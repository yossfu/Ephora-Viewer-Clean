package com.ephora.sl

/** Terrain texture identifiers and height bands advertised by RegionHandshake. */
object TerrainComposition {
  private const val NULL_UUID = "00000000-0000-0000-0000-000000000000"
  @Volatile private var details: List<String> = emptyList()
  @Volatile private var starts: List<Float> = emptyList()
  @Volatile private var ranges: List<Float> = emptyList()
  @Volatile var lastError: String? = null
    private set

  fun textureIds(): List<String> = details.filter { it != NULL_UUID }
  fun detailTextures(): List<String> = details.map { if (it == NULL_UUID) "" else it }
  fun startHeights(): List<Float> = starts
  fun heightRanges(): List<Float> = ranges
  fun baseTexture(): String = details.firstOrNull { it != NULL_UUID }.orEmpty()
  fun status(): String = "TERRAIN-TEX ids=${details.count { it != NULL_UUID }} base=${baseTexture().take(8).ifEmpty { "-" }} bands=${starts.size}/4 err=${lastError ?: "-"}"

  /** RegionHandshake: parse the fixed RegionInfo block after LLUDP message ID removal. */
  @Synchronized fun accept(payload: ByteArray): String? {
    try {
      var p = 0
      fun need(n: Int) { require(n >= 0 && p + n <= payload.size) { "truncated@${p}+${n}" } }
      fun skip(n: Int) { need(n); p += n }
      fun readU8(): Int { need(1); return payload[p++].toInt() and 0xff }
      fun readF32(): Float { need(4); val v = java.nio.ByteBuffer.wrap(payload, p, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float; p += 4; return v }
      fun readUuid(): String {
        need(16)
        val h = payload.copyOfRange(p, p + 16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        p += 16
        return "${h.substring(0,8)}-${h.substring(8,12)}-${h.substring(12,16)}-${h.substring(16,20)}-${h.substring(20,32)}"
      }
      skip(4) // RegionFlags U32
      skip(1) // SimAccess U8
      val nameLen = readU8()
      skip(nameLen) // SimName Variable 1
      skip(16) // SimOwner LLUUID
      skip(1) // IsEstateManager BOOL
      skip(4) // WaterHeight F32
      skip(4) // BillableFactor F32
      skip(16) // CacheID LLUUID
      val ids = (0 until 8).map { readUuid() }
      val hs = (0 until 4).map { readF32() }
      val rs = (0 until 4).map { readF32() }
      require(ids.size == 8 && hs.all { it.isFinite() } && rs.all { it.isFinite() }) { "invalid-composition" }
      details = ids.drop(4)
      starts = hs
      ranges = rs
      lastError = null
      return status()
    } catch (e: Throwable) {
      lastError = e.message ?: e.javaClass.simpleName
      return "TERRAIN-HANDSHAKE-ERROR ${lastError}"
    }
  }
}
