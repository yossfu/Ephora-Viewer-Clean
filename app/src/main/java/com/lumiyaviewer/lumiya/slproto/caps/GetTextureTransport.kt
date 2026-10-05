package com.lumiyaviewer.lumiya.slproto.caps

import com.lumiyaviewer.lumiya.slproto.asset.TextureFailureKind
import com.lumiyaviewer.lumiya.slproto.asset.TextureFetchFailure
import com.lumiyaviewer.lumiya.slproto.asset.TextureTransport
import com.lumiyaviewer.lumiya.slproto.asset.TextureTransportReply
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDUuid
import java.net.URL

/**
 * The `GetTexture` capability, as a [TextureTransport].
 *
 * This is the whole of the viewer's texture *network* code. What it sends depends
 * on the kind of endpoint the region handed back, and that is the one decision in
 * this file (see [assetRequestFor]):
 *
 *  * a URL with no path is the **asset CDN** base, asked with
 *    `GET <base>/?texture_id=<uuid>` — exactly what the official viewer builds
 *    (`lltexturefetch.cpp`: `setUrl(http_url + "/?texture_id=" + mID.asString())`);
 *  * a URL with a path is a **legacy capability endpoint**, asked with `POST` and
 *    the LLSD body `{texture_id, discard_level}` that third-party viewers have
 *    always sent.
 *
 * Either way the response body is the JPEG2000 codestream itself (not LLSD).
 * Everything else — queueing, retries, cancellation, counters, timings — belongs
 * to the provider, and the bytes are kept opaque here on purpose: this class never
 * looks inside a codestream, which is what keeps JPEG2000 out of the network layer
 * and the network out of the decoder.
 *
 * ## Why the form matters (phase 2.13b-rev2)
 *
 * The device run of 2.13b-rev1 sent 755 requests and got 755 `HTTP 403` with an
 * `Access Denied` body from `asset-cdn.glb.agni.lindenlab.com`, 0 bytes each. The
 * endpoint was right and the capability was offered by the seed; the *form* was
 * not: an `asset-cdn` host is a CloudFront distribution that serves
 * `GET /?texture_id=<uuid>`, and a body-carrying `POST` to its root is refused
 * before it is read. The report prints the URL shape and the form used, so the
 * next run says which form went out.
 *
 * ## Why `RequestTextureDownload` is not used instead
 *
 * The UDP `RequestImage`/`RequestTextureDownload` path is the legacy one and
 * needs its own packet accounting, priorities and loss handling; every modern
 * viewer fetches textures over `GetTexture` (or the asset cap that replaced it).
 * It stays on the table as a fallback (TODO, fase 4) if a simulator ever refuses
 * the capability.
 *
 * ## Threading
 *
 * [fetch] blocks in an `HttpURLConnection` request with a 30 s timeout, which is
 * why the provider runs it on its own workers. [isReady] reads the resolved
 * capability map, which [Capabilities] publishes from a snapshot so a read from
 * a worker thread is well defined even while the session thread is resolving.
 *
 * ## Why it does not just return a string
 *
 * The failure path used to end in one sentence, which is enough to print and not
 * enough to *diagnose*: a run that reported `respuestas recibidas 649 · errores
 * de red 649 · bytes recibidos 0` could not say whether the endpoint answered, and
 * with which status, or whether nothing was ever read. So each failure is now
 * classified ([TextureFailureKind]) and carries the facts behind the
 * classification — method, status, content type, size, exception, `Server`/`Via`,
 * sanitized body excerpt — which is what the report prints and what tells "the
 * grid refused us" apart from "a proxy in the path refused us" apart from "the
 * connection never completed".
 */
class GetTextureTransport(
    private val capabilities: Capabilities,
    private val capabilityName: String = CAP_NAME
) : TextureTransport {

    override val name: String = "capability " + capabilityName

    override val isReady: Boolean get() = !capabilities.url(capabilityName).isNullOrEmpty()

    override fun fetch(textureId: String, discardLevel: Int): TextureTransportReply {
        val base = capabilities.url(capabilityName)
        if (base.isNullOrEmpty()) {
            // Not a failure yet, and it must not look like one: the capability
            // does not exist until the seed resolves. The provider bounds how
            // long it waits for this (see `GetTextureAssetProvider`).
            val reason = capabilities.lastError.ifEmpty { capabilityName + " no esta en la semilla todavia" }
            return TextureTransportReply.Error(
                reason = reason,
                failure = TextureFetchFailure(
                    kind = TextureFailureKind.NOT_READY,
                    capability = capabilityName,
                    method = METHOD,
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
            )
        }
        // Which request the endpoint wants is a property of the URL, not of the
        // capability: a bare asset base URL (the CDN) is asked with
        // `GET <base>/?texture_id=<uuid>`, and a path-shaped legacy capability
        // endpoint with `POST` and the LLSD body it always took. See
        // [assetRequestFor].
        val request = assetRequestFor(base, textureId, discardLevel)
        val bytes = try {
            capabilities.requestAsset(capabilityName, request.url, request.method, request.legacyBody)
        } catch (error: Throwable) {
            // `requestAsset` does not throw today; if it ever does, the packet
            // must still not escape a worker thread, and it must still be a
            // classified failure rather than a bare sentence.
            return TextureTransportReply.Error(
                reason = capabilityName + ": " + (error.message ?: error.javaClass.simpleName),
                failure = TextureFetchFailure(
                    kind = TextureFailureKind.TRANSPORT_EXCEPTION,
                    capability = capabilityName,
                    method = request.method,
                    endpoint = capabilities.endpointHost(capabilityName),
                    answered = false,
                    status = 0,
                    contentType = "",
                    size = 0,
                    exception = error.javaClass.simpleName,
                    exceptionMessage = error.message ?: "",
                    server = "",
                    via = "",
                    detail = error.message ?: error.javaClass.simpleName,
                    looksProxied = false
                )
            )
        }
        if (bytes == null) {
            // The session already recorded *why* this capability failed, with the
            // status and the headers, so the classification is a translation
            // rather than a guess. `endpointHost` is only used when there is no
            // record at all (a transport that answered nothing at all).
            val recorded = capabilities.lastFailureOf(capabilityName)
                ?: capabilities.lastCallFailure
            if (recorded != null) {
                return TextureTransportReply.Error(
                    reason = capabilityName + ": " + recorded.describe(),
                    failure = classify(recorded)
                )
            }
            val reason = capabilities.lastError.ifEmpty { capabilityName + " no respondio" }
            return TextureTransportReply.Error(
                reason = reason,
                failure = TextureFetchFailure(
                    kind = TextureFailureKind.NOT_READY,
                    capability = capabilityName,
                    method = request.method,
                    endpoint = capabilities.endpointHost(capabilityName),
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
            )
        }
        if (bytes.isEmpty()) {
            // A 2xx with no bytes at all: the endpoint answered, so this is a
            // different failure from every one above, and it is the one that
            // looks identical to success to a counter that only counts "the call
            // finished".
            return TextureTransportReply.Error(
                reason = capabilityName + " respondio 2xx sin cuerpo (0 bytes)",
                failure = TextureFetchFailure(
                    kind = TextureFailureKind.EMPTY_BODY,
                    capability = capabilityName,
                    method = request.method,
                    endpoint = capabilities.endpointHost(capabilityName),
                    answered = true,
                    status = 200,
                    contentType = "",
                    size = 0,
                    exception = "",
                    exceptionMessage = "",
                    server = "",
                    via = "",
                    detail = capabilityName + " devolvio una respuesta valida de 0 bytes",
                    looksProxied = false
                )
            )
        }
        return TextureTransportReply.Bytes(bytes, capabilityName)
    }

    /** See [textureFailureOf], which is where the mapping lives so it can be asserted. */
    private fun classify(recorded: CapabilityCallFailure): TextureFetchFailure =
        textureFailureOf(recorded, capabilityName)

    /**
     * The lines the texture report prints to compare this capability with the
     * rest of the session's HTTP path.
     *
     * When every texture fails, "which of the session's endpoints are failing and
     * in the same way" is the question that decides between a texture bug and a
     * transport-wide one — and the answer is already in the session, next to this
     * one (`EventQueueGet` failing with 502 in the same log is what turned a
     * mysterious 649 errors into a transport question).
     */
    override fun httpDiagnostics(): String {
        val builder = StringBuilder(520)
        // The shape of what the region handed us, and the shape of the request we
        // build from it. Together they answer "capability correcta? endpoint
        // correcto? forma de la URL?" without printing a URL that carries the
        // session (the path of a capability) or a signature (the query of the CDN).
        builder.append("  forma de las URLs resueltas (la ruta y la query no se imprimen: llevan la sesion):\n")
        for (name in SHAPED_CAPABILITIES) {
            builder.append("    ").append(capabilities.urlShape(name)).append('\n')
        }
        builder.append("  forma de la peticion de textura: ").append(REQUEST_FORM_LINE).append('\n')
        val failures = StringBuilder(320)
        for (name in COMPARED_CAPABILITIES) {
            val failure = capabilities.lastFailureOf(name) ?: continue
            failures.append("  ").append(name).append(": ").append(failure.describe())
                .append("  ·  fallos ").append(capabilities.failureCountOf(name)).append('\n')
        }
        if (failures.isNotEmpty()) {
            builder.append("  otras capabilities por el mismo camino HTTP (para comparar):\n").append(failures)
        }
        return builder.toString()
    }

    companion object {
        /** The capability's name in the seed's map; see `Capabilities.SEED_CAPABILITIES`. */
        const val CAP_NAME = "GetTexture"

        /**
         * The method this capability used to be asked with, and still is when the
         * seed hands back a path-shaped legacy capability URL.
         *
         * The modern form is a `GET` on a bare asset base URL (see
         * [assetRequestFor]); this constant is the *default* the diagnostics print
         * before any call has been made, and the one a legacy endpoint keeps.
         */
        const val METHOD = "POST"

        /** The `GET` the asset CDN is asked with. */
        const val METHOD_ASSET = "GET"

        /**
         * What the comparison prints: this capability, the event queue (the one
         * whose failure the user saw in the same log), the other asset capability,
         * and the seed the whole map comes from. A short list on purpose — it is
         * read by eye, and each name costs a line.
         */
        private val COMPARED_CAPABILITIES = listOf(CAP_NAME, "EventQueueGet", "GetMesh", Capabilities.SEED_NAME)

        /**
         * The URL shapes the texture report prints, so a log says *which kind of
         * endpoint* the region handed us without printing the URL itself.
         */
        val SHAPED_CAPABILITIES = listOf(CAP_NAME, "ViewerAsset")

        /**
         * The rule the transport applies, printed as one line so a log states what
         * was sent instead of leaving the reader to infer it.
         */
        const val REQUEST_FORM_LINE =
            "GET <base>/?texture_id=<uuid> si la URL de la region no tiene ruta (CDN de assets, es lo que hace el visor oficial)  ·  " +
                "POST con cuerpo LLSD (texture_id, discard_level) si tiene ruta (capability heredada)"
    }
}

/**
 * One texture request, as the endpoint that will receive it wants it.
 *
 * The two forms are not interchangeable and this is exactly where the viewer
 * chooses:
 *
 *  * **Asset CDN** (`method = GET`, [legacyBody] null): the URL the region
 *    publishes for assets is a *base* URL with no path, and the texture id goes
 *    in the query — `GET <base>/?texture_id=<uuid>`. This is what the official
 *    viewer builds (`lltexturefetch.cpp`: `setUrl(http_url + "/?texture_id=" +
 *    mID.asString())`) and what the CDN answers with a codestream.
 *  * **Legacy capability endpoint** (`method = POST`, [legacyBody] set): a URL
 *    with a real path is a session capability, which takes the LLSD body
 *    (`texture_id`, `discard_level`) that third-party viewers have always sent.
 *
 * Sending the POST form to the CDN base is what an asset CDN answers with an
 * HTTP 403 (`Access Denied`): the request carries no texture id anywhere the CDN
 * looks for it.
 */
internal class AssetRequest(val method: String, val url: String, val legacyBody: Any?) {
    /** True when this is the LLSD-body form rather than the CDN query form. */
    val isLegacy: Boolean get() = legacyBody != null
}

/**
 * Chooses the request form for a resolved asset/capability URL.
 *
 * A URL with no path (or just `/`) is an asset base URL; anything else is a
 * capability endpoint with a session path. Top-level and `internal` so the check
 * harness asserts the choice, including the trailing-slash and existing-query
 * cases, without a network.
 */
internal fun assetRequestFor(base: String, textureId: String, discardLevel: Int): AssetRequest {
    val trimmed = base.trim()
    val path = try {
        URL(trimmed).path ?: ""
    } catch (t: Throwable) {
        ""
    }
    if (path.isEmpty() || path == "/") {
        return AssetRequest(GetTextureTransport.METHOD_ASSET, assetUrlFor(trimmed, textureId), null)
    }
    // The body is the one libopenmetaverse sends: the UUID as an LLSD uuid and
    // the discard level as an integer. A discard level of 0 is the full
    // codestream; higher levels are the grid's own reduction of it. (The CDN form
    // carries no discard: it returns the codestream and the decoder reduces it.)
    val body = LinkedHashMap<String, Any?>()
    body["texture_id"] = LLSDUuid(textureId)
    body["discard_level"] = discardLevel
    return AssetRequest(GetTextureTransport.METHOD, trimmed, body)
}

/**
 * `https://host` -> `https://host/?texture_id=<uuid>`, the form the official
 * viewer sends (it concatenates `"/?texture_id="`), with the two cases that
 * concatenation would get wrong handled explicitly: a base that already ends in
 * `/` and one that already carries a query.
 */
internal fun assetUrlFor(base: String, textureId: String): String {
    val trimmed = base.trim()
    return when {
        trimmed.contains('?') -> trimmed + "&texture_id=" + textureId
        trimmed.endsWith("/") -> trimmed + "?texture_id=" + textureId
        else -> trimmed + "/?texture_id=" + textureId
    }
}

/**
 * The session's facts, in the texture pipeline's vocabulary.
 *
 * The mapping is the whole diagnosis in one place, and it is deliberately a
 * function of what was observed rather than of a status code alone: a 502 is
 * [TextureFailureKind.HTTP_ERROR], but [CapabilityCallFailure.looksProxied] is
 * what says the 502 came from something in front of the grid.
 *
 * Top-level (and `internal`) so the check harness can feed it every kind of record
 * the session can produce and assert the answer, including the two that are easy
 * to get wrong: a 2xx with no bytes is [TextureFailureKind.EMPTY_BODY] and *not* a
 * success, and a 2xx with bytes that we could not read is [TextureFailureKind.UNKNOWN]
 * (our own parsing, not the grid's fault).
 */
internal fun textureFailureOf(recorded: CapabilityCallFailure, capability: String): TextureFetchFailure =
    TextureFetchFailure(
        kind = when {
            recorded.notOffered -> TextureFailureKind.CAPABILITY_MISSING
            !recorded.answered && recorded.exception.isNotEmpty() -> TextureFailureKind.TRANSPORT_EXCEPTION
            !recorded.answered -> TextureFailureKind.NOT_READY
            recorded.status !in 200..299 -> TextureFailureKind.HTTP_ERROR
            recorded.size == 0 -> TextureFailureKind.EMPTY_BODY
            else -> TextureFailureKind.UNKNOWN
        },
        capability = capability,
        method = recorded.method,
        endpoint = recorded.endpoint,
        answered = recorded.answered,
        status = recorded.status,
        contentType = recorded.contentType,
        size = recorded.size,
        exception = recorded.exception,
        exceptionMessage = recorded.exceptionMessage,
        server = recorded.server,
        via = recorded.via,
        detail = recorded.detail,
        looksProxied = recorded.looksProxied
    )
