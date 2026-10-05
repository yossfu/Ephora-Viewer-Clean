package com.lumiyaviewer.lumiya.slworld

/**
 * An immutable copy of the region's height field at one moment.
 *
 * The protocol's [com.lumiyaviewer.lumiya.slproto.world.TerrainData] lives on the
 * network threads, which keep writing patches into it as `LayerData` arrives.
 * The render thread must never read an array another thread is filling, so
 * [SLWorld] hands the scene a *copy* — the same pull-model the objects use.
 *
 * The copy is only taken when something actually changed (the patch count or the
 * version moved), so a quiet region costs nothing, and it is 256 KB of `float`s
 * at 1 m resolution: the same grid and the same units (region metres, absolute
 * height) that Second Life's `LayerData` carries and that object positions use.
 * Nothing here is invented or rescaled.
 */
class SLTerrainSnapshot(
    val regionHandle: Long,
    val version: Int,
    val patchCount: Int,
    /** Patch size in metres — always 1 m per sample in Second Life. */
    val size: Int,
    val minHeight: Float,
    val maxHeight: Float,
    /** `heights[y * size + x]`, metres above the region's zero plane. */
    val heights: FloatArray
) {

    /**
     * How much of the 256x256 grid actually arrived. A region streams its
     * terrain in patches, so a partial grid is normal — the samples that have
     * not arrived yet read as 0 m and are reported as such rather than being
     * smoothed over.
     */
    val patchesExpected: Int get() = (size / PATCH_METRES) * (size / PATCH_METRES)

    override fun toString(): String =
        "SLTerrainSnapshot(v" + version + ", " + patchCount + " parches, " +
            minHeight + ".." + maxHeight + " m)"

    companion object {
        const val PATCH_METRES = 16
    }
}
