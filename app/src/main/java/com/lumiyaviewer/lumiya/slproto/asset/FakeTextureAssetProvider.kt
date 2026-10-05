package com.lumiyaviewer.lumiya.slproto.asset

/**
 * The stand-in provider for fase 2.13a: it implements the real interface and
 * **never touches the network**.
 *
 * It exists so the parser, the cache and (later) the decode/upload stages can be
 * driven end to end in the harness and on the device before the `GetTexture`
 * capability is wired in 2.13b. A request is only remembered; the bytes have to
 * be handed to it explicitly ([deliver]) or refused ([fail]), which is exactly
 * what a test needs to reproduce "arrived", "too big", "404" and "never answered"
 * without a grid.
 *
 * 2.13b added the real one ([GetTextureAssetProvider], over the `GetTexture`
 * capability). This class stays as the test double it was written to be, and
 * nothing about it had to change when the real provider arrived — the interface,
 * the result type and the cache are the same on both sides of the seam.
 */
class FakeTextureAssetProvider : TextureAssetProvider {

    private val outstanding = LinkedHashMap<String, String>()
    private val completed = ArrayList<TextureAssetResult>()

    /**
     * The same counters the real provider keeps, so a test (or a first run with
     * no grid) can read the report's transport numbers and get something
     * meaningful rather than zeros. Timings are zero: this provider answers
     * instantly because it does not go anywhere.
     */
    override val transportStats = TextureTransportStats()

    override val inFlight: Int get() = outstanding.size

    /** Requests seen, including repeats (the cache is supposed to stop those). */
    var requests = 0
        private set

    var cancelled = 0
        private set

    override fun request(textureId: String, discardLevel: Int) {
        if (textureId.isEmpty()) {
            return
        }
        requests += 1
        transportStats.noteRequest()
        outstanding[keyOf(textureId, discardLevel)] = textureId
    }

    override fun cancel(textureId: String) {
        val removed = outstanding.keys.filter { it.startsWith(textureId + "@") }
        for (key in removed) {
            outstanding.remove(key)
            cancelled += 1
            transportStats.noteCancel(countedInFlight = true)
        }
    }

    override fun drainCompleted(): List<TextureAssetResult> {
        if (completed.isEmpty()) {
            return emptyList()
        }
        val drained = ArrayList<TextureAssetResult>(completed)
        completed.clear()
        return drained
    }

    /** Test hook: the texture "arrived" with these bytes. */
    fun deliver(textureId: String, bytes: ByteArray, discardLevel: Int = 0, source: String = "fake"): Boolean {
        val key = keyOf(textureId, discardLevel)
        if (!outstanding.containsKey(key)) {
            return false
        }
        outstanding.remove(key)
        transportStats.noteResponse(bytes.size, 0L)
        completed.add(TextureAssetResult.Ready(TextureAsset(textureId, bytes, discardLevel, source)))
        return true
    }

    /** Test hook: the texture "failed" for a reason. */
    fun fail(textureId: String, reason: String, discardLevel: Int = 0): Boolean {
        val key = keyOf(textureId, discardLevel)
        if (!outstanding.containsKey(key)) {
            return false
        }
        outstanding.remove(key)
        // A plain sentence with no record behind it: the counters classify it as
        // "sin clasificar" so it still adds up, and the report prints the sentence.
        transportStats.noteFailure(
            TextureFetchFailure(
                kind = TextureFailureKind.UNKNOWN,
                capability = "proveedor de prueba",
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
            ),
            textureId,
            0L
        )
        completed.add(TextureAssetResult.Failed(textureId, discardLevel, reason))
        return true
    }

    /** Requests that have neither arrived nor failed. */
    val pendingCount: Int get() = outstanding.size

    private fun keyOf(textureId: String, discardLevel: Int): String = textureId + "@" + discardLevel
}
