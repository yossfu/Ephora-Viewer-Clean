package com.ephora.sl
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
object EventQueue {
  fun pollBody(ack: Int = 0): String {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><llsd><map><key>ack</key><integer>" + ack + "</integer><key>done</key><boolean>0</boolean></map></llsd>"
  }
  fun grabAfter(txt: String, marker: String, tag: String): String {
    val i = txt.indexOf(marker)
    if (i < 0) return ""
    val s = txt.indexOf("<" + tag + ">", i)
    if (s < 0) return ""
    val e = txt.indexOf("</" + tag + ">", s)
    if (e < 0) return ""
    return txt.substring(s + tag.length + 2, e).trim().take(120)
  }
  fun eventTypes(txt: String): List<String> {
    val out = mutableListOf<String>()
    var i = txt.indexOf("<key>message</key>")
    var guard = 0
    while (i >= 0 && guard < 200) {
      guard++
      val s = txt.indexOf("<string>", i)
      val e = if (s >= 0) txt.indexOf("</string>", s) else -1
      if (s < 0 || e < 0 || e - s > 300) break
      out.add(txt.substring(s + 8, e).trim())
      i = txt.indexOf("<key>message</key>", e)
    }
    return out
  }
  data class Detail(val code: Int, val retry: String, val upstream: Boolean, val id: String, val idInt: Int, val types: List<String>, val body: String)
  suspend fun pollDetail(eqUrl: String, ack: Int = 0): Detail = withContext(Dispatchers.IO) {
    try {
      val client = okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(35, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).build()
      val mt = "application/llsd+xml".toMediaType()
      val body = pollBody(ack).toByteArray(Charsets.UTF_8).toRequestBody(mt)
      val req = okhttp3.Request.Builder().url(eqUrl).post(body).header("Content-Type", "application/llsd+xml").header("Accept", "application/llsd+xml").header("Accept-Encoding", "identity").header("User-Agent", "EPHORASL/7.42 (Android)").header("Connection", "close").build()
      client.newCall(req).execute().use { resp ->
        val code = resp.code
        val retry = resp.header("Retry-After") ?: ""
        val txt = try { resp.body?.string() ?: "" } catch(e: Throwable) { "readErr" }
        val up = txt.contains("Proxy Error") || txt.contains("invalid response from upstream")
        val id = grabAfter(txt, "<key>id</key>", "integer").ifBlank { grabAfter(txt, "<key>id</key>", "string") }
        Detail(code, retry, up, id, id.toIntOrNull() ?: -1, eventTypes(txt), txt)
      }
    } catch(e: Throwable) { Detail(-1, "", false, "", -1, emptyList(), "FAIL " + e::class.java.simpleName) }
  }
  fun effCode(d: Detail): Int = if (d.upstream && d.code != 502) 502 else d.code
  suspend fun pollOnce(eqUrl: String, ack: Int = 0): String {
    if (eqUrl.isBlank()) return "EQ method=POST code=- sin EventQueueGet: pulsa PROBAR CAPS con sesion valida"
    val d = pollDetail(eqUrl, ack)
    if (d.code == -1) return "EQ method=POST FAIL " + d.body.take(400)
    val ec = effCode(d)
    if (ec == 502) return "EQ method=POST code=502 timeout-esperado long-poll (reintento inmediato manual) id=" + d.id.ifBlank { "-" } + " events=" + d.types.size
    if (ec == 500 || ec == 503 || ec == 429 || ec == 504) {
      val wait = d.retry.ifBlank { "5" }
      return "EQ method=POST code=" + ec + " backoff reintento-en=" + wait + "s (sin auto-bucle) body=" + d.body.take(200)
    }
    if (ec !in 200..299) return "EQ method=POST code=" + ec + " fallo body=" + d.body.take(300)
    val first = d.types.firstOrNull() ?: "-"
    return "EQ method=POST code=200 id=" + d.id.ifBlank { "-" } + " events=" + d.types.size + " primer=" + first
  }
  object EQLoop {
    var job: Job? = null
    var polls = 0L
    var events = 0L
    var running = false
    var lastLine = ""
    var onTick: ((String) -> Unit)? = null
    fun status(): String = "EQ polls=" + polls + " events=" + events + (if (running) " vivo" else " parado") + (if (lastLine.isNotBlank()) " " + lastLine else "")
    fun start(eqUrl: String, scope: CoroutineScope): String {
      if (running) return "EQLoop ya vivo"
      if (eqUrl.isBlank()) return "EQLoop no arranca: sin EventQueueGet"
      running = true
      try { onTick?.invoke("EQ loop on polls=0 events=0") } catch(_: Throwable) {}
      job = scope.launch(Dispatchers.IO) {
        var ack = 0
        var t0 = System.currentTimeMillis()
        while (isActive) {
          val d = pollDetail(eqUrl, ack)
          val ec = effCode(d)
          if (d.code == -1) { delay(5000L); continue }
          if (ec == 502) { polls++; continue }
          if (ec == 500 || ec == 503 || ec == 429 || ec == 504) {
            polls++
            delay((d.retry.toLongOrNull() ?: 5L) * 1000L)
            continue
          }
          if (ec == 200) {
            polls++
            try { ChatManager.onEq(d.body) } catch(_: Throwable) {}
            if (d.idInt >= 0) ack = d.idInt
            if (d.types.isNotEmpty()) {
              events += d.types.size
              lastLine = "poll id=" + d.id.ifBlank { "-" } + " events=" + d.types.size + " primer=" + (d.types.firstOrNull() ?: "-")
              try { onTick?.invoke("EQ " + lastLine) } catch(_: Throwable) {}
            }
          } else { polls++; delay(5000L); continue }
          if (System.currentTimeMillis() - t0 >= 10000) {
            t0 = System.currentTimeMillis()
            try { onTick?.invoke("EQ polls=" + polls + " events=" + events) } catch(_: Throwable) {}
          }
        }
      }
      return "EQLoop arrancado"
    }
    fun stop() {
      try { job?.cancel() } catch(_: Throwable) {}
      job = null
      running = false
    }
  }
}
