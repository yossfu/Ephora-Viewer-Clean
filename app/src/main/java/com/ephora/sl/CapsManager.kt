package com.ephora.sl
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
object CapsManager {
  var caps: Map<String,String> = emptyMap()
  var capsCount = 0
  @Volatile var viewerAssetUrl = ""
  @Volatile var meshUrl = ""
  @Volatile var meshUrlState = "sin-leer"
  @Volatile private var metadataReadState = "sin-leer"
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
  private fun metadataUrl(txt: String, capName: String): String {
    try {
      val parser = android.util.Xml.newPullParser()
      parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
      parser.setInput(java.io.StringReader(txt))
      var event = parser.eventType
      while (event != org.xmlpull.v1.XmlPullParser.START_TAG && event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) event = parser.next()
      if (event != org.xmlpull.v1.XmlPullParser.START_TAG || parser.name != "llsd") {
        metadataReadState = "raiz-llsd-no-encontrada"
        return ""
      }
      if (parser.nextTag() != org.xmlpull.v1.XmlPullParser.START_TAG) {
        metadataReadState = "valor-raiz-vacio"
        return ""
      }
      val root = readLlsdValue(parser, 0, intArrayOf(0)) as? Map<*, *>
      val metadata = root?.get("Metadata") as? Map<*, *>
      val cap = metadata?.get(capName) as? Map<*, *>
      val value = (cap?.get("url") as? String)?.trim().orEmpty()
      metadataReadState = when {
        metadata == null -> "sin-metadata"
        cap == null -> "sin-$capName"
        value.isBlank() -> "$capName-sin-url"
        !value.startsWith("http") -> "$capName-url-invalida"
        else -> "$capName-ok"
      }
      return value.takeIf { it.startsWith("http") } ?: ""
    } catch (e: Throwable) {
      metadataReadState = "xml-${e.javaClass.simpleName}"
      return ""
    }
  }

  private fun readLlsdValue(parser: org.xmlpull.v1.XmlPullParser, depth: Int, nodes: IntArray): Any? {
    require(depth < 32 && ++nodes[0] <= 20000) { "llsd-limite" }
    require(parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) { "llsd-valor" }
    return when (parser.name) {
      "map" -> {
        val out = LinkedHashMap<String, Any?>()
        while (true) {
          val event = parser.next()
          if (event == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "map") break
          if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
            require(parser.name == "key") { "llsd-map-key" }
            val key = parser.nextText()
            require(parser.nextTag() == org.xmlpull.v1.XmlPullParser.START_TAG) { "llsd-map-value" }
            out[key] = readLlsdValue(parser, depth + 1, nodes)
          }
        }
        out
      }
      "array" -> {
        val out = ArrayList<Any?>()
        while (true) {
          val event = parser.next()
          if (event == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "array") break
          if (event == org.xmlpull.v1.XmlPullParser.START_TAG) out.add(readLlsdValue(parser, depth + 1, nodes))
          require(out.size <= 20000) { "llsd-array-limite" }
        }
        out
      }
      else -> {
        val tag = parser.name
        val value = parser.nextText().trim()
        when (tag) {
          "boolean" -> value == "1" || value.equals("true", ignoreCase = true)
          "integer", "int" -> value.toLongOrNull() ?: 0L
          "real" -> value.toDoubleOrNull() ?: 0.0
          "undef" -> null
          else -> value
        }
      }
    }
  }
  suspend fun fetchSeed(seedUrl: String): String = withContext(Dispatchers.IO) {
    meshUrl = ""
    meshUrlState = "leyendo"
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
        if (code !in 200..299) { meshUrlState = "caps-http-$code"; return@withContext "CAPS method=POST seedHost=" + seedHost + " seedLen=" + seedUrl.length + " seedTieneCap=" + hasCap + " seedTail4=" + tail4 + " reqCaps=" + WANT.size + " reqBodyLen=" + reqBody.length + " reqPreview=" + reqPrev + " code=" + code + " CT=[" + ctSent + "] Accept=[" + acSent + "] respLen=" + txt.length + " respPreview=" + txt.replace("\r", "").replace("\n", " ").take(800) }
        rawHasEQ = txt.contains("EventQueueGet")
        lastKeys = allKeys(txt, 20)
        caps = parseSeedMap(txt)
        capsCount = caps.size
        lastEqUrl = caps["EventQueueGet"] ?: ""
        try { viewerAssetUrl = caps["ViewerAsset"] ?: "" } catch(_: Throwable) {}
        try {
          meshUrl = metadataUrl(txt, "GetMesh2")
          meshUrlState = if (meshUrl.isNotBlank()) "GetMesh2" else ""
          if (meshUrl.isBlank()) {
            meshUrl = metadataUrl(txt, "GetMesh")
            meshUrlState = if (meshUrl.isNotBlank()) "GetMesh" else "no:$metadataReadState"
          }
        } catch(_: Throwable) { meshUrl = ""; meshUrlState = "error" }
        val hasEq = if (lastEqUrl.isNotBlank()) "si" else "no"
        val meta = metaSubKeys(txt)
        "CAPS method=POST seedHost=" + seedHost + " seedLen=" + seedUrl.length + " seedTieneCap=" + hasCap + " seedTail4=" + tail4 + " reqCaps=" + WANT.size + " reqBodyLen=" + reqBody.length + " reqPreview=" + reqPrev + " code=200 respLen=" + txt.length + " respPreview=" + txt.replace("\r", "").replace("\n", " ").take(800) + " keys=" + lastKeys.joinToString(",") + " rawTieneEQ=" + (if (rawHasEQ) "si" else "no") + " tieneEventQueueGet=" + hasEq + " capsCount=" + capsCount + " metaKeys=" + meta.joinToString(",") + " meshCap=" + meshUrlState
      }
    } catch(e: Throwable) { meshUrlState = "caps-${e.javaClass.simpleName}"; "CAPS method=POST seedHost=" + seedHost + " seedLen=" + seedUrl.length + " FAIL " + LoginManager.errText(e, "").take(400) }
  }
}
