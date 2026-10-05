package com.lumiyaviewer.lumiya.slproto.asset

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real texture provider: it turns [request] calls into blocking fetches on
 * its own worker threads, and hands the results back through [drainCompleted].
 *
 * ## Why a pool and not a call
 *
 * The render thread must never wait for HTTP, and the session's network threads
 * must never be delayed by a texture (region traffic and the event queue are
 * latency-critical; a 200 kB codestream is not). So the fetch is off both: a
 * small fixed pool of workers pulls from a queue, exactly like the reference
 * viewers' `BackgroundExecutor`, and the render thread only ever touches the
 * two lock-free ends of the pipe — [request] (enqueue) and [drainCompleted]
 * (take what finished).
 *
 * ## Identity, repeats and cancellation
 *
 * A request is keyed by `uuid@discard`. Asking twice for the same key while it
 * is in flight is a no-op — the pipeline's cache already prevents it in normal
 * operation, and this is the second line of defence, so a frame that asks for
 * the same texture from twenty faces still costs one download. [cancel] removes
 * a key from the queue *and* drops its result if it arrives anyway, which is what
 * makes "the object left the view" not waste a decode or a GPU upload.
 *
 * ## Not-ready is not failure
 *
 * At login the capability does not exist yet. A worker that finds the transport
 * not ready **waits** (bounded) instead of failing the texture, so textures
 * asked for during the handshake are downloaded rather than written off. Only
 * after [MAX_CAPABILITY_WAITS] waits does the request fail, with the reason
 * naming the capability.
 *
 * Fase 2.13b: the transport is a [GetTextureTransport]-style capability call.
 * The JPEG2000 codestream is not looked at here — this class only knows that
 * bytes arrived and how long that took.
 */
class GetTextureAssetProvider(
    private val transport: TextureTransport,
    workers: Int = DEFAULT_WORKERS,
    /** How long a worker waits between readiness checks. */
    private val capabilityWaitMillis: Long = CAPABILITY_WAIT_MILLIS,
    /** How many such waits before the request is failed. */
    private val maxCapabilityWaits: Int = MAX_CAPABILITY_WAITS
) : TextureAssetProvider {

    /** The provider can serve when the endpoint exists. */
    override val isReady: Boolean get() = transport.isReady

    /** Everything the wire stage has done; see [TextureTransportStats]. */
    override val transportStats = TextureTransportStats()

    /** Requests handed over and not answered yet. */
    override val inFlight: Int get() = transportStats.inFlight

    private class Request(val textureId: String, val discardLevel: Int)

    private val queue = LinkedBlockingQueue<String>()
    private val requests = ConcurrentHashMap<String, Request>()
    private val withdrawn = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val completed = ArrayList<TextureAssetResult>()
    private val completedLock = Any()

    @Volatile
    private var running = true

    private val workerCount = if (workers < 1) 1 else workers
    private val workersStarted = AtomicInteger()

    @Volatile
    private var workerThreads = emptyList<Thread>()

    /** Requests the pipeline handed over, repeats included. */
    var received: Int = 0
        private set

    /** Requests dropped because the same key was already in flight. */
    var duplicates: Int = 0
        private set

    override fun request(textureId: String, discardLevel: Int) {
        if (textureId.isEmpty() || !running) {
            return
        }
        received += 1
        val key = keyOf(textureId, discardLevel)
        if (requests.putIfAbsent(key, Request(textureId, discardLevel)) != null) {
            // Already asked for (the cache normally prevents this): one download,
            // not two. It is counted so a caller that bypasses the cache shows up
            // in the report instead of silently doubling traffic.
            duplicates += 1
            return
        }
        withdrawn.remove(key)
        transportStats.noteRequest()
        startWorkersIfNeeded()
        queue.offer(key)
    }

    override fun cancel(textureId: String) {
        if (textureId.isEmpty()) {
            return
        }
        val prefix = textureId + "@"
        for (key in requests.keys.filter { it.startsWith(prefix) }) {
            if (requests.remove(key) != null) {
                withdrawn.add(key)
                queue.remove(key)
                transportStats.noteCancel(countedInFlight = true)
            }
        }
    }

    override fun drainCompleted(): List<TextureAssetResult> {
        synchronized(completedLock) {
            if (completed.isEmpty()) {
                return emptyList()
            }
            val drained = ArrayList<TextureAssetResult>(completed)
            completed.clear()
            return drained
        }
    }

    /** The wire layer's own lines about the session's shared HTTP path. */
    override fun httpDiagnostics(): String = transport.httpDiagnostics()

    override fun shutdown() {
        running = false
        queue.clear()
        for (thread in workerThreads) {
            thread.interrupt()
        }
        workerThreads = emptyList()
        requests.clear()
    }

    /** Requests that are queued or being fetched right now. */
    val pendingCount: Int get() = requests.size

    /** Nothing was asked yet: the workers have not been created. */
    val isStarted: Boolean get() = workersStarted.get() > 0

    // ------------------------------------------------------------- workers ---

    private fun startWorkersIfNeeded() {
        if (workersStarted.get() >= workerCount) {
            return
        }
        synchronized(this) {
            if (workerThreads.isNotEmpty()) {
                return
            }
            val created = ArrayList<Thread>(workerCount)
            for (index in 1..workerCount) {
                val thread = Thread({ workerLoop() }, "EphoraTexture-" + index)
                // Daemon: a provider that was never shut down must not keep the
                // process (or the check harness) alive.
                thread.isDaemon = true
                thread.start()
                created.add(thread)
            }
            workerThreads = created
        }
    }

    private fun workerLoop() {
        workersStarted.incrementAndGet()
        while (running) {
            val key = try {
                queue.poll(WORKER_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } ?: continue
            if (!running) {
                return
            }
            if (withdrawn.remove(key)) {
                requests.remove(key)
                continue
            }
            val request = requests[key] ?: continue
            fetchOne(key, request)
        }
    }

    private fun fetchOne(key: String, request: Request) {
        var waits = 0
        while (running && !transport.isReady && waits < maxCapabilityWaits) {
            waits += 1
            transportStats.noteCapabilityWait()
            if (!sleepQuietly(capabilityWaitMillis)) {
                return
            }
        }
        if (!running) {
            return
        }
        if (withdrawn.remove(key)) {
            requests.remove(key)
            return
        }
        val startedNanos = System.nanoTime()
        var attempt = 0
        var reply: TextureTransportReply
        while (true) {
            attempt += 1
            reply = try {
                transport.fetch(request.textureId, request.discardLevel)
            } catch (error: Throwable) {
                // A transport that throws is a transport that failed: the pipeline
                // must not see an exception from a worker thread.
                TextureTransportReply.Error(
                    error.message ?: error.javaClass.simpleName,
                    transientTransportFailure(error)
                )
            }
            if (!running || attempt > MAX_TRANSIENT_RETRIES || !isTransient(reply)) {
                break
            }
            transportStats.noteRetry()
            if (!sleepQuietly(TRANSIENT_RETRY_DELAY_MILLIS * attempt.toLong())) {
                return
            }
        }
        val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L
        requests.remove(key)
        if (withdrawn.remove(key)) {
            // Withdrawn while the fetch was in the air: the bytes are dropped on
            // purpose. They were paid for, so this is counted, but nothing is
            // decoded or uploaded for an object that is no longer drawn.
            return
        }
        when (reply) {
            is TextureTransportReply.Bytes -> {
                if (reply.bytes.isEmpty()) {
                    // The result is published *before* the counters move: a caller
                    // that sees `responses` go up must be able to drain the result
                    // it just counted, or the report and the pipe tell two
                    // different stories.
                    publish(TextureAssetResult.Failed(
                        request.textureId,
                        request.discardLevel,
                        "respuesta vacia de " + reply.source
                    ))
                    transportStats.noteFailure(
                        emptyFailure(reply),
                        request.textureId,
                        elapsedMillis
                    )
                } else {
                    publish(TextureAssetResult.Ready(
                        TextureAsset(request.textureId, reply.bytes, request.discardLevel, reply.source)
                    ))
                    transportStats.noteResponse(reply.bytes.size, elapsedMillis)
                }
            }
            is TextureTransportReply.Error -> {
                publish(TextureAssetResult.Failed(request.textureId, request.discardLevel, reply.reason))
                transportStats.noteFailure(reply.failure ?: unclassifiedFailure(reply.reason), request.textureId, elapsedMillis)
            }
        }
    }

    private fun isTransient(reply: TextureTransportReply): Boolean {
        val failure = (reply as? TextureTransportReply.Error)?.failure ?: return false
        return when (failure.kind) {
            TextureFailureKind.TRANSPORT_EXCEPTION -> true
            TextureFailureKind.HTTP_ERROR -> failure.status == 408 ||
                failure.status == 425 || failure.status == 429 ||
                failure.status == 500 || failure.status == 502 ||
                failure.status == 503 || failure.status == 504
            else -> false
        }
    }

    private fun transientTransportFailure(error: Throwable): TextureFetchFailure = TextureFetchFailure(
        kind = TextureFailureKind.TRANSPORT_EXCEPTION,
        capability = transport.name,
        method = "",
        endpoint = "",
        answered = false,
        status = 0,
        contentType = "",
        size = 0,
        exception = error.javaClass.simpleName,
        exceptionMessage = "",
        server = "",
        via = "",
        detail = error.javaClass.simpleName,
        looksProxied = false
    )

    /**
     * A 2xx-with-no-bytes answer from a transport that did not build a failure
     * record (a simple one, or a test double).
     *
     * The counters still have to add up and the report still has to say
     * something, so the provider classifies what it knows instead of dropping the
     * answer into an anonymous "error".
     */
    private fun emptyFailure(reply: TextureTransportReply.Bytes): TextureFetchFailure = TextureFetchFailure(
        kind = TextureFailureKind.EMPTY_BODY,
        capability = reply.source,
        method = "POST",
        endpoint = "",
        answered = true,
        status = 200,
        contentType = "",
        size = 0,
        exception = "",
        exceptionMessage = "",
        server = "",
        via = "",
        detail = reply.source + " devolvio una respuesta valida de 0 bytes",
        looksProxied = false
    )

    /** Same, for an error a transport described with a sentence only. */
    private fun unclassifiedFailure(reason: String): TextureFetchFailure = TextureFetchFailure(
        kind = TextureFailureKind.UNKNOWN,
        capability = transport.name,
        method = "POST",
        endpoint = "",
        answered = false,
        status = 0,
        contentType = "",
        size = 0,
        exception = "",
        exceptionMessage = "",
        server = "",
        via = "",
        detail = reason,
        looksProxied = false
    )

    private fun publish(result: TextureAssetResult) {
        synchronized(completedLock) {
            completed.add(result)
        }
    }

    private fun sleepQuietly(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    override fun toString(): String = "GetTextureAssetProvider(" + transport.name +
        ", " + workerCount + " hilos, recibidas " + received + ", duplicadas " + duplicates +
        ", " + transportStats.summary() + ")"


    private fun keyOf(textureId: String, discardLevel: Int): String = textureId + "@" + discardLevel

    private companion object {
        /** Two: the grid serves textures over HTTP/2 and one is enough for a queue. */
        const val DEFAULT_WORKERS = 2
        const val WORKER_POLL_MILLIS = 200L
        // One short retry budget for errors likely to be transient. Do not retry
        // 401/403/404 because those usually mean a stale capability or missing asset.
        const val MAX_TRANSIENT_RETRIES = 2
        const val TRANSIENT_RETRY_DELAY_MILLIS = 500L
        const val CAPABILITY_WAIT_MILLIS = 500L
        /**
         * 20 x 500 ms = 10 s of "the capability is not there yet". Beyond that the
         * grid answered the seed without a `GetTexture`, so waiting more is
         * pointless and the texture is failed with a reason that names it.
         */
        const val MAX_CAPABILITY_WAITS = 20
    }
}
