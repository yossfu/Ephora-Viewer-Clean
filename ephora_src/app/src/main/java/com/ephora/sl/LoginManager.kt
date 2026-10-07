package com.ephora.sl
import kotlinx.coroutines.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.MessageDigest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
object LoginManager {
  const val MAIN = "https://login.agni.lindenlab.com/cgi-bin/login.cgi"
  fun splitName(input: String): Pair<String,String> {
    val t = input.trim().lowercase()
    if (t.contains(".")) { val p = t.split(".", limit=2); return p[0] to p[1] }
    if (t.contains(" ")) { val p = t.split(" ", limit=2); return p[0] to p[1] }
    return t to "resident"
  }
  fun md5pass(pwRaw: String): String {
    val pw = pwRaw.replace("\r","").replace("\n","")
    val md = MessageDigest.getInstance("MD5")
    val h = md.digest(pw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    return "\$1\$"+h
  }
  fun md5Lumiya16(pwRaw: String): String {
    var pw = pwRaw.replace("\r","").replace("\n","").trim()
    if (pw.length > 16) pw = pw.substring(0, 16)
    val md = MessageDigest.getInstance("MD5")
    val h = md.digest(pw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    return "\$1\$"+h
  }
  fun parseUser(full: String, lastOpt: String): Pair<String,String> {
    if (lastOpt.trim().isNotBlank()) return full.trim().lowercase().split(" ", ".").first().ifBlank { "x" } to lastOpt.trim().lowercase()
    val t = full.trim()
    if (t.contains(".")) { val p = t.split(".", limit=2); return p[0].lowercase() to p[1].lowercase().ifBlank { "resident" } }
    if (t.contains(" ")) { val p = t.split(" ", limit=2); return p[0].lowercase() to p[1].lowercase().ifBlank { "resident" } }
    return t.lowercase().ifBlank { "x" } to "resident"
  }
  fun pwSafeInfo(pwRaw: String): String {
    val clean = pwRaw.replace("\r","").replace("\n","")
    return "passLen="+clean.length+" leadSpace="+(pwRaw.firstOrNull()==' ')+" trailSpace="+(clean.lastOrNull()==' ')
  }
  fun esc(s: String): String = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
  fun maskFull(body: String): String {
    try {
      val key = "<name>passwd</name>"
      val i = body.indexOf(key)
      if (i < 0) return body
      val s = body.indexOf("<string>", i)
      val e = body.indexOf("</string>", i)
      if (s < 0 || e < 0 || e <= s) return body
      var out = body.substring(0, s + 8) + "\$1\$XXX" + body.substring(e)
      val k2 = out.indexOf("second_factor_token")
      if (k2 >= 0) {
        val s2 = out.indexOf("<string>", k2)
        val e2 = out.indexOf("</string>", k2)
        if (s2 >= 0 && e2 > s2) out = out.substring(0, s2 + 8) + "XXXXXX" + out.substring(e2)
      }
      return out
    } catch(t: Throwable) { return "xml-enmascarado-no-disponible causa: "+t::class.java.simpleName }
  }
  fun maskHeadTail(body: String): Pair<String,String> {
    val m = maskFull(body)
    return m.take(500) to m.takeLast(500)
  }
  fun maskedXml(body: String): String {
    val (h, t) = maskHeadTail(body)
    return h + " ...TAIL... " + t
  }
  fun xml(f: String, l: String, hash: String, start: String, mfa: String, variant: String = "A"): String {
    val digits = mfa.trim().filter { it.isDigit() }
    var m = ""
    if (digits.length == 6) m = "<member><name>second_factor_token</name><value><string>"+esc(digits)+"</string></value></member>"
    if (variant != "B") {
      return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><methodCall><methodName>login_to_simulator</methodName><params><param><value><struct>"+"<member><name>first</name><value><string>"+esc(f)+"</string></value></member>"+"<member><name>last</name><value><string>"+esc(l)+"</string></value></member>"+"<member><name>passwd</name><value><string>"+esc(hash)+"</string></value></member>"+"<member><name>start</name><value><string>"+esc(if (start.isBlank()) "last" else start.trim())+"</string></value></member>"+"<member><name>channel</name><value><string>EPHORASL</string></value></member>"+"<member><name>version</name><value><string>7.42</string></value></member>"+"<member><name>platform</name><value><string>android</string></value></member>"+"<member><name>mac</name><value><string>ephora-android</string></value></member>"+"<member><name>agree_to_tos</name><value><boolean>0</boolean></value></member>"+"<member><name>read_critical</name><value><boolean>0</boolean></value></member>"+"<member><name>last_exec_event</name><value><int>0</int></value></member>"+m+"<member><name>options</name><value><array><data>"+"<value><string>inventory-root</string></value>"+"<value><string>inventory-skeleton</string></value>"+"<value><string>initial-outfit</string></value>"+"<value><string>display_names</string></value>"+"<value><string>buddy-list</string></value>"+"</data></array></value></member>"+"</struct></value></param></params></methodCall>"
    }
    val opts = listOf("inventory-root","inventory-skeleton","inventory-lib-root","inventory-lib-owner","inventory-skel-lib","initial-outfit","gestures","event_categories","event_notifications","classified_categories","buddy-list","ui-config","tutorial_settings","login-flags","global-textures","adult_compliant").joinToString("") { "<value><string>"+it+"</string></value>" }
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><methodCall><methodName>login_to_simulator</methodName><params><param><value><struct>"+"<member><name>first</name><value><string>"+esc(f)+"</string></value></member>"+"<member><name>last</name><value><string>"+esc(l)+"</string></value></member>"+"<member><name>passwd</name><value><string>"+esc(hash)+"</string></value></member>"+"<member><name>start</name><value><string>"+esc(if (start.isBlank()) "last" else start.trim())+"</string></value></member>"+"<member><name>channel</name><value><string>EPHORASL</string></value></member>"+"<member><name>version</name><value><string>7.42</string></value></member>"+"<member><name>platform</name><value><string>android</string></value></member>"+"<member><name>platform_version</name><value><string>15-SDK36</string></value></member>"+"<member><name>mac</name><value><string>ephora-android</string></value></member>"+"<member><name>id0</name><value><string>ephora-android-id0</string></value></member>"+"<member><name>agree_to_tos</name><value><boolean>1</boolean></value></member>"+"<member><name>read_critical</name><value><boolean>1</boolean></value></member>"+"<member><name>last_exec_event</name><value><int>0</int></value></member>"+m+"<member><name>options</name><value><array><data>"+opts+"</data></array></value></member>"+"</struct></value></param></params></methodCall>"
  }
  fun netDiag(url: String): String {
    val host = try { URL(url).host } catch(_: Throwable) { url }
    val g = try { java.net.InetAddress.getByName("www.google.com").hostAddress ?: "?" } catch(e: Exception) { "FAIL" }
    val h = try { java.net.InetAddress.getByName(host).hostAddress ?: "?" } catch(e: Exception) { "FAIL" }
    return "net google="+g+" host="+host+"="+h
  }
  fun errText(e: Throwable, extra: String = ""): String {
    val cs = e.cause?.let { " causa="+(it::class.java.simpleName)+":"+(it.message ?: "sin-mensaje") } ?: ""
    val st = android.util.Log.getStackTraceString(e).lines().take(30).joinToString("\n").take(2000)
    return "EXC "+e::class.java.simpleName+" msg="+(e.message ?: "sin-mensaje")+cs+" "+extra+"\nSTACK:\n"+st
  }
  fun readRaw(s: java.io.InputStream?, tag: String, cap: Int = 2100000): Pair<String,String> {
    if (s == null) return "" to "respTruncado=no stream-null-"+tag
    return try {
      val baos = java.io.ByteArrayOutputStream()
      val buf = ByteArray(32768)
      var cut = false
      try {
        while (true) {
          val n = try { s.read(buf, 0, 32768) } catch(e: java.io.EOFException) { break } catch(e: Throwable) { break }
          if (n < 0) break
          if (n > 0) baos.write(buf, 0, n)
          if (baos.size() > cap) { cut = true; break }
        }
      } finally { try { s.close() } catch(_: Throwable) {} }
      baos.toString("UTF-8") to if (cut) "respTruncado=si" else "respTruncado=no"
    } catch(e: Throwable) { "" to ("respTruncado=no readErr="+errText(e, tag).take(600)) }
  }
  fun readSafe(s: java.io.InputStream?, tag: String): Pair<String,String> = readRaw(s, tag)
  object Session {
    var agentId = ""; var sessionId = ""; var secureId = ""; var seedCap = ""
    var firstLast = ""; var access = ""; var seedPending = false
    var buddies: List<String> = emptyList()
    var circuitCode = 0; var simIp = ""; var simPort = 0
    fun save(txt: String, who: String): String {
      agentId = valOf(txt, "agent_id")
      sessionId = valOf(txt, "session_id")
      secureId = valOf(txt, "secure_session_id")
      seedCap = valOf(txt, "seed_capability")
      firstLast = valOf(txt, "first_name")+"."+valOf(txt, "last_name")
      buddies = buddyIds(txt)
      access = valOf(txt, "agent_access")
      circuitCode = intOf(txt, "circuit_code")
      simIp = valOf(txt, "sim_ip")
      simPort = intOf(txt, "sim_port")
      seedPending = seedCap.isBlank()
      if (agentId.isBlank() || sessionId.isBlank()) return "sesionGuardada=no ("+who+") login="+loginState(txt)
      if (seedPending) return "sesionGuardada=parcial ("+who+") agent="+agentId+" sessionFin="+sessionId.takeLast(4)+" seedPendiente=si"
      val host = try { java.net.URL(seedCap).host } catch(_: Throwable) { "?" }
      return "sesionGuardada=si ("+who+") agent="+agentId+" seedHost="+host+" seedLen="+seedCap.length+" secureFin="+secureId.takeLast(4)+" circuit_fin="+circuitCode.toString().takeLast(4)+" sim_port="+simPort
    }
  }
  suspend fun seedFetch(url: String): String = withContext(Dispatchers.IO) {
    try {
      val client = okhttp3.OkHttpClient.Builder().connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS).readTimeout(25, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).build()
      val req = okhttp3.Request.Builder().url(url).get().header("Accept", "application/llsd+xml").header("Accept-Encoding", "identity").header("User-Agent", "EPHORASL/7.42 (Android)").header("Connection", "close").build()
      client.newCall(req).execute().use { resp ->
        val code = resp.code
        val txt = try { resp.body?.string()?.take(2100000) ?: "" } catch(e: Throwable) { "readErr" }
        var caps = 0
        var i = txt.indexOf("<key>")
        while (i >= 0) { caps++; i = txt.indexOf("<key>", i + 5) }
        "SEED code="+code+" len="+txt.length+" caps="+caps+" eqg="+(if (txt.contains("EventQueueGet")) "si" else "?")
      }
    } catch(e: Throwable) { "SEED FAIL "+errText(e, "") }
  }
  fun grabTag(seg: String, tag: String, max: Int): String {
    val s = seg.indexOf("<"+tag+">")
    if (s < 0) return ""
    val e = seg.indexOf("</"+tag+">", s)
    if (e < 0) return ""
    return seg.substring(s + tag.length + 2, e).take(max)
  }
  fun intOf(txt: String, key: String): Int {
    val i = txt.indexOf("<name>"+key+"</name>")
    if (i < 0) return 0
    val seg = txt.substring(i, (i+500).coerceAtMost(txt.length))
    for (tag in listOf("int", "i4", "integer")) {
      val s = seg.indexOf("<"+tag+">")
      if (s < 0) continue
      val e = seg.indexOf("</"+tag+">", s)
      if (e < 0) continue
      val n = seg.substring(s + tag.length + 2, e).trim().toIntOrNull()
      if (n != null) return n
    }
    return 0
  }
  fun valOf(txt: String, key: String): String {
    val i = txt.indexOf("<name>"+key+"</name>")
    if (i < 0) return ""
    val v = txt.indexOf("<value>", i)
    if (v < 0 || v - i > 4000) return ""
    val seg = txt.substring(v, (v+4000).coerceAtMost(txt.length))
    val s1 = grabTag(seg, "string", 1200)
    if (s1.isNotEmpty()) return s1
    val b = grabTag(seg, "boolean", 8)
    if (b.isNotEmpty()) return b
    val n = grabTag(seg, "int", 16)
    if (n.isNotEmpty()) return n
    return ""
  }
  fun loginState(txt: String): String {
    val raw = valOf(txt, "login").trim().lowercase()
    if (raw == "true" || raw == "1") return "true"
    if (valOf(txt, "agent_id").isNotBlank() && valOf(txt, "session_id").isNotBlank()) return "asumido-true-por-agent"
    if (raw.isBlank()) return "ausente"
    return "false"
  }
  fun isLoginTrue(txt: String): Boolean {
    val s = loginState(txt)
    return s == "true" || s == "asumido-true-por-agent"
  }
  fun loginCtx(txt: String): String {
    val i = txt.indexOf("<name>login</name>")
    if (i < 0) return "login-tag-ausente"
    val a = (i - 100).coerceAtLeast(0)
    return txt.substring(a, (i + 200).coerceAtMost(txt.length)).replace("\r","").replace("\n"," ")
  }
  fun buddyIds(txt: String): List<String> {
    try {
      val out = ArrayList<String>()
      val i = txt.indexOf("<name>buddy-list</name>")
      if (i < 0) return out
      val a = txt.indexOf("<array>", i)
      if (a < 0 || a - i > 4000) return out
      val e = txt.indexOf("</array>", a)
      if (e < 0) return out
      var guard = 0
      for (tag in listOf("<name>buddy_id</name>", "<key>buddy_id</key>")) {
        var j = txt.indexOf(tag, a)
        while (j >= 0 && j < e && guard < 200) {
          guard++
          val s = txt.indexOf("<string>", j)
          if (s < 0 || s > e) break
          val k = txt.indexOf("</string>", s)
          if (k < 0 || k > e || k - s > 200) break
          val u = txt.substring(s + 8, k).trim()
          if (u.length == 36 && !out.contains(u)) out.add(u)
          j = txt.indexOf(tag, k)
        }
      }
      return out
    } catch(_: Throwable) { return emptyList() }
  }
  fun buddyRaw(txt: String): String {
    try {
      val i = txt.indexOf("<name>buddy-list</name>")
      if (i < 0) return "ausente"
      return txt.substring(i, (i + 200).coerceAtMost(txt.length)).replace("\r", "").replace("\n", " ")
    } catch(_: Throwable) { return "exc" }
  }
  fun buddyCount(txt: String): String {
    val i = txt.indexOf("<name>buddy-list</name>")
    if (i < 0) return "vacio"
    val a = txt.indexOf("<array>", i)
    if (a < 0 || a - i > 4000) return "?"
    val e = txt.indexOf("</array>", a)
    if (e < 0) return "truncado"
    var c = 0
    var j = txt.indexOf("<value>", a)
    while (j >= 0 && j < e) { c++; j = txt.indexOf("<value>", j + 7) }
    return c.toString()
  }
  fun seedHostLen(txt: String): String {
    val seed = valOf(txt, "seed_capability")
    if (seed.isBlank()) return "vacia"
    val host = try { java.net.URL(seed).host } catch(_: Throwable) { "?" }
    return host+" len="+seed.length
  }
  fun summarize(txt: String): String {
    if (txt.isBlank()) return "(respuesta vacia)"
    return "agent_id="+valOf(txt,"agent_id")+"\nfirst="+valOf(txt,"first_name")+" last="+valOf(txt,"last_name")+" access="+valOf(txt,"agent_access")+"\nsession_fin="+valOf(txt,"session_id").takeLast(4)+"\nsecure_fin="+valOf(txt,"secure_session_id").takeLast(4)+"\ncircuit_fin="+intOf(txt,"circuit_code").toString().takeLast(4)+" sim="+valOf(txt,"sim_ip")+":"+intOf(txt,"sim_port")+"\nseed="+seedHostLen(txt)+"\nlogin_raw="+valOf(txt,"login").take(100)+" login="+loginState(txt)+"\nloginCtx="+loginCtx(txt)+"\ninvRoot="+valOf(txt,"inventory-root").take(60)+" invSkel="+(if (txt.contains("inventory-skeleton")) "si" else "no")+" BUDDY-N "+buddyIds(txt).size+(if (buddyIds(txt).isEmpty()) " BUDDY-RAW "+buddyRaw(txt) else "")+"\nmotd="+valOf(txt,"motd").take(200)+"\nmessage_id="+valOf(txt,"message_id")+"\nLinden_Error_Code="+valOf(txt,"Linden_Error_Code")+"\nreason="+valOf(txt,"reason")+"\nmessage="+valOf(txt,"message").replace("\r","").take(200)+"\nnext="+valOf(txt,"next_url").take(400)
  }
  suspend fun login(fIn: String, lIn: String, pw: String, url: String, start: String, mfa: String = "", variant: String = "A", hashMode: String = "full"): String = withContext(Dispatchers.IO) {
    val (f, l) = parseUser(fIn, lIn)
    val net = netDiag(url)
    val cleanLen = pw.replace("\r","").replace("\n","").length
    val usedLen = if (hashMode == "lumiya16") minOf(cleanLen, 16) else cleanLen
    val info = pwSafeInfo(pw)+" hashMode="+hashMode+" passLenOrig="+cleanLen+" passLenUsado="+usedLen+" mfa="+(if (mfa.trim().filter{it.isDigit()}.length==6) "6dig" else "vacio")+" variante="+variant+" firstLastEnviados="+f+"."+l
    val hash = if (hashMode == "lumiya16") md5Lumiya16(pw) else md5pass(pw)
    if (pw.replace("\r","").replace("\n","").isEmpty()) return@withContext "FAIL validacion pass vacio user="+f+"."+l+"\nurl="+url+"\n"+net
    val body: String
    try { body = xml(f, l, hash, start, mfa, variant) }
    catch(e: Throwable) { return@withContext "FAIL construccion XML\n"+errText(e, info) }
    val bodyBytes = body.toByteArray(Charsets.UTF_8)
    val mf = maskFull(body)
    val reqLog = "REQ reqFixed=true hdrVar=ct-exact reqCL="+bodyBytes.size+" reqCT=text/xml reqTE=null-fixed bodyLen="+bodyBytes.size+" xmlP1="+mf.take(500)+" xmlP2="+mf.drop(500).take(500)+" xmlP3="+mf.drop(1000).take(500)
    val conn = URL(url).openConnection() as HttpsURLConnection
    try {
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.doInput = true
      conn.useCaches = false
      conn.instanceFollowRedirects = false
      conn.connectTimeout = 25000
      conn.readTimeout = 25000
      conn.setFixedLengthStreamingMode(bodyBytes.size)
      conn.setRequestProperty("Content-Type", "text/xml")
      conn.setRequestProperty("Content-Length", bodyBytes.size.toString())
      conn.setRequestProperty("Accept", "text/xml")
      conn.setRequestProperty("Accept-Encoding", "identity")
      conn.setRequestProperty("User-Agent", "EPHORASL/7.42 (Android)")
      conn.setRequestProperty("Connection", "close")
    } catch(e: Throwable) { return@withContext "FAIL openConnection config\n"+reqLog+"\nurl="+url+"\n"+net+"\n"+errText(e, info) }
    try { conn.outputStream.use { it.write(bodyBytes); it.flush() } }
    catch(e: Throwable) { return@withContext "FAIL POST write\n"+reqLog+"\nurl="+url+"\n"+net+"\n"+errText(e, info) }
    val code: Int
    try { code = conn.responseCode }
    catch(e: Throwable) { return@withContext "FAIL getResponseCode\n"+reqLog+"\nurl="+url+"\n"+net+"\n"+errText(e, info) }
    val hdrAll = try { conn.headerFields.entries.joinToString("; ") { k -> (k.key ?: "status")+"="+k.value } } catch(_: Throwable) { "hdrs=?" }
    val ctSent = try { conn.getRequestProperty("Content-Type") ?: "?" } catch(_: Throwable) { "?" }
    val respMsg = try { conn.responseMessage ?: "?" } catch(_: Throwable) { "?" }
    val reqId = try { conn.getHeaderField("X-LL-Request-Id") ?: "?" } catch(_: Throwable) { "?" }
    val respHeaderCL = try { conn.getHeaderField("Content-Length") ?: "null" } catch(_: Throwable) { "?" }
    val stream = if (code in 200..299) { try { conn.inputStream } catch(_: Throwable) { conn.errorStream } } else { conn.errorStream ?: try { conn.inputStream } catch(_: Throwable) { null } }
    val (txt, rflag) = readRaw(stream, "code="+code)
    "HTTP "+code+" "+respMsg+" user="+f+"."+l+" "+info+" via=nativo\n"+reqLog+"\nurl="+url+"\n"+net+"\nRESP "+hdrAll+"\nreqId="+reqId+" respHeaderCL="+respHeaderCL+" respBytesReales="+txt.length+" "+rflag+" rawError308="+txt.take(800)+"\n"+Session.save(txt, "nativo")+"\nSUMM "+summarize(txt)
  }
  suspend fun controlGet(url: String): String = withContext(Dispatchers.IO) {
    try {
      val c = URL(url).openConnection() as HttpsURLConnection
      c.requestMethod = "GET"
      c.connectTimeout = 15000
      c.readTimeout = 15000
      c.useCaches = false
      c.instanceFollowRedirects = false
      c.setRequestProperty("User-Agent", "EPHORASL/7.42")
      c.setRequestProperty("Accept-Encoding", "identity")
      c.setRequestProperty("Connection", "close")
      val code = try { c.responseCode } catch(e: Throwable) { return@withContext "GET FAIL "+errText(e, "") }
      val hdr = try { c.headerFields.entries.joinToString("; ") { k -> (k.key ?: "status")+"="+k.value } } catch(_: Throwable) { "hdrs=?" }
      val msg = try { c.responseMessage ?: "?" } catch(_: Throwable) { "?" }
      val reqId = try { c.getHeaderField("X-LL-Request-Id") ?: "?" } catch(_: Throwable) { "?" }
      val (txt, rflagGet) = readRaw(if (code in 200..299) { try { c.inputStream } catch(_: Throwable) { c.errorStream } } else { c.errorStream }, "get")
      "GET code="+code+" "+msg+"\nurl="+url+"\n"+hdr+"\nreqId="+reqId+" respBytesReales="+txt.length+" "+rflagGet+" body500="+txt.take(500)
    } catch(e: Throwable) { "GET FAIL "+errText(e, "") }
  }
  suspend fun loginOkHttp(fIn: String, lIn: String, pw: String, url: String, start: String, mfa: String = "", variant: String = "A", hashMode: String = "full"): String = withContext(Dispatchers.IO) {
    val (f, l) = parseUser(fIn, lIn)
    val net = netDiag(url)
    val cleanLen = pw.replace("\r","").replace("\n","").length
    val usedLen = if (hashMode == "lumiya16") minOf(cleanLen, 16) else cleanLen
    val info = pwSafeInfo(pw)+" hashMode="+hashMode+" passLenOrig="+cleanLen+" passLenUsado="+usedLen+" mfa="+(if (mfa.trim().filter{it.isDigit()}.length==6) "6dig" else "vacio")+" variante="+variant+" firstLastEnviados="+f+"."+l
    val hash = if (hashMode == "lumiya16") md5Lumiya16(pw) else md5pass(pw)
    val body: String
    try { body = xml(f, l, hash, start, mfa, variant) }
    catch(e: Throwable) { return@withContext "FAIL construccion XML\n"+errText(e, info) }
    val bodyBytes = body.toByteArray(Charsets.UTF_8)
    val mf = maskFull(body)
    val reqLog = "REQ reqFixed=true hdrVar=ct-bare reqCL="+bodyBytes.size+" reqCT=text/xml via=okhttp bodyLen="+bodyBytes.size+" xmlP1="+mf.take(500)+" xmlP2="+mf.drop(500).take(500)+" xmlP3="+mf.drop(1000).take(500)
    try {
      val client = okhttp3.OkHttpClient.Builder().connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS).readTimeout(25, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).build()
      val mt = "text/xml".toMediaType()
      val reqBody = bodyBytes.toRequestBody(mt)
      val req = okhttp3.Request.Builder().url(url).post(reqBody).header("Content-Type", "text/xml").header("Accept", "text/xml").header("Accept-Encoding", "identity").header("User-Agent", "EPHORASL/7.42 (Android)").header("Connection", "close").build()
      val ctExact = req.header("Content-Type") ?: "?"
      client.newCall(req).execute().use { resp ->
        val code = resp.code
        val announced = resp.header("Content-Length") ?: "null"
        var txt = ""
        var readErr = ""
        try { txt = resp.body?.string() ?: "" }
        catch(e: Throwable) {
          readErr = errText(e, "okhttp").take(300)
          try { txt = resp.body?.source()?.buffer?.snapshot()?.utf8() ?: "" } catch(_: Throwable) {}
        }
        val rflag = (if (txt.length >= 2100000) "respTruncado=si" else "respTruncado=no") + (if (readErr.isNotBlank()) " readErr="+readErr else "")
        val reqId = resp.header("X-LL-Request-Id") ?: "?"
        "HTTP "+code+" "+resp.message+" "+resp.protocol+" user="+f+"."+l+" "+info+" via=okhttp\n"+reqLog+" reqCTExacto=["+ctExact+"] reqAccept=[text/xml]\nurl="+url+"\n"+net+"\nRESP hdrs="+resp.headers+"\nreqId="+reqId+" respHeaderCL="+announced+" respBytesReales="+txt.length+" "+rflag+" rawError308="+txt.take(800)+"\n"+Session.save(txt, "okhttp")+"\nSUMM "+summarize(txt)
      }
    } catch(e: Throwable) { return@withContext "FAIL okhttp\n"+reqLog+"\nurl="+url+"\n"+net+"\n"+errText(e, info) }
  }
}
