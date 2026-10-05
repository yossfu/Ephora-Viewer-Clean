package com.lumiyaviewer.lumiya.slproto.asset

import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Where a texture request is in its life cycle.
 *
 * `PENDING` covers both "asked the grid" and "bytes arrived, not decoded yet":
 * from the caller's point of view both mean "do not ask again". Only `READY`
 * means there are pixels to upload, and only `FAILED` needs a [TextureAssetCache.retry].
 */
enum class TextureAssetState { UNKNOWN, PENDING, READY, FAILED }

/**
 * A texture asset as it came off the grid: the raw bytes of the codestream and
 * the discard level they were requested at.
 *
 * The bytes are **not** decoded here — JPEG2000 decoding is its own layer — and
 * nothing in this class touches the renderer. The `discardLevel` is part of the
 * asset's identity because the wire format can be decoded at a reduced
 * resolution, so the same UUID at two discard levels is two different images.
 */
class TextureAsset(
    val textureId: String,
    val bytes: ByteArray,
    val discardLevel: Int = 0,
    /** Where the bytes came from, for the report: the capability that served them. */
    val source: String = ""
) {
    val size: Int get() = bytes.size

    override fun toString(): String =
        "TextureAsset(" + textureId + ", " + bytes.size + " bytes, discard " + discardLevel +
            (if (source.isEmpty()) "" else ", " + source) + ")"
}

/** What a provider finished since the last drain. */
sealed class TextureAssetResult {

    class Ready(val asset: TextureAsset) : TextureAssetResult()

    class Failed(val textureId: String, val discardLevel: Int, val reason: String) :
        TextureAssetResult()
}

/**
 * The cache's own books: what it was asked, what it decided, what it holds.
 *
 * They live in one object so the HUD, the saved log and the tests all read the
 * same numbers. Only *cache* facts are here — the size of what it is holding,
 * how many times it answered "already asked", how many textures the grid
 * refused. The stages that run *outside* the cache have their own books, and
 * the report prints all of them side by side rather than merging them:
 * [TextureTransportStats] for the wire, `TexturePipelineStats` for decode and
 * upload. That way "4 assets fallidos" and "2 descargas fallidas" can never turn
 * into the same number.
 */
class TextureAssetStats {

    /** Different UUIDs/discard levels the cache was asked for (the misses). */
    var requested = 0
    var downloaded = 0
    var cacheHits = 0
    var cacheMisses = 0
    /** Textures the grid refused; see [TextureAssetCache.fail]. */
    var failed = 0
    var retried = 0
    var bytesDownloaded = 0L

    fun reset() {
        requested = 0
        downloaded = 0
        cacheHits = 0
        cacheMisses = 0
        failed = 0
        retried = 0
        bytesDownloaded = 0L
    }

    fun summary(): String = "solicitadas " + requested + "  ·  descargadas " + downloaded +
        " (" + bytesDownloaded + " bytes)" +
        "  ·  cache hit " + cacheHits + " / miss " + cacheMisses +
        "  ·  fallidas " + failed + "  ·  reintentos " + retried
}

/**
 * What the *transport* stage has done: the counters of [TextureAssetProvider].
 *
 * They live in their own object, and not in [TextureAssetStats], because they
 * answer a different question. [TextureAssetStats] is about the cache ("how
 * many textures are known, how many were refused by the grid"), while this is
 * about the wire ("how many requests left, how many came back, how long they
 * took"). Merging them would make "un asset fallido" and "una descarga fallida"
 * the same number, and they are not: an asset can fail before any request is
 * made, and a request can be cancelled without any asset failing.
 *
 * Every field is written from the provider's worker threads and read from the
 * render thread when the report is built, so the counters are atomics and the
 * readers get a snapshot rather than a torn value.
 */
class TextureTransportStats {

    private val requestsField = AtomicInteger()
    private val inFlightField = AtomicInteger()
    private val responsesField = AtomicInteger()
    private val failuresField = AtomicInteger()
    private val cancelledField = AtomicInteger()
    private val bytesField = AtomicLong()
    private val millisField = AtomicLong()
    private val lastMillisField = AtomicLong()
    private val maxMillisField = AtomicLong()
    private val capabilityWaitsField = AtomicInteger()
    private val httpErrorsField = AtomicInteger()
    private val transportExceptionsField = AtomicInteger()
    private val emptyResponsesField = AtomicInteger()
    private val capabilityMissingField = AtomicInteger()
    private val notReadyField = AtomicInteger()
    private val unclassifiedField = AtomicInteger()
    private val proxiedFailuresField = AtomicInteger()
    private val retriesField = AtomicInteger()

    /** Requests that left for the grid (a repeat of the same UUID is not one). */
    val requests: Int get() = requestsField.get()

    /** Requests handed over and not answered yet. */
    val inFlight: Int get() = inFlightField.get()

    /** Answers that arrived, successful or not. */
    val responses: Int get() = responsesField.get()

    /** Answers that were an error rather than bytes. */
    val failures: Int get() = failuresField.get()

    /**
     * Answers that actually carried bytes.
     *
     * It exists so the report can say `respuestas recibidas: 649 (con bytes 0 /
     * con error 649)` instead of leaving the reader to subtract two numbers, and
     * because "the callback finished" and "we got a texture" are the two things a
     * single counter must never be allowed to mean at once.
     */
    val successfulResponses: Int get() = responsesField.get() - failuresField.get()

    /** Failures where the endpoint answered and the answer was not 2xx. */
    val httpErrors: Int get() = httpErrorsField.get()

    /** Failures where no response was ever read: DNS, connect, TLS, timeout, a refused socket. */
    val transportExceptions: Int get() = transportExceptionsField.get()

    /** Failures where the endpoint answered 2xx with zero bytes. */
    val emptyResponses: Int get() = emptyResponsesField.get()

    /** Failures where the session's seed never offered this capability at all. */
    val capabilityMissing: Int get() = capabilityMissingField.get()

    /** Failures where the capability exists and never became usable. */
    val notReady: Int get() = notReadyField.get()

    /** Failures the transport could not classify. */
    val unclassified: Int get() = unclassifiedField.get()

    /**
     * Failures whose evidence names a proxy: a `Via` header, a `Server` that is a
     * proxy, or a body that says "Proxy Error".
     *
     * A subset of [httpErrors], and the one number that separates "the grid
     * refused us" from "something between the device and the grid refused us".
     */
    val proxiedFailures: Int get() = proxiedFailuresField.get()

    /** The first failure, kept because the first one is the honest one. */
    @Volatile
    var firstFailure: TextureFetchFailure? = null
        private set

    /** The texture whose fetch produced [firstFailure]. */
    @Volatile
    var firstFailureTextureId: String = ""
        private set

    /** The most recent failure, with everything that was observed for it. */
    @Volatile
    var lastFailure: TextureFetchFailure? = null
        private set

    @Volatile
    var lastFailureTextureId: String = ""
        private set

    /** Retries performed after transient transport/HTTP failures. */
    val retries: Int get() = retriesField.get()

    /** Requests the caller withdrew before they were answered. */
    val cancelled: Int get() = cancelledField.get()

    /** Bytes actually received (only successful answers). */
    val bytes: Long get() = bytesField.get()

    /** Total time spent inside [TextureTransport.fetch], in milliseconds. */
    val millis: Long get() = millisField.get()

    /** Duration of the last completed exchange. */
    val lastMillis: Long get() = lastMillisField.get()

    /** Duration of the slowest exchange so far. */
    val maxMillis: Long get() = maxMillisField.get()

    /**
     * How many times a worker found the endpoint not ready and waited instead of
     * failing. Non-zero is normal during login; a large number means the grid
     * never offered `GetTexture`.
     */
    val capabilityWaits: Int get() = capabilityWaitsField.get()

    val averageMillis: Double
        get() = if (responses <= 0) 0.0 else millis.toDouble() / responses.toDouble()

    fun noteRequest() {
        requestsField.incrementAndGet()
        inFlightField.incrementAndGet()
    }

    fun noteRetry() {
        retriesField.incrementAndGet()
    }

    fun noteResponse(bytes: Int, elapsedMillis: Long) {
        responsesField.incrementAndGet()
        inFlightField.decrementAndGet()
        bytesField.addAndGet(bytes.toLong())
        millisField.addAndGet(elapsedMillis)
        lastMillisField.set(elapsedMillis)
        maxMillisField.updateAndGet { current -> if (elapsedMillis > current) elapsedMillis else current }
    }

    /**
     * One answer that was not bytes.
     *
     * The classified failure is what separates "the grid said no" from "nobody
     * answered" from "it answered with nothing", which is the difference between
     * four different fixes — so it is counted per kind as well as in the total,
     * and the first and last ones are kept verbatim (sanitized) for the report.
     *
     * `failure` is null only for a transport that does not classify anything; the
     * answer is then counted as [unclassified] rather than dropped, so the total
     * always matches [responses] and no failure can be missing from the report.
     */
    fun noteFailure(failure: TextureFetchFailure?, textureId: String, elapsedMillis: Long) {
        responsesField.incrementAndGet()
        inFlightField.decrementAndGet()
        failuresField.incrementAndGet()
        when (failure?.kind) {
            TextureFailureKind.HTTP_ERROR -> httpErrorsField.incrementAndGet()
            TextureFailureKind.TRANSPORT_EXCEPTION -> transportExceptionsField.incrementAndGet()
            TextureFailureKind.EMPTY_BODY -> emptyResponsesField.incrementAndGet()
            TextureFailureKind.CAPABILITY_MISSING -> capabilityMissingField.incrementAndGet()
            TextureFailureKind.NOT_READY -> notReadyField.incrementAndGet()
            else -> unclassifiedField.incrementAndGet()
        }
        if (failure != null && failure.looksProxied) {
            proxiedFailuresField.incrementAndGet()
        }
        if (firstFailure == null) {
            firstFailure = failure
            firstFailureTextureId = textureId
        }
        lastFailure = failure
        lastFailureTextureId = textureId
        millisField.addAndGet(elapsedMillis)
        lastMillisField.set(elapsedMillis)
        maxMillisField.updateAndGet { current -> if (elapsedMillis > current) elapsedMillis else current }
    }

    /**
     * A request that ended without an answer: it was withdrawn. `inFlight` is
     * only decremented when the request had actually been counted as in flight.
     */
    fun noteCancel(countedInFlight: Boolean) {
        cancelledField.incrementAndGet()
        if (countedInFlight) {
            inFlightField.decrementAndGet()
        }
    }

    fun noteCapabilityWait() {
        capabilityWaitsField.incrementAndGet()
    }

    fun reset() {
        requestsField.set(0)
        inFlightField.set(0)
        responsesField.set(0)
        failuresField.set(0)
        cancelledField.set(0)
        bytesField.set(0L)
        millisField.set(0L)
        lastMillisField.set(0L)
        maxMillisField.set(0L)
        capabilityWaitsField.set(0)
        httpErrorsField.set(0)
        transportExceptionsField.set(0)
        emptyResponsesField.set(0)
        capabilityMissingField.set(0)
        notReadyField.set(0)
        unclassifiedField.set(0)
        proxiedFailuresField.set(0)
        retriesField.set(0)
        firstFailure = null
        firstFailureTextureId = ""
        lastFailure = null
        lastFailureTextureId = ""
    }

    /**
     * The failures broken down by what actually went wrong, for the report.
     *
     * Only the kinds that happened are printed, and the total is spelled out, so
     * the line can never disagree with [failures]: every classified kind plus
     * [unclassified] adds up to it by construction.
     */
    fun failureSummary(): String {
        val parts = ArrayList<String>(6)
        if (httpErrors > 0) {
            parts.add("error HTTP " + httpErrors)
        }
        if (transportExceptions > 0) {
            parts.add("excepcion de transporte " + transportExceptions)
        }
        if (emptyResponses > 0) {
            parts.add("respuesta vacia " + emptyResponses)
        }
        if (capabilityMissing > 0) {
            parts.add("capability ausente " + capabilityMissing)
        }
        if (notReady > 0) {
            parts.add("endpoint no disponible " + notReady)
        }
        if (unclassified > 0) {
            parts.add("sin clasificar " + unclassified)
        }
        if (parts.isEmpty()) {
            return "sin errores"
        }
        return parts.joinToString("  ·  ") + "  ·  total " + failures
    }

    fun summary(): String = "peticiones " + requests + "  ·  en vuelo " + inFlight +
        "  ·  respuestas " + responses + "  ·  errores " + failures +
        "  ·  reintentos " + retries +
        "  ·  canceladas " + cancelled + "  ·  " + bytes + " bytes  ·  " +
        String.format(Locale.US, "%.0f ms/%d", averageMillis, maxMillis) +
        " (ultima " + lastMillis + " ms, total " + millis + " ms)" +
        (if (capabilityWaits > 0) "  ·  esperas de capability " + capabilityWaits else "")
}

/**
 * The seam between "a texture is needed" and "here are its bytes".
 *
 * It is deliberately the *only* thing that talks to the grid about textures, so
 * the renderer never sees HTTP and the HTTP never sees Filament. Fase 2.13a
 * shipped [FakeTextureAssetProvider] (which does nothing on its own, so the
 * cache could be exercised end to end without a network); fase 2.13b adds the
 * real one, [GetTextureAssetProvider], which fetches the codestream off the
 * `GetTexture` capability on its own worker threads.
 *
 * [request] and [cancel] may be called from the render thread and must return
 * immediately; [drainCompleted] is the render-thread hand-off; the blocking work
 * belongs to whatever threads the implementation starts.
 */
interface TextureAssetProvider {

    /**
     * False while nothing can be served at all (the capability is not resolved
     * yet). A request made in this state is not an error and must not be
     * recorded as one: the pipeline does not even ask, and the texture stays
     * fetchable for when the grid is ready.
     */
    val isReady: Boolean get() = true

    /** Requests handed over and not answered yet, for the report. */
    val inFlight: Int get() = 0

    /** What the wire stage has actually done; see [TextureTransportStats]. */
    val transportStats: TextureTransportStats get() = TextureTransportStats()

    /** Asks for a texture. Non-blocking and idempotent; never throws. */
    fun request(textureId: String, discardLevel: Int = 0)

    /** Forgets a request that is no longer wanted (left the view). */
    fun cancel(textureId: String)

    /** Takes what finished since the last call. Called from the render thread. */
    fun drainCompleted(): List<TextureAssetResult>

    /**
     * Extra lines for the report about the *rest* of the session's HTTP path, or
     * "" when a provider has nothing to add.
     *
     * It is here, on the provider, because only the provider knows what its
     * transport talks to — and the comparison it enables (this capability against
     * the others sharing its HTTP path) is the one that decides whether "every
     * texture failed" is a texture problem at all.
     */
    fun httpDiagnostics(): String = ""

    /**
     * Stops every background thread this provider started. Called when the
     * scene is torn down. Must not block the render thread for long.
     */
    fun shutdown() {}
}
