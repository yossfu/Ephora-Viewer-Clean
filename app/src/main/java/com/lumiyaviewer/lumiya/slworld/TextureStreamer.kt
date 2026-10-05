package com.lumiyaviewer.lumiya.slworld

import com.lumiyaviewer.lumiya.renderer.Renderer
import com.lumiyaviewer.lumiya.renderer.TextureDesc
import com.lumiyaviewer.lumiya.slproto.asset.TexturePipeline
import com.lumiyaviewer.lumiya.slproto.asset.OpenJpegTextureDecoder
import com.lumiyaviewer.lumiya.slproto.asset.TexturePipelineEvent
import com.lumiyaviewer.lumiya.slproto.asset.TextureTransportStats
import com.lumiyaviewer.lumiya.slproto.asset.formatMillis
import com.lumiyaviewer.lumiya.slproto.asset.failureReading
import com.lumiyaviewer.lumiya.slproto.asset.humanBytes
import com.lumiyaviewer.lumiya.slproto.asset.shortId

/**
 * The bridge between the texture pipeline and the scene.
 *
 * The pipeline knows about bytes and pixels; the scene knows about entities and
 * materials. This class is the only thing that knows both, and it only ever runs
 * on the render thread (the scene's frame), which is what makes the GPU upload
 * legal: [Renderer.createTexture] may only be called there.
 *
 * ## What it does per frame
 *
 *  * [request] — a face wants its texture. It goes through the pipeline's cache,
 *    so twenty faces sharing a texture cost one download.
 *  * [pump] — take whatever the pipeline finished. For each decoded image:
 *    upload it, register it in [SLTextureCache] (which rebuilds every material
 *    already using that UUID) and report the upload back to the pipeline so the
 *    upload counters live where the GPU call is.
 *
 * ## Fallback
 *
 * Nothing here is ever required for the region to draw. A texture that has not
 * arrived, one the grid refused and one the decoder could not read all end the
 * same way: the material keeps the per-face [SLTextureFace] tint, or the
 * per-texture debug tint when the wire carried no tint, and the object is drawn
 * as it was before textures existed. The report says which of the three it was,
 * per texture, so "no texture yet" is never guessed at.
 *
 * ## Counters
 *
 * Everything the user asked to be able to read is exposed here or on the
 * pipeline, and [report] is the single text that prints it all. The two numbers
 * that need the scene (faces and entities currently showing pixels) are asked
 * for through [faceCountProvider]/[entityCountProvider] rather than copied, so
 * the report can never show a stale count.
 */
class TextureStreamer(
    /** The stages: cache, wire, decode. Owned by the scene that built it. */
    val pipeline: TexturePipeline,
    /** Where decoded textures are registered so materials get them. */
    private val materials: SLTextureCache,
    /** The only thread allowed to touch it is the render thread. */
    private val renderer: Renderer
) {

    /**
     * Master switch. Off means the scene draws exactly what it drew in 2.13a:
     * no requests, no uploads, the same fallback colours.
     */
    var enabled: Boolean = true

    /**
     * How many *new* textures one scan may ask for. A region can reference
     * thousands of textures from the first frame onward; asking for all of them
     * at once would put a wall of requests between the login and the picture, so
     * the scan is rationed and the nearest faces win. Zero means "no new
     * requests this scan".
     */
    var perScanBudget: Int = DEFAULT_PER_SCAN_BUDGET

    /**
     * Recovery 4 (método Lumiya, GLSyncLoadQueue MAX_LOADS_PER_FRAME /
     * MAX_SIZE_PER_FRAME): at most this many GPU uploads and this many bytes
     * of pixels per frame. Each upload is a render-thread `createTexture` plus
     * a material rebind; unbounded bursts of them are what stretched the sync
     * phase to seconds. The rest waits in the pipeline, in order.
     */
    var maxUploadsPerFrame: Int = DEFAULT_MAX_UPLOADS_PER_FRAME
    var maxUploadBytesPerFrame: Long = DEFAULT_MAX_UPLOAD_BYTES_PER_FRAME

    /**
     * Recovery 4 (método Lumiya: `if (!isResponsiveMode) RunLoadQueue()`):
     * while the user drives the camera, uploads and scans pause and the frame
     * stays for the camera. Set every frame from the render thread; downloads
     * and decodes keep running in their own threads meanwhile.
     */
    @Volatile
    var holdLoads: Boolean = false

    /** Uploads executed by the last [pump]; the report prints it. */
    var lastPumpUploads: Int = 0
        private set

    /**
     * Destino sculpt (fase 5): cuando un mapa decodificado es un sculpt map
     * pendiente, la escena lo consume para construir geometría real y devuelve
     * true; el mapa no se sube a GPU como textura (no se dibuja en ninguna
     * cara). Null = sin sculpt pendiente. Solo hilo de render.
     */
    var sculptSink: ((textureId: String, texture: com.lumiyaviewer.lumiya.slproto.asset.DecodedTexture) -> Boolean)? = null

    /** Mapas sculpt consumidos para geometría por el último [pump]. */
    var lastPumpSculpts: Int = 0
        private set

    /** How many uploaded images the backend refused to create. */
    var rejectedUploads = 0
        private set

    /** The last upload the backend refused, verbatim. */
    var lastUploadFailure: String = ""
        private set

    private val uploadDims = LinkedHashMap<String, String>()

    fun uploadDimsOf(textureId: String): String? = uploadDims[textureId]

    /** The last codestream a decoder could not read, verbatim. */
    var lastDecodeFailure: String = ""
        private set

    /** Assigned by the scene; see the class comment. */
    var faceCountProvider: () -> Int = { 0 }

    /** Assigned by the scene; see the class comment. */
    var entityCountProvider: () -> Int = { 0 }

    /**
     * A face wants its texture. Returns true when this call starts meaningful
     * work for the scan budget: either a new network fetch or a decode re-arm
     * from an already cached codestream after GPU eviction. A normal cache hit
     * returns false.
     */
    fun request(face: SLTextureFace): Boolean {
        if (!enabled) {
            return false
        }
        if (!face.hasTexture) {
            return false
        }
        // A GPU texture may have been evicted by the residency LRU while its
        // J2K codestream is still cached. Re-arm only the decode in that case;
        // do not spend the scan's network-request budget and do not redownload.
        if (!materials.hasDecoded(face.textureId) &&
            pipeline.ensureCachedDecode(face.textureId, DISCARD_LEVEL)
        ) {
            return true
        }
        return pipeline.request(face.textureId, DISCARD_LEVEL)
    }

    /** Forgets a texture nobody draws any more (the object left the scene). */
    fun cancel(textureId: String) {
        pipeline.provider.cancel(textureId)
    }

    /**
     * Takes what the pipeline finished and uploads it, within the per-frame
     * budget. Called once per frame from the scene, on the render thread.
     * While [holdLoads] (camera interaction) nothing is uploaded: Lumiya
     * skips its whole load queue in responsive mode for the same reason.
     */
    fun pump() {
        lastPumpUploads = 0
        lastPumpSculpts = 0
        if (!enabled || holdLoads) {
            return
        }
        for (event in pipeline.pump(maxUploadsPerFrame, maxUploadBytesPerFrame)) {
            when (event) {
                is TexturePipelineEvent.Decoded -> {
                    val sink = sculptSink
                    if (sink != null) {
                        val consumed = try {
                            sink(event.textureId, event.texture)
                        } catch (e: Throwable) {
                            false
                        }
                        if (consumed) {
                            lastPumpSculpts += 1
                            pipeline.releaseDecoded(event.textureId, event.discardLevel)
                            continue
                        }
                    }
                    upload(event)
                    lastPumpUploads += 1
                }
                is TexturePipelineEvent.DecodeFailed -> {
                    // Not a failure of the pipeline: the bytes are cached and the
                    // scene keeps its fallback. Recorded so the report can name
                    // the codestream that could not be read.
                    lastDecodeFailure = event.textureId + ": " + event.reason
                }
            }
        }
    }

    private fun upload(event: TexturePipelineEvent.Decoded) {
        val image = event.texture
        if (!image.isConsistent) {
            // A decoder that returns a buffer whose size does not match its
            // dimensions is a decoder bug, not a texture: refused here so a
            // malformed image can never reach the GPU.
            pipeline.noteUploadFailed(
                event.textureId,
                event.discardLevel,
                "imagen inconsistente " + image
            )
            rejectedUploads += 1
            lastUploadFailure = "imagen inconsistente " + image
            pipeline.releaseDecoded(event.textureId, event.discardLevel)
            return
        }
        // Account for mip levels before the native/GPU allocation. This is an
        // estimate (RGBA base * 4/3), deliberately conservative enough to stop
        // the viewer from accumulating hundreds of resident textures.
        val gpuResidencyBytes = (image.pixels.size.toLong() * 4L / 3L).coerceAtLeast(image.pixels.size.toLong())
        materials.prepareForUpload(event.textureId, gpuResidencyBytes)
        val startedNanos = System.nanoTime()
        val handle = try {
            renderer.createTexture(
                TextureDesc(
                    width = image.width,
                    height = image.height,
                    pixels = image.pixels,
                    hasAlpha = image.hasAlpha
                )
            )
        } catch (error: Throwable) {
            // A backend refusal must not stop the frame or the region: the
            // material simply keeps its fallback colour. Keep the real error
            // because a generic "backend rejected" cannot diagnose GPU failures.
            lastUploadFailure = event.textureId + ": " +
                (error.message ?: error.javaClass.simpleName)
            null
        }
        val millis = (System.nanoTime() - startedNanos) / 1_000_000L
        if (handle == null) {
            val reason = if (lastUploadFailure.startsWith(event.textureId + ":")) {
                lastUploadFailure.substringAfter(": ")
            } else {
                "el backend rechazo la textura"
            }
            pipeline.noteUploadFailed(event.textureId, event.discardLevel, reason)
            rejectedUploads += 1
            if (!lastUploadFailure.startsWith(event.textureId + ":")) {
                lastUploadFailure = event.textureId + ": " + reason
            }
            pipeline.releaseDecoded(event.textureId, event.discardLevel)
            return
        }
        // Registering the pixels is what rebinds every material already built
        // for that UUID; materials created afterwards pick it up on creation.
        // The alpha flag travels too, so faces with an alpha texture blend
        // even when their wire tint is opaque (rebind rebuilds them as BLEND).
        materials.textureDecoded(event.textureId, handle, image.hasAlpha, gpuResidencyBytes)
        pipeline.noteUploaded(event.textureId, event.discardLevel, image.size, millis)
        uploadDims[event.textureId] = image.width.toString() + "x" + image.height
        if (uploadDims.size > 128) {
            val it = uploadDims.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        // GPU owns the uploaded copy now; do not retain another full RGBA buffer
        // on the managed heap. The codestream in the asset cache is preserved.
        pipeline.releaseDecoded(event.textureId, event.discardLevel)
    }

    /** Stops this scene's decode workers; the session's provider outlives it. */
    fun shutdown() {
        pipeline.shutdown()
    }

    // --------------------------------------------------------------- report ---

    /** One HUD line: the numbers that move while textures stream in. */
    fun hudLine(): String = pipeline.hudLine() +
        " · aplicadas " + materials.materialsWithTexture + " mat/" + faceCountProvider() + " caras" +
        (if (rejectedUploads > 0) " · rechazadas " + rejectedUploads else "")

    /**
     * The whole texture block. Every counter the phase was asked to make
     * visible is here, each one next to the stage that owns it, so no number has
     * to be inferred from another.
     */
    fun report(): String {
        val builder = StringBuilder(1400)
        val cacheStats = pipeline.cache.stats
        val transport = pipeline.provider.transportStats
        val stats = pipeline.stats
        builder.append("--- texturas (fase 2.13c-recovery2) ---").append('\n')
        builder.append("decoder: ").append(pipeline.decoder.name)
            .append(if (pipeline.decoder.isAvailable) "" else "  (los bytes se guardan y se esperan: no se pierde ninguna descarga)")
            .append('\n')
        builder.append("UUIDs solicitados (miss): ").append(cacheStats.requested)
            .append("  ·  cache hit: ").append(cacheStats.cacheHits)
            .append("  ·  fallidas por la grid: ").append(cacheStats.failed)
            .append("  ·  reintentos: ").append(cacheStats.retried)
            .append('\n')
        builder.append("peticiones emitidas: ").append(transport.requests)
            .append("  ·  en vuelo: ").append(transport.inFlight)
            .append("  ·  respuestas recibidas: ").append(transport.responses)
            .append(" (con bytes ").append(transport.successfulResponses)
            .append(" / con error ").append(transport.failures).append(")")
            .append("  ·  canceladas: ").append(transport.cancelled)
            .append('\n')
        builder.append("errores por tipo (").append(transport.failures).append(" en total): ")
            .append(transport.failureSummary()).append('\n')
        builder.append(failureReading(transport)).append('\n')
        val firstFailure = transport.firstFailure
        builder.append("primer error")
        if (transport.firstFailureTextureId.isNotEmpty()) {
            builder.append(" (textura ").append(shortId(transport.firstFailureTextureId)).append(")")
        }
        builder.append(": ").append(if (firstFailure == null) "-" else firstFailure.describe()).append('\n')
        val lastFailure = transport.lastFailure
        builder.append("ultimo error")
        if (transport.lastFailureTextureId.isNotEmpty()) {
            builder.append(" (textura ").append(shortId(transport.lastFailureTextureId)).append(")")
        }
        builder.append(": ").append(if (lastFailure == null) "-" else lastFailure.describe()).append('\n')
        if (pipeline.httpDiagnostics().isNotEmpty()) {
            builder.append(pipeline.httpDiagnostics())
        }
        builder.append("assets listos: ").append(stats.ready)
            .append("  ·  assets fallidos: ").append(stats.failed)
            .append("  ·  repetidas sin pedir (cache): ").append(cacheStats.cacheHits)
            .append("  ·  peticiones diferidas (sin capability): ").append(stats.deferred)
            .append(if (stats.capped > 0) "  ·  cortadas por tope: " + stats.capped else "")
            .append('\n')
        builder.append("decode: OK ").append(stats.decodeOk)
            .append("  ·  fallo ").append(stats.decodeFailed)
            .append("  ·  encoladas ").append(stats.decodeQueued)
            .append("  ·  esperando decoder ").append(pipeline.waitingForDecode)
            .append('\n')
        (pipeline.decoder as? OpenJpegTextureDecoder)?.let {
            builder.append(it.diagnosticLine()).append('\n')
        }
        builder.append("upload: OK ").append(stats.uploadOk)
            .append("  ·  fallo ").append(stats.uploadFailed)
            .append("  ·  rechazadas por el backend ").append(rejectedUploads)
            .append("  ·  bytes subidos ").append(humanBytes(stats.uploadBytes))
            .append("  ·  ultimo barrido ").append(lastPumpUploads)
            .append("  ·  diferidas por presupuesto ").append(stats.uploadsDeferred)
            .append("  ·  en espera de subida ").append(pipeline.pendingUploadCount)
            .append('\n')
        builder.append("aplicadas: ").append(materials.materialsWithTexture)
            .append(" materiales con textura  ·  ").append(faceCountProvider())
            .append(" caras con textura  ·  ").append(entityCountProvider())
            .append(" entidades con textura  ·  ").append(materials.decodedCount)
            .append(" texturas decodificadas")
            .append('\n')
        builder.append("memoria: cache ").append(humanBytes(pipeline.cache.cachedBytes))
            .append(" en ").append(pipeline.cache.count)
            .append(" blobs  ·  imagenes ").append(humanBytes(pipeline.decodedBytes))
            .append(" en ").append(pipeline.decodedCount)
            .append(" texturas")
            .append('\n')
        builder.append("tiempo de descarga: ").append(transport.millis).append(" ms total")
            .append("  ·  media ").append(formatMillis(transport.averageMillis)).append(" ms")
            .append("  ·  max ").append(transport.maxMillis).append(" ms")
            .append("  ·  ultima ").append(transport.lastMillis).append(" ms")
            .append('\n')
        builder.append("tiempo de decode: ").append(stats.decodeMillis).append(" ms total")
            .append("  ·  media ").append(formatMillis(stats.averageDecodeMillis)).append(" ms")
            .append("  ·  ultima ").append(stats.lastDecodeMillis).append(" ms")
            .append('\n')
        builder.append("tiempo de upload: ").append(stats.uploadMillis).append(" ms total")
            .append("  ·  media ").append(formatMillis(stats.averageUploadMillis)).append(" ms")
            .append("  ·  ultima ").append(stats.lastUploadMillis).append(" ms")
            .append('\n')
        builder.append("transporte: ").append(pipeline.provider.transportStats.summary()).append('\n')
        if (transport.capabilityWaits > 0) {
            builder.append("esperas de la capability GetTexture: ").append(transport.capabilityWaits)
                .append(" (normal durante el login; si no baja nunca, la grid no la ofrece)").append('\n')
        }
        val failure = pipeline.lastUploadFailure.ifEmpty { lastUploadFailure }
        builder.append("ultimo fallo de textura: ").append(failure.ifEmpty { "-" }).append('\n')
        builder.append("ultimo codestream ilegible: ")
            .append(lastDecodeFailure.ifEmpty { "-" }).append('\n')
        builder.append("enabled: ").append(enabled)
            .append(if (holdLoads) " (en espera: camara activa)" else "")
            .append("  ·  presupuesto por barrido: ").append(perScanBudget)
            .append("  ·  tope subida: ").append(maxUploadsPerFrame)
            .append(" u / ").append(humanBytes(maxUploadBytesPerFrame))
            .append("  ·  tope de peticiones: ")
            .append(if (pipeline.maxRequests > 0) pipeline.maxRequests.toString() else "sin tope")
            .append('\n')
        return builder.toString()
    }

    /** A one-line verdict for the HUD's list: is anything wrong right now. */
    fun statusLine(): String = when {
        !enabled -> "texturas: desactivadas"
        pipeline.provider.inFlight > 0 -> "texturas: descargando (" + pipeline.provider.inFlight + " en vuelo)"
        pipeline.decoder.isAvailable && pipeline.stats.decodeOk > 0 -> "texturas: " + pipeline.stats.decodeOk + " decodificadas"
        pipeline.decoder.isAvailable && pipeline.stats.decodeFailed > 0 -> "texturas: decoder " + pipeline.decoder.name + ": " + pipeline.stats.decodeFailed + " fallos (ver j2k etapas)"
        pipeline.stats.ready > 0 -> "texturas: " + pipeline.stats.ready + " descargadas, esperando decode"
        pipeline.stats.deferred > 0 -> "texturas: esperando la capability GetTexture"
        pipeline.stats.requested == 0 -> "texturas: nada pedido todavia"
        else -> "texturas: pidiendo"
    }

    private companion object {
        /**
         * Full resolution. Discard levels are how the grid hands out a smaller
         * version of the same codestream, and choosing one by distance is a
         * *rendering* decision (Phase 4/11) that belongs with the LOD work, not
         * with the pipeline that proves the flow.
         */
        // Start at a reduced discard level to cap initial decode/GPU memory.
        // Higher detail can be requested later once stability is confirmed.
        const val DISCARD_LEVEL = 2

        /** Enough to fill a view in a few seconds without a wall of requests. */
        const val DEFAULT_PER_SCAN_BUDGET = 24

        /**
         * Recovery 4, método Lumiya (GLSyncLoadQueue: 16 cargas / 4 MiB por
         * frame con descanso): punto de partida conservador para subidas
         * Filament, que pesan más que una carga GL de Lumiya porque cada una
         * trae su rebind de materiales. Se ajusta con la medida del
         * dispositivo, no a ojo.
         */
        const val DEFAULT_MAX_UPLOADS_PER_FRAME = 4
        const val DEFAULT_MAX_UPLOAD_BYTES_PER_FRAME = 4L * 1024L * 1024L
    }
}
