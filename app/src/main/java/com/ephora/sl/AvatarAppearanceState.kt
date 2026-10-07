package com.ephora.sl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object AvatarAppearanceState {
  const val TEX_HEAD_BAKED = 8
  const val TEX_UPPER_BAKED = 9
  const val TEX_LOWER_BAKED = 10
  const val TEX_EYES_BAKED = 11
  const val TEX_SKIRT_BAKED = 19
  const val TEX_HAIR_BAKED = 20

  @Volatile var avatarId: String = ""
    private set
  @Volatile var isTrial: Boolean = false
    private set
  @Volatile var visualParams: ByteArray = ByteArray(0)
    private set
  @Volatile var appearanceVersion: Int = 0
    private set
  @Volatile var cofVersion: Int = 0
    private set
  @Volatile var appearanceFlags: Long = 0L
    private set
  @Volatile var hoverX: Float = 0f
    private set
  @Volatile var hoverY: Float = 0f
    private set
  @Volatile var hoverZ: Float = 0f
    private set
  @Volatile var textureEntryLength: Int = 0
    private set
  @Volatile var lastRxMs: Long = 0L
    private set
  @Volatile private var faces: List<String> = emptyList()
  @Volatile private var attachments: List<Attachment> = emptyList()

  data class Attachment(val id: String, val point: Int)

  fun texture(face: Int): String {
    if (face < 0) return ""
    val list = faces
    return if (face < list.size) list[face] else ""
  }

  fun textures(): List<String> = faces
  fun attachmentList(): List<Attachment> = attachments

  fun status(): String {
    val nonEmpty = faces.count { it.isNotEmpty() && it != NULL_UUID }
    return "AVATAR-APP id=" + avatarId.take(8) +
      " appearance=" + appearanceVersion +
      " cof=" + cofVersion +
      " visual=" + visualParams.size +
      " tex=" + nonEmpty +
      " attach=" + attachments.size +
      " rx=" + if (lastRxMs == 0L) "-" else (System.currentTimeMillis() - lastRxMs).toString() + "ms"
  }

  fun accept(payload: ByteArray): String? {
    return try {
      var o = 0
      if (payload.size < 19) return null
      val id = uuidString(payload, o)
      o += 16
      val trial = (payload[o].toInt() and 0xFF) != 0
      o++

      if (o + 2 > payload.size) return null
      val teLen = u16(payload, o)
      o += 2
      if (teLen < 0 || o + teLen > payload.size) return null
      val te = payload.copyOfRange(o, o + teLen)
      o += teLen

      var vp = ByteArray(0)
      if (o < payload.size) {
        val n = payload[o].toInt() and 0xFF
        o++
        if (n <= payload.size - o) {
          vp = payload.copyOfRange(o, o + n)
          o += n
        } else {
          o--
        }
      }

      var appVersion = 0
      var cof = 0
      var flags = 0L
      if (o < payload.size) {
        val blocks = payload[o].toInt() and 0xFF
        if (blocks in 0..8 && o + 1 + blocks * 9 <= payload.size) {
          o++
          if (blocks > 0) {
            appVersion = payload[o].toInt() and 0xFF
            o++
            cof = i32(payload, o)
            o += 4
            flags = i32u(payload, o)
            o += 4
          }
        }
      }

      var hx = 0f
      var hy = 0f
      var hz = 0f
      if (o < payload.size) {
        val blocks = payload[o].toInt() and 0xFF
        if (blocks in 0..2 && o + 1 + blocks * 12 <= payload.size) {
          o++
          if (blocks > 0) {
            hx = f32(payload, o); hy = f32(payload, o + 4); hz = f32(payload, o + 8)
            o += 12
          }
        }
      }

      val att = ArrayList<Attachment>()
      if (o < payload.size) {
        val blocks = payload[o].toInt() and 0xFF
        o++
        if (blocks in 0..32) {
          repeat(blocks) {
            if (o + 17 <= payload.size) {
              val aid = uuidString(payload, o)
              o += 16
              val point = payload[o].toInt() and 0xFF
              o++
              att.add(Attachment(aid, point))
            }
          }
        }
      }

      avatarId = id
      isTrial = trial
      visualParams = vp
      appearanceVersion = appVersion
      cofVersion = cof
      appearanceFlags = flags
      hoverX = hx
      hoverY = hy
      hoverZ = hz
      textureEntryLength = teLen
      faces = parseTextureEntryIds(te)
      attachments = att
      lastRxMs = System.currentTimeMillis()
      status()
    } catch (_: Throwable) {
      null
    }
  }

  private fun parseTextureEntryIds(data: ByteArray): List<String> {
    if (data.size < 16) return emptyList()
    val out = MutableList(32) { "" }
    var o = 0
    val defaultId = uuidString(data, o)
    for (i in out.indices) out[i] = defaultId
    o += 16

    fun readBitfield(): Long {
      var bits = 0L
      var width = 0
      while (o < data.size && width < 32) {
        val b = data[o].toInt() and 0xFF
        o++
        bits = (bits shl 7) or (b and 0x7F).toLong()
        width += 7
        if ((b and 0x80) == 0) break
      }
      return bits
    }

    while (o < data.size) {
      val bits = readBitfield()
      if (bits == 0L) break
      if (o + 16 > data.size) break
      val id = uuidString(data, o)
      o += 16
      for (face in 0 until 32) {
        if ((bits and (1L shl face)) != 0L) out[face] = id
      }
    }
    return out
  }

  private fun uuidString(data: ByteArray, off: Int): String {
    if (off < 0 || off + 16 > data.size) return ""
    return try {
      val bb = ByteBuffer.wrap(data, off, 16).order(ByteOrder.BIG_ENDIAN)
      UUID(bb.long, bb.long).toString()
    } catch (_: Throwable) { "" }
  }

  private fun u16(data: ByteArray, off: Int): Int =
    ByteBuffer.wrap(data, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

  private fun i32(data: ByteArray, off: Int): Int =
    ByteBuffer.wrap(data, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

  private fun i32u(data: ByteArray, off: Int): Long =
    i32(data, off).toLong() and 0xFFFFFFFFL

  private fun f32(data: ByteArray, off: Int): Float =
    ByteBuffer.wrap(data, off, 4).order(ByteOrder.LITTLE_ENDIAN).float

  private const val NULL_UUID = "00000000-0000-0000-0000-000000000000"
}
