package com.lumiyaviewer.lumiya.slproto.world

import com.lumiyaviewer.lumiya.slproto.base.Vector3
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything the viewer knows about the region's contents. The network threads
 * write here and the renderer reads; a `ConcurrentHashMap` plus a version
 * counter keeps that safe without locking the render loop.
 */
class WorldModel {

    private val objects = ConcurrentHashMap<Int, SceneObject>()
    private val objectVersion = AtomicInteger(0)

    val terrain = TerrainData()

    @Volatile var regionName: String = ""
    @Volatile var regionId: String = ""
    /**
     * The region's grid address: its coordinates in 256 m blocks, packed as
     * `(x << 32) | y`. This is Second Life's own region handle — the same
     * value teleports use to name a destination region.
     */
    @Volatile var regionHandle: Long = 0L
    @Volatile var regionGridX: Int = 0
    @Volatile var regionGridY: Int = 0
    @Volatile var waterHeight: Float = 20f
    @Volatile var agentLocalId: Int = -1
    @Volatile var agentPosition: Vector3 = Vector3.ZERO
    @Volatile var agentPositionKnown: Boolean = false
    @Volatile var agentHeadingRadians: Float = 0f

    var fullUpdates = 0
        private set
    var compressedUpdates = 0
        private set
    var terseUpdates = 0
        private set
    var killCount = 0
        private set

    /** Compressed blobs too short to even hold the fixed header. */
    var compressedWithoutHeader = 0
        private set

    /** Compressed blobs that carried placement but no path/profile parameters. */
    var compressedWithoutParams = 0
        private set

    /** Compressed blocks (and full-update blocks) the parser could not decode at all. */
    var parseFailures = 0
        private set

    fun noteCompressedWithoutHeader() {
        compressedWithoutHeader += 1
    }

    fun noteCompressedWithoutParams() {
        compressedWithoutParams += 1
    }

    fun noteParseFailure() {
        parseFailures += 1
    }

    val version: Int
        get() = objectVersion.get()

    val size: Int
        get() = objects.size

    fun get(localId: Int): SceneObject? = objects[localId]

    fun getOrCreate(localId: Int): SceneObject {
        val existing = objects[localId]
        if (existing != null) {
            return existing
        }
        val created = SceneObject(localId)
        val raced = objects.putIfAbsent(localId, created)
        return raced ?: created
    }

    fun put(updated: SceneObject) {
        updated.lastUpdateMillis = System.currentTimeMillis()
        // The revision only ever moves forward, even when the caller hands us a
        // freshly built record instead of the one already in the map: consumers
        // (the scene) use it to tell "changed" from "unchanged", so a lower
        // number would silently hide an update.
        val previous = objects[updated.localId]
        updated.revision = maxOf(previous?.revision ?: 0, updated.revision) + 1
        objects[updated.localId] = updated
        objectVersion.incrementAndGet()
    }

    fun remove(localId: Int) {
        if (objects.remove(localId) != null) {
            killCount += 1
            objectVersion.incrementAndGet()
        }
    }

    fun clear() {
        objects.clear()
        objectVersion.incrementAndGet()
    }

    fun noteFullUpdate() {
        fullUpdates += 1
        objectVersion.incrementAndGet()
    }

    fun noteCompressedUpdate() {
        compressedUpdates += 1
        objectVersion.incrementAndGet()
    }

    fun noteTerseUpdate() {
        terseUpdates += 1
        objectVersion.incrementAndGet()
    }

    fun forEach(action: (SceneObject) -> Unit) {
        for (sceneObject in objects.values) {
            action(sceneObject)
        }
    }

    /** Nearest objects to a position, closest first (used to bound the draw list). */
    fun nearest(position: Vector3, limit: Int, maxDistance: Float, into: MutableList<SceneObject>): List<SceneObject> {
        into.clear()
        val maxSquared = maxDistance * maxDistance
        for (sceneObject in objects.values) {
            if (!sceneObject.positionKnown) {
                continue
            }
            val dx = sceneObject.position.x - position.x
            val dy = sceneObject.position.y - position.y
            val dz = sceneObject.position.z - position.z
            val distance = dx * dx + dy * dy + dz * dz
            if (distance > maxSquared) {
                continue
            }
            into.add(sceneObject)
        }
        into.sortBy { candidate ->
            val dx = candidate.position.x - position.x
            val dy = candidate.position.y - position.y
            dx * dx + dy * dy
        }
        if (into.size > limit) {
            return into.subList(0, limit)
        }
        return into
    }

    fun avatars(into: MutableList<SceneObject>): List<SceneObject> {
        into.clear()
        for (sceneObject in objects.values) {
            if (sceneObject.isAvatar) {
                into.add(sceneObject)
            }
        }
        return into
    }

    fun counts(): String {
        var avatars = 0
        var trees = 0
        var grass = 0
        var prims = 0
        var other = 0
        for (sceneObject in objects.values) {
            when {
                sceneObject.isAvatar -> avatars += 1
                sceneObject.isGrass -> grass += 1
                sceneObject.isTree -> trees += 1
                sceneObject.isPrim -> prims += 1
                else -> other += 1
            }
        }
        return prims.toString() + " prims, " + trees + " arboles, " + grass + " hierba, " +
            avatars + " avatares" + (if (other > 0) ", " + other + " otros pcodes" else "")
    }

    /**
     * How many objects have a usable geometric definition and how many are still
     * waiting for one.
     *
     * This is the set of numbers the incremental model is judged by, and they
     * are counted from the stored state rather than from the update stream:
     * "548 prims recibidos" and "13 convertidos" are answers to different
     * questions, and mixing them is how a decoding bug stayed invisible.
     */
    fun shapeCounts(): ShapeCounts {
        var complete = 0
        var partial = 0
        var missingShape = 0
        var fallback = 0
        var persisted = 0
        var fromFull = 0
        var fromCompressed = 0
        var avatars = 0
        for (sceneObject in objects.values) {
            if (sceneObject.isAvatar) {
                avatars += 1
            }
            if (sceneObject.hasCompleteShape) {
                complete += 1
            }
            if (!sceneObject.paramsKnown && !sceneObject.isAvatar) {
                partial += 1
            }
            if (sceneObject.isPrim && !sceneObject.paramsKnown) {
                missingShape += 1
            }
            if (sceneObject.shapeIsFallback) {
                fallback += 1
            }
            when (sceneObject.shapeSource) {
                ShapeSource.PERSISTED -> persisted += 1
                ShapeSource.RECEIVED_FULL -> fromFull += 1
                ShapeSource.RECEIVED_COMPRESSED -> fromCompressed += 1
                else -> Unit
            }
        }
        return ShapeCounts(
            received = objects.size,
            withCompleteShape = complete,
            withPartialState = partial,
            withMissingShape = missingShape,
            usingShapeFallback = fallback,
            withPersistedShape = persisted,
            shapeFromFull = fromFull,
            shapeFromCompressed = fromCompressed,
            avatars = avatars
        )
    }
}

/**
 * The state-of-shape census of one region, as stored right now.
 *
 * [usingShapeFallback] must be **zero**: it counts prims that would be labelled
 * with the constructor's placeholder path/profile values instead of a received
 * definition. It is computed from real state (not asserted), so if a future
 * change reintroduces a silent default, the report shows it.
 */
class ShapeCounts(
    val received: Int,
    val withCompleteShape: Int,
    val withPartialState: Int,
    val withMissingShape: Int,
    val usingShapeFallback: Int,
    val withPersistedShape: Int,
    val shapeFromFull: Int,
    val shapeFromCompressed: Int,
    val avatars: Int
) {
    override fun toString(): String = "recibidos " + received +
        ", con forma completa " + withCompleteShape +
        ", estado parcial " + withPartialState +
        ", sin forma " + withMissingShape +
        ", usando fallback " + usingShapeFallback +
        ", persistida " + withPersistedShape
}
