package com.ephora.sl
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
object CapsManager {
  var caps: Map<String,String> = emptyMap()
  var capsCount = 0
  @Volatile var viewerAssetUrl = ""
  var seedHost = ""
  var lastEqUrl = ""
  var rawHasEQ = false
  var lastKeys: List<String> = emptyList()
  val WANT = listOf("EventQueueGet","FetchInventory2","FetchLib2","FetchInventoryDescendents2","GetTexture","ViewerAsset","GetMesh","GetMesh2","ViewerStats","AgentState","UpdateAgentInformation","ChatSessionRequest","EnvironmentSettings","SimulatorFeatures")
  fun seedHostOf(url: String): String {
    return try { java.net.URL(url).host } catch(_: Throwable) { "?" }
  }
  fun seedBody(): String {
    val items = WANT.joinToString("") { "<string>" + it + "</string>" }
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><llsd><array>" + items + "</array></llsd>"
  }
  fun allKeys(txt: String, max: Int): List<String> {
    val out = mutableListOf<String>()
    var i = txt.indexOf("<key>")
    var guard = 0
    while (i >= 0 && guard < 20000 && out.size < max) {
      guard++
      val e = txt.indexOf("</key>", i)
      if (e < 0) break
      out.add(txt.substring(i + 5, e).trim().take(80))
      i = txt.indexOf("<key>", e + 6)
    }
    return out
  }
  fun metaSubKeys(txt: String): List<String> {
    val i = txt.indexOf("<key>Metadata</key>")
    if (i < 0) return emptyList()
    val m = txt.indexOf("<map>", i)
    if (m < 0 || m - i > 200) return emptyList()
    return allKeys(txt.substring(m, (m + 6000).coerceAtMost(txt.length)), 10)
  }
  fun parseSeedMap(txt: String): Map<String,String> {
    val out = LinkedHashMap<String,String>()
    var i = txt.indexOf("<key>")
    var guard = 0
    while (i >= 0 && guard < 20000) {
      guard++
      val ke = txt.indexOf("</key>", i)
      if (ke < 0) break
      val key = txt.substring(i + 5, ke).trim()
      val vOpen1 = txt.indexOf("<string>", ke)
      val vOpen2 = txt.indexOf("<uri>", ke)
      val nextKey = txt.indexOf("<key>", ke + 6)
      var useOpen = -1
      var useTag = ""
      if (vOpen1 >= 0 && (nextKey < 0 || vOpen1 < nextKey)) { useOpen = vOpen1; useTag = "string" }
      else if (vOpen2 >= 0 && (nextKey < 0 || vOpen2 < nextKey)) { useOpen = vOpen2; useTag = "uri" }
      if (useOpen >= 0) {
        val ce = txt.indexOf("</" + useTag + ">", useOpen)
        if (ce >= 0 && (nextKey < 0 || ce < nextKey)) {
          val url = txt.substring(useOpen + useTag.length + 2, ce).trim()
          if (key.isNotEmpty() && url.startsWith("http") && !out.containsKey(key)) out[key] = url.take(2000)
        }
      }
      i = txt.indexOf("<key>", ke + 6)
    }
    return out
  }
  suspend fun fetchSeed(seedUrl: String): String = withContext(Dispatchers.IO) {
    if (seedUrl.isBlank()) return@withContext "CAPS method=POST code=- seed vacia: haz LOGIN primero"
    seedHost = seedHostOf(seedUrl)
    val tail4 = seedUrl.takeLast(4)
    val hasCap = if (seedUrl.contains("/cap/")) "si" else "no"
    val reqBody = seedBody()
    try {
      val client = okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).build()
      val mt = "application/llsd+xml".toMediaType()
      val body = reqBody.toByteArray(Charsets.UTF_8).toRequestBody(mt)
      val req = okhttp3.Request.Builder().url(seedUrl).post(body).header("Content-Type", "application/llsd+xml").header("Accept", "application/llsd+xml").header("Accept-Encoding", "identity").header("User-Agent", "EPHORASL/7.42 (Android)").header("Connection", "close").build()
      val ctSent = req.header("Content-Type") ?: "?"
      val acSent = req.header("Accept") ?: "?"
      val reqPrev = reqBody.take(300).replace("\r", "").replace("\n", " ")
      client.newCall(req).execute().use { resp ->
        val code = resp.code
        val txt = try { resp.body?.string() ?: "" } catch(e: Throwable) { "readErr" }
        if (code !in 200..299) return@withContext "CAPS method=POST seedHost=" + seedHost + " seedLen=" + seedUrl.length + " seedTieneCap=" + hasCap + " seedTail4=" + tail4 + " reqCaps=" + WANT.size + " reqBodyLen=" + reqBody.length + " reqPreview=" + reqPrev + " code=" + code + " CT=[" + ctSent + "] Accept=[" + acSent + "] respLen=" + txt.length + " respPreview=" + txt.replace("\r", "").replace("\n", " ").take(800)
        rawHasEQ = txt.contains("EventQueueGet")
        lastKeys = allKeys(txt, 20)
        caps = parseSeedMap(txt)
        capsCount = caps.size
        lastEqUrl = caps["EventQueueGet"] ?: ""
        try { viewerAssetUrl = caps["ViewerAsset"] ?: "" } catch(_: Throwable) {}
        val hasEq = if (lastEqUrl.isNotBlank()) "si" else "no"
        val meta = metaSubKeys(txt)
        "CAPS method=POST seedHost=" + seedHost + " seedLen=" + seedUrl.length + " seedTieneCap=" + hasCap + " seedTail4=" + tail4 + " reqCaps=" + WANT.size + " reqBodyLen=" + reqBody.length + " reqPreview=" + reqPrev + " code=200 respLen=" + txt.length + " respPreview=" + txt.replace("\r", "").replace("\n", " ").take(800) + " keys=" + lastKeys.joinToString(",") + " rawTieneEQ=" + (if (rawHasEQ) "si" else "no") + " tieneEventQueueGet=" + hasEq + " capsCount=" + capsCount + " metaKeys=" + meta.joinToString(",")
      }
    } catch(e: Throwable) { "CAPS method=POST seedHost=" + seedHost + " seedLen=" + seedUrl.length + " FAIL " + LoginManager.errText(e, "").take(400) }
  }
}
