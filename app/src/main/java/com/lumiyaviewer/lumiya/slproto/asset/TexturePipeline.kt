package com.lumiyaviewer.lumiya.slproto.asset

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What the pipeline finished for one texture, handed to the caller on the
 * render thread by [TexturePipeline.pump].
 *
 * The pipeline deliberately stops at "there are pixels": the GPU upload is the
 * scene's, so nothing here depends on the renderer.
 */
sealed class TexturePipelineEvent {

    /** Pixels exist; the caller uploads them and rebinds the materials. */
    class Decoded(
        val textureId: String,
        val discardLevel: Int,
        val texture: DecodedTexture,
        /** How long the decode took, so the caller can print the same number. */
        val millis: Long
    ) : TexturePipelineEvent()

    /**
     * The bytes exist but there are no pixels: either the decoder refused them
     * (a broken codestream) or no decoder is linked in. In both cases the scene
     * keeps drawing its fallback colour, and [reason] says which it was.
     */
    class DecodeFailed(
        val textureId: String,
        val discardLevel: Int,
        val reason: String
    ) : TexturePipelineEvent()
}

/**
 * The books of the decode and upload stages, plus the pipeline's own view of
 * what it asked for.
 *
 * These are the counters the user asked to be able to read, and each one is a
 * fact about *this* stage: requests the pipeline issued, results it collected,
 * images decoded, images uploaded. The cache's counters and the transport's
 * counters are separate objects and are printed next to these, never folded
 * into them.
 */
class TexturePipelineStats {

    /** New UUID requests handed to the provider (a cache hit is not one). */
    var requested = 0

    /** Requests not made because the provider is not ready yet (login). */
    var deferred = 0

    /** Requests not made because the debug cap was reached. */
    var capped = 0

    /** Results collected from the provider (ready + failed). */
    var received = 0

    /** Results that were bytes. */
    var ready = 0

    /** Results that were refused by the grid. */
    var failed = 0

    /** Assets handed to a decode worker. */
    var decodeQueued = 0

    /** Images decoded. */
    var decodeOk = 0

    /** Codestreams the decoder refused. */
    var decodeFailed = 0

    /** Images uploaded to the GPU. */
    var uploadOk = 0

    /** Uploads the backend refused. */
    var uploadFailed = 0

    /** Bytes of pixels uploaded. */
    var uploadBytes = 0L

    /** 2.26: descargas en curso canceladas porque su objeto salio de escena. */
    var deadCancelled = 0

    /**
     * Decoded events held back by the per-frame upload budget (recovery 4,
     * método Lumiya: GLSyncLoadQueue MAX_LOADS_PER_FRAME/MAX_SIZE_PER_FRAME).
     * They stay queued in [decodeResults] and are emitted by later frames:
     * nothing is dropped, failed or re-downloaded.
     */
    var uploadsDeferred = 0

    /** Milliseconds spent inside [TextureDecoder.decode]. */
    var decodeMillis = 0L

    /** Milliseconds spent uploading (measured by the caller's upload call). */
    var uploadMillis = 0L

    var lastDecodeMillis = 0L
    var lastUploadMillis = 0L

    val averageDecodeMillis: Double
        get() = if (decodeOk <= 0) 0.0 else decodeMillis.toDouble() / decodeOk.toDouble()

    val averageUploadMillis: Double
        get() = if (uploadOk <= 0) 0.0 else uploadMillis.toDouble() / uploadOk.toDouble()

    fun reset() {
        requested = 0
        deferred = 0
        capped = 0
        received = 0
        ready = 0
        failed = 0
        decodeQueued = 0
        decodeOk = 0
        decodeFailed = 0
        uploadOk = 0
        uploadFailed = 0
        uploadBytes = 0L
        uploadsDeferred = 0
        deadCancelled = 0
        decodeMillis = 0L
        uploadMillis = 0L
        lastDecodeMillis = 0L
        lastUploadMillis = 0L
    }
}

/**
 * The texture pipeline: the one object that owns the whole "a face needs a
 * texture" → "there are pixels in the cache" chain.
 *
 * It is built from three pieces that each know only their own job — the
 * [TextureAssetProvider] (the wire), the [TextureAssetCache] (identity and
 * reuse) and the [TextureDecoder] (JPEG2000) — and it adds the two things none
 * of them should have: *where* the decode runs (its own worker threads, never
 * the render thread) and *what it all cost* (its counters and timings).
 *
 * ## The frame contract
 *
 *  * [request] — render thread, non-blocking: "this face wants this texture".
 *    It consults the cache, and only on a real miss does it ask the provider.
 *  * [pump] — once per frame, render thread, non-blocking: it collects whatever
 *    the provider finished, sends those bytes to the decode queue, collects
 *    whatever the decoders finished, and returns the events. Everything the
 *    pipeline does per frame is bounded by "what completed", never by how much
 *    is outstanding, so a region with thousands of textures costs the same per
 *    frame as a region with none.
 *  * [noteUploaded]/[noteUploadFailed] — the caller reports back the last stage,
 *    so the upload counters live where the GPU call is.
 *
 * ## What happens with no decoder
 *
 * In fase 2.13b there is no JPEG2000 decoder, so [decoder] is
 * [UnavailableTextureDecoder] and every asset that arrives is **kept and
 * waited for** (`decodeQueued` in the report as "esperando decoder"), not
 * failed. When 2.13c links OpenJPEG in, the same cached bytes are decoded
 * without a second download — which is the whole reason the two sub-phases are
 * separable at all.
 */
class TexturePipeline(
    /** The wire stage. Its life cycle is the caller's; [shutdown] only asks. */
    val provider: TextureAssetProvider,
    /** The JPEG2000 stage; swappable so 2.13c adds one line at the call site. */
    var decoder: TextureDecoder = OpenJpegTextureDecoder(),
    /** How many decode threads to run. Decoding is CPU-bound and off-thread. */
    decodeWorkers: Int = DEFAULT_DECODE_WORKERS,
    /**
     * How many runs of this class may be started at once by the provider; 0
     * disables the safety cap entirely. A cap is what keeps a first device run
     * from asking the grid for a whole region's worth of textures at once.
     */
    var maxRequests: Int = 0
) {

    /** Identity and reuse of the codestreams. */
    val cache = TextureAssetCache()

    /** Everything this class itself did; see [TexturePipelineStats]. */
    val stats = TexturePipelineStats()

    /** Requests actually issued, for [maxRequests]. */
    private var issuedRequests = 0

    private val decodeSlots: Int = if (decodeWorkers < 1) 1 else decodeWorkers
    // Recovery safety: JPEG2000 -> RGBA is memory-heavy on Android. Keep only
    // one native decode in flight by default; the render thread stays responsive
    // and decoded CPU buffers are released immediately after GPU upload.
    private val maxDecodeInFlight: Int = decodeSlots

    private val decodeQueue = LinkedBlockingQueue<String>()
    private val decodeQueueLock = Any()

    /** Keys whose bytes are cached and not decoded yet, in arrival order. */
    private val waitingForDecoder = LinkedHashSet<String>()

    /** Keys handed to a decode worker and not collected yet. */
    private val decoding = HashSet<String>()

    /** Decoded images, by key; read only on the render thread. */
    private val decoded = HashMap<String, DecodedTexture>()

    /** Every key the pipeline holds bytes for, in arrival order. */
    private val knownKeys = LinkedHashSet<String>()

    private val decodeResults = ArrayList<DecodeOutcome>()
    private val decodeResultsLock = Any()

    /**
     * The last upload the backend refused, verbatim, for the report. It names
     * the texture so a refusal can be traced back to one face.
     */
    var lastUploadFailure: String = ""
        private set

    @Volatile
    private var running = true

    private val decodeThreadsStarted = java.util.concurrent.atomic.AtomicInteger()
    private var decodeThreads = emptyList<Thread>()

    private class DecodeOutcome(
        val key: String,
        val textureId: String,
        val discardLevel: Int,
        val texture: DecodedTexture?,
        val reason: String,
        val millis: Long
    )

    // ------------------------------------------------------------- requests ---

    /**
     * Asks for one texture if it is not known yet. Returns true when the request
     * actually left for the grid, which is exactly what the caller wants to
     * count ("solicitudes nuevas" vs "ya la teníamos").
     *
     * It never fails a texture: an unready provider means the request is
     * *deferred*, and the caller will ask again next frame (the same face is
     * still there), so nothing is lost and nothing has to be retried by hand.
     */
    fun request(textureId: String, discardLevel: Int = 0): Boolean {
        if (textureId.isEmpty() || !running) {
            return false
        }
        if (!provider.isReady) {
            stats.deferred += 1
            return false
        }
        if (maxRequests > 0 && issuedRequests >= maxRequests) {
            stats.capped += 1
            return false
        }
        if (!cache.markPending(textureId, discardLevel)) {
            return false
        }
        issuedRequests += 1
        stats.requested += 1
        provider.request(textureId, discardLevel)
        return true
    }

    /** The cached codestream, or null. */
    fun asset(textureId: String, discardLevel: Int = 0): TextureAsset? =
        cache.asset(textureId, discardLevel)

    /** The wire layer's lines about the session's shared HTTP path, for the report. */
    fun httpDiagnostics(): String = provider.httpDiagnostics()

    /** The decoded image, when the pipeline has one. */
    fun image(textureId: String, discardLevel: Int = 0): DecodedTexture? =
        decoded[keyOf(textureId, discardLevel)]

    fun isDecoded(textureId: String, discardLevel: Int = 0): Boolean =
        decoded.containsKey(keyOf(textureId, discardLevel))

    /**
     * Re-arms a decode from an already cached codestream without issuing a new
     * network request. This is the path used after the renderer LRU evicts a GPU
     * texture: the bytes can still be local, so the next visible scan only needs
     * to decode them again. Returns true when a decode was actually queued.
     */
    fun ensureCachedDecode(textureId: String, discardLevel: Int = 0): Boolean {
        if (textureId.isEmpty() || !running || !decoder.isAvailable) {
            return false
        }
        val key = keyOf(textureId, discardLevel)
        if (decoded.containsKey(key) || waitingForDecoder.contains(key) || decoding.contains(key)) {
            return false
        }
        if (cache.asset(textureId, discardLevel) == null) {
            return false
        }
        waitingForDecoder.add(key)
        return true
    }

    /** Assets whose bytes are cached and whose pixels are still owed. */
    val waitingForDecode: Int get() = waitingForDecoder.size + decoding.size

    /** Images held in memory. */
    val decodedCount: Int get() = decoded.size

    /**
     * Decoded outcomes collected but not yet handed to the caller because the
     * per-frame upload budget held them back (recovery 4). Leftovers are always
     * [Decoded]: failures are never rationed.
     */
    val pendingUploadCount: Int get() = synchronized(decodeResultsLock) { decodeResults.size }

    /**
     * Releases one decoded CPU image after the scene has uploaded it to the GPU.
     * The codestream remains cached, so a scene rebuild can decode it again without
     * a second HTTP request.
     */
    fun releaseDecoded(textureId: String, discardLevel: Int = 0) {
        decoded.remove(keyOf(textureId, discardLevel))
    }

    /** How much of the decoded pixels are in RAM, for the report. */
    val decodedBytes: Long
        get() {
            var total = 0L
            for (image in decoded.values) {
                total += image.size.toLong()
            }
            return total
        }

    // ---------------------------------------------------------------- frame ---

    /**
     * One frame's work, on the render thread. Collects what finished, queues what
     * can be decoded, collects what was decoded, and returns the events in
     * arrival order.
     *
     * Recovery 4 (método Lumiya, GLSyncLoadQueue): the [Decoded] events are
     * rationed per frame ([maxDecodedPerFrame] images, [maxDecodedBytesPerFrame]
     * bytes of pixels), because each one becomes a GPU upload plus a material
     * rebind on this thread. [DecodeFailed] is always emitted whole: it costs
     * no GPU work and keeps the failure accounting prompt. Whatever does not
     * fit stays queued in arrival order for the next frames.
     */
    fun pump(maxDecodedPerFrame: Int = Int.MAX_VALUE, maxDecodedBytesPerFrame: Long = Long.MAX_VALUE): List<TexturePipelineEvent> {
        val events = ArrayList<TexturePipelineEvent>()
        collectFromProvider()
        dispatchDecodes()
        collectDecodes(events, maxDecodedPerFrame, maxDecodedBytesPerFrame)
        return events
    }

    private fun collectFromProvider() {
        for (result in provider.drainCompleted()) {
            when (result) {
                is TextureAssetResult.Ready -> {
                    stats.received += 1
                    if (cache.put(result.asset)) {
                        stats.ready += 1
                    }
                    val key = keyOf(result.asset.textureId, result.asset.discardLevel)
                    knownKeys.add(key)
                    if (!decoded.containsKey(key)) {
                        waitingForDecoder.add(key)
                    }
                }
                is TextureAssetResult.Failed -> {
                    stats.received += 1
                    stats.failed += 1
                    cache.fail(result.textureId, result.discardLevel, result.reason)
                }
            }
        }
        // 2.26: los codestreams ya no se retienen para siempre; lo que supere
        // el tope sale por LRU (las claves en decode se protegen). Solo hilo
        // de render, como todo pump().
        cache.evictExcess(decoding.toSet() + waitingForDecoder)
    }

    /**
     * 2.26: cancela las descargas en curso que ningun objeto vivo necesita.
     * [liveIds] son UUIDs de textura (sin nivel de descarte). Solo hilo de
     * render. Devuelve cuantas cancelo.
     */
    fun cancelDead(liveIds: Set<String>): Int {
        var cancelled = 0
        for (key in cache.pendingKeys()) {
            if (liveIds.contains(textureIdOf(key))) {
                continue
            }
            if (decoding.contains(key) || waitingForDecoder.contains(key)) {
                continue
            }
            if (cache.unpend(key)) {
                provider.cancel(textureIdOf(key))
                stats.deadCancelled += 1
                cancelled += 1
            }
        }
        return cancelled
    }

    /**
     * Sends cached-but-undecoded assets to the decode workers, while there is
     * room.
     *
     * With no decoder linked in, this is where the pipeline stops: the assets
     * stay in [waitingForDecoder] (and are counted as "esperando decoder"), so
     * nothing is lost when OpenJPEG arrives — the very next [pump] after the
     * decoder is set drains the whole backlog without re-downloading anything.
     */
    private fun dispatchDecodes() {
        if (!decoder.isAvailable || waitingForDecoder.isEmpty()) {
            return
        }
        while (running && decoding.size < maxDecodeInFlight && waitingForDecoder.isNotEmpty()) {
            val key = waitingForDecoder.iterator().next()
            waitingForDecoder.remove(key)
            val asset = cache.asset(textureIdOf(key), discardLevelOf(key)) ?: continue
            decoding.add(key)
            stats.decodeQueued += 1
            startDecodeThreadsIfNeeded()
            decodeQueue.offer(key)
        }
    }

    private fun collectDecodes(events: MutableList<TexturePipelineEvent>, maxDecoded: Int, maxBytes: Long) {
        val finished = synchronized(decodeResultsLock) {
            if (decodeResults.isEmpty()) {
                return
            }
            var decodedTaken = 0
            var bytesTaken = 0L
            var cut = 0
            for (outcome in decodeResults) {
                val image = outcome.texture
                if (image == null) {
                    cut += 1
                } else if (decodedTaken < maxDecoded &&
                    (bytesTaken + image.size <= maxBytes || decodedTaken == 0)
                ) {
                    decodedTaken += 1
                    bytesTaken += image.size
                    cut += 1
                } else {
                    break
                }
            }
            val head = ArrayList<DecodeOutcome>(decodeResults.subList(0, cut))
            decodeResults.subList(0, cut).clear()
            stats.uploadsDeferred += decodeResults.size
            head
        }
        for (outcome in finished) {
            decoding.remove(outcome.key)
            val image = outcome.texture
            if (image == null) {
                if (outcome.reason == ASSET_EVICTED) {
                    // 2.26: el LRU expulso los bytes mientras el worker los
                    // pedia. No es fallo del codestream: vuelve a la cola y se
                    // decodifica cuando los bytes regresen (o se re-descarguen).
                    if (cache.asset(outcome.textureId, outcome.discardLevel) == null &&
                        !waitingForDecoder.contains(outcome.key)
                    ) {
                        waitingForDecoder.add(outcome.key)
                    }
                    continue
                }
                stats.decodeFailed += 1
                events.add(TexturePipelineEvent.DecodeFailed(
                    outcome.textureId, outcome.discardLevel, outcome.reason
                ))
                // A codestream the decoder refused is not queued again: it would
                // fail identically every time. The asset stays cached so the
                // fallback colour can be explained by "decoded refused".
            } else {
                stats.decodeOk += 1
                stats.decodeMillis += outcome.millis
                stats.lastDecodeMillis = outcome.millis
                decoded[outcome.key] = image
                events.add(TexturePipelineEvent.Decoded(
                    outcome.textureId, outcome.discardLevel, image, outcome.millis
                ))
            }
        }
    }

    // -------------------------------------------------------------- uploads ---

    /** The caller uploaded the pixels of this texture (see [pump]). */
    fun noteUploaded(textureId: String, discardLevel: Int, bytes: Int, millis: Long) {
        stats.uploadOk += 1
        stats.uploadBytes += bytes.toLong()
        stats.uploadMillis += millis
        stats.lastUploadMillis = millis
    }

    /** The backend refused to create the texture; the fallback stays. */
    fun noteUploadFailed(textureId: String, discardLevel: Int, reason: String) {
        stats.uploadFailed += 1
        lastUploadFailure = textureId + "@" + discardLevel + ": " + reason
    }

    // ----------------------------------------------------------- life cycle ---

    /**
     * Stops the decode workers. Safe to call twice.
     *
     * It deliberately does **not** stop the provider: that one belongs to the
     * session, which outlives the scene (a content switch or a device rotation
     * builds a new scene, and re-downloading everything because of it would be
     * absurd). The provider's workers are daemons and idle on an empty queue, so
     * one instance for the life of the app costs two parked threads.
     */
    fun shutdown() {
        running = false
        decodeQueue.clear()
        for (thread in decodeThreads) {
            thread.interrupt()
        }
        decodeThreads = emptyList()
    }

    /**
     * Forgets everything: cache, decoded images and counters. The provider is
     * left alone (its threads are shared with the session), but its in-flight
     * requests are withdrawn.
     */
    fun reset() {
        cache.clear()
        decoded.clear()
        knownKeys.clear()
        waitingForDecoder.clear()
        decoding.clear()
        decodeResults.clear()
        stats.reset()
        issuedRequests = 0
        lastUploadFailure = ""
    }

    /**
     * Throws the decoded images away but keeps the codestreams, and queues them
     * for decoding again.
     *
     * This is what the scene calls when it has destroyed its GPU side (the debug
     * content switch clears the scene, and with it every material): the materials
     * are gone, so the images have to be handed over again — but they must not be
     * *downloaded* again. Rebuilding a few hundred materials from cached bytes is
     * milliseconds of decode; refetching them is minutes of grid time.
     */
    fun rearmDecodes() {
        decoded.clear()
        decoding.clear()
        waitingForDecoder.clear()
        waitingForDecoder.addAll(knownKeys)
    }

    /** The keys of the textures the pipeline is holding pixels for. */
    fun decodedKeys(): List<String> = decoded.keys.toList()
    // ------------------------------------------------------------- workers ---

    private fun startDecodeThreadsIfNeeded() {
        if (decodeThreadsStarted.get() >= decodeSlots) {
            return
        }
        synchronized(this) {
            if (decodeThreads.isNotEmpty()) {
                return
            }
            val created = ArrayList<Thread>(decodeSlots)
            for (index in 1..decodeSlots) {
                val thread = Thread({ decodeLoop() }, "EphoraDecode-" + index)
                thread.isDaemon = true
                thread.start()
                created.add(thread)
            }
            decodeThreads = created
        }
    }

    private fun decodeLoop() {
        decodeThreadsStarted.incrementAndGet()
        while (running) {
            val key = try {
                decodeQueue.poll(WORKER_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } ?: continue
            if (!running) {
                return
            }
            val textureId = textureIdOf(key)
            val discardLevel = discardLevelOf(key)
            val asset = cache.asset(textureId, discardLevel)
            if (asset == null) {
                // 2.26: sin esto la clave quedaba en `decoding` para siempre
                // (fuga del set + textura jamas reintentada). El render thread
                // lo reencola en collectDecodes; aqui solo se informa.
                synchronized(decodeResultsLock) {
                    decodeResults.add(DecodeOutcome(key, textureId, discardLevel, null, ASSET_EVICTED, 0L))
                }
                continue
            }
            val startedNanos = System.nanoTime()
            val image = try {
                decoder.decode(asset)
            } catch (error: Throwable) {
                // The interface says it must not throw; a decoder that does is
                // still not allowed to take the pipeline down.
                null
            }
            val millis = (System.nanoTime() - startedNanos) / 1_000_000L
            val reason = if (image == null) {
                "el decoder no pudo leer el codestream (" + decoder.name + ")"
            } else {
                ""
            }
            synchronized(decodeResultsLock) {
                decodeResults.add(DecodeOutcome(key, textureId, discardLevel, image, reason, millis))
            }
        }
    }

    // ---------------------------------------------------------------- misc ---

    /**
     * The compact form the HUD shows: the numbers that change while textures
     * stream in, in the order they happen (asked → arrived → decoded → bound).
     */
    fun hudLine(): String = "texturas: pedidas " + stats.requested +
        " · en vuelo " + provider.inFlight +
        " · recibidas " + stats.received +
        " · bytes " + humanBytes(cache.cachedBytes) +
        " · decode " + stats.decodeOk + "/" + stats.decodeFailed +
        " · subidas " + stats.uploadOk +
        (if (pendingUploadCount > 0) " · en subida " + pendingUploadCount else "") +
        (if (stats.uploadsDeferred > 0) " · diferidas " + stats.uploadsDeferred else "") +
        (if (waitingForDecode > 0) " · esperando " + waitingForDecode else "") +
        " · decoder " + decoder.name

    fun summary(): String = "solicitadas " + stats.requested + "  ·  en vuelo " + provider.inFlight +
        "  ·  recibidas " + stats.received + "  ·  listas " + stats.ready +
        "  ·  fallidas " + stats.failed +
        "  ·  decodificadas " + stats.decodeOk + " / fallo " + stats.decodeFailed +
        "  ·  subidas " + stats.uploadOk + " / fallo " + stats.uploadFailed

    override fun toString(): String = "TexturePipeline(" + decoder.name + ", " +
        provider.transportStats.summary() + ", " + summary() + ")"

    private companion object {
        /**
         * Recovery default: one worker keeps peak native/JVM memory bounded.
         * The queue is deep and remains asynchronous, so the render thread never
         * blocks on JPEG2000. This can be raised later after device evidence.
         */
        const val DEFAULT_DECODE_WORKERS = 1
        const val WORKER_POLL_MILLIS = 200L
        /**
         * 2.26: motivo interno cuando el LRU expulso los bytes antes de que el
         * worker los leyera. No es fallo del codestream (no cuenta decodeFailed).
         */
        const val ASSET_EVICTED = "asset-expulsado-por-LRU"
    }

    private fun keyOf(textureId: String, discardLevel: Int): String = textureId + "@" + discardLevel

    private fun textureIdOf(key: String): String =
        if (key.lastIndexOf('@') < 0) key else key.substring(0, key.lastIndexOf('@'))

    private fun discardLevelOf(key: String): Int {
        val at = key.lastIndexOf('@')
        if (at < 0) {
            return 0
        }
        return key.substring(at + 1).toIntOrNull() ?: 0
    }
}
