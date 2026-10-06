package com.ephora.sl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
object PrimDecoder {
  data class Prim(val id: Long, var tipo: Int, var x: Double, var y: Double, var z: Double, var sx: Float, var sy: Float, var sz: Float, var yaw: Float, var seen: Long, var mat: Int = -1, var tex: String = "", var texScaleS: Float = 1f, var texScaleT: Float = 1f, var texOffsetS: Float = 0f, var texOffsetT: Float = 0f, var texRotation: Float = 0f, var texR: Float = 1f, var texG: Float = 1f, var texB: Float = 1f, var texA: Float = 1f)
  var nTerse = 0L
  var nComp = 0L
  var nFull = 0L
  var nCached = 0L
  var nKill = 0L
  var nObjTerse = 0L
  var nObjComp = 0L
  var nObjFull = 0L
  var nFueraRango = 0L
  var nLenMalo = 0L
  var nEscMala = 0L
  var nZeroFix = 0L
  var nTerseQ = 0L
  var nTerseF = 0L
  private val hexFam = LinkedHashSet<Int>()
  private val recs = LinkedHashMap<Long, Prim>()
  private val famShown = LinkedHashSet<Int>()
  private var lastSum = 0L
  fun reset() {
    try {
      synchronized(recs) { recs.clear() }
    } catch(_: Throwable) {}
    try { famShown.clear() } catch(_: Throwable) {}
    nTerse = 0L
    nComp = 0L
    nFull = 0L
    nCached = 0L
    nKill = 0L
    nObjTerse = 0L
    nObjComp = 0L
    nObjFull = 0L
    nFueraRango = 0L
    nLenMalo = 0L
    nEscMala = 0L
    nZeroFix = 0L
    nTerseQ = 0L
    nTerseF = 0L
    vecLogged = false
    vecMemo = null
    vecTaken = false
    r3latch = ""
    vecLatch = ""
    lastPutReject = ""
    firstTerseId = -1L
    firstTerseX = Double.NaN
    firstTerseY = Double.NaN
    firstTerseZ = Double.NaN
    nKillHit = 0L
    nKillMiss = 0L
    fueraMuestra = ""
    try { fullQ.clear() } catch(_: Throwable) {}
    nFullQ = 0
    nFullTot = 0L
    try { brHist.clear() } catch(_: Throwable) {}
    try { extraQ.clear() } catch(_: Throwable) {}
    nAttach = 0L
    attachMuestra = null
    attachArmed = true
    sixtyLine = null
    try { pendingTexQ.clear() } catch(_: Throwable) {}
    texEmitTotal = 0L
    texEmitSesion = 0L
    try { texIds.clear() } catch(_: Throwable) {}
    lastKillId = -1L
    try { hexFam.clear() } catch(_: Throwable) {}
    lastSum = 0L
  }
  fun count(): Int {
    try {
      synchronized(recs) { return recs.size }
    } catch(_: Throwable) {}
    return 0
  }
  private fun u16at(p: ByteArray, o: Int): Int {
    return (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8)
  }
  // CITA-LE u16at: U16 cable LE (OMV PacketsBig.cs:76326 TimeDilation, :76359 TextureEntry)
  private fun u16f(v: Int, lo: Float, hi: Float): Float {
    return lo + (hi - lo) * (v.toFloat() / 65535f)
  }
  private fun yawQuat(qx: Float, qy: Float, qz: Float, qw: Float): Float {
    return Math.atan2((2.0 * (qw * qz + qx * qy)).toDouble(), (1.0 - 2.0 * (qy * qy + qz * qz)).toDouble()).toFloat()
  }
  private fun yawVec(x: Float, y: Float, z: Float): Float {
    val w2 = 1f - x * x - y * y - z * z
    val w = Math.sqrt(w2.coerceAtLeast(0f).toDouble()).toFloat()
    return yawQuat(x, y, z, w)
  }
  private fun normS(v: Float): Float {
    if (!v.isFinite()) return 1f
    if (v <= 0f) return 1f
    if (v > 1024f) return 1024f
    return v
  }
  private fun skipVar(p: ByteArray, o: Int, wide: Boolean): Int {
    if (wide) {
      if (o + 2 > p.size) return -1
      val n = u16at(p, o)
      if (o + 2 + n > p.size) return -1
      return o + 2 + n
    }
    if (o + 1 > p.size) return -1
    val n = p[o].toInt() and 0xFF
    if (o + 1 + n > p.size) return -1
    return o + 1 + n
  }
  private fun skipGet(p: ByteArray, o: Int, wide: Boolean): Pair<Int, ByteArray> {
    try {
      if (wide) {
        if (o + 2 > p.size) return Pair(-1, ByteArray(0))
        val n = u16at(p, o)
        if (o + 2 + n > p.size) return Pair(-1, ByteArray(0))
        return Pair(o + 2 + n, p.copyOfRange(o + 2, o + 2 + n))
      }
      if (o + 1 > p.size) return Pair(-1, ByteArray(0))
      val n = p[o].toInt() and 0xFF
      if (o + 1 + n > p.size) return Pair(-1, ByteArray(0))
      return Pair(o + 1 + n, p.copyOfRange(o + 1, o + 1 + n))
    } catch (_: Throwable) { return Pair(-1, ByteArray(0)) }
  }
  private data class TextureEntryFields(val uuid: String, val scaleS: Float, val scaleT: Float, val offsetS: Float, val offsetT: Float, val rotation: Float, val r: Float, val g: Float, val b: Float, val a: Float)
  // SL TextureEntry fields use one default value followed by variable-length face masks and overrides.
  private fun parseTextureEntry(raw: ByteArray): TextureEntryFields? {
    try {
      var o = 0
      fun field(size: Int): ByteArray? {
        if (size <= 0 || o + size > raw.size) return null
        val base = raw.copyOfRange(o, o + size)
        o += size
        var guard = 0
        while (o < raw.size && guard++ < 128) {
          var flags = 0L
          var more: Boolean
          var bytes = 0
          do {
            if (o >= raw.size || bytes++ >= 10) return null
            val v = raw[o++].toInt() and 255
            flags = (flags shl 7) or (v and 0x7f).toLong()
            more = (v and 0x80) != 0
          } while (more)
          if (flags == 0L) return base
          if (o + size > raw.size) return null
          o += size
        }
        return if (o == raw.size) base else null
      }
      val id = field(16) ?: return null
      val color = field(4) ?: return null
      val ss = field(4) ?: return null
      val st = field(4) ?: return null
      val os = field(2) ?: return null
      val ot = field(2) ?: return null
      val rot = field(2) ?: return null
      val uuidHex = hexPrev(id, 16).lowercase(Locale.US)
      val uuid = uuidHex.substring(0,8)+"-"+uuidHex.substring(8,12)+"-"+uuidHex.substring(12,16)+"-"+uuidHex.substring(16,20)+"-"+uuidHex.substring(20,32)
      val sc = ByteBuffer.wrap(ss).order(ByteOrder.LITTLE_ENDIAN).float
      val tc = ByteBuffer.wrap(st).order(ByteOrder.LITTLE_ENDIAN).float
      val s16 = ByteBuffer.wrap(os).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
      val t16 = ByteBuffer.wrap(ot).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
      val r16 = ByteBuffer.wrap(rot).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
      return TextureEntryFields(uuid, sc.coerceIn(-100f,100f), tc.coerceIn(-100f,100f), s16 / 32767f, t16 / 32767f, (r16 / 32768f) * (Math.PI * 2.0).toFloat(), (255 - (color[0].toInt() and 255)) / 255f, (255 - (color[1].toInt() and 255)) / 255f, (255 - (color[2].toInt() and 255)) / 255f, (255 - (color[3].toInt() and 255)) / 255f)
    } catch (_: Throwable) { return null }
  }  private fun zeroExpand(data: ByteArray): ByteArray {
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
  private var zeroFixLogged = false
  var r3latch = ""
  var vecLatch = ""
  @Volatile private var vecLogged = false
  @Volatile private var vecMemo: String? = null
  @Volatile private var vecTaken = false
  fun consumeVec(): String? {
    try {
      if (vecTaken) return null
      val v = vecMemo ?: return null
      vecTaken = true
      return v
    } catch (_: Throwable) { return null }
  }
  var nKillHit = 0L
  var nKillMiss = 0L
  var lastKillId = -1L
  var lastPutReject = ""
  var firstTerseId = -1L
  var firstTerseX = Double.NaN
  var firstTerseY = Double.NaN
  var firstTerseZ = Double.NaN
  var fueraMuestra = ""
  private val fullQ = mutableListOf<String>()
  private var nFullQ = 0
  private var nFullTot = 0L
  private val brHist = LinkedHashMap<String,Long>()
  private val extraQ = mutableListOf<String>()
  var nAttach = 0L
  var attachMuestra: String? = null
  private var attachArmed = true
  var sixtyLine: String? = null
  private fun stashSkA(muId: Long, muIlen: Int, muPc: Int, muIn: String, o: Int, size: Int, p: ByteArray) {
    try {
      val rem = size - o
      var hx = ""
      try { hx = hexPrev(p.copyOfRange(o, (o + 16).coerceAtMost(size).coerceAtLeast(o)), 16) } catch(_: Throwable) {}
      var u16v = -1
      try { if (o + 2 <= size && o >= 0) u16v = u16at(p, o) } catch(_: Throwable) {}
      stashFullMu("skA sko=" + o + " rem=" + rem + " u16=" + u16v + " skhex=" + hx + " ", muId, muIlen, muPc, muIn, 0, 0, 0)
    } catch(_: Throwable) {}
  }
  private fun stashFullMu(br: String, muId: Long, muIlen: Int, muPc: Int, muIn: String, wa: Int, wc: Int, wd: Int) {
    try {
      nFullTot += 1
      try {
        val bk = br.trim().split(" ")[0].take(12)
        brHist[bk] = (brHist[bk] ?: 0L) + 1L
      } catch(_: Throwable) {}
      if (fullQ.size >= 3) return
      val line = "FULL-MUESTRA ilen=" + muIlen + " pcode=" + muPc + " id=" + muId + " " + muIn + "w=" + wa + "," + wc + "," + wd + " br=" + br
      if (nFullQ < 3) {
        nFullQ += 1
        fullQ.add(line)
      } else if (nFullTot % 100 == 0L) {
        fullQ.add(line + " rot")
      }
    } catch(_: Throwable) {}
  }
  fun brHistLine(): String {
    try {
      if (brHist.isEmpty()) return "HISTO-BR vacio"
      val sb = StringBuilder("HISTO-BR")
      for ((k, v) in brHist) {
        sb.append(" ")
        sb.append(k)
        sb.append("=")
        sb.append(v)
      }
      return sb.toString()
    } catch(_: Throwable) { return "HISTO-BR error" }
  }
  fun terseLine(): String {
    try {
      synchronized(recs) {
        if (recs.isEmpty()) return "TERSE-N n=0"
        val sb = StringBuilder("TERSE-N n=" + recs.size)
        var ni = 0
        for ((k, r) in recs) {
          if (ni >= 3) break
          sb.append(" ")
          sb.append(k)
          sb.append(":")
          sb.append(r.x)
          sb.append(",")
          sb.append(r.y)
          sb.append(",")
          sb.append(r.z)
          ni++
        }
        return sb.toString()
      }
    } catch(_: Throwable) { return "TERSE-N error" }
  }
  val pendingTexQ = mutableListOf<String>()
  var texEmitTotal = 0L
  var texEmitSesion = 0L
  var onTexLine: ((String) -> Unit)? = null
  fun texIdsReset() { try { synchronized(recs) { texIds.clear() } } catch(_: Throwable) {} }
  fun texList(): List<String> {
    try {
      val out = mutableListOf<String>()
      synchronized(recs) {
        for (r in recs.values) {
          if (r.tex.isNotEmpty() && !out.contains(r.tex)) out.add(r.tex)
          if (out.size >= 48) break
        }
      }
      return out
    } catch(_: Throwable) { return emptyList() }
  }
  fun pollTexFull(): String? { try { synchronized(recs) { for (r in recs.values) { if (r.tex.isNotEmpty()) return r.tex } } } catch(_: Throwable) {}; return null }
  private val texIds = LinkedHashSet<Long>()
  private var lastTexSum = 0L
  private fun stashFirst(got: Int, id: Long, x: Double, y: Double, z: Double) {
    try { if (got == 0) { firstTerseId = id; firstTerseX = x; firstTerseY = y; firstTerseZ = z } } catch(_: Throwable) {}
  }
  private fun hexPrev(b: ByteArray, n: Int): String {    val sb = StringBuilder()
    for (i in 0 until n.coerceAtMost(b.size)) sb.append("%02X".format(b[i]))
    return sb.toString()
  }
  // CITA-SEMANTICA attachments/HUD: el visor los posiciona relativos al avatar (coords ~0 o levemente negativas), no son prims de region: se cuentan aparte, ni render ni fuera.
  private fun put(id: Long, tipo: Int, x: Double, y: Double, z: Double, sx: Float, sy: Float, sz: Float, yw: Float, now: Long, mat: Int = -1, tex: String = "") {
    try {
      if (!x.isFinite()) { try { lastPutReject = "no-finito-x id=" + id } catch(_: Throwable) {}; return }
      if (!y.isFinite()) { try { lastPutReject = "no-finito-y id=" + id } catch(_: Throwable) {}; return }
      if (!z.isFinite()) { try { lastPutReject = "no-finito-z id=" + id } catch(_: Throwable) {}; return }
      if (x > -10.0 && x < 10.0 && y > -10.0 && y < 10.0 && z > -200.0 && z < 2000.0) {
        try { nAttach++ } catch(_: Throwable) {}
        try { lastPutReject = "attach id=" + id } catch(_: Throwable) {}
        try { if (attachArmed && attachMuestra == null) { attachMuestra = "ATTACH-MUESTRA id=" + id + " xyz=" + x + "," + y + "," + z + " t=" + tipo; attachArmed = false } } catch(_: Throwable) {}
        return
      }
      if (x < 0.0 || x > 256.0 || y < 0.0 || y > 256.0 || z < -200.0 || z > 2000.0) {
        try { nFueraRango++ } catch(_: Throwable) {}
        try { lastPutReject = "fuera id=" + id + " xyz=" + x + "," + y + "," + z } catch(_: Throwable) {}
        try { if (fueraMuestra.isEmpty()) fueraMuestra = "id=" + id + " xyz=" + x + "," + y + "," + z + " t=" + tipo } catch(_: Throwable) {}
        return
      }
      if (sx.isFinite() && sy.isFinite() && sz.isFinite() && (sx <= 0f || sy <= 0f || sz <= 0f || sx > 64f || sy > 64f || sz > 64f)) {
        try { nEscMala++ } catch(_: Throwable) {}
        try { lastPutReject = "escala id=" + id + " s=" + sx + "," + sy + "," + sz } catch(_: Throwable) {}
        return
      }
      synchronized(recs) {
        val r = recs[id]
        if (r == null) {
          val yy = if (yw.isFinite()) yw else 0f
          recs[id] = Prim(id, tipo, x, y, z, normS(sx), normS(sy), normS(sz), yy, now, mat, tex)
        } else {
          if (tipo != -1) r.tipo = tipo
          if (mat != -1) r.mat = mat
          if (tex.isNotEmpty()) r.tex = tex
          r.x = x
          r.y = y
          r.z = z
          if (sx.isFinite()) r.sx = normS(sx)
          if (sy.isFinite()) r.sy = normS(sy)
          if (sz.isFinite()) r.sz = normS(sz)
          if (yw.isFinite()) r.yaw = yw
          r.seen = now
        }
      }
      try { lastPutReject = "" } catch(_: Throwable) {}
    } catch(_: Throwable) {}
  }
  private fun sample(now: Long): Prim? {
    try {
      synchronized(recs) {
        for (r in recs.values) {
          if (r.seen == now) return r.copy()
        }
      }
    } catch(_: Throwable) {}
    return null
  }
  private fun fmtP(s: Prim): String {
    return "%.1f,%.1f,%.1f".format(s.x, s.y, s.z) + " t=" + s.tipo
  }
  private fun fmtS(s: Prim): String {
    return "%.2f,%.2f,%.2f".format(s.sx, s.sy, s.sz)
  }
  // M1 CASO-A/B: R3-TEST runtime (vector R3 node-metaverse ImprovedTerseObjectUpdateMessageL.json).
  // CITA layout R1 = OMV ImprovedTerseObjectUpdateHandler: LocalID U32@0 LE, State@4, Avatar@5, pos F32@6 LE.
  // Lo llama SlWorldRenderer al abrir 3D (siempre, no single-shot): si OK => CASO-A decoder bien.
  fun r3selftest(): String {
    try {
      val d = ByteArray(44) { i -> "50B1002600009AC062438D1862435A1EFF42FF7FFF7FFF7FFF7FFF7FFF7FFF7FFF7FBE09FBB0FF7FFF7FFF7F".substring(i * 2, i * 2 + 2).toInt(16).toByte() }
      val pay = ByteArray(10 + 1 + 1 + 44 + 2)
      pay[10] = 1
      pay[11] = 44
      for (i in d.indices) pay[12 + i] = d[i]
      var direct = "R3-DIRECT error"
      try {
        val bbD = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val idD = bbD.int.toLong() and 0xFFFFFFFFL
        val xD = bbD.getFloat(6).toDouble()
        val yD = bbD.getFloat(10).toDouble()
        val zD = bbD.getFloat(14).toDouble()
        direct = "R3-DIRECT id=" + idD + " x=" + xD + " y=" + yD + " z=" + zD
      } catch (_: Throwable) {}
      val vecWas = vecLogged
      val vecWasMemo = vecMemo
      val f0 = nFueraRango
      val l0 = nLenMalo
      val q0 = nTerseQ
      val tf0 = nTerseF
      val s0 = count()
      val rej0 = lastPutReject
      val n = parseTerse(pay, System.currentTimeMillis())
      val dFuera = nFueraRango - f0
      val dLen = nLenMalo - l0
      val dQ = nTerseQ - q0
      val dTf = nTerseF - tf0
      val s1 = count()
      try { vecLogged = vecWas; vecMemo = vecWasMemo } catch (_: Throwable) {}
      var got = "nada"
      try {
        synchronized(recs) {
          val r = recs[637579600L]
          if (r != null) got = "id=" + r.id + " xyz=" + String.format(Locale.US, "%.2f,%.2f,%.2f", r.x, r.y, r.z)
          recs.remove(637579600L)
        }
      } catch (_: Throwable) {}
      if (got == "nada") {
        try {
          val rej1 = lastPutReject
          got = if (rej1.isNotEmpty() && rej1 != rej0) "rechazado " + rej1 else "nada n=" + n + " (put no inserto)"
        } catch (_: Throwable) {}
      }
      var cmp = "live=sin-VEC"
      try {
        val lh = vecWasMemo?.substringAfter("hex=", "") ?: ""
        if (lh.length >= 36) {
          val sh = hexPrev(d, 18)
          val lv = lh.take(36)
          var eqB = 4
          while (eqB < 18 && sh.substring(eqB * 2, eqB * 2 + 2) == lv.substring(eqB * 2, eqB * 2 + 2)) eqB += 1
          cmp = if (eqB >= 18) "cmp4..17=iguales" else "syn18=" + sh + " live18=" + lv + " difB@" + eqB
        }
      } catch (_: Throwable) {}
      val ok = got.startsWith("id=637579600 xyz=226.75,226.1")
      val line = "R3-TEST R3-CODE=718a n=" + n + " got=" + got + " exp=id=637579600 xyz=226.75,226.10,127.56 " + (if (ok) "OK-CASO-A-decoder-bien" else "FAIL-CASO-B-framing") + " mot=size=" + s0 + "->" + s1 + " fuera+" + dFuera + " len+" + dLen + " q+" + dQ + " f+" + dTf + " " + cmp + " win0=" + hexPrev(pay.copyOfRange(0, 18), 18) + " win12=" + hexPrev(pay.copyOfRange(12, 30), 18) + " o=12 len=44 pay=" + pay.size + " " + direct
      try { r3latch = line } catch (_: Throwable) {}
      return line
    } catch (_: Throwable) { return "R3-TEST error" }
  }
  // REGLA terse: solo posicion/yaw, jamas escala (R1 no trae escala; nuevo sin full = 1m via normS)
  private fun parseTerse(p: ByteArray, now: Long): Int {
    try {
      if (p.size < 11) return 0
      var o = 10
      val count = p[o].toInt() and 0xFF
      o += 1
      if (count <= 0) return 0
      var got = 0
      var guard = count.coerceAtMost(256)
      while (guard > 0) {
        guard -= 1
        if (o + 1 > p.size) break
        val len = p[o].toInt() and 0xFF
        o += 1
        if (len != 44 && len != 60 && len != 64 && len != 32 && len != 80 && len != 48) {
          try { nLenMalo++ } catch(_: Throwable) {}
          if (o + len > p.size) break
          o += len
          if (o + 2 > p.size) break
          val sl = u16at(p, o)
          o += 2
          if (o + sl > p.size) break
          o += sl
          continue
        }
        if (o + len > p.size) break
        val end = o + len
        val blk = p.copyOfRange(o, end)
        if (len == 44 && !vecLogged) {
          vecLogged = true
          try { vecMemo = "PRIMS-VEC len=44 id=" + (ByteBuffer.wrap(blk).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL) + " hex=" + hexPrev(blk.copyOfRange(0, len), 44) } catch (_: Throwable) {}
          try { vecLatch = vecMemo ?: "" } catch (_: Throwable) {}
        }
        val bb = ByteBuffer.wrap(blk).order(ByteOrder.LITTLE_ENDIAN)
        // CITA-LE terse: U32/F32 payload LE (OMV PacketsBig.cs:8388 LocalID; BitPack.cs:193-199 floats)
        // M1-7.18: id Y floats se leen de copia fresca, nunca del paquete p.
        val id = bb.int.toLong() and 0xFFFFFFFFL
        val av = blk[5].toInt() and 0xFF
        if (len == 64 || len == 32 || len == 80 || len == 48) {
          val qav = (len == 80 || len == 48)
          val qb = 6 + (if (qav) 16 else 0)
          val zlo = if (TerrainMesh.nPatches > 0 && TerrainMesh.minH.isFinite() && TerrainMesh.maxH.isFinite()) TerrainMesh.minH - 20f else -100f
          val zhi = if (TerrainMesh.nPatches > 0 && TerrainMesh.minH.isFinite() && TerrainMesh.maxH.isFinite()) TerrainMesh.maxH + 20f else 500f
          val x = u16f(u16at(blk, qb), -128f, 384f).toDouble()
          val y = u16f(u16at(blk, qb + 2), -128f, 384f).toDouble()
          val z = u16f(u16at(blk, qb + 4), zlo, zhi).toDouble()
          try { nTerseQ++ } catch(_: Throwable) {}
          stashFirst(got, id, x, y, z)
          put(id, if (qav) 47 else -1, x, y, z, Float.NaN, Float.NaN, Float.NaN, Float.NaN, now)
        } else if (av == 1) {
          if (len >= 34) {
            val x = bb.getFloat(22).toDouble()
            val y = bb.getFloat(26).toDouble()
            val z = bb.getFloat(30).toDouble()
            var yw = Float.NaN
            if (len >= 60) {
              val qx = u16f(bb.getShort(46).toInt() and 0xFFFF, -1f, 1f)
              val qy = u16f(bb.getShort(48).toInt() and 0xFFFF, -1f, 1f)
              val qz = u16f(bb.getShort(50).toInt() and 0xFFFF, -1f, 1f)
              val qw = u16f(bb.getShort(52).toInt() and 0xFFFF, -1f, 1f)
              yw = yawQuat(qx, qy, qz, qw)
            }
            stashFirst(got, id, x, y, z)
            put(id, 47, x, y, z, Float.NaN, Float.NaN, Float.NaN, yw, now)
            try { nTerseF++ } catch(_: Throwable) {}
          }
        } else {
          if (len >= 18) {
            val x = bb.getFloat(6).toDouble()
            val y = bb.getFloat(10).toDouble()
            val z = bb.getFloat(14).toDouble()
            var yw = Float.NaN
            if (len >= 38) {
              val qx = u16f(bb.getShort(30).toInt() and 0xFFFF, -1f, 1f)
              val qy = u16f(bb.getShort(32).toInt() and 0xFFFF, -1f, 1f)
              val qz = u16f(bb.getShort(34).toInt() and 0xFFFF, -1f, 1f)
              val qw = u16f(bb.getShort(36).toInt() and 0xFFFF, -1f, 1f)
              yw = yawQuat(qx, qy, qz, qw)
            }
            stashFirst(got, id, x, y, z)
            put(id, -1, x, y, z, Float.NaN, Float.NaN, Float.NaN, yw, now)
            try { nTerseF++ } catch(_: Throwable) {}
          }
        }
        o = end
        if (o + 2 > p.size) break
        val tlen = u16at(p, o)
        o += 2
        if (o + tlen > p.size) break
        o += tlen
        got += 1
      }
      return got
    } catch(_: Throwable) { return 0 }
  }
  private fun parseComp(p: ByteArray, now: Long): Int {
    try {
      if (p.size < 11) return 0
      var o = 10
      val count = p[o].toInt() and 0xFF
      o += 1
      if (count <= 0) return 0
      var got = 0
      var guard = count.coerceAtMost(256)
      while (guard > 0) {
        guard -= 1
        if (o + 6 > p.size) break
        o += 4
        val dlen = u16at(p, o)
        o += 2
        if (o + dlen > p.size) break
        if (dlen == 44 || dlen == 60) {
          val blk = p.copyOfRange(o, o + dlen)
          val bb = ByteBuffer.wrap(blk).order(ByteOrder.LITTLE_ENDIAN)
          // CITA-LE comp-terse: OMV PacketsBig.cs:8388 LocalID LE; floats LE BitPack.cs:193-199
          val id = bb.int.toLong() and 0xFFFFFFFFL
          val av = blk[5].toInt() and 0xFF
          if (av == 1 && dlen == 60) {
            val x = bb.getFloat(22).toDouble()
            val y = bb.getFloat(26).toDouble()
            val z = bb.getFloat(30).toDouble()
            put(id, 47, x, y, z, Float.NaN, Float.NaN, Float.NaN, Float.NaN, now)
          } else {
            val x = bb.getFloat(6).toDouble()
            val y = bb.getFloat(10).toDouble()
            val z = bb.getFloat(14).toDouble()
            put(id, -1, x, y, z, Float.NaN, Float.NaN, Float.NaN, Float.NaN, now)
          }
        } else if (dlen >= 84) {
          val blk = p.copyOfRange(o, o + dlen)
          val bb = ByteBuffer.wrap(blk).order(ByteOrder.LITTLE_ENDIAN)
          // CITA-LE comp-full: OMV PacketsBig.cs:13897 ObjectLocalID LE; floats LE BitPack.cs:193-199
          val id = bb.getInt(16).toLong() and 0xFFFFFFFFL
          val pcode = blk[20].toInt() and 0xFF
          val sx = bb.getFloat(28)
          val sy = bb.getFloat(32)
          val sz = bb.getFloat(36)
          val x = bb.getFloat(40).toDouble()
          val y = bb.getFloat(44).toDouble()
          val z = bb.getFloat(48).toDouble()
          val yw = yawVec(bb.getFloat(52), bb.getFloat(56), bb.getFloat(60))
          put(id, pcode, x, y, z, sx, sy, sz, yw, now, blk[26].toInt() and 0xFF)
        } else {
          try { nLenMalo++ } catch(_: Throwable) {}
        }
        o += dlen
        got += 1
      }
      return got
    } catch(_: Throwable) { return 0 }
  }
  private fun parseFull(p: ByteArray, now: Long): Int {
    try {
      if (p.size < 11) return 0
      var o = 10
      val count = p[o].toInt() and 0xFF
      o += 1
      if (count <= 0) return 0
      var got = 0
      var guard = count.coerceAtMost(64)
      while (guard > 0) {
        guard -= 1
        var muId = -1L
        var muIlen = -1
        var muPc = -1
        var muIn = ""
        var wA = ByteArray(0)
        var wC = ByteArray(0)
        var wD = ByteArray(0)
        if (o + 40 > p.size) { try { stashFullMu("hdr40", muId, muIlen, muPc, muIn, 0, 0, 0) } catch(_: Throwable) {}; break }
        val hb = p.copyOfRange(o, o + 40)
        val bb = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN)
        // CITA-LE full-hdr: OMV PacketsBig.cs:13897 ObjectLocalID LE
        val id = bb.int.toLong() and 0xFFFFFFFFL
        val pcode = hb[25].toInt() and 0xFF
        val sx = bb.getFloat(28)
        val sy = bb.getFloat(32)
        val sz = bb.getFloat(36)
        o += 40
        muId = id
        muPc = pcode
        if (o + 1 > p.size) { try { stashFullMu("ilen1", muId, muIlen, muPc, muIn, 0, 0, 0) } catch(_: Throwable) {}; break }
        val ilen = p[o].toInt() and 0xFF
        o += 1
        muIlen = ilen
        if (o + ilen > p.size) { try { stashFullMu("ilenN", muId, muIlen, muPc, muIn, 0, 0, 0) } catch(_: Throwable) {}; break }
        if (ilen != 76 && ilen != 60 && ilen != 124 && ilen != 140) { try { nLenMalo++ } catch(_: Throwable) {}; try { stashFullMu("ilenX", muId, muIlen, muPc, muIn, 0, 0, 0) } catch(_: Throwable) {} }
        else {
          val ibc = p.copyOfRange(o, o + ilen)
          val ib = ByteBuffer.wrap(ibc).order(ByteOrder.LITTLE_ENDIAN)
          // CITA-LE full-inner: floats LE (OMV BitPack.cs:193-199 UnpackFloat)
          val po = if (ilen == 76 || ilen == 140) 16 else 0
          val x = ib.getFloat(po).toDouble()
          val y = ib.getFloat(po + 4).toDouble()
          val z = ib.getFloat(po + 8).toDouble()
          val yw = yawVec(ib.getFloat(po + 36), ib.getFloat(po + 40), ib.getFloat(po + 44))
          if (ilen == 60 && sixtyLine == null) {
            try {
              val tr = synchronized(recs) { recs[id]?.copy() }
              if (tr != null) {
                var goff = 0
                var hit = false
                while (goff <= 48) {
                  try {
                    val cx = ib.getFloat(goff).toDouble()
                    val cy = ib.getFloat(goff + 4).toDouble()
                    val cz = ib.getFloat(goff + 8).toDouble()
if (cx > tr.x - 1.0 && cx < tr.x + 1.0 && cy > tr.y - 1.0 && cy < tr.y + 1.0 && cz > tr.z - 1.0 && cz < tr.z + 1.0) {
                      var dId = -1L
                      var dInfo = ""
                      var oInfo = ""
                      try {
                        synchronized(recs) {
                          if (recs.size >= 2) {
                            var ni = 0
                            for (k2 in recs.keys.toList()) {
                              val r2 = recs[k2]
                              if (r2 == null) continue
                              if (ni < 3) {
                                if (oInfo.isNotEmpty()) oInfo += " "
                                oInfo += k2.toString() + ":" + r2.x.toString() + "," + r2.y.toString() + "," + r2.z.toString()
                                ni++
                              }
                              if (dId < 0 && k2 != id && (Math.abs(r2.x - tr.x) >= 0.5 || Math.abs(r2.y - tr.y) >= 0.5 || Math.abs(r2.z - tr.z) >= 0.5)) {
                                dId = k2
                                dInfo = r2.x.toString() + "," + r2.y.toString() + "," + r2.z.toString()
                              }
                            }
                          }
                        }
                      } catch(_: Throwable) {}
                      if (dId >= 0) sixtyLine = "SIXTY-OFF id=" + id + " off=" + goff + " xyz=" + cx + "," + cy + "," + cz + " terse=" + tr.x + "," + tr.y + "," + tr.z + " distinto=" + dId + ":" + dInfo
                      else if (oInfo.isNotEmpty()) sixtyLine = "SIXTY-IGUAL id=" + id + " terse=" + tr.x + "," + tr.y + "," + tr.z + " otros=" + oInfo + " (partes co-localizadas)"
                      hit = true
                      break
                    }
                  } catch(_: Throwable) {}
                  goff += 4
                }
                if (!hit) { try { sixtyLine = "SIXTY-OFF id=" + id + " off=NONE terse=" + tr.x + "," + tr.y + "," + tr.z + " in0=" + ib.getFloat(0).toDouble() + "," + ib.getFloat(4).toDouble() + "," + ib.getFloat(8).toDouble() } catch(_: Throwable) {} }
              }
            } catch(_: Throwable) {}
          }
          try { muIn = "in16=" + ib.getFloat(16).toDouble() + "," + ib.getFloat(20).toDouble() + "," + ib.getFloat(24).toDouble() + " in0=" + ib.getFloat(0).toDouble() + "," + ib.getFloat(4).toDouble() + "," + ib.getFloat(8).toDouble() + " inhex=" + hexPrev(ibc.copyOfRange(0, 32), 32) + " " } catch(_: Throwable) {}
          put(id, pcode, x, y, z, sx, sy, sz, yw, now, hb[26].toInt() and 0xFF)
        }
        o += ilen
        // CITA-PLANTILLA ObjectUpdate (message_template.msg): ParentID U32(4)+UpdateFlags U32(4)+PathCurve/ProfileCurve(2)+PathBegin/End(4)+ScaleX/Y+ShearX/Y(4)+Twist..Skew(7)+ProfileBegin/End/Hollow(6)=31B; luego TextureEntry V2 primera. Empirico [00,len-lo]=ProfileHollow-hi+TEntry-len-lo.
        // NOTA narrow: TextureEntry es Variable 2 por plantilla; leerla en U8 contradice la plantilla (TextureAnim de 50-149B es inverosimil).
        if (o + 30 > p.size) { try { stashFullMu("in30", muId, muIlen, muPc, muIn, 0, 0, 0) } catch(_: Throwable) {}; break }
        o += 31
        var sgv = skipGet(p, o, true)
        if (sgv.first < 0) { try { stashSkA(muId, muIlen, muPc, muIn, o, p.size, p) } catch(_: Throwable) {}; break }
        o = sgv.first
        wA = sgv.second
        sgv = skipGet(p, o, false)
        if (sgv.first < 0) { try { stashFullMu("skB", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}; break }
        o = sgv.first
        sgv = skipGet(p, o, true)
        if (sgv.first < 0) { try { stashFullMu("skC", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}; break }
        o = sgv.first
        wC = sgv.second
        sgv = skipGet(p, o, true)
        if (sgv.first < 0) { try { stashFullMu("skD", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}; break }
        o = sgv.first
        wD = sgv.second
        sgv = skipGet(p, o, false)
        if (sgv.first < 0) { try { stashFullMu("skE", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}; break }
        o = sgv.first
        if (o + 4 > p.size) { try { stashFullMu("fix4", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}; break }
        o += 4
        o = skipVar(p, o, false)
        if (o < 0) { try { stashFullMu("skF", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}; break }
        o = skipVar(p, o, false)
        if (o < 0) { try { stashFullMu("skG", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}; break }
        o = skipVar(p, o, false)
        if (o < 0) { try { stashFullMu("skH", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}; break }
        // TextureEntry V2 has the default image UUID first, then typed fields with face overrides.
        try {
          val te = parseTextureEntry(wA)
          if (te != null) {
            try { synchronized(recs) { val r = recs[id]; if (r != null) { if (r.tex.isEmpty()) r.tex = te.uuid; r.texScaleS = te.scaleS; r.texScaleT = te.scaleT; r.texOffsetS = te.offsetS; r.texOffsetT = te.offsetT; r.texRotation = te.rotation; r.texR = te.r; r.texG = te.g; r.texB = te.b; r.texA = te.a } } } catch(_: Throwable) {}
            try { if (texIds.size < 8 && texIds.add(id)) { val tl = "TEX-UUID id=" + id + " u=" + te.uuid + " id8=" + te.uuid.take(8) + " uv=" + te.scaleS + "," + te.scaleT + "," + te.offsetS + "," + te.offsetT + "," + te.rotation; try { texEmitTotal++ } catch(_: Throwable) {}; try { texEmitSesion++ } catch(_: Throwable) {}; try { onTexLine?.invoke(tl) } catch(_: Throwable) {} } } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}        if (o + 66 > p.size) { try { stashFullMu("fix66", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}; break }
        o += 66
        try { stashFullMu("ok", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}
        got += 1
      }
      return got
    } catch(_: Throwable) { return 0 }
  }
  private fun parseCached(p: ByteArray): Int {
    try {
      if (p.size < 11) return 0
      val count = p[10].toInt() and 0xFF
      if (count <= 0) return 0
      if (11 + count * 12 > p.size) return 0
      return count
    } catch(_: Throwable) { return 0 }
  }
  private fun parseKill(p: ByteArray): Int {
    try {
      if (p.size < 1) return 0
      var o = 0
      val count = p[o].toInt() and 0xFF
      o += 1
      if (count <= 0) return 0
      var got = 0
      var guard = count.coerceAtMost(256)
      while (guard > 0) {
        guard -= 1
        if (o + 4 > p.size) break
        val id = ByteBuffer.wrap(p, o, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        // CITA-LE kill-id: OMV PacketsBig.cs:8388 LocalID LE
        o += 4
        if (got == 0) lastKillId = id
        try {
          val had = synchronized(recs) { recs.containsKey(id) }
          if (had) {
            try { synchronized(recs) { recs.remove(id) } } catch (_: Throwable) {}
            try { nKillHit++ } catch (_: Throwable) {}
          } else {
            try { nKillMiss++ } catch (_: Throwable) {}
          }
        } catch (_: Throwable) {}
        got += 1
      }
      return got
    } catch(_: Throwable) { return 0 }
  }
  fun ingest(msgId: Int, payload: ByteArray, zeroCoded: Boolean = false): String? {
    try {
      val now = System.currentTimeMillis()
      if (msgId == 15) {
        var n = parseTerse(payload, now)
        if (n == 0 && zeroCoded) {
          try {
            val z = zeroExpand(payload)
            if (z.size != payload.size) {
              val n2 = parseTerse(z, now)
              if (n2 > 0) {
                n = n2
                try { nZeroFix++ } catch (_: Throwable) {}
                if (!zeroFixLogged) { zeroFixLogged = true; return "PRIMS-ZEROFIX f=15 n=" + n2 }
              }
            }
          } catch (_: Throwable) {}
        }
        nTerse += 1
        nObjTerse += n
        if (hexFam.add(15)) return "PRIMS-HEX f=15 len=" + payload.size + " count=" + (if (payload.size > 10) (payload[10].toInt() and 0xFF) else -1) + " head=" + hexPrev(payload, 60)
        if (n > 0 && famShown.add(15)) {
          return "PRIMS-TERSE id=" + firstTerseId + " p=" + firstTerseX + "," + firstTerseY + "," + firstTerseZ + " obj=" + count()
        }
      } else if (msgId == 13) {
        var n = parseComp(payload, now)
        if (n == 0) {
          try {
            val z = zeroExpand(payload)
            if (z.size != payload.size) {
              val n2 = parseComp(z, now)
              if (n2 > 0) {
                n = n2
                try { nZeroFix++ } catch (_: Throwable) {}
                if (!zeroFixLogged) { zeroFixLogged = true; return "PRIMS-ZEROFIX f=13 n=" + n2 }
              }
            }
          } catch (_: Throwable) {}
        }
        nComp += 1
        nObjComp += n
        if (hexFam.add(13)) return "PRIMS-HEX f=13 len=" + payload.size + " count=" + (if (payload.size > 10) (payload[10].toInt() and 0xFF) else -1) + " head=" + hexPrev(payload, 16) + " dlen0=" + (if (payload.size > 16) u16at(payload, 15) else -1)
        if (n > 0 && famShown.add(13)) {
          val s = sample(now)
          if (s != null) return "PRIMS-COMP id=" + s.id + " p=" + fmtP(s) + " esc=" + fmtS(s) + " obj=" + count()
          return "PRIMS-COMP n=" + n + " obj=" + count()
        }
      } else if (msgId == 12) {
        var n = parseFull(payload, now)
        if (n == 0) {
          try {
            val z = zeroExpand(payload)
            if (z.size != payload.size) {
              val n2 = parseFull(z, now)
              if (n2 > 0) {
                n = n2
                try { nZeroFix++ } catch (_: Throwable) {}
                if (!zeroFixLogged) { zeroFixLogged = true; return "PRIMS-ZEROFIX f=12 n=" + n2 }
              }
            }
          } catch (_: Throwable) {}
        }
        var txLine: String? = null
        try { if (pendingTexQ.isNotEmpty()) txLine = pendingTexQ.removeAt(0) } catch(_: Throwable) {}
        if (txLine != null) { try { texEmitTotal++ } catch(_: Throwable) {}; return txLine }
        var sxLine: String? = null
        try { sxLine = sixtyLine; sixtyLine = null } catch(_: Throwable) {}
        if (sxLine != null) return sxLine
        var fxLine: String? = null
        try { if (fullQ.isNotEmpty()) fxLine = fullQ.removeAt(0) } catch(_: Throwable) {}
        if (fxLine != null) return fxLine
        var exLine: String? = null
        try { if (extraQ.isNotEmpty()) exLine = extraQ.removeAt(0) } catch(_: Throwable) {}
        if (exLine == "HISTO") return brHistLine()
        if (exLine == "TERSEN") return terseLine()
        if (exLine == "ATTN") return "ATTACH-ESTADO attach=" + nAttach
        if (exLine != null) return exLine
        var atLine: String? = null
        try { atLine = attachMuestra; attachMuestra = null } catch(_: Throwable) {}
        if (atLine != null) return atLine
        nFull += 1
        nObjFull += n
        if (hexFam.add(12)) return "PRIMS-HEX f=12 len=" + payload.size + " count=" + (if (payload.size > 10) (payload[10].toInt() and 0xFF) else -1) + " head=" + hexPrev(payload, 16)
        if (n > 0 && famShown.add(12)) {
          val s = sample(now)
          if (s != null) return "PRIMS-FULL id=" + s.id + " p=" + fmtP(s) + " esc=" + fmtS(s) + " obj=" + count()
          return "PRIMS-FULL n=" + n + " obj=" + count()
        }
      } else if (msgId == 14) {
        val n = parseCached(payload)
        nCached += 1
        if (famShown.add(14)) return "PRIMS-CACHED n=" + n + " (solo id+crc, sin geometria)"
      } else if (msgId == 16) {
        val n = parseKill(payload)
        nKill += 1
        if (famShown.add(16)) return "PRIMS-KILL n=" + n + " id=" + lastKillId + " hit=" + nKillHit + " miss=" + nKillMiss + " obj=" + count()
      } else {
        return null
      }
      if (now - lastTexSum >= 30000L) {
        lastTexSum = now
        try {
          var con = 0
          var tot = 0
          synchronized(recs) { for (r in recs.values) { tot += 1; if (r.tex.isNotEmpty()) con += 1 } }
          return "TEX-ESTADO con=" + con + " sin=" + (tot - con) + " obj=" + tot + " emit=" + texEmitTotal
        } catch(_: Throwable) {}
      }
      if (now - lastSum >= 30000L) {
        lastSum = now
        try { extraQ.add("HISTO") } catch(_: Throwable) {}
        try { extraQ.add("TERSEN") } catch(_: Throwable) {}
        try { extraQ.add("ATTN") } catch(_: Throwable) {}
        try { attachArmed = true } catch(_: Throwable) {}
        return "PRIMS-ESTADO terse=" + nTerse + "/" + nObjTerse + "(q=" + nTerseQ + " f=" + nTerseF + ") comp=" + nComp + "/" + nObjComp + " full=" + nFull + "/" + nObjFull + " cached=" + nCached + " kill=" + nKill + "(hit=" + nKillHit + " miss=" + nKillMiss + ") fuera=" + nFueraRango + " lenMalo=" + nLenMalo + " escMala=" + nEscMala + " zeroFix=" + nZeroFix + " obj=" + count() + " FUERA-MUESTRA " + (if (fueraMuestra.isEmpty()) "ninguna" else fueraMuestra)
      }
      return null
    } catch(_: Throwable) { return null }
  }
  fun publish(ax: Double, ay: Double, az: Double): List<Prim> {
    try {
      synchronized(recs) {
        val now = System.currentTimeMillis()
        val it = recs.values.iterator()
        while (it.hasNext()) {
          if (now - it.next().seen > 300000L) it.remove()
        }
        val all = recs.values.toList()
        val sorted = all.sortedBy { r -> (r.x - ax) * (r.x - ax) + (r.y - ay) * (r.y - ay) + (r.z - az) * (r.z - az) }
        if (recs.size > 600) {
          var i = 0
          for (r in sorted) {
            i += 1
            if (i > 600) recs.remove(r.id)
          }
        }
        return sorted.take(64).map { r -> r.copy() }
      }
    } catch(_: Throwable) { return emptyList() }
  }
}
