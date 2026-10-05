package com.lumiyaviewer.lumiya.slworld

import com.lumiyaviewer.lumiya.renderer.MeshDesc
import com.lumiyaviewer.lumiya.slproto.asset.MeshAssetProvider

class MeshPipeline(
    private val provider: MeshAssetProvider
) {

    class MeshReady(
        val meshId: String,
        val desc: MeshDesc,
        val lod: Int,
        val bytes: Int
    )

    private val lock = Object()
    private val seen = HashSet<String>()
    private val queued = HashMap<String, Float>()
    private val inFlight = HashSet<String>()
    private val failed = HashSet<String>()
    private val descs = HashMap<String, MeshDesc>()
    private val ready = ArrayList<MeshReady>()
    private var worker: Thread? = null
    @Volatile private var running = true

    var detected = 0
        private set
    var requested = 0
        private set
    var downloaded = 0
        private set
    var downloadedBytes = 0L
        private set
    var decoded = 0
        private set
    var cacheHits = 0
        private set
    var failedCount = 0
        private set
    var lastFailure: String = ""
        private set

    companion object {
        const val MAX_CACHED_DESCS = 512
    }

    fun noteDetected(meshId: String): Boolean {
        if (meshId.isEmpty()) {
            return false
        }
        synchronized(lock) {
            if (!seen.add(meshId)) {
                return false
            }
            detected += 1
            return true
        }
    }

    fun isFailed(meshId: String): Boolean {
        synchronized(lock) {
            return failed.contains(meshId)
        }
    }

    fun cachedDesc(meshId: String): MeshDesc? {
        synchronized(lock) {
            return descs[meshId]
        }
    }

    fun noteCacheHit() {
        synchronized(lock) {
            cacheHits += 1
        }
    }

    fun request(meshId: String, distanceMeters: Float) {
        if (meshId.isEmpty()) {
            return
        }
        synchronized(lock) {
            if (seen.add(meshId)) {
                detected += 1
            }
            if (descs.containsKey(meshId) || failed.contains(meshId) || inFlight.contains(meshId)) {
                return
            }
            val current = queued[meshId]
            if (current == null || distanceMeters < current) {
                queued[meshId] = distanceMeters
            }
            lock.notifyAll()
        }
        ensureWorker()
    }

    fun pump(maxEvents: Int): List<MeshReady> {
        synchronized(lock) {
            if (ready.isEmpty()) {
                return emptyList()
            }
            val take = minOf(maxEvents, ready.size)
            val out = ArrayList<MeshReady>(take)
            repeat(take) { out.add(ready.removeAt(0)) }
            return out
        }
    }

    fun noteFailed(meshId: String, reason: String) {
        if (meshId.isEmpty()) {
            return
        }
        synchronized(lock) {
            if (!failed.add(meshId)) {
                return
            }
            failedCount += 1
            lastFailure = meshId.take(8) + ": " + reason
        }
    }

    fun queueSize(): Int {
        synchronized(lock) {
            return queued.size + inFlight.size
        }
    }

    fun stop() {
        running = false
        synchronized(lock) {
            lock.notifyAll()
        }
    }

    private fun ensureWorker() {
        synchronized(lock) {
            if (worker == null) {
                val thread = Thread({ workerLoop() }, "EphoraMesh")
                thread.isDaemon = true
                worker = thread
                thread.start()
            }
        }
    }

    private fun workerLoop() {
        while (running) {
            val next: String? = synchronized(lock) {
                while (running && queued.isEmpty()) {
                    try {
                        lock.wait(500)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                if (!running) {
                    null
                } else {
                    var best: String? = null
                    var bestDist = Float.MAX_VALUE
                    for ((id, dist) in queued) {
                        if (dist < bestDist) {
                            bestDist = dist
                            best = id
                        }
                    }
                    if (best != null) {
                        queued.remove(best)
                        inFlight.add(best)
                    }
                    best
                }
            }
            if (next == null) {
                continue
            }
            if (!running) {
                synchronized(lock) {
                    inFlight.remove(next)
                }
                continue
            }
            fetchOne(next)
        }
    }

    private fun fetchOne(meshId: String) {
        synchronized(lock) {
            requested += 1
        }
        var reason: String? = null
        try {
            if (!provider.isReady) {
                reason = "sin capability de mallas"
            } else {
                val headerReply = provider.fetchHeader(meshId)
                val headerBytes = headerReply?.bytes
                if (headerBytes == null || headerBytes.isEmpty()) {
                    reason = "cabecera sin respuesta"
                } else {
                    val parsed = MeshDecoder.parseHeader(headerBytes)
                    val header = parsed.header
                    if (header == null) {
                        reason = parsed.error ?: "cabecera ilegible"
                    } else {
                        val lod = header.bestLod()
                        if (lod < 0) {
                            reason = "cabecera sin LOD util"
                        } else {
                            val abs = header.headerSize.toLong() + header.lodOffset[lod].toLong()
                            val size = header.lodSize[lod].toLong()
                            if (abs < 0 || size <= 0 || size > MeshDecoder.MAX_MESH_BYTES) {
                                reason = "LOD fuera de rango (" + size + " bytes)"
                            } else {
                                val lodBytes = sliceOrFetch(meshId, headerBytes, abs, size)
                                if (lodBytes == null) {
                                    reason = "LOD sin respuesta"
                                } else {
                                    synchronized(lock) {
                                        downloaded += 1
                                        downloadedBytes += lodBytes.size.toLong()
                                    }
                                    val decodedLod = MeshDecoder.decodeLod(lodBytes, meshId)
                                    val desc = decodedLod.desc
                                    if (desc == null) {
                                        reason = decodedLod.error ?: "LOD ilegible"
                                    } else {
                                        synchronized(lock) {
                                            decoded += 1
                                            if (descs.size < MAX_CACHED_DESCS) {
                                                descs[meshId] = desc
                                            }
                                            ready.add(MeshReady(meshId, desc, lod, lodBytes.size))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            reason = t.message ?: t.javaClass.simpleName
        }
        synchronized(lock) {
            inFlight.remove(meshId)
            if (reason != null && !descs.containsKey(meshId)) {
                if (failed.add(meshId)) {
                    failedCount += 1
                    lastFailure = meshId.take(8) + ": " + reason
                }
            }
        }
    }

    private fun sliceOrFetch(
        meshId: String,
        headerBytes: ByteArray,
        abs: Long,
        size: Long
    ): ByteArray? {
        if (abs + size <= headerBytes.size.toLong()) {
            return headerBytes.copyOfRange(abs.toInt(), (abs + size).toInt())
        }
        val reply = provider.fetchRange(meshId, abs, size) ?: return null
        val bytes = reply.bytes
        if (bytes.isEmpty()) {
            return null
        }
        if (!reply.partial) {
            if (bytes.size < abs + size) {
                return null
            }
            return bytes.copyOfRange(abs.toInt(), (abs + size).toInt())
        }
        if (bytes.size < size) {
            return null
        }
        return if (bytes.size == size.toInt()) bytes else bytes.copyOfRange(0, size.toInt())
    }
}
