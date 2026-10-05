package com.lumiyaviewer.lumiya.slworld

import android.util.Log
import com.lumiyaviewer.lumiya.renderer.MeshDesc
import com.lumiyaviewer.lumiya.renderer.MeshHandle
import com.lumiyaviewer.lumiya.renderer.Renderer
import com.lumiyaviewer.lumiya.slproto.world.SceneObject
import com.lumiyaviewer.lumiya.slscene.PrimGeometryNative

/**
 * A mesh Second Life asked us to draw, already uploaded to the GPU.
 *
 * [Source.PRIM] is geometry generated from a prim's path/profile parameters by
 * `slcore` (the port of `LLVolume`), [Source.FIXED] is the viewer's old
 * hard-coded geometry for the deprecated grass/tree pcodes, and [Source.ASSET]
 * is a mesh asset fetched with `GetMesh` — that decoder is Phase 5, so nothing
 * creates it yet and no substitute is invented in the meantime.
 *
 * Phase 5 also grows this into several LOD levels per mesh asset. For now every
 * prim is built once, at the standard tessellation level.
 */
class SLMesh(
    val key: Long,
    val desc: MeshDesc,
    val handle: MeshHandle,
    val source: Source,
    val pcode: Int
) {
    enum class Source { PRIM, FIXED, SCULPT, ASSET }

    val triangles: Int get() = desc.indices.size / 3

    val faceCount: Int get() = maxOf(1, desc.faceCount)

    override fun toString(): String = "SLMesh(" + source + " tris=" + triangles + ")"
}

/**
 * Builds and caches Second Life meshes.
 *
 * Two identical boxes in a region share one GPU mesh: the cache key is the
 * prim's 19 raw parameters (position, rotation and scale are excluded because
 * they live in the entity transform), so a prim that is merely moved or resized
 * reuses whatever is already on the GPU.
 *
 * Everything here runs on the render thread, which owns the renderer and its
 * GPU resources.
 */
class SLMeshLibrary(
    /** Injectable so the scene layer can be exercised without the native library. */
    private val primGeometry: (IntArray) -> MeshDesc? = { PrimGeometryNative.buildPrimMesh(it) },
    private val fixedGeometry: (Int) -> MeshDesc? = { PrimGeometryNative.buildFixedMesh(it) }
) {

    private val meshes = HashMap<Long, SLMesh>()

    /**
     * Geometría sculpt real por UUID de sculpt map (fase 5 parcial). Clave
     * separada del cache de prims: la identidad de un sculpt es su mapa, no
     * los 19 parámetros del prim subyacente.
     */
    private val sculpts = HashMap<String, SLMesh>()

    /**
     * Geometría mesh real por MeshAssetID (fase 5): un asset descargado una
     * vez sirve a todos los objetos que lo referencian, sin re-descargar ni
     * re-decodificar. Clave disjunta de prims y sculpts.
     */
    private val assets = HashMap<String, SLMesh>()

    var built = 0
        private set
    var cacheHits = 0
        private set
    var failures = 0
        private set
    var sculptBuilt = 0
        private set
    var sculptHits = 0
        private set
    var sculptFailures = 0
        private set
    var assetBuilt = 0
        private set
    var assetHits = 0
        private set
    var assetFailures = 0
        private set

    /** Why the last mesh build returned nothing (never a substituted shape). */
    var lastFailure: String? = null
        private set

    val count: Int get() = meshes.size

    /** Total triangles of every distinct mesh in the cache. */
    val triangles: Int get() = meshes.values.sumOf { it.triangles }

    /**
     * The mesh for one object, or null when there is nothing to draw. [pcode]
     * decides whether the geometry comes from the prim parameters or from the
     * legacy fixed meshes.
     */
    fun meshFor(renderer: Renderer, prim: SLPrimitive, pcode: Int): SLMesh? {
        val fixedKind = fixedKindOf(pcode)
        val key = if (fixedKind < 0) prim.meshKey else FIXED_KEY_BASE or (fixedKind.toLong() and 0xFFFFL)
        meshes[key]?.let {
            cacheHits += 1
            return it
        }
        val desc = if (fixedKind < 0) primGeometry(prim.params) else fixedGeometry(fixedKind)
        if (desc == null) {
            failures += 1
            lastFailure = if (fixedKind < 0) {
                "slcore no genero malla para " + prim.shape + " (pathCurve=0x" +
                    Integer.toHexString(prim.pathCurve) + ", profileCurve=0x" +
                    Integer.toHexString(prim.profileCurve) + ", detail=" + prim.detail + ")"
            } else {
                "slcore no genero la malla fija " + fixedKind + " (pcode " + pcode + ")"
            }
            Log.w(TAG, lastFailure!!)
            return null
        }
        lastFailure = null
        val mesh = SLMesh(key, desc, renderer.createMesh(desc), if (fixedKind < 0) SLMesh.Source.PRIM else SLMesh.Source.FIXED, pcode)
        meshes[key] = mesh
        built += 1
        return mesh
    }

    /**
     * El sculpt real ya construido para este mapa, o null si aún no llegó (el
     * objeto conserva entonces su prim básico; nada se sustituye ni se
     * destruye). Solo lectura del cache.
     */
    fun sculptIfReady(sculptId: String): SLMesh? {
        if (sculptId.isEmpty() || sculptId == SLTextureFace.DEFAULT_UUID) {
            return null
        }
        val hit = sculpts[sculptId]
        if (hit != null) {
            sculptHits += 1
        }
        return hit
    }

    /**
     * Construye y registra el sculpt real desde los píxeles decodificados del
     * mapa (hilo de render, como todo lo de esta clase). Null cuando el tipo
     * no es generable o el mapa no sirve: el objeto sigue con su prim básico.
     */
    fun putSculpt(
        renderer: Renderer,
        sculptId: String,
        sculptType: Int,
        pixels: ByteArray,
        width: Int,
        height: Int
    ): SLMesh? {
        if (sculptId.isEmpty() || sculptId == SLTextureFace.DEFAULT_UUID) {
            return null
        }
        sculpts[sculptId]?.let {
            sculptHits += 1
            return it
        }
        val desc = SculptMeshBuilder.build(pixels, width, height, sculptType)
        if (desc == null || desc.geometryProblem() != null) {
            sculptFailures += 1
            return null
        }
        val mesh = SLMesh(sculptKey(sculptId), desc, renderer.createMesh(desc), SLMesh.Source.SCULPT, -1)
        sculpts[sculptId] = mesh
        sculptBuilt += 1
        return mesh
    }

    /** Clave estable del sculpt, disjunta de las de prims (bit alto). */
    private fun sculptKey(sculptId: String): Long {
        var h = sculptId.hashCode().toLong() and 0xFFFFFFFFL
        h = h xor (h shl 17)
        return h or sculptKeyBase
    }

    /**
     * El mesh real ya subido a GPU para este MeshAssetID, o null si aún no
     * llegó (el objeto conserva su prim básico). Solo lectura del cache.
     */
    fun meshAssetIfReady(meshId: String): SLMesh? {
        if (meshId.isEmpty() || meshId == SLTextureFace.DEFAULT_UUID) {
            return null
        }
        val hit = assets[meshId]
        if (hit != null) {
            assetHits += 1
        }
        return hit
    }

    /**
     * Sube a GPU el mesh decodificado y lo registra por MeshAssetID (hilo de
     * render, como todo lo de esta clase). Null cuando el MeshDesc no sirve:
     * el objeto sigue con su prim básico.
     */
    fun putMeshAsset(
        renderer: Renderer,
        meshId: String,
        desc: MeshDesc
    ): SLMesh? {
        if (meshId.isEmpty() || meshId == SLTextureFace.DEFAULT_UUID) {
            return null
        }
        assets[meshId]?.let {
            assetHits += 1
            return it
        }
        if (desc.geometryProblem() != null) {
            assetFailures += 1
            lastFailure = "mesh " + meshId.take(8) + ": " + desc.geometryProblem()
            Log.w(TAG, lastFailure!!)
            return null
        }
        lastFailure = null
        val mesh = SLMesh(meshAssetKey(meshId), desc, renderer.createMesh(desc), SLMesh.Source.ASSET, -1)
        assets[meshId] = mesh
        assetBuilt += 1
        return mesh
    }

    /** Clave estable del mesh asset, disjunta de prims/sculpts/fijas. */
    private fun meshAssetKey(meshId: String): Long {
        var h = meshId.hashCode().toLong() and 0xFFFFFFFFL
        h = h xor (h shl 13)
        return h or MESH_ASSET_KEY_BASE
    }

    /**
     * A unit box was used here as a debug stand-in while the geometry library
     * was being brought up. It is deliberately gone: PRUEBA A proves Filament
     * draws, so a prim that cannot be generated is reported as
     * "sin geometria" (see `SLScene.upsert`) instead of being shown as a shape
     * the region never described.
     */
    fun destroy(renderer: Renderer) {
        for (mesh in meshes.values) {
            renderer.destroyMesh(mesh.handle)
        }
        meshes.clear()
        for (mesh in sculpts.values) {
            renderer.destroyMesh(mesh.handle)
        }
        sculpts.clear()
        for (mesh in assets.values) {
            renderer.destroyMesh(mesh.handle)
        }
        assets.clear()
    }

    private fun fixedKindOf(pcode: Int): Int = when (pcode) {
        SceneObject.PCODE_GRASS -> PrimGeometryNative.FIXED_GRASS
        SceneObject.PCODE_TREE, SceneObject.PCODE_NEW_TREE -> PrimGeometryNative.FIXED_TREE
        else -> -1
    }

    private companion object {
        const val TAG = "SLMeshLibrary"

        /** Keys for fixed pcode geometry live in their own space. */
        const val FIXED_KEY_BASE = 0x4000_0000_0000_0000L

        /** Sculpt keys live in their own space too (bit alto, como las fijas). */
        const val sculptKeyBase = 0x2000_0000_0000_0000L

        /** Mesh-asset keys live in their own space too. */
        const val MESH_ASSET_KEY_BASE = 0x1000_0000_0000_0000L
    }
}
