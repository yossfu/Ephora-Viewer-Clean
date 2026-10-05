package com.lumiyaviewer.lumiya.slproto.asset

import java.util.Locale

/**
 * The small formatting helpers every texture report line uses.
 *
 * They live here, once, because the texture report is assembled from three
 * layers (the pipeline, the transport and the scene's streamer) and a byte count
 * or a millisecond average that is written three different ways makes two
 * numbers that should be comparable look like they are not.
 */
internal fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
}

/** One decimal place, with a dot, whatever the device's locale says. */
internal fun formatMillis(value: Double): String = String.format(Locale.US, "%.1f", value)

/**
 * A texture UUID, shortened for a report line.
 *
 * The first group is enough to recognise a texture across two lines of the same
 * log (which is what "which texture failed" needs), and it keeps a line that
 * names a texture from being 36 characters wider than one that does not.
 */
internal fun shortId(textureId: String): String =
    if (textureId.length <= 8) textureId else textureId.take(8) + "..."

/**
 * The one-line reading of a failure breakdown, in the terms the question is asked
 * in: is the fetch failing because the capability is missing (A), the endpoint
 * cannot be used (B), the grid answered with an error (C), something in the path
 * refused it (D), the answer was valid and empty (E), or is it our own reading of
 * a good answer (F)?
 *
 * The kinds partition the failures — a proxy-shaped HTTP error and a connection
 * failure are both D, and C is what is left of the HTTP errors once those are
 * taken out — so nothing is counted twice, nothing is left out, and the last
 * number is [TextureTransportStats.failures]. That is what makes it an answer
 * rather than a summary of a summary.
 *
 * It lives here, next to the other report text helpers, so the check harness can
 * assert the partition without a scene.
 */
internal fun failureReading(transport: TextureTransportStats): String {
    val proxied = transport.proxiedFailures
    val serverErrors = if (transport.httpErrors > proxied) transport.httpErrors - proxied else 0
    return "lectura A-F: A capability ausente " + transport.capabilityMissing +
        "  ·  B endpoint no disponible " + transport.notReady +
        "  ·  C error HTTP del servidor/grid " + serverErrors +
        "  ·  D proxy/transporte " + (transport.transportExceptions + proxied) +
        "  ·  E respuesta valida de 0 bytes " + transport.emptyResponses +
        "  ·  F lectura local " + transport.unclassified +
        "  ·  suma " + transport.failures
}
