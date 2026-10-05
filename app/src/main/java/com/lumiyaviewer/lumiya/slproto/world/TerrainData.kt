package com.lumiyaviewer.lumiya.slproto.world

/**
 * Region terrain as a 256x256 grid of metres, filled in from the simulator's
 * `LayerData` patches. The patches are bit-packed DCT blocks (see
 * [TerrainCodec]); each one carries absolute heights in metres for a 16x16
 * square of the region, and they arrive a few at a time.
 */
class TerrainData {

    val size = 256
    val heights = FloatArray(size * size)

    // These are written by the network threads and read by the render thread
    // (which copies the grid into a snapshot when they move), so they are
    // volatile: the volatile write is what orders the height writes that came
    // before it, and without that the render thread could see a half-filled
    // grid.
    @Volatile
    var version = 0
        private set

    @Volatile
    var hasData = false
        private set

    @Volatile
    var minHeight = 0f
        private set

    @Volatile
    var maxHeight = 0f
        private set

    @Volatile
    var patchCount = 0
        private set
    private var lastBumpPatchCount = 0
    var lastError = ""
        private set

    fun heightAt(x: Float, y: Float): Float {
        if (!hasData) {
            return 0f
        }
        val ix = x.toInt().coerceIn(0, size - 1)
        val iy = y.toInt().coerceIn(0, size - 1)
        return heights[iy * size + ix]
    }

    fun clear() {
        heights.fill(0f)
        hasData = false
        minHeight = 0f
        maxHeight = 0f
        patchCount = 0
        lastBumpPatchCount = 0
        version += 1
    }

    /**
     * Applies a `LayerData.Data` blob. Returns false when the blob carried
     * nothing usable (in which case nothing is changed and [lastError] says
     * why, so the diagnostics screen can show it).
     */
    fun applyLayerData(data: ByteArray): Boolean {
        val decoded = TerrainCodec.decodeLayerData(data)
        if (decoded == null) {
            lastError = "blob LayerData ilegible (" + data.size + " bytes)"
            return false
        }
        if (decoded.layerType != TerrainCodec.LAND) {
            lastError = "capa " + decoded.layerType + " (no es terreno)"
            return false
        }
        if (decoded.patchSize != TerrainCodec.PATCH_SIZE) {
            lastError = "tamano de parche " + decoded.patchSize + " (se esperaba 16)"
            return false
        }
        if (decoded.patches.isEmpty()) {
            lastError = "el mensaje LayerData no contenia parches"
            return false
        }

        var localMin = Float.MAX_VALUE
        var localMax = -Float.MAX_VALUE
        for (patch in decoded.patches) {
            val baseX = patch.x * TerrainCodec.PATCH_SIZE
            val baseY = patch.y * TerrainCodec.PATCH_SIZE
            for (row in 0 until TerrainCodec.PATCH_SIZE) {
                val worldY = baseY + row
                for (column in 0 until TerrainCodec.PATCH_SIZE) {
                    val worldX = baseX + column
                    val raw = patch.heights[row * TerrainCodec.PATCH_SIZE + column]
                    if (raw < localMin) localMin = raw
                    if (raw > localMax) localMax = raw
                    if (worldX < size && worldY < size) {
                        heights[worldY * size + worldX] =
                            raw.coerceIn(MIN_PLAUSIBLE_HEIGHT, MAX_PLAUSIBLE_HEIGHT)
                    }
                }
            }
        }
        if (!localMin.isFinite() || !localMax.isFinite()) {
            lastError = "alturas no finitas"
            return false
        }
        // Regions do reach a few hundred metres, so this only rejects a decode
        // that went wrong badly enough to produce nonsense.
        if (localMax > MAX_PLAUSIBLE_HEIGHT || localMin < MIN_PLAUSIBLE_HEIGHT) {
            lastError = "alturas poco razonables (" + localMin + " .. " + localMax + ")"
            return false
        }

        hasData = true
        val firstBlob = patchCount == 0
        patchCount += decoded.patches.size
        if (patchCount >= lastBumpPatchCount + VERSION_BUMP_PATCHES || patchCount >= PATCHES_TOTAL) {
            lastBumpPatchCount = patchCount
            version += 1
        }
        if (firstBlob) {
            minHeight = localMin
            maxHeight = localMax
        } else {
            if (localMin < minHeight) {
                minHeight = localMin
            }
            if (localMax > maxHeight) {
                maxHeight = localMax
            }
        }
        return true
    }

    private companion object {
        const val PATCHES_TOTAL = 256
        const val VERSION_BUMP_PATCHES = 48
        const val MAX_PLAUSIBLE_HEIGHT = 10000f
        const val MIN_PLAUSIBLE_HEIGHT = -2000f
    }
}
