package com.ephora.sl

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives Second Life texture assets from either HTTP or UDP and produces
 * decoded Android Bitmaps for the renderer.
 *
 * UDP semantics follow ImageData packet 0 + ImagePacket N. ImagePacket data is
 * retained by packet number so out-of-order delivery cannot corrupt the J2C stream.
 */
object ImageAssets {
  private const val J2C_IMAGE_CODEC = 2
  private const val MAX_COMPRESSED = 16 * 1024 * 1024
  private const val MAX_BITMAPS = 192
  private const val MAX_RAW_BYTES = 32L * 1024L * 1024L
  private const val MAX_PENDING = 16
  private const val UDP_STALL_MS = 12_000L
  private const val UDP_RETRY_MS = 8_000L

  private data class Pending(
    var expected: Int = 0,
    var codec: Int = 0,
    var packetCount: Int = 0,
    val parts: TreeMap<Int, ByteArray> = TreeMap(),
    var receivedBytes: Int = 0,
    var lastReceivedMs: Long = 0L,
    var lastRetryMs: Long = 0L,
    var retries: Int = 0
  ) {
    fun addPart(packet: Int, bytes: ByteArray) {
      if (packet < 0 || bytes.isEmpty()) return
      val old = parts.put(packet, bytes)
      if (old != null) receivedBytes -= old.size
      receivedBytes += bytes.size
    }
  }

  private val pending = LinkedHashMap<String, Pending>()
  private val bitmaps = object : LinkedHashMap<String, Bitmap>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
      if (size > MAX_BITMAPS) {
        try { eldest.value.recycle() } catch (_: Throwable) {}
        return true
      }
      return false
    }
  }
  private val raw = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {}
  private var rawBytes = 0L
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
  @Volatile var httpCompleteCount = 0L
    private set
  @Volatile var lastResult = "idle"
    private set

  @Synchronized fun resetSession() {
    pending.clear()
    raw.clear()
    rawBytes = 0L
    decoding.clear()
    for (bitmap in bitmaps.values) {
      try { bitmap.recycle() } catch (_: Throwable) {}
    }
    bitmaps.clear()
    packetCount = 0L
    completeCount = 0L
    decodedCount = 0L
    failedCount = 0L
    httpCompleteCount = 0L
    lastResult = "reset"
  }

  fun accept(messageId: Int, payload: ByteArray): String? {
    if (messageId != 9 && messageId != 10) return null
    val minimum = if (messageId == 9) 25 else 20
    if (payload.size < minimum) return null
    val uuid = uuidAt(payload) ?: return null
    val now = System.currentTimeMillis()
    var completed: ByteArray? = null
    var codec = J2C_IMAGE_CODEC

    synchronized(this) {
      packetCount++
      val transfer = pending.getOrPut(uuid) { Pending(lastReceivedMs = now) }
      transfer.lastReceivedMs = now

      if (messageId == 9) {
        val imageCodec = payload[16].toInt() and 0xff
        val size = u32(payload, 17)
        val packets = u16(payload, 21)
        val length = u16(payload, 23)
        if (size <= 0 || size > MAX_COMPRESSED || length <= 0 || 25 + length > payload.size) return null
        // A new ImageData starts a fresh Lumiya-style transfer from packet 0.
        // Discard fragments belonging to an earlier timed-out attempt so stale
        // packets can never complete or corrupt the new stream.
        transfer.parts.clear()
        transfer.receivedBytes = 0
        transfer.expected = size
        transfer.codec = imageCodec
        transfer.packetCount = packets
        transfer.addPart(0, payload.copyOfRange(25, 25 + length))
      } else {
        val packet = u16(payload, 16)
        val length = u16(payload, 18)
        if (packet < 1 || length <= 0 || 20 + length > payload.size) return null
        transfer.addPart(packet, payload.copyOfRange(20, 20 + length))
      }

      codec = transfer.codec
      if (transfer.expected > 0 && transfer.parts.containsKey(0) && transfer.receivedBytes >= transfer.expected) {
        completed = assemble(transfer)
        if (completed != null) {
          pending.remove(uuid)
          completeCount++
          putRawLocked(uuid, completed!!)
        }
      }

      val expired = pending.entries
        .filter { now - it.value.lastReceivedMs > 60_000L }
        .map { it.key }
      for (id in expired) pending.remove(id)
      while (pending.size > MAX_PENDING) {
        val first = pending.keys.firstOrNull() ?: break
        pending.remove(first)
      }
    }

    if (completed != null) {
      decode(uuid, codec, completed!!)
      return "TEX-UDP-ENSAMBLADA id=" + uuid.take(8) + " bytes=" + completed!!.size + " codec=" + codec
    }
    return null
  }

  private fun assemble(transfer: Pending): ByteArray? {
    val out = ByteArray(transfer.expected)
    var offset = 0
    var packet = 0
    while (offset < transfer.expected) {
      val data = transfer.parts[packet] ?: return null
      val take = minOf(data.size, transfer.expected - offset)
      System.arraycopy(data, 0, out, offset, take)
      offset += take
      packet++
    }
    return if (offset == transfer.expected) out else null
  }

  fun acceptHttpJ2c(uuid: String, data: ByteArray): Boolean {
    val key = uuid.lowercase()
    if (data.isEmpty() || data.size > MAX_COMPRESSED) return false
    synchronized(this) {
      if (bitmaps.containsKey(key)) return true
      putRawLocked(key, data)
      httpCompleteCount++
    }
    decode(key, J2C_IMAGE_CODEC, data)
    return true
  }

  private fun decode(uuid: String, codec: Int, compressed: ByteArray) {
    synchronized(this) {
      if (!decoding.add(uuid)) return
    }
    scope.launch {
      try {
        if (codec != J2C_IMAGE_CODEC) throw IllegalArgumentException("codec=" + codec)
        val pixels = J2kDecoder.decodeNative(compressed) ?: throw IllegalStateException("OpenJPEG-rechazo-J2C")
        if (pixels.size < 8) throw IllegalStateException("salida-corta")
        val bb = ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN)
        val width = bb.int
        val height = bb.int
        if (width < 1 || height < 1 || width > 4096 || height > 4096) throw IllegalStateException("dimensiones=" + width + "x" + height)
        val needed = 8L + width.toLong() * height.toLong() * 4L
        if (needed > pixels.size.toLong()) throw IllegalStateException("rgba-corta=" + pixels.size)
        val argb = IntArray(width * height)
        var offset = 8
        for (i in argb.indices) {
          val r = pixels[offset++].toInt() and 255
          val g = pixels[offset++].toInt() and 255
          val b = pixels[offset++].toInt() and 255
          val a = pixels[offset++].toInt() and 255
          argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        synchronized(this@ImageAssets) {
          bitmaps[uuid] = bitmap
          decodedCount++
          decoding.remove(uuid)
          lastResult = "ok:" + uuid + ":" + width + "x" + height
        }
        try { AgentLoop.onTick?.invoke("TEX-J2K-OK id8=" + uuid.take(8) + " size=" + width + "x" + height) } catch (_: Throwable) {}
      } catch (e: Throwable) {
        synchronized(this@ImageAssets) {
          failedCount++
          decoding.remove(uuid)
          lastResult = "fail:" + uuid.take(8) + ":" + (e.message ?: e.javaClass.simpleName)
        }
        try { AgentLoop.onTick?.invoke("TEX-J2K-FAIL id8=" + uuid.take(8) + " cause=" + (e.message ?: e.javaClass.simpleName)) } catch (_: Throwable) {}
      }
    }
  }

  @Synchronized private fun putRawLocked(uuid: String, data: ByteArray) {
    val old = raw.put(uuid, data)
    if (old != null) rawBytes -= old.size.toLong()
    rawBytes += data.size.toLong()
    while (rawBytes > MAX_RAW_BYTES && raw.isNotEmpty()) {
      val first = raw.entries.first()
      if (first.key == uuid && raw.size == 1) break
      rawBytes -= first.value.size.toLong()
      raw.remove(first.key)
    }
  }

  @Synchronized fun bitmap(uuid: String): Bitmap? = bitmaps[uuid.lowercase()]
  @Synchronized fun has(uuid: String): Boolean = bitmaps.containsKey(uuid.lowercase())
  @Synchronized fun bitmapCount(): Int = bitmaps.size
  @Synchronized fun rawCount(): Int = raw.size
  @Synchronized fun pendingCount(): Int = pending.size
  @Synchronized fun hasPending(uuid: String): Boolean = pending.containsKey(uuid.lowercase())

  /**
   * Lumiya-compatible UDP recovery: restart the entire transfer from Packet=0.
   * Individual ImagePacket retransmission is intentionally avoided here.
   */
  @Synchronized fun missingRequests(limit: Int = 2): List<Pair<String, Int>> {
    val now = System.currentTimeMillis()
    val result = ArrayList<Pair<String, Int>>(limit)
    for ((uuid, transfer) in pending) {
      if (result.size >= limit) break
      if (transfer.expected <= 0) continue
      if (now - transfer.lastReceivedMs < UDP_STALL_MS) continue
      if (now - transfer.lastRetryMs < UDP_RETRY_MS) continue
      if (transfer.retries >= 2) continue
      transfer.retries++
      transfer.lastRetryMs = now
      result.add(uuid to 0)
    }
    return result
  }

  @Synchronized fun pendingTop(): String {
    val ids = pending.entries.take(8).map { e ->
      var missing = 1
      while (missing <= e.value.packetCount && e.value.parts.containsKey(missing)) missing++
      e.key.take(8) + "=" + e.value.receivedBytes + "/" + e.value.expected +
        "/pk=" + e.value.packetCount + "/miss=" + missing
    }
    return "IMAGE-PEND-DET n=" + pending.size + " ids=" + if (ids.isEmpty()) "-" else ids.joinToString(",")
  }

  @Synchronized fun keySummary(limit: Int = 10): String {
    val keys = bitmaps.keys.take(limit).map { it.take(8) }
    return if (keys.isEmpty()) "-" else keys.joinToString(",")
  }

  @Synchronized fun touchIds(ids: List<String>) {}

  @Synchronized fun status(): String {
    return "TEX-ASSETS packets=" + packetCount +
      " complete=" + completeCount +
      " decoded=" + decodedCount +
      " failed=" + failedCount +
      " httpComplete=" + httpCompleteCount +
      " cache=" + bitmaps.size +
      " raw=" + raw.size +
      " rawMB=" + "%.1f".format(rawBytes.toDouble() / 1048576.0) +
      " pending=" + pending.size +
      " keys=" + keySummary() +
      " last=" + lastResult
  }

  private fun uuidAt(bytes: ByteArray): String? {
    if (bytes.size < 16) return null
    val h = UdpCircuit.hexPrev(bytes, 16).lowercase()
    return try {
      UUID.fromString(
        h.substring(0, 8) + "-" + h.substring(8, 12) + "-" +
          h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20, 32)
      ).toString()
    } catch (_: Throwable) { null }
  }

  private fun u16(bytes: ByteArray, offset: Int): Int =
    if (offset + 1 < bytes.size)
      (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    else -1

  private fun u32(bytes: ByteArray, offset: Int): Int =
    if (offset + 3 < bytes.size)
      (bytes[offset].toInt() and 255) or
        ((bytes[offset + 1].toInt() and 255) shl 8) or
        ((bytes[offset + 2].toInt() and 255) shl 16) or
        ((bytes[offset + 3].toInt() and 255) shl 24)
    else -1
}

object J2kDecoder {
  init { NdkCore.helloSafe() }
  @JvmStatic external fun decodeNative(data: ByteArray): ByteArray?
}
