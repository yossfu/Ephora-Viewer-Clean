package com.ephora.sl
object TerrainMesh {
  @Volatile var nPk = 0L
  @Volatile var nPatches = 0L
  @Volatile var nBad = 0L
  @Volatile var version = 0L
  @Volatile var minH = Float.NaN
  @Volatile var maxH = Float.NaN
  val H = FloatArray(256 * 256) { Float.NaN }
  private val gotPatch = BooleanArray(256)
  private var hexLogged = false
  private var patchLogged = false
  private var estadoT0 = 0L
  private var deq = FloatArray(256)
  private var cosT = FloatArray(256)
  private var copyM = IntArray(256)
  private var tablasOk = false
  // CITA terreno: tablas = OMV TerrainCompressor.cs:772 deq, :788-798 cosenos, :801-851 copy-zigzag (port viewer patch_idct)
  fun reset() {
    try { nPk = 0L; nPatches = 0L; nBad = 0L } catch(_: Throwable) {}
    try { for (i in H.indices) H[i] = Float.NaN } catch(_: Throwable) {}
    try { for (i in gotPatch.indices) gotPatch[i] = false } catch(_: Throwable) {}
    try { hexLogged = false; patchLogged = false; estadoT0 = 0L } catch(_: Throwable) {}
    try { version++ } catch(_: Throwable) {}
  }
  private fun tablas() {
    if (tablasOk) return
    for (j in 0 until 16) for (i in 0 until 16) deq[j * 16 + i] = 1f + 2f * (i + j).toFloat()
    val hposz = (Math.PI.toFloat() * 0.5f / 16f)
    for (u in 0 until 16) for (n in 0 until 16) cosT[u * 16 + n] = Math.cos(((2f * n + 1f) * u.toFloat() * hposz).toDouble()).toFloat()
    var diag = false
    var right = true
    var i = 0
    var j = 0
    var count = 0
    while (i < 16 && j < 16) {
      copyM[j * 16 + i] = count++
      if (!diag) {
        if (right) { if (i < 15) i++ else j++; right = false; diag = true }
        else { if (j < 15) j++ else i++; right = true; diag = true }
      } else {
        if (right) { i++; j--; if (i == 15 || j == 0) diag = false }
        else { i--; j++; if (j == 15 || i == 0) diag = false }
      }
    }
    tablasOk = true
  }
  // CITA terreno: bits MSB-first + bytes LE como OMV BitPack.cs:366-416 UnpackBitsArray + :193-226; header OMV TerrainCompressor.cs:361-380
  // stride=2049 BE era este bug: cable 08 01 da 264 solo con ensamblado LE (R4 verificado)
  private class BitReader(val d: ByteArray, var bytePos: Int) {
    var bitPos = 0
    private fun bit(): Int {
      if (bytePos >= d.size) throw RuntimeException("terreno-fin")
      val b = (d[bytePos].toInt() ushr (7 - bitPos)) and 1
      bitPos++
      if (bitPos >= 8) { bitPos = 0; bytePos++ }
      return b
    }
    fun unpack(n: Int): Int {
      var v = 0
      var k = n
      var bi = 0
      var cur = 0
      var filled = 0
      while (k > 0) {
        cur = (cur shl 1) or bit()
        filled++
        if (filled == 8) {
          v = v or (cur shl (bi * 8))
          bi++
          cur = 0
          filled = 0
        }
        k--
      }
      if (filled > 0) v = v or (cur shl (bi * 8))
      return v
    }
    fun unpackFloat(): Float {
      var v = 0
      for (bi in 0 until 4) {
        var cur = 0
        for (j in 0 until 8) cur = (cur shl 1) or bit()
        v = v or (cur shl (bi * 8))
      }
      return Float.fromBits(v)
    }
  }
  // CITA terreno: IDCT = OMV TerrainCompressor.cs:434-471 IDCTColumn16/IDCTLine16
  private fun idctCol(lin: FloatArray, lout: FloatArray, col: Int) {
    val s = 0.7071067811865475244008443621049f
    for (n in 0 until 16) {
      var total = s * lin[col]
      for (u in 1 until 16) total += lin[u * 16 + col] * cosT[u * 16 + n]
      lout[16 * n + col] = total
    }
  }
  private fun idctLine(lin: FloatArray, lout: FloatArray, line: Int) {
    val s = 0.7071067811865475244008443621049f
    val oosob = 2f / 16f
    val ls = line * 16
    for (n in 0 until 16) {
      var total = s * lin[ls]
      for (u in 1 until 16) total += lin[ls + u] * cosT[u * 16 + n]
      lout[ls + n] = total * oosob
    }
  }
  fun heightAt(x: Int, y: Int): Float {
    try {
      if (x < 0 || x > 255 || y < 0 || y > 255) return Float.NaN
      return H[y * 256 + x]
    } catch (_: Throwable) { return Float.NaN }
  }
  /** Fill a missing LandPatch sample from the nearest valid samples around the hole. */
  fun heightAtFilled(x: Int, y: Int, fallback: Float): Float {
    val direct = heightAt(x, y)
    if (direct.isFinite()) return direct
    fun horizontal(): Float? {
      var leftX = -1; var rightX = -1
      var leftH = Float.NaN; var rightH = Float.NaN
      for (d in 1..64) {
        if (leftX < 0 && x - d in 0..255) heightAt(x - d, y).takeIf { it.isFinite() }?.let { leftX = x - d; leftH = it }
        if (rightX < 0 && x + d in 0..255) heightAt(x + d, y).takeIf { it.isFinite() }?.let { rightX = x + d; rightH = it }
        if (leftX >= 0 && rightX >= 0) break
      }
      return when {
        leftX >= 0 && rightX >= 0 -> leftH + (rightH - leftH) * ((x - leftX).toFloat() / (rightX - leftX).toFloat())
        leftX >= 0 -> leftH
        rightX >= 0 -> rightH
        else -> null
      }
    }
    fun vertical(): Float? {
      var topY = -1; var bottomY = -1
      var topH = Float.NaN; var bottomH = Float.NaN
      for (d in 1..64) {
        if (topY < 0 && y - d in 0..255) heightAt(x, y - d).takeIf { it.isFinite() }?.let { topY = y - d; topH = it }
        if (bottomY < 0 && y + d in 0..255) heightAt(x, y + d).takeIf { it.isFinite() }?.let { bottomY = y + d; bottomH = it }
        if (topY >= 0 && bottomY >= 0) break
      }
      return when {
        topY >= 0 && bottomY >= 0 -> topH + (bottomH - topH) * ((y - topY).toFloat() / (bottomY - topY).toFloat())
        topY >= 0 -> topH
        bottomY >= 0 -> bottomH
        else -> null
      }
    }
    val h = horizontal()
    val v = vertical()
    return when {
      h != null && v != null -> (h + v) * 0.5f
      h != null -> h
      v != null -> v
      else -> fallback
    }
  }
  fun meanH(): Float {
    try {
      var s = 0.0
      var n = 0
      for (v in H) if (v.isFinite()) { s += v; n++ }
      if (n == 0) return Float.NaN
      return (s / n).toFloat()
    } catch (_: Throwable) { return Float.NaN }
  }
  fun patchesGot(): Int {
    try {
      var n = 0
      for (b in gotPatch) if (b) n++
      return n
    } catch (_: Throwable) { return 0 }
  }
  fun coverageStatus(limit: Int = 12): String {
    try {
      val missing = ArrayList<String>()
      var count = 0
      for (py in 0 until 16) for (px in 0 until 16) {
        if (!gotPatch[py * 16 + px]) {
          count++
          if (missing.size < limit) missing.add("$px,$py")
        }
      }
      return "holes=$count" + if (missing.isEmpty()) "" else "[${missing.joinToString(";")}]"
    } catch (_: Throwable) { return "holes=?" }
  }
  @Volatile var nTerraZero = 0L
  private var hexLine: String? = null
  private fun zeroExpandLocal(data: ByteArray): ByteArray {
    try {
      val out = mutableListOf<Byte>()
      var i = 0
      while (i < data.size) {
        val b = data[i].toInt() and 0xFF
        if (b == 0 && i + 1 < data.size) {
          val n = data[i + 1].toInt() and 0xFF
          repeat(n) { out.add(0) }
          i += 2
        } else { out.add(data[i]); i++ }
      }
      return out.toByteArray()
    } catch (_: Throwable) { return data }
  }
  fun ingest(payload: ByteArray): String? {
    return ingestInner(payload, false)
  }
  private fun ingestInner(payload: ByteArray, retried: Boolean): String? {
    try {
      tablas()
      nPk++
      if (payload.size < 4) { nBad++; return null }
      val type = payload[0].toInt() and 0xFF
      if (type != 0x4C) {
        nBad++
        if (!hexLogged) { hexLogged = true; return "TERRA-TIPO type=" + type + " (no-Land, se ignora)" }
        return null
      }
      val dlen = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
      if (3 + dlen > payload.size) { nBad++; return null }
      val r = BitReader(payload, 3)
      val stride = r.unpack(16)
      val psize = r.unpack(8)
      val ltype = r.unpack(8)
      if (!(stride == 264 && psize == 16 && ltype == 0x4C)) {
        if (!retried) {
          try {
            val z = zeroExpandLocal(payload)
            if (z.size != payload.size) {
              try { nTerraZero++ } catch (_: Throwable) {}
              return ingestInner(z, true)
            }
          } catch (_: Throwable) {}
        }
        nBad++
        if (!hexLogged) { hexLogged = true; return "TERRA-MAL stride=" + stride + " psize=" + psize + " ltype=" + ltype + " dlen=" + dlen }
        return null
      }
      if (!hexLogged) {
        hexLogged = true
        hexLine = "TERRA-HEX stride=" + stride + " psize=" + psize + " ltype=" + ltype + " dlen=" + dlen
      }
      var guard = 40
      while (guard > 0) {
        guard--
        val qw = r.unpack(8)
        if (qw == 97) break
        val dc = r.unpackFloat()
        val range = r.unpack(16)
        val ids = r.unpack(10)
        val wb = (qw and 0x0F) + 2
        val px = ids shr 5
        val py = ids and 0x1F
        if (px >= 16 || py >= 16) { nBad++; break }
        val co = IntArray(256)
        var n = 0
        while (n < 256) {
          val b1 = r.unpack(1)
          if (b1 == 0) { co[n] = 0; n++ }
          else {
            val b2 = r.unpack(1)
            if (b2 == 0) { while (n < 256) { co[n] = 0; n++ } }
            else {
              val b3 = r.unpack(1)
              val mag = r.unpack(wb)
              co[n] = if (b3 != 0) -mag else mag
              n++
            }
          }
        }
        // CITA terreno: mult/addval = OMV TerrainCompressor.cs:631-637 DecompressPatch
        val prequant = (qw shr 4) + 2
        val quantize = 1 shl prequant
        val mult = (1f / quantize.toFloat()) * range.toFloat()
        val addval = mult * (1 shl (prequant - 1)).toFloat() + dc
        val block = FloatArray(256)
        for (k in 0 until 256) block[k] = co[copyM[k]] * deq[k]
        val ft = FloatArray(256)
        for (o in 0 until 16) idctCol(block, ft, o)
        for (o in 0 until 16) idctLine(ft, block, o)
        var zmin = Float.MAX_VALUE
        var zmax = -Float.MAX_VALUE
        for (jy in 0 until 16) for (ix in 0 until 16) {
          var h = block[jy * 16 + ix] * mult + addval
          if (!h.isFinite()) { try { nBad++ } catch (_: Throwable) {}; continue }
          h = h.coerceIn(-200f, 2000f)
          H[(py * 16 + jy) * 256 + (px * 16 + ix)] = h
          if (h < zmin) zmin = h
          if (h > zmax) zmax = h
          if (!minH.isFinite() || h < minH) minH = h
          if (!maxH.isFinite() || h > maxH) maxH = h
        }
        gotPatch[py * 16 + px] = true
        nPatches++
        version++
        if (!patchLogged && zmin <= zmax) {
          patchLogged = true
          return "TERRA-PATCH x=" + px + " y=" + py + " zmin=" + "%.1f".format(zmin) + " zmax=" + "%.1f".format(zmax)
        }
      }
      val now = System.currentTimeMillis()
      if (now - estadoT0 >= 30000L) {
        estadoT0 = now
        return "TERRA-ESTADO pk=" + nPk + " patches=" + patchesGot() + "/256 min=" + minH + " max=" + maxH + " zeroFix=" + nTerraZero
      }
      val hx = hexLine
      hexLine = null
      return hx
    } catch (e: Throwable) {
      try { nBad++ } catch (_: Throwable) {}
      return null
    }
  }
}
