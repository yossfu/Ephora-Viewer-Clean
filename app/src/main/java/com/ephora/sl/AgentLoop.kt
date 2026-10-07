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
  @Volatile var joystickX = 0f
  @Volatile var joystickY = 0f
  @Volatile var cameraYaw = 0f
  fun setJoystick(x: Float, y: Float) { joystickX=x.coerceIn(-1f,1f); joystickY=y.coerceIn(-1f,1f) }
  private fun joystickFlags(): Int {
    val x=joystickX; val y=joystickY; var f=0
    if (y < -0.18f) f=f or 1
    if (y > 0.18f) f=f or 2
    if (x < -0.18f) f=f or 4
    if (x > 0.18f) f=f or 8
    if (kotlin.math.abs(y)>0.82f) f=f or 0x400
    if (kotlin.math.abs(x)>0.82f) f=f or 0x800
    return f
  }
  var px = 128.0
  var py = 128.0
  var pz = 25.0
  var coarseSeen = false
  var coarseN = 0
  var posCoarseInit = false
  var posCoarseLastEmit = 0L
  var movOn = false
  var pingTx = 0L
  var lastPingId = -1
  @Volatile var lastChatText = ""
  var lastAuHex = ""
  val ackNoShown = LinkedHashSet<Long>()
  @Volatile var loopSock: DatagramSocket? = null
  @Volatile var loopAddr: InetAddress? = null
  @Volatile var throttleLastMs = 0L
  @Volatile var throttleSentN = 0L
  @Volatile var throttleLastTag = "-"
  @Volatile var camVec = floatArrayOf(128f, 128f, 25f, 0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
  fun camReport(): String {
    try {
      val v = camVec
      return "centro=%.1f,%.1f,%.1f".format(v[0], v[1], v[2]) + " at=%.2f,%.2f,%.2f".format(v[3], v[4], v[5])
    } catch(_: Throwable) {
      return "camReport-error"
    }
  }
  fun sendThrottle(tag: String) {
    try {
      val s = LoginManager.Session
      val sk = loopSock ?: return
      val ad = loopAddr ?: return
      if (s.agentId.isBlank() || s.sessionId.isBlank() || s.simPort == 0) return
      val th = UdpCircuit.agentThrottle(s.agentId, s.sessionId, s.circuitCode)
      sk.send(DatagramPacket(th, th.size, ad, s.simPort))
      tx++
      throttleSentN++
      throttleLastMs = System.currentTimeMillis()
      throttleLastTag = tag
      onTick?.invoke(UdpCircuit.txHex("AgentThrottle", UdpCircuit.lastSeq(), th) + " tag=" + tag + " preset=500 total=512000Bps n=" + throttleSentN)
    } catch(_: Throwable) {}
  }
  fun throttleLine(): String {
    try {
      return "enviados=" + throttleSentN + " ultimo-tag=" + throttleLastTag + " edadMs=" + (System.currentTimeMillis() - throttleLastMs)
    } catch(_: Throwable) {
      return "throttleLine-error"
    }
  }
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
  val rxDescPorId = LinkedHashMap<String,Long>()
  val imgReqTime = LinkedHashMap<String,Long>()
  val imgReqTry = LinkedHashMap<String,Int>()
  val imgDataOk = LinkedHashSet<String>()
  val imgSeen = LinkedHashSet<String>()
  val imgNoDb = LinkedHashSet<String>()
  fun imgLista(u: String): Boolean {
    // A reassembled J2C stream is not yet renderable. Only a decoded Bitmap
    // is a usable asset, so never stop retries merely because UDP assembly finished.
    try { if (ImageAssets.has(u)) return true } catch (_: Throwable) {}
    return false
  }
  fun notaImgAsm(asm: String) {
    try { if (asm.startsWith("TEX-UDP-ENSAMBLADA")) imgDataOk.add(asm.substringAfter("id=").take(8).lowercase()) } catch (_: Throwable) {}
    try { onTick?.invoke(asm) } catch (_: Throwable) {}
  }
  fun terrenoPend(): String {
    try {
      val ids = TerrainComposition.textureIds().take(4)
      if (ids.isEmpty()) return "TERRAIN-PEND sin-ids"
      val parts = ids.map { u -> u.take(8) + "=" + (if (ImageAssets.has(u)) "bitmap" else if (imgDataOk.contains(u.take(8))) "ensamblada" else if (imgNoDb.contains(u.take(8))) "no-existe" else if (imgReqSent.contains(u)) "pedida-r" + (imgReqTry[u] ?: 1) else "nada") }
      return "TERRAIN-PEND base=" + TerrainComposition.baseTexture().take(8) + " " + parts.joinToString(" ")
    } catch (_: Throwable) { return "TERRAIN-PEND error" }
  }
  val imgHexDone = LinkedHashSet<String>()
  var loopT0 = 0L
  var sintDone = false
  @Volatile var sintLine = "synthetic-tests-disabled"
  @Volatile var destLine = "IMAGE-DEST pendiente"
  fun sintTestOnce() {
    try {
      if (sintDone) return
      sintDone = true
      sintLine = "synthetic-tests-disabled"
    } catch (_: Throwable) {}
  }
  fun sendImageReqBody(src: String = "tick"): Boolean {
    return try {
      val s = LoginManager.Session
      val sk = loopSock ?: return false
      val ad = loopAddr ?: return false
      if (s.agentId.isBlank() || s.sessionId.isBlank() || s.simPort == 0) return false

      val now = System.currentTimeMillis()
      val ids = (TerrainComposition.textureIds() + PrimDecoder.texList(px, py, pz) + AvatarAppearanceState.textures()).filter { it.isNotBlank() }.distinct()

      var active = 0
      for (u in imgReqSent) {
        if (ImageAssets.has(u)) continue
        if (ImageAssets.hasPending(u) || now - (imgReqTime[u] ?: now) < 20_000L) active++
      }
      if (active >= 2) return false

      var sent = 0
      for (u in ids) {
        if (sent >= 2 - active) break
        try { java.util.UUID.fromString(u) } catch (_: Throwable) { continue }
        if (ImageAssets.has(u)) continue

        val last = imgReqTime[u] ?: 0L
        val known = imgReqSent.contains(u)
        if (known && now - last < 15_000L) continue
        if (known && ImageAssets.hasPending(u)) continue

        val b = UdpCircuit.requestImage(s.agentId, s.sessionId, u)
        try { sk.send(DatagramPacket(b, b.size, ad, s.simPort)) } catch (_: Throwable) { continue }

        tx++
        imgReqSent.add(u)
        imgReqTime[u] = now
        imgReqTry[u] = (imgReqTry[u] ?: 0) + 1
        sent++

        try {
          onTick?.invoke(
            "IMAGE-UDP-REQ id8=" + u.take(8) +
              " intento=" + imgReqTry[u] +
              " active=" + (active + sent) +
              " seq=" + UdpCircuit.lastSeq() +
              " src=" + src
          )
        } catch (_: Throwable) {}
      }
      sent > 0
    } catch (_: Throwable) { false }
  }
  /** Re-request specific missing ImagePacket blocks instead of restarting the whole image. */
  /** Lumiya-compatible recovery: restart stalled texture transfers from Packet=0. */
  fun retryMissingImagePackets(src: String = "missing"): Int {
    return try {
      val s = LoginManager.Session
      val sk = loopSock ?: return 0
      val ad = loopAddr ?: return 0
      if (s.agentId.isBlank() || s.sessionId.isBlank() || s.simPort == 0) return 0
      val stalled = ImageAssets.missingRequests(2)
      var sent = 0
      for ((u, _) in stalled) {
        try {
          val b = UdpCircuit.requestImagePacket(s.agentId, s.sessionId, u, 0)
          sk.send(DatagramPacket(b, b.size, ad, s.simPort))
          tx++
          sent++
          onTick?.invoke("IMAGE-UDP-RESTART id8=" + u.take(8) + " packet=0 src=" + src)
        } catch (_: Throwable) {}
      }
      sent
    } catch(_: Throwable) { 0 }
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
      try {
        val youOff = 1 + n * 3
        if (payload.size >= youOff + 4) {
          val ibb = ByteBuffer.wrap(payload, youOff, 4).order(ByteOrder.LITTLE_ENDIAN)
          val you = ibb.short.toInt()
          val prey = ibb.short.toInt()
          if (you >= 0 && you < pts.size) {
            val me = pts[you]
            val ox = px
            val oy = py
            val oz = pz
            px = me.first
            py = me.second
            pz = me.third
            val moved = Math.sqrt((px - ox) * (px - ox) + (py - oy) * (py - oy) + (pz - oz) * (pz - oz))
            val now2 = System.currentTimeMillis()
            if (!posCoarseInit || moved > 2.0 || now2 - posCoarseLastEmit > 60000L) {
              posCoarseInit = true
              posCoarseLastEmit = now2
              try { onTick?.invoke("POS-COARSE you=" + you + " prey=" + prey + " pos=" + posStr()) } catch(_: Throwable) {}
            }
          }
        }
      } catch(_: Throwable) {}
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
  var lastReqMultT = 0L
  var sx = 0.0
  var sy = 0.0
  var sz = 0.0
  fun posStr(): String = "%.1f,%.1f,%.1f".format(px, py, pz)
  fun descTop(): String {
    try {
      val top = rxDescPorId.entries.sortedByDescending { it.value }.take(8)
      if (top.isEmpty()) return "RX-DESC-TOP vacio"
      return "RX-DESC-TOP " + top.joinToString(" ") { e -> e.key + "=" + e.value }
    } catch(_: Throwable) { return "RX-DESC-TOP error" }
  }
  fun imgPendiente(): String {
    try {
      val uuids = try { (TerrainComposition.textureIds() + PrimDecoder.texList(px, py, pz)).distinct() } catch(_: Throwable) { emptyList<String>() }
      var pedidas = 0
      var ok = 0
      var vistas = 0
      for (u in uuids) {
        try { if (imgReqSent.contains(u)) pedidas++ } catch(_: Throwable) {}
        try { if (imgLista(u)) ok++ } catch(_: Throwable) {}
        try { if (imgSeen.contains(u.take(8))) vistas++ } catch(_: Throwable) {}
      }
      return "IMAGE-REQ-PEND total=" + uuids.size + " pedidas=" + pedidas + " ok=" + ok + " vistas=" + vistas + " pendiente=" + (uuids.size - pedidas)
    } catch(_: Throwable) { return "IMAGE-REQ-PEND error" }
  }
  private var lastHttpTexKick = 0L
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
      if (mid == 158) {
        try {
          val d = UdpCircuit.decode(buf, len)
          if (d != null) {
            AvatarAppearanceState.accept(d.payload)?.let { line ->
              try { onTick?.invoke(line) } catch(_: Throwable) {}
            }
          }
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
      if (mid == 0xFFFF00FA.toInt()) {
        try {
          val d = UdpCircuit.decode(buf, len)
          val pl = d?.payload ?: ByteArray(0)
          if (pl.size >= 44) {
            val bb = ByteBuffer.wrap(pl, 32, 12).order(ByteOrder.LITTLE_ENDIAN)
            val x = bb.float
            val y = bb.float
            val z = bb.float
            if (x.isFinite() && y.isFinite() && z.isFinite() && x >= 0f && x <= 256f && y >= 0f && y <= 256f && z >= -100f && z <= 2000f) {
              px = x.toDouble()
              py = y.toDouble()
              pz = z.toDouble()
              try { onTick?.invoke("POS-SIM x=" + x + " y=" + y + " z=" + z) } catch(_: Throwable) {}
              try { sendThrottle("entrada") } catch(_: Throwable) {}
            }
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
      if (mid == 9 || mid == 10 || mid == 86 || mid == 0xFFFF0056.toInt()) {
        try { imgRxCount++ } catch(_: Throwable) {}
        try { onTick?.invoke("IMAGE-RAW mid=" + java.lang.Integer.toHexString(mid).uppercase() + " len=" + len) } catch(_: Throwable) {}
        try {
          val d = UdpCircuit.decode(buf, len)
          if (d != null) {
            val imgMid = if (mid == 0xFFFF0056.toInt()) 86 else mid
            try { ImageAssets.accept(imgMid, d.payload)?.let { asm -> notaImgAsm(asm) } } catch(_: Throwable) {}
            try {
              val line = UdpCircuit.parseImage(imgMid, d.payload)
              if (line != null) {
                try { if (line.startsWith("IMAGE-DATA") && !line.contains("no-en-db")) imgSeen.add(line.substringAfter("id=").take(8).lowercase()) } catch(_: Throwable) {}
                try { if (line.contains("no-en-db")) imgNoDb.add(line.substringAfter("id=").take(8).lowercase()) } catch(_: Throwable) {}
                try { if (line.startsWith("IMAGE-DATA") && line.contains("completo=si")) imgDataOk.add(line.substringAfter("id=").take(8).lowercase()) } catch(_: Throwable) {}
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
            if (mid == 12 || mid == 13 || mid == 14 || mid == 15) {
              try {
                val now = System.currentTimeMillis()
                if (now - lastReqMultT >= 500L) {
                  try { PrimDecoder.sweepTexless(px, py, pz, now) } catch(_: Throwable) {}
                  val ids = try { PrimDecoder.drainReqMult(32, px, py, pz) } catch(_: Throwable) { emptyList<Long>() }
                  if (ids.isNotEmpty()) {
                    lastReqMultT = now
                    try {
                      val s = LoginManager.Session
                      val sk = loopSock
                      val ad = loopAddr
                      if (s.agentId.isNotBlank() && s.sessionId.isNotBlank() && sk != null && ad != null && s.simPort != 0) {
                        val b = UdpCircuit.requestMultipleObjects(s.agentId, s.sessionId, ids)
                        sk.send(DatagramPacket(b, b.size, ad, s.simPort))
                        tx++
                        try { PrimDecoder.nReqMultSent += ids.size } catch(_: Throwable) {}
                        try { PrimDecoder.nReqMultPk += 1 } catch(_: Throwable) {}
                        try { onTick?.invoke("REQ-MULT n=" + ids.size + " " + PrimDecoder.reqMultLine()) } catch(_: Throwable) {}
                      }
                    } catch(_: Throwable) {}
                  }
                }
              } catch(_: Throwable) {}
            }
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
    try { TexFetch.reset() } catch(_: Throwable) {}
    try { imgReqSent.clear() } catch(_: Throwable) {}
    try { rxDescPorId.clear() } catch(_: Throwable) {}
    try { imgReqTime.clear() } catch(_: Throwable) {}
    try { imgReqTry.clear() } catch(_: Throwable) {}
    try { imgDataOk.clear() } catch(_: Throwable) {}
    try { imgSeen.clear() } catch(_: Throwable) {}
    try { imgNoDb.clear() } catch(_: Throwable) {}
    try { imgHexDone.clear() } catch(_: Throwable) {}
    try { destLine = "IMAGE-DEST pendiente" } catch(_: Throwable) {}
    try { loopT0 = System.currentTimeMillis() } catch(_: Throwable) {}
    try { throttleSentN = 0L } catch(_: Throwable) {}
    try { throttleLastMs = 0L } catch(_: Throwable) {}
    try { throttleLastTag = "-" } catch(_: Throwable) {}
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
        try { sendThrottle("arranque") } catch(_: Throwable) {}
        var t0 = System.currentTimeMillis()
        var last = System.currentTimeMillis()
        var lastAuSend = 0L
        var burstMax = 0
        var burstT0 = System.currentTimeMillis()
        while (isActive) {
          val now = System.currentTimeMillis()
          val dt = ((now - last).coerceIn(1L, 500L)) / 1000.0
          last = now
          val f = joystickFlags() or controlFlags
          val buttonX = when {
            (f and 8) != 0 && (f and 4) == 0 -> 1f
            (f and 4) != 0 && (f and 8) == 0 -> -1f
            else -> 0f
          }
          val buttonY = when {
            (f and 1) != 0 && (f and 2) == 0 -> -1f
            (f and 2) != 0 && (f and 1) == 0 -> 1f
            else -> 0f
          }
          val moveX = if (kotlin.math.abs(joystickX) > 0.01f || kotlin.math.abs(joystickY) > 0.01f) joystickX else buttonX
          val moveY = if (kotlin.math.abs(joystickX) > 0.01f || kotlin.math.abs(joystickY) > 0.01f) joystickY else buttonY
          if (now - lastAuSend >= 100L) {
            lastAuSend = now
          try {
            val cv = camVec
            val b = UdpCircuit.agentUpdate(s.agentId, s.sessionId, f, cv[0], cv[1], cv[2], 512f, cameraYaw, cameraYaw, cv[3], cv[4], cv[5], cv[6], cv[7], cv[8], cv[9], cv[10], cv[11])
            sock.send(DatagramPacket(b, b.size, addr, s.simPort))
            tx++
            val hx = UdpCircuit.txHex("AgentUpdate", UdpCircuit.lastSeq(), b)
            if (f != 0 && !movOn) { try { onTick?.invoke(hx) } catch(_: Throwable) {} }
            if (f != 0 || now - t0 >= 10000) lastAuHex = hx
          } catch(_: Throwable) {}
          }
          if (f != 0) {
            if (!movOn) { movOn = true; movFlags = f; sx = px; sy = py; sz = pz }
            val mag = kotlin.math.sqrt((moveX*moveX + moveY*moveY).toDouble()).coerceAtMost(1.0)
            if (mag > 0.05) {
              val speed = 3.2 * (if (mag > 0.82) 1.35 else 1.0) * dt
              val forward = -moveY.toDouble()
              val strafe = moveX.toDouble()
              val cy = kotlin.math.cos(cameraYaw.toDouble())
              val sy = kotlin.math.sin(cameraYaw.toDouble())
              px += (cy*forward - sy*strafe) * speed
              py += (sy*forward + cy*strafe) * speed
            }
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
                if (rx != null && rx.msgId != 1 && rx.msgId != 2 && rx.msgId != 4 && rx.msgId != 0xFF06 && rx.msgId != 12 && rx.msgId != 13 && rx.msgId != 14 && rx.msgId != 15 && rx.msgId != 16 && rx.msgId != 0xFFFFFFFB.toInt() && rx.msgId != 0xFFFF008B.toInt() && rx.msgId != 9 && rx.msgId != 10 && rx.msgId != 86 && rx.msgId != 64 && rx.msgId != 66 && rx.msgId != 69 && rx.msgId != 72 && rx.msgId != 73 && rx.msgId != -1 && rx.msgId != 11 && rx.msgId != 0xFFFF00FA.toInt() && rx.msgId != 0xFFFF0094.toInt() && rx.msgId != 0xFFFF00FE.toInt() && rx.msgId != 0xFFFF00EC.toInt() && rx.msgId != 236 && rx.msgId != 0xFFFF0142.toInt() && rx.msgId != 0xFFFF0143.toInt() && rx.msgId != 0xFFFF0056.toInt()) {
                  try {
                    val d = UdpCircuit.decode(p.data, p.length)
                    if (d != null) {
                      val uline = ChatManager.unknownLine(rx.name, d.payload)
                      if (uline != null) { try { onTick?.invoke(uline) } catch(_: Throwable) {} }
                      else { try { rxDescartados++ } catch(_: Throwable) {} }
                      try { rxDescPorId[rx.name] = (rxDescPorId[rx.name] ?: 0L) + 1L } catch(_: Throwable) {}
                      try { if (rxDescPorId.size > 24) rxDescPorId.remove(rxDescPorId.keys.firstOrNull()) } catch(_: Throwable) {}
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
          if (now % 2000L < 25L) { try { retryMissingImagePackets("tick") } catch(_: Throwable) {} }
          if (now - t0 >= 10000) {
            t0 = now
            lastTick = "tick10s tx=" + tx
            try { onTick?.invoke("AU tx=" + tx) } catch(_: Throwable) {}
            try { if (now - throttleLastMs > 90000L) sendThrottle("refresco") } catch(_: Throwable) {}
            try { if (imgRxCount > 0 || rxDescartados > 0) onTick?.invoke("IMAGE-RX-N n=" + imgRxCount + " RX-DESCARTADOS n=" + rxDescartados) } catch(_: Throwable) {}
            try {
              if (loopSock != null && loopAddr != null) {
                val textureIds = try {
                  (TerrainComposition.textureIds() + PrimDecoder.texList(px, py, pz)).distinct()
                } catch(_: Throwable) { emptyList<String>() }

                try { TexFetch.requestVisible(textureIds, 24) } catch(_: Throwable) {}
                try { sendImageReqBody("tick") } catch(_: Throwable) {}
                try { onTick?.invoke(TexFetch.status()) } catch(_: Throwable) {}
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
