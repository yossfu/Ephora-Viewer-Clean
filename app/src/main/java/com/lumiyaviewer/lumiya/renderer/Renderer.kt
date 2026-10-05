package com.lumiyaviewer.lumiya.renderer

/**
 * Which parts of a mesh are drawn, and with which material.
 *
 * A [MeshHandle] may carry several Second Life faces (see `MeshDesc.faceGroups`);
 * [EntityDesc.materialOfFace] maps a face index onto a material, which is what
 * makes per-face `TextureEntry` possible without touching the renderer backend.
 */
class MeshDesc(
    /** Interleaved position(3), normal(3), uv(2). */
    val vertices: FloatArray,
    val indices: IntArray,
    /** Triples of (Second Life face index, first index, index count). */
    val faceGroups: IntArray,
    val boundsMin: FloatArray,
    val boundsMax: FloatArray
) {
    val vertexCount: Int get() = vertices.size / VERTEX_FLOATS
    val faceCount: Int get() = faceGroups.size / 3

    fun faceIndexAt(group: Int): Int = faceGroups[group * 3]
    fun faceFirstIndexAt(group: Int): Int = faceGroups[group * 3 + 1]
    fun faceIndexCountAt(group: Int): Int = faceGroups[group * 3 + 2]

    /**
     * Why this description cannot be handed to a graphics backend, or null when
     * it can.
     *
     * This exists because a mesh with no geometry, or with face ranges that
     * point past its index buffer, does not fail loudly in any engine: the
     * driver either draws nothing or the backend throws somewhere far from the
     * data that was wrong. Checking here means the *object* is reported and
     * skipped (see [SLScene][com.lumiyaviewer.lumiya.slscene.SLScene]) instead of
     * taking the whole scene build down with it.
     *
     * The messages are deliberately specific — each one names a different bug in
     * the geometry generator.
     */
    fun geometryProblem(): String? {
        if (vertices.isEmpty()) {
            return "sin vertices"
        }
        if (vertices.size % VERTEX_FLOATS != 0) {
            return "vertices.size=" + vertices.size + " no es multiplo de " + VERTEX_FLOATS
        }
        if (vertexCount < 3) {
            return "solo " + vertexCount + " vertices (minimo 3)"
        }
        if (indices.isEmpty()) {
            return "sin indices (" + vertexCount + " vertices)"
        }
        if (indices.size % 3 != 0) {
            return "indices.size=" + indices.size + " no es multiplo de 3"
        }
        if (faceGroups.isEmpty()) {
            return "sin grupos de caras (faceGroups vacio, " + vertexCount + " verts, " +
                indices.size + " indices)"
        }
        if (faceGroups.size % 3 != 0) {
            return "faceGroups.size=" + faceGroups.size + " no es multiplo de 3"
        }
        for (group in 0 until faceCount) {
            val faceIndex = faceIndexAt(group)
            val first = faceFirstIndexAt(group)
            val count = faceIndexCountAt(group)
            if (faceIndex < 0) {
                return "cara " + group + ": faceIndex=" + faceIndex
            }
            if (first < 0) {
                return "cara " + group + ": firstIndex=" + first
            }
            if (count <= 0) {
                return "cara " + group + ": indexCount=" + count
            }
            if (count % 3 != 0) {
                return "cara " + group + ": indexCount=" + count + " no es multiplo de 3"
            }
            if (first + count > indices.size) {
                return "cara " + group + ": rango " + first + "+" + count +
                    " fuera de " + indices.size + " indices"
            }
        }
        for (index in indices) {
            if (index < 0 || index >= vertexCount) {
                return "indice " + index + " fuera de " + vertexCount + " vertices"
            }
        }
        for (value in vertices) {
            if (!value.isFinite()) {
                return "vertice no finito (NaN o infinito)"
            }
        }
        return null
    }

    /**
     * A defect that is worth reporting but is *not* a reason to hide the object:
     * indices no face group claims. Those triangles are simply never submitted,
     * while everything the groups do cover draws normally — so the mesh is
     * usable, and rejecting it would turn a generator bug into a missing prim.
     */
    fun geometryWarning(): String? {
        if (faceGroups.isEmpty() || faceGroups.size % 3 != 0) {
            return null
        }
        var covered = 0
        for (group in 0 until faceCount) {
            covered += faceIndexCountAt(group)
        }
        if (covered != indices.size) {
            return "los grupos de caras cubren " + covered + " de " + indices.size +
                " indices (" + (indices.size - covered) + " triangulos no se dibujan)"
        }
        return null
    }

    /** One line naming the counts, for logs and the debug HUD. */
    fun summary(): String = "verts=" + vertexCount + " indices=" + indices.size +
        " tris=" + (indices.size / 3) + " caras=" + faceCount

    companion object {
        const val VERTEX_FLOATS = 8
    }
}

/** A decoded texture, ready for the GPU. Pixels are RGBA, top row first. */
class TextureDesc(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val hasAlpha: Boolean = false
)

enum class AlphaMode {
    OPAQUE, MASKED, BLEND;

    companion object {
        /**
         * Which blend mode a face whose `TextureEntry` alpha is [alpha] needs.
         *
         * Second Life's own rule (the face is drawn with `BT_ALPHA` as soon as
         * `getAlpha() < 1`, see `LLFace`): anything that is not fully opaque is
         * blended. `MASKED` is *not* chosen here on purpose — cutout masking in
         * Second Life comes from the texture's own alpha channel plus the
         * material's alpha mode, and there are no decoded pixels to inspect yet
         * (2.13b). When the JPEG2000 layer exists, a face whose pixels are all
         * 0/1 will be able to say `MASKED`; the wire alone cannot.
         *
         * Opaque is the common case and the fast path, so the test is "is it
         * 1.0" rather than "is it exactly 1.0 in the wire's 8-bit value".
         */
        fun forAlpha(alpha: Float): AlphaMode = if (alpha >= OPAQUE_ALPHA) OPAQUE else BLEND

        /** The wire's own 255/255, with a hair of slack for the float round-trip. */
        const val OPAQUE_ALPHA = 0.999f
    }
}

/**
 * How one Second Life face is shaded. Everything here comes from the protocol
 * (TextureEntry, the prim's material byte, fullbright flag) — the renderer
 * never sees Second Life types.
 */
class MaterialDesc(
    /** Linear RGBA. */
    val baseColor: FloatArray,
    val texture: TextureHandle? = null,
    /** TextureEntry per-face mapping: repeatU, repeatV, offsetU, offsetV. */
    val uvTransform: FloatArray = DEFAULT_UV_TRANSFORM,
    /** TextureEntry rotation, radians, counter-clockwise about the face centre. */
    val uvRotation: Float = 0f,
    /**
     * 0 = default (geometry UVs through the xform), 1 = planar projection
     * (llface.cpp `planarProjection` from the object-space position, then the
     * same xform). Per-face uniform, so mixed faces never share state.
     */
    val planarMode: Float = 0f,
    /** Planar basis (binormal) in object space; only read when [planarMode] is 1. */
    val planarBasisU: FloatArray = PLANAR_BASIS_U,
    /** Planar basis (tangent) in object space; only read when [planarMode] is 1. */
    val planarBasisV: FloatArray = PLANAR_BASIS_V,
    val fullBright: Boolean = false,
    /** Second Life material code 0..7 (stone, metal, glass, wood, flesh, plastic, rubber, light). */
    val materialCode: Int = 3,
    val alphaMode: AlphaMode = AlphaMode.OPAQUE,
    val doubleSided: Boolean = false
) {
    companion object {
        val DEFAULT_UV_TRANSFORM = floatArrayOf(1f, 1f, 0f, 0f)
        val PLANAR_BASIS_U = floatArrayOf(0f, 1f, 0f)
        val PLANAR_BASIS_V = floatArrayOf(1f, 0f, 0f)
    }
}

/** Second Life renders with the region's axes; the renderer only needs a TRS. */
class Transform(
    val translation: FloatArray,
    /** Quaternion, xyzw. */
    val rotation: FloatArray,
    val scale: FloatArray,
    /** Parent entity for attachments and linkset children, null for region objects. */
    val parent: EntityHandle? = null
) {
    /** Column-major 4x4, as Filament expects. */
    fun toMatrix16(out: FloatArray = FloatArray(16)): FloatArray {
        val x = rotation[0]
        val y = rotation[1]
        val z = rotation[2]
        val w = rotation[3]
        val sx = scale[0]
        val sy = scale[1]
        val sz = scale[2]

        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z

        out[0] = (1f - 2f * (yy + zz)) * sx
        out[1] = (2f * (xy + wz)) * sx
        out[2] = (2f * (xz - wy)) * sx
        out[3] = 0f

        out[4] = (2f * (xy - wz)) * sy
        out[5] = (1f - 2f * (xx + zz)) * sy
        out[6] = (2f * (yz + wx)) * sy
        out[7] = 0f

        out[8] = (2f * (xz + wy)) * sz
        out[9] = (2f * (yz - wx)) * sz
        out[10] = (1f - 2f * (xx + yy)) * sz
        out[11] = 0f

        out[12] = translation[0]
        out[13] = translation[1]
        out[14] = translation[2]
        out[15] = 1f
        return out
    }
}

class EntityDesc(
    val mesh: MeshHandle,
    /** Material per Second Life face index; -1 means "reuse the first entry". */
    val materialOfFace: IntArray,
    val transform: Transform,
    val visible: Boolean = true
)

class CameraDesc(
    val eye: FloatArray,
    val target: FloatArray,
    val up: FloatArray,
    val verticalFovDegrees: Float,
    val near: Float = 0.05f,
    val far: Float = 1024f
)

class LightDesc(
    /** Direction the light travels in, normalised. */
    val direction: FloatArray,
    val color: FloatArray,
    val intensity: Float,
    val castsShadows: Boolean = false
)

/**
 * The live camera, read back out of the backend after [Renderer.setCamera].
 *
 * This is the piece that makes "the camera is where the scene thinks it is"
 * checkable instead of assumed: the view and projection matrices here come from
 * the backend's own camera object, not from the values that were sent to it, so
 * comparing them against the scene's own basis math catches a camera that never
 * moved, a camera whose near/far clamped the scene away, or a projection that
 * was not what was asked for.
 *
 * Both matrices are column-major, exactly as [Transform.toMatrix16] produces.
 */
class CameraSnapshot(
    val viewMatrix: FloatArray,
    val projectionMatrix: DoubleArray,
    val eye: FloatArray,
    val forward: FloatArray,
    /** The camera's own "left" vector, as the backend reports it. */
    val left: FloatArray,
    val up: FloatArray,
    val near: Float,
    val far: Float
)

/**
 * What the backend's own bookkeeping holds for one entity, read back and
 * identified (fase 2.10, diagnostico).
 *
 * Everything here is a *read*: no method that produces it changes anything the
 * backend holds. The point is to be able to say **which component** a matrix
 * came from, so a mismatch between the scene's matrix and the backend's can be
 * attributed either to the transform data or to the identity of the component
 * being read.
 *
 * * `cachedTransformInstance` — the instance the caller stored when it created
 *   the entity, and the one every `setTransform` / `getTransform` call in the
 *   backend uses.
 * * `actualTransformInstance` — what the backend answers for that entity *now*.
 *   The two can differ: a component manager compacts its storage when a
 *   component is removed (it swaps the last one into the freed slot), so an
 *   entity created early can be moved to a different slot later. If they differ,
 *   the "cached" matrix is another entity's transform.
 * * `parentEntity` / `parentHandle` — the parent the backend actually has, as an
 *   entity and as the scene's own handle (0 when there is none).
 */
class EntityProbe(
    /** The scene's handle id, as passed in. */
    val handle: Int,
    /** The backend's own entity id behind that handle. */
    val filamentEntity: Int,
    val cachedTransformInstance: Int,
    val actualTransformInstance: Int,
    val cachedRenderableInstance: Int,
    val actualRenderableInstance: Int,
    /**
     * The parent the backend has for this entity, asked through the instance the
     * entity owns **now** — the authoritative answer. 0 means no parent.
     */
    val parentEntity: Int,
    /**
     * The same question asked through the remembered instance. It is only equal
     * to [parentEntity] while [instanceMatches]; when it is not, this value is
     * whatever the component that took that slot has.
     */
    val parentEntityFromCached: Int,
    /** The scene handle that owns [parentEntity], 0 when it is not one of ours. */
    val parentHandle: Int,
    val childCount: Int,
    val mesh: String,
    val triangles: Int,
    val visible: Boolean,
    val cachedLocal: FloatArray?,
    val actualLocal: FloatArray?,
    val cachedWorld: FloatArray?,
    val actualWorld: FloatArray?
) {
    /** True when the instance the caller cached is still the entity's own. */
    val instanceMatches: Boolean get() = cachedTransformInstance == actualTransformInstance
}

/** Frame timing and counters, filled in by the backend. */
class RenderStats(
    var framesPerSecond: Double = 0.0,
    var frameMillis: Double = 0.0,
    var cpuFrameMillis: Double = 0.0,
    var gpuFrameMillis: Double = 0.0,
    var drawCalls: Int = 0,
    var triangles: Int = 0,
    var meshCount: Int = 0,
    var textureCount: Int = 0,
    var materialCount: Int = 0,
    var entityCount: Int = 0
)

class MeshHandle(val id: Int) {
    override fun hashCode(): Int = id
    override fun equals(other: Any?): Boolean = other is MeshHandle && other.id == id
    override fun toString(): String = "Mesh#$id"
}

class TextureHandle(val id: Int) {
    override fun hashCode(): Int = id
    override fun equals(other: Any?): Boolean = other is TextureHandle && other.id == id
    override fun toString(): String = "Texture#$id"
}

class MaterialHandle(val id: Int) {
    override fun hashCode(): Int = id
    override fun equals(other: Any?): Boolean = other is MaterialHandle && other.id == id
    override fun toString(): String = "Material#$id"
}

class EntityHandle(val id: Int) {
    /** False for [INVALID]: the backend refused to build this entity. */
    val isValid: Boolean get() = id != INVALID_ID

    override fun hashCode(): Int = id
    override fun equals(other: Any?): Boolean = other is EntityHandle && other.id == id
    override fun toString(): String = if (isValid) "Entity#$id" else "Entity(INVALID)"

    companion object {
        /**
         * The handle a backend returns when it could not create the entity.
         * Entity ids are handed out from 1, so 0 is never a live entity.
         */
        const val INVALID_ID = 0
        val INVALID: EntityHandle = EntityHandle(INVALID_ID)
    }
}

/**
 * The graphics backend, as seen by the Second Life scene layer.
 *
 * Nothing outside [com.lumiyaviewer.lumiya.renderer.filament] may depend on a
 * concrete backend: `SLWorld`, `SLScene` and the protocol code only ever talk to
 * this interface, so Filament can be replaced (or a Vulkan-only build produced)
 * without touching the protocol, asset or scene code.
 *
 * All methods must be called on the render thread — the backend owns GPU
 * resources and has no locking of its own. [com.lumiyaviewer.lumiya.slscene.SLScene]
 * takes care of handing work over from the network thread.
 */
interface Renderer {

    /** Human readable backend name, e.g. "Filament (OpenGL ES)". */
    val name: String

    /** True once the backend can actually draw (engine created, surface ready). */
    val isReady: Boolean

    fun createMesh(mesh: MeshDesc): MeshHandle

    fun destroyMesh(handle: MeshHandle)

    fun createTexture(texture: TextureDesc): TextureHandle

    fun updateTexture(handle: TextureHandle, texture: TextureDesc)

    fun destroyTexture(handle: TextureHandle)

    fun createMaterial(material: MaterialDesc): MaterialHandle

    fun updateMaterial(handle: MaterialHandle, material: MaterialDesc)

    fun destroyMaterial(handle: MaterialHandle)

    fun createEntity(entity: EntityDesc): EntityHandle

    fun destroyEntity(handle: EntityHandle)

    /** Replaces the whole transform, including the parent. */
    fun updateTransform(handle: EntityHandle, transform: Transform)

    fun setVisible(handle: EntityHandle, visible: Boolean)

    /**
     * LOD hook (fase 2.12): whether an entity contributes to the shadow maps.
     *
     * It keeps being drawn, keeps its geometry and keeps receiving light — only
     * its shadow-map cost changes — so a backend that has no shadows can ignore
     * this entirely (hence the no-op default). The scene uses it to drop the
     * *cast* of prims far from the camera without touching their geometry, which
     * is the one render-cost reduction that cannot change a shape.
     */
    fun setShadowCaster(handle: EntityHandle, castsShadows: Boolean) {}

    /** Swaps the material(s) of an existing entity; -1 keeps the current one. */
    fun updateMaterials(handle: EntityHandle, materialOfFace: IntArray)

    fun setCamera(camera: CameraDesc)

    fun setSun(light: LightDesc)

    fun setBackgroundColor(color: FloatArray)

    /**
     * The live counters behind the debug HUD.
     *
     * This is how "the screen is black" gets attributed to a layer instead of
     * guessed at: the renderer fills in what it created, what it submitted and
     * what the driver said, and the caller fills in the Second Life side (see
     * [RenderDiagnostics]). It must be safe to call from another thread.
     */
    fun diagnostics(): RenderDiagnostics

    /**
     * Read-back of an entity's transform as the backend's own transform system
     * holds it, column-major. Null for an entity the backend does not have.
     *
     * This exists so the scene can prove what was *actually* applied instead of
     * restating what it sent: the matrix here went through
     * `TransformManager.setTransform`, so if it differs from the scene's own
     * [Transform.toMatrix16] the difference is in the backend, not in the data.
     */
    fun entityLocalMatrix(handle: EntityHandle): FloatArray? = null

    /** The same, after the parent chain: the entity's transform in world space. */
    fun entityWorldMatrix(handle: EntityHandle): FloatArray? = null

    /**
     * TEST6: lectura del PrimTransformNode (local SL verbatim, escala 1) y del
     * PrimRenderable (origen, identidad, escala propia). Null en backends sin
     * split o para entidades desconocidas.
     */
    fun entityNodeMatrix(handle: EntityHandle): FloatArray? = null

    /** TEST6: lo mismo para el PrimRenderable del prim. */
    fun entityMeshMatrix(handle: EntityHandle): FloatArray? = null

    /**
     * TEST8: el AABB object-space que el backend guarda para el culling, como
     * [cx,cy,cz,hx,hy,hz]. Null si no disponible. Es el AABB que el algoritmo
     * real de frustum culling usa (no el centro del objeto).
     */
    fun entityObjectBox(handle: EntityHandle): FloatArray? = null

    /** TEST8: mascara de layer del renderable (-1 = desconocida). */
    fun entityLayerMask(handle: EntityHandle): Int = -1

    /** TEST8: culling propio del renderable (null = desconocido). */
    fun entityCullingEnabled(handle: EntityHandle): Boolean? = null

    /** TEST8: cambia el culling propio del renderable (reversible). */
    fun setEntityCulling(handle: EntityHandle, enabled: Boolean): Boolean = false

    /**
     * DIAG-VIS testigo (temporal, reversible): pone/quita el material rojo
     * de aislamiento (UNLIT, culling NONE, double-sided, sin depth) en TODAS
     * las primitivas del renderable, guardando las instancias originales
     * para restaurarlas. Devuelve una linea de evidencia.
     */
    fun setWitnessDiagMaterial(handle: EntityHandle, enabled: Boolean): String = "-"

    /** DIAG-VIS testigo: linea fija con los flags del material de aislamiento. */
    fun witnessMaterialLine(): String = "-"

    /** DIAG-VIS: capas visibles de la vista (-1 = desconocido). */
    fun viewVisibleLayers(): Int = -1

    /**
     * TEST8: reemplaza temporalmente el AABB object-space (reversible: el
     * llamante guarda el original). [box] = [cx,cy,cz,hx,hy,hz].
     */
    fun setEntityObjectBox(handle: EntityHandle, box: FloatArray): Boolean = false

    /** TEST8: control de material/instancia (solo lectura, una linea). */
    fun entityMaterialLine(handle: EntityHandle): String = "-"

    /**
     * TEST12: que geometryType se paso al construir el renderable
     * ("NO_EXPLICITO" si el build no lo fijo; el defecto de Filament es
     * DYNAMIC). Solo lectura.
     */
    fun probeGeometryType(handle: EntityHandle): String = "?"

    /** TEST12: matriz local (nodo x malla) tal como quedo al construir. */
    fun entityBuildMatrix(handle: EntityHandle): FloatArray? = null

    /**
     * TEST12: renderable diagnostico temporal que comparte malla, material y
     * AABB del origen, sin parent y sin tocar el original. [geometryType] =
     * DYNAMIC/STATIC_BOUNDS/STATIC. Si [prebuildTransform] es null se usa
     * identidad y el llamante fija el transform despues (POSTBUILD).
     */
    fun createProbe(source: EntityHandle, geometryType: String, prebuildTransform: FloatArray?): EntityHandle =
        EntityHandle.INVALID

    /** TEST12: destruye un probe (lo saca de la Scene). */
    fun destroyProbe(handle: EntityHandle) {}

    /** TEST12: fija el transform local de un probe (POSTBUILD). */
    fun setProbeTransform(handle: EntityHandle, matrix: FloatArray): Boolean = false

    /** TEST12: culling individual de un probe. */
    fun setProbeCulling(handle: EntityHandle, enabled: Boolean): Boolean = false

    /** TEST12: hilos registrados en cada punto Filament (nombre#id). */
    fun threadAuditLine(): String = "-"

    /**
     * TEST13: ficha completa de solo lectura del Renderable detras de
     * [handle], una linea "campo = valor" por fila. Lo que la API no expone
     * se registra como "no expuesto" en vez de inventarse.
     */
    fun renderableFingerprint(handle: EntityHandle): String = "-"

    /** TEST13: camino de construccion registrado para [handle] (ordenado). */
    fun renderableBuildLog(handle: EntityHandle): String = "-"

    /** TEST13: etiqueta un handle para el camino ("REAL #id", "CLON..."). */
    fun labelHandleForOps(handle: EntityHandle, label: String) {}

    /** TEST13: armar/desarmar el registro del camino en el build real. */
    fun armBuildOpLog(armed: Boolean) {}

    /**
     * TEST13: clon exacto del origen (mismos buffers, TRIANGLES,
     * offset/count por cara, mismos MaterialInstance, mismo AABB,
     * mismo GeometryType, mismo culling, capa [layerSelect]/[layerValues],
     * mismas sombras). Sin parent salvo [withHierarchy], colocado en
     * [worldMatrix] (mundo). Registra su propio camino BUILDER_*.
     */
    fun createExactClone(source: EntityHandle, layerSelect: Int, layerValues: Int, worldMatrix: FloatArray?, withHierarchy: Boolean, label: String): EntityHandle =
        EntityHandle.INVALID

    /** TEST13: destruye un clon exacto (sale de la Scene, temporales fuera). */
    fun destroyExactClone(handle: EntityHandle) {}

    /** TEST13 FASE 5: reescribe la geometria del MISMO Renderable + AABB + culling ON. */
    fun realSetGeometryRepair(handle: EntityHandle): Boolean = false

    /** TEST13 FASE 6: destruye solo el componente Renderable y lo reconstruye en la MISMA Entity. */
    fun realRebuildSameEntity(handle: EntityHandle): Boolean = false

    /**
     * TEST13 FASE 7: construye el Renderable exacto en una Entity NUEVA
     * (misma jerarquia si [withHierarchy]), sin tocar el original.
     */
    fun realRebuildNewEntity(handle: EntityHandle, withHierarchy: Boolean, label: String): EntityHandle =
        EntityHandle.INVALID

    /** TEST13: capa del renderable (reversible; el llamante guarda el estado previo). */
    fun setRenderableLayer(handle: EntityHandle, select: Int, values: Int): Boolean = false

    /** TEST13: aisla las capas visibles de la vista; devuelve la mascara previa. */
    fun viewLayerIsolate(select: Int, values: Int): Int = -1

    /** TEST13: restaura la mascara de capas visibles de la vista. */
    fun viewLayerRestore(mask: Int) {}

    /** TEST13: frustum culling de la vista (nulo si no disponible). */
    fun viewFrustumCullingEnabled(): Boolean? = null

    /** TEST13: fija el frustum culling de la vista. */
    fun setViewFrustumCulling(enabled: Boolean) {}

    /** TEST13: saca temporalmente la entidad de la Scene (reversible). */
    fun sceneRemoveEntity(handle: EntityHandle): Boolean = false

    /** TEST13: devuelve la entidad a la Scene. */
    fun sceneAddEntity(handle: EntityHandle): Boolean = false

    /** TEST13: instancia viva del Renderable ahora mismo (0 = sin componente). */
    fun renderableInstanceNow(handle: EntityHandle): Int = 0

    /** TEST15: estado vivo del Renderable con instancia fresca (lineas campo = valor). */
    fun renderableLiveState(handle: EntityHandle): String = "-"

    /** TEST15: setCulling con instancia fresca + relectura inmediata (una linea). */
    fun setRenderableCullingFresh(handle: EntityHandle, enabled: Boolean): String = "-"

    /** TEST16: has/getInstance/getEntity-inverso/culling/prims/AABB de UNA Entity. */
    fun renderableIdentityState(handle: EntityHandle): String = "-"

    /** TEST16: setCulling sobre la instancia EXACTA indicada (verifica dueno). */
    fun setCullingOnInstance(handle: EntityHandle, instance: Int, enabled: Boolean): String = "-"

    /** TEST16: target a targetLayer y todos los demas reales fuera de el. */
    fun isolateTargetForTest(handle: EntityHandle, targetLayer: Int): String = "-"

    /** TEST16: todos los reales a 0x1 (default de construccion). */
    fun restoreTestLayers(): String = "-"

    /**
     * TEST14: jerarquía real completa (nodo + renderable + relación) como
     * líneas "campo = valor". Fuente de verdad: getParent (devuelve Entity),
     * getChildCount/getChildren del TransformManager.
     */
    fun captureHierarchy(handle: EntityHandle): String = "-"

    /**
     * TEST14: probe de jerarquía H0/H1/H2/H3/H4/H5/H6/H7/H8A/H8B con la
     * secuencia exacta registrada (frame/hilo/entity/instancias vivas).
     * Mismos buffers/material/AABB del origen, mundo [worldMatrix], capa
     * [layerSelect]/[layerValues], culling ON al final.
     */
    fun createHierarchyProbe(source: EntityHandle, kind: String, layerSelect: Int, layerValues: Int, worldMatrix: FloatArray?, label: String): EntityHandle =
        EntityHandle.INVALID

    /** TEST14: destruye un probe de jerarquía (nodos + temporales fuera). */
    fun destroyHierarchyProbe(handle: EntityHandle) {}

    /** TEST14: mueve el nodo del probe (H6 epsilon); registra instancias vivas. */
    fun setProbeNodeTransform(handle: EntityHandle, matrix: FloatArray): Boolean = false

    /** TEST14: captura post-operación del probe (worlds + escena + instancias). */
    fun probeHierarchySnapshot(handle: EntityHandle): String = "-"

    /** TEST14: camino de operaciones del probe (ordenado). */
    fun probeOpLog(handle: EntityHandle): String = "-"

    /**
     * Read-only identification of the backend bookkeeping behind one entity
     * (fase 2.10, diagnostico).
     *
     * A matrix read-back answers "what is stored", but not "**whose** is it
     * stored as". This does: it reports the component instance the caller
     * remembered when it created the entity *and* the instance the backend
     * answers with right now, plus the parent component the backend actually has
     * for it. If the two instances differ, the comparison that uses the
     * remembered one is reading another entity's transform — which is exactly
     * the doubt this phase has to settle for `#830138250`. Nothing here writes
     * anything to the backend.
     */
    fun entityProbe(handle: EntityHandle): EntityProbe? = null

    /** True while the backend still holds this entity (it is in its scene). */
    fun entityExists(handle: EntityHandle): Boolean = false

    /** TEST16: true si la Entity sigue en la Scene (solo lectura, null si no se sabe). */
    fun entityInScene(handle: EntityHandle): Boolean? = null

    /**
     * The live camera, as the backend sees it. Null when the backend cannot
     * report it (it is optional so a test double does not have to implement it).
     */
    fun cameraSnapshot(): CameraSnapshot? = null

    /** Whether the backend's own frustum culling is on, or null if unknown. */
    val frustumCullingEnabled: Boolean? get() = null

    /**
     * Draws one frame. [frameTimeNanos] is `System.nanoTime()`; the backend owns
     * its own frame pacing. Returns false when the frame was skipped (surface
     * lost, back pressure, app in the background).
     */
    fun render(frameTimeNanos: Long): Boolean

    fun stats(): RenderStats

    /** Releases every GPU resource; the renderer is unusable afterwards. */
    fun destroy()
}
