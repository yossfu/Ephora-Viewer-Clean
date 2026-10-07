package com.ephora.sl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
object PrimDecoder {
  data class TextureFace(val uuid: String, val scaleS: Float, val scaleT: Float, val offsetS: Float, val offsetT: Float, val rotation: Float, val r: Float, val g: Float, val b: Float, val a: Float)
  data class Prim(val id: Long, var tipo: Int, var x: Double, var y: Double, var z: Double, var sx: Float, var sy: Float, var sz: Float, var yaw: Float, var seen: Long, var mat: Int = -1, var tex: String = "", var texScaleS: Float = 1f, var texScaleT: Float = 1f, var texOffsetS: Float = 0f, var texOffsetT: Float = 0f, var texRotation: Float = 0f, var texR: Float = 1f, var texG: Float = 1f, var texB: Float = 1f, var texA: Float = 1f, var texFaces: List<TextureFace> = emptyList(), var pathCurve: Int = 0x10, var profileCurve: Int = 0x01, var meshId: String = "", var hasShape: Boolean = false, var shPb: Float = 0f, var shPe: Float = 1f, var shPsx: Float = 1f, var shPsy: Float = 1f, var shShx: Float = 0f, var shShy: Float = 0f, var shTw: Float = 0f, var shTwb: Float = 0f, var shRo: Float = 0f, var shTpx: Float = 0f, var shTpy: Float = 0f, var shRev: Float = 0f, var shSk: Float = 0f, var shQb: Float = 0f, var shQe: Float = 1f, var shQh: Float = 0f)
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
  var nAnsweredReq = 0L
  var nFullNoRec = 0L
  var nFullTex = 0L
  var nFullSinTex = 0L
  var nEvict = 0L
  var nTerseQ = 0L
  var nTerseF = 0L
  var nTerseCap = 0L
  var nFullCap = 0L
  private val censoTerse = LinkedHashSet<Long>()
  private val censoComp = LinkedHashSet<Long>()
  private val censoFull = LinkedHashSet<Long>()
  private val censoCached = LinkedHashSet<Long>()
  private val censoAttach = LinkedHashSet<Long>()
  private val reqMultPend = LinkedHashSet<Long>()
  private val reqMultDone = LinkedHashSet<Long>()
  private val reqMultTime = LinkedHashMap<Long, Long>()
  var sixtySticky = ""
  @Volatile var diagLatch = ""
  @Volatile var nReqMultSent = 0L
  @Volatile var nReqMultPk = 0L
  private fun censoAdd(set: LinkedHashSet<Long>, id: Long) {
    try { synchronized(recs) { if (set.size < 20000) set.add(id) } } catch (_: Throwable) {}
  }
  private val answeredIds = LinkedHashSet<Long>()
  private val evictSample = ArrayDeque<String>()
  private val killSample = ArrayDeque<String>()
  private val deadIds = LinkedHashSet<Long>()
  private val pubPrev = LinkedHashSet<Long>()
  @Volatile var pubDiffLatch = "sin-pub-aun"
  @Volatile var advReqLatch = "sin-req-aun"
  private var lastAdvReq = 0L
  private var pubAx = 0.0
  private var pubAy = 0.0
  private var pubAz = 0.0
  private fun evictStr(r: Prim, ax: Double, ay: Double, az: Double): String {
    return r.id.toString() + " " + "%.0f,%.0f,%.0f".format(r.x, r.y, r.z) + " " + Math.sqrt((r.x - ax) * (r.x - ax) + (r.y - ay) * (r.y - ay)).toInt().toString() + "m"
  }
  private fun noteEvict(r: Prim, ax: Double, ay: Double, az: Double) {
    try { evictSample.addLast(evictStr(r, ax, ay, az)) } catch(_: Throwable) {}
    try { while (evictSample.size > 8) evictSample.removeFirst() } catch(_: Throwable) {}
  }
  private fun noteKill(id: Long) {
    var rk: Prim? = null
    try { rk = synchronized(recs) { recs[id] } } catch(_: Throwable) {}
    val r = rk
    try { if (r == null) return } catch(_: Throwable) { return }
    try { killSample.addLast(id.toString() + " " + "%.0f,%.0f,%.0f".format(r.x, r.y, r.z)) } catch(_: Throwable) {}
    try { while (killSample.size > 8) killSample.removeFirst() } catch(_: Throwable) {}
  }
  private fun updatePubDiff(pub: List<Prim>) {
    val cur = LinkedHashSet<Long>()
    try { for (p in pub) cur.add(p.id) } catch(_: Throwable) {}
    var apIds: List<Long> = emptyList()
    try { apIds = cur.filter { !pubPrev.contains(it) } } catch(_: Throwable) {}
    var goIds: List<Long> = emptyList()
    try { goIds = pubPrev.filter { !cur.contains(it) } } catch(_: Throwable) {}
    try { pubDiffLatch = "ADV-PUB pub=" + cur.size + " nuevos=" + apIds.size + " [" + apIds.take(3).joinToString(" ") + "] fuera=" + goIds.size + " [" + goIds.take(3).joinToString(" ") + "] evict8=[" + evictSample.joinToString(" ") + "] kill8=[" + killSample.joinToString(" ") + "]" } catch(_: Throwable) {}
    try { pubPrev.clear() } catch(_: Throwable) {}
    try { pubPrev.addAll(cur) } catch(_: Throwable) {}
  }
  private fun maybeAdvReq() {
    val now = System.currentTimeMillis()
    try { if (now - lastAdvReq < 15000L) return } catch(_: Throwable) { return }
    try { lastAdvReq = now } catch(_: Throwable) {}
    var mundo: List<Long> = emptyList()
    try { mundo = reqMultDone.filter { !answeredIds.contains(it) && synchronized(recs) { recs.containsKey(it) } } } catch(_: Throwable) {}
    var muertos = 0
    try { muertos = reqMultDone.count { !answeredIds.contains(it) && !synchronized(recs) { recs.containsKey(it) } } } catch(_: Throwable) {}
    try { mundo = mundo.sortedBy { distPend(it, pubAx, pubAy, pubAz) }.take(8) } catch(_: Throwable) {}
    var mu = ""
    try { mu = mundo.joinToString(" ") { id -> id.toString() + ":" + Math.sqrt(distPend(id, pubAx, pubAy, pubAz)).toInt().toString() + "m:" + ((now - (try { reqMultTime[id] ?: now } catch(_: Throwable) { now })) / 1000L).toString() + "s" } } catch(_: Throwable) {}
    try { advReqLatch = "ADV-REQ pedidas=" + nReqMultSent + " contestadas=" + nAnsweredReq + " mundoSinResp=" + mundo.size + " muertos=" + muertos + " muestra=[" + mu + "]" } catch(_:Throwable) {}
  }
  fun advSceneLine(): String {
    var tot = 0
    var avatar = 0
    var tex = 0
    var shape = 0
    var mesh = 0
    var nada = 0
    try { synchronized(recs) { tot = recs.size } } catch(_: Throwable) {}
    try { synchronized(recs) { avatar = recs.values.count { it.tipo == 47 } } } catch(_: Throwable) {}
    try { synchronized(recs) { tex = recs.values.count { it.tipo != 47 && hasRealTex(it) } } } catch(_: Throwable) {}
    try { synchronized(recs) { shape = recs.values.count { it.tipo != 47 && it.hasShape } } } catch(_: Throwable) {}
    try { synchronized(recs) { mesh = recs.values.count { it.tipo != 47 && it.meshId.isNotEmpty() } } } catch(_: Throwable) {}
    try { synchronized(recs) { nada = recs.values.count { it.tipo != 47 && !hasRealTex(it) && !it.hasShape && it.meshId.isEmpty() } } } catch(_: Throwable) {}
    return "ADV-SCENE recs=" + tot + " avatar=" + avatar + " tex=" + tex + " forma=" + shape + " mesh=" + mesh + " pelados=" + nada
  }
  private const val NULL_UUID = "00000000-0000-0000-0000-000000000000"
  private fun hasRealTex(r: Prim): Boolean {
    try { if (r.tex.isNotEmpty() && r.tex != NULL_UUID) return true } catch(_: Throwable) {}
    try { for (f in r.texFaces) { if (f.uuid.isNotEmpty() && f.uuid != NULL_UUID) return true } } catch(_: Throwable) {}
    return false
  }
  fun censoLine(): String {
    return "CENSO terse=" + censoTerse.size + " comp=" + censoComp.size + " full=" + censoFull.size + " cached=" + censoCached.size + " attach=" + censoAttach.size + " recs=" + count() + " capT=" + nTerseCap + " capF=" + nFullCap
  }
  fun tiposLine(): String {
    try {
      var mesh = 0
      var simple = 0
      var avatar = 0
      var sintipo = 0
      var conTex = 0
      synchronized(recs) {
        for (r in recs.values) {
          if (r.tipo == 47) avatar += 1
          else if (r.meshId.isNotEmpty()) mesh += 1
          else if (r.tipo == -1) sintipo += 1
          else simple += 1
          if (r.tex.isNotEmpty()) conTex += 1
        }
      }
      return "TIPOS mesh=" + mesh + " simple=" + simple + " sintipo=" + sintipo + " avatar=" + avatar + " conTex=" + conTex
    } catch (_: Throwable) { return "TIPOS error" }
  }
  @Volatile var meshExtraBlocks = 0L
  @Volatile var meshParams = 0L
  @Volatile var meshIds = 0L
  @Volatile var meshParamLast = "-"
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
    nAnsweredReq = 0L
    nFullNoRec = 0L
    nFullTex = 0L
    nFullSinTex = 0L
    nEvict = 0L
    nTerseQ = 0L
    nTerseF = 0L
    nTerseCap = 0L
    nFullCap = 0L
    try { synchronized(recs) { censoTerse.clear() } } catch(_: Throwable) {}
    try { synchronized(recs) { censoComp.clear() } } catch(_: Throwable) {}
    try { synchronized(recs) { censoFull.clear() } } catch(_: Throwable) {}
    try { synchronized(recs) { censoCached.clear() } } catch(_: Throwable) {}
    try { synchronized(recs) { censoAttach.clear() } } catch(_: Throwable) {}
    try { synchronized(recs) { reqMultPend.clear() } } catch(_: Throwable) {}
    try { synchronized(recs) { reqMultDone.clear() } } catch(_: Throwable) {}
    try { synchronized(recs) { reqMultTime.clear() } } catch(_: Throwable) {}
    try { synchronized(recs) { answeredIds.clear() } } catch(_: Throwable) {}
    try { synchronized(recs) { evictSample.clear() } } catch(_: Throwable) {}
    try { synchronized(recs) { killSample.clear() } } catch(_: Throwable) {}
    try { synchronized(recs) { pubPrev.clear() } } catch(_: Throwable) {}
    try { pubDiffLatch = "sin-pub-aun" } catch(_: Throwable) {}
    try { advReqLatch = "sin-req-aun" } catch(_: Throwable) {}
    nReqMultSent = 0L
    nReqMultPk = 0L
    meshExtraBlocks = 0L
    meshParams = 0L
    meshIds = 0L
    meshParamLast = "-"
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
    sixtySticky = ""
    try { pendingTexQ.clear() } catch(_: Throwable) {}
    texEmitTotal = 0L
    texEmitSesion = 0L
    try { texIds.clear() } catch(_: Throwable) {}
    lastKillId = -1L
    try { hexFam.clear() } catch(_: Throwable) {}
    lastSum = 0L
    try { estadoLatch = "" } catch(_: Throwable) {}
    try { texEstadoLatch = "" } catch(_: Throwable) {}
    try { attachEstadoLatch = "" } catch(_: Throwable) {}
    try { sixtyLastEmit = 0L } catch(_: Throwable) {}
    try { diagLatch = "" } catch(_: Throwable) {}
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
  private data class TextureEntryFields(val faces: List<TextureFace>)
  // TE has a default value and a variable-length mask/value pair for each face override.
  private fun parseTextureEntry(raw: ByteArray): TextureEntryFields? {
    try {
      var o = 0
      fun field(size: Int): Array<ByteArray>? {
        if (size <= 0 || o + size > raw.size) return null
        val base = raw.copyOfRange(o, o + size)
        o += size
        val values = Array(45) { base }
        var guard = 0
        while (o < raw.size && guard++ < 64) {
          var flags = 0L
          var more: Boolean
          var bytes = 0
          do {
            if (o >= raw.size || bytes++ >= 10) return null
            val v = raw[o++].toInt() and 255
            flags = (flags shl 7) or (v and 0x7f).toLong()
            more = (v and 0x80) != 0
          } while (more)
          if (flags == 0L) return values
          if (o + size > raw.size) return null
          val overrideBytes = raw.copyOfRange(o, o + size)
          o += size
          for (face in values.indices) if ((flags and (1L shl face)) != 0L) values[face] = overrideBytes
        }
        return null
      }
      val ids = field(16) ?: return null
      val colors = field(4) ?: return null
      val scalesS = field(4) ?: return null
      val scalesT = field(4) ?: return null
      val offsetsS = field(2) ?: return null
      val offsetsT = field(2) ?: return null
      val rotations = field(2) ?: return null
      fun makeFace(i: Int): TextureFace {
        val idHex = hexPrev(ids[i], 16).lowercase(Locale.US)
        val uuid = idHex.substring(0,8)+"-"+idHex.substring(8,12)+"-"+idHex.substring(12,16)+"-"+idHex.substring(16,20)+"-"+idHex.substring(20,32)
        val sc = ByteBuffer.wrap(scalesS[i]).order(ByteOrder.LITTLE_ENDIAN).float.coerceIn(-100f,100f)
        val tc = ByteBuffer.wrap(scalesT[i]).order(ByteOrder.LITTLE_ENDIAN).float.coerceIn(-100f,100f)
        val so = ByteBuffer.wrap(offsetsS[i]).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val to = ByteBuffer.wrap(offsetsT[i]).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val ro = ByteBuffer.wrap(rotations[i]).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val c = colors[i]
        return TextureFace(uuid, sc, tc, so / 32767f, to / 32767f, (ro / 32768f) * (Math.PI * 2.0).toFloat(),
          (255 - (c[0].toInt() and 255)) / 255f, (255 - (c[1].toInt() and 255)) / 255f,
          (255 - (c[2].toInt() and 255)) / 255f, (255 - (c[3].toInt() and 255)) / 255f)
      }
      return TextureEntryFields((0 until 45).map(::makeFace))
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
  private var sixtyLastEmit = 0L
  @Volatile var estadoLatch = ""
  @Volatile var texEstadoLatch = ""
  @Volatile var attachEstadoLatch = ""
  @Volatile var pubLast = 0
  @Volatile var recsLast = 0
  fun pubLine(): String {
    return "PUB pub=" + pubLast + " recs=" + recsLast
  }
  fun respLine(): String {
    return "REQ-RESP pedidas=" + nReqMultSent + " contestadas=" + nAnsweredReq + " fullSinRec=" + nFullNoRec + " evict=" + nEvict
  }
  fun estadoFijo(): String {
    val a = try { estadoLatch } catch(_: Throwable) { "" }
    val b = try { texEstadoLatch } catch(_: Throwable) { "" }
    val c = try { attachEstadoLatch } catch(_: Throwable) { "" }
    val d = try { diagLatch } catch(_: Throwable) { "" }
    var out = ""
    try { if (a.isNotEmpty()) out += "FIJO-" + a + "\n" } catch(_: Throwable) {}
    try { if (b.isNotEmpty()) out += "FIJO-" + b + "\n" } catch(_: Throwable) {}
    try { if (c.isNotEmpty()) out += "FIJO-" + c } catch(_: Throwable) {}
    try { if (d.isNotEmpty() && out.isNotEmpty()) out += "\n" } catch(_: Throwable) {}
    try { if (d.isNotEmpty()) out += "FIJO-DIAG " + d } catch(_:Throwable) {}
    try { if (out.isEmpty()) out = "FIJO-sin-estado-aun" } catch(_: Throwable) {}
    return out.trim()
  }
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
  fun texList(ax: Double = 0.0, ay: Double = 0.0, az: Double = 0.0): List<String> {
    try {
      val seen = LinkedHashSet<String>()
      synchronized(recs) {
        val sorted = recs.values.sortedBy { r -> (r.x - ax) * (r.x - ax) + (r.y - ay) * (r.y - ay) + (r.z - az) * (r.z - az) }
        for (r in sorted) {
          val ids = if (r.texFaces.isEmpty()) listOf(r.tex) else r.texFaces.map { it.uuid }
          for (id in ids) {
            if (id.isNotEmpty() && id != "00000000-0000-0000-0000-000000000000" && seen.add(id)) { if (seen.size >= 256) return seen.toList() }
          }
        }
      }
      return seen.toList()
    } catch(_: Throwable) { return emptyList() }
  }
  fun sweepTexless(ax: Double = 0.0, ay: Double = 0.0, az: Double = 0.0, now: Long = 0L): Int {
    try {
      var n = 0
      synchronized(recs) {
        for (r in recs.values) {
          if (r.tipo == 47) continue
          if (hasRealTex(r)) continue
          if (reqMultDone.contains(r.id)) continue
          if (reqMultPend.size >= 20000) break
          if (reqMultPend.add(r.id)) n++
        }
        if (now > 0L && reqMultPend.size < 96) {
          val cand = ArrayList<Prim>()
          for (r in recs.values) {
            if (r.tipo == 47) continue
            if (hasRealTex(r)) continue
            if (!reqMultDone.contains(r.id)) continue
            if (reqMultPend.contains(r.id)) continue
            val t = try { reqMultTime[r.id] ?: 0L } catch(_: Throwable) { 0L }
            if (now - t < 15000L) continue
            cand.add(r)
          }
          cand.sortBy { r -> (r.x - ax) * (r.x - ax) + (r.y - ay) * (r.y - ay) + (r.z - az) * (r.z - az) }
          for (r in cand) {
            if (reqMultPend.size >= 96) break
            if (reqMultPend.add(r.id)) n++
          }
        }
      }
      return n
    } catch(_: Throwable) { return 0 }
  }  fun pollTexFull(): String? { try { synchronized(recs) { for (r in recs.values) { if (r.tex.isNotEmpty()) return r.tex } } } catch(_: Throwable) {}; return null }
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
        try { censoAdd(censoAttach, id) } catch(_: Throwable) {}
        try { synchronized(recs) { if (deadIds.size < 20000) deadIds.add(id) } } catch(_: Throwable) {}
        try { lastPutReject = "attach id=" + id } catch(_: Throwable) {}
        try { if (attachArmed && attachMuestra == null) { attachMuestra = "ATTACH-MUESTRA id=" + id + " xyz=" + x + "," + y + "," + z + " t=" + tipo; attachArmed = false } } catch(_: Throwable) {}
        return
      }
      if (x < 0.0 || x > 256.0 || y < 0.0 || y > 256.0 || z < -200.0 || z > 2000.0) {
        try { nFueraRango++ } catch(_: Throwable) {}
        try { synchronized(recs) { if (deadIds.size < 20000) deadIds.add(id) } } catch(_: Throwable) {}
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
      try { synchronized(recs) { deadIds.remove(id) } } catch(_: Throwable) {}
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
      try { if (count > 256) nTerseCap += (count - 256).toLong() } catch (_: Throwable) {}
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
          try { censoAdd(censoTerse, id) } catch (_: Throwable) {}
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
            try { censoAdd(censoTerse, id) } catch (_: Throwable) {}
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
            try { censoAdd(censoTerse, id) } catch (_: Throwable) {}
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
          try { censoAdd(censoComp, id) } catch(_: Throwable) {}
          try { synchronized(recs) { if (reqMultDone.contains(id)) { nAnsweredReq++; answeredIds.add(id) } } } catch(_: Throwable) {}
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
      try { if (count > 64) nFullCap += (count - 64).toLong() } catch (_: Throwable) {}
      while (guard > 0) {
        guard -= 1
        var muId = -1L
        var muIlen = -1
        var muPc = -1
        var muIn = ""
        var wA = ByteArray(0)
        var wC = ByteArray(0)
        var wD = ByteArray(0)
        var wExtra = ByteArray(0)
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
          try { censoAdd(censoFull, id) } catch (_: Throwable) {}
          put(id, pcode, x, y, z, sx, sy, sz, yw, now, hb[26].toInt() and 0xFF)
        }
        o += ilen
        // CITA-DECODE formas (manda el codigo, no el comentario de la plantilla): LibreMetaverse Primitive.cs UnpackBeginCut/UnpackEndCut/UnpackPathScale/UnpackPathShear/UnpackPathTwist/UnpackPathTaper/UnpackPathRevolutions/UnpackProfileHollow (copia scratch/upstream/LM-Primitive.cs:1537-1631) + OpenSim PrimitiveBaseShape.cs ToPrim (PathEnd=1-raw*2e-5, ProfileEnd=1-raw*2e-5, Scale=(200-raw)*0.01, Shear S8 con signo, Rev=1+raw*0.015; copia scratch/upstream/PrimitiveBaseShape.cs:1427-1445). Los comentarios quanta=0.01 de message_template.msg estan obsoletos: el End va INVERTIDO (raw 0 = sin corte = 1.0).
        if (o + 31 > p.size) { try { stashFullMu("in30", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}; break }
        val fPathCurve = p[o + 8].toInt() and 255
        val fProfileCurve = p[o + 9].toInt() and 255
        val fPb = (u16at(p, o + 10) * 0.00002f).coerceIn(0f, 1f)
        val fPe = (1f - u16at(p, o + 12) * 0.00002f).coerceIn(0f, 1f)
        val fPsx = (200 - (p[o + 14].toInt() and 255)) * 0.01f
        val fPsy = (200 - (p[o + 15].toInt() and 255)) * 0.01f
        val fShx = p[o + 16].toInt() / 100f
        val fShy = p[o + 17].toInt() / 100f
        val fTw = p[o + 18].toInt() / 100f
        val fTwb = p[o + 19].toInt() / 100f
        val fRo = p[o + 20].toInt() / 100f
        val fTpx = p[o + 21].toInt() / 100f
        val fTpy = p[o + 22].toInt() / 100f
        val fRev = 1f + (p[o + 23].toInt() and 255) * 0.015f
        val fSk = p[o + 24].toInt() / 100f
        val fQb = (u16at(p, o + 25) * 0.00002f).coerceIn(0f, 1f)
        val fQe = (1f - u16at(p, o + 27) * 0.00002f).coerceIn(0f, 1f)
        val fQh = (u16at(p, o + 29) * 0.00002f).coerceIn(0f, 1f)
        o += 31
        try { synchronized(recs) { recs[id]?.let { it.pathCurve = fPathCurve; it.profileCurve = fProfileCurve; it.hasShape = true; it.shPb = fPb; it.shPe = fPe; it.shPsx = fPsx; it.shPsy = fPsy; it.shShx = fShx; it.shShy = fShy; it.shTw = fTw; it.shTwb = fTwb; it.shRo = fRo; it.shTpx = fTpx; it.shTpy = fTpy; it.shRev = fRev; it.shSk = fSk; it.shQb = fQb; it.shQe = fQe; it.shQh = fQh } } } catch(_: Throwable) {}
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
        sgv = skipGet(p, o, false)
        if (sgv.first < 0) { try { stashFullMu("skH", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}; break }
        o = sgv.first
        wExtra = sgv.second
        // TextureEntry V2 has the default image UUID first, then typed fields with face overrides.
        try {
          val te = parseTextureEntry(wA)
          try { if (te != null && te.faces[0].uuid.isNotEmpty() && te.faces[0].uuid != NULL_UUID) nFullTex++ else nFullSinTex++ } catch(_: Throwable) {}
          if (te != null) {
            try { synchronized(recs) { val r = recs[id]; if (r != null) { val face0 = te.faces[0]; if (r.tex.isEmpty()) r.tex = face0.uuid; r.texFaces = te.faces; r.texScaleS = face0.scaleS; r.texScaleT = face0.scaleT; r.texOffsetS = face0.offsetS; r.texOffsetT = face0.offsetT; r.texRotation = face0.rotation; r.texR = face0.r; r.texG = face0.g; r.texB = face0.b; r.texA = face0.a } } } catch(_: Throwable) {}
            try { if (texIds.size < 8 && texIds.add(id)) { val tl = "TEX-UUID id=" + id + " u=" + te.faces[0].uuid + " id8=" + te.faces[0].uuid.take(8) + " uv=" + te.faces[0].scaleS + "," + te.faces[0].scaleT + "," + te.faces[0].offsetS + "," + te.faces[0].offsetT + "," + te.faces[0].rotation; try { texEmitTotal++ } catch(_: Throwable) {}; try { texEmitSesion++ } catch(_: Throwable) {}; try { onTexLine?.invoke(tl) } catch(_: Throwable) {} } } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
        if (o + 66 > p.size) { try { stashFullMu("fix66", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}; break }
        val meshId = meshIdFromExtraParams(wExtra)
        try {
          if (wExtra.isNotEmpty()) meshExtraBlocks++
          synchronized(recs) { recs[id]?.let { it.meshId = meshId ?: "" } }
          if (meshId != null) { meshIds++; meshParamLast = meshId; MeshAssets.request(meshId) }
        } catch(_: Throwable) {}
        try { synchronized(recs) { if (reqMultDone.contains(id)) { nAnsweredReq++; answeredIds.add(id) } } } catch(_: Throwable) {}
        try { synchronized(recs) { if (!recs.containsKey(id)) nFullNoRec++ } } catch(_: Throwable) {}
        o += 66
        try { stashFullMu("ok", muId, muIlen, muPc, muIn, wA.size, wC.size, wD.size) } catch(_: Throwable) {}
        got += 1
      }
      return got
    } catch(_: Throwable) { return 0 }
  }
  private fun meshIdFromExtraParams(raw: ByteArray): String? {
    try {
      if (raw.isEmpty()) return null
      var o = 0
      val count = raw[o++].toInt() and 255
      if (count > 32) return null
      repeat(count) {
        if (o + 6 > raw.size) return null
        val type = u16at(raw, o)
        o += 2
        val size = ByteBuffer.wrap(raw, o, 4).order(ByteOrder.LITTLE_ENDIAN).int
        o += 4
        if (size < 0 || size > 1024 || o + size > raw.size) return null
        if (type == 0x30 || type == 0x60) {
          meshParams++
          meshParamLast = "type=${type.toString(16)} size=$size"
        }
        if ((type == 0x30 || type == 0x60) && size >= 17 && ((raw[o + 16].toInt() and 255) and 0x0f) == 5) {
          val hex = hexPrev(raw.copyOfRange(o, o + 16), 16).lowercase(Locale.US)
          return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32)
        }
        o += size
      }
    } catch (_: Throwable) {}
    return null
  }
  fun meshStatus(): String = "MESH-PRIM extra=$meshExtraBlocks params=$meshParams ids=$meshIds last=$meshParamLast"
  fun drainReqMult(max: Int, ax: Double = 0.0, ay: Double = 0.0, az: Double = 0.0): List<Long> {
    try {
      synchronized(recs) {
        val out = ArrayList<Long>(max)
        val nowD = try { System.currentTimeMillis() } catch(_: Throwable) { 0L }
        if (reqMultPend.isEmpty()) return out
        val sorted = reqMultPend.toList().sortedBy { id -> distPend(id, ax, ay, az) }
        for (id in sorted) {
          if (out.size >= max) break
          try { if (deadIds.contains(id)) { reqMultPend.remove(id); continue } } catch(_: Throwable) {}
          if (reqMultPend.remove(id)) {
            reqMultDone.add(id)
            try { reqMultTime[id] = nowD } catch(_: Throwable) {}
            out.add(id)
          }
        }
        try { if (reqMultTime.size > 20000) reqMultTime.keys.firstOrNull()?.let { reqMultTime.remove(it) } } catch(_: Throwable) {}
        try { if (answeredIds.size > 20000) answeredIds.remove(answeredIds.first()) } catch(_: Throwable) {}
        return out
      }
    } catch(_: Throwable) { return emptyList() }
  }
  private fun distPend(id: Long, ax: Double, ay: Double, az: Double): Double {
    try {
      val r = synchronized(recs) { recs[id] }
      if (r == null) return Double.MAX_VALUE
      return (r.x - ax) * (r.x - ax) + (r.y - ay) * (r.y - ay) + (r.z - az) * (r.z - az)
    } catch(_: Throwable) { return Double.MAX_VALUE }
  }
  fun reqMultLine(): String {
    return "REQ-MULT pend=" + (try { synchronized(recs) { reqMultPend.size } } catch(_: Throwable) { -1 }) + " done=" + (try { synchronized(recs) { reqMultDone.size } } catch(_: Throwable) { -1 }) + " sent=" + nReqMultSent + " pk=" + nReqMultPk
  }
  private fun parseCached(p: ByteArray): Int {
    try {
      if (p.size < 11) return 0
      val count = p[10].toInt() and 0xFF
      if (count <= 0) return 0
      if (11 + count * 12 > p.size) return 0
      var i = 0
      while (i < count) {
        try {
          val id = ByteBuffer.wrap(p, 11 + i * 12, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
          synchronized(recs) {
            censoAdd(censoCached, id)
            if (!recs.containsKey(id) && !reqMultDone.contains(id) && !deadIds.contains(id) && reqMultPend.size < 20000) reqMultPend.add(id)
          }
        } catch (_: Throwable) {}
        i += 1
      }
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
            try { noteKill(id) } catch(_: Throwable) {}
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
        try { if (sxLine != null) sixtySticky = sxLine } catch(_: Throwable) {}
        try { if (sxLine != null && sxLine.startsWith("SIXTY-OFF") && now - sixtyLastEmit < 10000L) sxLine = null } catch(_: Throwable) {}
        try { if (sxLine != null && sxLine.startsWith("SIXTY-OFF")) sixtyLastEmit = now } catch(_: Throwable) {}
        if (sxLine != null) return sxLine
        var fxLine: String? = null
        try { if (fullQ.isNotEmpty()) fxLine = fullQ.removeAt(0) } catch(_: Throwable) {}
        if (fxLine != null) return fxLine
        var exLine: String? = null
        try { if (extraQ.isNotEmpty()) exLine = extraQ.removeAt(0) } catch(_: Throwable) {}
        if (exLine == "HISTO") return brHistLine()
        if (exLine == "TERSEN") return terseLine()
        if (exLine == "ATTN") {
          val atEstado = "ATTACH-ESTADO attach=" + nAttach
          try { attachEstadoLatch = atEstado } catch(_: Throwable) {}
          return atEstado
        }
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
          synchronized(recs) { for (r in recs.values) { tot += 1; if (hasRealTex(r)) con += 1 } }
          val texLine2 = "TEX-ESTADO con=" + con + " sin=" + (tot - con) + " obj=" + tot + " emit=" + texEmitTotal
          try { texEstadoLatch = texLine2 } catch(_: Throwable) {}
          return texLine2
        } catch(_: Throwable) {}
      }
      if (now - lastSum >= 30000L) {
        lastSum = now
        try { extraQ.add("HISTO") } catch(_: Throwable) {}
        try { extraQ.add("TERSEN") } catch(_: Throwable) {}
        try { extraQ.add("ATTN") } catch(_: Throwable) {}
        try { attachArmed = true } catch(_: Throwable) {}
        val estLine = "PRIMS-ESTADO terse=" + nTerse + "/" + nObjTerse + "(q=" + nTerseQ + " f=" + nTerseF + ") comp=" + nComp + "/" + nObjComp + " full=" + nFull + "/" + nObjFull + " cached=" + nCached + " kill=" + nKill + "(hit=" + nKillHit + " miss=" + nKillMiss + ") fuera=" + nFueraRango + " lenMalo=" + nLenMalo + " escMala=" + nEscMala + " zeroFix=" + nZeroFix + " obj=" + count() + " reqMultPend=" + (try { synchronized(recs) { reqMultPend.size } } catch(_: Throwable) { -1 }) + " reqMultSent=" + nReqMultSent + " reqMultPk=" + nReqMultPk + " FUERA-MUESTRA " + (if (fueraMuestra.isEmpty()) "ninguna" else fueraMuestra)
        try { estadoLatch = estLine } catch(_: Throwable) {}
        try { diagLatch = sixtySticky + " | " + brHistLine() + " | " + shapeLine() } catch(_: Throwable) {}
        return estLine
      }
      return null
    } catch(_: Throwable) { return null }
  }
  fun shapeLine(): String {
    try {
      var has = 0
      var tot = 0
      val hist = LinkedHashMap<Int, Int>()
      synchronized(recs) {
        for (r in recs.values) {
          if (r.tipo == 47) continue
          tot++
          if (r.hasShape) has++
          val k = r.pathCurve
          try { hist[k] = (hist[k] ?: 0) + 1 } catch(_: Throwable) {}
        }
      }
      var hs = ""
      for (e in hist.entries.sortedByDescending { it.value }.take(6)) {
        hs += e.key.toString() + ":" + e.value.toString() + ","
      }
      return "SHAPE-N tot=" + tot + " has=" + has + " paths=" + hs
    } catch(_: Throwable) { return "SHAPE-N error" }
  }
  fun shapeMuestra(): String {
    try {
      val arr = ArrayList<String>()
      synchronized(recs) {
        val shaped = recs.values.filter { it.tipo != 47 && it.hasShape }
        val sorted = shaped.sortedBy { r -> (r.x - pubAx) * (r.x - pubAx) + (r.y - pubAy) * (r.y - pubAy) + (r.z - pubAz) * (r.z - pubAz) }
        for (r in sorted.take(3)) {
          arr.add(r.id.toString() + " xyz=" + "%.1f,%.1f,%.1f".format(r.x, r.y, r.z) + " path=" + r.pathCurve + " prof=" + r.profileCurve + " pb=" + r.shPb + " pe=" + r.shPe + " qb=" + r.shQb + " qe=" + r.shQe + " dist=" + Math.sqrt((r.x - pubAx) * (r.x - pubAx) + (r.y - pubAy) * (r.y - pubAy) + (r.z - pubAz) * (r.z - pubAz)).toInt() + "m")
        }
      }
      return "SHAPE-MUESTRA " + (if (arr.isEmpty()) "sin-formas" else arr.joinToString(" | "))
    } catch(_: Throwable) { return "SHAPE-MUESTRA error" }
  }
  fun fullTexLine(): String {
    return "FULL-TEX conTex=" + nFullTex + " sinTex=" + nFullSinTex
  }
  fun publish(ax: Double, ay: Double, az: Double): List<Prim> {
    try {
      synchronized(recs) {
        val now = System.currentTimeMillis()
        val it = recs.values.iterator()
        while (it.hasNext()) {
          val r = it.next()
          if (r.tipo == 47 && now - r.seen > 300000L) it.remove()
        }
        val all = recs.values.toList()
        try { recsLast = all.size } catch (_: Throwable) {}
        val sorted = all.sortedBy { r -> (r.x - ax) * (r.x - ax) + (r.y - ay) * (r.y - ay) + (r.z - az) * (r.z - az) }
        if (recs.size > 4000) {
          var i = 0
          for (r in sorted) {
            i += 1
            if (i > 4000) {
              try { noteEvict(r, ax, ay, az) } catch(_: Throwable) {}
              try { recs.remove(r.id) } catch(_: Throwable) {}
              try { nEvict++ } catch(_: Throwable) {}
            }
          }
        }
        val pub = sorted.take(1024).map { r -> r.copy() }
        try { pubLast = pub.size } catch (_: Throwable) {}
        try { pubAx = ax } catch(_: Throwable) {}
        try { pubAy = ay } catch(_: Throwable) {}
        try { pubAz = az } catch(_: Throwable) {}
        try { updatePubDiff(pub) } catch(_: Throwable) {}
        try { maybeAdvReq() } catch(_: Throwable) {}
        return pub
      }
    } catch(_: Throwable) { return emptyList() }
  }
}
