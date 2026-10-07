package com.ephora.sl
import java.nio.ByteBuffer
import java.nio.ByteOrder
object ChatManager {
  const val CHAT_TX_ID = 0xFFFF0080
  const val CHAT_RX_ID = 0xFFFF008B
  var onChat: ((String) -> Unit)? = null
  var onEqChat: ((String) -> Unit)? = null
  var onEcho: ((String) -> Unit)? = null
  var onChatOk: ((String) -> Unit)? = null
  var onDup: ((String) -> Unit)? = null
  var onAckTx: ((String) -> Unit)? = null
  var dupTotal = 0L
  val dupByKey = LinkedHashMap<String, Long>()
  var ackTxTotal = 0L
  fun noteInboundAck(seq: Long) {
    ackTxTotal++
    if (ackTxTotal % 100 == 0L) { try { onAckTx?.invoke("ACK-SUM n=" + ackTxTotal) } catch(_: Throwable) {} }
  }
  val seen = LinkedHashMap<String, Long>()
  fun dedup(key: String): Boolean {
    val now = System.currentTimeMillis()
    val it = seen.entries.iterator()
    while (it.hasNext()) { if (now - it.next().value > 15000) it.remove() }
    if (seen.containsKey(key)) {
      dupTotal++
      val c = (dupByKey[key] ?: 0L) + 1
      dupByKey[key] = c
      while (dupByKey.size > 50) { val kk = dupByKey.keys.first(); dupByKey.remove(kk) }
      try { onDup?.invoke("RX-DUP " + key.take(80) + " n=" + c) } catch(_: Throwable) {}
      return false
    }
    seen[key] = now
    while (seen.size > 50) { seen.entries.iterator().let { e -> if (e.hasNext()) { e.next(); e.remove() } } }
    return true
  }
  fun push(line: String) {
    try { onChat?.invoke(line) } catch(_: Throwable) {}
  }
  fun pushEq(line: String) {
    try {
      val cb = onEqChat ?: onChat
      cb?.invoke(line)
    } catch(_: Throwable) {}
  }
  val pendingChat = LinkedHashMap<Long, String>()
  val pendingBytes = LinkedHashMap<Long, ByteArray>()
  val pendingVariant = LinkedHashMap<Long, String>()
  val ackedChat = LinkedHashSet<Long>()
  val ackedVariant = LinkedHashMap<Long, String>()
  var ackTotal = 0L
  var txVariant = "B"
  fun noteChatSent(seq: Long, text: String, pkt: ByteArray, variant: String = "?") {
    pendingChat[seq] = text.take(60)
    pendingBytes[seq] = pkt
    pendingVariant[seq] = variant
    try { noteSentText(text) } catch(_: Throwable) {}
    while (pendingChat.size > 20) { val k = pendingChat.keys.first(); pendingChat.remove(k); pendingBytes.remove(k); pendingVariant.remove(k) }
  }
  fun isChatPending(seq: Long): Boolean = pendingChat.containsKey(seq) && !ackedChat.contains(seq)
  fun noteChatResent(seq: Long) {
    try { if (pendingChat.containsKey(seq)) pendingChat[seq] = (pendingChat[seq] ?: "") } catch(_: Throwable) {}
  }
  fun noteAck(seqs: List<Long>): List<Long> {
    val hit = seqs.filter { pendingChat.containsKey(it) && !ackedChat.contains(it) }
    for (h in hit) { ackedChat.add(h); ackedVariant[h] = pendingVariant[h] ?: "?"; pendingChat.remove(h); pendingBytes.remove(h); pendingVariant.remove(h); try { autoRetried.remove(h) } catch(_: Throwable) {} }
    while (ackedChat.size > 20) { val k = ackedChat.first(); ackedChat.remove(k) }
    while (ackedVariant.size > 20) { val k = ackedVariant.keys.first(); ackedVariant.remove(k) }
    return hit
  }
  fun noteEchoDelivered(echoText: String) {
    try {
      val key = echoText.trim().take(60)
      if (key.isBlank()) return
      val hit = pendingChat.filter { it.value.trim() == key && !ackedChat.contains(it.key) }.keys.toList()
      for (h in hit) { ackedChat.add(h); ackedVariant[h] = pendingVariant[h] ?: "?"; pendingChat.remove(h); pendingBytes.remove(h); pendingVariant.remove(h); try { autoRetried.remove(h) } catch(_: Throwable) {} }
      while (ackedChat.size > 20) { val k = ackedChat.first(); ackedChat.remove(k) }
      while (ackedVariant.size > 20) { val k = ackedVariant.keys.first(); ackedVariant.remove(k) }
      for (h in hit) { try { onChatOk?.invoke("CHAT-OK seq=" + h + " (eco)") } catch(_: Throwable) {} }
      for (h in hit) { try { if (cancelArmed(h)) onChatOk?.invoke("RETRY-CANCELADO seq=" + h + " (eco)") } catch(_: Throwable) {} }
    } catch(_: Throwable) {}
  }
  fun ringKeep(line: String): Boolean {
    try {
      if (line.startsWith("ACK-SUM")) return false
      if (line.startsWith("CHAT-")) return true
      if (line.startsWith("TX-")) return true
      if (line.startsWith("RETRY-")) return true
      if (line.startsWith("ACK-")) return true
      if (line.startsWith("RX-DUP")) return true
      if (line.startsWith("ECO-OK")) return true
      if (line.startsWith("IM-")) return true
      if (line.startsWith("AMIGO")) return true
      if (line.startsWith("BUDDY-")) return true
      if (line.startsWith("NAME-")) return true
      return false
    } catch(_: Throwable) { return false }
  }
  fun burstLoud(n: Int): Boolean {
    try { return n >= 40 } catch(_: Throwable) { return false }
  }
  fun ackedVariantOf(seq: Long): String = ackedVariant[seq] ?: "?"
  const val AUTO_RETRY_ENABLED = true
  val retryArmed = LinkedHashSet<Long>()
  fun armRetry(seq: Long) {
    retryArmed.add(seq)
    while (retryArmed.size > 20) { val k = retryArmed.first(); retryArmed.remove(k) }
  }
  fun cancelArmed(seq: Long): Boolean {
    return try { retryArmed.remove(seq) } catch(_: Throwable) { false }
  }
  fun isArmed(seq: Long): Boolean = retryArmed.contains(seq)
  val txNums = LinkedHashMap<String, Int>()
  fun txNum(text: String): Int {
    val k = text.trim()
    val c = (txNums[k] ?: 0) + 1
    txNums[k] = c
    while (txNums.size > 50) { val kk = txNums.keys.first(); txNums.remove(kk) }
    return c
  }
  val sentTexts = LinkedHashMap<String, Long>()
  fun noteSentText(t: String) {
    try {
      sentTexts[t.trim()] = System.currentTimeMillis()
      while (sentTexts.size > 20) { val k = sentTexts.keys.first(); sentTexts.remove(k) }
    } catch(_: Throwable) {}
  }
  fun isEchoText(t: String): Boolean {
    try {
      val now = System.currentTimeMillis()
      val tt = t.trim()
      val ts = sentTexts[tt] ?: return false
      return now - ts < 120000L
    } catch(_: Throwable) { return false }
  }
  fun isOwnName(n: String): Boolean {
    try {
      val fl = try { LoginManager.Session.firstLast } catch(_: Throwable) { "" }
      if (fl.isBlank()) return false
      val parts = fl.split(".")
      val first = parts.getOrElse(0) { "" }.trim()
      val last = parts.getOrElse(1) { "" }.trim()
      if (first.isBlank()) return false
      val cand = LinkedHashSet<String>()
      cand.add((first + " " + last).trim().lowercase())
      cand.add((first + "." + last).trim().lowercase())
      cand.add(first.trim().lowercase())
      return cand.contains(n.trim().lowercase())
    } catch(_: Throwable) { return false }
  }
  fun uuidStr(b: ByteArray, o: Int): String {
    val h = "0123456789abcdef"
    val sb = StringBuilder()
    for (i in 0 until 16) { val v = b[o + i].toInt() and 0xFF; sb.append(h[v ushr 4]); sb.append(h[v and 15]); if (i == 3 || i == 5 || i == 7 || i == 9) sb.append("-") }
    return sb.toString()
  }
  fun senderIdOf(payload: ByteArray): String? {
    try {
      if (payload.size < 1 + 16 + 16 + 3 + 12 + 2) return null
      val nl = payload[0].toInt() and 0xFF
      if (nl <= 0 || 1 + nl + 16 + 16 + 3 + 12 + 2 > payload.size) return null
      return uuidStr(payload, 1 + nl)
    } catch(_: Throwable) { return null }
  }
  fun isOwnId(sid: String?): Boolean {
    try {
      if (sid == null || sid.isBlank()) return false
      val mine = try { LoginManager.Session.agentId.trim() } catch(_: Throwable) { "" }
      if (mine.isBlank()) return false
      return sid.trim().equals(mine, ignoreCase = true)
    } catch(_: Throwable) { return false }
  }
  fun splitNameText(line: String): Pair<String, String> {
    val i = line.indexOf(": ")
    if (i < 0) return Pair("", line)
    return Pair(line.substring(0, i), line.substring(i + 2))
  }
  fun resetSession() {
    try { txNums.clear() } catch(_: Throwable) {}
    try { sentTexts.clear() } catch(_: Throwable) {}
    try { retryArmed.clear() } catch(_: Throwable) {}
    try { pendingChat.clear(); pendingBytes.clear(); pendingVariant.clear() } catch(_: Throwable) {}
    try { autoRetried.clear() } catch(_: Throwable) {}
    try { seen.clear() } catch(_: Throwable) {}
    try { rxKeys.clear() } catch(_: Throwable) {}
    try { dupByKey.clear(); dupTotal = 0 } catch(_: Throwable) {}
    try { ackTxTotal = 0 } catch(_: Throwable) {}
    try { imSeen.clear() } catch(_: Throwable) {}
    try { imDupByKey.clear(); imDupTotal = 0 } catch(_: Throwable) {}
    try { imRxCount = 0; imReplyUuid = ""; imReplyName = "" } catch(_: Throwable) {}
    try { buddyNames.clear() } catch(_: Throwable) {}
    try { onlineIds.clear() } catch(_: Throwable) {}
    try { imThreads.clear() } catch(_: Throwable) {}
    try { imUnread.clear() } catch(_: Throwable) {}
    try { imTyping.clear() } catch(_: Throwable) {}
    try { imOffSeen = 0 } catch(_: Throwable) {}
    try { lastImOff = 0 } catch(_: Throwable) {}
  }
  val autoRetried = LinkedHashSet<Long>()
  fun shouldAutoRetry(seq: Long): Boolean = AUTO_RETRY_ENABLED && isChatPending(seq) && !autoRetried.contains(seq)
  fun markAutoRetried(seq: Long) {
    autoRetried.add(seq)
    while (autoRetried.size > 20) { val k = autoRetried.first(); autoRetried.remove(k) }
  }
  fun packetAckSeqs(payload: ByteArray): List<Long> {
    try {
      if (payload.size < 2) return emptyList()
      val n = payload[0].toInt() and 0xFF
      if (n <= 0 || n > 32 || 1 + n * 4 != payload.size) return emptyList()
      val out = ArrayList<Long>(n)
      val bb = ByteBuffer.wrap(payload, 1, n * 4).order(ByteOrder.LITTLE_ENDIAN)
      repeat(n) { out.add(bb.int.toLong() and 0xFFFFFFFFL) }
      return out
    } catch(_: Throwable) { return emptyList() }
  }
  fun onPacketAck(raw: ByteArray, len: Int): List<Long> {
    try {
      val d = UdpCircuit.decode(raw, len) ?: return emptyList()
      if (d.msgId != 0xFFFFFFFB.toInt()) return emptyList()
      ackTotal++
      return noteAck(packetAckSeqs(d.payload))
    } catch(_: Throwable) { return emptyList() }
  }
  fun buildTx(agentId: String, sessionId: String, text: String): ByteArray {
    val msg = text.toByteArray(Charsets.UTF_8).take(255).toByteArray()
    val h = UdpCircuit.headerReliable()
    val p = ByteBuffer.allocate(4 + 16 + 16 + 2 + msg.size + 1 + 4).order(ByteOrder.LITTLE_ENDIAN)
    p.putShort(-1)
    p.put(0x00.toByte())
    p.put(0x50.toByte())
    p.order(ByteOrder.BIG_ENDIAN)
    p.put(UdpCircuit.uuidBE(agentId))
    p.put(UdpCircuit.uuidBE(sessionId))
    p.put((msg.size and 255).toByte())
    p.put(((msg.size ushr 8) and 255).toByte())
    p.put(msg)
    p.put(0x01.toByte())
    p.order(ByteOrder.LITTLE_ENDIAN)
    p.putInt(0)
    return h + p.array()
  }
  fun buildTxB(agentId: String, sessionId: String, text: String): ByteArray {
    val msg = text.toByteArray(Charsets.UTF_8).take(255).toByteArray()
    val h = UdpCircuit.headerReliable()
    val p = ByteBuffer.allocate(4 + 16 + 16 + 2 + msg.size + 1 + 1 + 4).order(ByteOrder.LITTLE_ENDIAN)
    p.putShort(-1)
    p.put(0x00.toByte())
    p.put(0x50.toByte())
    p.order(ByteOrder.BIG_ENDIAN)
    p.put(UdpCircuit.uuidBE(agentId))
    p.put(UdpCircuit.uuidBE(sessionId))
    p.put(((msg.size + 1) and 255).toByte())
    p.put((((msg.size + 1) ushr 8) and 255).toByte())
    p.put(msg)
    p.put(0x00.toByte())
    p.put(0x01.toByte())
    p.order(ByteOrder.LITTLE_ENDIAN)
    p.putInt(0)
    return h + p.array()
  }
  fun buildTxC(agentId: String, sessionId: String, text: String): ByteArray {
    val msg = text.toByteArray(Charsets.UTF_8).take(255).toByteArray()
    val h = UdpCircuit.headerReliable()
    val p = ByteBuffer.allocate(4 + 16 + 16 + 1 + msg.size + 1 + 4).order(ByteOrder.LITTLE_ENDIAN)
    p.putShort(-1)
    p.put(0x00.toByte())
    p.put(0x50.toByte())
    p.order(ByteOrder.BIG_ENDIAN)
    p.put(UdpCircuit.uuidBE(agentId))
    p.put(UdpCircuit.uuidBE(sessionId))
    p.put((msg.size and 255).toByte())
    p.put(msg)
    p.put(0x01.toByte())
    p.order(ByteOrder.LITTLE_ENDIAN)
    p.putInt(0)
    return h + p.array()
  }
  fun u16le(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
  fun hex128(b: ByteArray): String {
    val sb = StringBuilder()
    for (i in 0 until 128.coerceAtMost(b.size)) sb.append("%02X".format(b[i]))
    return sb.toString()
  }
  fun hexFull(b: ByteArray): String {
    val sb = StringBuilder()
    for (i in b.indices) sb.append("%02X".format(b[i]))
    return sb.toString()
  }
  fun hex32(b: ByteArray): String {
    val sb = StringBuilder()
    for (i in 0 until 32.coerceAtMost(b.size)) sb.append("%02X".format(b[i]))
    return sb.toString()
  }
  val unknownSeen = LinkedHashMap<String, Long>()
  val unknownLastLog = LinkedHashMap<String, Long>()
  fun unknownLine(name: String, payload: ByteArray): String? {
    try {
      val c = (unknownSeen[name] ?: 0L) + 1
      unknownSeen[name] = c
      if (unknownSeen.size > 40) { val k = unknownSeen.keys.first(); unknownSeen.remove(k); unknownLastLog.remove(k) }
      val now = System.currentTimeMillis()
      val last = unknownLastLog[name] ?: 0L
      if (c <= 3 || now - last >= 60000L) {
        unknownLastLog[name] = now
        return "RX-UNKNOWN " + name + " c=" + c + " " + hex32(payload)
      }
      return null
    } catch(_: Throwable) { return null }
  }
  var rxCount = 0L
  val rxKeys = LinkedHashSet<String>()
  fun normKey(line: String): String {
    return line.trim().lowercase().replace(Regex("\\s+"), " ")
  }
  fun noteRxPainted(line: String) {
    try { if (rxKeys.add(normKey(line))) rxCount++ } catch(_: Throwable) {}
    while (rxKeys.size > 200) { try { val k = rxKeys.first(); rxKeys.remove(k) } catch(_: Throwable) { break } }
  }
  fun isPrintable(b: ByteArray, o: Int, n: Int): Boolean {
    for (i in o until o + n) {
      val v = b[i].toInt() and 0xFF
      if (v < 32 && v != 9 && v != 10 && v != 13) return false
    }
    return true
  }
  fun cleanStr(b: ByteArray, o: Int, n: Int): String {
    return b.copyOfRange(o, o + n).toString(Charsets.UTF_8).replace(0.toChar().toString(), "")
  }
  fun tryParseChat(payload: ByteArray): String? {
    try {
      if (payload.size < 1 + 16 + 16 + 3 + 12 + 2) return null
      var o = 0
      val nl = payload[o].toInt() and 0xFF
      o += 1
      if (nl <= 0 || o + nl + 16 + 16 + 3 + 12 + 2 > payload.size) return null
      val name = cleanStr(payload, o, nl)
      if (name.isBlank()) return null
      val nb = name.toByteArray(Charsets.UTF_8)
      if (!isPrintable(nb, 0, nb.size)) return null
      o += nl
      o += 16
      o += 16
      o += 3
      o += 12
      val n = u16le(payload, o)
      o += 2
      if (n <= 0 || o + n > payload.size) return null
      val text = cleanStr(payload, o, n).trim()
      if (text.isBlank()) return null
      val tb = text.toByteArray(Charsets.UTF_8)
      if (!isPrintable(tb, 0, tb.size)) return null
      return name + ": " + text
    } catch(_: Throwable) { return null }
  }
  fun onDatagram(raw: ByteArray, len: Int) {
    try {
      val d = UdpCircuit.decode(raw, len) ?: return
      if (d.msgId == 0xFFFF00FE.toInt()) { try { onImDatagram(raw, len) } catch(_: Throwable) {}; return }
      if (d.msgId == 236 || d.msgId == 0xFFFF00EC.toInt()) { try { onNameDatagram(raw, len) } catch(_: Throwable) {}; return }
      if (d.msgId == 0xFFFF0142.toInt()) { try { onPresenceDatagram(d.payload, true) } catch(_: Throwable) {}; return }
      if (d.msgId == 0xFFFF0143.toInt()) { try { onPresenceDatagram(d.payload, false) } catch(_: Throwable) {}; return }
      if (d.msgId != 0xFFFF008B.toInt()) return
      val line = tryParseChat(d.payload) ?: return
      val nt = splitNameText(line)
      val sid = try { senderIdOf(d.payload) } catch(_: Throwable) { null }
      if (isOwnId(sid) || isEchoText(nt.second) || isOwnName(nt.first)) {
        if (dedup("ECO:" + line)) { try { onEcho?.invoke("ECO-OK " + nt.second.take(120) + " t=" + System.currentTimeMillis()) } catch(_: Throwable) {}; try { noteEchoDelivered(nt.second) } catch(_: Throwable) {} }
        return
      }
      if (dedup(line)) {
        noteRxPainted(line)
        push(line)
      }
    } catch(_: Throwable) {}
  }
  fun nextStr(seg: String, from: Int): String {
    val s = seg.indexOf("<string>", from)
    if (s < 0) return ""
    val e = seg.indexOf("</string>", s)
    if (e < 0 || e - s > 1100) return ""
    return seg.substring(s + 8, e)
  }
  const val IM_RX_ID = 0xFFFF00FE
  var onIm: ((String) -> Unit)? = null
  var imRxCount = 0L
  var imReplyUuid = ""
  var imReplyName = ""
  var lastImOff = 0
  var imOffSeen = 0
  val imThreads = LinkedHashMap<String, ArrayDeque<String>>()
  val imUnread = LinkedHashMap<String, Int>()
  val imTyping = LinkedHashMap<String, Long>()
  var onTyping: ((String, String, Boolean) -> Unit)? = null
  fun imThreadPush(uuid: String, line: String) {
    try {
      val k = uuid.lowercase()
      val q = imThreads.getOrPut(k) { ArrayDeque() }
      q.addLast(line.take(500))
      while (q.size > 100) q.removeFirst()
    } catch(_: Throwable) {}
  }
  fun imThreadGet(uuid: String): List<String> {
    try { return imThreads[uuid.lowercase()]?.toList() ?: emptyList() } catch(_: Throwable) { return emptyList() }
  }
  fun imUnreadAdd(uuid: String): Int {
    try {
      val k = uuid.lowercase()
      val n = (imUnread[k] ?: 0) + 1
      imUnread[k] = n
      return n
    } catch(_: Throwable) { return 0 }
  }
  fun imUnreadClear(uuid: String) {
    try { imUnread.remove(uuid.lowercase()) } catch(_: Throwable) {}
  }
  val imSeen = LinkedHashMap<String, Long>()
  val imDupByKey = LinkedHashMap<String, Long>()
  var imDupTotal = 0L
  fun ownDisplayName(): String {
    try {
      val fl = try { LoginManager.Session.firstLast } catch(_: Throwable) { "" }
      if (fl.isBlank()) return ""
      val parts = fl.split(".")
      val first = parts.getOrElse(0) { "" }.trim()
      val last = parts.getOrElse(1) { "" }.trim()
      if (first.isBlank()) return ""
      return (first + " " + last).trim()
    } catch(_: Throwable) { return "" }
  }
  fun isImUuid(s: String): Boolean {
    return try { s.trim().matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) } catch(_: Throwable) { false }
  }
  fun xorUuid(a: ByteArray, b: ByteArray): ByteArray {
    val o = ByteArray(16)
    for (i in 0 until 16) o[i] = (a[i].toInt() xor b[i].toInt()).toByte()
    return o
  }
  fun dedupIm(key: String, name: String): Boolean {
    try {
      val now = System.currentTimeMillis()
      val it = imSeen.entries.iterator()
      while (it.hasNext()) { if (now - it.next().value > 120000) it.remove() }
      if (imSeen.containsKey(key)) {
        imDupTotal++
        val c = (imDupByKey[key] ?: 0L) + 1
        imDupByKey[key] = c
        while (imDupByKey.size > 50) { val kk = imDupByKey.keys.first(); imDupByKey.remove(kk) }
        try { onDup?.invoke("IM-DUP " + name.take(40) + " n=" + c) } catch(_: Throwable) {}
        return false
      }
      imSeen[key] = now
      while (imSeen.size > 50) { imSeen.entries.iterator().let { e -> if (e.hasNext()) { e.next(); e.remove() } } }
      return true
    } catch(_: Throwable) { return true }
  }
  fun imDiag(payload: ByteArray): String {
    try {
      if (payload.size < 108) return "IM-PARSE dialog=? toMe=? to=? from=? sess=? off=? name=? text=? err=size" + payload.size
      val fromId = uuidStr(payload, 0)
      val from8 = fromId.take(8)
      var o = 32
      o += 1
      val toId = uuidStr(payload, o)
      o += 16
      o += 4
      o += 16
      o += 12
      val off = payload[o].toInt() and 0xFF
      o += 1
      val dlg = payload[o].toInt() and 0xFF
      o += 1
      val mine = try { uuidStr(UdpCircuit.uuidBE(LoginManager.Session.agentId), 0) } catch(_: Throwable) { "" }
      val toMe = toId.equals(mine, ignoreCase = true)
      val to8 = toId.take(8)
      val head = "IM-PARSE dialog=" + dlg + " toMe=" + (if (toMe) "si" else "no") + " to=" + to8 + " from=" + from8
      if (o + 16 + 4 + 1 > payload.size) return head + " sess=? off=" + off + " name=? text=? err=sess-ts"
      val sess = uuidStr(payload, o)
      o += 16
      o += 4
      val nl = payload[o].toInt() and 0xFF
      o += 1
      if (o + nl + 2 > payload.size) return head + " sess=" + sess.take(8) + " off=" + off + " name=? text=? err=name@" + o
      val nm = cleanStr(payload, o, nl).trim()
      o += nl
      if (o + 2 > payload.size) return head + " sess=" + sess.take(8) + " off=" + off + " name=" + nm.take(20) + " text=? err=msglen@" + o
      val ml = u16le(payload, o)
      o += 2
      if (ml < 0 || o + ml + 2 > payload.size) return head + " sess=" + sess.take(8) + " off=" + off + " name=" + nm.take(20) + " text=? err=msg@" + o
      val tx = cleanStr(payload, o, ml).trim()
      return head + " sess=" + sess.take(8) + " off=" + off + " name=" + nm.take(20) + " text=" + tx.take(80) + " err=ok"
    } catch(_: Throwable) { return "IM-PARSE dialog=? toMe=? to=? from=? sess=? off=? name=? text=? err=exc" }
  }
  fun tryParseIm(payload: ByteArray): Triple<String, String, String>? {
    try {
      if (payload.size < 108) return null
      try { lastImOff = if (payload.size > 81) payload[81].toInt() and 0xFF else 0 } catch(_: Throwable) { lastImOff = 0 }
      val fromId = uuidStr(payload, 0)
      var o = 32
      o += 1
      val toId = uuidStr(payload, o)
      o += 16
      o += 4
      o += 16
      o += 12
      o += 1
      val dlg = payload[o].toInt() and 0xFF
      o += 1
      o += 16
      o += 4
      val nl = payload[o].toInt() and 0xFF
      o += 1
      if (o + nl + 2 > payload.size) return null
      val name = cleanStr(payload, o, nl).trim()
      o += nl
      if (o + 2 > payload.size) return null
      val ml = u16le(payload, o)
      o += 2
      if (ml < 0 || o + ml + 2 > payload.size) return null
      val text = cleanStr(payload, o, ml).trim()
      val shown = if (name.isBlank()) "?" else name
      val mine = try { uuidStr(UdpCircuit.uuidBE(LoginManager.Session.agentId), 0) } catch(_: Throwable) { "" }
      if (!toId.equals(mine, ignoreCase = true)) return null
      if (dlg != 0) {
        try {
          if (dlg == 41) {
            imTyping[fromId.lowercase()] = System.currentTimeMillis()
            if (dedupIm(fromId.lowercase() + "|" + dlg + "|" + text.take(20), shown)) {
              try { onDup?.invoke("IM-ESCRIBE " + shown.take(40)) } catch(_: Throwable) {}
            }
            try { onTyping?.invoke(fromId, shown, true) } catch(_: Throwable) {}
          } else if (dlg == 42) {
            imTyping.remove(fromId.lowercase())
            if (dedupIm(fromId.lowercase() + "|" + dlg + "|" + text.take(20), shown)) {
              try { onDup?.invoke("IM-FIN-ESCRIBE " + shown.take(40)) } catch(_: Throwable) {}
            }
            try { onTyping?.invoke(fromId, shown, false) } catch(_: Throwable) {}
          } else {
            try { onDup?.invoke("IM-OTRO dialog=" + dlg + " name=" + shown.take(20) + " text=" + text.take(80)) } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
        return null
      }
      if (text.isBlank()) return null
      return Triple(shown, text, fromId)
    } catch(_: Throwable) { return null }
  }
  val buddyNames = LinkedHashMap<String, String>()
  val onlineIds = LinkedHashSet<String>()
  fun isOnline(u: String): Boolean {
    try { return onlineIds.contains(u.lowercase()) } catch(_: Throwable) { return false }
  }
  fun onPresenceDatagram(payload: ByteArray, isOn: Boolean) {
    try {
      if (payload.isEmpty()) return
      val n = payload[0].toInt() and 0xFF
      if (n <= 0 || n > 64) return
      if (payload.size < 1 + n * 16) return
      var o = 1
      val fresh = ArrayList<String>()
      repeat(n) {
        val id = uuidStr(payload, o)
        o += 16
        val k = id.lowercase()
        if (isOn) onlineIds.add(k) else onlineIds.remove(k)
        fresh.add(k)
      }
      for (f in fresh) {
        val nm = try { buddyNames[f] ?: f.take(8) } catch(_: Throwable) { f.take(8) }
        try { onDup?.invoke(if (isOn) "AMIGO-ON " + nm.take(60) else "AMIGO-OFF " + nm.take(60)) } catch(_: Throwable) {}
      }
      try { onDup?.invoke("AMIGO-ON-N n=" + onlineIds.size) } catch(_: Throwable) {}
    } catch(_: Throwable) {}
  }
  fun onNameDatagram(raw: ByteArray, len: Int) {
    try {
      val d = UdpCircuit.decode(raw, len)
      if (d == null) {
        try { onDup?.invoke("NAME-REPLY-ERROR decode") } catch(_: Throwable) {}
        return
      }
      if (d.msgId != 236 && d.msgId != 0xFFFF00EC.toInt()) return
      val p = d.payload
      if (p.isEmpty()) {
        try { onDup?.invoke("NAME-REPLY-ERROR size") } catch(_: Throwable) {}
        return
      }
      var o = 0
      val n = p[o].toInt() and 0xFF
      o += 1
      if (n <= 0 || n > 64) {
        try { onDup?.invoke("NAME-REPLY-ERROR count@" + n) } catch(_: Throwable) {}
        return
      }
      try { onDup?.invoke("NAME-REPLY n=" + n) } catch(_: Throwable) {}
      val fresh = ArrayList<String>()
      repeat(n) {
        if (o + 16 + 1 > p.size) {
          try { onDup?.invoke("NAME-REPLY-ERROR bounds@o=" + o) } catch(_: Throwable) {}
          return
        }
        val id = uuidStr(p, o)
        o += 16
        if (o + 1 > p.size) {
          try { onDup?.invoke("NAME-REPLY-ERROR bounds@o=" + o) } catch(_: Throwable) {}
          return
        }
        val fl = p[o].toInt() and 0xFF
        o += 1
        if (o + fl + 1 > p.size) {
          try { onDup?.invoke("NAME-REPLY-ERROR bounds@o=" + o) } catch(_: Throwable) {}
          return
        }
        val first = cleanStr(p, o, fl).trim()
        o += fl
        if (o + 1 > p.size) {
          try { onDup?.invoke("NAME-REPLY-ERROR bounds@o=" + o) } catch(_: Throwable) {}
          return
        }
        val ll = p[o].toInt() and 0xFF
        o += 1
        if (o + ll > p.size) {
          try { onDup?.invoke("NAME-REPLY-ERROR bounds@o=" + o) } catch(_: Throwable) {}
          return
        }
        val last = cleanStr(p, o, ll).trim()
        o += ll
        val nm = (first + " " + last).trim()
        if (nm.isNotBlank() && !buddyNames.containsKey(id.lowercase())) { buddyNames[id.lowercase()] = nm; fresh.add(nm) }
      }
      for (f in fresh) { try { onDup?.invoke("AMIGO " + f.take(60)) } catch(_: Throwable) {} }
      try { onDup?.invoke("AMIGO-N n=" + buddyNames.size) } catch(_: Throwable) {}
    } catch(_: Throwable) {}
  }
  fun onImDatagram(raw: ByteArray, len: Int) {
    try {
      val d = UdpCircuit.decode(raw, len) ?: return
      if (d.msgId != 0xFFFF00FE.toInt()) return
      try { onDup?.invoke("IM-RAW len=" + d.payload.size + " " + hex128(d.payload)) } catch(_: Throwable) {}
      try {
        val hx = hexFull(d.payload)
        var k = 0
        var fi = 1
        while (k < hx.length && fi <= 9) {
          try { onDup?.invoke("IM-F" + fi + " " + hx.substring(k, (k + 120).coerceAtMost(hx.length))) } catch(_: Throwable) {}
          k += 120
          fi += 1
        }
      } catch(_: Throwable) {}
      try { onDup?.invoke(imDiag(d.payload)) } catch(_: Throwable) {}
      if (d.payload.size < 108) { try { onDup?.invoke("IM-CORTO len=" + d.payload.size + " from8=" + try { if (d.payload.size >= 32) uuidStr(d.payload, 0).take(8) else "?" } catch(_: Throwable) { "?" }) } catch(_: Throwable) {} }
      val t = tryParseIm(d.payload) ?: return
      val key = t.third + "|" + t.second
      if (!dedupIm(key, t.first)) return
      imRxCount++
      val wasOpen = try { t.third.equals(imReplyUuid, ignoreCase = true) } catch(_: Throwable) { false }
      try { imThreadPush(t.third, "IM de " + t.first + ": " + t.second.take(500)) } catch(_: Throwable) {}
      if (lastImOff == 1) {
        imOffSeen++
        try { onDup?.invoke("IM-OFF " + t.first.take(40)) } catch(_: Throwable) {}
      }
      if (!wasOpen) {
        val n = imUnreadAdd(t.third)
        try { onDup?.invoke("IM-NOLEIDO " + t.first.take(40) + " n=" + n) } catch(_: Throwable) {}
      }
      imReplyUuid = t.third
      imReplyName = t.first
      try { onIm?.invoke("IM de " + t.first + ": " + t.second.take(500)) } catch(_: Throwable) {}
    } catch(_: Throwable) {}
  }
  fun buildNameReq(ids: List<String>): ByteArray {
    val us = ids.take(64)
    val h = UdpCircuit.headerReliable()
    val p = ByteBuffer.allocate(4 + 1 + 16 * us.size).order(ByteOrder.LITTLE_ENDIAN)
    p.putShort(-1)
    p.put(0x00.toByte())
    p.put(0xEB.toByte())
    p.put(us.size.toByte())
    for (u in us) p.put(UdpCircuit.uuidBE(u))
    return h + p.array()
  }
  fun buildRetrieve(agentId: String, sessionId: String): ByteArray {
    val h = UdpCircuit.headerReliable()
    val p = ByteBuffer.allocate(4 + 16 + 16).order(ByteOrder.LITTLE_ENDIAN)
    p.putShort(-1)
    p.put(0x00.toByte())
    p.put(0xFF.toByte())
    p.order(ByteOrder.BIG_ENDIAN)
    p.put(UdpCircuit.uuidBE(agentId))
    p.put(UdpCircuit.uuidBE(sessionId))
    return h + p.array()
  }
  fun buildIm(agentId: String, sessionId: String, toUuid: String, fromName: String, text: String, dialog: Int = 0): ByteArray {
    val nm = fromName.toByteArray(Charsets.UTF_8).take(255).toByteArray()
    val msg = text.toByteArray(Charsets.UTF_8).take(1023).toByteArray()
    val sess = xorUuid(UdpCircuit.uuidBE(toUuid), UdpCircuit.uuidBE(agentId))
    val h = UdpCircuit.headerReliable()
    val p = ByteBuffer.allocate(4 + 16 + 16 + 1 + 16 + 4 + 16 + 12 + 1 + 1 + 16 + 4 + 1 + nm.size + 2 + msg.size + 3).order(ByteOrder.LITTLE_ENDIAN)
    p.putShort(-1)
    p.put(0x00.toByte())
    p.put(0xFE.toByte())
    p.order(ByteOrder.BIG_ENDIAN)
    p.put(UdpCircuit.uuidBE(agentId))
    p.put(UdpCircuit.uuidBE(sessionId))
    p.put(0x00.toByte())
    p.put(UdpCircuit.uuidBE(toUuid))
    p.order(ByteOrder.LITTLE_ENDIAN)
    p.putInt(0)
    p.put(ByteArray(16))
    p.putFloat(0f)
    p.putFloat(0f)
    p.putFloat(0f)
    p.put(0x01.toByte())
    p.put(dialog.toByte())
    p.put(sess)
    p.putInt((System.currentTimeMillis() / 1000L).toInt())
    p.put((nm.size and 255).toByte())
    p.put(nm)
    p.put(((msg.size + 1) and 255).toByte())
    p.put((((msg.size + 1) ushr 8) and 255).toByte())
    p.put(msg)
    p.put(0x00.toByte())
    p.put(0x00.toByte())
    p.put(0x00.toByte())
    return h + p.array()
  }
  val EQ_MARKERS = listOf("ChatFromSimulator", "ChatterBoxInvitation", "ChatSessionRequest")
  fun onEq(body: String) {
    try {
      var m = -1
      for (mk in EQ_MARKERS) { val i = body.indexOf(mk); if (i >= 0) { m = i; break } }
      if (m < 0) return
      val seg = body.substring(m, (m + 2000).coerceAtMost(body.length))
      val mi = seg.indexOf("<key>message</key>")
      if (mi < 0) return
      val text = nextStr(seg, mi).trim()
      if (text.isBlank()) return
      var skip = false
      for (mk in EQ_MARKERS) { if (text == mk) { skip = true; break } }
      if (skip) return
      var name = "?"
      val k = seg.indexOf("<key>from")
      if (k >= 0) { val v = nextStr(seg, k).trim(); if (v.isNotEmpty()) name = v }
      val line = name + ": " + text
      if (isEchoText(text) || isOwnName(name)) {
        if (dedup("ECO:" + line)) { try { onEcho?.invoke("ECO-OK " + text.take(120) + " t=" + System.currentTimeMillis()) } catch(_: Throwable) {}; try { noteEchoDelivered(text) } catch(_: Throwable) {} }
        return
      }
      if (dedup(line)) {
        noteRxPainted(line)
        pushEq(line)
      }
    } catch(_: Throwable) {}
  }
}
