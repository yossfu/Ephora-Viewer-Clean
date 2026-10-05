package com.lumiyaviewer.lumiya.slscene

import android.util.Log
import com.lumiyaviewer.lumiya.renderer.EntityDesc
import com.lumiyaviewer.lumiya.renderer.EntityHandle
import com.lumiyaviewer.lumiya.renderer.MaterialDesc
import com.lumiyaviewer.lumiya.renderer.MaterialHandle
import com.lumiyaviewer.lumiya.renderer.MeshDesc
import com.lumiyaviewer.lumiya.renderer.MeshHandle
import com.lumiyaviewer.lumiya.renderer.Renderer
import com.lumiyaviewer.lumiya.renderer.Transform
import com.lumiyaviewer.lumiya.slworld.SLTerrainSnapshot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The region's ground, as a mesh.
 *
 * Second Life sends terrain as `LayerData` patches: 16x16 squares of absolute
 * heights in metres on a 256x256 grid. This class turns that grid into a
 * triangle mesh and hands it to the renderer as one more entity, so the viewer
 * can stand on something and the horizon is where the region says it is.
 *
 * Three things are deliberate:
 *
 * * **The grid is decimated**, by [STRIDE] metres: a full-resolution mesh is
 *   65 000 vertices for a flat field, and this phase is about whether terrain
 *   appears at all. Phase 6 selects the step from the camera's distance (and
 *   adds the real texture layers).
 * * **Normals come from the height field** (central differences), not from the
 *   triangles: slopes then shade smoothly, the way the region's own terrain
 *   does.
 * * **The colour is a debug tint**, not Second Life data. The real appearance
 *   comes from the region's terrain textures, which need the JPEG2000 decoder
 *   (Phase 4). Until then the ground is a solid colour, exactly like the prims
 *   are a solid colour, and the HUD says so.
 *
 * Terrain arrives a patch at a time, so this class coalesces rebuilds: a new
 * snapshot is remembered and the mesh is rebuilt at most every
 * [MIN_REBUILD_MILLIS], which keeps a streaming region from rebuilding the mesh
 * on every frame.
 */
class SLTerrain(private val renderer: Renderer) {

    private var mesh: MeshHandle? = null
    private var entity: EntityHandle? = null
    private var material: MaterialHandle? = null

    private var pending: SLTerrainSnapshot? = null
    private var lastBuildMillis = 0L
    private var builtPatchCount = -1
    private var builtVersion = -1
    private var builtRegionHandle = Long.MIN_VALUE

    /** Height of the sample under a point, in metres, or null before any data. */
    var lastSnapshot: SLTerrainSnapshot? = null
        private set

    var rebuilds = 0
        private set
    var triangles = 0
        private set
    var vertices = 0
        private set

    /** Metres covered by the built grid on each axis. */
    var spanMetres = 0f
        private set

    var lastError: String = ""
        private set

    val hasMesh: Boolean get() = entity != null

    /**
     * Offers the latest terrain snapshot. Called every frame from the render
     * thread. Returns true when the mesh was rebuilt this call.
     */
    fun update(snapshot: SLTerrainSnapshot?, nowMillis: Long): Boolean {
        if (snapshot != null) {
            pending = snapshot
            lastSnapshot = snapshot
        }
        val waiting = pending ?: return false
        val first = entity == null
        val changed = waiting.patchCount != builtPatchCount ||
            waiting.version != builtVersion ||
            waiting.regionHandle != builtRegionHandle
        if (!changed) {
            return false
        }
        if (!first && nowMillis - lastBuildMillis < MIN_REBUILD_MILLIS) {
            return false
        }
        build(waiting)
        builtPatchCount = waiting.patchCount
        builtVersion = waiting.version
        builtRegionHandle = waiting.regionHandle
        lastBuildMillis = nowMillis
        return true
    }

    private fun build(snapshot: SLTerrainSnapshot) {
        val started = System.nanoTime()
        val desc = try {
            buildMesh(snapshot, STRIDE)
        } catch (error: Throwable) {
            lastError = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "terrain mesh build failed", error)
            return
        }
        if (desc == null) {
            lastError = "grid demasiado pequena (" + snapshot.size + ")"
            return
        }
        if (entity == null) {
            material = renderer.createMaterial(
                MaterialDesc(baseColor = DEBUG_TERRAIN_COLOR, materialCode = 0)
            )
        }
        if (mesh != null) {
            renderer.destroyEntity(entity!!)
            renderer.destroyMesh(mesh!!)
            entity = null
            mesh = null
        }
        val meshHandle = renderer.createMesh(desc)
        mesh = meshHandle
        entity = renderer.createEntity(
            EntityDesc(
                mesh = meshHandle,
                materialOfFace = intArrayOf(material?.id ?: -1),
                transform = Transform(
                    translation = floatArrayOf(0f, 0f, 0f),
                    rotation = floatArrayOf(0f, 0f, 0f, 1f),
                    scale = floatArrayOf(1f, 1f, 1f)
                )
            )
        )
        rebuilds += 1
        vertices = desc.vertexCount
        triangles = desc.indices.size / 3
        spanMetres = (snapshot.size - 1).toFloat()
        lastError = ""
        Log.i(
            TAG,
            "terreno reconstruido: " + triangles + " triangulos, " + vertices + " vertices, " +
                snapshot.patchCount + " parches, " + snapshot.minHeight + ".." + snapshot.maxHeight +
                " m, " + (System.nanoTime() - started) / 1_000_000 + " ms"
        )
    }

    fun destroy() {
        entity?.let { renderer.destroyEntity(it) }
        entity = null
        mesh?.let { renderer.destroyMesh(it) }
        mesh = null
        material?.let { renderer.destroyMaterial(it) }
        material = null
        pending = null
        lastSnapshot = null
        builtPatchCount = -1
        builtVersion = -1
        builtRegionHandle = Long.MIN_VALUE
        triangles = 0
        vertices = 0
    }

    companion object {
        private const val TAG = "SLTerrain"

        /** Metres between grid samples of the generated mesh. */
        const val STRIDE = 2

        /** Rebuild at most this often while patches are still streaming in. */
        const val MIN_REBUILD_MILLIS = 400L

        /**
         * Debug-only colour. The region's real terrain textures arrive with the
         * JPEG2000 decoder (Phase 4); this is the same kind of placeholder the
         * prims use, and the HUD labels it as such. Linear RGB.
         */
        val DEBUG_TERRAIN_COLOR = floatArrayOf(0.16f, 0.34f, 0.11f, 1f)

        /**
         * Builds the triangle mesh for a decimated height field.
         *
         * Vertices are `position(3), normal(3), uv(2)` like every other mesh in
         * the viewer, in region metres with Z up, so the terrain lines up with
         * the object positions with no conversion at all. Faces point up and are
         * wound counter-clockwise seen from above (Filament's front face).
         */
        fun buildMesh(snapshot: SLTerrainSnapshot, stride: Int): MeshDesc? {
            val size = snapshot.size
            if (size < 2 || stride < 1) {
                return null
            }
            val samples = spanSamples(size, stride)
            val vertexCount = samples * samples
            val vertices = FloatArray(vertexCount * MeshDesc.VERTEX_FLOATS)
            val boundsMin = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val boundsMax = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

            var write = 0
            for (row in 0 until samples) {
                val y = sampleAt(row, stride, size)
                for (column in 0 until samples) {
                    val x = sampleAt(column, stride, size)
                    val height = heightOf(snapshot, x, y)
                    val normal = normalAt(snapshot, x, y, stride)
                    vertices[write] = x.toFloat()
                    vertices[write + 1] = y.toFloat()
                    vertices[write + 2] = height
                    vertices[write + 3] = normal[0]
                    vertices[write + 4] = normal[1]
                    vertices[write + 5] = normal[2]
                    vertices[write + 6] = x.toFloat() / UV_METRES
                    vertices[write + 7] = y.toFloat() / UV_METRES
                    write += MeshDesc.VERTEX_FLOATS
                    // The bounds include the normal's height so a sloped edge
                    // cannot be culled by a bounding box that is too tight.
                    if (x.toFloat() < boundsMin[0]) boundsMin[0] = x.toFloat()
                    if (y.toFloat() < boundsMin[1]) boundsMin[1] = y.toFloat()
                    if (height < boundsMin[2]) boundsMin[2] = height
                    if (x.toFloat() > boundsMax[0]) boundsMax[0] = x.toFloat()
                    if (y.toFloat() > boundsMax[1]) boundsMax[1] = y.toFloat()
                    if (height > boundsMax[2]) boundsMax[2] = height
                }
            }

            val cells = samples - 1
            val indices = IntArray(cells * cells * 6)
            var index = 0
            for (row in 0 until cells) {
                for (column in 0 until cells) {
                    val p00 = row * samples + column
                    val p10 = p00 + 1
                    val p01 = p00 + samples
                    val p11 = p01 + 1
                    indices[index] = p00
                    indices[index + 1] = p10
                    indices[index + 2] = p11
                    indices[index + 3] = p00
                    indices[index + 4] = p11
                    indices[index + 5] = p01
                    index += 6
                }
            }
            return MeshDesc(
                vertices = vertices,
                indices = indices,
                faceGroups = intArrayOf(0, 0, indices.size),
                boundsMin = boundsMin,
                boundsMax = boundsMax
            )
        }

        /** Samples per axis for a grid of [size] samples taken every [stride]. */
        fun spanSamples(size: Int, stride: Int): Int =
            (size - 1 + stride - 1) / stride + 1

        private fun sampleAt(position: Int, stride: Int, size: Int): Int =
            min(position * stride, size - 1)

        private fun heightOf(snapshot: SLTerrainSnapshot, x: Int, y: Int): Float {
            val index = y * snapshot.size + x
            if (index < 0 || index >= snapshot.heights.size) {
                return 0f
            }
            return snapshot.heights[index]
        }

        /**
         * Surface normal from the height field's central differences. Terrain in
         * Second Life is a smooth height field, so this gives the smooth shading
         * the region's own ground has instead of per-triangle facets.
         */
        private fun normalAt(snapshot: SLTerrainSnapshot, x: Int, y: Int, stride: Int): FloatArray {
            val left = heightOf(snapshot, (x - stride).coerceAtLeast(0), y)
            val right = heightOf(snapshot, (x + stride).coerceAtMost(snapshot.size - 1), y)
            val down = heightOf(snapshot, x, (y - stride).coerceAtLeast(0))
            val up = heightOf(snapshot, x, (y + stride).coerceAtMost(snapshot.size - 1))
            val dx = (right - left) * 0.5f
            val dy = (up - down) * 0.5f
            val scale = stride.toFloat()
            val nx = -dx / scale
            val ny = -dy / scale
            val length = sqrt(nx * nx + ny * ny + 1f)
            return floatArrayOf(nx / length, ny / length, 1f / length)
        }

        /** Metres per texture repeat, so a future terrain texture tiles sanely. */
        const val UV_METRES = 8f
    }
}
