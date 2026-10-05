package com.lumiyaviewer.lumiya.slproto.asset

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The texture cache: one entry per (UUID, discard level), and the only place
 * that decides whether a texture has to be fetched.
 *
 * It answers the four questions the pipeline needs, and nothing else:
 *
 *  * **hit** — [state] is `READY` or `PENDING`: do not fetch again.
 *  * **miss** — [state] is `UNKNOWN`: [markPending] starts a request and returns
 *    `true` exactly once per UUID.
 *  * **pending** — [markPending] returns `false` while a request is in flight, so
 *    two callers that ask for the same texture in the same frame do not both go
 *    to the grid.
 *  * **failed** — [fail] records why, and [retry] moves the entry back to
 *    `UNKNOWN` so the next [markPending] fetches it again.
 *
 * Fase 2.13a stores only the raw bytes ([TextureAsset]); the decoded image and
 * the GPU texture are added in 2.13c/2.13d. Nothing here downloads, decodes or
 * uploads — the bytes arrive through [put], and the counters in [stats] say what
 * has actually happened, never what is planned.
 *
 * 2.13b does not change any of that: the download itself lives in
 * [GetTextureAssetProvider] and the decode in [TexturePipeline], and both report
 * into their own books, because this class stays the single place that decides
 * *whether* a fetch is needed.
 */
class TextureAssetCache {

    private val assets = ConcurrentHashMap<String, TextureAsset>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, String>()
    /**
     * 2.27 (estabilidad): mapas concurrentes porque los workers de decode
     * leen (`asset`) mientras el hilo de render escribe (`put`,
     * `evictExcess`, `clear`). Con HashMap planos esa combinacion puede
     * colgar o devolver null durante un resize.
     * 2.26 (estabilidad): LRU de codestreams. Sin esto los bytes descargados
     * se retenian para siempre (~157 MiB observados) hasta tumbar el proceso.
     * La eviccion devuelve la entrada a UNKNOWN: si la cara vuelve a verse se
     * re-descarga (correcto, nunca se inventan pixeles).
     */
    private val lastUse = ConcurrentHashMap<String, Long>()
    /**
     * 2.27: total de bytes retenidos, O(1). Antes `cachedBytes` recorria todo
     * el mapa en cada llamada y `evictExcess` lo hacia por iteracion (O(n^2)
     * por frame del hilo de render).
     */
    private val bytesTotal = AtomicLong(0L)

    /** Tope de bytes retenidos en codestreams; 0 = sin tope (solo tests). */
    var maxBytes: Long = DEFAULT_MAX_BYTES

    /** Entradas expulsadas por el tope desde la creacion del cache. */
    var evicted = 0
        private set

    /** Bytes liberados por expulsion desde la creacion del cache. */
    var evictedBytes = 0L
        private set

    val stats = TextureAssetStats()

    /** The state of one texture, never blocking. */
    fun state(textureId: String, discardLevel: Int = 0): TextureAssetState {
        val key = keyOf(textureId, discardLevel)
        if (assets.containsKey(key)) {
            return TextureAssetState.READY
        }
        if (pending.contains(key)) {
            return TextureAssetState.PENDING
        }
        if (failures.containsKey(key)) {
            return TextureAssetState.FAILED
        }
        return TextureAssetState.UNKNOWN
    }

    /**
     * Starts a request when the texture is not already known. Returns `true`
     * exactly once per UUID/discard (the miss), `false` while it is pending, known
     * or failed.
     */
    fun markPending(textureId: String, discardLevel: Int = 0): Boolean {
        if (textureId.isEmpty()) {
            return false
        }
        val key = keyOf(textureId, discardLevel)
        if (assets.containsKey(key) || pending.contains(key) || failures.containsKey(key)) {
            stats.cacheHits += 1
            touch(key)
            return false
        }
        pending.add(key)
        stats.cacheMisses += 1
        stats.requested += 1
        return true
    }

    /** Stores the bytes of a finished download. */
    fun put(asset: TextureAsset): Boolean {
        val key = keyOf(asset.textureId, asset.discardLevel)
        pending.remove(key)
        failures.remove(key)
        val previous = assets.put(key, asset)
        if (previous != null) {
            bytesTotal.addAndGet(asset.size.toLong() - previous.size.toLong())
            touch(key)
            return false
        }
        stats.downloaded += 1
        stats.bytesDownloaded += asset.size.toLong()
        bytesTotal.addAndGet(asset.size.toLong())
        touch(key)
        return true
    }

    /** Records a failure and why. A later [retry] makes the texture fetchable again. */
    fun fail(textureId: String, discardLevel: Int = 0, reason: String): Boolean {
        val key = keyOf(textureId, discardLevel)
        pending.remove(key)
        if (assets.containsKey(key)) {
            return false
        }
        failures[key] = reason
        stats.failed += 1
        return true
    }

    /** Puts a failed texture back in the queue. */
    fun retry(textureId: String, discardLevel: Int = 0): Boolean {
        val key = keyOf(textureId, discardLevel)
        if (failures.remove(key) == null) {
            return false
        }
        stats.retried += 1
        return true
    }

    fun asset(textureId: String, discardLevel: Int = 0): TextureAsset? {
        val found = assets[keyOf(textureId, discardLevel)]
        if (found != null) {
            touch(keyOf(textureId, discardLevel))
        }
        return found
    }

    /** Why a texture failed, or "" when it did not. */
    fun failureOf(textureId: String, discardLevel: Int = 0): String =
        failures[keyOf(textureId, discardLevel)] ?: ""

    /** Textures holding bytes right now. */
    val count: Int get() = assets.size

    /** Requests that went out and have not been answered. */
    val pendingCount: Int get() = pending.size

    /** Textures the grid refused (they stay refusable through [retry]). */
    val failedCount: Int get() = failures.size

    /** How much memory the cached codestreams take, for the report. */
    val cachedBytes: Long
        get() = bytesTotal.get()

    fun clear() {
        assets.clear()
        pending.clear()
        failures.clear()
        lastUse.clear()
        bytesTotal.set(0L)
        stats.reset()
    }

    /**
     * 2.26: claves con descarga en curso (para cancelar las de objetos que ya
     * no estan en escena sin tocar las vivas).
     */
    fun pendingKeys(): Set<String> = HashSet(pending)

    /**
     * 2.26: retira una descarga en curso del cache. El wire se cancela aparte
     * ([TexturePipeline.cancelDead]); aqui la entrada vuelve a UNKNOWN.
     */
    fun unpend(key: String): Boolean {
        lastUse.remove(key)
        return pending.remove(key)
    }

    /**
     * 2.26: expulsa los codestreams menos usados hasta volver bajo [maxBytes].
     * [skip] protege claves que un worker esta decodificando ahora mismo (el
     * hilo de render las conoce; el worker solo lee). Solo hilo de render.
     * 2.27: una sola pasada con total local (antes O(n^2): `cachedBytes`
     * recorria el mapa en cada iteracion, una vez por frame).
     */
    fun evictExcess(skip: Set<String> = emptySet()): Int {
        val cap = maxBytes
        if (cap <= 0 || assets.isEmpty()) {
            return 0
        }
        var total = bytesTotal.get()
        if (total <= cap) {
            return 0
        }
        val ordered = assets.keys.sortedBy { lastUse[it] ?: 0L }
        var freed = 0
        for (key in ordered) {
            if (total <= cap) {
                break
            }
            if (skip.contains(key)) {
                continue
            }
            val removed = assets.remove(key) ?: continue
            lastUse.remove(key)
            total -= removed.size.toLong()
            evicted += 1
            evictedBytes += removed.size.toLong()
            freed += 1
        }
        bytesTotal.set(total)
        return freed
    }

    private fun touch(key: String) {
        lastUse[key] = System.nanoTime()
    }

    private companion object {
        /** 2.26: 64 MiB de codestreams J2K retenidos; el resto se re-descarga. */
        const val DEFAULT_MAX_BYTES = 64L * 1024L * 1024L
    }

    private fun keyOf(textureId: String, discardLevel: Int): String =
        textureId + "@" + discardLevel
}
