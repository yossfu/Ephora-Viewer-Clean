package com.lumiyaviewer.lumiya.slworld

import com.lumiyaviewer.lumiya.slproto.world.ShapeSource
import com.lumiyaviewer.lumiya.slproto.world.WorldModel

/**
 * One simulator's worth of world: its identity plus the objects in it.
 *
 * A region is *not* the same thing as a connection — [SLWorld] holds several of
 * these so that adjacent regions, teleports and region crossings (Phase 7) are
 * a matter of filling a few more of them in, rather than reshaping the viewer.
 *
 * The region handle is Second Life's own 64-bit grid address: the region's grid
 * coordinates in 256 m blocks, packed as `(x << 32) | y`. It comes from the
 * login response, which is also how teleports address a destination.
 */
class SLRegion(
    val handle: Long,
    val gridX: Int,
    val gridY: Int,
    var name: String,
    var id: String,
    var waterHeight: Float
) {

    private val objectsById = HashMap<Int, SLObject>()

    @Volatile
    var lastSyncMillis: Long = 0L

    val objectCount: Int get() = objectsById.size

    fun get(localId: Int): SLObject? = objectsById[localId]

    fun put(object_: SLObject) {
        objectsById[object_.localId] = object_
    }

    fun remove(localId: Int): SLObject? = objectsById.remove(localId)

    fun forEach(action: (SLObject) -> Unit) {
        for (object_ in objectsById.values) {
            action(object_)
        }
    }

    fun objects(): Collection<SLObject> = objectsById.values

    /** Reusable scan buffer, so a sync pass does not allocate a set per tick. */
    private val scratchSeen = HashSet<Int>(1024)

    internal fun beginScan(): HashSet<Int> {
        scratchSeen.clear()
        return scratchSeen
    }

    /** Removes anything not in [seen]; returns the local IDs that went away. */
    internal fun retainAll(seen: Set<Int>): MutableList<Int>? {
        if (objectsById.size == seen.size) {
            var same = true
            for (localId in objectsById.keys) {
                if (!seen.contains(localId)) {
                    same = false
                    break
                }
            }
            if (same) {
                return null
            }
        }
        val removed = ArrayList<Int>()
        val iterator = objectsById.keys.iterator()
        while (iterator.hasNext()) {
            val localId = iterator.next()
            if (!seen.contains(localId)) {
                iterator.remove()
                removed.add(localId)
            }
        }
        return removed
    }

    fun counts(): SLObjectCounts {
        var prims = 0
        var trees = 0
        var avatars = 0
        var unknown = 0
        var withoutPosition = 0
        var withCompleteShape = 0
        var withMissingShape = 0
        var withPersistedShape = 0
        var usingFallback = 0
        for (object_ in objectsById.values) {
            when (object_.kind) {
                SLObjectKind.PRIM -> prims += 1
                SLObjectKind.TREE, SLObjectKind.GRASS -> trees += 1
                SLObjectKind.AVATAR -> avatars += 1
                SLObjectKind.UNKNOWN -> unknown += 1
            }
            if (!object_.positionKnown) {
                withoutPosition += 1
            }
            if (object_.hasCompleteShape) {
                withCompleteShape += 1
            }
            if (object_.prim == null && !object_.kind.equals(SLObjectKind.AVATAR)) {
                withMissingShape += 1
            }
            if (object_.shapeSource == ShapeSource.PERSISTED) {
                withPersistedShape += 1
            }
            // A prim that *displays* a shape while the stored definition is
            // missing: that is the silent default this phase removes. Must be
            // zero, and it is counted from state so a regression shows up in
            // the report instead of hiding.
            if (object_.kind == SLObjectKind.PRIM &&
                object_.shapeText != "UNKNOWN/MISSING_SHAPE" &&
                object_.shapeSource == ShapeSource.MISSING
            ) {
                usingFallback += 1
            }
        }
        return SLObjectCounts(
            prims,
            trees,
            avatars,
            unknown,
            withoutPosition,
            withCompleteShape,
            withMissingShape,
            withPersistedShape,
            usingFallback
        )
    }

    companion object {
        /** A region whose grid position the login response did not provide. */
        const val UNKNOWN_HANDLE = 0L

        fun handleOf(gridX: Int, gridY: Int): Long =
            (gridX.toLong() shl 32) or (gridY.toLong() and 0xFFFFFFFFL)

        /**
         * Creates the snapshot description of the region the session is in.
         * Only fields the simulator actually sent are used; the rest stay at
         * their "unknown" values.
         */
        fun from(model: WorldModel): SLRegion = SLRegion(
            handle = model.regionHandle,
            gridX = model.regionGridX,
            gridY = model.regionGridY,
            name = model.regionName,
            id = model.regionId,
            waterHeight = model.waterHeight
        )
    }
}

class SLObjectCounts(
    val prims: Int,
    val trees: Int,
    val avatars: Int,
    val unknown: Int,
    val withoutPosition: Int,
    /** Objects whose geometry the region has fully described. */
    val withCompleteShape: Int = 0,
    /** Objects still waiting for a geometric definition. */
    val withMissingShape: Int = 0,
    /** Objects whose earlier definition survived later partial updates. */
    val withPersistedShape: Int = 0,
    /** Prims labelled with a placeholder shape instead of a received one (must be 0). */
    val usingFallback: Int = 0
) {
    val total: Int get() = prims + trees + avatars + unknown

    override fun toString(): String = prims.toString() + " prims, " + trees + " arboles, " +
        avatars + " avatares" + (if (unknown > 0) ", " + unknown + " desconocidos" else "") +
        (if (withoutPosition > 0) ", " + withoutPosition + " sin posicion" else "") +
        ", con forma completa " + withCompleteShape +
        (if (withMissingShape > 0) ", sin forma " + withMissingShape else "") +
        (if (withPersistedShape > 0) ", persistida " + withPersistedShape else "") +
        (if (usingFallback > 0) ", FALLBACK " + usingFallback else "")
}
