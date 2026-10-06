package com.ephora.sl
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
object AgentLoop {
  var job: Job? = null
  var tx = 0L
  var running = false
  var lastTick = ""
  var onTick: ((String) -> Unit)? = null
  @Volatile var controlFlags = 0
  var px = 128.0
  var py = 128.0
  var pz = 25.0
  var coarseSeen = false
  var coarseN = 0
  var movOn = false
  var pingTx = 0L
  var lastPingId = -1
  @Volatile var lastChatText = ""
  var lastAuHex = ""
  val ackNoShown = LinkedHashSet<Long>()
  @Volatile var loopSock: DatagramSocket? = null
  @Volatile var loopAddr: InetAddress? = null
  fun sendChatNow(text: String, tag: String): Boolean {
    return try {
      val s = LoginManager.Session
      val sk = loopSock ?: return false
      val ad = loopAddr ?: return false
      val b = if (ChatManager.txVariant == "C") ChatManager.buildTxC(s.agentId, s.sessionId, text) else if (ChatManager.txVariant == "B") ChatManager.buildTxB(s.agentId, s.sessionId, text) else ChatManager.buildTx(s.agentId, s.sessionId, text)
      val seq = try { ByteBuffer.wrap(b, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL } catch(_: Throwable) { UdpCircuit.lastSeq() }
      sk.send(DatagramPacket(b, b.size, ad, s.simPort))
      tx++
      lastChatText = text
      val v0 = ChatManager.txVariant
      try { ChatManager.noteChatSent(seq, text, b, v0) } catch(_: Throwable) {}
      try { onTick?.invoke(tag + text.take(60)) } catch(_: Throwable) {}
      try {
        val n = try { ChatManager.txNum(text) } catch(_: Throwable) { 1 }
        try { onTick?.invoke("TX-NUM " + text.take(60) + " #" + n + " seq=" + seq) } catch(_: Throwable) {}
      } catch(_: Throwable) {}
      try { onTick?.invoke(nearLine) } catch(_: Throwable) {}
      try { onTick?.invoke(UdpCircuit.txHex("ChatFromViewer", seq, b)) } catch(_: Throwable) {}
      val manual = tag.contains("REINTENTO")
      try { ChatManager.armRetry(seq) } catch(_: Throwable) {}
      try { onTick?.invoke("RETRY-ARMADO seq=" + seq + (if (manual) " manual" else if (ChatManager.AUTO_RETRY_ENABLED) " modo=auto" else " modo=manual (auto off)")) } catch(_: Throwable) {}
      if (manual) {
        try { onTick?.invoke("RETRY-DISPARADO seq=" + seq + " manual") } catch(_: Throwable) {}
      }
      try {
        CoroutineScope(Dispatchers.IO).launch {
          delay(if (v0 == "B") 8000L else 20000L)
          if (!ChatManager.isChatPending(seq)) {
            try { if (ChatManager.cancelArmed(seq)) onTick?.invoke("RETRY-CANCELADO seq=" + seq + " (llego ack)") } catch(_: Throwable) {}
            return@launch
          }
          try { onTick?.invoke("CHAT-ACK-" + v0 + " seq=" + seq + " no (sin ack ni eco: si no lo ves en el oficial, pulsa REINTENTAR)") } catch(_: Throwable) {}
          try { ackNoShown.add(seq); while (ackNoShown.size > 20) { val k = ackNoShown.first(); ackNoShown.remove(k) } } catch(_: Throwable) {}
          if (!ChatManager.shouldAutoRetry(seq)) return@launch
          try { ChatManager.markAutoRetried(seq) } catch(_: Throwable) {}
          try { ChatManager.cancelArmed(seq) } catch(_: Throwable) {}
          try { onTick?.invoke("RETRY-DISPARADO seq=" + seq) } catch(_: Throwable) {}
          try {
            val s2 = loopSock
            val a2 = loopAddr
            val port = LoginManager.Session.simPort
            if (s2 != null && a2 != null && port != 0) {
              s2.send(DatagramPacket(b, b.size, a2, port))
              tx++
              try { ChatManager.noteChatResent(seq) } catch(_: Throwable) {}
              try { onTick?.invoke("CHAT-TX-REINTENTO-ACK-" + v0 + " seq=" + seq + " " + text.take(60)) } catch(_:Throwable) {}
            } else {
              try { onTick?.invoke("CHAT-TX-REINTENTO-ACK-ERROR seq=" + seq + " socket-nulo") } catch(_: Throwable) {}
            }
          } catch(_: Throwable) {}
        }
      } catch(_: Throwable) {}
      true
    } catch(_: Throwable) { false }
  }
  fun sendChat(text: String): Boolean {
    return sendChatNow(text, "CHAT-TX ")
  }
  fun sendExp(text: String, variant: String): Boolean {
    return try { ChatManager.txVariant = variant; sendChatNow(text, "CHAT-TX-EXP-" + variant + " ") } catch(_: Throwable) { false }
  }
  var lastImText = ""
  fun sendIm(text: String): Boolean {
    return sendImInner(text, false)
  }
  fun resendIm(): Boolean {
    return resendSameSeq()
  }
  fun sendImInner(text: String, manual: Boolean): Boolean {
    return try {
      val s = LoginManager.Session
      val sk = loopSock ?: return false
      val ad = loopAddr ?: return false
      val to = ChatManager.imReplyUuid
      if (to.isBlank()) return false
      val from = ChatManager.ownDisplayName()
      if (from.isBlank()) return false
      val b = ChatManager.buildIm(s.agentId, s.sessionId, to, from, text)
      val seq = try { ByteBuffer.wrap(b, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL } catch(_: Throwable) { UdpCircuit.lastSeq() }
      sk.send(DatagramPacket(b, b.size, ad, s.simPort))
      tx++
      lastImText = text
      try { ChatManager.noteChatSent(seq, text, b, "IM") } catch(_: Throwable) {}
      try { onTick?.invoke(if (manual) "IM-TX-REINTENTO " + text.take(120) else "IM-TX " + text.take(120)) } catch(_: Throwable) {}
      try {
        val n = try { ChatManager.txNum("IM:" + text) } catch(_: Throwable) { 1 }
        try { onTick?.invoke("IM-TX " + text.take(120) + " #" + n + " seq=" + seq) } catch(_: Throwable) {}
      } catch(_: Throwable) {}
      try { onTick?.invoke(UdpCircuit.txHex("ImprovedInstantMessage", seq, b)) } catch(_: Throwable) {}
      try { ChatManager.armRetry(seq) } catch(_: Throwable) {}
      try { onTick?.invoke("RETRY-ARMADO seq=" + seq + (if (manual) " manual" else if (ChatManager.AUTO_RETRY_ENABLED) " modo=auto" else " modo=manual (auto off)")) } catch(_: Throwable) {}
      if (manual) {
        try { onTick?.invoke("RETRY-DISPARADO seq=" + seq + " manual") } catch(_: Throwable) {}
      }
      try {
        CoroutineScope(Dispatchers.IO).launch {
          delay(8000L)
          if (!ChatManager.isChatPending(seq)) {
            try { if (ChatManager.cancelArmed(seq)) onTick?.invoke("RETRY-CANCELADO seq=" + seq + " (llego ack)") } catch(_: Throwable) {}
            return@launch
          }
          try { onTick?.invoke("CHAT-ACK-IM seq=" + seq + " no (sin ack ni eco: si no lo ves en el oficial, pulsa REINTENTAR)") } catch(_: Throwable) {}
          try { ackNoShown.add(seq); while (ackNoShown.size > 20) { val k = ackNoShown.first(); ackNoShown.remove(k) } } catch(_: Throwable) {}
          if (!ChatManager.shouldAutoRetry(seq)) return@launch
          try { ChatManager.markAutoRetried(seq) } catch(_: Throwable) {}
          try { ChatManager.cancelArmed(seq) } catch(_: Throwable) {}
          try { onTick?.invoke("RETRY-DISPARADO seq=" + seq) } catch(_: Throwable) {}
          try {
            val s2 = loopSock
            val a2 = loopAddr
            val port = LoginManager.Session.simPort
            if (s2 != null && a2 != null && port != 0) {
              s2.send(DatagramPacket(b, b.size, a2, port))
              tx++
              try { ChatManager.noteChatResent(seq) } catch(_: Throwable) {}
              try { onTick?.invoke("CHAT-TX-REINTENTO-ACK-IM seq=" + seq + " " + text.take(60)) } catch(_:Throwable) {}
            } else {
              try { onTick?.invoke("CHAT-TX-REINTENTO-ACK-ERROR seq=" + seq + " socket-nulo") } catch(_: Throwable) {}
            }
          } catch(_: Throwable) {}
        }
      } catch(_: Throwable) {}
      true
    } catch(_: Throwable) { false }
  }
  fun sendChatAB(text: String, variant: String): Boolean {
    return try { ChatManager.txVariant = variant; sendChatNow(text, "CHAT-TX-" + variant + " ") } catch(_: Throwable) { false }
  }
  fun sendNameReq(): Boolean {
    return try {
      val s = LoginManager.Session
      val sk = loopSock
      if (sk == null) {
        try { onTick?.invoke("NAME-REQ-ERROR socket-nulo") } catch(_: Throwable) {}
        return false
      }
      val ad = loopAddr
      if (ad == null) {
        try { onTick?.invoke("NAME-REQ-ERROR socket-nulo") } catch(_: Throwable) {}
        return false
      }
      val ids = s.buddies.toList()
      if (ids.isEmpty()) {
        try { onTick?.invoke("NAME-REQ-ERROR sin-buddies") } catch(_: Throwable) {}
        return false
      }
      val b = ChatManager.buildNameReq(ids)
      val seq = try { ByteBuffer.wrap(b, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL } catch(_: Throwable) { UdpCircuit.lastSeq() }
      sk.send(DatagramPacket(b, b.size, ad, s.simPort))
      tx++
      try { onTick?.invoke("NAME-REQ n=" + ids.size + " seq=" + seq) } catch(_: Throwable) {}
      true
    } catch(_: Throwable) { false }
  }
  val imgReqSent = LinkedHashSet<String>()
  var imgRxCount = 0L
  var rxDescartados = 0L
  val imgReqTime = LinkedHashMap<String,Long>()
  val imgReqTry = LinkedHashMap<String,Int>()
  val imgDataOk = LinkedHashSet<String>()
  val imgHexDone = LinkedHashSet<String>()
  var loopT0 = 0L
  val imgUnrelSent = LinkedHashSet<String>()
  var sintDone = false
  @Volatile var sintLine = "TEST-SINT-pendiente"
  @Volatile var destLine = "IMAGE-DEST pendiente"
  var imgCtrlDone = false
  fun sintTestOnce() {
    try { if (sintDone) return; sintDone = true } catch(_: Throwable) { return }
    try { imageSelfTest() } catch(_: Throwable) {}
  }
  fun imageSelfTest() {
    try {
      val fake = ByteArray(16) { (it + 1).toByte() }
      var ok9 = false
      var ok10 = false
      var ok86 = false
      try {
        val p = java.nio.ByteBuffer.allocate(37).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        p.put(0xFF.toByte()); p.put(0xFF.toByte()); p.put(0x00.toByte()); p.put(0x09.toByte())
        p.order(java.nio.ByteOrder.BIG_ENDIAN)
        p.put(fake)
        p.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        p.put(2.toByte()); p.putInt(100); p.putShort(3); p.putShort(8)
        p.put(ByteArray(8) { 0x41.toByte() })
        val pkt = UdpCircuit.headerUnreliable() + p.array()
        val d = UdpCircuit.decode(pkt, pkt.size)
        val line = if (d != null) UdpCircuit.parseImage(d.msgId, d.payload) else null
        ok9 = line != null && line.startsWith("IMAGE-DATA")
      } catch(_: Throwable) {}
      try {
        val p = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        p.put(0xFF.toByte()); p.put(0xFF.toByte()); p.put(0x00.toByte()); p.put(0x0A.toByte())
        p.order(java.nio.ByteOrder.BIG_ENDIAN)
        p.put(fake)
        p.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        p.putShort(7); p.putShort(8)
        p.put(ByteArray(8) { 0x42.toByte() })
        val pkt = UdpCircuit.headerUnreliable() + p.array()
        val d = UdpCircuit.decode(pkt, pkt.size)
        val line = if (d != null) UdpCircuit.parseImage(d.msgId, d.payload) else null
        ok10 = line != null && line.startsWith("IMAGE-DATA")
      } catch(_: Throwable) {}
      try {
        val pkt = UdpCircuit.headerUnreliable() + byteArrayOf(0x56.toByte()) + fake
        val d = UdpCircuit.decode(pkt, pkt.size)
        val line = if (d != null) UdpCircuit.parseImage(d.msgId, d.payload) else null
        ok86 = line != null && line.startsWith("IMAGE-DATA")
      } catch(_: Throwable) {}
      try {
        val bad = mutableListOf<String>()
        if (!ok9) bad.add("data9")
        if (!ok10) bad.add("packet10")
        if (!ok86) bad.add("notindb86")
        try { sintLine = if (bad.isEmpty()) "TEST-SINT-OK 3/3" else "TEST-SINT-FALLO " + bad.joinToString(",") } catch(_: Throwable) {}
        if (bad.isEmpty()) onTick?.invoke("TEST-SINT-OK 3/3") else onTick?.invoke("TEST-SINT-FALLO " + bad.joinToString(","))
      } catch(_: Throwable) {}
    } catch(_: Throwable) {}
  }
  fun sendImageReqBody(src: String = "tick"): Boolean {
    return try {
      val s = LoginManager.Session
      val sk = loopSock
      if (sk == null) { try { onTick?.invoke("IMAGE-REQ-ERROR socket-nulo") } catch(_: Throwable) {}; return false }
      val ad = loopAddr
      if (ad == null) { try { onTick?.invoke("IMAGE-REQ-ERROR sin-destino") } catch(_: Throwable) {}; return false }
      if (s.agentId.isBlank() || s.sessionId.isBlank()) { try { onTick?.invoke("IMAGE-REQ-ERROR sin-sesion") } catch(_: Throwable) {}; return false }
      val uuids = try { (TerrainComposition.textureIds() + PrimDecoder.texList()).distinct().take(48) } catch(_: Throwable) { emptyList<String>() }
      if (uuids.isEmpty()) {
        try { onTick?.invoke("IMAGE-REQ sin-uuid") } catch(_: Throwable) {}
        return false
      }
      var n = 0
      for (u in uuids) {
        if (imgReqSent.contains(u)) {
          try {
            val last = imgReqTime[u] ?: 0L
            val tries = imgReqTry[u] ?: 1
            if (tries < 3 && !imgDataOk.contains(u.take(8)) && System.currentTimeMillis() - last > 20000L) {
              val b2 = UdpCircuit.requestImage(s.agentId, s.sessionId, u)
              val seq2 = try { ByteBuffer.wrap(b2, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL } catch(_: Throwable) { UdpCircuit.lastSeq() }
              try {
                sk.send(DatagramPacket(b2, b2.size, ad, s.simPort))
              } catch(e: Throwable) { try { onTick?.invoke("IMAGE-REQ-ERROR id8=" + u.take(8) + " exc=" + e::class.java.simpleName) } catch(_: Throwable) {}; continue }
              tx++
              try { imgReqTime[u] = System.currentTimeMillis() } catch(_: Throwable) {}
              try { imgReqTry[u] = tries + 1 } catch(_: Throwable) {}
              try { onTick?.invoke("IMAGE-REQ-RETRY id8=" + u.take(8) + " intento=" + (tries + 1) + " seq=" + seq2 + " src=" + src) } catch(_: Throwable) {}
              n++
            }
          } catch(_: Throwable) {}
          continue
        }
        if (imgReqSent.size >= 48) break
        try { java.util.UUID.fromString(u) } catch(_: Throwable) { try { onTick?.invoke("IMAGE-REQ-UUID-MALO u=" + u.take(20)) } catch(_: Throwable) {}; continue }
        val b = UdpCircuit.requestImage(s.agentId, s.sessionId, u)
        val seq = try { ByteBuffer.wrap(b, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL } catch(_: Throwable) { UdpCircuit.lastSeq() }
        try {
          sk.send(DatagramPacket(b, b.size, ad, s.simPort))
        } catch(e: Throwable) { try { onTick?.invoke("IMAGE-REQ-ERROR id8=" + u.take(8) + " exc=" + e::class.java.simpleName) } catch(_: Throwable) {}; continue }
        tx++
        imgReqSent.add(u)
        try { imgReqTime[u] = System.currentTimeMillis() } catch(_: Throwable) {}
        try { imgReqTry[u] = 1 } catch(_: Throwable) {}
        try { onTick?.invoke("IMAGE-REQ-SENT id8=" + u.take(8) + " bytes=" + b.size + " priority=100000 seq=" + seq + " src=" + src + " fmt=high8-rel") } catch(_: Throwable) {}
        try {
          if (!imgCtrlDone) {
            imgCtrlDone = true
            try {
              val bc = UdpCircuit.requestImageLow8(s.agentId, s.sessionId, u)
              val seqc = try { ByteBuffer.wrap(bc, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL } catch(_: Throwable) { UdpCircuit.lastSeq() }
              try { sk.send(DatagramPacket(bc, bc.size, ad, s.simPort)) } catch(_: Throwable) {}
              tx++
              try { onTick?.invoke("IMAGE-REQ-SENT id8=" + u.take(8) + " bytes=" + bc.size + " seq=" + seqc + " src=" + src + " fmt=low8-CTRL") } catch(_: Throwable) {}
            } catch(_: Throwable) {}
            try {
              val bt = UdpCircuit.requestImageType1(s.agentId, s.sessionId, u)
              val seqt = try { ByteBuffer.wrap(bt, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL } catch(_: Throwable) { UdpCircuit.lastSeq() }
              try { sk.send(DatagramPacket(bt, bt.size, ad, s.simPort)) } catch(_: Throwable) {}
              tx++
              try { onTick?.invoke("IMAGE-REQ-SENT id8=" + u.take(8) + " bytes=" + bt.size + " seq=" + seqt + " src=" + src + " fmt=high8-type1") } catch(_: Throwable) {}
            } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
        try { if (imgHexDone.add(u)) onTick?.invoke(UdpCircuit.txHex("RequestImage", seq, b, 64)) } catch(_: Throwable) {}
        n++
      }
      n > 0
    } catch(_: Throwable) { false }
  }
  fun sendImageReq(): Boolean {
    return try { sendImageReqBody("ui") } catch(_: Throwable) { false }
  }
  fun sendTeleportHome(): Boolean {
    return try {
      val s = LoginManager.Session
      val sk = loopSock ?: return false
      val ad = loopAddr ?: return false
      val b = UdpCircuit.teleportHome(s.agentId, s.sessionId)
      val seq = try { ByteBuffer.wrap(b, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL } catch(_: Throwable) { UdpCircuit.lastSeq() }
      sk.send(DatagramPacket(b, b.size, ad, s.simPort))
      tx++
      try { onTick?.invoke("TP-TX home bytes=" + b.size + " seq=" + seq) } catch(_: Throwable) {}
      true
    } catch(_: Throwable) { false }
  }
  fun sendTeleportHandle(gx: Long, gy: Long, lx: Float, ly: Float, lz: Float): Boolean {
    return try {
      val s = LoginManager.Session
      val sk = loopSock ?: return false
      val ad = loopAddr ?: return false
      val handle = (gx shl 32) or (gy and 0xFFFFFFFFL)
      val b = UdpCircuit.teleportLocation(s.agentId, s.sessionId, handle, lx, ly, lz)
      val seq = try { ByteBuffer.wrap(b, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL } catch(_: Throwable) { UdpCircuit.lastSeq() }
      sk.send(DatagramPacket(b, b.size, ad, s.simPort))
      tx++
      try { onTick?.invoke("TP-TX handle=" + handle + " pos=" + lx + "," + ly + "," + lz + " bytes=" + b.size + " seq=" + seq) } catch(_: Throwable) {}
      true
    } catch(_: Throwable) { false }
  }
  var imTypingLast = 0L
  fun sendImTyping(start: Boolean): Boolean {
    return try {
      val to = ChatManager.imReplyUuid
      if (to.isBlank()) return false
      val now = System.currentTimeMillis()
      if (start && now - imTypingLast < 10000L) return true
      imTypingLast = now
      val s = LoginManager.Session
      val sk = loopSock ?: return false
      val ad = loopAddr ?: return false
      val from = ChatManager.ownDisplayName()
      if (from.isBlank()) return false
      val b = ChatManager.buildIm(s.agentId, s.sessionId, to, from, "", if (start) 41 else 42)
      sk.send(DatagramPacket(b, b.size, ad, s.simPort))
      tx++
      true
    } catch(_: Throwable) { false }
  }
  fun sendRetrieve(): Boolean {
    return try {
      val s = LoginManager.Session
      val sk = loopSock
      if (sk == null) {
        try { onTick?.invoke("IM-OFF-DRAIN-ERROR socket-nulo") } catch(_: Throwable) {}
        return false
      }
      val ad = loopAddr
      if (ad == null) {
        try { onTick?.invoke("IM-OFF-DRAIN-ERROR socket-nulo") } catch(_: Throwable) {}
        return false
      }
      if (s.agentId.isBlank() || s.sessionId.isBlank()) {
        try { onTick?.invoke("IM-OFF-DRAIN-ERROR sin-sesion") } catch(_: Throwable) {}
        return false
      }
      val b = ChatManager.buildRetrieve(s.agentId, s.sessionId)
      val seq = try { ByteBuffer.wrap(b, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL } catch(_: Throwable) { UdpCircuit.lastSeq() }
      sk.send(DatagramPacket(b, b.size, ad, s.simPort))
      tx++
      try { onTick?.invoke("IM-OFF-DRAIN seq=" + seq) } catch(_: Throwable) {}
      true
    } catch(_: Throwable) { false }
  }
  var nearN = 0
  var nearMin = -1.0
  var nearLine = "NEAR-ESTADO sin-datos"
  var nearPos = ArrayList<Triple<Double,Double,Double>>()
  var nearLastEmit = 0L
  @Volatile var objetos: List<PrimDecoder.Prim> = emptyList()
  var primLastPub = 0L
  fun parseCoarse(payload: ByteArray) {
    try {
      if (payload.size < 1) return
      val n = payload[0].toInt() and 0xFF
      if (n <= 0 || n > 64) return
      if (payload.size < 1 + n * 3 + 4 + 1) return
      var best = Double.MAX_VALUE
      var o = 1
      val pts = ArrayList<Triple<Double,Double,Double>>(n)
      repeat(n) {
        val x = (payload[o].toInt() and 0xFF).toDouble()
        val y = (payload[o + 1].toInt() and 0xFF).toDouble()
        val z = (payload[o + 2].toInt() and 0xFF).toDouble() * 4.0
        o += 3
        pts.add(Triple(x, y, z))
        val dx = x - px
        val dy = y - py
        val dz = z - pz
        val d = Math.sqrt(dx * dx + dy * dy + dz * dz)
        if (d < best) best = d
      }
      nearN = n
      nearMin = best
      nearPos = pts
      nearLine = "NEAR-ESTADO n=" + n + " min=" + best.toInt() + "m"
      val now = System.currentTimeMillis()
      if (now - nearLastEmit >= 10000L) {
        nearLastEmit = now
        try { onTick?.invoke(nearLine) } catch(_: Throwable) {}
      }
    } catch(_: Throwable) {}
  }
  fun resendSameSeq(): Boolean {
    return try {
      val sk = loopSock ?: return false
      val ad = loopAddr ?: return false
      val port = LoginManager.Session.simPort
      if (port == 0) return false
      val seq = try { ChatManager.pendingBytes.keys.maxOrNull() } catch(_: Throwable) { null }
      if (seq == null) {
        try { onTick?.invoke("RETRY-MANUAL-ERROR sin-pendiente") } catch(_: Throwable) {}
        return false
      }
      val b = try { ChatManager.pendingBytes[seq] } catch(_: Throwable) { null }
      if (b == null) {
        try { onTick?.invoke("RETRY-MANUAL-ERROR sin-pendiente") } catch(_: Throwable) {}
        return false
      }
      sk.send(DatagramPacket(b, b.size, ad, port))
      tx++
      try { ChatManager.noteChatResent(seq) } catch(_: Throwable) {}
      try { onTick?.invoke("RETRY-MANUAL-MISMOSEQ seq=" + seq) } catch(_: Throwable) {}
      true
    } catch(_: Throwable) { false }
  }
  fun resendChat(): Boolean {
    return resendSameSeq()
  }
  var movFlags = 0
  var sx = 0.0
  var sy = 0.0
  var sz = 0.0
  fun posStr(): String = "%.1f,%.1f,%.1f".format(px, py, pz)
  fun status(): String = "AU tx=" + tx + (if (running) " vivo" else " parado") + (if (lastTick.isNotBlank()) " " + lastTick else "")
  fun sniff(buf: ByteArray, len: Int) {
    try {
      val rx = UdpCircuit.parseRx(buf, len) ?: return
      if (rx.msgId == 0xFF06) {
        coarseSeen = true
        coarseN++
        try {
          val d = UdpCircuit.decode(buf, len)
          if (d != null) parseCoarse(d.payload)
        } catch(_: Throwable) {}
      }
      val mid = rx.msgId
      if (mid == 0xFFFF0094.toInt()) {
        try {
          val d = UdpCircuit.decode(buf, len)
          if (d != null) TerrainComposition.accept(d.payload)?.let { onTick?.invoke(it) }
        } catch(_: Throwable) {}
      }
      if (mid == 11) {
        try {
          val d = UdpCircuit.decode(buf, len)
          if (d != null) {
            try {
              val line = TerrainMesh.ingest(d.payload)
              if (line != null) {
                try { onTick?.invoke(line) } catch(_: Throwable) {}
              }
            } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
      }
      if (mid == 64 || mid == 66 || mid == 69 || mid == 72 || mid == 73) {
        try {
          val d = UdpCircuit.decode(buf, len)
          val pl = d?.payload ?: ByteArray(0)
          if (mid == 66) {
            var msg = "?"
            try { if (pl.size > 21) { val ml = pl[20].toInt() and 0xFF; if (ml in 1..500 && 21 + ml <= pl.size) msg = String(pl, 21, ml, Charsets.UTF_8) } } catch(_: Throwable) {}
            var fl = -1L
            try { if (pl.size >= 20) fl = ByteBuffer.wrap(pl, 16, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL } catch(_: Throwable) {}
            try { onTick?.invoke("TP-PROGRESS flags=" + fl + " msg=" + msg.take(120)) } catch(_: Throwable) {}
          } else if (mid == 64) {
            var x = 0f; var y = 0f; var z = 0f
            try { if (pl.size >= 32) { val bb = ByteBuffer.wrap(pl, 20, 12).order(ByteOrder.LITTLE_ENDIAN); x = bb.float; y = bb.float; z = bb.float } } catch(_: Throwable) {}
            try { px = x.toDouble(); py = y.toDouble(); pz = z.toDouble() } catch(_: Throwable) {}
            try { onTick?.invoke("TP-LOCAL x=" + x + " y=" + y + " z=" + z) } catch(_: Throwable) {}
          } else if (mid == 69) {
            var ip = "?"; var port = 0; var seed = ""; var hd = -1L
            try {
              if (pl.size >= 36) {
                ip = (pl[20].toInt() and 0xFF).toString() + "." + (pl[21].toInt() and 0xFF) + "." + (pl[22].toInt() and 0xFF) + "." + (pl[23].toInt() and 0xFF)
                port = ((pl[24].toInt() and 0xFF) shl 8) or (pl[25].toInt() and 0xFF)
                hd = ByteBuffer.wrap(pl, 26, 8).order(ByteOrder.LITTLE_ENDIAN).long
                val sl = ByteBuffer.wrap(pl, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                if (sl in 1..2000 && 36 + sl <= pl.size) seed = String(pl, 36, sl, Charsets.UTF_8)
              }
            } catch(_: Throwable) {}
            try { onTick?.invoke("TP-FINISH ip=" + ip + " port=" + port + " handle=" + hd + " seed=" + seed.take(1500)) } catch(_: Throwable) {}
          } else if (mid == 72) {
            try { onTick?.invoke("TP-CANCEL") } catch(_: Throwable) {}
          } else {
            try { onTick?.invoke("TP-START") } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
      }
      if (mid == 9 || mid == 10 || mid == 86) {
        try { imgRxCount++ } catch(_: Throwable) {}
        try { onTick?.invoke("IMAGE-RAW mid=" + java.lang.Integer.toHexString(mid).uppercase() + " len=" + len) } catch(_: Throwable) {}
        try {
          val d = UdpCircuit.decode(buf, len)
          if (d != null) {
            try { ImageAssets.accept(mid, d.payload)?.let { onTick?.invoke(it) } } catch(_: Throwable) {}
            try {
              val line = UdpCircuit.parseImage(mid, d.payload)
              if (line != null) {
                try { if (line.startsWith("IMAGE-DATA")) imgDataOk.add(line.substringAfter("id=").take(8)) } catch(_: Throwable) {}
                try { onTick?.invoke(line) } catch(_: Throwable) {}
              }
            } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
      }
      if (mid == 12 || mid == 13 || mid == 14 || mid == 15 || mid == 16) {
        try {
          val d = UdpCircuit.decode(buf, len)
          if (d != null) {
            try {
              val line = PrimDecoder.ingest(mid, d.payload, (d.flags and 0x80) != 0)
              if (line != null) {
                try { onTick?.invoke(line) } catch(_: Throwable) {}
              }
            } catch(_: Throwable) {}
            try {
              val now = System.currentTimeMillis()
              if (now - primLastPub >= 100L) {
                primLastPub = now
                try { objetos = PrimDecoder.publish(px, py, pz) } catch(_: Throwable) {}
              }
            } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
      }
    } catch(_: Throwable) {}
  }
  fun start(scope: CoroutineScope) {
    if (running) return
    val s = LoginManager.Session
    if (s.agentId.isBlank() || s.simIp.isBlank() || s.simPort == 0) return
    running = true
    try { ImageAssets.resetSession() } catch(_: Throwable) {}
    try { imgReqSent.clear() } catch(_: Throwable) {}
    try { imgReqTime.clear() } catch(_: Throwable) {}
    try { imgReqTry.clear() } catch(_: Throwable) {}
    try { imgDataOk.clear() } catch(_: Throwable) {}
    try { imgHexDone.clear() } catch(_: Throwable) {}
    try { sintDone = false } catch(_: Throwable) {}
    try { imgCtrlDone = false } catch(_: Throwable) {}
    try { sintLine = "TEST-SINT-pendiente" } catch(_: Throwable) {}
    try { destLine = "IMAGE-DEST pendiente" } catch(_: Throwable) {}
    try { imgUnrelSent.clear() } catch(_: Throwable) {}
    try { loopT0 = System.currentTimeMillis() } catch(_: Throwable) {}
    try { PrimDecoder.texIdsReset() } catch(_: Throwable) {}
    try { PrimDecoder.onTexLine = { tl -> try { onTick?.invoke(tl) } catch(_: Throwable) {} } } catch(_: Throwable) {}
    job = scope.launch(Dispatchers.IO) {
      var sock: DatagramSocket? = null
      try {
        sock = UdpCircuit.ensureSessionSock()
        loopSock = sock
        loopAddr = UdpCircuit.sessionAddr ?: InetAddress.getByName(s.simIp)
        UdpCircuit.sessionAddr = loopAddr
        try { val dl = "IMAGE-DEST ip=" + (try { loopAddr?.hostAddress ?: s.simIp } catch(_: Throwable) { s.simIp }) + " port=" + s.simPort; destLine = dl; onTick?.invoke(dl) } catch(_: Throwable) {}
        try { sintDone = false } catch(_: Throwable) {}
        sock.soTimeout = 15
        try { sock.receiveBufferSize = 262144 } catch(_: Throwable) {}
        val addr = loopAddr!!
        var t0 = System.currentTimeMillis()
        var last = System.currentTimeMillis()
        var lastAuSend = 0L
        var burstMax = 0
        var burstT0 = System.currentTimeMillis()
        while (isActive) {
          val now = System.currentTimeMillis()
          val dt = ((now - last).coerceIn(1L, 500L)) / 1000.0
          last = now
          val f = controlFlags
          if (now - lastAuSend >= 100L) {
            lastAuSend = now
          try {
            val b = UdpCircuit.agentUpdate(s.agentId, s.sessionId, f, px.toFloat(), py.toFloat(), pz.toFloat())
            sock.send(DatagramPacket(b, b.size, addr, s.simPort))
            tx++
            val hx = UdpCircuit.txHex("AgentUpdate", UdpCircuit.lastSeq(), b)
            if (f != 0 && !movOn) { try { onTick?.invoke(hx) } catch(_: Throwable) {} }
            if (f != 0 || now - t0 >= 10000) lastAuHex = hx
          } catch(_: Throwable) {}
          }
          if (f != 0) {
            if (!movOn) { movOn = true; movFlags = f; sx = px; sy = py; sz = pz }
            val v = 3.2 * dt
            if ((f and 1) != 0) px += v
            if ((f and 2) != 0) px -= v
            if ((f and 4) != 0) py -= v
            if ((f and 8) != 0) py += v
          } else if (movOn) {
            movOn = false
            try { onTick?.invoke("MOV flags=" + movFlags + " pos=" + "%.1f,%.1f,%.1f".format(sx, sy, sz) + "->" + posStr() + " coarse=" + (if (coarseSeen) "si" else "no")) } catch(_: Throwable) {}
          }
          var burst = 0
          try {
            while (true) {
              val buf = ByteArray(4096)
              val p = DatagramPacket(buf, buf.size)
              try { sock.receive(p) } catch(_: Throwable) { break }
              burst++
              try {
                val rx = UdpCircuit.parseRx(p.data, p.length)
                if (rx != null) UdpCircuit.noteRx(rx.name)
                if (rx != null && (rx.flags and 0x40) != 0) {
                  try {
                    val a = UdpCircuit.ackPacket(listOf(rx.seq))
                    sock.send(DatagramPacket(a, a.size, addr, s.simPort))
                    tx++
                    try { ChatManager.noteInboundAck(rx.seq) } catch(_: Throwable) {}
                    try { if (rx.msgId == 0xFFFF00FE.toInt()) onTick?.invoke("IM-ACK-SI seq=" + rx.seq) } catch(_: Throwable) {}
                  } catch(_: Throwable) {}
                }
                if (rx != null && rx.msgId == 1) {
                  val pid = UdpCircuit.pingIdOf(p.data, p.length)
                  if (pid != null) {
                    try {
                      val pong = UdpCircuit.completePingCheck(pid)
                      sock.send(DatagramPacket(pong, pong.size, addr, s.simPort))
                      tx++
                      pingTx++
                      lastPingId = pid
                      if (pingTx % 10L == 1L) {
                        try { onTick?.invoke("PING-RX id=" + pid) } catch(_: Throwable) {}
                        try { onTick?.invoke("PING-TX id=" + pid) } catch(_: Throwable) {}
                        try { onTick?.invoke(UdpCircuit.txHex("CompletePingCheck", UdpCircuit.lastSeq(), pong, 8)) } catch(_: Throwable) {}
                      }
                    } catch(_: Throwable) {}
                  }
                }
                if (rx != null && rx.msgId == 0xFFFFFFFB.toInt()) {
                  try { onTick?.invoke("ACK-RAW " + UdpCircuit.hexPrev(p.data, p.length.coerceAtMost(64))) } catch(_: Throwable) {}
                  try {
                    val hits = ChatManager.onPacketAck(p.data, p.length)
                    for (h in hits) {
                      val hv = try { ChatManager.ackedVariantOf(h) } catch(_: Throwable) { "?" }
                      try { onTick?.invoke("CHAT-ACK-" + hv + " seq=" + h + " si") } catch(_: Throwable) {}
                      try { onTick?.invoke("CHAT-ACK-SI seq=" + h) } catch(_: Throwable) {}
                      try { if (ackNoShown.remove(h)) onTick?.invoke("CHAT-ACK-TARDE seq=" + h) } catch(_: Throwable) {}
                      try { if (ChatManager.cancelArmed(h)) onTick?.invoke("RETRY-CANCELADO seq=" + h + " (llego ack)") } catch(_: Throwable) {}
                    }
                  } catch(_: Throwable) {}
                }
                if (rx != null && rx.msgId != 1 && rx.msgId != 2 && rx.msgId != 4 && rx.msgId != 0xFF06 && rx.msgId != 12 && rx.msgId != 13 && rx.msgId != 14 && rx.msgId != 15 && rx.msgId != 16 && rx.msgId != 0xFFFFFFFB.toInt() && rx.msgId != 0xFFFF008B.toInt() && rx.msgId != 9 && rx.msgId != 10 && rx.msgId != 86 && rx.msgId != 64 && rx.msgId != 66 && rx.msgId != 69 && rx.msgId != 72 && rx.msgId != 73 && rx.msgId != -1) {
                  try {
                    val d = UdpCircuit.decode(p.data, p.length)
                    if (d != null) {
                      val uline = ChatManager.unknownLine(rx.name, d.payload)
                      if (uline != null) { try { onTick?.invoke(uline) } catch(_: Throwable) {} }
                      else { try { rxDescartados++ } catch(_: Throwable) {} }
                      if (rx.msgId == 0xFFFF00FE.toInt()) { try { onTick?.invoke("IM-CORTO-PREVIO len=" + d.payload.size + " rama=rx-unknown") } catch(_: Throwable) {} }
                    }
                  } catch(_: Throwable) {}
                }
                } catch(_: Throwable) {}
                sniff(p.data, p.length)
                try { ChatManager.onDatagram(p.data, p.length) } catch(_: Throwable) {}
            }
          } catch(_: Throwable) {}
          if (ChatManager.burstLoud(burst)) {
            try { onTick?.invoke("RX-BURST n=" + burst) } catch(_: Throwable) {}
          }
          if (burst > burstMax) burstMax = burst
          if (now - burstT0 >= 10000L) {
            burstT0 = now
            try { onTick?.invoke("RX-BURST-MAX n=" + burstMax) } catch(_: Throwable) {}
            burstMax = 0
          }
          if (now - t0 >= 10000) {
            t0 = now
            lastTick = "tick10s tx=" + tx
            try { onTick?.invoke("AU tx=" + tx) } catch(_: Throwable) {}
            try { if (imgRxCount > 0 || rxDescartados > 0) onTick?.invoke("IMAGE-RX-N n=" + imgRxCount + " RX-DESCARTADOS n=" + rxDescartados) } catch(_: Throwable) {}
            try {
              if (loopSock != null && loopAddr != null) {
                val nowR = System.currentTimeMillis()
                val go = try { PrimDecoder.texList().any { lane -> !imgReqSent.contains(lane) || ((imgReqTry[lane] ?: 1) < 3 && !imgDataOk.contains(lane.take(8)) && nowR - (imgReqTime[lane] ?: 0L) > 20000L) } } catch(_: Throwable) { false }
                if (go) sendImageReqBody("tick")
                try {
                  if (imgRxCount == 0L && System.currentTimeMillis() - loopT0 > 90000L) {
                    val cands = try { PrimDecoder.texList().filter { (imgReqTry[it] ?: 0) >= 3 && !imgUnrelSent.contains(it) } } catch(_: Throwable) { emptyList<String>() }
                    for (u in cands) {
                      try { java.util.UUID.fromString(u) } catch(_: Throwable) { continue }
                      val b = UdpCircuit.requestImageUnrel(s.agentId, s.sessionId, u)
                      val seq = try { ByteBuffer.wrap(b, 1, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL } catch(_: Throwable) { UdpCircuit.lastSeq() }
                      val sk3 = loopSock ?: break
                      val ad3 = loopAddr ?: break
                      try {
                        sk3.send(DatagramPacket(b, b.size, ad3, s.simPort))
                      } catch(e: Throwable) { try { onTick?.invoke("IMAGE-REQ-ERROR id8=" + u.take(8) + " exc=" + e::class.java.simpleName) } catch(_: Throwable) {}; continue }
                      tx++
                      try { imgUnrelSent.add(u) } catch(_: Throwable) {}
                      try { onTick?.invoke("IMAGE-REQ-SENT id8=" + u.take(8) + " bytes=" + b.size + " priority=100000 seq=" + seq + " src=tick rel=unrel fmt=high8-unrel") } catch(_: Throwable) {}
                    }
                  }
                } catch(_: Throwable) {}
              }
            } catch(_: Throwable) {}
            try { if (lastAuHex.isNotBlank()) onTick?.invoke(lastAuHex) } catch(_: Throwable) {}
            try { onTick?.invoke("PING-ESTADO tx=" + pingTx + " ultimo=" + lastPingId) } catch(_: Throwable) {}
          }
          delay(20L)
        }
      } catch(_: Throwable) {
      } finally { loopSock = null; loopAddr = null }
    }
  }
  fun stop() {
    try { job?.cancel() } catch(_: Throwable) {}
    job = null
    running = false
  }
}
