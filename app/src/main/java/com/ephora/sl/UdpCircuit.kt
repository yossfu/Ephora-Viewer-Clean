package com.ephora.sl
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
object UdpCircuit {
  var seq = 1
  @Volatile var sessionSock: DatagramSocket? = null
  @Volatile var sessionAddr: InetAddress? = null
  fun ensureSessionSock(): DatagramSocket {
    var s = sessionSock
    if (s == null || s.isClosed) {
      s = DatagramSocket()
      sessionSock = s
    }
    return s
  }
  var rxTotal = 0L
  val rxByName = LinkedHashMap<String, Long>()
  fun noteRx(name: String) {
    rxTotal++
    rxByName[name] = (rxByName[name] ?: 0L) + 1
    if (rxByName.size > 24) { val k = rxByName.keys.first(); rxByName.remove(k) }
  }
  fun rxCountLine(): String {
    val top = rxByName.entries.sortedByDescending { it.value }.take(6).joinToString(",") { it.key + ":" + it.value }
    return "RX-COUNT total=" + rxTotal + (if (top.isNotEmpty()) " " + top else "")
  }
  fun resetRx() { rxTotal = 0; rxByName.clear() }
  fun lastSeq(): Long = (seq - 1).toLong() and 0xFFFFFFFFL
  fun uuidBE(u: String): ByteArray {
    val id = try { UUID.fromString(u) } catch(_: Throwable) { UUID(0L, 0L) }
    val bb = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
    bb.putLong(id.mostSignificantBits)
    bb.putLong(id.leastSignificantBits)
    return bb.array()
  }
  fun headerReliable(): ByteArray {
    val bb = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
    bb.put(0x40.toByte())
    bb.putInt(seq++)
    bb.put(0x00.toByte())
    return bb.array()
  }
  fun headerUnreliable(): ByteArray {
    val bb = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
    bb.put(0x00.toByte())
    bb.putInt(seq++)
    bb.put(0x00.toByte())
    return bb.array()
  }
  fun useCircuitCode(circuit: Int, sessionId: String, agentId: String): ByteArray {
    val h = headerReliable()
    val p = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
    p.putShort(-1)
    p.put(0x00.toByte())
    p.put(0x03.toByte())
    p.putInt(circuit)
    p.order(ByteOrder.BIG_ENDIAN)
    p.put(uuidBE(sessionId))
    p.put(uuidBE(agentId))
    return h + p.array()
  }
  fun completeMovement(agentId: String, sessionId: String, circuit: Int): ByteArray {
    val h = headerReliable()
    val p = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
    p.putShort(-1)
    p.put(0x00.toByte())
    p.put(0xF9.toByte())
    p.order(ByteOrder.BIG_ENDIAN)
    p.put(uuidBE(agentId))
    p.put(uuidBE(sessionId))
    p.order(ByteOrder.LITTLE_ENDIAN)
    p.putInt(circuit)
    return h + p.array()
  }
  fun handshakeReply(agentId: String, sessionId: String): ByteArray {
    val h = headerReliable()
    val p = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
    p.putShort(-1)
    p.put(0x00.toByte())
    p.put(0x95.toByte())
    p.order(ByteOrder.BIG_ENDIAN)
    p.put(uuidBE(agentId))
    p.put(uuidBE(sessionId))
    p.order(ByteOrder.LITTLE_ENDIAN)
    p.putInt(0)
    return h + p.array()
  }
  fun ackPacket(seqs: List<Long>): ByteArray {
    val h = headerUnreliable()
    val p = ByteBuffer.allocate(4 + seqs.size * 4 + 1).order(ByteOrder.LITTLE_ENDIAN)
    p.put(0xFF.toByte())
    p.put(0xFF.toByte())
    p.put(0xFF.toByte())
    p.put(0xFB.toByte())
    p.put(seqs.size.toByte())
    for (s in seqs) p.putInt(s.toInt())
    return h + p.array()
  }
  fun completePingCheck(pingId: Int): ByteArray {
    val h = headerUnreliable()
    return h + byteArrayOf(0x02.toByte(), pingId.toByte())
  }
  fun hexPrev(b: ByteArray, n: Int): String {
    val sb = StringBuilder()
    for (i in 0 until n.coerceAtMost(b.size)) sb.append("%02X".format(b[i]))
    return sb.toString()
  }
  fun txHex(name: String, seqUsed: Long, pkt: ByteArray, n: Int = 64): String {
    return "TX-HEX " + name + " seq=" + seqUsed + " " + hexPrev(pkt, n.coerceAtMost(pkt.size))
  }
  fun zeroDecode(data: ByteArray): ByteArray {
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
  }
  data class Rx(val flags: Int, val seq: Long, val msgId: Int, val name: String, val ackRx: Int, val raw: ByteArray)
  data class Decoded(val flags: Int, val seq: Long, val msgId: Int, val payload: ByteArray, val ackRx: Int)
  fun msgName(id: Int): String {
    return when (id) {
      1 -> "StartPingCheck"
      2 -> "CompletePingCheck"
      4 -> "AgentUpdate"
      0xFF06 -> "CoarseLocationUpdate"
      0xFFFF0003.toInt() -> "UseCircuitCode"
      0xFFFF00F9.toInt() -> "CompleteAgentMovement"
      0xFFFF0094.toInt() -> "RegionHandshake"
      0xFFFF0095.toInt() -> "RegionHandshakeReply"
      0xFFFF00FA.toInt() -> "AgentMovementComplete"
      0xFFFF008B.toInt() -> "ChatFromSimulator"
      0xFFFF0008.toInt() -> "RequestImage"
      9 -> "ImageData"
      10 -> "ImagePacket"
      86 -> "ImageNotInDatabase"
      0xFFFF0056.toInt() -> "ImageNotInDatabase"
      0xFFFF0040.toInt() -> "TeleportLocal"
      0xFFFF0042.toInt() -> "TeleportProgress"
      0xFFFF0045.toInt() -> "TeleportFinish"
      0xFFFF0048.toInt() -> "TeleportCancel"
      0xFFFF0049.toInt() -> "TeleportStart"
      0xFFFF0080.toInt() -> "ChatFromViewer"
      0xFFFFFFFB.toInt() -> "PacketAck"
      -1 -> "ACK-solo"
      else -> { val u = id.toLong() and 0xFFFFFFFFL; if (u > 0xFFFFL) "otro:" + java.lang.Long.toHexString(u).uppercase() else "med:" + java.lang.Long.toHexString(u).uppercase() }
    }
  }
  fun decode(buf: ByteArray, len: Int): Decoded? {
    if (len < 6) return null
    val flags = buf[0].toInt() and 0xFF
    val sq = ByteBuffer.wrap(buf, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
    var end = len
    var ackRx = 0
    if ((flags and 0x10) != 0 && len > 7) {
      val count = buf[len - 1].toInt() and 0xFF
      if (count <= 32 && 1 + count * 4 <= len - 6) {
        ackRx = count
        end = len - 1 - count * 4
      }
    }
    if (end <= 6) return Decoded(flags, sq, -1, ByteArray(0), ackRx)
    var body = buf.copyOfRange(6, end)
    if ((flags and 0x80) != 0) body = zeroDecode(body)
    if (body.isEmpty()) return Decoded(flags, sq, -1, ByteArray(0), ackRx)
    val b0 = body[0].toInt() and 0xFF
    var id = -1
    var idLen = 1
    if (b0 == 0xFF && body.size >= 4 && (body[1].toInt() and 0xFF) == 0xFF) {
      id = ByteBuffer.wrap(body, 0, 4).order(ByteOrder.BIG_ENDIAN).int
      idLen = 4
    } else if (b0 == 0xFF && body.size >= 2) {
      id = 0xFF00 or (body[1].toInt() and 0xFF)
      idLen = 2
    } else {
      id = b0
      idLen = 1
    }
    val data = body.copyOfRange(idLen, body.size)
    return Decoded(flags, sq, id, data, ackRx)
  }
  fun parseRx(buf: ByteArray, len: Int): Rx? {
    val d = decode(buf, len) ?: return null
    if (d.msgId == -1 && d.payload.isEmpty()) return Rx(d.flags, d.seq, -1, "ACK-solo", d.ackRx, buf.copyOf(len))
    return Rx(d.flags, d.seq, d.msgId, msgName(d.msgId), d.ackRx, buf.copyOf(len))
  }
  fun pingIdOf(buf: ByteArray, len: Int): Int? {
    val d = decode(buf, len) ?: return null
    if (d.msgId != 1 || d.payload.isEmpty()) return null
    return d.payload[0].toInt() and 0xFF
  }
  // RequestImage High 8 (message_template.msg: "RequestImage High 8 NotTrusted Unencoded" AgentData{AgentID,SessionID}+RequestImage{Image LLUUID,DiscardLevel S8,DownloadPriority F32,Packet U32,Type U8}; libopenmetaverse RequestImagePacket: Frequency=High ID=8 Reliable=true base 7B). Diferencial chat-vs-request: H1 destino=FALSO (mismo loopAddr/simPort en ambas vias), H2 zerocode=FALSO (el chat sale sin 0x80 y llega con acks+eco). Causa del silencio total: se enviaba Low-8 (FF FF 00 08, mensaje inexistente) y se escuchaba Low-9/10 (ImageData/ImagePacket reales son High 9/10 de 1 byte).
  fun requestImageUnrel(agentId: String, sessionId: String, imageUuid: String): ByteArray {
    return requestImageBody(headerUnreliable(), agentId, sessionId, imageUuid, 0)
  }
  fun requestImageType1(agentId: String, sessionId: String, imageUuid: String): ByteArray {
    return requestImageBody(headerReliable(), agentId, sessionId, imageUuid, 1)
  }
  fun requestImageLow8(agentId: String, sessionId: String, imageUuid: String): ByteArray {
    val h = headerReliable()
    val p = ByteBuffer.allocate(4 + 32 + 1 + 26).order(ByteOrder.LITTLE_ENDIAN)
    p.putShort(-1)
    p.put(0x00.toByte())
    p.put(0x08.toByte())
    p.order(ByteOrder.BIG_ENDIAN)
    p.put(uuidBE(agentId))
    p.put(uuidBE(sessionId))
    p.put(1.toByte())
    p.put(uuidBE(imageUuid))
    p.order(ByteOrder.LITTLE_ENDIAN)
    p.put(0.toByte())
    p.putFloat(100000f)
    p.putInt(0)
    p.put(0.toByte())
    return h + p.array()
  }
  fun requestImage(agentId: String, sessionId: String, imageUuid: String): ByteArray {
    return requestImageBody(headerReliable(), agentId, sessionId, imageUuid, 0)
  }
  private fun requestImageBody(h: ByteArray, agentId: String, sessionId: String, imageUuid: String, type: Int): ByteArray {
    val p = ByteBuffer.allocate(1 + 32 + 1 + 26).order(ByteOrder.BIG_ENDIAN)
    p.put(0x08.toByte())
    p.put(uuidBE(agentId))
    p.put(uuidBE(sessionId))
    p.put(1.toByte())
    p.put(uuidBE(imageUuid))
    p.order(ByteOrder.LITTLE_ENDIAN)
    p.put(0.toByte())
    p.putFloat(100000f)
    p.putInt(0)
    p.put(type.toByte())
    return h + p.array()
  }
  var imgGot = LinkedHashMap<String,Long>()
  var imgWant = LinkedHashMap<String,Long>()
  var imgSeen = LinkedHashMap<String,Long>()
  fun imgReset() { try { imgGot.clear() } catch(_: Throwable) {}
    try { imgWant.clear() } catch(_: Throwable) {}
    try { imgSeen.clear() } catch(_: Throwable) {} }
  private fun u32le(p: ByteArray, o: Int): Long {
    try {
      if (o + 4 > p.size || o < 0) return -1L
      return (ByteBuffer.wrap(p, o, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL)
    } catch(_: Throwable) { return -1L }
  }
  private fun u16le(p: ByteArray, o: Int): Int {
    try {
      if (o + 2 > p.size || o < 0) return -1
      return ByteBuffer.wrap(p, o, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    } catch(_: Throwable) { return -1 }
  }
  fun parseImage(msgId: Int, payload: ByteArray): String? {
    try {
      if (payload.size < 16) return null
      if (imgGot.size > 16) imgReset()
      val hex = hexPrev(payload.copyOfRange(0, 16), 16)
      val id8 = hex.take(8)
      if (msgId == 86) return "IMAGE-DATA id=" + id8 + " no-en-db"
      val isPkt = msgId == 10
      if (!isPkt && payload.size < 25) return null
      if (isPkt && payload.size < 20) return null
      var codec = -1
      var size = -1L
      var want = -1L
      var pktN = -1
      var dOff = 0
      if (!isPkt) {
        codec = payload[16].toInt() and 0xFF
        size = u32le(payload, 17)
        want = u16le(payload, 21).toLong()
        dOff = 25
      } else {
        pktN = u16le(payload, 16)
        dOff = 20
      }
      val dlen = u16le(payload, dOff - 2)
      if (dlen < 0) return null
      val got = payload.size - dOff
      var head = "-"
      try { head = hexPrev(payload.copyOfRange(dOff, (dOff + 16).coerceAtMost(payload.size)), 16) } catch(_: Throwable) {}
      val tot = (imgGot[hex] ?: 0L) + got
      imgGot[hex] = tot
      val seen = (imgSeen[hex] ?: 0L) + 1L
      imgSeen[hex] = seen
      if (!isPkt && want > 0) imgWant[hex] = want
      if (size < 0) size = imgWant[hex] ?: -1L
      val w = imgWant[hex] ?: -1L
      var line = "IMAGE-DATA id=" + id8
      if (!isPkt) line += " codec=" + codec + " size=" + size + " pkts=" + want
      else line += " pkt=" + pktN
      line += " got=" + got + " tot=" + tot + " head=" + head
      if ((size > 0 && tot >= size) || (w > 0 && seen >= w)) line += "\nTEX-TABLA-UDP id=" + id8 + " bytes=" + tot + " pkts=" + seen + "/" + w + " completo=si"
      return line
    } catch(_: Throwable) { return null }
  }
  // TeleportLandmarkRequest Low 65 (message_template.msg: "teleport to landmark asset ID destination. use LLUUID::null for home"). TeleportLocationRequest Low 63 (Info{RegionHandle U64 LE, Position LLVector3 LE, LookAt}; libopenmetaverse AgentManager.RequestTeleport: mismo orden y LE).
  fun teleportHome(agentId: String, sessionId: String): ByteArray {
    val h = headerReliable()
    val p = ByteBuffer.allocate(4 + 32 + 16).order(ByteOrder.BIG_ENDIAN)
    p.putShort(-1)
    p.put(0x00.toByte())
    p.put(0x41.toByte())
    p.put(uuidBE(agentId))
    p.put(uuidBE(sessionId))
    p.put(ByteArray(16))
    return h + p.array()
  }
  fun teleportLocation(agentId: String, sessionId: String, handle: Long, px: Float, py: Float, pz: Float): ByteArray {
    val h = headerReliable()
    val p = ByteBuffer.allocate(4 + 32 + 32).order(ByteOrder.BIG_ENDIAN)
    p.putShort(-1)
    p.put(0x00.toByte())
    p.put(0x3F.toByte())
    p.put(uuidBE(agentId))
    p.put(uuidBE(sessionId))
    p.order(ByteOrder.LITTLE_ENDIAN)
    p.putLong(handle)
    p.putFloat(px); p.putFloat(py); p.putFloat(pz)
    p.putFloat(0f); p.putFloat(1f); p.putFloat(0f)
    return h + p.array()
  }
  fun agentUpdate(agentId: String, sessionId: String, controlFlags: Int = 0, cx: Float = 128f, cy: Float = 128f, cz: Float = 25f, far: Float = 256f): ByteArray {
    val h = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
    h.put(0x00.toByte())
    h.putInt(seq++)
    h.put(0x00.toByte())
    val p = ByteBuffer.allocate(115).order(ByteOrder.LITTLE_ENDIAN)
    p.put(0x04.toByte())
    p.order(ByteOrder.BIG_ENDIAN)
    p.put(uuidBE(agentId))
    p.put(uuidBE(sessionId))
    p.order(ByteOrder.LITTLE_ENDIAN)
    repeat(3) { p.putFloat(0f) }
    repeat(3) { p.putFloat(0f) }
    p.put(0x00.toByte())
    p.putFloat(cx); p.putFloat(cy); p.putFloat(cz)
    p.putFloat(0f); p.putFloat(1f); p.putFloat(0f)
    p.putFloat(-1f); p.putFloat(0f); p.putFloat(0f)
    p.putFloat(0f); p.putFloat(0f); p.putFloat(1f)
    p.putFloat(far)
    p.putInt(controlFlags)
    p.put(0x00.toByte())
    return h.array() + p.array()
  }
  suspend fun handshakeOnce(): String = withContext(Dispatchers.IO) {
    val s = LoginManager.Session
    if (s.agentId.isBlank() || s.sessionId.isBlank()) return@withContext "UDP FAIL sin sesion: haz LOGIN primero"
    if (s.simIp.isBlank() || s.simPort == 0 || s.circuitCode == 0) return@withContext "UDP FAIL faltan campos sim=" + s.simIp + " sim_port=" + s.simPort + " circuit_fin=" + s.circuitCode.toString().takeLast(4)
    val cfin = s.circuitCode.toString().takeLast(4)
    val dest = s.simIp + ":" + s.simPort
    try {
      val sock = ensureSessionSock()
      try {
        sock.soTimeout = 1000
        val addr = InetAddress.getByName(s.simIp)
        sessionAddr = addr
        var txN = 0
        var rxN = 0
        var acks = 0
        var gotHs = false
        var gotName = "ACK-solo"
        var replied = false
        var complete = false
        var lastHex = "-"
        val det = StringBuilder()
        fun send(b: ByteArray) {
          sock.send(DatagramPacket(b, b.size, addr, s.simPort))
          txN++
        }
        fun drain(ms: Long, tag: String) {
          val end = System.currentTimeMillis() + ms
          while (System.currentTimeMillis() < end) {
            try {
              val buf = ByteArray(4096)
              val p = DatagramPacket(buf, buf.size)
              sock.receive(p)
              rxN++
              val rx = parseRx(p.data, p.length)
              if (rx == null) { det.append(tag + ":rx-malformado "); continue }
              noteRx(rx.name)
              lastHex = hexPrev(rx.raw, 16)
              acks += rx.ackRx
              if ((rx.flags and 0x40) != 0) {
                try { send(ackPacket(listOf(rx.seq))); acks++ } catch(_: Throwable) {}
              }
              det.append(tag + ":rx=" + p.length + "b tipoMsg=" + rx.name + " ackRx=" + rx.ackRx + " hex=" + lastHex + " | ")
              if (rx.msgId == 0xFFFF0094.toInt()) {
                gotHs = true; gotName = "RegionHandshake"
                try { decode(p.data, p.length)?.let { TerrainComposition.accept(it.payload) } } catch(_: Throwable) {}
              }
            } catch(e: java.net.SocketTimeoutException) { break }
          }
        }
        val ucc = useCircuitCode(s.circuitCode, s.sessionId, s.agentId)
        send(ucc)
        det.append(txHex("UseCircuitCode", lastSeq(), ucc) + " | ")
        drain(3000, "e1")
        if (rxN == 0) {
          val ucc2 = useCircuitCode(s.circuitCode, s.sessionId, s.agentId)
          send(ucc2)
          det.append(txHex("UseCircuitCode-reintento", lastSeq(), ucc2) + " | ")
          drain(3000, "e2")
        }
        val cm = completeMovement(s.agentId, s.sessionId, s.circuitCode)
        send(cm)
        complete = true
        det.append(txHex("CompleteAgentMovement", lastSeq(), cm) + " | ")
        drain(15000, "hs")
        if (gotHs) {
          try {
            val hr = handshakeReply(s.agentId, s.sessionId)
            send(hr)
            replied = true
            det.append(txHex("RegionHandshakeReply", lastSeq(), hr) + " | ")
          } catch(_: Throwable) {}
          drain(3000, "post")
        }
        "UDP dest=" + dest + " tx=" + txN + " rx=" + rxN + " handshake=" + (if (gotHs) "si" else "no") + " msg=" + gotName + " reply=" + (if (replied) "si" else "no") + " complete=" + (if (complete) "si" else "no") + " acks=" + acks + " circuit_fin=" + cfin + " hex=" + lastHex + " [" + det.toString().take(1400) + "]"
      } finally { try { sock.soTimeout = 15 } catch(_: Throwable) {} }
    } catch(e: Throwable) { "UDP dest=" + dest + " circuit_fin=" + cfin + " FAIL " + LoginManager.errText(e, "").take(400) }
  }
}
