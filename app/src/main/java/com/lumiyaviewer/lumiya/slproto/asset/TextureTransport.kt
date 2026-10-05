package com.lumiyaviewer.lumiya.slproto.asset

/**
 * What kind of failure stopped a texture fetch.
 *
 * The distinction is the whole point of the 2.13b-rev1 diagnostic: `respuestas
 * recibidas 649 · errores de red 649` says every attempt failed but not *how*,
 * and the four ways a fetch can fail have four different fixes:
 *
 * | kind | what happened | the fix lives in |
 * |---|---|---|
 * | [CAPABILITY_MISSING] | the seed's map has no URL for `GetTexture` | the login/seed handshake |
 * | [NOT_READY] | the capability exists and never became usable | the session's state machine |
 * | [TRANSPORT_EXCEPTION] | no response was ever read (DNS, connect, TLS, timeout, proxy refusal at the socket level) | the HTTP layer / the network |
 * | [HTTP_ERROR] | a response arrived and it was not 2xx (403, 404, 500, 502…) | the grid, or whatever sits in front of it |
 * | [EMPTY_BODY] | a 2xx response with zero bytes | the grid, or our request being the wrong shape |
 * | [UNKNOWN] | none of the above | — |
 *
 * [HTTP_ERROR] with a proxy signature is what "502 Proxy Error" in the log looks
 * like: see [TextureFetchFailure.looksProxied].
 */
enum class TextureFailureKind(val label: String) {
    CAPABILITY_MISSING("capability ausente"),
    NOT_READY("endpoint no disponible"),
    TRANSPORT_EXCEPTION("excepcion de transporte"),
    HTTP_ERROR("error HTTP del servidor"),
    EMPTY_BODY("respuesta valida de 0 bytes"),
    UNKNOWN("sin clasificar")
}

/**
 * One failed texture exchange, with every fact the transport actually saw.
 *
 * It exists because the report has to answer "why did all 649 fail" **from the
 * device log alone**, without a second run and without guessing. So the record
 * carries the pieces the question is made of: the capability and method used, the
 * endpoint host, whether a response arrived at all and with which status, the
 * content type and size, the exception when the failure was an exception, the
 * `Server`/`Via` response headers (which is how a proxy identifies itself), and a
 * short sanitized excerpt of the body.
 *
 * **Sanitizing.** [detail] and [exceptionMessage] come from the network and are
 * printed in a log the user copies around, so they are stripped of the things
 * that must never travel: full URLs become `[url]`, `token=`/`session_id=` style
 * pairs become `[redacted]`, `Cookie:` lines become `[cookie]`, whitespace is
 * collapsed and the whole thing is cut to a fixed length. [endpoint] is the host
 * and port **only** — never the path, the query or anything credential-shaped.
 */
class TextureFetchFailure(
    val kind: TextureFailureKind,
    /** The capability's name, e.g. `GetTexture`. */
    val capability: String,
    /** The HTTP method used, e.g. `POST`. */
    val method: String,
    /** Host and port of the endpoint, or "" when there was no endpoint. */
    val endpoint: String,
    /** True when the endpoint answered at all (then [status] is meaningful). */
    val answered: Boolean,
    /** The HTTP status, or 0 when no response was read. */
    val status: Int,
    val contentType: String,
    /** Bytes the response carried (after decompression), 0 when there was none. */
    val size: Int,
    /** The exception's class name, or "" when the failure was not an exception. */
    val exception: String,
    /** The exception's message, sanitized. */
    val exceptionMessage: String,
    /** The `Server` response header, sanitized; "" when it was absent. */
    val server: String,
    /** The `Via` response header, sanitized; "" when it was absent. */
    val via: String,
    /** A short, sanitized excerpt of the body or of the failure text. */
    val detail: String,
    /**
     * True when the evidence points at an HTTP proxy rather than at the grid: a
     * `Via` header, a `Server` naming a proxy, or a body that says so. A proxy in
     * the path is the one cause that makes *every* capability fail at once — which
     * is exactly the shape of "GetTexture 649 errors and EventQueueGet 500/502 in
     * the same log".
     */
    val looksProxied: Boolean
) {

    /** The one-line form the report prints, with the facts in reading order. */
    fun describe(): String {
        val builder = StringBuilder(240)
        builder.append(kind.label)
        builder.append(" · ").append(capability).append(' ').append(method)
        if (endpoint.isNotEmpty()) {
            builder.append(" · ").append(endpoint)
        }
        if (answered) {
            builder.append(" · HTTP ").append(status)
            if (contentType.isNotEmpty()) {
                builder.append(" · Content-Type ").append(contentType)
            }
            builder.append(" · ").append(size).append(" B")
        } else {
            builder.append(" · sin respuesta")
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
}

/**
 * The answer to one "give me this texture" exchange: either the codestream's
 * bytes, or the reason they did not come.
 *
 * There is no third state on purpose. "Not answered yet" is not a reply — it is
 * the request still being in flight, which the provider tracks itself — so a
 * caller that has a [TextureTransportReply] always knows whether it is holding
 * bytes or holding an error.
 */
sealed class TextureTransportReply {

    /**
     * The bytes the grid sent, with the endpoint that served them. `source` is
     * for the report only: it never takes part in caching or keying.
     */
    class Bytes(val bytes: ByteArray, val source: String) : TextureTransportReply()

    /**
     * Nothing to decode, and why. The reason is shown verbatim in the report, so
     * it should name the actual failure ("HTTP 404", "capability GetTexture no
     * disponible") rather than a generic one.
     *
     * [failure] is the same thing in structured form, when the transport could
     * produce it: the reason string is for the eye, the record is for the
     * counters, the classification and the report. A transport that only knows
     * its reason leaves it null, and the provider classifies it as
     * [TextureFailureKind.UNKNOWN] with that text as the detail — so nothing
     * disappears from the report just because a transport is simple.
     */
    class Error(val reason: String, val failure: TextureFetchFailure? = null) : TextureTransportReply()
}

/**
 * The seam between "the pipeline needs a texture" and "the grid hands one out".
 *
 * It exists so that the download policy (queueing, retries, cancellation,
 * counters, timings) can be written and tested against a transport that answers
 * instantly, while the real transport is a few lines over [Capabilities]. It is
 * also what keeps the JPEG2000 layer out of the picture: the transport returns
 * whatever the grid sent — a codestream — and never looks inside it.
 *
 * ## Threading
 *
 * [fetch] is **synchronous and blocking** and is called from the provider's own
 * worker threads. It must never be called from the render thread, and it must
 * never be called from the session's network threads either — a slow texture
 * must not delay region traffic. [isReady] and [name] may be read from any
 * thread.
 */
interface TextureTransport {

    /** Human readable name of the endpoint, for the report. */
    val name: String

    /**
     * False when the endpoint cannot serve anything at all — the capability has
     * not been resolved yet, or the grid does not offer it.
     *
     * This is not a failure and must not be turned into one: a request made
     * while it is false is *waited for*, not failed, so textures requested
     * during login are not lost.
     */
    val isReady: Boolean

    /** Asks for one texture. Blocking, off the render thread, never throws. */
    fun fetch(textureId: String, discardLevel: Int): TextureTransportReply

    /**
     * Extra HTTP lines for the report: what the *other* endpoints that share this
     * transport's HTTP path are doing.
     *
     * It exists for one comparison. When every texture fails, the question is
     * whether that is a texture problem or the session's whole HTTP path failing,
     * and the log already contains the evidence (`EventQueueGet: HTTP 502`). This
     * is what lets the texture block print both next to each other, so the
     * comparison is made by the report instead of by whoever reads it.
     */
    fun httpDiagnostics(): String = ""
}
