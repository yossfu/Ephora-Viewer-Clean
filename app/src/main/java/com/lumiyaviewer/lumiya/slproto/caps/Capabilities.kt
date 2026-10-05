package com.lumiyaviewer.lumiya.slproto.caps

import com.lumiyaviewer.lumiya.slproto.llsd.LLSDParser
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDWriter
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * One capability call that did not produce a body, kept as **facts**.
 *
 * This class deliberately does not classify the failure. It records what the
 * transport saw — was the capability even offered, did a response arrive, which
 * status, which content type, how many bytes, which exception, which `Server` and
 * `Via` headers, a short sanitized excerpt — and the layer that knows what the
 * failure *means* for its own job does the classification. That is what lets the
 * texture pipeline say "this was an HTTP error, not an empty body, not a dead
 * connection" without the session's capability code having to know anything about
 * textures.
 *
 * Everything text-shaped in here has been through [HttpText.sanitize]: no full
 * URLs, no `token=`/`session_id=` pairs, no cookies. [endpoint] is a host and a
 * port, never a path or a query.
 */
class CapabilityCallFailure(
    /** The capability's name, e.g. `GetTexture`. */
    val capability: String,
    /** True when the seed's map has no URL for it at all. */
    val notOffered: Boolean,
    /** True when the endpoint answered (then [status] is meaningful). */
    val answered: Boolean,
    /** The HTTP status, or 0 when no response was read. */
    val status: Int,
    val contentType: String,
    /** Bytes of the response body, 0 when there was none. */
    val size: Int,
    /** The exception's class name, or "" when the failure was not an exception. */
    val exception: String,
    /** The exception's message, sanitized. */
    val exceptionMessage: String,
    /** Host and port of the endpoint, or "" when there was no endpoint. */
    val endpoint: String,
    /** The `Server` response header, sanitized. */
    val server: String,
    /** The `Via` response header, sanitized. */
    val via: String,
    /** A short, sanitized excerpt of the body or of the failure text. */
    val detail: String,
    /** True when the evidence points at an HTTP proxy rather than at the grid. */
    val looksProxied: Boolean,
    /**
     * The HTTP method the call used. Not always POST: the asset CDN
     * (`GetTexture` in its modern form) is asked with a `GET` whose query carries
     * the texture id, and a diagnostic that printed "POST" for a 403 caused by
     * asking the CDN the wrong way would send the reader down the wrong path.
     */
    val method: String = "POST"
) {

    /** The one-line form the reports print. */
    fun describe(): String {
        val builder = StringBuilder(240)
        builder.append(capability).append(' ').append(method)
        if (endpoint.isNotEmpty()) {
            builder.append(" · ").append(endpoint)
        }
        when {
            notOffered -> builder.append(" · no la ofrece la capability semilla")
            answered -> {
                builder.append(" · HTTP ").append(status)
                if (contentType.isNotEmpty()) {
                    builder.append(" · Content-Type ").append(contentType)
                }
                builder.append(" · ").append(size).append(" B")
            }
            else -> builder.append(" · sin respuesta")
        }
        if (server.isNotEmpty()) {
            builder.append(" · Server ").append(server)
        }
        if (via.isNotEmpty()) {
            builder.append(" · Via ").append(via)
        }
        if (exception.isNotEmpty()) {
            builder.append(" · ").append(exception)
            if (exceptionMessage.isNotEmpty()) {
                builder.append(": ").append(exceptionMessage)
            }
        }
        if (detail.isNotEmpty()) {
            builder.append(" · \"").append(detail).append('"')
        }
        if (looksProxied) {
            builder.append(" · PARECE UN PROXY")
        }
        return builder.toString()
    }

    override fun toString(): String = describe()

    companion object {

        /** The capability is simply not in the seed's map. */
        fun notOffered(capability: String): CapabilityCallFailure = CapabilityCallFailure(
            capability = capability,
            notOffered = true,
            answered = false,
            status = 0,
            contentType = "",
            size = 0,
            exception = "",
            exceptionMessage = "",
            endpoint = "",
            server = "",
            via = "",
            detail = "",
            looksProxied = false
        )

        /** Nothing was read: the failure happened in the connection itself. */
        fun exception(capability: String, endpoint: String, error: Throwable, method: String = "POST"): CapabilityCallFailure =
            CapabilityCallFailure(
                capability = capability,
                notOffered = false,
                answered = false,
                status = 0,
                contentType = "",
                size = 0,
                exception = error.javaClass.simpleName,
                exceptionMessage = HttpText.sanitize(error.message ?: ""),
                endpoint = endpoint,
                server = "",
                via = "",
                detail = HttpText.sanitize(error.message ?: error.javaClass.simpleName),
                looksProxied = false,
                method = method
            )

        /** A response arrived and it was not what we asked for. */
        fun response(
            capability: String,
            endpoint: String,
            status: Int,
            contentType: String,
            size: Int,
            server: String,
            via: String,
            detail: String,
            method: String = "POST"
        ): CapabilityCallFailure = CapabilityCallFailure(
            capability = capability,
            notOffered = false,
            answered = true,
            status = status,
            contentType = contentType,
            size = size,
            exception = "",
            exceptionMessage = "",
            endpoint = endpoint,
            server = HttpText.sanitize(server, 60),
            via = HttpText.sanitize(via, 60),
            detail = HttpText.sanitize(detail, 200),
            looksProxied = HttpText.looksProxied(server, via, detail),
            method = method
        )

        /** A response arrived, and our own reading of it failed. */
        fun unreadable(
            capability: String,
            endpoint: String,
            status: Int,
            contentType: String,
            size: Int,
            error: Throwable,
            method: String = "POST"
        ): CapabilityCallFailure = CapabilityCallFailure(
            capability = capability,
            notOffered = false,
            answered = true,
            status = status,
            contentType = contentType,
            size = size,
            exception = error.javaClass.simpleName,
            exceptionMessage = HttpText.sanitize(error.message ?: ""),
            endpoint = endpoint,
            server = "",
            via = "",
            detail = HttpText.sanitize("cuerpo ilegible: " + (error.message ?: error.javaClass.simpleName)),
            looksProxied = false,
            method = method
        )
    }
}

/**
 * The text rules for anything network-shaped that ends up in a report.
 *
 * A device log is copied into chats, issues and bug reports, so the request URLs
 * and the credentials in them must not be in it. The rules are deliberately
 * blunt — over-redacting a diagnostic costs a little context, leaking a session
 * token costs an account:
 *
 *  * any `http(s)://…` becomes `[url]` (a capability URL carries a session
 *    capability *in its path*, and the path is the credential);
 *  * `token=…`, `session_id=…`, `auth=…`, `cookie=…`, `password=…`, `key=…`
 *    pairs become `[redacted]`;
 *  * `Cookie: …` lines become `[cookie]` and `Authorization: …` lines become
 *    `[auth]`;
 *  * whitespace collapses, and the result is cut to a fixed length.
 *
 * `internal` rather than file-private on purpose: it is the rule that decides
 * what may be printed, so the check harness asserts it against the exact strings
 * that must not survive (a capability URL, a `session_id`, a `Cookie` line)
 * instead of trusting it.
 */
internal object HttpText {

    private val URL_PATTERN = Regex("""https?://[^\s"'<>\\]+""")
    private val CREDENTIAL_PATTERN =
        Regex("""(?i)\b(token|session_?id|session|auth|auth_?key|api_?key|access_?token|password|passwd|secret|key|cookie)=([^\s&"'<>]+)""")
    private val COOKIE_PATTERN = Regex("""(?i)\bcookie\s*:\s*[^\r\n]+""")
    private val AUTH_HEADER_PATTERN =
        Regex("""(?i)\b(authorization|proxy-authorization|x-auth-token|www-authenticate)\s*:\s*[^\r\n]+""")
    private val WHITESPACE = Regex("""\s+""")
    private val PROXY_PATTERN = Regex("""(?i)proxy|squid|nginx|haproxy|varnish|gateway""")

    /** A short, safe version of [text]: no URLs, no credentials, one line. */
    fun sanitize(text: String, maxLength: Int = 160): String {
        if (text.isEmpty()) {
            return ""
        }
        var out = URL_PATTERN.replace(text, "[url]")
        out = CREDENTIAL_PATTERN.replace(out) { match -> match.groupValues[1] + "=[redacted]" }
        out = COOKIE_PATTERN.replace(out, "[cookie]")
        out = AUTH_HEADER_PATTERN.replace(out, "[auth]")
        out = WHITESPACE.replace(out, " ").trim()
        return if (out.length > maxLength) out.take(maxLength) + "..." else out
    }

    /** `host:port`, and nothing else. Never the path, never the query. */
    fun hostOf(target: String): String = try {
        val url = URL(target)
        val host = url.host ?: ""
        if (host.isEmpty()) "" else if (url.port > 0) host + ":" + url.port else host
    } catch (error: Throwable) {
        "<url invalida>"
    }

    /**
     * Whether the response identifies a proxy in front of the grid.
     *
     * This is the difference between "the grid said 502" and "something between
     * the device and the grid said 502", and those two have nothing in common
     * except the number.
     */
    fun looksProxied(server: String, via: String, detail: String): Boolean =
        via.isNotEmpty() || PROXY_PATTERN.containsMatchIn(server) || PROXY_PATTERN.containsMatchIn(detail)
}

/**
 * The grid's capability system. Everything that is not UDP — inventory,
 * textures, group info, IM — is an HTTP endpoint that the seed capability hands
 * out at login time.
 *
 * The seed is itself an LLSD POST endpoint on the simulator host: you send it
 * the list of capabilities you want (that is what libopenmetaverse and the
 * official viewer do) and get back a `name -> https://...` map.
 *
 * Three details that a naive `HttpURLConnection` POST gets wrong, and which
 * silently produced an empty capability map before:
 *
 *  * redirects. Auto-following a 302 turns the POST into a GET, and the seed
 *    endpoint only answers POSTs, so it replies with an error document. We
 *    follow redirects by hand and keep the method.
 *  * content encoding. Responses can be gzipped and some are served with a
 *    UTF-8 BOM; both make an `startsWith("<")` test fail and send the body to
 *    the (wrong) LLSD notation parser.
 *  * visibility. When the map comes back empty, the log now says exactly what
 *    the server sent (status, content type, first bytes) instead of guessing.
 */
class Capabilities {

    /** The map as it is being built, on the session thread only. */
    private val urls = LinkedHashMap<String, String>()

    /**
     * The resolved map as other threads see it.
     *
     * Textures are fetched by the provider's own worker threads, so `url`/`isReady`
     * are read off the session thread while `resolve` may still be rebuilding the
     * map. Publishing a fresh immutable copy after each successful resolution is
     * what makes those reads well defined, instead of a read of a `HashMap` that
     * another thread is writing.
     */
    @Volatile
    private var published: Map<String, String> = emptyMap()

    private var seed: String = ""

    @Volatile
    var lastError: String = ""
        private set

    /**
     * The last call that failed, whichever it was, in structured form.
     *
     * [lastError] is the same thing as a sentence, and it is what the log has
     * always printed; this is the one a report can count, classify and sanitize.
     */
    @Volatile
    var lastCallFailure: CapabilityCallFailure? = null
        private set

    /** The last failure of each capability, for reports that compare them. */
    private val failures = ConcurrentHashMap<String, CapabilityCallFailure>()

    /** How many times each capability has failed. Diagnostics only, so a lock is fine. */
    private val failureCounts = HashMap<String, Int>()
    private val countsLock = Any()

    /**
     * The last failure of one capability, or null when it has not failed.
     *
     * This is what makes a cross-endpoint question answerable from one log: when
     * every texture fetch fails, the report prints `GetTexture`'s last failure
     * next to `EventQueueGet`'s, and whether they are the same kind of failure —
     * same status, same proxy, same everything — is then a matter of reading two
     * lines instead of comparing two runs.
     */
    fun lastFailureOf(name: String): CapabilityCallFailure? = failures[name]

    /** How many times one capability has failed since login. */
    fun failureCountOf(name: String): Int = synchronized(countsLock) { failureCounts[name] ?: 0 }

    private fun noteCallFailure(failure: CapabilityCallFailure) {
        lastCallFailure = failure
        failures[failure.capability] = failure
        synchronized(countsLock) {
            failureCounts[failure.capability] = (failureCounts[failure.capability] ?: 0) + 1
        }
    }

    private fun noteCallSuccess(name: String) {
        // A capability that works is not a failure, even if it failed before: the
        // report's question is "why is it failing *now*".
        failures.remove(name)
    }

    val isReady: Boolean
        get() = published.isNotEmpty()

    fun url(name: String): String? = published[name]

    fun names(): List<String> = published.keys.toList()

    /**
     * The endpoint of a resolved capability as `host:port`, or "".
     *
     * Only the reports use it, and only the host: a capability URL carries a
     * session capability in its path, so the path is a credential and never goes
     * into a log. The host and port are what answer "is the endpoint even the
     * right machine", which is a question the texture report has to be able to
     * answer.
     */
    fun endpointHost(name: String): String {
        val target = published[name] ?: return ""
        return HttpText.hostOf(target)
    }

    /** Resolves the seed capability into the full map of endpoints. */
    fun resolve(seedUrl: String): Boolean {
        seed = seedUrl
        urls.clear()
        published = emptyMap()
        lastError = ""
        if (seedUrl.isEmpty()) {
            lastError = "El login no devolvio seed_capability"
            noteCallFailure(CapabilityCallFailure.notOffered(SEED_NAME))
            return false
        }
        var attemptError = ""
        for (attempt in 1..SEED_ATTEMPTS) {
            if (attempt > 1) {
                try {
                    Thread.sleep(SEED_RETRY_MILLIS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (resolveOnce(seedUrl)) {
                return true
            }
            attemptError = lastError
        }
        lastError = attemptError
        return false
    }

    private fun resolveOnce(seedUrl: String): Boolean {
        lastError = ""
        val response = try {
            http("POST", seedUrl, encode(seedRequest()), DEFAULT_TIMEOUT_MILLIS)
        } catch (t: Throwable) {
            lastError = "No se pudo contactar con la capability semilla (" +
                describe(seedUrl) + "): " + (t.message ?: t.javaClass.simpleName)
            noteCallFailure(CapabilityCallFailure.exception(SEED_NAME, HttpText.hostOf(seedUrl), t))
            return false
        }
        if (response.status !in 200..299) {
            lastError = "La capability semilla respondio HTTP " + response.status +
                " (" + describe(seedUrl) + ") " + preview(response)
            noteCallFailure(responseFailure(SEED_NAME, HttpText.hostOf(seedUrl), response, "POST"))
            return false
        }
        val parsed = try {
            LLSDParser.asMap(parseBody(response))
        } catch (t: Throwable) {
            lastError = "Respuesta ilegible de la capability semilla: " +
                (t.message ?: t.javaClass.simpleName) + " " + preview(response)
            noteCallFailure(
                CapabilityCallFailure.unreadable(
                    SEED_NAME, HttpText.hostOf(seedUrl), response.status, response.contentType, response.bytes.size, t
                )
            )
            return false
        }
        for ((key, value) in parsed) {
            val url = LLSDParser.asString(value)
            if (url.startsWith("http")) {
                urls[key] = url
            }
        }
        if (urls.isEmpty()) {
            lastError = "La capability semilla no devolvio ninguna URL (" +
                describe(seedUrl) + ", HTTP " + response.status +
                ", tipo " + response.contentType + ", " +
                response.bytes.size + " bytes) " + preview(response)
            noteCallFailure(
                CapabilityCallFailure.response(
                    capability = SEED_NAME,
                    endpoint = HttpText.hostOf(seedUrl),
                    status = response.status,
                    contentType = response.contentType,
                    size = response.bytes.size,
                    server = response.server,
                    via = response.via,
                    detail = "la semilla no devolvio ninguna URL: " + preview(response)
                )
            )
            return false
        }
        published = HashMap(urls)
        noteCallSuccess(SEED_NAME)
        return true
    }

    /** Invokes a capability with an LLSD body and returns the reply bytes. */
    fun requestBytes(name: String, body: Any?, readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): ByteArray? {
        return requestBody(name, body, readTimeoutMillis)?.bytes
    }

    /**
     * Invokes a capability with an LLSD body and returns the raw response text,
     * or null when the capability is missing or the request fails.
     */
    fun request(name: String, body: Any?, readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): String? {
        val received = requestBody(name, body, readTimeoutMillis) ?: return null
        return String(received.bytes, Charsets.UTF_8)
    }

    /**
     * Invokes a capability and parses the LLSD reply. The notation is sniffed
     * from the bytes, because a simulator is free to answer XML, notation or
     * binary — and the event queue holds the request open for a minute, which
     * is why the caller can raise the read timeout.
     */
    fun requestParsed(name: String, body: Any?, readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): Any? {
        val received = requestBody(name, body, readTimeoutMillis) ?: return null
        return try {
            LLSDParser.parseBytes(received.bytes, received.contentType)
        } catch (t: Throwable) {
            lastError = "Respuesta ilegible de " + name + ": " + (t.message ?: t.javaClass.simpleName)
            noteCallFailure(
                CapabilityCallFailure.unreadable(
                    name, received.endpoint, 200, received.contentType, received.bytes.size, t
                )
            )
            null
        }
    }

    private fun requestBody(name: String, body: Any?, readTimeoutMillis: Int): Body? {
        val target = url(name) ?: run {
            lastError = "La simulacion no ofrece la capability " + name
            noteCallFailure(CapabilityCallFailure.notOffered(name))
            return null
        }
        return call(name, target, "POST", encode(body), readTimeoutMillis)
    }

    /**
     * Fetches an **asset URL**: a plain URL the grid handed us (the asset CDN),
     * not a capability with an LLSD body.
     *
     * The distinction is real and it is the whole texture protocol: a capability
     * is `POST <cap URL>` with an LLSD body, while the CDN is
     * `GET <base>/?texture_id=<uuid>` with no body at all. Sending the first form
     * to the second endpoint is what an asset CDN answers with an HTTP 403.
     *
     * The failure is recorded under [name] with the same facts as a capability
     * call (status, content type, size, `Server`/`Via`, sanitized excerpt), so the
     * texture report compares like with like.
     */
    fun requestAsset(
        name: String,
        target: String,
        method: String = "GET",
        body: Any? = null,
        readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS
    ): ByteArray? = call(name, target, method, body?.let { encode(it) }, readTimeoutMillis)?.bytes

    fun requestRange(
        name: String,
        target: String,
        offset: Long,
        length: Long,
        readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS
    ): RangeBody? = callRange(name, target, offset, length, readTimeoutMillis)

    private fun callRange(
        name: String,
        target: String,
        offset: Long,
        length: Long,
        readTimeoutMillis: Int
    ): RangeBody? {
        val response = try {
            httpRange(target, offset, length, readTimeoutMillis)
        } catch (t: Throwable) {
            lastError = "Error llamando a " + name + ": " + (t.message ?: t.javaClass.simpleName)
            noteCallFailure(CapabilityCallFailure.exception(name, HttpText.hostOf(target), t, "GET"))
            return null
        }
        if (response.status !in 200..299 && response.status != 206) {
            lastError = name + " respondio HTTP " + response.status + " " + preview(response)
            noteCallFailure(responseFailure(name, HttpText.hostOf(target), response, "GET"))
            return null
        }
        val bytes = try {
            decompress(response)
        } catch (t: Throwable) {
            lastError = "Respuesta ilegible de " + name + ": " + (t.message ?: t.javaClass.simpleName)
            noteCallFailure(
                CapabilityCallFailure.unreadable(
                    name, HttpText.hostOf(target), response.status, response.contentType, response.bytes.size, t, "GET"
                )
            )
            return null
        }
        if (bytes.isEmpty()) {
            lastError = name + " devolvio una respuesta valida de 0 bytes"
            noteCallFailure(responseFailure(name, HttpText.hostOf(target), response, "GET"))
            return null
        }
        noteCallSuccess(name)
        return RangeBody(bytes, response.status == 206)
    }

    private fun call(name: String, target: String, method: String, body: ByteArray?, readTimeoutMillis: Int): Body? {
        val response = try {
            http(method, target, body, readTimeoutMillis)
        } catch (t: Throwable) {
            lastError = "Error llamando a " + name + ": " + (t.message ?: t.javaClass.simpleName)
            noteCallFailure(CapabilityCallFailure.exception(name, HttpText.hostOf(target), t, method))
            return null
        }
        if (response.status !in 200..299) {
            lastError = name + " respondio HTTP " + response.status + " " + preview(response)
            noteCallFailure(responseFailure(name, HttpText.hostOf(target), response, method))
            return null
        }
        val bytes = try {
            decompress(response)
        } catch (t: Throwable) {
            lastError = "Respuesta ilegible de " + name + ": " + (t.message ?: t.javaClass.simpleName)
            noteCallFailure(
                CapabilityCallFailure.unreadable(
                    name, HttpText.hostOf(target), response.status, response.contentType, response.bytes.size, t, method
                )
            )
            return null
        }
        noteCallSuccess(name)
        return Body(bytes, response.contentType, HttpText.hostOf(target))
    }

    /** The structured form of a non-2xx answer, with the proxy hints included. */
    private fun responseFailure(name: String, endpoint: String, response: Response, method: String): CapabilityCallFailure =
        CapabilityCallFailure.response(
            capability = name,
            endpoint = endpoint,
            status = response.status,
            contentType = response.contentType,
            size = response.bytes.size,
            server = response.server,
            via = response.via,
            detail = preview(response),
            method = method
        )

    // ------------------------------------------------------------------ HTTP ---

    private class Body(val bytes: ByteArray, val contentType: String, val endpoint: String)

    private class Response(
        val status: Int,
        val contentType: String,
        val contentEncoding: String,
        val bytes: ByteArray,
        /** The `Server` header, which is how a proxy names itself. */
        val server: String = "",
        /** The `Via` header, which is the other way a proxy names itself. */
        val via: String = ""
    )

    private fun encode(body: Any?): ByteArray = LLSDWriter.toXml(body).toByteArray(Charsets.UTF_8)

    private fun parseBody(response: Response): Any? {
        val bytes = decompress(response)
        if (bytes.isEmpty()) {
            throw IllegalStateException("cuerpo vacio")
        }
        return LLSDParser.parseBytes(bytes, response.contentType)
    }

    /** Decompresses / strips a UTF-8 BOM, leaving the bytes of the LLSD body. */
    private fun decompress(response: Response): ByteArray {
        var bytes = response.bytes
        if (response.contentEncoding.contains("gzip", ignoreCase = true) || looksGzipped(bytes)) {
            bytes = gunzip(bytes)
        }
        if (bytes.size > 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            bytes = bytes.copyOfRange(3, bytes.size)
        }
        return bytes
    }

    private fun looksGzipped(bytes: ByteArray): Boolean =
        bytes.size > 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B

    private fun gunzip(bytes: ByteArray): ByteArray {
        val input = GZIPInputStream(bytes.inputStream())
        val output = ByteArrayOutputStream(bytes.size * 4)
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            output.write(buffer, 0, read)
        }
        input.close()
        return output.toByteArray()
    }

    private fun http(method: String, target: String, body: ByteArray?, readTimeoutMillis: Int): Response {
        var current = target
        var hop = 0
        while (true) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 30000
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/llsd+xml")
                    connection.setRequestProperty("Accept", "application/llsd+xml")
                } else {
                    // An asset CDN GET: no body, no LLSD, and a plain Accept. The
                    // `Range` header the reference viewer may add is optional, so
                    // this asks for the whole codestream.
                    connection.setRequestProperty("Accept", "*/*")
                }
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (body != null) {
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (status in REDIRECT_CODES && !location.isNullOrEmpty() && hop < MAX_REDIRECTS) {
                    current = URL(URL(current), location).toString()
                    hop += 1
                    continue
                }
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                return Response(
                    status,
                    connection.getHeaderField("Content-Type") ?: "",
                    connection.getHeaderField("Content-Encoding") ?: "",
                    bytes,
                    connection.getHeaderField("Server") ?: "",
                    connection.getHeaderField("Via") ?: ""
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun httpRange(target: String, offset: Long, length: Long, readTimeoutMillis: Int): Response {
        var current = target
        var hop = 0
        while (true) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 30000
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (offset >= 0 && length > 0) {
                    connection.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1))
                }
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (status in REDIRECT_CODES && !location.isNullOrEmpty() && hop < MAX_REDIRECTS) {
                    current = URL(URL(current), location).toString()
                    hop += 1
                    continue
                }
                val stream = if (status in 200..299 || status == 206) connection.inputStream else connection.errorStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                return Response(
                    status,
                    connection.getHeaderField("Content-Type") ?: "",
                    connection.getHeaderField("Content-Encoding") ?: "",
                    bytes,
                    connection.getHeaderField("Server") ?: "",
                    connection.getHeaderField("Via") ?: ""
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    // ----------------------------------------------------------- diagnostics ---

    private fun describe(target: String): String {
        val withoutQuery = if (target.contains('?')) target.substringBefore('?') else target
        return if (withoutQuery.length > 90) withoutQuery.take(90) + "..." else withoutQuery
    }

    /**
     * The **shape** of a resolved URL, which is the part of it that is safe to
     * print: scheme and host, whether it has a path, whether it has a query, and
     * the query's key names — never the values.
     *
     * A capability URL carries its session capability in the *path*, and an asset
     * CDN URL can carry a signature in the *query*, so neither may go in a report.
     * The shape is what answers the question a report has to answer anyway: "is
     * this a capability endpoint, or a bare CDN base URL?" — because a bare base
     * URL is fetched with `GET <base>/?texture_id=…`, and a capability endpoint
     * with `POST` and an LLSD body.
     */
    fun urlShape(name: String): String {
        val target = url(name) ?: return name + ": (no resuelta)"
        val parsed = try {
            URL(target)
        } catch (t: Throwable) {
            return name + ": (URL ilegible)"
        }
        val scheme = parsed.protocol ?: ""
        val host = parsed.host ?: ""
        val port = parsed.port
        val path = parsed.path ?: ""
        val query = parsed.query ?: ""
        val address = scheme + "://" + host + (if (port > 0) ":" + port else "")
        val pathShape = if (path.isEmpty() || path == "/") {
            "sin ruta"
        } else {
            "con ruta (" + path.count { it == '/' } + " segmentos, no se imprime)"
        }
        val queryShape = if (query.isEmpty()) {
            "sin query"
        } else {
            val keys = query.split('&')
                .map { if (it.contains('=')) it.substringBefore('=') else it }
                .filter { it.isNotEmpty() }
                .joinToString(",") { HttpText.sanitize(it, 24) }
            "query con claves: " + keys
        }
        return name + ": " + address + " · " + pathShape + " · " + queryShape
    }

    private fun preview(response: Response): String {
        if (response.bytes.isEmpty()) {
            return "[cuerpo vacio]"
        }
        val raw = response.bytes
        val head = if (looksGzipped(raw)) "[gzip " + raw.size + " bytes]" else {
            String(raw, 0, minOf(raw.size, 240), Charsets.UTF_8)
        }
        // Sanitized on the way out: this text is printed in the log and copied
        // into reports, and a capability URL or a session token must not travel
        // with it.
        return "-> " + HttpText.sanitize(head, 240)
    }

    private fun seedRequest(): List<String> = SEED_CAPABILITIES

    companion object {
        /**
         * The name the seed capability's failures are recorded under. Public
         * because the reports print it next to the capabilities it feeds.
         *
         * The seed is not a capability in the map (it is the thing that produces
         * the map), but it shares the same HTTP path, so its failure has to be
         * printable next to the others: when the seed is what is failing, *every*
         * capability fails, and no per-capability diagnosis can say that.
         */
        const val SEED_NAME = "seed"

        const val USER_AGENT = "EphoraViewer/1.0.0"
        const val MAX_REDIRECTS = 4
        const val DEFAULT_TIMEOUT_MILLIS = 30000
        const val SEED_ATTEMPTS = 3
        const val SEED_RETRY_MILLIS = 1200L
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /**
         * What we ask the seed for. Identical in spirit to libopenmetaverse's
         * list, which is the de-facto reference every third-party viewer sends.
         */
        val SEED_CAPABILITIES = listOf(
            "AgentPreferences",
            "AgentState",
            "AttachmentResources",
            "AvatarPickerSearch",
            "AvatarRenderInfo",
            "CharacterProperties",
            "ChatSessionRequest",
            "CopyInventoryFromNotecard",
            "CreateInventoryCategory",
            "DispatchRegionInfo",
            "EnvironmentSettings",
            "EstateChangeInfo",
            "EventQueueGet",
            "FetchLib2",
            "FetchLibDescendents2",
            "FetchInventory2",
            "FetchInventoryDescendents2",
            "GetDisplayNames",
            "GetMesh",
            "GetMesh2",
            "GetObjectCost",
            "GetObjectPhysicsData",
            "GetTexture",
            "GroupAPIv1",
            "GroupMemberData",
            "HomeLocation",
            "LandResources",
            "MapLayer",
            "ObjectMedia",
            "ObjectMediaNavigate",
            "ParcelPropertiesUpdate",
            "ProductInfoRequest",
            "RegionExperiences",
            "RenderMaterials",
            "RequestTextureDownload",
            "ResourceCostSelected",
            "SearchStatRequest",
            "ServerReleaseNotes",
            "SetDisplayName",
            "SimConsoleAsync",
            "SimulatorFeatures",
            "TextureStats",
            "UntrustedSimulatorMessage",
            "UpdateAgentInformation",
            "UpdateAgentLanguage",
            "UpdateAvatarAppearance",
            "UploadBakedTexture",
            "ViewerAsset",
            "ViewerBenefits",
            "ViewerMetrics",
            "ViewerStartAuction",
            "VoiceAccountProvision"
        )
    }
}

class RangeBody(val bytes: ByteArray, val partial: Boolean)
