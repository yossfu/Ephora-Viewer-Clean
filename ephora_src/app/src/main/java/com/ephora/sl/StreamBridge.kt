package com.ephora.sl
import android.content.Context
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// Puente Firebase (SOLO REST via OkHttp, sin SDK Firebase ni google-services):
// PUT .../ephora/reports/<dev>/latest.json (overwrite) y POST .../history.json (push).
// Reglas Realtime Database a aplicar en console (avisar al dueno):
// {"rules":{"ephora":{"reports":{".read":true,".write":true}}}}
// Reglas abiertas = cualquiera con la URL lee/escribe; aceptado porque el log va scrubbeado;
// fase siguiente cerrara con auth si el dueno quiere.
object StreamBridge {
  const val BASE = "https://ephora-dat-default-rtdb.firebaseio.com/ephora/reports/"
  @Volatile var streaming = true
  @Volatile var lastState = "STREAM-ESTADO off"
  private var job: Job? = null
  data class Snap(val app: String, val fase: String, val edadS: String, val text: String, val summary: String)
  data class SessionSnap(val app: String, val loginReqId: String, val agent4: String, val seedTail4: String, val capsCount: Int, val simPort: Int, val head: String)
  fun devId(ctx: Context): String {
    return try {
      val raw = try { android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "" } catch(_: Throwable) { "" }
      val tail = if (raw.length >= 6) raw.takeLast(6) else raw.ifEmpty { "nodev" }
      val mod = try { android.os.Build.MODEL ?: "" } catch(_: Throwable) { "" }
      (tail + "-" + mod).lowercase(Locale.US).replace(Regex("[^a-z0-9-]"), "").take(40).ifEmpty { "nodev" }
    } catch(_: Throwable) { "nodev" }
  }
  fun scrub(t: String): String {
    return try {
      var s = t
      try { s = s.replace(Regex("(<name>passwd</name><value><string>)[^<]*"), "$1\$1\$XXX") } catch(_: Throwable) {}
      for (k in listOf("secure_session_id", "session_id", "agent_id")) {
        try {
          s = s.replace(Regex("(" + k + "\\s*[=:]\\s*)([0-9a-fA-F-]{5,})")) { m -> m.groupValues[1] + "****" + m.groupValues[2].takeLast(4) }
        } catch(_: Throwable) {}
      }
      try {
        s = s.replace(Regex("(circuit_code\\s*[=:]\\s*)([0-9a-fA-F-]{3,})")) { m -> m.groupValues[1] + "**" + m.groupValues[2].takeLast(2) }
      } catch(_: Throwable) {}
      val texGuard = LinkedHashMap<String,String>()
      try {
        var gi = 0
        s = s.replace(Regex("TEX-UUID id=\\d+ u=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")) { m -> gi++; val k = "TEXGUARD" + gi + "X"; texGuard[k] = m.groupValues[1]; m.value.replace(m.groupValues[1], k) }
      } catch(_: Throwable) {}
      try {
        s = s.replace(Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")) { m -> m.value.take(8) }
      } catch(_: Throwable) {}
      try { for ((k, v) in texGuard) s = s.replace(k, v) } catch(_:Throwable) {}
      s
    } catch(_: Throwable) { t.takeLast(200) }
  }
  private fun client(): okhttp3.OkHttpClient {
    return okhttp3.OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
  }
  private fun iso(): String {
    return try {
      val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
      try { df.timeZone = TimeZone.getTimeZone("UTC") } catch(_: Throwable) {}
      df.format(Date())
    } catch(_: Throwable) { "" }
  }
  private fun send(put: Boolean, url: String, json: String): Boolean {
    var ok = false
    var why = ""
    var c: okhttp3.OkHttpClient? = null
    try {
      c = client()
      val mt = "application/json; charset=utf-8".toMediaType()
      val b = json.toRequestBody(mt)
      val req = if (put) okhttp3.Request.Builder().url(url).put(b).build() else okhttp3.Request.Builder().url(url).post(b).build()
      val resp = c.newCall(req).execute()
      try {
        ok = resp.isSuccessful
        if (!ok) why = "http" + resp.code
      } finally { try { resp.close() } catch(_: Throwable) {} }
    } catch(e: Throwable) { ok = false; why = e::class.java.simpleName }
    try {
      try { c?.dispatcher?.executorService?.shutdown() } catch(_: Throwable) {}
      try { c?.connectionPool?.evictAll() } catch(_: Throwable) {}
    } catch(_: Throwable) {}
    try { lastState = if (ok) "STREAM-ESTADO ok" else "STREAM-ESTADO error " + why.ifEmpty { "?" } } catch(_: Throwable) {}
    return ok
  }
  private fun latestJson(s: Snap): String {
    return try {
      val o = org.json.JSONObject()
      o.put("ts", iso())
      o.put("app", s.app)
      o.put("fase", s.fase)
      o.put("circuitEdadS", s.edadS)
      o.put("text", s.text)
      o.toString()
    } catch(_: Throwable) { "{\"app\":\"" + s.app + "\"}" }
  }
  private fun histJson(s: Snap): String {
    return try {
      val o = org.json.JSONObject()
      o.put("ts", iso())
      o.put("app", s.app)
      o.put("summary", s.summary)
      o.put("text", s.text)
      o.toString()
    } catch(_: Throwable) { "{\"app\":\"" + s.app + "\"}" }
  }
  fun pushLatest(dev: String, s: Snap) {
    try {
      val sc = try { scrub(s.text.takeLast(12000)) } catch(_: Throwable) { "" }
      send(true, BASE + dev + "/latest.json", latestJson(s.copy(text = sc)))
    } catch(_: Throwable) { try { lastState = "STREAM-ESTADO error exc" } catch(_: Throwable) {} }
  }
  fun pushHist(dev: String, s: Snap) {
    try {
      val sc = try { scrub(s.text.takeLast(12000)) } catch(_: Throwable) { "" }
      val sm = try { scrub(s.summary.lines().take(3).joinToString(" | ")) } catch(_: Throwable) { "" }
      send(false, BASE + dev + "/history.json", histJson(s.copy(text = sc, summary = sm)))
    } catch(_: Throwable) { try { lastState = "STREAM-ESTADO error exc" } catch(_: Throwable) {} }
  }
  fun pushNow(dev: String, s: Snap) {
    try { pushHist(dev, s) } catch(_: Throwable) {}
  }
  fun pushSession(dev: String, s: SessionSnap) {
    try {
      val o = org.json.JSONObject()
      o.put("app", s.app)
      o.put("ts", iso())
      o.put("loginReqId", s.loginReqId)
      o.put("agent4", s.agent4)
      o.put("seedTail4", s.seedTail4)
      o.put("capsCount", s.capsCount)
      o.put("simPort", s.simPort)
      o.put("head", try { scrub(s.head) } catch(_: Throwable) { "" })
      send(true, BASE + dev + "/session.json", o.toString())
    } catch(_: Throwable) { try { lastState = "STREAM-ESTADO error exc" } catch(_: Throwable) {} }
  }
  fun start(scope: CoroutineScope, dev: String, snap: () -> Snap?) {
    try { stop() } catch(_: Throwable) {}
    streaming = true
    try { lastState = "STREAM-ESTADO ok" } catch(_: Throwable) {}
    job = scope.launch(Dispatchers.IO) {
      var lastHist = 0L
      while (isActive && streaming) {
        try {
          val s = try { snap() } catch(_: Throwable) { null }
          if (s != null) {
            pushLatest(dev, s)
            val now = System.currentTimeMillis()
            if (now - lastHist >= 300000L) { lastHist = now; pushHist(dev, s) }
          }
        } catch(_: Throwable) {}
        try { delay(15000L) } catch(_: Throwable) { break }
      }
    }
  }
  fun stop() {
    try { streaming = false } catch(_: Throwable) {}
    try { job?.cancel() } catch(_: Throwable) {}
    job = null
    try { lastState = "STREAM-ESTADO off" } catch(_: Throwable) {}
  }
}
