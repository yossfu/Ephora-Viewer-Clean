package com.lumiyaviewer.lumiya.slscene

import android.util.Log
import com.lumiyaviewer.lumiya.renderer.MeshDesc
import com.lumiyaviewer.lumiya.slproto.world.SceneObject

/**
 * Builds Second Life primitive geometry in native code.
 *
 * The rules for turning a prim's path/profile parameters into a mesh are
 * Linden Lab's and they are intricate (see `indra/llmath/llvolume.cpp`), so they
 * are ported to C++ once, in `app/src/main/cpp/sl_prim_geometry.cpp`, and
 * exercised by `slcore_tests`. This object is only the bridge: it packs the raw
 * protocol values into an int array (no interpretation, no smoothing over
 * weird values) and turns the returned buffers into an engine-independent
 * [MeshDesc].
 *
 * The library is optional at runtime: if `libslcore.so` is missing for the
 * device's ABI, [isAvailable] is false, [unavailableReason] explains why, and
 * the scene layer reports each object as "sin geometria" and carries on instead
 * of crashing or substituting an invented shape.
 */
object PrimGeometryNative {

    private const val TAG = "PrimGeometryNative"

    /** Matches `slcore::PrimParams` — the ObjectUpdate prim fields, in order. */
    const val PARAM_COUNT = 19

    /** `slcore::buildFixedMesh`: the legacy non-prim pcodes. */
    const val FIXED_GRASS = 0
    const val FIXED_TREE = 1

    @Volatile
    var isAvailable: Boolean = false
        private set

    @Volatile
    var unavailableReason: String? = "not loaded"
        private set

    init {
        try {
            System.loadLibrary("slcore")
            isAvailable = true
            unavailableReason = null
        } catch (error: Throwable) {
            isAvailable = false
            unavailableReason = error.message ?: error.javaClass.simpleName
            Log.w(TAG, "libslcore.so unavailable: $unavailableReason")
        }
    }

    // The JNI symbol is
    // Java_com_lumiyaviewer_lumiya_slscene_PrimGeometryNative_nativeBuildPrimMesh.
    // It resolves to this object's singleton instance as the second argument;
    // the native side never uses it.
    private external fun nativeBuildPrimMesh(params: IntArray): Array<Any>?

    private external fun nativeBuildFixedMesh(kind: Int): Array<Any>?

    /** Builds the mesh for a prim's raw parameters, or null if nothing to draw. */
    fun buildPrimMesh(params: IntArray): MeshDesc? {
        if (!isAvailable || params.size < PARAM_COUNT) {
            return null
        }
        return toMeshDesc(safeCall { nativeBuildPrimMesh(params) })
    }

    /** The viewer's fixed geometry for the legacy grass/tree pcodes. */
    fun buildFixedMesh(kind: Int): MeshDesc? {
        if (!isAvailable) {
            return null
        }
        return toMeshDesc(safeCall { nativeBuildFixedMesh(kind) })
    }

    /**
     * A cache key for a set of prim parameters.
     *
     * Deliberately excludes position, rotation and scale: a prim's *shape* is
     * fully described by these 19 values, and its size is applied by the entity
     * transform, so every 2m and every 0.5m box in the region shares one mesh.
     */
    fun meshKey(params: IntArray): Long {
        var hash = -3750763034362895579L // FNV-1a 64 offset basis
        for (value in params) {
            hash = hash xor (value.toLong() and 0xFFFFFFFFL)
            hash *= 1099511628211L
        }
        return hash
    }

    /** Packs the ObjectUpdate prim fields, exactly as received. */
    fun packParams(object_: SceneObject): IntArray = intArrayOf(
        object_.pathCurve,
        object_.profileCurve,
        object_.pathBegin,
        object_.pathEnd,
        object_.pathScaleX,
        object_.pathScaleY,
        object_.pathShearX,
        object_.pathShearY,
        object_.pathTwist,
        object_.pathTwistBegin,
        object_.pathRadiusOffset,
        object_.pathTaperX,
        object_.pathTaperY,
        object_.pathRevolutions,
        object_.pathSkew,
        object_.profileBegin,
        object_.profileEnd,
        object_.profileHollow,
        DETAIL_STANDARD
    )

    /** Second Life detail level: 1 (low) to 4 (high); 0 means "use the prim's own". */
    var detailLevel: Int = DETAIL_STANDARD

    const val DETAIL_LOW = 1
    const val DETAIL_STANDARD = 3

    /**
     * Meshes the native generator returned in a shape the renderer cannot use,
     * and which were therefore refused. Both are reported in the debug HUD: a
     * silent zero here and a silent zero on screen look identical.
     */
    @Volatile
    var faceGroupFallbacks: Int = 0
        private set

    @Volatile
    var rejectedMeshes: Int = 0
        private set

    /** Meshes that draw, but with triangles no face group claims (a warning). */
    @Volatile
    var faceGroupWarnings: Int = 0
        private set

    @Volatile
    var lastRejection: String = "-"
        private set

    private inline fun safeCall(block: () -> Array<Any>?): Array<Any>? {
        return try {
            block()
        } catch (error: Throwable) {
            Log.e(TAG, "native mesh build failed", error)
            null
        }
    }

    /**
     * Turns the native result into a [MeshDesc], refusing anything the renderer
     * could not draw and *saying why*.
     *
     * The face-group check is not theoretical: `slcore`'s legacy grass/tree
     * meshes were built without a face group, which made `faceCount` zero and
     * sent `FilamentRenderer` looking for `faceGroups[-2]` — one
     * `ArrayIndexOutOfBoundsException` per object, and a scene with hundreds of
     * objects and no renderables. Synthesising one group over the whole index
     * buffer is the correct reading of "this mesh has a single face", and it is
     * counted so that the *generator* bug is still visible in the report.
     */
    private fun toMeshDesc(result: Array<Any>?): MeshDesc? {
        if (result == null || result.size < 3) {
            return null
        }
        val vertices = result[0] as? FloatArray ?: return null
        val indices = result[1] as? IntArray ?: return null
        val nativeFaceGroups = result[2] as? IntArray
        if (vertices.size < MeshDesc.VERTEX_FLOATS || indices.isEmpty()) {
            return null
        }
        val faceGroups = if (nativeFaceGroups == null || nativeFaceGroups.isEmpty() ||
            nativeFaceGroups.size % 3 != 0
        ) {
            faceGroupFallbacks += 1
            Log.w(
                TAG,
                "malla nativa sin grupos de caras (faceGroups=" +
                    (nativeFaceGroups?.size ?: -1) + ") con " + (indices.size / 3) +
                    " triangulos: se usa un unico grupo (bug del generador, contado como fallback)"
            )
            intArrayOf(0, 0, indices.size)
        } else {
            nativeFaceGroups
        }
        val boundsMin = FloatArray(3) { Float.MAX_VALUE }
        val boundsMax = FloatArray(3) { -Float.MAX_VALUE }
        var i = 0
        while (i + 2 < vertices.size) {
            for (axis in 0..2) {
                val value = vertices[i + axis]
                if (!value.isFinite()) {
                    continue
                }
                if (value < boundsMin[axis]) boundsMin[axis] = value
                if (value > boundsMax[axis]) boundsMax[axis] = value
            }
            i += MeshDesc.VERTEX_FLOATS
        }
        if (boundsMin[0] > boundsMax[0]) {
            boundsMin[0] = -0.5f; boundsMax[0] = 0.5f
        }
        if (boundsMin[1] > boundsMax[1]) {
            boundsMin[1] = -0.5f; boundsMax[1] = 0.5f
        }
        if (boundsMin[2] > boundsMax[2]) {
            boundsMin[2] = -0.5f; boundsMax[2] = 0.5f
        }
        val desc = MeshDesc(vertices, indices, faceGroups, boundsMin, boundsMax)
        val problem = desc.geometryProblem()
        if (problem != null) {
            rejectedMeshes += 1
            lastRejection = problem
            Log.e(TAG, "geometria rechazada: " + problem + " (" + desc.summary() + ")")
            return null
        }
        desc.geometryWarning()?.let { warning ->
            faceGroupWarnings += 1
            Log.w(TAG, "geometria incompleta: " + warning + " (" + desc.summary() + ")")
        }
        return desc
    }

}
