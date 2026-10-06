package com.ephora.sl
import kotlinx.coroutines.*
object TexFetch {
  var done = false
  private var flying = false
  private data class TexResp(val code: Int, val ct: String, val bytes: ByteArray, val loc: String, val srv: String, val xh: String, val clen: String, val txt: String)
  private fun bodyTxt(b: ByteArray): String {
    try {
      val s = String(b, Charsets.UTF_8)
      return s.replace("\r", "").replace("\n", " ").take(400)
    } catch(_: Throwable) { return "-" }
  }
  private fun forceHttps(url: String): String {
    try {
      if (url.startsWith("http://") && url.contains("asset-cdn")) return url.replaceFirst("http://", "https://")
    } catch(_: Throwable) {}
    return url
  }
  private fun esqOf(url: String): String {
    try {
      if (url.startsWith("https://")) return "https"
      if (url.startsWith("http://")) return "http"
    } catch(_: Throwable) {}
    return "?"
  }
  private data class TexRes(val code: Int, val ct: String, val len: Int, val hex16: String, val hex64: String, val srv: String, val xh: String, val clen: String, val esq: String, val ini: Int, val loc: String, val hops: Int, val fail: String, val msg: String, val err0: String)
  fun reset() { try { done = false; flying = false } catch(_: Throwable) {} }
  private fun hexOf(b: ByteArray, n: Int): String {
    try {
      val sb = StringBuilder()
      var i = 0
      while (i < n && i < b.size) {
        sb.append("%02X".format(b[i]))
        i++
      }
      return sb.toString()
    } catch(_: Throwable) { return "-" }
  }
  private fun buildClient(): okhttp3.OkHttpClient {
    return okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
  }
  private fun resolveUrl(base: String, loc: String): String {
    try {
      if (loc.startsWith("http://") || loc.startsWith("https://")) return loc
      val u = java.net.URL(base)
      val port = if (u.port < 0) "" else ":" + u.port
      if (loc.startsWith("/")) return u.protocol + "://" + u.host + port + loc
      val path = u.path
      val dir = if (path.contains("/")) path.substring(0, path.lastIndexOf("/") + 1) else "/"
      return u.protocol + "://" + u.host + port + dir + loc
    } catch(_: Throwable) { return loc }
  }
  private fun getOnce(client: okhttp3.OkHttpClient, url: String): TexResp {
    val req = okhttp3.Request.Builder().url(url).get().header("Accept", "*/*").header("Accept-Encoding", "identity").header("User-Agent", "EPHORASL/7.50 (Android)").header("Connection", "close").build()
    client.newCall(req).execute().use { resp ->
      val code = resp.code
      val hd = resp.headers
      val ct = try { hd["Content-Type"] ?: "?" } catch(_: Throwable) { "?" }
      val loc = try { hd["Location"] ?: "" } catch(_: Throwable) { "" }
      val srv = try { hd["Server"] ?: "-" } catch(_: Throwable) { "-" }
      val cl = try { hd["Content-Length"] ?: "?" } catch(_: Throwable) { "?" }
      var xh = ""
      try {
        var i = 0
        while (i < hd.size) {
          val nm = hd.name(i)
          if (nm.startsWith("X-", ignoreCase = true)) {
            if (xh.isNotEmpty()) xh += ";"
            xh += nm + "=" + hd.value(i)
          }
          i++
        }
        if (xh.length > 220) xh = xh.take(220)
        if (xh.isEmpty()) xh = "-"
      } catch(_: Throwable) { xh = "-" }
      if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
        try { resp.body?.close() } catch(_: Throwable) {}
        return TexResp(code, ct, ByteArray(0), loc, srv, xh, cl, "-")
      }
      val bytes = try { resp.body?.bytes() ?: ByteArray(0) } catch(_: Throwable) { ByteArray(0) }
      return TexResp(code, ct, bytes, "", srv, xh, cl, bodyTxt(bytes))
    }
  }
  private fun runChain(client: okhttp3.OkHttpClient, startUrl: String, esq0: String, httpFallback: String, tag: String, hopOut: MutableList<String>, upHttps: Boolean): TexRes {
    var url = startUrl
    var esq = esq0
    var code0 = -1
    var loc0 = "-"
    var hops = 0
    var triedFallback = false
    var err0 = "-"
    while (hops <= 5) {
      var r: TexResp? = null
      var err = ""
      try {
        r = getOnce(client, url)
      } catch(e: Throwable) {
        err = e::class.java.simpleName + " " + (e.message ?: "sin-mensaje").take(160)
      }
      if (err.isNotEmpty()) {
        try { hopOut.add("TEX-HOP tag=" + tag + " n=" + hops + " esq=" + esqOf(url) + " code=FAIL " + err.take(80)) } catch(_: Throwable) {}
        try { if (err0 == "-") err0 = err.take(120) } catch(_: Throwable) {}
        if (!triedFallback && httpFallback.isNotEmpty()) {
          triedFallback = true
          url = httpFallback
          esq = "http"
          hops += 1
        } else {
          return TexRes(-1, "?", 0, "-", "-", "-", "-", "?", esq, code0, loc0, hops, err, "-", err0)
        }
      } else if (r != null && (r.code == 301 || r.code == 302 || r.code == 303 || r.code == 307 || r.code == 308)) {
        if (code0 < 0) {
          code0 = r.code
          loc0 = if (r.loc.isEmpty()) "-" else r.loc.take(120)
        }
        if (r.loc.isEmpty()) {
          try { hopOut.add("TEX-HOP tag=" + tag + " n=" + hops + " esq=" + esqOf(url) + " code=" + r.code + " sin-location") } catch(_: Throwable) {}
          return TexRes(r.code, r.ct, 0, "-", "-", r.srv, r.xh, r.clen, esq, code0, loc0, hops, "sin-location", r.txt, err0)
        }
        try { hopOut.add("TEX-HOP tag=" + tag + " n=" + hops + " esq=" + esqOf(url) + " code=" + r.code + " loc=" + r.loc.take(120)) } catch(_: Throwable) {}
        if (upHttps) url = forceHttps(resolveUrl(url, r.loc)) else url = resolveUrl(url, r.loc)
        hops += 1
      } else if (r != null) {
        try { hopOut.add("TEX-HOP tag=" + tag + " n=" + hops + " esq=" + esqOf(url) + " code=" + r.code + " final") } catch(_: Throwable) {}
        return TexRes(r.code, r.ct, r.bytes.size, hexOf(r.bytes, 16), hexOf(r.bytes, 64), r.srv, r.xh, r.clen, esq, code0, loc0, hops, "", r.txt, err0)
      }
    }
    return TexRes(-1, "?", 0, "-", "-", "-", "-", "?", esq, code0, loc0, hops, "demasiados-redirects", "-", err0)
  }
  private fun certDiag(host: String): String {
    var sock: javax.net.ssl.SSLSocket? = null
    try {
      val tm = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> { return arrayOf() }
      }
      val sc = javax.net.ssl.SSLContext.getInstance("TLS")
      sc.init(null, arrayOf(tm), java.security.SecureRandom())
      sock = sc.socketFactory.createSocket(host, 443) as javax.net.ssl.SSLSocket
      try { sock.startHandshake() } catch(_: Throwable) {}
      val certs = sock.session.peerCertificates
      if (certs.isEmpty()) return "sin-cert"
      val c0 = certs[0] as java.security.cert.X509Certificate
      var subj = "-"
      try { subj = c0.subjectX500Principal.name.take(160) } catch(_: Throwable) {}
      var iss = "-"
      try { iss = c0.issuerX500Principal.name.take(160) } catch(_: Throwable) {}
      var sans = "-"
      try {
        val alt = c0.subjectAlternativeNames
        if (alt != null) {
          val sb = StringBuilder()
          for (e in alt) {
            try {
              if (sb.isNotEmpty()) sb.append(";")
              sb.append(e[1].toString())
            } catch(_: Throwable) {}
          }
          sans = sb.toString().take(300)
        }
      } catch(_: Throwable) {}
      return "subject=" + subj + " issuer=" + iss + " sans=" + sans
    } catch(e: Throwable) { return "cert-FAIL " + e::class.java.simpleName }
    finally { try { sock?.close() } catch(_: Throwable) {} }
  }
  fun kick(onLine: (String) -> Unit) {
    try {
      if (done || flying) return
      flying = true
    } catch(_: Throwable) { return }
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val uuid = try { PrimDecoder.pollTexFull() } catch(_: Throwable) { null }
        if (uuid.isNullOrEmpty()) {
          try { flying = false } catch(_: Throwable) {}
          try { onLine("TEX-ESTADO con=0 fetch=sin-uuid") } catch(_: Throwable) {}
          return@launch
        }
        val base = try { CapsManager.caps["GetTexture"] ?: "" } catch(_: Throwable) { "" }
        if (base.isBlank()) {
          try { flying = false } catch(_: Throwable) {}
          try { onLine("TEX-ESTADO con=0 u=" + uuid.take(8) + " fetch=sin-cap-GetTexture") } catch(_: Throwable) {}
          return@launch
        }
        var shape = "len=" + base.length
        try {
          val u = java.net.URL(base)
          var hasQ = "no"
          try { if (u.query != null && u.query.isNotEmpty()) hasQ = "si" } catch(_: Throwable) {}
          shape = "len=" + base.length + " pathLen=" + u.path.length + " query=" + hasQ
        } catch(_: Throwable) {}
        try { onLine("TEX-CAP capHost=" + CapsManager.seedHostOf(base) + " esqCap=" + esqOf(base)) } catch(_: Throwable) {}
        try { onLine("TEX-CAP-FULL-shape " + shape) } catch(_: Throwable) {}
        val httpsBase = if (base.startsWith("http://")) base.replaceFirst("http://", "https://") else ""
        val nodash = uuid.replace("-", "")
        val bhttp = if (base.startsWith("https://")) base.replaceFirst("https://", "http://") else base
        val btrim = bhttp.trimEnd('/')
        val client = buildClient()
        val hopLines = mutableListOf<String>()
        var urlH = base + "?texture_id=" + uuid
        var esqH = "http"
        var fbH = ""
        if (httpsBase.isNotEmpty()) {
          urlH = httpsBase + "?texture_id=" + uuid
          esqH = "https"
          fbH = base + "?texture_id=" + uuid
        }
        val rH = runChain(client, urlH, esqH, fbH, "https-query", hopLines, true)
        var certLine = ""
        try {
          val blob = rH.err0 + "|" + rH.fail
          if (blob.contains("SSL") || blob.contains("erif")) {
            var hh = ""
            try { hh = java.net.URL(urlH).host } catch(_: Throwable) {}
            if (hh.isNotEmpty()) certLine = certDiag(hh)
          }
        } catch(_: Throwable) {}
        // HTTP sin firma = AccessDenied esperado (testigo 403); la via real es UDP RequestImage.
        val tries = listOf("path-dash" to btrim + "/" + uuid, "path-nodash" to btrim + "/" + nodash)
        val res4 = mutableListOf<TexRes>()
        for (tt in tries) {
          var rr: TexRes? = null
          try { rr = runChain(client, tt.second, "http", "", tt.first, hopLines, false) } catch(_: Throwable) {}
          if (rr != null) res4.add(rr)
        }
        try { for (h in hopLines) onLine(h) } catch(_: Throwable) {}
        if (rH.fail.isNotEmpty()) {
          try { onLine("TEX-ESTADO con=0 u=" + uuid.take(8) + " fetch-FAIL " + rH.fail + " tag=https-query") } catch(_: Throwable) {}
        } else if (rH.code == 403) {
          try { onLine("TEX-403 intento=https-query code=403 ct=" + rH.ct + " len=" + rH.len + " hex16=" + rH.hex16 + " srv=" + rH.srv + " clen=" + rH.clen + " xh=" + rH.xh + " esq=" + rH.esq + " ini=" + rH.ini + " hops=" + rH.hops + " msg=" + rH.msg + " err0=" + rH.err0) } catch(_: Throwable) {}
        }
        if (certLine.isNotEmpty()) {
          try { onLine("TEX-CERT " + certLine) } catch(_: Throwable) {}
        }
        for (i in res4.indices) {
          val tag = tries[i].first
          val r = res4[i]
          if (r.fail.isNotEmpty()) {
            try { onLine("TEX-ESTADO con=0 u=" + uuid.take(8) + " fetch-FAIL " + r.fail + " tag=" + tag) } catch(_: Throwable) {}
          } else if (r.code == 403) {
            try { onLine("TEX-403 intento=" + tag + " code=403 ct=" + r.ct + " len=" + r.len + " hex16=" + r.hex16 + " srv=" + r.srv + " clen=" + r.clen + " xh=" + r.xh + " esq=" + r.esq + " ini=" + r.ini + " hops=" + r.hops + " msg=" + r.msg + " err0=" + r.err0) } catch(_: Throwable) {}
          } else if (r.code in 200..299) {
            try { done = true } catch(_: Throwable) {}
            try { onLine("TEX-ESTADO con=1 u=" + uuid.take(8) + " bytes=" + r.len + " ct=" + r.ct + " code=" + r.code + " tag=" + tag) } catch(_: Throwable) {}
          } else {
            try { onLine("TEX-ESTADO con=0 u=" + uuid.take(8) + " fetch-HTTP code=" + r.code + " ct=" + r.ct + " len=" + r.len + " tag=" + tag) } catch(_: Throwable) {}
          }
        }
        try {
          val hopMark = hopLines.size
          val vaBase = try { CapsManager.viewerAssetUrl } catch(_: Throwable) { "" }
          if (vaBase.isBlank()) {
            try { onLine("TEX-CAP2 viewerAssetHost=vacio causa=sin-url-en-seed paso=pedir-cap-por-region") } catch(_: Throwable) {}
          } else {
            var vaHost = "?"
            try { vaHost = java.net.URL(vaBase).host } catch(_: Throwable) {}
            try { onLine("TEX-CAP2 viewerAssetHost=" + vaHost) } catch(_: Throwable) {}
            val vaUrl = vaBase.trimEnd('/') + "/?texture_id=" + uuid
            var vaRes: TexRes? = null
            try { vaRes = runChain(client, vaUrl, esqOf(vaUrl), "", "viewer-asset", hopLines, false) } catch(_: Throwable) {}
            try { for (i in hopMark until hopLines.size) onLine(hopLines[i]) } catch(_: Throwable) {}
            if (vaRes != null) {
              if (vaRes.fail.isNotEmpty()) {
                try { onLine("TEX-ESTADO con=0 u=" + uuid.take(8) + " fetch-FAIL " + vaRes.fail + " tag=viewer-asset") } catch(_: Throwable) {}
              } else if (vaRes.code in 200..299) {
                try { done = true } catch(_: Throwable) {}
                try { onLine("TEX-HTTP200 u=" + uuid.take(8) + " bytes=" + vaRes.len + " ct=" + vaRes.ct + " tag=viewer-asset") } catch(_: Throwable) {}
              } else {
                try { onLine("TEX-ESTADO con=0 u=" + uuid.take(8) + " fetch-HTTP code=" + vaRes.code + " ct=" + vaRes.ct + " len=" + vaRes.len + " tag=viewer-asset causa=" + (if (vaRes.code == 403) "base-o-firma tag-pendiente" else "ver-TEX-HOP")) } catch(_: Throwable) {}
              }
            }
          }
        } catch(_: Throwable) {}
        var tabla = "TEX-TABLA"
        try {
          for (i in res4.indices) tabla += " " + tries[i].first + "=" + res4[i].code + "/" + res4[i].ct + "/" + res4[i].len
        } catch(_: Throwable) {}
        try { onLine(tabla) } catch(_: Throwable) {}
        try { flying = false } catch(_: Throwable) {}
      } catch(_: Throwable) {
        try { flying = false } catch(_: Throwable) {}
      }
    }
  }
}
