package com.lumiyaviewer.lumiya.renderer.filament

import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
// Filament's frame renderer, which would otherwise clash with the viewer's own
// backend-agnostic Renderer interface.
import com.google.android.filament.Renderer as FrameRenderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.SurfaceOrientation
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.TransformManager
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.filamat.MaterialBuilder
import com.lumiyaviewer.lumiya.renderer.CameraDesc
import com.lumiyaviewer.lumiya.renderer.CameraSnapshot
import com.lumiyaviewer.lumiya.renderer.EntityDesc
import com.lumiyaviewer.lumiya.renderer.EntityHandle
import com.lumiyaviewer.lumiya.renderer.EntityProbe
import com.lumiyaviewer.lumiya.renderer.LightDesc
import com.lumiyaviewer.lumiya.renderer.MaterialDesc
import com.lumiyaviewer.lumiya.renderer.MaterialHandle
import com.lumiyaviewer.lumiya.renderer.MeshDesc
import com.lumiyaviewer.lumiya.renderer.MeshHandle
import com.lumiyaviewer.lumiya.renderer.RenderDiagnostics
import com.lumiyaviewer.lumiya.renderer.RenderStats
import com.lumiyaviewer.lumiya.renderer.Renderer
import com.lumiyaviewer.lumiya.renderer.TextureDesc
import com.lumiyaviewer.lumiya.renderer.TextureHandle
import com.lumiyaviewer.lumiya.renderer.Transform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer
import kotlin.math.max

/**
 * Which graphics API to ask for. The scene layer never sees this — it only ever
 * talks to [Renderer] — so moving to Vulkan is a one line change here (or a
 * setting) and nothing in the protocol, asset or scene code has to move.
 */
enum class FilamentBackend { AUTO, OPENGL, VULKAN }

/**
 * Filament implementation of the engine-agnostic [Renderer].
 *
 * This is the only class in the project that knows Filament exists. It receives
 * an already-interpreted scene — meshes, textures, materials, transforms — and
 * issues Filament calls. It never sees a Second Life packet, UUID or
 * TextureEntry, and it never tries to understand the Second Life protocol: the
 * translation happens in the `slscene`/`slworld` layers above it.
 *
 * Threading: every method must be called from the thread that created the
 * renderer (the `SurfaceView`'s render thread). The renderer owns all GPU
 * resources and does no locking of its own.
 */
class FilamentRenderer(
    backend: FilamentBackend = FilamentBackend.OPENGL,
    private val enableShadows: Boolean = true,
    private val msaaSampleCount: Int = 4,
    /**
     * The shared record the whole render path writes into and the debug HUD
     * reads. The caller keeps the same instance so the Second Life side of the
     * counters (world, scene, terrain) lands in the same report as Filament's.
     */
    val diagnostics: RenderDiagnostics = RenderDiagnostics()
) : Renderer {

    private class MeshResources(val desc: MeshDesc) {
        lateinit var vertexBuffer: VertexBuffer
        lateinit var indexBuffer: IndexBuffer
        val triangles: Int get() = desc.indices.size / 3
    }

    /**
     * TEST6: un prim son DOS entidades. `node` (PrimTransformNode) lleva la
     * posicion/rotacion local SL con escala (1,1,1) y es lo unico que se
     * emparenta entre prims, asi que S_root nunca alcanza a los hijos.
     * `entity` (PrimRenderable) cuelga del nodo con traslacion cero,
     * rotacion identidad y la escala propia del prim, y es lo unico que dibuja.
     * `transformInstance` es la instancia recordada del NODO (la jerarquia
     * vive ahi); `meshTransformInstance`, la del renderable.
     */
    private class EntityRecord(
        val entity: Int,
        val node: Int,
        var renderableInstance: Int,
        val transformInstance: Int,
        val meshTransformInstance: Int,
        val mesh: MeshHandle,
        val materials: Array<MaterialInstance?>,
        val triangles: Int,
        var visible: Boolean,
        val geometryType: String,
        val buildLocal: FloatArray
    )

    private val threadMarks = HashMap<String, String>()

    private fun mark(point: String) {
        val t = Thread.currentThread()
        threadMarks[point] = t.name + "#" + t.id
    }

    private val engine: Engine
    private val filamentRenderer: FrameRenderer
    private val scene: Scene
    private val view: View
    private val cameraEntity: Int
    private val camera: Camera
    private val transformManager: TransformManager
    private val renderableManager: RenderableManager
    private val lightManager: LightManager
    /**
     * Not a field initializer: `EntityManager`'s holder class reaches native
     * code, and this constructor's field initializers run before `init`.
     */
    private val entityManager: EntityManager
    private val materials: FilamentMaterials
    /** Created in `init`, never in the companion object (see the `init` comment). */
    private val mipmappedSampler: TextureSampler
    private val plainSampler: TextureSampler

    private val meshes = HashMap<Int, MeshResources>()
    private val textures = HashMap<Int, Texture>()
    private val materialInstances = HashMap<Int, MaterialInstance>()
    private val entities = HashMap<Int, EntityRecord>()
    private val textureSamplers = HashMap<Int, TextureSampler>()

    private var nextMeshId = 1
    private var nextTextureId = 1
    private var nextMaterialId = 1
    private var nextEntityId = 1

    private var swapChain: SwapChain? = null
    private var skybox: Skybox? = null
    private var indirectLight: IndirectLight? = null
    private var sunEntity = 0
    private var sunInstance = 0

    /**
     * A deliberately bright sky while the render path is being diagnosed: if the
     * screen shows this blue, frames *are* being presented and the problem is
     * inside the scene (camera, geometry, materials). A pure black screen with
     * a live HUD then means "nothing was ever presented". Phase 10 replaces it
     * with the region's real WindLight environment. Linear RGB.
     */
    private var backgroundColor = floatArrayOf(0.18f, 0.34f, 0.58f, 1f)

    /**
     * Filament's frustum culling, view level and per renderable. Fase 2.12 turns
     * it on from the debug switch; see [setCullingEnabled].
     */
    private var cullingEnabled = false

    /**
     * The whole shadow pass, mutable at runtime for the fase 2.12 A/B (it is a
     * large part of the GPU cost). Starts at the constructor's value.
     */
    private var shadowsEnabled = enableShadows

    private val fallbackTexture: Texture
    private val fallbackMaterial: MaterialInstance

    /**
     * DIAG-VIS (temporal, reversible): cuando es true, las caras sin
     * material usan el fallback MAGENTA de diagnostico en vez del blanco,
     * para distinguir a simple vista "sin textura" de "con textura".
     * No toca el camino normal de materiales ni el fallback blanco.
     */
    private var diagFallbackEnabled = true
    private var diagFallbackMaterial: MaterialInstance? = null
    private val transformMatrix = FloatArray(16)
    /** TEST6: constantes del split (solo lectura en toMatrix16, sin nativos). */
    private val nodeUnitScale = floatArrayOf(1f, 1f, 1f)
    private val zeroTranslation = floatArrayOf(0f, 0f, 0f)
    private val identityRotation = floatArrayOf(0f, 0f, 0f, 1f)
    private val frameInfoHistory: Array<FrameRenderer.FrameInfo>

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var cameraFov = 60f
    private var cameraNear = 0.05f
    private var cameraFar = 1024f

    private val stats = RenderStats()
    private var lastFrameNanos = 0L
    private var frameAccumulatorMillis = 0.0
    private var framesSinceStats = 0
    private var destroyed = false
    private var ready = false
    private var frameCount = 0L

    override val name: String
        get() = "Filament (${backendName()})"

    override val isReady: Boolean
        get() = ready && !destroyed && swapChain != null

    init {
        // Nothing in this class may touch a Filament class before this line:
        // Filament's JNI library is loaded from Filament's own static
        // initializer, and a Kotlin `companion object` / field initializer runs
        // *before* the constructor body. Doing it in the wrong order makes this
        // class's initializer fail, which is unrecoverable for the whole process
        // (every later attempt throws NoClassDefFoundError naming this class).
        FilamentBootstrap.ensureLoaded(diagnostics)

        engine = step("ENGINE_CREATE") {
            Engine.Builder()
                .backend(
                    when (backend) {
                        FilamentBackend.OPENGL -> Engine.Backend.OPENGL
                        FilamentBackend.VULKAN -> Engine.Backend.VULKAN
                        FilamentBackend.AUTO -> Engine.Backend.DEFAULT
                    }
                )
                .build()
        }
        diagnostics.step("ENGINE_BACKEND", engine.getBackend().name)

        // EntityManager.get() also reaches native code (its holder class
        // initializer calls nGetEntityManager), so it belongs here and not in a
        // field initializer.
        entityManager = step("ENTITY_MANAGER", { EntityManager.get() })
        filamentRenderer = step("RENDERER_CREATE") { engine.createRenderer() }
        scene = step("SCENE_CREATE") { engine.createScene() }
        view = step("VIEW_CREATE") { engine.createView() }
        cameraEntity = entityManager.create()
        camera = step("CAMERA_CREATE") { engine.createCamera(cameraEntity) }

        transformManager = step("TRANSFORM_MANAGER") { engine.getTransformManager() }
        renderableManager = step("RENDERABLE_MANAGER") { engine.getRenderableManager() }
        lightManager = step("LIGHT_MANAGER") { engine.getLightManager() }
        mark("ENGINE_CREATE")
        mark("ENGINE_THREAD_OWNER")
        mark("VIEW_CREATE")
        mark("CAMERA_CREATE")

        // No Filament object may be built in the companion object either:
        // TextureSampler's constructor calls the native nCreateSampler straight
        // away, which is what broke the class initializer before.
        mipmappedSampler = step("SAMPLER_MIPMAPPED") {
            TextureSampler(
                TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.REPEAT
            )
        }
        plainSampler = step("SAMPLER_PLAIN") {
            TextureSampler(
                TextureSampler.MinFilter.LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.REPEAT
            )
        }

        // Compiling the first material loads filamat-jni; it is a separate
        // native library from filament-jni, so it is its own step.
        materials = step("MATERIAL_COMPILER") {
            FilamentMaterials(
                engine,
                if (backend == FilamentBackend.VULKAN) {
                    MaterialBuilder.TargetApi.VULKAN
                } else {
                    MaterialBuilder.TargetApi.OPENGL
                }
            )
        }

        view.setScene(scene)
        view.setCamera(camera)
        view.setViewport(Viewport(0, 0, 1, 1))
        view.setFrustumCullingEnabled(cullingEnabled)
        view.setShadowingEnabled(shadowsEnabled)
        if (shadowsEnabled) {
            view.setShadowType(View.ShadowType.PCF)
        }
        step("VIEW_CONFIGURED") {
            if (msaaSampleCount > 0 && SwapChain.isMSAASwapChainSupported(engine, msaaSampleCount)) {
                view.setSampleCount(msaaSampleCount)
            }
        }

        fallbackTexture = step("FALLBACK_TEXTURE") { createSolidTexture(255, 255, 255, 255) }
        // 2.27b: el fallback de arranque es el material minimo UNLIT, no el
        // material SL completo: si el completo no compila en el dispositivo,
        // el arranque igual llega a RENDERER_READY. Si el minimo falla, se
        // aborta con el error real (nunca null, nunca listo simulado).
        fallbackMaterial = step("FALLBACK_MATERIAL") {
            materials.createFallbackInstance() ?: throw IllegalStateException(
                "el material fallback minimo no compilo: " + materials.fallbackLastError
            )
        }

        step("LIGHTS") {
            setBackgroundColor(backgroundColor)
            setAmbient(AMBIENT_LEVEL)
            setSun(DEFAULT_SUN)
        }
        updateCameraProjection()
        frameInfoHistory = Array(max(1, filamentRenderer.getMaxFrameHistorySize())) { FrameRenderer.FrameInfo() }
        ready = true
        recordBackendDiagnostics()
        diagnostics.step("RENDERER_READY", "SUCCESS")
    }

    /**
     * Runs one start-up step, reporting START / SUCCESS / FAIL, and records the
     * complete throwable when it fails before rethrowing. This is why a failure
     * names the exact Filament call instead of "the renderer did not start".
     */
    private fun <T> step(name: String, body: () -> T): T {
        diagnostics.step(name, "START")
        try {
            val value = body()
            diagnostics.step(name, "SUCCESS")
            return value
        } catch (error: Throwable) {
            diagnostics.failDetailed(name, error)
            throw error
        }
    }

    /** Fills in the "what exists" half of the debug report. */
    private fun recordBackendDiagnostics() {
        diagnostics.backendName = backendName()
        diagnostics.engineValid = engine.isValid()
        diagnostics.frameRendererValid = engine.isValidRenderer(filamentRenderer)
        diagnostics.sceneValid = engine.isValidScene(scene)
        diagnostics.viewValid = engine.isValidView(view)
        // Filament has no validity query for a camera component; the entity it
        // was created on is the only thing we can check.
        diagnostics.cameraValid = cameraEntity != 0
        diagnostics.cullingEnabled = cullingEnabled
        diagnostics.diagFallbackActive = if (diagFallbackEnabled) "MAGENTA (diagnostico)" else "BLANCO (normal)"
        diagnostics.shadowingEnabled = shadowsEnabled
        diagnostics.msaaSampleCount =
            if (msaaSampleCount > 0 && SwapChain.isMSAASwapChainSupported(engine, msaaSampleCount)) {
                msaaSampleCount
            } else {
                0
            }
    }

    /**
     * Diagnostic switch for Filament's own frustum culling (fase 2.12).
     *
     * The view-level flag alone is not enough: the per-renderable culling flag is
     * baked in when the renderable is built (the bounding box is), so objects
     * that already exist would keep the setting they were created with and the
     * A/B would only affect objects built afterwards. The switch therefore also
     * updates every live renderable. It is reversible in both directions.
     */
    private fun freshRenderable(entity: Int): Int {
        return try {
            renderableManager.getInstance(entity)
        } catch (e: Throwable) {
            0
        }
    }

    fun setCullingEnabled(enabled: Boolean) {
        mark("VIEW_SET_FRUSTUM_CULLING")
        cullingEnabled = enabled
        view.setFrustumCullingEnabled(enabled)
        for (record in entities.values) {
            val live = freshRenderable(record.entity)
            if (live != 0) {
                renderableManager.setCulling(live, enabled)
            }
        }
        diagnostics.cullingEnabled = enabled
    }

    /**
     * DIAG-VIS (temporal, reversible): interruptor del fallback magenta.
     * Usa la API ya integrada (misma familia que [setCullingEnabled]).
     */
    fun isDiagFallbackEnabled(): Boolean = diagFallbackEnabled

    fun setDiagFallbackEnabled(enabled: Boolean) {
        diagFallbackEnabled = enabled
        android.util.Log.i(TAG, "DIAG-VIS: fallback de diagnostico " + (if (enabled) "MAGENTA (sin material = magenta)" else "BLANCO normal"))
        diagnostics.diagFallbackActive = if (enabled) "MAGENTA (diagnostico)" else "BLANCO (normal)"
    }

    /** Instancia magenta perezosa: si no compila, se usa el blanco sin abortar. */
    private fun diagMaterial(): MaterialInstance? {
        val existing = diagFallbackMaterial
        if (existing != null) return existing
        return try {
            val created = materials.createDiagFallbackInstance()
            if (created == null) {
                android.util.Log.w(TAG, "DIAG-VIS: no se pudo compilar el magenta, se usa blanco: " + materials.diagFallbackLastError)
                diagnostics.fail("diagFallback", materials.diagFallbackLastError ?: "sin detalle")
                null
            } else {
                diagFallbackMaterial = created
                created
            }
        } catch (error: Throwable) {
            android.util.Log.w(TAG, "DIAG-VIS: excepcion creando magenta, se usa blanco", error)
            null
        }
    }

    /**
     * Diagnostic switch for the whole shadow pass (fase 2.12 A/B). Turning it off
     * removes the shadow maps and their cost; turning it back on restores them.
     * Geometry, materials and transforms are untouched.
     */
    fun setShadowingEnabled(enabled: Boolean) {
        shadowsEnabled = enabled
        view.setShadowingEnabled(enabled)
        if (enabled) {
            view.setShadowType(View.ShadowType.PCF)
        }
        diagnostics.shadowingEnabled = enabled
    }

    /** True while the renderer has a surface to draw into. */
    fun hasSurface(): Boolean = swapChain?.let { engine.isValidSwapChain(it) } ?: false

    // ---------------------------------------------------------------- surface

    /**
     * Binds a native Android surface and starts producing frames. Safe to call
     * again after [detachSurface] (rotation, app switched away and back).
     */
    fun attachSurface(surface: Any, width: Int, height: Int) {
        if (destroyed) return
        detachSurface()
        swapChain = try {
            engine.createSwapChain(surface)
        } catch (error: Throwable) {
            Log.e(TAG, "createSwapChain failed", error)
            diagnostics.fail("createSwapChain", error.message ?: error.javaClass.simpleName)
            null
        }
        swapChain?.let {
            if (it.isFrameRateChangeSupported()) {
                // A pacing hint, never a hard limit: it asks the platform to pair
                // the surface with a compatible display mode, and is ignored
                // where that is not possible. It is asked for on every attach so
                // the render loop below (one turn per VSYNC) and the swap chain
                // agree on the rate. A refusal must not cost the surface.
                try {
                    it.setFrameRate(TARGET_FRAME_RATE)
                    diagnostics.frameRateHintCalls += 1
                    diagnostics.frameRateHintFps = TARGET_FRAME_RATE
                } catch (error: Throwable) {
                    Log.w(TAG, "the swap chain refused the frame rate hint", error)
                }
            }
        }
        diagnostics.surfaceValid = swapChain != null
        diagnostics.swapChainValid = swapChain != null
        diagnostics.surfaceWidth = width
        diagnostics.surfaceHeight = height
        if (swapChain == null) {
            Log.e(TAG, "no swap chain for the surface (${width}x$height)")
        }
        resize(width, height)
    }

    /** Releases the swap chain. Must happen before the surface is destroyed. */
    fun detachSurface() {
        swapChain?.let {
            if (engine.isValidSwapChain(it)) {
                engine.destroySwapChain(it)
            }
        }
        swapChain = null
        diagnostics.swapChainValid = false
    }

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewportWidth = width
        viewportHeight = height
        view.setViewport(Viewport(0, 0, width, height))
        diagnostics.viewportWidth = width
        diagnostics.viewportHeight = height
        diagnostics.surfaceWidth = width
        diagnostics.surfaceHeight = height
        updateCameraProjection()
    }

    // ------------------------------------------------------------------ meshes

    override fun createMesh(mesh: MeshDesc): MeshHandle {
        val handle = MeshHandle(nextMeshId++)

        diagnostics.lastMeshVertices = mesh.vertexCount
        diagnostics.lastMeshIndices = mesh.indices.size
        diagnostics.lastMeshFaces = mesh.faceCount
        val problem = mesh.geometryProblem()
        if (problem != null) {
            // A mesh with no geometry is silently invisible in every engine; it
            // is worth naming here rather than wondering later. Nothing is
            // uploaded, and the handle is only valid as a key: the scene never
            // attaches it to an entity, because `createEntity` refuses the same
            // description (see `geometryProblem`).
            diagnostics.fail("createMesh", "geometria invalida: " + problem + " (" + mesh.summary() + ")")
            Log.e(TAG, "createMesh refused: " + problem + " (" + mesh.summary() + ")")
            return handle
        }
        val resources = MeshResources(mesh)

        val vertexCount = mesh.vertexCount
        // Filament has no NORMAL vertex attribute: the normal and the tangent are
        // packed together into TANGENTS, as a normalised quaternion. The
        // orientation is derived from the geometry once, here, by Filament's own
        // SurfaceOrientation helper (the same thing glTF importers do).
        val vertexBuffer = VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(2)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                MeshDesc.VERTEX_FLOATS * 4
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV0,
                0,
                VertexBuffer.AttributeType.FLOAT2,
                6 * 4,
                MeshDesc.VERTEX_FLOATS * 4
            )
            .attribute(
                VertexBuffer.VertexAttribute.TANGENTS,
                1,
                VertexBuffer.AttributeType.SHORT4,
                0,
                TANGENT_STRIDE
            )
            .normalized(VertexBuffer.VertexAttribute.TANGENTS, true)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, floatViewOf(mesh.vertices))
        vertexBuffer.setBufferAt(engine, 1, shortViewOf(tangentFrameOf(mesh)))
        resources.vertexBuffer = vertexBuffer

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(mesh.indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, intViewOf(mesh.indices))
        resources.indexBuffer = indexBuffer

        meshes[handle.id] = resources
        diagnostics.vertexBuffers += 1
        diagnostics.indexBuffers += 1
        diagnostics.meshesLive = meshes.size
        return handle
    }

    /**
     * Packs each vertex's normal and tangent into the quaternion Filament's
     * TANGENTS attribute expects. The mesh's own normals are used — they are the
     * ones `slcore` computed with the same winding Second Life uses — and the
     * tangent is chosen to follow the face's U direction.
     */
    private fun tangentFrameOf(mesh: MeshDesc): ShortArray {
        val count = mesh.vertexCount
        val positions = FloatArray(count * 3)
        val normals = FloatArray(count * 3)
        val uvs = FloatArray(count * 2)
        val stride = MeshDesc.VERTEX_FLOATS
        for (index in 0 until count) {
            val base = index * stride
            val element = index * 3
            positions[element] = mesh.vertices[base]
            positions[element + 1] = mesh.vertices[base + 1]
            positions[element + 2] = mesh.vertices[base + 2]
            normals[element] = mesh.vertices[base + 3]
            normals[element + 1] = mesh.vertices[base + 4]
            normals[element + 2] = mesh.vertices[base + 5]
            uvs[index * 2] = mesh.vertices[base + 6]
            uvs[index * 2 + 1] = mesh.vertices[base + 7]
        }
        val quaternionBuffer = shortViewOf(ShortArray(count * 4))
        val orientation = SurfaceOrientation.Builder()
            .vertexCount(count)
            .positions(floatViewOf(positions))
            .normals(floatViewOf(normals))
            .uvs(floatViewOf(uvs))
            .triangleCount(mesh.indices.size / 3)
            .triangles_uint32(intViewOf(mesh.indices))
            .build()
        orientation.getQuatsAsShort(quaternionBuffer)
        orientation.destroy()
        // The direct buffer is a copy owner: read the generated quaternions back.
        quaternionBuffer.rewind()
        val quaternions = ShortArray(count * 4)
        quaternionBuffer.get(quaternions)
        return quaternions
    }

    override fun destroyMesh(handle: MeshHandle) {
        val resources = meshes.remove(handle.id) ?: return
        engine.destroyVertexBuffer(resources.vertexBuffer)
        engine.destroyIndexBuffer(resources.indexBuffer)
        diagnostics.meshesLive = meshes.size
    }

    // ---------------------------------------------------------------- textures

    override fun createTexture(texture: TextureDesc): TextureHandle {
        val handle = TextureHandle(nextTextureId++)
        textures[handle.id] = uploadTexture(texture)
        textureSamplers[handle.id] = samplerFor(texture)
        diagnostics.texturesCreated += 1
        diagnostics.texturesLive = textures.size
        return handle
    }

    override fun updateTexture(handle: TextureHandle, texture: TextureDesc) {
        val existing = textures[handle.id]
        if (existing == null) {
            textures[handle.id] = uploadTexture(texture)
            textureSamplers[handle.id] = samplerFor(texture)
            return
        }
        // Reallocating is the safe path when the size changed; Filament only
        // allows re-uploading into a texture with identical dimensions.
        if (existing.getWidth(0) != texture.width || existing.getHeight(0) != texture.height) {
            engine.destroyTexture(existing)
            textures[handle.id] = uploadTexture(texture)
            textureSamplers[handle.id] = samplerFor(texture)
            return
        }
        val buffer = ByteBuffer.allocateDirect(texture.pixels.size).order(ByteOrder.nativeOrder())
        buffer.put(texture.pixels)
        buffer.rewind()
        existing.setImage(
            engine,
            0,
            Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE)
        )
        if (existing.getLevels() > 1) {
            existing.generateMipmaps(engine)
        }
    }

    override fun destroyTexture(handle: TextureHandle) {
        textureSamplers.remove(handle.id)
        textures.remove(handle.id)?.let { engine.destroyTexture(it) }
        diagnostics.texturesLive = textures.size
    }

    private fun uploadTexture(desc: TextureDesc): Texture {
        val levels = mipLevelsFor(desc)
        val texture = Texture.Builder()
            .width(desc.width)
            .height(desc.height)
            .levels(levels)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8)
            .usage(Texture.Usage.UPLOADABLE or Texture.Usage.SAMPLEABLE or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)

        val buffer = ByteBuffer.allocateDirect(desc.pixels.size).order(ByteOrder.nativeOrder())
        buffer.put(desc.pixels)
        buffer.rewind()
        texture.setImage(
            engine,
            0,
            Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE)
        )
        if (levels > 1 && Texture.isTextureFormatMipmappable(engine, Texture.InternalFormat.SRGB8_A8)) {
            texture.generateMipmaps(engine)
        }
        return texture
    }

    private fun mipLevelsFor(desc: TextureDesc): Int {
        if (desc.width <= 0 || desc.height <= 0) return 1
        var size = max(desc.width, desc.height)
        var levels = 1
        while (size > 1) {
            size = size shr 1
            levels++
        }
        return levels
    }

    private fun samplerFor(desc: TextureDesc): TextureSampler {
        // A texture with a single level must not ask for a mipmapping min
        // filter: sampling it makes the whole surface render black in GLES.
        return if (mipLevelsFor(desc) > 1) mipmappedSampler else plainSampler
    }

    private fun createSolidTexture(r: Int, g: Int, b: Int, a: Int): Texture {
        return uploadTexture(
            TextureDesc(1, 1, byteArrayOf(r.toByte(), g.toByte(), b.toByte(), a.toByte()), a < 255)
        )
    }

    // --------------------------------------------------------------- materials

    override fun createMaterial(material: MaterialDesc): MaterialHandle {
        val handle = MaterialHandle(nextMaterialId++)
        val instance = createInstance(handle.id, material)
        if (instance == null) {
            diagnostics.materialFailures += 1
            diagnostics.fail("createMaterial", materials.lastError ?: "material $handle sin compilar")
        } else {
            materialInstances[handle.id] = instance
            diagnostics.materialsCreated += 1
        }
        syncMaterialStats()
        return handle
    }

    override fun updateMaterial(handle: MaterialHandle, material: MaterialDesc) {
        val instance = materialInstances[handle.id]
        if (instance == null) {
            createInstance(handle.id, material)?.let { materialInstances[handle.id] = it }
            syncMaterialStats()
            return
        }
        materials.apply(instance, material, ::textureOf, ::samplerOf, true)
        syncMaterialStats()
    }

    /** TEST 2: copia pasiva de los contadores del camino de materiales al informe. */
    private fun syncMaterialStats() {
        diagnostics.materialCacheHits = materials.cacheHits
        diagnostics.materialCacheMiss = materials.cacheMiss
        diagnostics.materialBuildAttempts = materials.buildAttempts
        diagnostics.materialBuildOk = materials.buildOk
        diagnostics.materialFilamatBuildNanos = materials.filamatBuildNanos
        diagnostics.materialBuildNanos = materials.materialBuildNanos
        diagnostics.materialInstanceCreates = materials.instanceCreates
        diagnostics.materialInstanceAllocNanos = materials.instanceAllocNanos
        diagnostics.materialParamApplies = materials.paramApplies
        diagnostics.materialParamApplyNanos = materials.paramApplyNanos
        diagnostics.materialRebuilds = materials.rebuilds
        diagnostics.materialRebuildNanos = materials.rebuildNanos
        diagnostics.materialCompiledCount = materials.compiledCount
        diagnostics.materialInstancesLive = materialInstances.size
        diagnostics.materialKeySummary = materials.keySummary()
        diagnostics.materialSigSummary = materials.sigSummary()
        diagnostics.materialTierSummary = materials.tierSummary()
        diagnostics.materialTierFailure = materials.lastTierFailure.ifEmpty { "-" }
    }

    override fun destroyMaterial(handle: MaterialHandle) {
        materialInstances.remove(handle.id)?.let {
            // 2.27c: el peldano registrado muere con la instancia.
            materials.releaseInstance(it)
            engine.destroyMaterialInstance(it)
        }
    }

    private fun createInstance(id: Int, desc: MaterialDesc): MaterialInstance? {
        val instance = materials.createInstance(desc, ::textureOf, ::samplerOf)
        if (instance == null) {
            Log.w(TAG, "material $id could not be created (${materials.lastError})")
        }
        return instance
    }

    private fun textureOf(handle: TextureHandle?): Texture {
        if (handle == null) return fallbackTexture
        return textures[handle.id] ?: fallbackTexture
    }

    private fun samplerOf(handle: TextureHandle?): TextureSampler {
        if (handle == null) return plainSampler
        return textureSamplers[handle.id] ?: plainSampler
    }

    // ----------------------------------------------------------------- entities

    override fun createEntity(entity: EntityDesc): EntityHandle {
        val mesh = meshes[entity.mesh.id] ?: run {
            diagnostics.entityFailures += 1
            diagnostics.fail("createEntity", "sin malla para " + entity.mesh)
            rejectEntity(entity, "la malla " + entity.mesh + " no existe en el renderer")
            return EntityHandle.INVALID
        }
        // The renderable is only as valid as the geometry behind it. Checking
        // here is what keeps one bad object from taking the whole scene with it:
        // Filament's builder happily walks off the end of a short index buffer,
        // and the failure surfaces as an exception far from the data. The object
        // is refused, counted and named instead.
        val problem = mesh.desc.geometryProblem()
        if (problem != null) {
            rejectEntity(entity, "geometria invalida: " + problem)
            return EntityHandle.INVALID
        }
        val handle = EntityHandle(nextEntityId++)

        val faceCount = max(1, mesh.desc.faceCount)
        val instances = arrayOfNulls<MaterialInstance>(faceCount)
        for (i in 0 until faceCount) {
            instances[i] = resolveMaterial(entity, i)
        }

        val entityId = entityManager.create()
        try {
            val builder = RenderableManager.Builder(faceCount)
            for (i in 0 until faceCount) {
                // A mesh may declare fewer face groups than the entity has
                // materials (the legacy tree/grass meshes have exactly one);
                // every extra material then draws that same geometry.
                val group = minOf(i, mesh.desc.faceCount - 1)
                builder.geometry(
                    i,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    mesh.vertexBuffer,
                    mesh.indexBuffer,
                    mesh.desc.faceFirstIndexAt(group),
                    mesh.desc.faceIndexCountAt(group)
                )
                builder.material(i, instances[i] ?: (if (diagFallbackEnabled) diagMaterial() ?: fallbackMaterial else fallbackMaterial))
            }
            builder.boundingBox(boundsOf(mesh.desc))
            builder.culling(cullingEnabled)
            builder.castShadows(enableShadows)
            builder.receiveShadows(enableShadows)
            builder.build(engine, entityId)
        } catch (error: Throwable) {
            Log.e(TAG, "renderable build failed for " + describeEntity(entity), error)
            diagnostics.entityFailures += 1
            // Full throwable, not just its message: the difference between an
            // ArrayIndexOutOfBoundsException, an IllegalStateException and a
            // native crash is the whole diagnosis.
            diagnostics.failDetailed("buildRenderable", error)
            rejectEntity(entity, "buildRenderable: " + diagnostics.describe(error))
            entityManager.destroy(entityId)
            return EntityHandle.INVALID
        }

        // TEST6: el nodo se crea aqui (el build de arriba ya valido la malla,
        // asi que el camino de fallo no tiene nodo que destruir).
        val meshInstance = transformManager.create(entityId)
        val nodeId = entityManager.create()
        val nodeInstance = transformManager.create(nodeId)
        transformManager.setTransform(nodeInstance, nodeMatrixOf(entity.transform, transformMatrix))
        entity.transform.parent?.let { parent ->
            entities[parent.id]?.let { parentRecord ->
                transformManager.setParent(nodeInstance, currentNodeInstance(parentRecord))
            }
        }
        transformManager.setTransform(meshInstance, meshMatrixOf(entity.transform, transformMatrix))
        transformManager.setParent(meshInstance, nodeInstance)

        scene.addEntity(entityId)
        val renderableInstance = renderableManager.getInstance(entityId)
        entities[handle.id] = EntityRecord(
            entityId,
            nodeId,
            renderableInstance,
            nodeInstance,
            meshInstance,
            entity.mesh,
            instances,
            mesh.triangles,
            entity.visible,
            "NO_EXPLICITO",
            mat4Mul(
                transformManager.getTransform(nodeInstance, FloatArray(16)),
                transformManager.getTransform(meshInstance, FloatArray(16))
            )
        )
        if (opLogArmed) {
            val ops = opLogFor(handle.id)
            val tag = opTag(opLabels[handle.id] ?: ("handle#" + handle.id), entityId, renderableInstance)
            ops.add(tag + " op=CREATE_ENTITY")
            ops.add(tag + " op=BUILDER_CREATE caras=" + faceCount)
            for (i in 0 until faceCount) {
                val group = minOf(i, mesh.desc.faceCount - 1)
                ops.add(tag + " op=BUILDER_GEOMETRY cara=" + i + " first=" + mesh.desc.faceFirstIndexAt(group) + " count=" + mesh.desc.faceIndexCountAt(group))
                ops.add(tag + " op=BUILDER_MATERIAL cara=" + i)
            }
            ops.add(tag + " op=BUILDER_BOUNDING_BOX")
            ops.add(tag + " op=BUILDER_GEOMETRY_TYPE no llamada (default DYNAMIC)")
            ops.add(tag + " op=BUILDER_CULLING " + cullingEnabled)
            ops.add(tag + " op=BUILD_RENDERABLE inst=" + renderableInstance)
            if (entity.visible) ops.add(tag + " op=SCENE_ADD")
        }
        if (!entity.visible) {
            scene.removeEntity(entityId)
        }
        diagnostics.entitiesCreated += 1
        diagnostics.entitiesInScene = scene.getEntityCount()
        diagnostics.entitiesLive = entities.size
        if (renderableInstance != 0) {
            diagnostics.renderablesCreated += 1
            diagnostics.renderableInstances += 1
        } else {
            // An entity in the scene whose renderable instance is 0 will never
            // draw: this is exactly the "entity created but nothing visible"
            // case the phase is about.
            diagnostics.fail("createEntity", "RenderableManager no devolvio instancia para " + handle)
        }
        return handle
    }

    /**
     * Records an entity the backend refused to build, with the object's mesh,
     * geometry counts, transform and face count. The HUD keeps the first few and
     * counts the rest, so "one prim failed" and "every prim failed" cannot look
     * the same.
     */
    private fun rejectEntity(entity: EntityDesc, reason: String) {
        val label = describeEntity(entity) + " -> " + reason
        diagnostics.rejectEntity(label)
        Log.e(TAG, "entidad rechazada: " + label)
    }

    /** Everything the renderer knows about the entity it is about to build. */
    private fun describeEntity(entity: EntityDesc): String {
        val mesh = meshes[entity.mesh.id]
        val t = entity.transform.translation
        val builder = StringBuilder(120)
        builder.append(entity.mesh).append(' ')
        if (mesh == null) {
            builder.append("(malla desconocida)")
        } else {
            builder.append('(').append(mesh.desc.summary()).append(')')
        }
        builder.append(" pos ").append(String.format(java.util.Locale.US, "%.2f, %.2f, %.2f", t[0], t[1], t[2]))
        builder.append(" materiales ").append(entity.materialOfFace.size)
        return builder.toString()
    }

    private fun resolveMaterial(entity: EntityDesc, face: Int): MaterialInstance? {
        val materialHandle = entity.materialOfFace.getOrElse(face) { entity.materialOfFace[0] }
        if (materialHandle < 0) {
            return entity.materialOfFace.firstOrNull { it >= 0 }?.let { materialInstances[it] }
        }
        return materialInstances[materialHandle]
    }

    override fun destroyEntity(handle: EntityHandle) {
        val record = entities.remove(handle.id) ?: return
        scene.removeEntity(record.entity)
        engine.destroyEntity(record.entity)
        // TEST6: el nodo no esta en la Scene (no dibuja), pero hay que
        // destruirlo igual; el conteo de escena no cambia.
        engine.destroyEntity(record.node)
        diagnostics.entitiesInScene = scene.getEntityCount()
        diagnostics.entitiesLive = entities.size
    }

    /**
     * The transform component an entity owns **right now** (fase 2.11).
     *
     * `EntityRecord.transformInstance` is captured when the entity is created,
     * but Filament's component manager keeps its components in one compacting
     * array: destroying any entity moves the last component into the freed slot
     * (`SingleInstanceComponentManager::removeComponent`), so a remembered
     * instance can begin to address another entity's component. Feeding a
     * transform or a parent through the remembered instance would then write to
     * the wrong entity, so the write paths ask the manager for the entity's
     * current instance first.
     *
     * The remembered value is deliberately left untouched: the fase-2.10
     * diagnosis prints both so exactly this staleness stays visible.
     */
    private fun currentTransformInstance(record: EntityRecord): Int {
        return currentNodeInstance(record)
    }

    /**
     * TEST6: la instancia viva del nodo (la jerarquia vive en los nodos) y la
     * del renderable (la escala propia vive ahi). Las recordadas quedan en el
     * EntityRecord para el diagnostico fase-2.10.
     */
    private fun currentNodeInstance(record: EntityRecord): Int {
        val current = transformManager.getInstance(record.node)
        return if (current != 0) current else record.transformInstance
    }

    private fun currentMeshInstance(record: EntityRecord): Int {
        val current = transformManager.getInstance(record.entity)
        return if (current != 0) current else record.meshTransformInstance
    }

    /** TEST6: TRS del PrimTransformNode: local SL verbatim, escala (1,1,1). */
    private fun nodeMatrixOf(transform: Transform, out: FloatArray = FloatArray(16)): FloatArray =
        Transform(transform.translation, transform.rotation, nodeUnitScale, null).toMatrix16(out)

    /** TEST6: TRS del PrimRenderable: origen, identidad y escala propia. */
    private fun meshMatrixOf(transform: Transform, out: FloatArray = FloatArray(16)): FloatArray =
        Transform(zeroTranslation, identityRotation, transform.scale, null).toMatrix16(out)

    /** Producto 4x4 column-major (nodo x renderable = TRS completo). */
    private fun mat4Mul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                out[column * 4 + row] =
                    a[row] * b[column * 4] +
                        a[4 + row] * b[column * 4 + 1] +
                        a[8 + row] * b[column * 4 + 2] +
                        a[12 + row] * b[column * 4 + 3]
            }
        }
        return out
    }

    private fun liveTransformInstance(entity: Int): Int {
        return try {
            transformManager.getInstance(entity)
        } catch (e: Throwable) {
            0
        }
    }

    override fun updateTransform(handle: EntityHandle, transform: Transform) {
        mark("TRANSFORM_SET")
        val record = entities[handle.id] ?: return
        // TEST6: el LOCAL del child se conserva verbatim en su nodo (incluido
        // reparentar); la escala propia va al renderable, nunca al offset.
        // Escritura solo sobre instancias vivas: una recordada caducada
        // podria escribir el transform en otra entidad tras una
        // reconstruccion. Las lecturas TEST6 no se tocan.
        val node = liveTransformInstance(record.node)
        if (node == 0) return
        transformManager.setTransform(node, nodeMatrixOf(transform, transformMatrix))
        val parent = transform.parent
        if (parent != null) {
            val parentRecord = entities[parent.id]
            if (parentRecord != null) {
                val parentInst = liveTransformInstance(parentRecord.node)
                if (parentInst != 0 && parentInst != node) {
                    transformManager.setParent(node, parentInst)
                }
            } else {
                transformManager.setParent(node, 0)
            }
        } else {
            transformManager.setParent(node, 0)
        }
        val mesh = liveTransformInstance(record.entity)
        if (mesh == 0) return
        transformManager.setTransform(mesh, meshMatrixOf(transform, transformMatrix))
        transformManager.setParent(mesh, node)
    }

    override fun setVisible(handle: EntityHandle, visible: Boolean) {
        val record = entities[handle.id] ?: return
        if (record.visible == visible) return
        record.visible = visible
        if (visible) {
            scene.addEntity(record.entity)
        } else {
            scene.removeEntity(record.entity)
        }
    }

    /**
     * LOD hook (fase 2.12): drop an entity's contribution to the shadow maps
     * without touching anything else it draws. The renderable keeps its geometry,
     * its materials and its transform, so this can never change a visible shape —
     * only whether it casts a shadow.
     */
    override fun setShadowCaster(handle: EntityHandle, castsShadows: Boolean) {
        val record = entities[handle.id] ?: return
        val live = freshRenderable(record.entity)
        if (live == 0) return
        renderableManager.setCastShadows(live, castsShadows)
    }

    override fun updateMaterials(handle: EntityHandle, materialOfFace: IntArray) {
        val record = entities[handle.id] ?: return
        val live = freshRenderable(record.entity)
        if (live == 0) return
        for (i in record.materials.indices) {
            val materialHandle = materialOfFace.getOrElse(i) { -1 }
            if (materialHandle < 0) continue
            val instance = materialInstances[materialHandle] ?: continue
            renderableManager.setMaterialInstanceAt(live, i, instance)
            record.materials[i] = instance
        }
    }

    private fun boundsOf(desc: MeshDesc): Box {
        val center = FloatArray(3)
        val half = FloatArray(3)
        for (axis in 0..2) {
            center[axis] = (desc.boundsMin[axis] + desc.boundsMax[axis]) * 0.5f
            half[axis] = max(0.0001f, (desc.boundsMax[axis] - desc.boundsMin[axis]) * 0.5f)
        }
        return Box(center, half)
    }

    // ------------------------------------------------------------------ camera

    override fun setCamera(camera: CameraDesc) {
        mark("CAMERA_SET")
        mark("VIEW_SET_CAMERA")
        cameraFov = camera.verticalFovDegrees
        cameraNear = camera.near
        cameraFar = camera.far
        this.camera.lookAt(
            camera.eye[0].toDouble(), camera.eye[1].toDouble(), camera.eye[2].toDouble(),
            camera.target[0].toDouble(), camera.target[1].toDouble(), camera.target[2].toDouble(),
            camera.up[0].toDouble(), camera.up[1].toDouble(), camera.up[2].toDouble()
        )
        updateCameraProjection()
        val diagnostic = diagnostics
        diagnostic.cameraEye = floatArrayOf(camera.eye[0], camera.eye[1], camera.eye[2])
        diagnostic.cameraTarget = floatArrayOf(camera.target[0], camera.target[1], camera.target[2])
        diagnostic.cameraUp = floatArrayOf(camera.up[0], camera.up[1], camera.up[2])
        val dx = camera.target[0] - camera.eye[0]
        val dy = camera.target[1] - camera.eye[1]
        val dz = camera.target[2] - camera.eye[2]
        val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        val inverse = if (length > 0.0001f) 1f / length else 0f
        diagnostic.cameraForward = floatArrayOf(dx * inverse, dy * inverse, dz * inverse)
        diagnostic.cameraNear = camera.near
        diagnostic.cameraFar = camera.far
        diagnostic.cameraFov = camera.verticalFovDegrees
    }

    private fun updateCameraProjection() {
        val aspect = if (viewportHeight > 0) {
            viewportWidth.toDouble() / viewportHeight.toDouble()
        } else {
            1.0
        }
        camera.setProjection(cameraFov.toDouble(), aspect, cameraNear.toDouble(), cameraFar.toDouble(), Camera.Fov.VERTICAL)
        diagnostics.cameraAspect = aspect.toFloat()
    }

    // ------------------------------------------------------------------- light

    override fun setSun(light: LightDesc) {
        if (sunEntity == 0) {
            sunEntity = entityManager.create()
            LightManager.Builder(LightManager.Type.SUN)
                .castShadows(light.castsShadows && enableShadows)
                .direction(light.direction[0], light.direction[1], light.direction[2])
                .color(light.color[0], light.color[1], light.color[2])
                .intensity(light.intensity)
                .build(engine, sunEntity)
            sunInstance = lightManager.getInstance(sunEntity)
            scene.addEntity(sunEntity)
        } else {
            lightManager.setDirection(sunInstance, light.direction[0], light.direction[1], light.direction[2])
            lightManager.setColor(sunInstance, light.color[0], light.color[1], light.color[2])
            lightManager.setIntensity(sunInstance, light.intensity)
            lightManager.setShadowCaster(sunInstance, light.castsShadows && enableShadows)
        }
    }

    override fun setBackgroundColor(color: FloatArray) {
        backgroundColor = floatArrayOf(color[0], color[1], color[2], color.getOrElse(3) { 1f })
        val previous = skybox
        skybox = Skybox.Builder()
            .color(backgroundColor[0], backgroundColor[1], backgroundColor[2], backgroundColor[3])
            .build(engine)
        scene.setSkybox(skybox)
        if (previous != null && engine.isValidSkybox(previous)) {
            engine.destroySkybox(previous)
        }
    }

    /**
     * A uniform ambient term, standing in for the region's WindLight sky until
     * Phase 10 evaluates the real environment settings the simulator sends.
     */
    fun setAmbient(intensity: Float) {
        indirectLight?.let { engine.destroyIndirectLight(it) }
        val sh = FloatArray(9 * 3)
        sh[0] = intensity
        sh[1] = intensity
        sh[2] = intensity
        indirectLight = IndirectLight.Builder()
            .irradiance(3, sh)
            .intensity(1f)
            .build(engine)
        scene.setIndirectLight(indirectLight)
    }

    /** Adaptive resolution: lets Filament trade sharpness for frame rate. */
    fun setDynamicResolutionEnabled(enabled: Boolean, minScale: Float = 0.6f) {
        val options = View.DynamicResolutionOptions()
        options.enabled = enabled
        options.minScale = minScale
        options.maxScale = 1f
        options.homogeneousScaling = true
        view.setDynamicResolutionOptions(options)
    }

    fun setTargetFrameRate(fps: Float) {
        diagnostics.frameRateHintCalls += 1
        diagnostics.frameRateHintFps = fps
        val chain = swapChain ?: return
        if (chain.isFrameRateChangeSupported()) {
            chain.setFrameRate(fps)
        }
    }

    val backendNameValue: String get() = backendName()

    private fun backendName(): String = when (engine.getBackend()) {
        Engine.Backend.OPENGL -> "OpenGL ES"
        Engine.Backend.VULKAN -> "Vulkan"
        else -> engine.getBackend().name
    }

    // -------------------------------------------------------------------- frame

    override fun render(frameTimeNanos: Long): Boolean {
        if (destroyed) return false
        mark("RENDER_BEGIN")
        val diagnostic = diagnostics
        diagnostic.framesAttempted += 1
        val chain = swapChain ?: run {
            diagnostic.fail("render", "sin swap chain (superficie no enlazada)")
            return false
        }
        if (!engine.isValid()) {
            diagnostic.fail("render", "el engine de Filament no es valido")
            return false
        }
        if (!engine.isValidSwapChain(chain)) {
            diagnostic.swapChainValid = false
            diagnostic.fail("render", "el swap chain no es valido")
            return false
        }
        // Everything the driver can refuse is caught here: a throw out of
        // beginFrame/render/endFrame used to take the whole render thread down
        // with it, leaving a black surface and no explanation.
        return try {
            if (!filamentRenderer.beginFrame(chain, frameTimeNanos)) {
                // expected once in a while: no buffer available, or the surface
                // is being recreated. A refusal means the GPU is still busy with
                // the previous frame, so the thread backs off instead of asking
                // again on the next VSYNC with a full scene sync in between.
                //
                // The diagnostics also record the *run* of refusals: the render
                // loop has no pacing, so a refusal is immediately retried, and how
                // long the run gets is what turns "no buffer this instant" into a
                // measurable waste of the render thread (fase 2.13a revision).
                diagnostic.beginFrameFail += 1
                diagnostic.consecutiveBeginFrameFails += 1
                if (diagnostic.consecutiveBeginFrameFails > diagnostic.maxConsecutiveBeginFrameFails) {
                    diagnostic.maxConsecutiveBeginFrameFails = diagnostic.consecutiveBeginFrameFails
                }
                if (diagnostic.consecutiveBeginFrameFails >= BEGINFRAME_BACKOFF_START) {
                    try {
                        Thread.sleep(BEGINFRAME_BACKOFF_MILLIS)
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                return false
            }
            diagnostic.beginFrameOk += 1
            diagnostic.consecutiveBeginFrameFails = 0
            diagnostic.presentedFrames += 1
            mark("RENDER_CALL")
            filamentRenderer.render(view)
            diagnostic.renderCalls += 1
            val renderablesInScene = scene.getRenderableCount()
            val visibleRenderables = view.getVisibleRenderableCount()
            diagnostic.drawnRenderables = visibleRenderables
            diagnostic.extraRenderables = renderablesInScene
            // Fase 2.12: the engine's own culling measurement. Both numbers come
            // from Filament after the frame is submitted — the difference is what
            // the view left out, not a second estimate computed here.
            diagnostic.renderablesInScene = renderablesInScene
            diagnostic.visibleRenderables = visibleRenderables
            diagnostic.culledByFrustum = if (cullingEnabled) {
                (renderablesInScene - visibleRenderables).coerceAtLeast(0)
            } else {
                0
            }
            diagnostic.entitiesInScene = scene.getEntityCount()
            filamentRenderer.endFrame()
            diagnostic.endFrameCalls += 1
            mark("RENDER_END")
            frameCount++
            updateStats(frameTimeNanos)
            true
        } catch (error: Throwable) {
            Log.e(TAG, "frame failed", error)
            diagnostic.fail("frame", error.message ?: error.javaClass.simpleName)
            false
        }
    }

    private fun updateStats(frameTimeNanos: Long) {
        if (lastFrameNanos != 0L) {
            frameAccumulatorMillis += (frameTimeNanos - lastFrameNanos) / 1_000_000.0
            framesSinceStats++
        }
        lastFrameNanos = frameTimeNanos
        if (framesSinceStats >= STATS_WINDOW) {
            stats.frameMillis = frameAccumulatorMillis / framesSinceStats
            stats.framesPerSecond = if (stats.frameMillis > 0.0) 1000.0 / stats.frameMillis else 0.0
            stats.entityCount = entities.size
            stats.meshCount = meshes.size
            stats.textureCount = textures.size
            stats.materialCount = materialInstances.size
            stats.cpuFrameMillis = stats.frameMillis
            stats.triangles = entities.values.sumOf { it.triangles }
            stats.drawCalls = view.getVisibleRenderableCount()
            stats.gpuFrameMillis = gpuFrameMillis()
            frameAccumulatorMillis = 0.0
            framesSinceStats = 0
            diagnostics.fps = stats.framesPerSecond
            diagnostics.frameMillis = stats.frameMillis
            diagnostics.gpuFrameMillis = stats.gpuFrameMillis
            diagnostics.drawnRenderables = stats.drawCalls
        }
    }

    private fun gpuFrameMillis(): Double {
        val history = frameInfoHistory
        val count = filamentRenderer.getFrameInfoHistory(history)
        if (count <= 0) return 0.0
        val duration = history[0].gpuFrameDuration
        if (duration <= 0L || duration == FrameRenderer.FrameInfo.INVALID || duration == FrameRenderer.FrameInfo.PENDING) {
            return 0.0
        }
        return duration / 1_000_000.0
    }

    override fun stats(): RenderStats = stats

    override fun diagnostics(): RenderDiagnostics = diagnostics

    // ------------------------------------------------------- read-back (traza)

    /**
     * What Filament's transform system actually holds for this entity.
     *
     * The point of reading it back instead of re-deriving it: `getTransform`
     * returns "the value set by setTransform()", so a mismatch with the scene's
     * own matrix is a real bug in this layer, and a match is proof that the SL
     * transform reached the backend intact.
     */
    override fun entityLocalMatrix(handle: EntityHandle): FloatArray? {
        // TEST6: lo que la escena entrego (TRS completo) = nodo x renderable.
        val record = entities[handle.id] ?: return null
        val node = transformManager.getTransform(currentNodeInstance(record), FloatArray(16))
        val mesh = transformManager.getTransform(currentMeshInstance(record), FloatArray(16))
        return mat4Mul(node, mesh)
    }

    override fun entityWorldMatrix(handle: EntityHandle): FloatArray? {
        mark("TRANSFORM_GET")
        // TEST6: el mundo que dibuja = mundo del renderable (nodos sin escala).
        val record = entities[handle.id] ?: return null
        return transformManager.getWorldTransform(currentMeshInstance(record), FloatArray(16))
    }

    override fun entityNodeMatrix(handle: EntityHandle): FloatArray? {
        val record = entities[handle.id] ?: return null
        return transformManager.getTransform(currentNodeInstance(record), FloatArray(16))
    }

    override fun entityMeshMatrix(handle: EntityHandle): FloatArray? {
        val record = entities[handle.id] ?: return null
        return transformManager.getTransform(currentMeshInstance(record), FloatArray(16))
    }

    /**
     * TEST8: acceso por reflexion a las lecturas/escrituras por-renderable
     * (AABB, culling, layers, primitivas). Reflexion y no llamada directa para
     * no acoplar la compilacion a una version concreta del API de Filament: si
     * un metodo falta, el informe lo dice y la prueba sigue con el resto.
     */
    private var renderableApiProbed = false
    private var rmGetBox: java.lang.reflect.Method? = null
    private var rmSetBox: java.lang.reflect.Method? = null
    private var rmSetCull: java.lang.reflect.Method? = null
    private var rmGetCull: java.lang.reflect.Method? = null
    private var rmGetLayers: java.lang.reflect.Method? = null
    private var rmGetPrimCount: java.lang.reflect.Method? = null
    private var boxGetter: java.lang.reflect.Method? = null
    private var boxHalfGetter: java.lang.reflect.Method? = null
    private var boxCenterSetter: java.lang.reflect.Method? = null
    private var boxHalfSetter: java.lang.reflect.Method? = null
    /** TEST8: que metodos existen realmente (evidencia, una linea). */
    var renderableApiLine: String = "-"
        private set

    private fun probeRenderableApi() {
        if (renderableApiProbed) return
        renderableApiProbed = true
        val cls = renderableManager.javaClass
        fun take(name: String, vararg params: Class<*>): java.lang.reflect.Method? =
            try {
                cls.getMethod(name, *params)
            } catch (e: NoSuchMethodException) {
                null
            }
        val int1 = Int::class.javaPrimitiveType as Class<*>
        val bool1 = Boolean::class.javaPrimitiveType as Class<*>
        rmGetBox = take("getAxisAlignedBoundingBox", int1, Box::class.java)
        rmSetBox = take("setAxisAlignedBoundingBox", int1, Box::class.java)
        rmSetCull = take("setCulling", int1, bool1)
        rmGetCull = take("getCulling", int1) ?: take("isCulling", int1)
        rmGetLayers = take("getLayerMask", int1)
        rmGetPrimCount = take("getPrimitiveCount", int1)
        val boxMethods = Box::class.java.methods
        boxGetter = boxMethods.firstOrNull { it.name == "getCenter" && it.parameterTypes.isEmpty() }
        boxHalfGetter = boxMethods.firstOrNull { it.name == "getHalfExtent" && it.parameterTypes.isEmpty() }
        boxCenterSetter = boxMethods.firstOrNull { it.name == "setCenter" }
        boxHalfSetter = boxMethods.firstOrNull { it.name == "setHalfExtent" }
        renderableApiLine = "getBox=" + (rmGetBox != null) + " setBox=" + (rmSetBox != null) +
            " setCull=" + (rmSetCull != null) + " getCull=" + (rmGetCull != null) +
            " layers=" + (rmGetLayers != null) + " prims=" + (rmGetPrimCount != null) +
            " boxGet=" + (boxGetter != null && boxHalfGetter != null) +
            " boxSet=" + (boxCenterSetter != null && boxHalfSetter != null)
        Log.i(TAG, "TEST8 API renderable: " + renderableApiLine)
    }

    private fun newBox(): Any? {
        return try {
            val ctor = Box::class.java.getConstructor()
            ctor.newInstance()
        } catch (e: Throwable) {
            try {
                val ctor = Box::class.java.declaredConstructors.firstOrNull() ?: return null
                ctor.isAccessible = true
                if (ctor.parameterTypes.isEmpty()) ctor.newInstance() else null
            } catch (e2: Throwable) {
                null
            }
        }
    }

    private fun boxFloats(box: Any, getter: java.lang.reflect.Method?): FloatArray? {
        if (getter == null) return null
        return try {
            val v = getter.invoke(box) as? FloatArray ?: return null
            if (v.size < 3) null else v
        } catch (e: Throwable) {
            null
        }
    }

    private fun boxSet(box: Any, setter: java.lang.reflect.Method?, v: FloatArray): Boolean {
        if (setter == null) return false
        return try {
            val p = setter.parameterTypes
            if (p.size == 3) setter.invoke(box, v[0], v[1], v[2])
            else setter.invoke(box, v)
            true
        } catch (e: Throwable) {
            false
        }
    }

    override fun entityObjectBox(handle: EntityHandle): FloatArray? {
        mark("AABB_GET")
        val record = entities[handle.id] ?: return null
        probeRenderableApi()
        val get = rmGetBox ?: return null
        val live = freshRenderable(record.entity)
        if (live == 0) return null
        return try {
            val box = newBox() ?: return null
            get.invoke(renderableManager, live, box)
            val c = boxFloats(box, boxGetter)
            val h = boxFloats(box, boxHalfGetter)
            if (c == null || h == null) null else floatArrayOf(c[0], c[1], c[2], h[0], h[1], h[2])
        } catch (e: Throwable) {
            null
        }
    }

    override fun entityLayerMask(handle: EntityHandle): Int {
        val record = entities[handle.id] ?: return -1
        probeRenderableApi()
        val get = rmGetLayers ?: return -1
        val live = freshRenderable(record.entity)
        if (live == 0) return -1
        return try {
            (get.invoke(renderableManager, live) as? Number)?.toInt() ?: -1
        } catch (e: Throwable) {
            -1
        }
    }

    override fun entityCullingEnabled(handle: EntityHandle): Boolean? {
        val record = entities[handle.id] ?: return null
        probeRenderableApi()
        val get = rmGetCull ?: return null
        val live = freshRenderable(record.entity)
        if (live == 0) return null
        return try {
            get.invoke(renderableManager, live) as? Boolean
        } catch (e: Throwable) {
            null
        }
    }

    override fun setEntityCulling(handle: EntityHandle, enabled: Boolean): Boolean {
        mark("RENDERABLE_SET_CULLING")
        val record = entities[handle.id]
        if (record == null) {
            val probeEntity = exactClones[handle.id]?.entity ?: hierProbes[handle.id]?.rendEntity ?: return false
            return try {
                val live = renderableManager.getInstance(probeEntity)
                if (live == 0) {
                    false
                } else {
                    renderableManager.setCulling(live, enabled)
                    true
                }
            } catch (e: Throwable) {
                false
            }
        }
        probeRenderableApi()
        val set = rmSetCull ?: return false
        val live = freshRenderable(record.entity)
        if (live == 0) return false
        return try {
            set.invoke(renderableManager, live, enabled)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * DIAG-VIS testigo (temporal, reversible): material rojo de aislamiento.
     * APIs directas verificadas en Filament 1.75.1 (getPrimitiveCount,
     * get/setMaterialInstanceAt). Solo el testigo; el resto no se toca.
     */
    private var witnessDiagMaterial: MaterialInstance? = null
    private val witnessSavedMaterials = HashMap<Int, Array<MaterialInstance?>>()
    private var witnessDiagOn = false

    private fun witnessMaterial(): MaterialInstance? {
        val existing = witnessDiagMaterial
        if (existing != null) return existing
        return try {
            val created = materials.createWitnessInstance()
            if (created == null) {
                android.util.Log.w(TAG, "DIAG-VIS testigo: no compilo el rojo: " + materials.witnessLastError)
                diagnostics.fail("witnessMaterial", materials.witnessLastError ?: "sin detalle")
                null
            } else {
                witnessDiagMaterial = created
                created
            }
        } catch (error: Throwable) {
            android.util.Log.w(TAG, "DIAG-VIS testigo: excepcion creando rojo", error)
            null
        }
    }

    override fun witnessMaterialLine(): String {
        val compiled = witnessDiagMaterial
        return "UNLIT rojo-solido(1,0,0,1) sin-textura culling=NONE doubleSided=true " +
            "colorWrite=true depthWrite=false depthCulling=false opaco sin-luz " +
            "(instancia=" + (if (compiled != null) "compilada" else "no-compilada: " + (materials.witnessLastError ?: "?")) + ")"
    }

    override fun viewVisibleLayers(): Int {
        return try {
            view.getVisibleLayers()
        } catch (e: Throwable) {
            -1
        }
    }

    override fun setWitnessDiagMaterial(handle: EntityHandle, enabled: Boolean): String {
        mark("WITNESS_DIAG_MATERIAL")
        val record = entities[handle.id] ?: return "testigo sin record en el backend"
        val live = freshRenderable(record.entity)
        if (live == 0) return "testigo sin instancia Renderable viva"
        return try {
            val prims = renderableManager.getPrimitiveCount(live)
            if (!enabled) {
                val saved = witnessSavedMaterials.remove(handle.id)
                if (saved == null) return "testigo ya con material normal (nada que restaurar)"
                for (i in saved.indices) {
                    val orig = saved[i] ?: continue
                    if (i < prims) renderableManager.setMaterialInstanceAt(live, i, orig)
                }
                witnessDiagOn = false
                "testigo restaurado a material normal (" + saved.size + " primitivas)"
            } else {
                val red = witnessMaterial() ?: return "testigo SIN rojo (no compilo: " + (materials.witnessLastError ?: "?") + ")"
                val saved = Array<MaterialInstance?>(prims) { i ->
                    try {
                        renderableManager.getMaterialInstanceAt(live, i)
                    } catch (e: Throwable) {
                        null
                    }
                }
                witnessSavedMaterials[handle.id] = saved
                for (i in 0 until prims) {
                    renderableManager.setMaterialInstanceAt(live, i, red)
                }
                witnessDiagOn = true
                "testigo con ROJO de aislamiento (" + prims + " primitivas, originales guardadas)"
            }
        } catch (e: Throwable) {
            "testigo ERROR: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun forensicKey(r: Float, g: Float, b: Float, back: Boolean, depthOff: Boolean): FilamentMaterials.ForensicKey =
        FilamentMaterials.ForensicKey(
            r, g, b,
            if (back) FilamentMaterials.ForensicFaceCull.BACK else FilamentMaterials.ForensicFaceCull.NONE,
            if (depthOff) FilamentMaterials.ForensicDepth.OFF else FilamentMaterials.ForensicDepth.NORMAL
        )

    fun forensicMaterial(r: Float, g: Float, b: Float, back: Boolean, depthOff: Boolean): MaterialInstance? {
        return try {
            val created = materials.createForensicInstance(forensicKey(r, g, b, back, depthOff))
            if (created == null) {
                android.util.Log.w(TAG, "FORENSIC: no compilo material: " + materials.forensicLastError)
                diagnostics.fail("forensicMaterial", materials.forensicLastError ?: "sin detalle")
            }
            created
        } catch (error: Throwable) {
            android.util.Log.w(TAG, "FORENSIC: excepcion creando material", error)
            null
        }
    }

    fun forensicMaterialLine(r: Float, g: Float, b: Float, back: Boolean, depthOff: Boolean): String {
        return try {
            materials.forensicRequestedLine(forensicKey(r, g, b, back, depthOff))
        } catch (e: Throwable) {
            "material ERROR: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private val forensicSavedMaterials = HashMap<Int, Array<MaterialInstance?>>()

    fun forensicApplyMaterial(handle: EntityHandle, mat: MaterialInstance?): String {
        mark("FORENSIC_MATERIAL")
        val record = entities[handle.id] ?: return "forense: sin record en el backend"
        val live = freshRenderable(record.entity)
        if (live == 0) return "forense: sin instancia Renderable viva"
        if (mat == null) return "forense: material nulo (no compilo)"
        return try {
            val prims = renderableManager.getPrimitiveCount(live)
            val saved = Array<MaterialInstance?>(prims) { i ->
                try {
                    renderableManager.getMaterialInstanceAt(live, i)
                } catch (e: Throwable) {
                    null
                }
            }
            if (!forensicSavedMaterials.containsKey(handle.id)) {
                forensicSavedMaterials[handle.id] = saved
            }
            for (i in 0 until prims) {
                renderableManager.setMaterialInstanceAt(live, i, mat)
            }
            "forense: material aplicado (" + prims + " primitivas, originales guardadas)"
        } catch (e: Throwable) {
            "forense ERROR: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun forensicRestoreMaterial(handle: EntityHandle): String {
        val record = entities[handle.id] ?: return "forense: sin record en el backend"
        val live = freshRenderable(record.entity)
        if (live == 0) return "forense: sin instancia Renderable viva"
        return try {
            val saved = forensicSavedMaterials.remove(handle.id)
                ?: return "forense: nada guardado (nada que restaurar)"
            val prims = renderableManager.getPrimitiveCount(live)
            for (i in saved.indices) {
                val orig = saved[i] ?: continue
                if (i < prims) renderableManager.setMaterialInstanceAt(live, i, orig)
            }
            "forense: material original restaurado (" + saved.size + " primitivas)"
        } catch (e: Throwable) {
            "forense ERROR: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun forensicSetLayer(handle: EntityHandle, layer: Int): String {
        val record = entities[handle.id] ?: return "forense: sin record en el backend"
        val live = freshRenderable(record.entity)
        if (live == 0) return "forense: sin instancia Renderable viva"
        return try {
            renderableManager.setLayerMask(live, 0xFF, layer)
            "forense: layerMask=0x" + layer.toString(16) + " entity=" + record.entity + " instancia=" + live
        } catch (e: Throwable) {
            "forense ERROR: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun forensicWindingReport(handle: EntityHandle, eye: FloatArray, maxTris: Int): String {
        return try {
            val record = entities[handle.id] ?: return "winding: sin record en el backend"
            val mesh = meshes[record.mesh.id] ?: return "winding: sin malla en el backend"
            val desc = mesh.desc
            val triCount = desc.indices.size / 3
            if (triCount == 0) return "winding: sin triangulos"
            val b = StringBuilder(6000)
            b.append("winding testigo: tris=").append(triCount).append(" eye=").append(eye.joinToString(",")).append('\n')
            val stride = MeshDesc.VERTEX_FLOATS
            val cap = max(1, maxTris)
            val step = max(1, triCount / cap)
            var shown = 0
            var t = 0
            while (t < triCount && shown < cap) {
                val i0 = desc.indices[t * 3]
                val i1 = desc.indices[t * 3 + 1]
                val i2 = desc.indices[t * 3 + 2]
                val v0x = desc.vertices[i0 * stride]
                val v0y = desc.vertices[i0 * stride + 1]
                val v0z = desc.vertices[i0 * stride + 2]
                val v1x = desc.vertices[i1 * stride]
                val v1y = desc.vertices[i1 * stride + 1]
                val v1z = desc.vertices[i1 * stride + 2]
                val v2x = desc.vertices[i2 * stride]
                val v2y = desc.vertices[i2 * stride + 1]
                val v2z = desc.vertices[i2 * stride + 2]
                val e1x = v1x - v0x
                val e1y = v1y - v0y
                val e1z = v1z - v0z
                val e2x = v2x - v0x
                val e2y = v2y - v0y
                val e2z = v2z - v0z
                var nx = e1y * e2z - e1z * e2y
                var ny = e1z * e2x - e1x * e2z
                var nz = e1x * e2y - e1y * e2x
                val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                if (nl > 1e-9f) {
                    nx /= nl
                    ny /= nl
                    nz /= nl
                }
                val cx = (v0x + v1x + v2x) / 3f
                val cy = (v0y + v1y + v2y) / 3f
                val cz = (v0z + v1z + v2z) / 3f
                var dx = eye[0] - cx
                var dy = eye[1] - cy
                var dz = eye[2] - cz
                val dl = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                if (dl > 1e-9f) {
                    dx /= dl
                    dy /= dl
                    dz /= dl
                }
                val dot = nx * dx + ny * dy + nz * dz
                b.append("tri=").append(t)
                    .append(" i=").append(i0).append(',').append(i1).append(',').append(i2)
                    .append(" v0=").append(f2(v0x)).append(',').append(f2(v0y)).append(',').append(f2(v0z))
                    .append(" v1=").append(f2(v1x)).append(',').append(f2(v1y)).append(',').append(f2(v1z))
                    .append(" v2=").append(f2(v2x)).append(',').append(f2(v2y)).append(',').append(f2(v2z))
                    .append(" normal=").append(f2(nx)).append(',').append(f2(ny)).append(',').append(f2(nz))
                    .append(" haciaCam=").append(f2(dx)).append(',').append(f2(dy)).append(',').append(f2(dz))
                    .append(" dot=").append(f2(dot)).append('\n')
                shown += 1
                t += step
            }
            b.toString()
        } catch (e: Throwable) {
            "winding ERROR: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private var forensicFlipEntity = 0
    private var forensicFlipInScene = false

    fun forensicBuildFlippedVariant(handle: EntityHandle): String {
        try {
            val record = entities[handle.id] ?: return "flip: sin record en el backend"
            val mesh = meshes[record.mesh.id] ?: return "flip: sin malla en el backend"
            val desc = mesh.desc
            if (desc.indices.size % 3 != 0) return "flip: indices no multiplo de 3"
            if (forensicFlipEntity != 0) return "flip: ya existe entity=" + forensicFlipEntity
            val n = desc.indices.size
            val flipped = IntArray(n)
            var t = 0
            while (t < n) {
                flipped[t] = desc.indices[t]
                flipped[t + 1] = desc.indices[t + 2]
                flipped[t + 2] = desc.indices[t + 1]
                t += 3
            }
            val ib = IndexBuffer.Builder()
                .indexCount(n)
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .build(engine)
            ib.setBuffer(engine, intViewOf(flipped))
            val entityId = entityManager.create()
            val builder = RenderableManager.Builder(1)
            builder.geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vertexBuffer, ib, 0, n)
            builder.material(0, materials.createFallbackInstance() ?: fallbackMaterial)
            builder.boundingBox(boundsOf(desc))
            builder.culling(false)
            builder.castShadows(false)
            builder.receiveShadows(false)
            builder.build(engine, entityId)
            val nodeInst = transformManager.create(entityId)
            val parent = try {
                transformManager.getParent(record.transformInstance)
            } catch (e: Throwable) {
                0
            }
            val local = transformManager.getTransform(record.transformInstance, FloatArray(16))
            transformManager.setTransform(nodeInst, local)
            if (parent != 0) transformManager.setParent(nodeInst, parent)
            val inst = renderableManager.getInstance(entityId)
            forensicFlipEntity = entityId
            forensicFlipInScene = false
            diagnostics.renderablesCreated += 1
            return "flip: entity=" + entityId + " instancia=" + inst + " tris=" + (n / 3) + " parent=" + parent + " (mismo vertexBuffer, indices i0,i2,i1)"
        } catch (e: Throwable) {
            return "flip ERROR: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun forensicVariantVisible(visible: Boolean): String {
        val e = forensicFlipEntity
        if (e == 0) return "flip: variante no construida"
        return try {
            if (visible) {
                scene.addEntity(e)
            } else {
                scene.removeEntity(e)
            }
            forensicFlipInScene = visible
            val inst = freshRenderable(e)
            "flip: visible-pedido=" + visible + " instancia=" + inst
        } catch (ex: Throwable) {
            "flip ERROR: " + (ex.message ?: ex.javaClass.simpleName)
        }
    }

    fun forensicVariantMaterial(mat: MaterialInstance?): String {
        val e = forensicFlipEntity
        if (e == 0) return "flip: variante no construida"
        if (mat == null) return "flip: material nulo (no compilo)"
        return try {
            val inst = freshRenderable(e)
            if (inst == 0) return "flip: sin instancia Renderable viva"
            renderableManager.setMaterialInstanceAt(inst, 0, mat)
            "flip: material forense aplicado instancia=" + inst
        } catch (ex: Throwable) {
            "flip ERROR: " + (ex.message ?: ex.javaClass.simpleName)
        }
    }

    fun forensicVariantCulling(enabled: Boolean): String {
        val e = forensicFlipEntity
        if (e == 0) return "flip: variante no construida"
        return try {
            val inst = freshRenderable(e)
            if (inst == 0) return "flip: sin instancia Renderable viva"
            renderableManager.setCulling(inst, enabled)
            val read = try {
                renderableManager.isCullingEnabled(inst)
            } catch (ex: Throwable) {
                null
            }
            "flip: culling pedido=" + (if (enabled) "ON" else "OFF") + " releido=" + read
        } catch (ex: Throwable) {
            "flip ERROR: " + (ex.message ?: ex.javaClass.simpleName)
        }
    }

    fun forensicRemoveVariant(): String {
        val e = forensicFlipEntity
        if (e == 0) return "flip: nada que retirar"
        return try {
            if (forensicFlipInScene) scene.removeEntity(e)
            entityManager.destroy(e)
            forensicFlipEntity = 0
            forensicFlipInScene = false
            "flip: variante retirada y destruida"
        } catch (ex: Throwable) {
            "flip ERROR: " + (ex.message ?: ex.javaClass.simpleName)
        }
    }

    private val forensicControls = ArrayList<Int>()

    fun forensicCreateControl(
        center: FloatArray,
        right: FloatArray,
        up: FloatArray,
        size: Float,
        mat: MaterialInstance?,
        tag: String
    ): String {
        try {
            if (mat == null) return "control " + tag + ": material nulo (no compilo)"
            val v = FloatArray(9)
            v[0] = center[0] + right[0] * size
            v[1] = center[1] + right[1] * size
            v[2] = center[2] + right[2] * size
            v[3] = center[0] - right[0] * 0.5f * size + up[0] * size
            v[4] = center[1] - right[1] * 0.5f * size + up[1] * size
            v[5] = center[2] - right[2] * 0.5f * size + up[2] * size
            v[6] = center[0] - right[0] * 0.5f * size - up[0] * size
            v[7] = center[1] - right[1] * 0.5f * size - up[1] * size
            v[8] = center[2] - right[2] * 0.5f * size - up[2] * size
            val e1x = v[3] - v[0]
            val e1y = v[4] - v[1]
            val e1z = v[5] - v[2]
            val e2x = v[6] - v[0]
            val e2y = v[7] - v[1]
            val e2z = v[8] - v[2]
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            val vb = VertexBuffer.Builder()
                .vertexCount(3)
                .bufferCount(1)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
                .build(engine)
            vb.setBufferAt(engine, 0, floatViewOf(v))
            val ib = IndexBuffer.Builder()
                .indexCount(3)
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .build(engine)
            ib.setBuffer(engine, intViewOf(intArrayOf(0, 1, 2)))
            val entityId = entityManager.create()
            val builder = RenderableManager.Builder(1)
            builder.geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, 3)
            builder.material(0, mat)
            builder.boundingBox(Box(center, floatArrayOf(size, size, size)))
            builder.culling(false)
            builder.castShadows(false)
            builder.receiveShadows(false)
            builder.build(engine, entityId)
            scene.addEntity(entityId)
            forensicControls.add(entityId)
            val inst = renderableManager.getInstance(entityId)
            diagnostics.renderablesCreated += 1
            return "control " + tag + ": entity=" + entityId + " instancia=" + inst +
                " enEscena-pedido=SI centro=" + center.joinToString(",") +
                " indices=(0,1,2) normal=" + f2(nx) + "," + f2(ny) + "," + f2(nz)
        } catch (e: Throwable) {
            return "control " + tag + " ERROR: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun forensicControlVisible(index: Int, visible: Boolean): String {
        if (index < 0 || index >= forensicControls.size) return "control[" + index + "]: no existe"
        val e = forensicControls[index]
        return try {
            if (visible) {
                scene.addEntity(e)
            } else {
                scene.removeEntity(e)
            }
            "control[" + index + "]: visible-pedido=" + visible + " entity=" + e + " instancia=" + freshRenderable(e)
        } catch (ex: Throwable) {
            "control[" + index + "] ERROR: " + (ex.message ?: ex.javaClass.simpleName)
        }
    }

    fun forensicRemoveControls(): String {
        return try {
            var n = 0
            for (e in forensicControls) {
                try {
                    scene.removeEntity(e)
                } catch (ex: Throwable) {
                }
                try {
                    entityManager.destroy(e)
                    n += 1
                } catch (ex: Throwable) {
                }
            }
            forensicControls.clear()
            "controles retirados=" + n
        } catch (e: Throwable) {
            "controles ERROR: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun forensicSetViewCulling(enabled: Boolean): String {
        setCullingEnabled(enabled)
        val read = try {
            view.isFrustumCullingEnabled()
        } catch (e: Throwable) {
            null
        }
        return "view frustum pedido=" + (if (enabled) "ON" else "OFF") + " releido=" + read
    }

    fun vFlipAuditLine(): String {
        val b = StringBuilder(1400)
        try {
            val names = arrayOf(
                "com.google.android.filament.filamat.MaterialBuilder",
                "com.google.android.filament.Material",
                "com.google.android.filament.MaterialInstance"
            )
            for (n in names) {
                val found = try {
                    Class.forName(n).methods
                        .filter { it.name.contains("lipUV", ignoreCase = true) }
                        .map { it.name + "(" + it.parameterTypes.joinToString(",") { p -> p.simpleName } + ")" }
                } catch (e: Throwable) {
                    listOf("clase-no-cargable:" + e.javaClass.simpleName)
                }
                b.append(n.substringAfterLast('.')).append(".flipUV=")
                    .append(if (found.isEmpty()) "sin-metodos" else found.joinToString("|")).append('\n')
            }
        } catch (e: Throwable) {
            b.append("probe ERROR: ").append(e.message ?: e.javaClass.simpleName).append('\n')
        }
        b.append("builder: el codigo no fija flipUV (MaterialBuilder usado sin setter; rige el default del material compilado)\n")
        b.append("shader: getUV0() en FRAGMENT_SOURCE y FRAGMENT_UNLIT_UV con 1.0-sl.y adicional; LIT_MIN y UNLIT_MIN muestrean getUV0() directo sin 1-v\n")
        b.append("carga: setImage sin volteo (uploadTexture/updateTexture); decoder RGBA8 first-row-first sin volteo\n")
        return b.toString()
    }

    override fun setEntityObjectBox(handle: EntityHandle, box: FloatArray): Boolean {
        if (box.size < 6) return false
        val record = entities[handle.id] ?: return false
        probeRenderableApi()
        val set = rmSetBox ?: return false
        val live = freshRenderable(record.entity)
        if (live == 0) return false
        return try {
            val b = newBox() ?: return false
            if (!boxSet(b, boxCenterSetter, floatArrayOf(box[0], box[1], box[2]))) return false
            if (!boxSet(b, boxHalfSetter, floatArrayOf(box[3], box[4], box[5]))) return false
            set.invoke(renderableManager, live, b)
            true
        } catch (e: Throwable) {
            false
        }
    }

    private class ProbeRecord(
        val entity: Int,
        val instance: Int,
        val renderable: Int,
        val geometryType: String
    )

    private val probes = HashMap<Int, ProbeRecord>()

    override fun probeGeometryType(handle: EntityHandle): String =
        entities[handle.id]?.geometryType ?: "?"

    override fun entityBuildMatrix(handle: EntityHandle): FloatArray? =
        entities[handle.id]?.buildLocal?.copyOf()

    override fun createProbe(source: EntityHandle, geometryType: String, prebuildTransform: FloatArray?): EntityHandle {
        mark("TEST12_PROBE_CREATE")
        val src = entities[source.id] ?: return EntityHandle.INVALID
        val mesh = meshes[src.mesh.id] ?: return EntityHandle.INVALID
        if (mesh.desc.geometryProblem() != null) return EntityHandle.INVALID
        val handle = EntityHandle(nextEntityId++)
        val faceCount = max(1, mesh.desc.faceCount)
        val entityId = entityManager.create()
        try {
            val builder = RenderableManager.Builder(faceCount)
            for (i in 0 until faceCount) {
                val group = minOf(i, mesh.desc.faceCount - 1)
                builder.geometry(
                    i,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    mesh.vertexBuffer,
                    mesh.indexBuffer,
                    mesh.desc.faceFirstIndexAt(group),
                    mesh.desc.faceIndexCountAt(group)
                )
                builder.material(i, src.materials.getOrElse(i) { src.materials[0] } ?: fallbackMaterial)
            }
            builder.boundingBox(boundsOf(mesh.desc))
            when (geometryType) {
                "STATIC_BOUNDS" -> builder.geometryType(RenderableManager.Builder.GeometryType.STATIC_BOUNDS)
                "STATIC" -> builder.geometryType(RenderableManager.Builder.GeometryType.STATIC)
                else -> builder.geometryType(RenderableManager.Builder.GeometryType.DYNAMIC)
            }
            builder.culling(true)
            builder.castShadows(enableShadows)
            builder.receiveShadows(enableShadows)
            builder.build(engine, entityId)
        } catch (error: Throwable) {
            Log.e(TAG, "probe build failed", error)
            entityManager.destroy(entityId)
            return EntityHandle.INVALID
        }
        val inst = transformManager.create(entityId)
        transformManager.setTransform(inst, prebuildTransform ?: Transform(zeroTranslation, identityRotation, nodeUnitScale, null).toMatrix16(FloatArray(16)))
        scene.addEntity(entityId)
        probes[handle.id] = ProbeRecord(entityId, inst, renderableManager.getInstance(entityId), geometryType)
        return handle
    }

    override fun destroyProbe(handle: EntityHandle) {
        val record = probes.remove(handle.id) ?: return
        scene.removeEntity(record.entity)
        engine.destroyEntity(record.entity)
    }

    override fun setProbeTransform(handle: EntityHandle, matrix: FloatArray): Boolean {
        mark("TEST12_PROBE_TRANSFORM")
        val record = probes[handle.id] ?: return false
        val inst = transformManager.getInstance(record.entity)
        if (inst == 0) return false
        transformManager.setTransform(inst, matrix)
        return true
    }

    override fun setProbeCulling(handle: EntityHandle, enabled: Boolean): Boolean {
        mark("RENDERABLE_SET_CULLING")
        val record = probes[handle.id] ?: return false
        val live = try {
            renderableManager.getInstance(record.entity)
        } catch (e: Throwable) {
            0
        }
        if (live == 0) return false
        renderableManager.setCulling(live, enabled)
        return true
    }

    // ------------------------------------------------------------ TEST13

    private class ExactCloneRecord(
        val entity: Int,
        val node: Int,
        var renderableInstance: Int,
        val label: String,
        val sourceHandle: Int,
        val geometryType: String,
        val materials: Array<MaterialInstance?>
    )

    private val exactClones = HashMap<Int, ExactCloneRecord>()
    private val opLabels = HashMap<Int, String>()
    private val opLogs = HashMap<Int, ArrayList<String>>()
    private var opLogArmed = false

    override fun labelHandleForOps(handle: EntityHandle, label: String) {
        opLabels[handle.id] = label
    }

    override fun armBuildOpLog(armed: Boolean) {
        opLogArmed = armed
        if (armed) {
            opLogs.clear()
        }
    }

    private fun opLogFor(handleId: Int): ArrayList<String> {
        var ops = opLogs[handleId]
        if (ops == null) {
            if (opLogs.size >= 128) {
                val eldest = opLogs.keys.firstOrNull()
                if (eldest != null) opLogs.remove(eldest)
            }
            ops = ArrayList()
            opLogs[handleId] = ops
        }
        if (ops.size >= 80) {
            ops = ArrayList(ops.subList(ops.size - 79, ops.size))
            opLogs[handleId] = ops
        }
        return ops
    }

    private fun opTag(label: String, entity: Int, rInst: Int): String {
        val t = Thread.currentThread()
        return "f=" + frameCount + " local=" + label + " entity=" + entity +
            " rInst=" + rInst + " hilo=" + t.name + "#" + t.id
    }

    override fun renderableBuildLog(handle: EntityHandle): String {
        val label = opLabels[handle.id] ?: ("handle#" + handle.id)
        val ops = opLogs[handle.id]
        if (ops == null || ops.isEmpty()) {
            return "camino de " + label + ": BUILD_PATH_HISTORICAL " +
                "(construido antes de TEST13; parametros en fingerprint)"
        }
        val b = StringBuilder(2000)
        b.append("camino de ").append(label).append(":\n")
        for (l in ops) b.append(l).append('\n')
        return b.toString()
    }

    /** La instancia viva del Renderable ahora mismo (la recordada es solo diagnostico). */
    private fun liveRenderable(entity: Int, remembered: Int): Int {
        return try {
            renderableManager.getInstance(entity)
        } catch (e: Throwable) {
            0
        }
    }

    override fun renderableInstanceNow(handle: EntityHandle): Int {
        val record = entities[handle.id]
        if (record != null) return liveRenderable(record.entity, record.renderableInstance)
        val clone = exactClones[handle.id]
        if (clone != null) return liveRenderable(clone.entity, clone.renderableInstance)
        val hier = hierProbes[handle.id] ?: return 0
        return liveRenderable(hier.rendEntity, 0)
    }

    override fun renderableLiveState(handle: EntityHandle): String {
        try {
            val record = entities[handle.id] ?: return "-"
            val entity = record.entity
            val thread = Thread.currentThread().name
            val has = try {
                renderableManager.hasComponent(entity)
            } catch (e: Throwable) {
                false
            }
            val inst = freshRenderable(entity)
            val prims = try {
                if (!has || inst == 0) -1 else renderableManager.getPrimitiveCount(inst)
            } catch (e: Throwable) {
                -1
            }
            val cull = entityCullingEnabled(handle)
            val box = entityObjectBox(handle)
            val boxStr = if (box == null) "-" else box.joinToString(",")
            val layer = entityLayerMask(handle)
            val frustum = try {
                view.isFrustumCullingEnabled()
            } catch (e: Throwable) {
                null
            }
            return "hasComponent=" + has + "\ninstance=" + inst + "\nprimitiveCount=" + prims +
                "\ncullingEnabled=" + cull + "\naabb=" + boxStr + "\nlayerMask=" + layer +
                "\nviewFrustum=" + frustum + "\nthread=" + thread
        } catch (e: Throwable) {
            return "ERROR " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun entityForInstance(instance: Int): Int {
        if (instance == 0) return 0
        for (record in entities.values) {
            try {
                if (renderableManager.getInstance(record.entity) == instance) return record.entity
            } catch (e: Throwable) {
            }
        }
        return 0
    }

    override fun renderableIdentityState(handle: EntityHandle): String {
        try {
            val record = entities[handle.id] ?: return "NO_RECORD"
            val entity = record.entity
            val thread = Thread.currentThread().name + "#" + Thread.currentThread().id
            val has = try {
                renderableManager.hasComponent(entity)
            } catch (e: Throwable) {
                false
            }
            val inst = freshRenderable(entity)
            val from = entityForInstance(inst)
            val cull = try {
                if (!has || inst == 0) null else renderableManager.isCullingEnabled(inst)
            } catch (e: Throwable) {
                null
            }
            val prims = try {
                if (!has || inst == 0) -1 else renderableManager.getPrimitiveCount(inst)
            } catch (e: Throwable) {
                -1
            }
            val box = entityObjectBox(handle)
            return "entity=" + entity + "\nhasComponent=" + has + "\ninstance=" + inst +
                "\nentityFromInstance=" + from + "\ncullingEnabled=" + cull +
                "\nprimitiveCount=" + prims + "\naabb=" + (box?.joinToString(",") ?: "-") +
                "\nthread=" + thread
        } catch (e: Throwable) {
            return "ERROR " + (e.message ?: e.javaClass.simpleName)
        }
    }

    override fun setCullingOnInstance(handle: EntityHandle, instance: Int, enabled: Boolean): String {
        try {
            val record = entities[handle.id] ?: return "NO_RECORD"
            val entity = record.entity
            val thread = Thread.currentThread().name + "#" + Thread.currentThread().id
            if (instance == 0) return "entity=" + entity + " instance=0 NO_OP thread=" + thread
            val owner = entityForInstance(instance)
            if (owner != entity) {
                return "entity=" + entity + " instance=" + instance + " owner=" + owner + " RECHAZADO thread=" + thread
            }
            try {
                renderableManager.setCulling(instance, enabled)
            } catch (e: Throwable) {
                return "entity=" + entity + " instance=" + instance + " SET_THROW=" + e.javaClass.simpleName + " thread=" + thread
            }
            return "entity=" + entity + " instance=" + instance + " set=" + enabled + " setterReached=true thread=" + thread
        } catch (e: Throwable) {
            return "ERROR " + (e.message ?: e.javaClass.simpleName)
        }
    }

    override fun isolateTargetForTest(handle: EntityHandle, targetLayer: Int): String {
        try {
            val record = entities[handle.id] ?: return "NO_RECORD"
            var others = 0
            for (rec in entities.values) {
                if (rec.entity == record.entity) continue
                try {
                    val live = freshRenderable(rec.entity)
                    if (live != 0) {
                        renderableManager.setLayerMask(live, 0xFF, 0x1)
                        others++
                    }
                } catch (e: Throwable) {
                }
            }
            try {
                val live = freshRenderable(record.entity)
                if (live != 0) renderableManager.setLayerMask(live, 0xFF, targetLayer)
            } catch (e: Throwable) {
                return "othersTo0x1=" + others + " target=ERROR"
            }
            return "othersTo0x1=" + others + " target=0x" + targetLayer.toString(16)
        } catch (e: Throwable) {
            return "ERROR " + (e.message ?: e.javaClass.simpleName)
        }
    }

    override fun restoreTestLayers(): String {
        try {
            var n = 0
            for (rec in entities.values) {
                try {
                    val live = freshRenderable(rec.entity)
                    if (live != 0) {
                        renderableManager.setLayerMask(live, 0xFF, 0x1)
                        n++
                    }
                } catch (e: Throwable) {
                }
            }
            return "restoredTo0x1=" + n
        } catch (e: Throwable) {
            return "ERROR " + (e.message ?: e.javaClass.simpleName)
        }
    }

    override fun setRenderableCullingFresh(handle: EntityHandle, enabled: Boolean): String {
        try {
            val record = entities[handle.id] ?: return "NO_RECORD"
            val entity = record.entity
            val thread = Thread.currentThread().name
            val has = try {
                renderableManager.hasComponent(entity)
            } catch (e: Throwable) {
                false
            }
            if (!has) return "has=false entity=" + entity + " thread=" + thread
            val before = freshRenderable(entity)
            if (before == 0) return "has=true instanceBefore=0 entity=" + entity + " thread=" + thread
            try {
                renderableManager.setCulling(before, enabled)
            } catch (e: Throwable) {
                return "has=true instanceBefore=" + before + " SET_THROW=" + e.javaClass.simpleName + " thread=" + thread
            }
            val after = freshRenderable(entity)
            val cullAfter = try {
                if (after == 0) null else renderableManager.isCullingEnabled(after)
            } catch (e: Throwable) {
                null
            }
            return "has=true entity=" + entity + " instanceBefore=" + before + " set=" + enabled +
                " instanceAfter=" + after + " cullingAfter=" + cullAfter + " thread=" + thread
        } catch (e: Throwable) {
            return "ERROR " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun f2(x: Float): String = (kotlin.math.round(x * 100f) / 100f).toString()

    private fun tmShort(m: FloatArray?): String {
        if (m == null || m.size < 16) return "-"
        return "t=" + f2(m[12]) + "," + f2(m[13]) + "," + f2(m[14])
    }

    private fun tmParentLine(inst: Int): String {
        if (inst == 0) return "0 (raiz)"
        val p = try {
            transformManager.getParent(inst)
        } catch (e: Throwable) {
            return "? (error)"
        }
        if (p == 0) return "0 (raiz)"
        for ((id, other) in entities) {
            if (transformManager.getInstance(other.node) == p) return "nodo de handle#" + id
            if (transformManager.getInstance(other.entity) == p) return "renderable de handle#" + id
        }
        for ((id, cl) in exactClones) {
            if (cl.node != 0) {
                try {
                    if (transformManager.getInstance(cl.node) == p) return "nodo de clon handle#" + id
                } catch (_: Throwable) {
                }
            }
            try {
                if (transformManager.getInstance(cl.entity) == p) return "renderable de clon handle#" + id
            } catch (_: Throwable) {
            }
        }
        return "instancia " + p + " (entidad desconocida)"
    }

    override fun renderableFingerprint(handle: EntityHandle): String {
        try {
            val b = StringBuilder(3000)
        val label = opLabels[handle.id] ?: ("handle#" + handle.id)
        val record = entities[handle.id]
        val clone = exactClones[handle.id]
        val entity = record?.entity ?: clone?.entity ?: -1
        val meshH = record?.mesh
        val mesh = if (meshH != null) meshes[meshH.id] else null
        b.append("ficha de ").append(label).append('\n')
        b.append("localID = ").append(label).append('\n')
        b.append("handle = ").append(handle.id).append('\n')
        b.append("Entity = ").append(entity).append('\n')
        val has = try {
            renderableManager.hasComponent(entity)
        } catch (e: Throwable) {
            false
        }
        b.append("hasComponent = ").append(has).append('\n')
        val live = liveRenderable(entity, record?.renderableInstance ?: clone?.renderableInstance ?: 0)
        b.append("RenderableManager instance = ").append(live).append('\n')
        b.append("recordedInstance = ").append(record?.renderableInstance ?: clone?.renderableInstance ?: -1).append('\n')
        if (!has || live == 0) {
            b.append("SIN componente Renderable vivo: resto no aplicable\n")
            return b.toString()
        }
        fun qi(fn: () -> Int): String = try {
            fn().toString()
        } catch (e: Throwable) {
            "?"
        }
        fun qb(fn: () -> Boolean): String = try {
            fn().toString()
        } catch (e: Throwable) {
            "?"
        }
        b.append("primitiveCount = ").append(qi { renderableManager.getPrimitiveCount(live) }).append('\n')
        b.append("instanceCount = ").append(qi { renderableManager.getInstanceCount(live) }).append('\n')
        b.append("cullingEnabled = ").append(qb { renderableManager.isCullingEnabled(live) }).append('\n')
        probeRenderableApi()
        val boxLine = try {
            val get = rmGetBox
            if (get == null) {
                "no expuesto"
            } else {
                val box = newBox()
                if (box == null) {
                    "?"
                } else {
                    get.invoke(renderableManager, live, box)
                    val c = boxFloats(box, boxGetter)
                    val h = boxFloats(box, boxHalfGetter)
                    if (c == null || h == null) "?" else "center=" + f2(c[0]) + "," + f2(c[1]) + "," + f2(c[2]) + " half=" + f2(h[0]) + "," + f2(h[1]) + "," + f2(h[2])
                }
            }
        } catch (e: Throwable) {
            "?"
        }
        b.append("AABB = ").append(boxLine).append('\n')
        b.append("layerMask = sin getter en 1.75.1 (build Ephora no llama layerMask; default 0x1 verificado en fuente C++)\n")
        b.append("priority = ").append(qi { renderableManager.getPriority(live) }).append('\n')
        b.append("channel = ").append(qi { renderableManager.getChannel(live) }).append('\n')
        b.append("blendOrder@0 = ").append(qi { renderableManager.getBlendOrderAt(live, 0) }).append('\n')
        b.append("globalBlendOrder@0 = ").append(qb { renderableManager.isGlobalBlendOrderEnabledAt(live, 0) }).append('\n')
        b.append("fog = ").append(qb { renderableManager.getFogEnabled(live) }).append('\n')
        val lights = StringBuilder(16)
        for (ch in 0..7) {
            try {
                lights.append(if (renderableManager.getLightChannel(live, ch)) '1' else '0')
            } catch (e: Throwable) {
                lights.append('?')
            }
        }
        b.append("lightChannels0-7 = ").append(lights).append('\n')
        b.append("castShadows = ").append(qb { renderableManager.isShadowCaster(live) }).append('\n')
        b.append("receiveShadows = ").append(qb { renderableManager.isShadowReceiver(live) }).append('\n')
        b.append("screenSpaceContactShadows = ").append(qb { renderableManager.isScreenSpaceContactShadowsEnabled(live) }).append('\n')
        b.append("enabledVertexAttributes = POSITION/UV0/TANGENTS (declarados en createMesh Ephora)\n")
        val mats = record?.materials ?: clone?.materials
        if (mats == null) {
            b.append("materialInstance = ? (sin registro)\n")
        } else {
            b.append("materialInstance caras=").append(mats.size).append('\n')
            for (i in mats.indices) {
                val mi = mats[i]
                if (mi == null) {
                    b.append("material@").append(i).append(" = null (fallback en build)\n")
                } else {
                    val nid = try {
                        mi.getNativeObject().toString()
                    } catch (e: Throwable) {
                        "?"
                    }
                    val nm = try {
                        mi.getName() ?: "?"
                    } catch (e: Throwable) {
                        "?"
                    }
                    val matNm = try {
                        mi.getMaterial()?.getName() ?: "?"
                    } catch (e: Throwable) {
                        "?"
                    }
                    b.append("material@").append(i).append(" = native=").append(nid).append(" nombre=").append(nm).append(" material=").append(matNm).append('\n')
                }
            }
        }
        if (mesh == null) {
            b.append("mesh = ? (sin registro)\n")
        } else {
            val vbN = try {
                mesh.vertexBuffer.getNativeObject().toString()
            } catch (e: Throwable) {
                "?"
            }
            val ibN = try {
                mesh.indexBuffer.getNativeObject().toString()
            } catch (e: Throwable) {
                "?"
            }
            val vbC = try {
                mesh.vertexBuffer.getVertexCount().toString()
            } catch (e: Throwable) {
                "?"
            }
            b.append("meshKey = Mesh#").append(meshH?.id ?: -1)
                .append(" descVerts=").append(mesh.desc.vertexCount)
                .append(" descIndices=").append(mesh.desc.indices.size)
                .append(" caras=").append(mesh.desc.faceCount).append('\n')
            b.append("VertexBuffer = native=").append(vbN).append(" vertexCount=").append(vbC).append('\n')
            b.append("IndexBuffer = native=").append(ibN).append(" indexCount=").append(mesh.desc.indices.size).append('\n')
            b.append("primitiveType = TRIANGLES (construccion)\n")
            for (i in 0 until mesh.desc.faceCount) {
                b.append("cara").append(i).append(" offset=").append(mesh.desc.faceFirstIndexAt(i))
                    .append(" count=").append(mesh.desc.faceIndexCountAt(i)).append('\n')
            }
            b.append("minIndex/maxIndex = no registrado en el camino de construccion (Ephora usa first/count por cara)\n")
        }
        b.append("geometryType = ").append(record?.geometryType ?: clone?.geometryType ?: "?").append('\n')
        val nodeInst = if (record != null) currentNodeInstance(record) else 0
        val meshInst = if (record != null) {
            currentMeshInstance(record)
        } else {
            try {
                transformManager.getInstance(entity)
            } catch (e: Throwable) {
                0
            }
        }
        b.append("TransformManager nodeInstance = ").append(nodeInst).append('\n')
        b.append("TransformManager meshInstance = ").append(meshInst).append('\n')
        b.append("parentNodo = ").append(tmParentLine(nodeInst)).append('\n')
        b.append("parentRenderable = ").append(tmParentLine(meshInst)).append('\n')
        val nodeLocal = if (nodeInst != 0) {
            try {
                transformManager.getTransform(nodeInst, FloatArray(16))
            } catch (e: Throwable) {
                null
            }
        } else null
        val meshLocal = if (meshInst != 0) {
            try {
                transformManager.getTransform(meshInst, FloatArray(16))
            } catch (e: Throwable) {
                null
            }
        } else null
        val meshWorld = if (meshInst != 0) {
            try {
                transformManager.getWorldTransform(meshInst, FloatArray(16))
            } catch (e: Throwable) {
                null
            }
        } else null
        b.append("transformLocalNodo = ").append(tmShort(nodeLocal)).append('\n')
        b.append("transformLocalRenderable = ").append(tmShort(meshLocal)).append('\n')
        b.append("transformWorld = ").append(tmShort(meshWorld)).append('\n')
            return b.toString()
        } catch (error: Throwable) {
            return "fingerprint ERROR: " + (error.message ?: error.javaClass.simpleName)
        }
    }

    private class ExactParams(
        val faceCount: Int,
        val firsts: IntArray,
        val counts: IntArray,
        val materials: Array<MaterialInstance?>,
        val box: Box,
        val boxNote: String,
        val geoRule: String,
        val culling: Boolean
    )

    private fun exactParamsOf(record: EntityRecord, mesh: MeshResources, live: Int, forceCulling: Boolean?): ExactParams? {
        val faceCount = max(1, mesh.desc.faceCount)
        val firsts = IntArray(faceCount)
        val counts = IntArray(faceCount)
        for (i in 0 until faceCount) {
            val group = minOf(i, mesh.desc.faceCount - 1)
            firsts[i] = mesh.desc.faceFirstIndexAt(group)
            counts[i] = mesh.desc.faceIndexCountAt(group)
        }
        var box: Box? = null
        var boxNote = "recomputado"
        try {
            probeRenderableApi()
            val get = rmGetBox
            if (get != null) {
                val raw = newBox()
                if (raw != null) {
                    get.invoke(renderableManager, live, raw)
                    val c = boxFloats(raw, boxGetter)
                    val h = boxFloats(raw, boxHalfGetter)
                    if (c != null && h != null) {
                        box = Box(c, h)
                        boxNote = "leido del real"
                    }
                }
            }
        } catch (_: Throwable) {
        }
        val b = box ?: boundsOf(mesh.desc)
        val geoRule = when (record.geometryType) {
            "STATIC_BOUNDS" -> "STATIC_BOUNDS"
            "STATIC" -> "STATIC"
            else -> "DEFAULT"
        }
        val cull = forceCulling ?: try {
            renderableManager.isCullingEnabled(live)
        } catch (e: Throwable) {
            true
        }
        return ExactParams(faceCount, firsts, counts, record.materials, b, boxNote, geoRule, cull)
    }

    private fun buildExactInto(entityId: Int, mesh: MeshResources, params: ExactParams, layerSelect: Int, layerValues: Int, log: (String) -> Unit): Int {
        val builder = RenderableManager.Builder(params.faceCount)
        log("op=BUILDER_CREATE caras=" + params.faceCount)
        for (i in 0 until params.faceCount) {
            builder.geometry(
                i,
                RenderableManager.PrimitiveType.TRIANGLES,
                mesh.vertexBuffer,
                mesh.indexBuffer,
                params.firsts[i],
                params.counts[i]
            )
            log("op=BUILDER_GEOMETRY cara=" + i + " TRIANGLES first=" + params.firsts[i] + " count=" + params.counts[i])
            builder.material(i, params.materials.getOrElse(i) { params.materials[0] } ?: fallbackMaterial)
            log("op=BUILDER_MATERIAL cara=" + i)
        }
        builder.boundingBox(params.box)
        log("op=BUILDER_BOUNDING_BOX " + params.boxNote)
        when (params.geoRule) {
            "STATIC_BOUNDS" -> {
                builder.geometryType(RenderableManager.Builder.GeometryType.STATIC_BOUNDS)
                log("op=BUILDER_GEOMETRY_TYPE STATIC_BOUNDS")
            }
            "STATIC" -> {
                builder.geometryType(RenderableManager.Builder.GeometryType.STATIC)
                log("op=BUILDER_GEOMETRY_TYPE STATIC")
            }
            else -> log("op=BUILDER_GEOMETRY_TYPE no llamada (default DYNAMIC, igual que el build real)")
        }
        builder.culling(params.culling)
        log("op=BUILDER_CULLING " + params.culling)
        builder.layerMask(layerSelect, layerValues)
        log("op=BUILDER_LAYER select=" + layerSelect + " values=" + layerValues)
        log("op=BUILDER_PRIORITY/CHANNEL no llamadas (defaults, igual que el build real)")
        builder.castShadows(enableShadows)
        builder.receiveShadows(enableShadows)
        log("op=BUILDER_SHADOWS " + enableShadows)
        log("op=BUILDER_FOG no llamada (default, igual que el build real)")
        builder.build(engine, entityId)
        val fresh = try {
            renderableManager.getInstance(entityId)
        } catch (e: Throwable) {
            0
        }
        log("op=BUILD_RENDERABLE inst=" + fresh)
        return fresh
    }

    private fun identity16(): FloatArray {
        val m = FloatArray(16)
        m[0] = 1f
        m[5] = 1f
        m[10] = 1f
        m[15] = 1f
        return m
    }

    override fun createExactClone(source: EntityHandle, layerSelect: Int, layerValues: Int, worldMatrix: FloatArray?, withHierarchy: Boolean, label: String): EntityHandle {
        mark("TEST13_CLONE_CREATE")
        val src = entities[source.id] ?: return EntityHandle.INVALID
        val mesh = meshes[src.mesh.id] ?: return EntityHandle.INVALID
        if (mesh.desc.geometryProblem() != null) return EntityHandle.INVALID
        val live = liveRenderable(src.entity, src.renderableInstance)
        if (live == 0) return EntityHandle.INVALID
        val params = exactParamsOf(src, mesh, live, null) ?: return EntityHandle.INVALID
        val handle = EntityHandle(nextEntityId++)
        opLabels[handle.id] = label
        val ops = opLogFor(handle.id)
        val tag0 = opTag(label, -1, 0)
        val entityId = entityManager.create()
        ops.add(tag0 + " op=CREATE_ENTITY")
        var nodeC = 0
        try {
            val log: (String) -> Unit = { op -> ops.add(opTag(label, entityId, 0) + " " + op) }
            val fresh = buildExactInto(entityId, mesh, params, layerSelect, layerValues, log)
            if (fresh == 0) {
                engine.destroyEntity(entityId)
                return EntityHandle.INVALID
            }
            val meshInst = transformManager.create(entityId)
            if (withHierarchy) {
                nodeC = entityManager.create()
                val nodeInstC = transformManager.create(nodeC)
                transformManager.setTransform(nodeInstC, worldMatrix?.copyOf() ?: identity16())
                ops.add(opTag(label, nodeC, nodeInstC) + " op=SET_TRANSFORM padre (mundo cocinado)")
                transformManager.setTransform(meshInst, identity16())
                ops.add(opTag(label, entityId, meshInst) + " op=SET_TRANSFORM hijo (identidad)")
                transformManager.setParent(meshInst, nodeInstC)
                ops.add(opTag(label, entityId, meshInst) + " op=SET_PARENT padre=nodoClon")
            } else {
                transformManager.setTransform(meshInst, worldMatrix?.copyOf() ?: identity16())
                ops.add(opTag(label, entityId, meshInst) + " op=SET_TRANSFORM raiz")
            }
            scene.addEntity(entityId)
            ops.add(opTag(label, entityId, fresh) + " op=SCENE_ADD")
            exactClones[handle.id] = ExactCloneRecord(entityId, nodeC, fresh, label, source.id, src.geometryType, src.materials)
            return handle
        } catch (error: Throwable) {
            Log.e(TAG, "clon exacto fallo", error)
            try {
                scene.removeEntity(entityId)
            } catch (_: Throwable) {
            }
            try {
                engine.destroyEntity(entityId)
            } catch (_: Throwable) {
            }
            if (nodeC != 0) {
                try {
                    engine.destroyEntity(nodeC)
                } catch (_: Throwable) {
                }
            }
            return EntityHandle.INVALID
        }
    }

    override fun destroyExactClone(handle: EntityHandle) {
        val record = exactClones.remove(handle.id) ?: return
        try {
            scene.removeEntity(record.entity)
        } catch (_: Throwable) {
        }
        try {
            engine.destroyEntity(record.entity)
        } catch (_: Throwable) {
        }
        if (record.node != 0) {
            try {
                engine.destroyEntity(record.node)
            } catch (_: Throwable) {
            }
        }
    }

    override fun realSetGeometryRepair(handle: EntityHandle): Boolean {
        mark("TEST13_SETGEOMETRY")
        val record = entities[handle.id] ?: return false
        val mesh = meshes[record.mesh.id] ?: return false
        val label = opLabels[handle.id] ?: ("handle#" + handle.id)
        val ops = opLogFor(handle.id)
        return try {
            val live0 = liveRenderable(record.entity, record.renderableInstance)
            if (live0 == 0) return false
            ops.add(opTag(label, record.entity, live0) + " op=FASE5 instanceAntes=" + live0)
            val faceCount = max(1, mesh.desc.faceCount)
            for (i in 0 until faceCount) {
                val group = minOf(i, mesh.desc.faceCount - 1)
                renderableManager.setGeometryAt(
                    live0, i, RenderableManager.PrimitiveType.TRIANGLES,
                    mesh.vertexBuffer, mesh.indexBuffer,
                    mesh.desc.faceFirstIndexAt(group), mesh.desc.faceIndexCountAt(group)
                )
                ops.add(opTag(label, record.entity, live0) + " op=SET_GEOMETRY_AT cara=" + i)
            }
            probeRenderableApi()
            val get = rmGetBox
            if (get != null) {
                val raw = newBox()
                if (raw != null) {
                    get.invoke(renderableManager, live0, raw)
                    val c = boxFloats(raw, boxGetter)
                    val h = boxFloats(raw, boxHalfGetter)
                    if (c != null && h != null) {
                        renderableManager.setAxisAlignedBoundingBox(live0, Box(c, h))
                        ops.add(opTag(label, record.entity, live0) + " op=SET_AABB original")
                    }
                }
            }
            renderableManager.setCulling(live0, true)
            ops.add(opTag(label, record.entity, live0) + " op=SET_CULLING true")
            val live1 = liveRenderable(record.entity, record.renderableInstance)
            ops.add(opTag(label, record.entity, live1) + " op=FASE5 instanceDespues=" + live1)
            if (live1 != record.renderableInstance && live1 != 0) {
                record.renderableInstance = live1
            }
            true
        } catch (error: Throwable) {
            Log.e(TAG, "FASE5 fallo", error)
            false
        }
    }

    override fun realRebuildSameEntity(handle: EntityHandle): Boolean {
        mark("TEST13_REBUILD_SAME")
        val record = entities[handle.id] ?: return false
        val mesh = meshes[record.mesh.id] ?: return false
        val label = opLabels[handle.id] ?: ("handle#" + handle.id)
        val ops = opLogFor(handle.id)
        return try {
            val live0 = liveRenderable(record.entity, record.renderableInstance)
            if (live0 == 0) return false
            val params = exactParamsOf(record, mesh, live0, true) ?: return false
            val meshInst = currentMeshInstance(record)
            val meshParent = try {
                transformManager.getParent(meshInst)
            } catch (e: Throwable) {
                -1
            }
            val meshLocal = try {
                transformManager.getTransform(meshInst, FloatArray(16))
            } catch (e: Throwable) {
                null
            }
            val wasInScene = record.visible
            ops.add(opTag(label, record.entity, live0) + " op=FASE6 instanceAntes=" + live0)
            if (wasInScene) {
                scene.removeEntity(record.entity)
                ops.add(opTag(label, record.entity, live0) + " op=SCENE_REMOVE")
            }
            renderableManager.destroy(record.entity)
            ops.add(opTag(label, record.entity, 0) + " op=RENDERABLE_DESTROY componente")
            val log: (String) -> Unit = { op -> ops.add(opTag(label, record.entity, 0) + " " + op) }
            val fresh = buildExactInto(record.entity, mesh, params, 0xFF, 0x40, log)
            if (fresh == 0) return false
            record.renderableInstance = fresh
            if (wasInScene) {
                scene.addEntity(record.entity)
                ops.add(opTag(label, record.entity, fresh) + " op=SCENE_ADD")
            }
            val meshInstNow = currentMeshInstance(record)
            val parentNow = try {
                transformManager.getParent(meshInstNow)
            } catch (e: Throwable) {
                -2
            }
            if (parentNow != meshParent && meshParent >= 0) {
                transformManager.setParent(meshInstNow, meshParent)
                ops.add(opTag(label, record.entity, fresh) + " op=SET_PARENT restaurado")
            } else {
                ops.add(opTag(label, record.entity, fresh) + " op=PARENT_VERIFICADO igual")
            }
            if (meshLocal != null) {
                val cur = try {
                    transformManager.getTransform(meshInstNow, FloatArray(16))
                } catch (e: Throwable) {
                    null
                }
                if (cur == null || !cur.contentEquals(meshLocal)) {
                    transformManager.setTransform(meshInstNow, meshLocal)
                    ops.add(opTag(label, record.entity, fresh) + " op=SET_TRANSFORM restaurado")
                } else {
                    ops.add(opTag(label, record.entity, fresh) + " op=TRANSFORM_VERIFICADO igual")
                }
            }
            renderableManager.setCulling(fresh, true)
            ops.add(opTag(label, record.entity, fresh) + " op=SET_CULLING true")
            true
        } catch (error: Throwable) {
            Log.e(TAG, "FASE6 fallo", error)
            false
        }
    }

    override fun realRebuildNewEntity(handle: EntityHandle, withHierarchy: Boolean, label: String): EntityHandle {
        mark("TEST13_REBUILD_NEW")
        val src = entities[handle.id] ?: return EntityHandle.INVALID
        val mesh = meshes[src.mesh.id] ?: return EntityHandle.INVALID
        if (mesh.desc.geometryProblem() != null) return EntityHandle.INVALID
        val live = liveRenderable(src.entity, src.renderableInstance)
        if (live == 0) return EntityHandle.INVALID
        val params = exactParamsOf(src, mesh, live, true) ?: return EntityHandle.INVALID
        val handle2 = EntityHandle(nextEntityId++)
        opLabels[handle2.id] = label
        val ops = opLogFor(handle2.id)
        val entityId = entityManager.create()
        ops.add(opTag(label, entityId, 0) + " op=CREATE_ENTITY nueva")
        try {
            val log: (String) -> Unit = { op -> ops.add(opTag(label, entityId, 0) + " " + op) }
            val fresh = buildExactInto(entityId, mesh, params, 0xFF, 0x40, log)
            if (fresh == 0) {
                engine.destroyEntity(entityId)
                return EntityHandle.INVALID
            }
            val meshInst = transformManager.create(entityId)
            val meshLocal = try {
                transformManager.getTransform(currentMeshInstance(src), FloatArray(16))
            } catch (e: Throwable) {
                identity16()
            }
            transformManager.setTransform(meshInst, meshLocal)
            ops.add(opTag(label, entityId, meshInst) + " op=SET_TRANSFORM igual al real")
            if (withHierarchy) {
                val nodeInst = currentNodeInstance(src)
                transformManager.setParent(meshInst, nodeInst)
                ops.add(opTag(label, entityId, meshInst) + " op=SET_PARENT nodoReal=" + nodeInst + " (temporal)")
            }
            scene.addEntity(entityId)
            ops.add(opTag(label, entityId, fresh) + " op=SCENE_ADD")
            exactClones[handle2.id] = ExactCloneRecord(entityId, 0, fresh, label, handle.id, src.geometryType, src.materials)
            return handle2
        } catch (error: Throwable) {
            Log.e(TAG, "FASE7 fallo", error)
            try {
                scene.removeEntity(entityId)
            } catch (_: Throwable) {
            }
            try {
                engine.destroyEntity(entityId)
            } catch (_: Throwable) {
            }
            return EntityHandle.INVALID
        }
    }

    override fun setRenderableLayer(handle: EntityHandle, select: Int, values: Int): Boolean {
        val entity = entities[handle.id]?.entity ?: exactClones[handle.id]?.entity ?: return false
        return try {
            val live = renderableManager.getInstance(entity)
            if (live == 0) return false
            renderableManager.setLayerMask(live, select, values)
            val label = opLabels[handle.id]
            if (label != null) {
                opLogFor(handle.id).add(opTag(label, entity, live) + " op=SET_LAYER select=" + select + " values=" + values)
            }
            true
        } catch (e: Throwable) {
            false
        }
    }

    override fun viewLayerIsolate(select: Int, values: Int): Int {
        return try {
            val prev = view.getVisibleLayers()
            view.setVisibleLayers(select, values)
            prev
        } catch (e: Throwable) {
            -1
        }
    }

    override fun viewLayerRestore(mask: Int) {
        if (mask < 0) return
        try {
            view.setVisibleLayers(0xFF, mask)
        } catch (_: Throwable) {
        }
    }

    override fun viewFrustumCullingEnabled(): Boolean? {
        return try {
            view.isFrustumCullingEnabled()
        } catch (e: Throwable) {
            null
        }
    }

    override fun setViewFrustumCulling(enabled: Boolean) {
        try {
            view.setFrustumCullingEnabled(enabled)
        } catch (_: Throwable) {
        }
    }

    override fun sceneRemoveEntity(handle: EntityHandle): Boolean {
        val entity = entities[handle.id]?.entity ?: exactClones[handle.id]?.entity ?: return false
        return try {
            scene.removeEntity(entity)
            val label = opLabels[handle.id]
            if (label != null) {
                opLogFor(handle.id).add(opTag(label, entity, 0) + " op=SCENE_REMOVE temporal")
            }
            true
        } catch (e: Throwable) {
            false
        }
    }

    override fun sceneAddEntity(handle: EntityHandle): Boolean {
        val entity = entities[handle.id]?.entity ?: exactClones[handle.id]?.entity ?: return false
        return try {
            scene.addEntity(entity)
            val label = opLabels[handle.id]
            if (label != null) {
                opLogFor(handle.id).add(opTag(label, entity, 0) + " op=SCENE_ADD restaurado")
            }
            true
        } catch (e: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------ TEST14

    private class HierProbeRecord(
        val entities: IntArray,
        val rendEntity: Int,
        val nodeEntities: IntArray,
        val label: String,
        val kind: String,
        val sourceHandle: Int,
        val geometryType: String,
        val materials: Array<MaterialInstance?>
    )

    private val hierProbes = HashMap<Int, HierProbeRecord>()

    private fun handleOfEntity(entity: Int): String {
        if (entity == 0) return "0 (ninguna)"
        for ((id, other) in entities) {
            if (other.entity == entity) return "handle#" + id + " (renderable)"
            if (other.node == entity) return "handle#" + id + " (nodo)"
        }
        for ((id, cl) in exactClones) {
            if (cl.entity == entity) return "clon handle#" + id
            if (cl.node == entity) return "clon handle#" + id + " (nodo)"
        }
        for ((id, hp) in hierProbes) {
            if (hp.rendEntity == entity) return "probe handle#" + id + " (" + hp.kind + ")"
            if (hp.nodeEntities.contains(entity)) return "probe handle#" + id + " (" + hp.kind + " nodo)"
        }
        return "entidad " + entity + " (desconocida)"
    }

    private fun hierInstLines(entity: Int): String {
        val ti = try {
            transformManager.getInstance(entity)
        } catch (e: Throwable) {
            0
        }
        val ri = try {
            renderableManager.getInstance(entity)
        } catch (e: Throwable) {
            0
        }
        return "tInst=" + ti + " rInst=" + ri
    }

    override fun probeOpLog(handle: EntityHandle): String {
        val label = opLabels[handle.id] ?: ("handle#" + handle.id)
        val ops = opLogs[handle.id]
        if (ops == null || ops.isEmpty()) {
            return "camino de " + label + ": SIN_OPS"
        }
        val b = StringBuilder(3000)
        b.append("camino de ").append(label).append(":\n")
        for (l in ops) b.append(l).append('\n')
        return b.toString()
    }

    override fun captureHierarchy(handle: EntityHandle): String {
        try {
            val b = StringBuilder(2000)
            val label = opLabels[handle.id] ?: ("handle#" + handle.id)
            val record = entities[handle.id]
                ?: return "jerarquia de " + label + ": sin registro real"
            b.append("jerarquia de ").append(label).append('\n')
            val node = record.node
            val nodeInst = currentNodeInstance(record)
            val nodeHasT = try {
                transformManager.hasComponent(node)
            } catch (e: Throwable) {
                false
            }
            b.append("nodeEntity = ").append(node).append('\n')
            b.append("nodeInstance = ").append(nodeInst).append(" (viva ahora)\n")
            b.append("nodeHasTransform = ").append(nodeHasT).append('\n')
            val nodeParentE = if (nodeInst != 0) {
                try {
                    transformManager.getParent(nodeInst)
                } catch (e: Throwable) {
                    -1
                }
            } else 0
            b.append("nodeParent = ").append(handleOfEntity(nodeParentE)).append('\n')
            val nodeKids = if (nodeInst != 0) {
                try {
                    transformManager.getChildCount(nodeInst)
                } catch (e: Throwable) {
                    -1
                }
            } else -1
            b.append("nodeChildrenCount = ").append(nodeKids).append('\n')
            if (nodeInst != 0 && nodeKids > 0) {
                try {
                    val kids = transformManager.getChildren(nodeInst, null)
                    b.append("nodeChildren = ").append(kids.map { handleOfEntity(it) }.joinToString(" + ")).append('\n')
                } catch (e: Throwable) {
                    b.append("nodeChildren = ?\n")
                }
            }
            val nodeInScene = try {
                scene.hasEntity(node)
            } catch (e: Throwable) {
                false
            }
            b.append("nodeInScene = ").append(nodeInScene).append('\n')
            val nodeLocal = if (nodeInst != 0) {
                try {
                    transformManager.getTransform(nodeInst, FloatArray(16))
                } catch (e: Throwable) {
                    null
                }
            } else null
            val nodeWorld = if (nodeInst != 0) {
                try {
                    transformManager.getWorldTransform(nodeInst, FloatArray(16))
                } catch (e: Throwable) {
                    null
                }
            } else null
            b.append("nodeLocalTransform = ").append(tmShort(nodeLocal)).append('\n')
            b.append("nodeWorldTransform = ").append(tmShort(nodeWorld)).append('\n')
            val meshInst = currentMeshInstance(record)
            val rendParentE = if (meshInst != 0) {
                try {
                    transformManager.getParent(meshInst)
                } catch (e: Throwable) {
                    -1
                }
            } else 0
            val live = liveRenderable(record.entity, record.renderableInstance)
            val hasR = try {
                renderableManager.hasComponent(record.entity)
            } catch (e: Throwable) {
                false
            }
            b.append("renderableEntity = ").append(record.entity).append('\n')
            b.append("renderableInstance = ").append(live).append(" (viva ahora)\n")
            b.append("renderableHasComponent = ").append(hasR).append('\n')
            b.append("renderableParent = ").append(handleOfEntity(rendParentE)).append('\n')
            b.append("renderableParentEntity = ").append(rendParentE).append('\n')
            val rendInScene = try {
                scene.hasEntity(record.entity)
            } catch (e: Throwable) {
                false
            }
            b.append("renderableInScene = ").append(rendInScene).append('\n')
            val meshLocal = if (meshInst != 0) {
                try {
                    transformManager.getTransform(meshInst, FloatArray(16))
                } catch (e: Throwable) {
                    null
                }
            } else null
            val meshWorld = if (meshInst != 0) {
                try {
                    transformManager.getWorldTransform(meshInst, FloatArray(16))
                } catch (e: Throwable) {
                    null
                }
            } else null
            b.append("renderableLocalTransform = ").append(tmShort(meshLocal)).append('\n')
            b.append("renderableWorldTransform = ").append(tmShort(meshWorld)).append('\n')
            val rel = when {
                rendParentE == 0 -> "ROOT_DIRECT"
                rendParentE == node -> "NODE_ONLY_PARENT -> RENDERABLE_CHILD"
                else -> "OTRO (padre=" + handleOfEntity(rendParentE) + ")"
            }
            b.append("REAL_HIERARCHY = ").append(rel).append('\n')
            val nodeHasR = try {
                renderableManager.hasComponent(node)
            } catch (e: Throwable) {
                false
            }
            b.append("NODE_HAS_RENDERABLE = ").append(if (nodeHasR) "YES" else "NO").append('\n')
            b.append("NODE_HAS_TRANSFORM = ").append(if (nodeHasT) "YES" else "NO").append('\n')
            return b.toString()
        } catch (error: Throwable) {
            return "jerarquia ERROR: " + (error.message ?: error.javaClass.simpleName)
        }
    }

    override fun createHierarchyProbe(source: EntityHandle, kind: String, layerSelect: Int, layerValues: Int, worldMatrix: FloatArray?, label: String): EntityHandle {
        mark("TEST14_PROBE_CREATE")
        val src = entities[source.id] ?: return EntityHandle.INVALID
        val mesh = meshes[src.mesh.id] ?: return EntityHandle.INVALID
        if (mesh.desc.geometryProblem() != null) return EntityHandle.INVALID
        val live = liveRenderable(src.entity, src.renderableInstance)
        if (live == 0) return EntityHandle.INVALID
        val params = exactParamsOf(src, mesh, live, true) ?: return EntityHandle.INVALID
        val handle = EntityHandle(nextEntityId++)
        opLabels[handle.id] = label
        val world = worldMatrix?.copyOf() ?: identity16()
        val created = ArrayList<Int>()
        fun log(entity: Int, op: String) {
            val l = opLabels[handle.id] ?: label
            opLogFor(handle.id).add(opTag(l, entity, 0) + " op=" + op + " " + hierInstLines(entity))
        }
        fun newEntity(tag: String): Int {
            val e = entityManager.create()
            created.add(e)
            log(e, tag)
            return e
        }
        fun buildOn(e: Int): Int {
            val l = opLabels[handle.id] ?: label
            val fresh = buildExactInto(e, mesh, params, layerSelect, layerValues) { op ->
                opLogFor(handle.id).add(opTag(l, e, 0) + " " + op)
            }
            val hasR = try {
                renderableManager.hasComponent(e)
            } catch (x: Throwable) {
                false
            }
            opLogFor(handle.id).add(opTag(l, e, fresh) + " post-BUILD hasComponent=" + hasR + " rInst=" + fresh)
            return fresh
        }
        fun postParent(e: Int, inst: Int) {
            val pe = try {
                transformManager.getParent(inst)
            } catch (x: Throwable) {
                -1
            }
            log(e, "post-SET_PARENT parentEntity=" + pe + " (" + handleOfEntity(pe) + ")")
        }
        fun postTransform(e: Int, inst: Int) {
            val l = try {
                transformManager.getTransform(inst, FloatArray(16))
            } catch (x: Throwable) {
                null
            }
            val w = try {
                transformManager.getWorldTransform(inst, FloatArray(16))
            } catch (x: Throwable) {
                null
            }
            log(e, "post-SET_TRANSFORM local=" + tmShort(l) + " world=" + tmShort(w))
        }
        fun postAdd(e: Int) {
            val h = try {
                scene.hasEntity(e)
            } catch (x: Throwable) {
                false
            }
            log(e, "post-SCENE_ADD hasEntity=" + h)
        }
        fun cullOn(e: Int, fresh: Int) {
            renderableManager.setCulling(fresh, true)
            log(e, "SET_CULLING true")
        }
        fun worldBefore(e: Int) {
            val inst = try {
                transformManager.getInstance(e)
            } catch (x: Throwable) {
                0
            }
            val w = if (inst != 0) {
                try {
                    transformManager.getWorldTransform(inst, FloatArray(16))
                } catch (x: Throwable) {
                    null
                }
            } else null
            log(e, "WORLD_BEFORE_FRAME world=" + tmShort(w))
        }
        try {
            val rec: HierProbeRecord? = when (kind) {
                "H0" -> {
                    val e = newEntity("CREATE_RENDERABLE_ENTITY")
                    val fresh = buildOn(e)
                    if (fresh == 0) null else {
                        val mi = transformManager.create(e)
                        log(e, "CREATE_RENDERABLE_TRANSFORM")
                        transformManager.setTransform(mi, world)
                        log(e, "SET_TRANSFORM_RENDERABLE " + tmShort(world))
                        postTransform(e, mi)
                        scene.addEntity(e)
                        log(e, "SCENE_ADD_RENDERABLE")
                        postAdd(e)
                        cullOn(e, fresh)
                        worldBefore(e)
                        HierProbeRecord(created.toIntArray(), e, intArrayOf(), label, kind, source.id, src.geometryType, src.materials)
                    }
                }
                "H1", "H6" -> {
                    val n = newEntity("CREATE_NODE")
                    val ni = transformManager.create(n, 0, identity16())
                    log(n, "CREATE_NODE_TRANSFORM create(n,root,identity)")
                    transformManager.setTransform(ni, world)
                    log(n, "SET_TRANSFORM_NODE " + tmShort(world))
                    postTransform(n, ni)
                    val e = newEntity("CREATE_RENDERABLE_ENTITY")
                    val fresh = buildOn(e)
                    if (fresh == 0) null else {
                        val ri = transformManager.create(e, ni, identity16())
                        log(e, "CREATE_RENDERABLE_TRANSFORM create(e,node,identity)")
                        postParent(e, ri)
                        postTransform(e, ri)
                        scene.addEntity(e)
                        log(e, "SCENE_ADD_RENDERABLE")
                        postAdd(e)
                        cullOn(e, fresh)
                        worldBefore(e)
                        HierProbeRecord(created.toIntArray(), e, intArrayOf(n), label, kind, source.id, src.geometryType, src.materials)
                    }
                }
                "H2" -> {
                    val n = newEntity("CREATE_NODE")
                    val ni = transformManager.create(n)
                    log(n, "CREATE_NODE_TRANSFORM")
                    transformManager.setTransform(ni, world)
                    log(n, "SET_TRANSFORM_NODE " + tmShort(world))
                    postTransform(n, ni)
                    val e = newEntity("CREATE_RENDERABLE_ENTITY")
                    val fresh = buildOn(e)
                    if (fresh == 0) null else {
                        val ri = transformManager.create(e)
                        log(e, "CREATE_RENDERABLE_TRANSFORM")
                        transformManager.setTransform(ri, identity16())
                        log(e, "SET_TRANSFORM_RENDERABLE identity")
                        transformManager.setParent(ri, ni)
                        log(e, "SET_PARENT node")
                        postParent(e, ri)
                        scene.addEntity(e)
                        log(e, "SCENE_ADD_RENDERABLE")
                        postAdd(e)
                        cullOn(e, fresh)
                        worldBefore(e)
                        HierProbeRecord(created.toIntArray(), e, intArrayOf(n), label, kind, source.id, src.geometryType, src.materials)
                    }
                }
                "H3" -> {
                    val n = newEntity("CREATE_NODE")
                    val e = newEntity("CREATE_RENDERABLE_ENTITY")
                    val fresh = buildOn(e)
                    if (fresh == 0) null else {
                        scene.addEntity(e)
                        log(e, "SCENE_ADD_RENDERABLE")
                        postAdd(e)
                        val ni = transformManager.create(n)
                        log(n, "CREATE_NODE_TRANSFORM")
                        val ri = transformManager.create(e)
                        log(e, "CREATE_RENDERABLE_TRANSFORM")
                        transformManager.setParent(ri, ni)
                        log(e, "SET_PARENT node")
                        postParent(e, ri)
                        transformManager.setTransform(ni, world)
                        log(n, "SET_TRANSFORM_NODE " + tmShort(world))
                        postTransform(n, ni)
                        transformManager.setTransform(ri, identity16())
                        log(e, "SET_TRANSFORM_RENDERABLE identity")
                        postTransform(e, ri)
                        cullOn(e, fresh)
                        worldBefore(e)
                        HierProbeRecord(created.toIntArray(), e, intArrayOf(n), label, kind, source.id, src.geometryType, src.materials)
                    }
                }
                "H4" -> {
                    val n = newEntity("CREATE_NODE")
                    val e = newEntity("CREATE_RENDERABLE_ENTITY")
                    val fresh = buildOn(e)
                    if (fresh == 0) null else {
                        val ni = transformManager.create(n)
                        log(n, "CREATE_NODE_TRANSFORM")
                        val ri = transformManager.create(e)
                        log(e, "CREATE_RENDERABLE_TRANSFORM")
                        transformManager.setTransform(ni, world)
                        log(n, "SET_TRANSFORM_NODE " + tmShort(world))
                        postTransform(n, ni)
                        transformManager.setTransform(ri, identity16())
                        log(e, "SET_TRANSFORM_RENDERABLE identity")
                        transformManager.setParent(ri, ni)
                        log(e, "SET_PARENT node (despues de transform)")
                        postParent(e, ri)
                        scene.addEntity(e)
                        log(e, "SCENE_ADD_RENDERABLE")
                        postAdd(e)
                        cullOn(e, fresh)
                        worldBefore(e)
                        HierProbeRecord(created.toIntArray(), e, intArrayOf(n), label, kind, source.id, src.geometryType, src.materials)
                    }
                }
                "H5" -> {
                    val n = newEntity("CREATE_NODE")
                    val e = newEntity("CREATE_RENDERABLE_ENTITY")
                    val fresh = buildOn(e)
                    if (fresh == 0) null else {
                        val ni = transformManager.create(n)
                        log(n, "CREATE_NODE_TRANSFORM")
                        val ri = transformManager.create(e)
                        log(e, "CREATE_RENDERABLE_TRANSFORM")
                        transformManager.setParent(ri, ni)
                        log(e, "SET_PARENT node (antes de transform)")
                        postParent(e, ri)
                        transformManager.setTransform(ni, world)
                        log(n, "SET_TRANSFORM_NODE " + tmShort(world))
                        postTransform(n, ni)
                        transformManager.setTransform(ri, identity16())
                        log(e, "SET_TRANSFORM_RENDERABLE identity")
                        scene.addEntity(e)
                        log(e, "SCENE_ADD_RENDERABLE")
                        postAdd(e)
                        cullOn(e, fresh)
                        worldBefore(e)
                        HierProbeRecord(created.toIntArray(), e, intArrayOf(n), label, kind, source.id, src.geometryType, src.materials)
                    }
                }
                "H7" -> {
                    val n = newEntity("CREATE_NODE")
                    val ni = transformManager.create(n)
                    log(n, "CREATE_NODE_TRANSFORM")
                    transformManager.setTransform(ni, identity16())
                    log(n, "SET_TRANSFORM_NODE identity")
                    val e = newEntity("CREATE_RENDERABLE_ENTITY")
                    val fresh = buildOn(e)
                    if (fresh == 0) null else {
                        val ri = transformManager.create(e)
                        log(e, "CREATE_RENDERABLE_TRANSFORM")
                        transformManager.setTransform(ri, world)
                        log(e, "SET_TRANSFORM_RENDERABLE " + tmShort(world))
                        postTransform(e, ri)
                        transformManager.setParent(ri, ni)
                        log(e, "SET_PARENT node")
                        postParent(e, ri)
                        scene.addEntity(e)
                        log(e, "SCENE_ADD_RENDERABLE")
                        postAdd(e)
                        cullOn(e, fresh)
                        worldBefore(e)
                        HierProbeRecord(created.toIntArray(), e, intArrayOf(n), label, kind, source.id, src.geometryType, src.materials)
                    }
                }
                "H8A", "H8B" -> {
                    val rootFirst = kind == "H8B"
                    val r = newEntity("CREATE_ROOT")
                    val rii = transformManager.create(r)
                    log(r, "CREATE_ROOT_TRANSFORM")
                    transformManager.setTransform(rii, if (rootFirst) world else identity16())
                    log(r, "SET_TRANSFORM_ROOT " + tmShort(if (rootFirst) world else identity16()))
                    val n = newEntity("CREATE_NODE")
                    val ni = transformManager.create(n)
                    log(n, "CREATE_NODE_TRANSFORM")
                    transformManager.setTransform(ni, if (rootFirst) identity16() else world)
                    log(n, "SET_TRANSFORM_NODE " + tmShort(if (rootFirst) identity16() else world))
                    transformManager.setParent(ni, rii)
                    log(n, "SET_PARENT root")
                    postParent(n, ni)
                    val e = newEntity("CREATE_RENDERABLE_ENTITY")
                    val fresh = buildOn(e)
                    if (fresh == 0) null else {
                        val rei = transformManager.create(e)
                        log(e, "CREATE_RENDERABLE_TRANSFORM")
                        transformManager.setTransform(rei, identity16())
                        log(e, "SET_TRANSFORM_RENDERABLE identity")
                        transformManager.setParent(rei, ni)
                        log(e, "SET_PARENT node")
                        postParent(e, rei)
                        scene.addEntity(e)
                        log(e, "SCENE_ADD_RENDERABLE")
                        postAdd(e)
                        cullOn(e, fresh)
                        worldBefore(e)
                        HierProbeRecord(created.toIntArray(), e, intArrayOf(r, n), label, kind, source.id, src.geometryType, src.materials)
                    }
                }
                else -> null
            }
            if (rec == null) {
                for (e in created) {
                    try {
                        scene.removeEntity(e)
                    } catch (_: Throwable) {
                    }
                    try {
                        engine.destroyEntity(e)
                    } catch (_: Throwable) {
                    }
                }
                return EntityHandle.INVALID
            }
            hierProbes[handle.id] = rec
            return handle
        } catch (error: Throwable) {
            Log.e(TAG, "probe jerarquia fallo", error)
            for (e in created) {
                try {
                    scene.removeEntity(e)
                } catch (_: Throwable) {
                }
                try {
                    engine.destroyEntity(e)
                } catch (_: Throwable) {
                }
            }
            return EntityHandle.INVALID
        }
    }

    override fun destroyHierarchyProbe(handle: EntityHandle) {
        val record = hierProbes.remove(handle.id) ?: return
        for (e in record.entities) {
            try {
                scene.removeEntity(e)
            } catch (_: Throwable) {
            }
            try {
                engine.destroyEntity(e)
            } catch (_: Throwable) {
            }
        }
    }

    override fun setProbeNodeTransform(handle: EntityHandle, matrix: FloatArray): Boolean {
        mark("TEST14_PROBE_NODE_MOVE")
        val record = hierProbes[handle.id] ?: return false
        val parent = record.nodeEntities.lastOrNull() ?: return false
        return try {
            val inst = transformManager.getInstance(parent)
            if (inst == 0) return false
            transformManager.setTransform(inst, matrix)
            val l = opLabels[handle.id] ?: record.label
            val w = try {
                transformManager.getWorldTransform(inst, FloatArray(16))
            } catch (x: Throwable) {
                null
            }
            opLogFor(handle.id).add(opTag(l, parent, 0) + " op=SET_TRANSFORM_NODE " + tmShort(matrix) + " world=" + tmShort(w) + " " + hierInstLines(parent))
            true
        } catch (e: Throwable) {
            false
        }
    }

    override fun probeHierarchySnapshot(handle: EntityHandle): String {
        try {
            val b = StringBuilder(2000)
            val record = hierProbes[handle.id] ?: return "snapshot: sin probe"
            val label = opLabels[handle.id] ?: record.label
            val t = Thread.currentThread()
            b.append("snapshot de ").append(label).append(" f=").append(frameCount)
                .append(" hilo=").append(t.name).append("#").append(t.id).append('\n')
            val ri = try {
                renderableManager.getInstance(record.rendEntity)
            } catch (e: Throwable) {
                0
            }
            val cull = try {
                renderableManager.isCullingEnabled(ri)
            } catch (e: Throwable) {
                null
            }
            val inScene = try {
                scene.hasEntity(record.rendEntity)
            } catch (e: Throwable) {
                false
            }
            val rInst = try {
                transformManager.getInstance(record.rendEntity)
            } catch (e: Throwable) {
                0
            }
            val rLocal = if (rInst != 0) {
                try {
                    transformManager.getTransform(rInst, FloatArray(16))
                } catch (e: Throwable) {
                    null
                }
            } else null
            val rWorld = if (rInst != 0) {
                try {
                    transformManager.getWorldTransform(rInst, FloatArray(16))
                } catch (e: Throwable) {
                    null
                }
            } else null
            val rParent = if (rInst != 0) {
                try {
                    transformManager.getParent(rInst)
                } catch (e: Throwable) {
                    -1
                }
            } else 0
            b.append("WORLD_AFTER_FRAME renderable=").append(tmShort(rWorld)).append('\n')
            b.append("renderable local=").append(tmShort(rLocal))
                .append(" parent=").append(handleOfEntity(rParent))
                .append(" culling=").append(cull?.toString() ?: "?")
                .append(" inScene=").append(inScene).append('\n')
            for ((i, n) in record.nodeEntities.withIndex()) {
                val ni = try {
                    transformManager.getInstance(n)
                } catch (e: Throwable) {
                    0
                }
                val nw = if (ni != 0) {
                    try {
                        transformManager.getWorldTransform(ni, FloatArray(16))
                    } catch (e: Throwable) {
                        null
                    }
                } else null
                val nl = if (ni != 0) {
                    try {
                        transformManager.getTransform(ni, FloatArray(16))
                    } catch (e: Throwable) {
                        null
                    }
                } else null
                val np = if (ni != 0) {
                    try {
                        transformManager.getParent(ni)
                    } catch (e: Throwable) {
                        -1
                    }
                } else 0
                b.append("nodo").append(i).append(" world=").append(tmShort(nw))
                    .append(" local=").append(tmShort(nl))
                    .append(" parent=").append(handleOfEntity(np)).append('\n')
            }
            return b.toString()
        } catch (error: Throwable) {
            return "snapshot ERROR: " + (error.message ?: error.javaClass.simpleName)
        }
    }

    override fun threadAuditLine(): String {
        val order = arrayOf(
            "ENGINE_CREATE", "ENGINE_THREAD_OWNER", "VIEW_CREATE", "CAMERA_CREATE",
            "CAMERA_SET", "VIEW_SET_CAMERA", "VIEW_SET_FRUSTUM_CULLING",
            "RENDERABLE_SET_CULLING", "TRANSFORM_SET", "TRANSFORM_GET", "AABB_GET",
            "TEST12_PROBE_CREATE", "TEST12_PROBE_TRANSFORM",
            "RENDER_BEGIN", "RENDER_CALL", "RENDER_END"
        )
        val b = StringBuilder(900)
        for (k in order) {
            b.append(k).append(" = ").append(threadMarks[k] ?: "-").append('\n')
        }
        return b.toString()
    }

    override fun entityMaterialLine(handle: EntityHandle): String {
        val record = entities[handle.id] ?: return "-"
        probeRenderableApi()
        val live = freshRenderable(record.entity)
        val prims = try {
            if (live == 0) null
            else rmGetPrimCount?.invoke(renderableManager, live) as? Number
        } catch (e: Throwable) {
            null
        }
        return "inst=" + live + " prims=" + (prims?.toInt()?.toString() ?: "?") +
            " caras=" + record.materials.size
    }

    /** TEST8.5: layers visibles de la vista (reflexion; "-" si no disponible). */
    fun viewLayersLine(): String {
        return try {
            val m = view.javaClass.methods.firstOrNull { it.name == "getVisibleLayers" && it.parameterTypes.isEmpty() }
                ?: return "vista layers: no expuesto por esta version"
            val v = m.invoke(view) as? Number ?: return "vista layers: ilegible"
            "vista layers=" + v.toInt()
        } catch (e: Throwable) {
            "vista layers: error"
        }
    }

    override fun entityExists(handle: EntityHandle): Boolean = entities.containsKey(handle.id)

    override fun entityInScene(handle: EntityHandle): Boolean? {
        if (destroyed) return null
        val record = entities[handle.id] ?: return false
        return try {
            scene.hasEntity(record.entity)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Read-only identification of the transform component behind one entity
     * (fase 2.10, diagnostico).
     *
     * Nothing is written: the only calls are `getInstance`, `getParent`,
     * `getChildCount`, `getTransform` and `getWorldTransform`. It exists because
     * a matrix comparison alone cannot tell "these bytes are wrong" from "these
     * bytes belong to another entity", and this phase has to tell those apart.
     */
    override fun entityProbe(handle: EntityHandle): EntityProbe? {
        if (destroyed) {
            return null
        }
        val record = entities[handle.id] ?: return null
        return try {
            // TEST6: la ficha describe el NODO (ahi viven jerarquia, parent e
            // identidad); el renderable solo aporta sus instancias.
            val entityId = record.node
            val actual = transformManager.getInstance(entityId)
            val actualRenderable = renderableManager.getInstance(record.entity)
            // The authoritative parent: asked through the instance the entity
            // owns now. The remembered instance is asked separately, because
            // after a component compaction it answers for a different entity.
            val parentEntity = if (actual == 0) 0 else transformManager.getParent(actual)
            val parentCached = if (record.transformInstance == 0) {
                0
            } else {
                transformManager.getParent(record.transformInstance)
            }
            var parentHandle = 0
            if (parentEntity != 0) {
                for ((id, other) in entities) {
                    if (other.node == parentEntity) {
                        parentHandle = id
                        break
                    }
                }
            }
            EntityProbe(
                handle = handle.id,
                filamentEntity = entityId,
                cachedTransformInstance = record.transformInstance,
                actualTransformInstance = actual,
                cachedRenderableInstance = record.renderableInstance,
                actualRenderableInstance = actualRenderable,
                parentEntity = parentEntity,
                parentEntityFromCached = parentCached,
                parentHandle = parentHandle,
                childCount = if (actual == 0) 0 else (transformManager.getChildCount(actual) - 1).coerceAtLeast(0),
                mesh = "Mesh#" + record.mesh.id,
                triangles = record.triangles,
                visible = record.visible,
                cachedLocal = instanceMatrix(record.transformInstance, world = false),
                actualLocal = instanceMatrix(actual, world = false),
                cachedWorld = instanceMatrix(record.transformInstance, world = true),
                actualWorld = instanceMatrix(actual, world = true)
            )
        } catch (error: Throwable) {
            // A read-back must never be able to take a frame down.
            Log.w(TAG, "no se pudo leer la ficha de la entidad " + handle, error)
            diagnostics.fail("entityProbe", error.message ?: error.javaClass.simpleName)
            null
        }
    }

    /** `getTransform`/`getWorldTransform` for a component instance, null for none. */
    private fun instanceMatrix(instance: Int, world: Boolean): FloatArray? {
        if (instance == 0) {
            return null
        }
        return if (world) {
            transformManager.getWorldTransform(instance, FloatArray(16))
        } else {
            transformManager.getTransform(instance, FloatArray(16))
        }
    }

    override fun cameraSnapshot(): CameraSnapshot? {
        if (destroyed) return null
        return try {
            CameraSnapshot(
                viewMatrix = camera.getViewMatrix(FloatArray(16)),
                projectionMatrix = camera.getProjectionMatrix(DoubleArray(16)),
                eye = camera.getPosition(FloatArray(3)),
                forward = camera.getForwardVector(FloatArray(3)),
                left = camera.getLeftVector(FloatArray(3)),
                up = camera.getUpVector(FloatArray(3)),
                near = camera.getNear(),
                far = camera.getCullingFar()
            )
        } catch (error: Throwable) {
            // A read-back must never be able to take the frame down: if the
            // native camera is gone the trace says so and rendering continues.
            Log.w(TAG, "no se pudo leer la camara del motor", error)
            diagnostics.fail("cameraSnapshot", error.message ?: error.javaClass.simpleName)
            null
        }
    }

    override val frustumCullingEnabled: Boolean?
        get() = if (destroyed) null else try {
            view.isFrustumCullingEnabled()
        } catch (error: Throwable) {
            null
        }

    /** Number of frames drawn since the renderer was created (debug overlay). */
    val renderedFrames: Long get() = frameCount

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        ready = false
        // The last error is deliberately NOT cleared: if the renderer is torn
        // down after a failure, the HUD still has to be able to show why.
        diagnostics.engineValid = false
        diagnostics.swapChainValid = false

        detachSurface()
        for (handle in entities.keys.toList()) {
            destroyEntity(EntityHandle(handle))
        }
        entities.clear()
        for (handle in materialInstances.keys.toList()) {
            destroyMaterial(MaterialHandle(handle))
        }
        materialInstances.clear()
        for (handle in textures.keys.toList()) {
            destroyTexture(TextureHandle(handle))
        }
        textures.clear()
        for (handle in meshes.keys.toList()) {
            destroyMesh(MeshHandle(handle))
        }
        meshes.clear()

        skybox?.let { if (engine.isValidSkybox(it)) engine.destroySkybox(it) }
        skybox = null
        indirectLight?.let { if (engine.isValidIndirectLight(it)) engine.destroyIndirectLight(it) }
        indirectLight = null
        if (sunEntity != 0) {
            scene.removeEntity(sunEntity)
            engine.destroyEntity(sunEntity)
            sunEntity = 0
        }
        scene.removeEntity(cameraEntity)
        engine.destroyCameraComponent(cameraEntity)
        engine.destroyEntity(cameraEntity)
        engine.destroyTexture(fallbackTexture)
        engine.destroyMaterialInstance(fallbackMaterial)
        diagFallbackMaterial?.let { engine.destroyMaterialInstance(it) }
        diagFallbackMaterial = null
        witnessDiagMaterial?.let { engine.destroyMaterialInstance(it) }
        witnessDiagMaterial = null
        witnessSavedMaterials.clear()
        witnessDiagOn = false
        materials.destroy()

        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(filamentRenderer)
        engine.destroy()
    }

    private companion object {
        const val TAG = "FilamentRenderer"
        const val STATS_WINDOW = 20

        /**
         * Back-off when the swap chain keeps refusing buffers: the GPU is still
         * busy, so the thread sleeps instead of burning a full scene sync per
         * VSYNC on frames that present nothing (fase 11).
         */
        const val BEGINFRAME_BACKOFF_START = 5
        const val BEGINFRAME_BACKOFF_MILLIS = 30L

        /**
         * The rate the swap chain is asked to run at, and the rate the render
         * loop paces itself to (fase 2.13a revision 2). It is a hint, not a
         * contract: the platform may round it to a rate the display supports, and
         * `isFrameRateChangeSupported()` says whether it was accepted at all.
         */
        const val TARGET_FRAME_RATE = 60f

        /**
         * Debug ambient level for this phase: a uniform ambient term standing in
         * for the region's WindLight sky, which Phase 10 evaluates from the
         * environment settings the simulator sends. It is deliberately generous
         * so that a face pointing away from the sun is still clearly visible —
         * an object that is present but pitch black is indistinguishable from an
         * object that is missing, and this phase has to tell those apart.
         */
        const val AMBIENT_LEVEL = 0.7f

        /** TANGENTS is a SHORT4 (four 16-bit components), so its stride is 8 bytes. */
        const val TANGENT_STRIDE = 8

        /** Direction the light travels, normalised; a mid-morning sun. */
        val DEFAULT_SUN = LightDesc(
            direction = floatArrayOf(-0.42f, -0.72f, -0.55f),
            color = floatArrayOf(1.0f, 0.97f, 0.90f),
            intensity = 100_000f,
            castsShadows = true
        )

        // NOTE: no Filament object may be created here. A companion object's
        // properties are initialized by this class's <clinit>, which runs before
        // the constructor body — i.e. before Filament.init(). TextureSampler's
        // constructor calls native code, so building the samplers here made the
        // whole class unloadable. They are created in `init` instead, and the
        // regression is covered by `renderPipelineChecks`.
    }
}

internal fun floatViewOf(values: FloatArray): FloatBuffer {
    val buffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
    val view = buffer.asFloatBuffer()
    view.put(values)
    view.rewind()
    return view
}

internal fun intViewOf(values: IntArray): IntBuffer {
    val buffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
    val view = buffer.asIntBuffer()
    view.put(values)
    view.rewind()
    return view
}

internal fun shortViewOf(values: ShortArray): ShortBuffer {
    val buffer = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder())
    val view = buffer.asShortBuffer()
    view.put(values)
    view.rewind()
    return view
}
