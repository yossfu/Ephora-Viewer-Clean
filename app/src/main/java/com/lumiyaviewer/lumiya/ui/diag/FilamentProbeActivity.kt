package com.lumiyaviewer.lumiya.ui.diag

import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View as FilamentView
import com.google.android.filament.Viewport
import com.google.android.filament.filamat.MaterialBuilder
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.databinding.ActivityFilamentProbeBinding
import com.lumiyaviewer.lumiya.renderer.RenderDiagnostics
import com.lumiyaviewer.lumiya.renderer.filament.FilamentBootstrap
import com.lumiyaviewer.lumiya.ui.common.BaseActivity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.max

/**
 * The isolated Filament test: an Android `Activity` + `SurfaceView` + Filament,
 * and **nothing else**. No login, no UDP, no `SLWorld`, no `SLScene`, no
 * avatars, no terrain, no textures, no JPEG2000, no `slcore`.
 *
 * It exists because a black screen has two very different possible causes:
 * the graphics stack on this device, or everything the viewer layers on top of
 * it. This screen removes the second possibility entirely — if Filament itself
 * cannot start here, no amount of protocol work can matter, and if it does
 * start, the fault is above.
 *
 * Every step is run separately and reported as START / SUCCESS / FAIL:
 *
 * ```
 * FILAMENT_INIT → ENGINE_CREATE → RENDERER_CREATE → SCENE_CREATE → VIEW_CREATE
 * → CAMERA_CREATE → SWAPCHAIN_CREATE → VERTEX/INDEX BUFFER → MATERIAL_COMPILE
 * → RENDERABLE_CREATE → SCENE_ADD → FRAME LOOP
 * ```
 *
 * Anything that throws is recorded with its class, message, cause chain and
 * full stack trace (never "Filament could not start"), and the whole report is
 * on screen — selectable and scrollable — and on disk, so it can be copied off
 * the device without adb.
 *
 * The geometry is deliberately trivial and solid: one red triangle in front of
 * the camera. It exercises the same `Engine`/`Scene`/`View`/`Camera`/
 * `Renderable` path the world view uses, and a solid triangle that does not
 * appear means the problem is the swap chain, the camera or the driver — not
 * the Second Life data.
 */
class FilamentProbeActivity : BaseActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityFilamentProbeBinding
    private lateinit var surfaceView: SurfaceView

    private val diagnostics = RenderDiagnostics()

    @Volatile
    private var thread: ProbeThread? = null

    private val surfaceLock = Object()
    private var surfaceRequest = 0
    private var surfaceAcknowledged = 0
    private var requestedWidth = 0
    private var requestedHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilamentProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // The SurfaceView is created in code, like the world view's, so the same
        // handshake is exercised (SurfaceHolder callbacks, valid-surface check,
        // swap chain only after the surface exists).
        surfaceView = SurfaceView(this)
        binding.probeViewportCtn.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        surfaceView.holder.addCallback(this)

        binding.saveBtn.setOnClickListener {
            val file = diagnostics.writeTo(java.io.File(filesDir, REPORT_FILE))
            android.widget.Toast.makeText(
                this,
                if (file == null) "No se pudo escribir el informe" else "Informe: " + file.absolutePath,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        binding.restartBtn.setOnClickListener { restart() }
        binding.reportBtn.setOnClickListener {
            val showing = binding.probeReportScroll.visibility == View.VISIBLE
            binding.probeReportScroll.visibility = if (showing) View.GONE else View.VISIBLE
            binding.reportBtn.text = getString(
                if (showing) R.string.probe_report_show else R.string.probe_report_hide
            )
        }

        start()
    }

    override fun onResume() {
        super.onResume()
        thread?.active = true
    }

    override fun onPause() {
        thread?.active = false
        super.onPause()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    // ------------------------------------------------------------ surface glue

    override fun surfaceCreated(holder: SurfaceHolder) {
        diagnostics.surfaceCreatedCalls += 1
        diagnostics.surfaceCallbackThread = Thread.currentThread().name
        val surface = holder.surface
        diagnostics.surfaceEvent(
            "CREATED  valida=" + (surface != null && surface.isValid) + "  vista=" + surfaceView.width + "x" + surfaceView.height
        )
        start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        diagnostics.surfaceChangedCalls += 1
        diagnostics.surfaceLastFormat = format
        diagnostics.surfaceCallbackThread = Thread.currentThread().name
        if (width > 0 && height > 0) {
            diagnostics.surfaceCreatedWidth = width
            diagnostics.surfaceCreatedHeight = height
        }
        diagnostics.surfaceEvent("CHANGED  " + width + "x" + height + "  formato=" + format)
        synchronized(surfaceLock) {
            requestedWidth = width
            requestedHeight = height
            surfaceRequest += 1
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        diagnostics.surfaceDestroyedCalls += 1
        diagnostics.surfaceValid = false
        diagnostics.surfaceEvent("DESTROYED")
        // Unblock the render thread if it is waiting on this handshake, but do
        // not sit here: the surface is already gone.
        synchronized(surfaceLock) {
            surfaceRequest += 1
            surfaceLock.notifyAll()
        }
    }

    // -------------------------------------------------------------- the thread

    private fun start() {
        if (thread == null) {
            val created = ProbeThread()
            thread = created
            created.start()
        } else {
            synchronized(surfaceLock) { surfaceRequest += 1 }
        }
    }

    private fun stop() {
        val current = thread ?: return
        thread = null
        current.running = false
        current.interrupt()
        try {
            current.join(4000)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun restart() {
        stop()
        diagnostics.clearError()
        start()
    }

    /**
     * The whole probe, in one thread: build Filament, upload one triangle, draw
     * it until the activity goes away. Every step is reported.
     */
    private inner class ProbeThread : Thread("FilamentProbe") {

        @Volatile
        var running = true

        @Volatile
        var active = true

        private var engine: Engine? = null
        private var renderer: Renderer? = null
        private var scene: Scene? = null
        private var view: FilamentView? = null
        private var camera: Camera? = null
        private var swapChain: SwapChain? = null
        private var entityManager: EntityManager? = null
        private var material: Material? = null
        private var instance: MaterialInstance? = null
        private var vertexBuffer: VertexBuffer? = null
        private var indexBuffer: IndexBuffer? = null
        private var triangle = 0
        private var appliedRequest = -1
        private var surfaceState = ""
        private var reportWritten = false
        private var frames = 0L
        private var lastUiMillis = 0L
        private var lastFrameNanos = 0L
        private var fps = 0.0

        /** START / SUCCESS / FAIL for one step, with the whole throwable on failure. */
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

        override fun run() {
            try {
                build()
                loop()
            } catch (error: Throwable) {
                // The step that failed has already recorded the full throwable;
                // this keeps the chain complete if something throws outside one.
                diagnostics.failDetailed("probeThread", error)
                publish()
            } finally {
                release()
                publish()
            }
        }

        private fun build() {
            // 1. Native libraries, and what the device actually has.
            FilamentBootstrap.ensureLoaded(diagnostics)
            diagnostics.environment(FilamentBootstrap.preflight(this@FilamentProbeActivity, diagnostics))

            // 2. Engine.
            val createdEngine = step("ENGINE_CREATE") {
                Engine.Builder().backend(Engine.Backend.OPENGL).build()
            }
            engine = createdEngine
            diagnostics.engineValid = createdEngine.isValid()
            diagnostics.step("ENGINE_VALID", if (diagnostics.engineValid) "SUCCESS" else "FAIL (isValid false)")

            // 3. Renderer, scene, view, camera.
            val createdRenderer = step("RENDERER_CREATE") { createdEngine.createRenderer() }
            renderer = createdRenderer
            diagnostics.frameRendererValid = createdEngine.isValidRenderer(createdRenderer)
            diagnostics.backendName = "Filament/OpenGL"
            diagnostics.step("RENDERER_VALID", if (diagnostics.frameRendererValid) "SUCCESS" else "FAIL")

            val createdScene = step("SCENE_CREATE") { createdEngine.createScene() }
            scene = createdScene
            val createdView = step("VIEW_CREATE") { createdEngine.createView() }
            view = createdView
            diagnostics.sceneValid = createdEngine.isValidScene(createdScene)
            diagnostics.viewValid = createdEngine.isValidView(createdView)
            diagnostics.step("SCENE_VALID", if (diagnostics.sceneValid) "SUCCESS" else "FAIL")
            diagnostics.step("VIEW_VALID", if (diagnostics.viewValid) "SUCCESS" else "FAIL")

            val manager = step("ENTITY_MANAGER") { EntityManager.get() }
            entityManager = manager
            val cameraEntity = manager.create()
            val createdCamera = step("CAMERA_CREATE") { createdEngine.createCamera(cameraEntity) }
            camera = createdCamera
            // Filament exposes no `isValidCamera` (unlike renderer/scene/view), so
            // the camera is proven by using it: lookAt + setProjection in their own
            // step. A camera that cannot be configured reports there.
            step("CAMERA_CONFIGURE") {
                createdCamera.lookAt(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0)
                createdCamera.setProjection(45.0, 1.0, 0.1, 100.0, Camera.Fov.VERTICAL)
            }
            diagnostics.cameraValid = true
            diagnostics.step("CAMERA_VALID", "SUCCESS (creada y configurada)")

            step("VIEW_CONFIGURE") {
                createdView.setScene(createdScene)
                createdView.setCamera(createdCamera)
                createdView.setViewport(Viewport(0, 0, 1, 1))
                createdView.setFrustumCullingEnabled(false)
                createdView.setShadowingEnabled(false)
                createdScene.addEntity(cameraEntity)
                createdRenderer.setClearOptions(clearOptions())
            }

            // 4. Geometry: one triangle, POSITION only, 12 bytes per vertex.
            val vertices = floatArrayOf(
                -0.6f, -0.5f, -2f,
                0.6f, -0.5f, -2f,
                0f, 0.7f, -2f
            )
            val vbo = step("VERTEX_BUFFER") {
                VertexBuffer.Builder()
                    .vertexCount(3)
                    .bufferCount(1)
                    .attribute(
                        VertexBuffer.VertexAttribute.POSITION,
                        0,
                        VertexBuffer.AttributeType.FLOAT3,
                        0,
                        12
                    )
                    .build(createdEngine)
            }
            step("VERTEX_BUFFER_UPLOAD") {
                vbo.setBufferAt(createdEngine, 0, floatBufferOf(vertices))
            }
            vertexBuffer = vbo

            val indices = shortArrayOf(0, 1, 2)
            val ibo = step("INDEX_BUFFER") {
                IndexBuffer.Builder()
                    .indexCount(3)
                    .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                    .build(createdEngine)
            }
            step("INDEX_BUFFER_UPLOAD") {
                ibo.setBuffer(createdEngine, shortBufferOf(indices))
            }
            indexBuffer = ibo

            // 5. The material, compiled on the device by filamat (a *separate*
            //    native library from filament-jni, so it is its own step).
            val compiled = step("MATERIAL_COMPILE") { compileMaterial() }
            material = compiled
            val createdInstance = step("MATERIAL_INSTANCE") { compiled.createInstance() }
            instance = createdInstance
            step("MATERIAL_PARAMETERS") {
                createdInstance.setParameter("baseColor", 0.95f, 0.25f, 0.15f, 1f)
            }

            // 6. Renderable + entity in the scene.
            val entity = manager.create()
            step("RENDERABLE_CREATE") {
                RenderableManager.Builder(1)
                    .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vbo, ibo, 0, 3)
                    .material(0, createdInstance)
                    .boundingBox(Box(0f, 0f, -2f, 1.5f, 1.5f, 0.5f))
                    .culling(false)
                    .castShadows(false)
                    .receiveShadows(false)
                    .build(createdEngine, entity)
            }
            triangle = entity
            step("SCENE_ADD") { createdScene.addEntity(entity) }
            diagnostics.probeEntities = 1
            diagnostics.probeVertices = 3
            diagnostics.probeTriangles = 1
            diagnostics.step("PROBE_READY", "SUCCESS (" + createdScene.getEntityCount() + " entidades en la escena)")
        }

        /** Compiles the probe's own tiny unlit material — no viewer code involved. */
        private fun compileMaterial(): Material {
            val activeEngine = engine ?: throw IllegalStateException("sin engine")
            try {
                MaterialBuilder.init()
                val builder = MaterialBuilder()
                    .name("ephora_probe_unlit")
                    .platform(MaterialBuilder.Platform.MOBILE)
                    .targetApi(MaterialBuilder.TargetApi.OPENGL)
                    .optimization(MaterialBuilder.Optimization.NONE)
                    .shading(MaterialBuilder.Shading.UNLIT)
                    .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
                    .vertexDomain(MaterialBuilder.VertexDomain.OBJECT)
                    .require(MaterialBuilder.VertexAttribute.POSITION)
                    .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "baseColor")
                    .material(
                        """
                        void material(inout MaterialInputs material) {
                            prepareMaterial(material);
                            material.baseColor = materialParams.baseColor;
                        }
                        """.trimIndent()
                    )
                val package_ = builder.build()
                if (!package_.isValid()) {
                    throw IllegalStateException("el compilador de materiales rechazo el material del probe")
                }
                val payload = package_.getBuffer()
                payload.rewind()
                val built = Material.Builder().payload(payload, payload.remaining()).build(activeEngine)
                if (!activeEngine.isValidMaterial(built)) {
                    throw IllegalStateException("el material del probe no es valido en el engine")
                }
                return built
            } finally {
                MaterialBuilder.shutdown()
            }
        }

        private fun clearOptions(): Renderer.ClearOptions {
            val options = Renderer.ClearOptions()
            options.clearColor = doubleArrayOf(0.06, 0.09, 0.16, 1.0)
            options.clear = true
            options.discard = false
            return options
        }

        private fun loop() {
            while (running) {
                if (!applySurface()) {
                    // No valid surface yet: nothing to draw into. The loop keeps
                    // publishing, so the on-screen report says why.
                    sleepQuietly(50)
                } else if (!active) {
                    sleepQuietly(60)
                } else {
                    drawFrame()
                }
                val nowMillis = System.currentTimeMillis()
                if (nowMillis - lastUiMillis >= 500L) {
                    lastUiMillis = nowMillis
                    publish()
                }
            }
        }

        private fun drawFrame() {
            val activeRenderer = renderer
            val activeView = view
            val chain = swapChain
            if (activeRenderer == null || activeView == null || chain == null) {
                sleepQuietly(200)
                return
            }
            diagnostics.framesAttempted += 1
            val now = System.nanoTime()
            try {
                if (!activeRenderer.beginFrame(chain, now)) {
                    // Normal once in a while: no buffer available yet, or the
                    // surface is being recreated.
                    diagnostics.beginFrameFail += 1
                    sleepQuietly(16)
                    return
                }
                diagnostics.beginFrameOk += 1
                activeRenderer.render(activeView)
                diagnostics.renderCalls += 1
                diagnostics.drawnRenderables = activeView.getVisibleRenderableCount()
                diagnostics.entitiesInScene = scene?.getEntityCount() ?: 0
                activeRenderer.endFrame()
                diagnostics.endFrameCalls += 1
                frames += 1
                if (lastFrameNanos != 0L) {
                    val delta = (now - lastFrameNanos) / 1_000_000.0
                    if (delta > 0.0) {
                        // Smoothed, so the number on screen is readable.
                        fps = if (fps == 0.0) 1000.0 / delta else fps * 0.9 + (1000.0 / delta) * 0.1
                    }
                }
                lastFrameNanos = now
                diagnostics.fps = fps
                diagnostics.frameMillis = if (fps > 0.0) 1000.0 / fps else 0.0
            } catch (error: Throwable) {
                diagnostics.failDetailed("FRAME", error)
                sleepQuietly(500)
            }
        }

        /**
         * Binds the swap chain, and only once the surface is valid and has a
         * size — the rule that keeps a 0x0 surface from being reported as a
         * rendering bug.
         */
        private fun applySurface(): Boolean {
            val request: Int
            val width: Int
            val height: Int
            synchronized(surfaceLock) {
                request = surfaceRequest
                width = requestedWidth
                height = requestedHeight
            }
            if (request == appliedRequest && swapChain != null) {
                return true
            }
            val surface: Surface? = surfaceView.holder.surface
            val valid = surface != null && surface.isValid
            diagnostics.surfaceValid = valid
            if (valid && width > 0 && height > 0) {
                diagnostics.surfaceWidth = width
                diagnostics.surfaceHeight = height
                diagnostics.viewportWidth = width
                diagnostics.viewportHeight = height
            }
            // The spec's section 4 wording, written once per state change so it
            // is readable instead of being overwritten 20 times a second.
            val state = if (valid && width > 0 && height > 0) {
                "SURFACE CREATED  width = " + width + "  height = " + height
            } else {
                "SURFACE NOT CREATED  valida=" + valid + "  " + width + "x" + height
            }
            if (state != surfaceState) {
                surfaceState = state
                diagnostics.surfaceEvent(state)
            }
            if (!valid || width <= 0 || height <= 0) {
                // The surface is gone: the swap chain that pointed at it must go
                // too, before it is reported as anything else.
                releaseSwapChain()
                diagnostics.step(
                    "SURFACE_BIND",
                    "NO (valida=" + valid + ", " + width + "x" + height + ")"
                )
                appliedRequest = request
                acknowledge(request)
                return false
            }
            val activeEngine = engine ?: return false
            return try {
                // Recreate the swap chain when the surface changed.
                releaseSwapChain()
                val created = step("SWAPCHAIN_CREATE") { activeEngine.createSwapChain(surface) }
                swapChain = created
                diagnostics.swapChainValid = created != null
                diagnostics.step("SWAPCHAIN_VALID", if (activeEngine.isValidSwapChain(created)) "SUCCESS" else "FAIL")
                val activeView = view
                if (activeView != null) {
                    activeView.setViewport(Viewport(0, 0, width, height))
                    camera?.setProjection(
                        45.0,
                        width.toDouble() / max(1, height).toDouble(),
                        0.1,
                        100.0,
                        Camera.Fov.VERTICAL
                    )
                }
                appliedRequest = request
                acknowledge(request)
                true
            } catch (error: Throwable) {
                diagnostics.failDetailed("SWAPCHAIN_CREATE", error)
                appliedRequest = request
                acknowledge(request)
                false
            }
        }

        private fun acknowledge(request: Int) {
            synchronized(surfaceLock) {
                if (request > surfaceAcknowledged) {
                    surfaceAcknowledged = request
                }
                surfaceLock.notifyAll()
            }
        }

        /** Releases the swap chain if there is one, and records the failure if not. */
        private fun releaseSwapChain() {
            val activeEngine = engine ?: return
            val chain = swapChain ?: return
            swapChain = null
            diagnostics.swapChainValid = false
            try {
                if (activeEngine.isValidSwapChain(chain)) {
                    activeEngine.destroySwapChain(chain)
                }
                diagnostics.step("SWAPCHAIN_RELEASE", "SUCCESS")
            } catch (error: Throwable) {
                diagnostics.failDetailed("SWAPCHAIN_RELEASE", error)
            }
        }

        private fun release() {
            val activeEngine = engine ?: return
            try {
                releaseSwapChain()
                if (triangle != 0) {
                    scene?.removeEntity(triangle)
                    activeEngine.destroyEntity(triangle)
                    triangle = 0
                }
                instance?.let { activeEngine.destroyMaterialInstance(it) }
                instance = null
                material?.let { if (activeEngine.isValidMaterial(it)) activeEngine.destroyMaterial(it) }
                material = null
                vertexBuffer?.let { activeEngine.destroyVertexBuffer(it) }
                vertexBuffer = null
                indexBuffer?.let { activeEngine.destroyIndexBuffer(it) }
                indexBuffer = null
                view?.let { activeEngine.destroyView(it) }
                view = null
                scene?.let { activeEngine.destroyScene(it) }
                scene = null
                renderer?.let { activeEngine.destroyRenderer(it) }
                renderer = null
                camera = null
                diagnostics.engineValid = false
                activeEngine.destroy()
                engine = null
                diagnostics.step("RELEASED", "SUCCESS")
            } catch (error: Throwable) {
                diagnostics.failDetailed("release", error)
            }
        }

        private fun publish() {
            if (diagnostics.errorMessage.isNotEmpty() && !reportWritten) {
                // A failure writes itself to the app's files directory without
                // being asked: the log has to exist even if the user just closes
                // the screen after seeing the error.
                reportWritten = true
                val file = diagnostics.writeTo(java.io.File(filesDir, REPORT_FILE))
                if (file != null) {
                    diagnostics.step("INFORME", "escrito en " + file.absolutePath)
                }
            }
            val status = buildString {
                append("FPS ").append(String.format(java.util.Locale.US, "%.1f", fps))
                append("  ·  frames ").append(frames)
                append("  ·  viewport ").append(diagnostics.viewportWidth).append('x').append(diagnostics.viewportHeight)
                append('\n')
                append("Engine ").append(ok(diagnostics.engineValid))
                append("  Renderer ").append(ok(diagnostics.frameRendererValid))
                append("  Scene ").append(ok(diagnostics.sceneValid))
                append("  View ").append(ok(diagnostics.viewValid))
                append("  Camera ").append(ok(diagnostics.cameraValid))
                append("  SwapChain ").append(ok(diagnostics.swapChainValid))
                append('\n')
                append("beginFrame OK ").append(diagnostics.beginFrameOk)
                append(" / fallo ").append(diagnostics.beginFrameFail)
                append("  ·  render ").append(diagnostics.renderCalls)
                append("  ·  endFrame ").append(diagnostics.endFrameCalls)
                append("  ·  dibujados ").append(diagnostics.drawnRenderables)
            }
            val text = buildString {
                append("PROBE FILAMENT PURO (sin Second Life)\n")
                append(status).append("\n\n")
                for (line in diagnostics.startUpReport()) {
                    append(line).append('\n')
                }
            }
            runOnUiThread {
                binding.probeStatusEl.text = status
                binding.probeInfoEl.text = text
            }
        }

        private fun ok(value: Boolean) = if (value) "OK" else "NO"

        private fun sleepQuietly(millis: Long) {
            try {
                sleep(millis)
            } catch (interrupted: InterruptedException) {
                currentThread().interrupt()
            }
        }
    }

    companion object {
        private const val REPORT_FILE = "ephora-probe.log"

        /** A direct FloatBuffer view of [values] — no viewer code involved. */
        private fun floatBufferOf(values: FloatArray): FloatBuffer {
            val buffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            val view = buffer.asFloatBuffer()
            view.put(values)
            view.rewind()
            return view
        }

        private fun shortBufferOf(values: ShortArray): ShortBuffer {
            val buffer = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder())
            val view = buffer.asShortBuffer()
            view.put(values)
            view.rewind()
            return view
        }
    }
}
