package com.ephora.sl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Reassembles the simulator's ImageData/ImagePacket stream and turns JPEG2000 into GLES-ready pixels. */
object ImageAssets {
  private const val J2C_IMAGE_CODEC = 2
  private const val MAX_COMPRESSED = 16 * 1024 * 1024
  private const val MAX_BITMAPS = 256
  private const val MAX_PENDING = 384
  private data class Pending(var expected: Int = 0, var codec: Int = 0, var packetCount: Int = 0, val parts: TreeMap<Int, ByteArray> = TreeMap(), var touched: Long = 0L, var queued: Boolean = false, var lastRequestedPacket: Int = -1, var lastRequestMs: Long = 0L) {
    fun byteCount(): Int = parts.values.sumOf { it.size }
  }
  private val pending = LinkedHashMap<String, Pending>()
  private val bitmaps = LinkedHashMap<String, Bitmap>()
  private val decoding = HashSet<String>()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile var packetCount = 0L
    private set
  @Volatile var completeCount = 0L
    private set
  @Volatile var decodedCount = 0L
    private set
  @Volatile var failedCount = 0L
    private set
  @Volatile var lastResult = "idle"
    private set

  @Synchronized fun resetSession() {
    pending.clear(); decoding.clear()
    for (b in bitmaps.values) { try { b.recycle() } catch (_: Throwable) {} }
    bitmaps.clear()
    packetCount = 0; completeCount = 0; decodedCount = 0; failedCount = 0; lastResult = "reset"
  }

  fun accept(messageId: Int, payload: ByteArray): String? {
    if (messageId != 9 && messageId != 10) return null
    if (payload.size < if (messageId == 9) 25 else 20) return null
    val uuid = uuidAt(payload) ?: return null
    val now = System.currentTimeMillis()
    var completed: ByteArray? = null
    var doneId = uuid
    var codec = 0
    synchronized(this) {
      packetCount++
      val entry = pending.getOrPut(uuid) { Pending(touched = now) }
      entry.touched = now
      val dataOff: Int
      val sequence: Int
      if (messageId == 9) {
        codec = payload[16].toInt() and 0xff
        val size = u32(payload, 17)
        val packetN = u16(payload, 21)
        val len = u16(payload, 23)
        if (size <= 0 || size > MAX_COMPRESSED || len < 0 || 25 + len > payload.size) return null
        entry.expected = size
        entry.codec = codec
        entry.packetCount = packetN
        sequence = 0
        dataOff = 25
        // Retain expected additional packet count for diagnosis only; byte size defines completion.
        if (packetN > 0 && entry.parts.isEmpty()) entry.parts[-1] = byteArrayOf((packetN and 0xff).toByte())
      } else {
        sequence = u16(payload, 16).coerceAtLeast(1)
        val len = u16(payload, 18)
        if (len < 0 || 20 + len > payload.size) return null
        dataOff = 20
      }
      val len = u16(payload, dataOff - 2)
      if (len <= 0 || dataOff + len > payload.size) return null
      entry.parts[sequence] = payload.copyOfRange(dataOff, dataOff + len)
      // Remove the private packet-count marker before calculating assembled byte length.
      if (entry.parts[-1]?.size == 1) entry.parts.remove(-1)
      if (entry.expected > 0 && entry.byteCount() >= entry.expected && entry.parts.containsKey(0) && !entry.queued) {
        val out = ByteArray(entry.expected)
        var offset = 0
        var expectedSequence = 0
        var contiguous = true
        for ((partNo, bytes) in entry.parts) {
          if (partNo != expectedSequence) { contiguous = false; break }
          expectedSequence++
          if (offset < out.size) {
            val take = minOf(bytes.size, out.size - offset)
            System.arraycopy(bytes, 0, out, offset, take)
            offset += take
          }
        }
        if (contiguous && offset >= entry.expected) {
          entry.queued = true
          completed = out
          codec = entry.codec
          completeCount++
          pending.remove(uuid)
        }
      }
      while (pending.size > MAX_PENDING) pending.remove(pending.minByOrNull { it.value.touched }?.key)
      val old = pending.filterValues { now - it.touched > 30000L }.keys.toList()
      for (id in old) pending.remove(id)
    }
    if (completed != null) decode(doneId, codec, completed!!)
    return if (completed != null) "TEX-UDP-ENSAMBLADA id=${uuid.take(8)} bytes=${completed!!.size} codec=$codec" else null
  }

  /** Accept a complete GetTexture HTTP response (JPEG2000/J2C) using the same decoder/cache as UDP. */
  fun acceptHttpJ2c(uuid: String, data: ByteArray): Boolean {
    val key = uuid.lowercase()
    if (data.isEmpty() || data.size > MAX_COMPRESSED) return false
    synchronized(this) {
      if (bitmaps.containsKey(key)) return true
      if (decoding.contains(key)) return false
    }
    decode(key, J2C_IMAGE_CODEC, data)
    return true
  }

  private fun decode(uuid: String, codec: Int, compressed: ByteArray) {
    synchronized(this) { if (!decoding.add(uuid)) return }
    scope.launch {
      try {
        if (codec != J2C_IMAGE_CODEC) throw IllegalArgumentException("codec=$codec")
        val raw = J2kDecoder.decodeNative(compressed) ?: throw IllegalStateException("OpenJPEG-rechazo-J2C")
        if (raw.size < 8) throw IllegalStateException("salida-corta")
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.int; val h = bb.int
        if (w < 1 || h < 1 || w > 4096 || h > 4096 || 8L + w.toLong() * h * 4L > raw.size) throw IllegalStateException("dimensiones=${w}x${h}")
        val argb = IntArray(w * h)
        var o = 8
        for (i in argb.indices) {
          val r = raw[o++].toInt() and 255; val g = raw[o++].toInt() and 255; val b = raw[o++].toInt() and 255; val a = raw[o++].toInt() and 255
          argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        synchronized(this@ImageAssets) {
          bitmaps[uuid] = bitmap
          while (bitmaps.size > MAX_BITMAPS) {
            val eldest = bitmaps.keys.firstOrNull() ?: break
            if (eldest == uuid && bitmaps.size == 1) break
            bitmaps.remove(eldest)?.recycle()
          }
          decodedCount++
          lastResult = "ok:$uuid:${w}x$h"
          decoding.remove(uuid)
        }
        try { AgentLoop.onTick?.invoke("TEX-J2K-OK id=${uuid.take(8)} size=${w}x$h rgba=${raw.size - 8}") } catch (_: Throwable) {}
      } catch (e: Throwable) {
        synchronized(this@ImageAssets) { failedCount++; lastResult = "fail:${uuid.take(8)}:${e.message}"; decoding.remove(uuid) }
        try { AgentLoop.onTick?.invoke("TEX-J2K-FAIL id=${uuid.take(8)} codec=$codec cause=${e.message ?: e.javaClass.simpleName}") } catch (_: Throwable) {}
      }
    }
  }

  @Synchronized fun bitmap(uuid: String): Bitmap? = bitmaps[uuid.lowercase()]
  @Synchronized fun has(uuid: String): Boolean = bitmaps.containsKey(uuid.lowercase())
  @Synchronized fun pendingTop(): String {
    val ids = pending.entries.take(12).map { e -> e.key.take(8) + "=" + e.value.byteCount() + "/" + e.value.expected + "/pk=" + e.value.packetCount + "/miss=" + (if (e.value.packetCount > 0) (1..e.value.packetCount).firstOrNull { !e.value.parts.containsKey(it) } ?: -1 else -1) }
    return "IMAGE-PEND-DET n=" + pending.size + " ids=" + (if (ids.isEmpty()) "-" else ids.joinToString(","))
  }
  /** Returns missing ImagePacket numbers that should be explicitly re-requested. */
  @Synchronized fun missingRequests(limit: Int = 8, minIntervalMs: Long = 1200L): List<Pair<String, Int>> {
    val now = System.currentTimeMillis()
    val out = ArrayList<Pair<String, Int>>()
    for ((uuid, e) in pending) {
      if (out.size >= limit) break
      if (now - e.lastRequestMs < minIntervalMs) continue
      val first = if (e.packetCount > 0) {
        var m = -1
        for (n in 1..e.packetCount) if (!e.parts.containsKey(n)) { m = n; break }
        m
      } else if (e.expected > 0 && !e.parts.containsKey(0)) 0 else -1
      if (first >= 0) {
        e.lastRequestedPacket = first
        e.lastRequestMs = now
        out.add(uuid to first)
      }
    }
    return out
  }

  @Synchronized fun touchIds(ids: List<String>) {
    val now = System.currentTimeMillis()
    for (u in ids) {
      try { pending[u]?.touched = now } catch (_: Throwable) {}
    }
  }
  @Synchronized fun keySummary(limit: Int = 10): String {
    val keys = bitmaps.keys.take(limit).map { it.take(8) }
    return if (keys.isEmpty()) "-" else keys.joinToString(",")
  }

  @Synchronized fun status(): String = "TEX-ASSETS packets=$packetCount complete=$completeCount decoded=$decodedCount failed=$failedCount cache=${bitmaps.size} pending=${pending.size} keys=${keySummary()} last=$lastResult"

  private fun uuidAt(b: ByteArray): String? {
    if (b.size < 16) return null
    val h = UdpCircuit.hexPrev(b, 16).lowercase()
    return try { UUID.fromString(h.substring(0,8)+"-"+h.substring(8,12)+"-"+h.substring(12,16)+"-"+h.substring(16,20)+"-"+h.substring(20,32)).toString() } catch (_: Throwable) { null }
  }
  private fun u16(b: ByteArray, o: Int): Int = if (o + 1 < b.size) (b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8) else -1
  private fun u32(b: ByteArray, o: Int): Int = if (o + 3 < b.size) ((b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8) or ((b[o+2].toInt() and 255) shl 16) or ((b[o+3].toInt() and 255) shl 24)) else -1
}

object J2kDecoder {
  init { NdkCore.helloSafe() }
  @JvmStatic external fun decodeNative(data: ByteArray): ByteArray?
}
