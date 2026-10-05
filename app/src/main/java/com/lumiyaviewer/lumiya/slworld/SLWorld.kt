package com.lumiyaviewer.lumiya.slworld

import com.lumiyaviewer.lumiya.slproto.world.WorldModel
import com.lumiyaviewer.lumiya.slscene.PrimGeometryNative

/**
 * What changed since the last look at the region.
 *
 * The world layer is deliberately a *pull* model: [SLWorld.sync] reads the
 * protocol's [WorldModel] and reports what is new, changed or gone. Nothing is
 * pushed across threads, so there is no lock anywhere in the render path — the
 * consumer decides when it wants to look.
 */
class SLWorldDelta(
    val region: SLRegion,
    val added: List<SLObject>,
    val updated: List<SLObject>,
    val removed: List<Int>,
    val avatars: Int,
    val regionChanged: Boolean,
    /**
     * Terrain as it stood when this change set was produced, or null when the
     * terrain did not change. Copied, not shared: see [SLTerrainSnapshot].
     */
    val terrain: SLTerrainSnapshot? = null,
    /** Bounding box of every object with a known position (Z-up, region metres). */
    val boundsMin: FloatArray = EMPTY_BOUNDS,
    val boundsMax: FloatArray = EMPTY_BOUNDS,
    /** False when no object had a position, so the bounds mean nothing. */
    val boundsKnown: Boolean = false,
    /** The region's own counters, for the debug HUD. */
    val counts: SLObjectCounts? = null
) {
    val isEmpty: Boolean
        get() = added.isEmpty() && updated.isEmpty() && removed.isEmpty() && terrain == null

    override fun toString(): String = "+" + added.size + " ~" + updated.size + " -" + removed.size +
        " (avatares " + avatars + ")" + (if (terrain != null) " +terreno" else "")

    companion object {
        val EMPTY_BOUNDS = floatArrayOf(0f, 0f, 0f)
    }
}

/**
 * The viewer's world: one or more regions, each with the objects Second Life
 * told us are in it.
 *
 * [SLWorld] sits between the protocol (`slproto.world.WorldModel`, which the
 * network coroutines write) and the scene (`slscene.SLScene`, which talks to the
 * renderer). It is the only place where "what the region sent" becomes "what we
 * are going to draw", and it does no drawing itself.
 *
 * Regions are keyed by their 64-bit handle so that Phase 7's adjacent regions,
 * teleports and region crossings are additive: the region you are leaving
 * becomes just another entry in [regions] instead of being thrown away.
 */
class SLWorld {

    /** Tessellation level for generated prim geometry (LOD selection is Phase 11). */
    var detailLevel: Int = PrimGeometryNative.DETAIL_STANDARD

    private val regionsByHandle = LinkedHashMap<Long, SLRegion>()

    var activeRegion: SLRegion? = null
        private set

    private var lastModelVersion = Int.MIN_VALUE
    private var lastRegionHandle = Long.MIN_VALUE
    private var lastTerrainPatchCount = -1
    private var lastTerrainVersion = -1

    /** Objects we looked at during the last full scan. */
    var scanned = 0
        private set

    /** Snapshots we had to rebuild (the object actually changed). */
    var rebuilt = 0
        private set

    var syncCount = 0
        private set

    /**
     * Shared debug record. Optional: the harness and the test scenes run without
     * one, and every write is guarded by the nullable receiver.
     */
    var diagnostics: com.lumiyaviewer.lumiya.renderer.RenderDiagnostics? = null

    val regions: Collection<SLRegion> get() = regionsByHandle.values

    fun region(handle: Long): SLRegion? = regionsByHandle[handle]

    /**
     * Reads the protocol's state and returns what changed, or null when there is
     * nothing to do. Cheap to call every frame: it early-outs on the model's
     * version counter.
     */
    fun sync(model: WorldModel): SLWorldDelta? {
        val region = ensureRegion(model)
        val regionChanged = region.handle != lastRegionHandle
        if (regionChanged) {
            lastRegionHandle = region.handle
            lastModelVersion = Int.MIN_VALUE
            lastTerrainPatchCount = -1
            lastTerrainVersion = -1
        }
        val version = model.version
        // Terrain is not part of the object version counter: `LayerData` patches
        // bump their own. Without this test a region whose terrain arrived but
        // whose objects did not change would never be looked at again.
        val terrain = model.terrain
        val terrainChanged = terrain.hasData &&
            (terrain.patchCount != lastTerrainPatchCount || terrain.version != lastTerrainVersion)
        if (version == lastModelVersion && !regionChanged && !terrainChanged) {
            return null
        }
        lastModelVersion = version
        syncCount += 1

        val added = ArrayList<SLObject>()
        val updated = ArrayList<SLObject>()
        val seen = region.beginScan()
        var scannedHere = 0
        var avatarsHere = 0
        var withoutPositionHere = 0
        val boundsMin = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val boundsMax = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        model.forEach { sceneObject ->
            val localId = sceneObject.localId
            seen.add(localId)
            scannedHere += 1
            if (sceneObject.isAvatar) {
                avatarsHere += 1
            }
            if (sceneObject.positionKnown) {
                val position = sceneObject.position
                if (position.x < boundsMin[0]) boundsMin[0] = position.x
                if (position.y < boundsMin[1]) boundsMin[1] = position.y
                if (position.z < boundsMin[2]) boundsMin[2] = position.z
                if (position.x > boundsMax[0]) boundsMax[0] = position.x
                if (position.y > boundsMax[1]) boundsMax[1] = position.y
                if (position.z > boundsMax[2]) boundsMax[2] = position.z
            } else {
                withoutPositionHere += 1
            }
            val existing = region.get(localId)
            if (existing != null && existing.revision == sceneObject.revision) {
                return@forEach
            }
            val snapshot = SLObject.from(sceneObject, detailLevel)
            if (existing == null) {
                added.add(snapshot)
            } else {
                updated.add(snapshot)
                rebuilt += 1
            }
            region.put(snapshot)
        }
        scanned = scannedHere
        val removed = region.retainAll(seen) ?: emptyList()
        region.lastSyncMillis = System.currentTimeMillis()
        val counts = region.counts()
        val boundsKnown = scannedHere > 0 && withoutPositionHere < scannedHere

        val terrainSnapshot = if (terrainChanged) {
            lastTerrainPatchCount = terrain.patchCount
            lastTerrainVersion = terrain.version
            SLTerrainSnapshot(
                regionHandle = region.handle,
                version = terrain.version,
                patchCount = terrain.patchCount,
                size = terrain.size,
                minHeight = terrain.minHeight,
                maxHeight = terrain.maxHeight,
                // Copied: the network threads keep writing into the model's grid
                // while the render thread reads this.
                heights = terrain.heights.copyOf()
            )
        } else {
            null
        }

        diagnostics?.let { d ->
            d.modelObjects = model.size
            d.regionObjects = region.objectCount
            d.prims = counts.prims
            d.trees = counts.trees
            d.avatars = counts.avatars
            d.unknownKind = counts.unknown
            d.withoutPosition = counts.withoutPosition
            d.syncCount = syncCount
            d.updateFull = model.fullUpdates
            d.updateCompressed = model.compressedUpdates
            d.updateTerse = model.terseUpdates
            d.updateKilled = model.killCount
            d.compressedWithoutHeader = model.compressedWithoutHeader
            d.compressedWithoutParams = model.compressedWithoutParams
            d.parseFailures = model.parseFailures
            // The census of the stored shape state, plus the parser's own
            // report. Kept separate from the update counters on purpose: "548
            // prims recibidos" and "N con forma completa" are different facts.
            val shapes = model.shapeCounts()
            d.objectsReceived = shapes.received
            d.objectsWithCompleteShape = shapes.withCompleteShape
            d.objectsWithPartialState = shapes.withPartialState
            d.objectsWithMissingShape = shapes.withMissingShape
            d.objectsWithPersistedShape = shapes.withPersistedShape
            d.objectsUsingShapeFallback = shapes.usingShapeFallback
            d.parserReport = com.lumiyaviewer.lumiya.slproto.world.ObjectUpdateDiagnostics
                .lines()
                .joinToString("\n")
            d.terrainReady = terrain.hasData
            d.terrainPatches = terrain.patchCount
            d.terrainMinHeight = terrain.minHeight
            d.terrainMaxHeight = terrain.maxHeight
            d.terrainVersion = terrain.version
            d.agentPositionKnown = model.agentPositionKnown
            if (boundsKnown) {
                d.recordWorldBounds(boundsMin, boundsMax)
            }
        }

        return SLWorldDelta(
            region = region,
            added = added,
            updated = updated,
            removed = removed,
            avatars = counts.avatars,
            regionChanged = regionChanged,
            terrain = terrainSnapshot,
            boundsMin = boundsMin,
            boundsMax = boundsMax,
            boundsKnown = boundsKnown,
            counts = counts
        )
    }

    /** Forgets a region's contents, e.g. after a teleport to a different one. */
    fun clearRegion(handle: Long) {
        regionsByHandle.remove(handle)
        if (activeRegion?.handle == handle) {
            activeRegion = null
        }
    }

    /**
     * Forgets every region snapshot, so the next [sync] reports the whole region
     * again as "added". Used when the scene threw everything away (the debug
     * content switch): without it, objects whose revision has not moved would
     * never be re-created.
     */
    fun reset() {
        for (handle in regionsByHandle.keys.toList()) {
            clearRegion(handle)
        }
        activeRegion = null
        lastRegionHandle = Long.MIN_VALUE
        lastModelVersion = Int.MIN_VALUE
        lastTerrainPatchCount = -1
        lastTerrainVersion = -1
    }

    private fun ensureRegion(model: WorldModel): SLRegion {
        val handle = model.regionHandle
        val existing = regionsByHandle[handle]
        if (existing != null) {
            existing.name = model.regionName
            if (model.regionId.isNotEmpty()) {
                existing.id = model.regionId
            }
            activeRegion = existing
            return existing
        }
        val created = SLRegion.from(model)
        regionsByHandle[handle] = created
        activeRegion = created
        return created
    }
}
