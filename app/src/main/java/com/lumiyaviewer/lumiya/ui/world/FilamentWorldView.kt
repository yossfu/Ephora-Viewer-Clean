package com.lumiyaviewer.lumiya.ui.world

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.lumiyaviewer.lumiya.BuildConfig
import com.lumiyaviewer.lumiya.renderer.CameraDesc
import com.lumiyaviewer.lumiya.renderer.EntityHandle
import com.lumiyaviewer.lumiya.renderer.RenderDiagnostics
import com.lumiyaviewer.lumiya.renderer.RenderStats
import com.lumiyaviewer.lumiya.renderer.filament.FilamentBackend
import com.lumiyaviewer.lumiya.renderer.filament.FilamentBootstrap
import com.lumiyaviewer.lumiya.renderer.filament.FilamentRenderer
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.asset.OpenJpegTextureDecoder
import com.lumiyaviewer.lumiya.slproto.asset.SyntheticTextureDecoder
import com.lumiyaviewer.lumiya.slproto.asset.TexturePipeline
import com.lumiyaviewer.lumiya.slproto.asset.UnavailableTextureDecoder
import com.lumiyaviewer.lumiya.slproto.movement.MovementAudit
import com.lumiyaviewer.lumiya.slproto.world.ObjectUpdateDiagnostics
import com.lumiyaviewer.lumiya.slscene.CameraFrustum
import com.lumiyaviewer.lumiya.slscene.SLCamera
import com.lumiyaviewer.lumiya.slscene.SLCameraProbe
import com.lumiyaviewer.lumiya.slscene.SLDiagnosticProbe
import com.lumiyaviewer.lumiya.slscene.SLScene
import com.lumiyaviewer.lumiya.slscene.SLTestScene
import com.lumiyaviewer.lumiya.slworld.SLObject
import com.lumiyaviewer.lumiya.slworld.SLRegion
import com.lumiyaviewer.lumiya.slworld.SLWorld
import com.lumiyaviewer.lumiya.slworld.SLWorldDelta
import com.lumiyaviewer.lumiya.slworld.MeshPipeline
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What the view draws.
 *
 * The first three are the acceptance tests of the rendering phase; each one
 * isolates a different layer, so a failure names itself:
 *
 * * [PROBE] — geometry built directly by the renderer, no Second Life data at
 *   all (PRUEBA A). If this is black, nothing about the protocol can be blamed.
 * * [PRIM_ROW] — the fixed row of prim shapes, which exercises the SL parameter
 *   → `slcore` → mesh path without needing a session (a login-free probe).
 * * [TERRAIN] — the region's real ground, alone: no prims at all (PRUEBA C).
 * * [REGION] — everything the simulator sent: ground and prims (PRUEBA B).
 */
enum class RenderContent {
    REGION,
    TERRAIN,
    PRIM_ROW,
    PROBE;

    fun next(): RenderContent = entries[(ordinal + 1) % entries.size]

    val label: String
        get() = when (this) {
            REGION -> "region"
            TERRAIN -> "terreno"
            PRIM_ROW -> "prims"
            PROBE -> "probe"
        }

    val description: String
        get() = when (this) {
            REGION -> "Region real (terreno + prims)"
            TERRAIN -> "Solo terreno de la region"
            PRIM_ROW -> "Fila de prims de prueba (sin login)"
            PROBE -> "Probe Filament puro (sin datos SL)"
        }
}

/** Where the camera gets its position from. */
enum class CameraFraming {
    AGENT,
    WORLD;

    fun next(): CameraFraming = if (this == AGENT) WORLD else AGENT

    val label: String
        get() = if (this == AGENT) "agente" else "mundo"
}

/**
 * The 3D viewport: a `SurfaceView` with its own render thread driving Filament.
 *
 * There is no `WebView` anywhere in the rendering path. The thread creates a
 * Filament engine, owns the swap chain for the surface, and each frame pulls
 * whatever changed out of the world model, hands it to [SLScene], and asks the
 * [com.lumiyaviewer.lumiya.renderer.Renderer] to draw. Everything the thread
 * touches (engine, scene, GPU resources) stays on that thread; the only things
 * crossing threads are the handful of `@Volatile` counters the HUD reads.
 *
 * The engine outlives the surface. Only the swap chain is tied to it, because
 * the swap chain *must* be gone before Android tears the surface down — the
 * classic crash of native viewers when the app is backgrounded. [surfaceDestroyed]
 * therefore asks the render thread to detach and waits for it to acknowledge,
 * while the engine and every uploaded mesh and texture stay alive.
 *
 * ## Instrumentation
 *
 * Every stage of the path writes into [diagnostics], and the same numbers are
 * logged to logcat under the tag `EphoraDiag` (one line every few seconds, plus
 * a full report on the first frame and whenever something fails). A black screen
 * therefore leaves a trace: which mode was active, how many objects arrived, how
 * many entities were built, whether `beginFrame` succeeded, and what the driver
 * said when it did not.
 */
class FilamentWorldView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    val camera = SLCamera()

    /** Everything the debug HUD shows; owned here, filled in on the render thread. */
    val diagnostics = RenderDiagnostics()

    @Volatile
    var content: RenderContent = RenderContent.REGION

    @Volatile
    var framing: CameraFraming = CameraFraming.AGENT

    /** Debug hook (spec §5): keep one real object 6 m in front of the camera. */
    @Volatile
    var forceObject: Boolean = false
        private set

    /** Supplies the test geometry when [content] is [RenderContent.PRIM_ROW]. */
    var testPattern: (() -> SLWorldDelta)? = null

    @Volatile
    var stats: RenderStats = RenderStats()
        private set

    @Volatile
    var sceneText: String = ""

    @Volatile
    var statusText: String = ""

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var drawnObjects: Int = 0
        private set

    @Volatile
    var drawnTriangles: Int = 0
        private set

    @Volatile
    var backendName: String = ""
        private set

    private var renderThread: RenderThread? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastSpan = 0f

    /**
     * Camera input is delivered on the UI thread while Filament renders on a
     * dedicated thread. World-object creation can be expensive on that render
     * thread, so keep a hard priority signal here: while the user is dragging
     * or pinching, do not start more expensive object insertions.
     */
    @Volatile
    private var cameraTouchActive = false

    @Volatile
    private var lastCameraTouchNanos = 0L

    /** Guards the surface handshake between the UI thread and the render thread. */
    private val surfaceLock = Object()
    private var surfaceRequest = 0
    private var surfaceAcknowledged = 0
    private var requestedWidth = 0
    private var requestedHeight = 0

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        // The thread the view is built on is the one Android will deliver input
        // and UI work on. Recorded here so the report can compare it against the
        // render thread instead of assuming they differ.
        diagnostics.uiThreadName = Thread.currentThread().name
    }

    // ------------------------------------------------------------- user actions

    /** Cycles through the diagnostic contents; see [RenderContent]. */
    fun cycleContent(): RenderContent {
        content = content.next()
        return content
    }

    /**
     * Writes the whole report (HUD + start-up steps + every failure with its
     * stack trace + the movement audit) to `filesDir`, so the evidence can be
     * copied off the device without a logcat session. Returns the file, or null
     * when writing failed.
     */
    fun writeReport(): java.io.File? = try {
        val file = java.io.File(context.filesDir, REPORT_FILE)
        file.writeText(fullReportText())
        file
    } catch (error: Throwable) {
        diagnostics.fail("writeReport", diagnostics.describe(error))
        null
    }

    fun cycleFraming(): CameraFraming {
        framing = framing.next()
        return framing
    }

    /**
     * Turns the "one real object, right in front of the camera" hook on or off.
     * Which object is chosen is decided on the render thread (the nearest one
     * that is actually drawn), because only it can see the scene.
     */
    fun toggleForcedObject(): Boolean {
        forceObject = !forceObject
        return forceObject
    }

    /**
     * The phase 2.9 camera probe: a bright cube built by this viewer, glued a few
     * metres in front of the camera, with no Second Life data in it at all.
     *
     * It answers the one question the counters cannot: is anything at all being
     * drawn? If the cube shows and the region's prims do not, the render path
     * (surface, swap chain, engine, camera, projection) is fine and the fault is
     * in the SL transforms — which the audit table then names. It is off by
     * default and turning it off removes it completely.
     */
    @Volatile
    var cameraProbeEnabled: Boolean = false
        private set

    fun toggleCameraProbe(): Boolean {
        cameraProbeEnabled = !cameraProbeEnabled
        return cameraProbeEnabled
    }

    /**
     * The reversible camera lock: pin the camera to `eye = prim + offset`,
     * `target = prim` for one real prim, so a specific object is guaranteed to be
     * dead centre of the picture. The object itself is never moved, and turning
     * the switch off restores the normal framing exactly.
     */
    @Volatile
    var primLockEnabled: Boolean = false
        private set

    fun togglePrimLock(): Boolean {
        primLockEnabled = !primLockEnabled
        return primLockEnabled
    }

    /**
     * DIAG-VIS (temporal, reversible): camera-lock automatico al objeto
     * testigo (#622043907). Solo mueve la camara (lockTo); el testigo
     * conserva geometria, transform y parent reales. El bloqueo manual
     * (primLock) tiene prioridad; apagar esto restaura el encuadre normal.
     */
    @Volatile
    var witnessLockRequested: Boolean = true
        private set

    fun toggleWitnessLock(): Boolean {
        witnessLockRequested = !witnessLockRequested
        return witnessLockRequested
    }

    /**
     * DIAG-VIS (temporal, reversible): pide el fallback magenta de
     * diagnostico; apagarlo restaura el blanco normal sin tocar codigo.
     */
    @Volatile
    var diagFallbackRequested: Boolean = true
        private set

    fun toggleDiagFallback(): Boolean {
        diagFallbackRequested = !diagFallbackRequested
        return diagFallbackRequested
    }

    /**
     * DIAG-VIS benchmark (temporal, reversible): secuencia real A/B/C/D
     * sobre el testigo. Se ejecuta solo una vez por contenido (REGION);
     * [rerunBenchmark] la repite a mano.
     */
    @Volatile
    var benchmarkEnabled: Boolean = true
        private set

    /** DIAG-VIS benchmark: IDLE/A/B/C/D/DONE (consts del companion). */
    @Volatile
    var benchmarkState: Int = BENCH_IDLE
        private set

    @Volatile
    var benchmarkFrame: Int = 0
        private set

    @Volatile
    var benchmarkDoneLogged: Boolean = false
        private set

    fun rerunBenchmark(): Boolean {
        benchmarkState = BENCH_IDLE
        benchmarkFrame = 0
        benchmarkDoneLogged = false
        return true
    }

    @Volatile
    var forensicEnabled: Boolean = true

    @Volatile
    var forensicState: Int = FORENSIC_IDLE
        private set

    @Volatile
    var forensicPhaseStart: Long = 0L
        private set

    fun rerunForensic(): Boolean {
        forensicState = FORENSIC_IDLE
        forensicPhaseStart = 0L
        return true
    }

    /**
     * TEST7: camara de prueba controlada (3 m detras + 1.5 m arriba del avatar,
     * mirando al avatar). Reversible: no toca la escena ni deja ningun cambio
     * permanente en el encuadre de produccion.
     */
    @Volatile
    var avatarCamRequested: Boolean = false
        private set

    fun toggleAvatarCam(): Boolean {
        avatarCamRequested = !avatarCamRequested
        return avatarCamRequested
    }

    /**
     * TEST8.4: culling OFF individual en 10 candidatos (reversible, global ON).
     * TEST8.7: AABB ampliado en 5 SMALL (reversible). Ninguno toca transforms,
     * parents, materiales ni el culling global permanente.
     */
    @Volatile
    var test8NoCullRequested: Boolean = false
        private set

    @Volatile
    var test8WideBoxRequested: Boolean = false
        private set

    fun toggleTest8NoCull(): Boolean {
        test8NoCullRequested = !test8NoCullRequested
        return test8NoCullRequested
    }

    fun toggleTest8WideBox(): Boolean {
        test8WideBoxRequested = !test8WideBoxRequested
        return test8WideBoxRequested
    }

    /**
     * TEST9: secuencia por objeto (10 cull + 5 box) con reporte unico.
     * Restaura todo al terminar o al apagar; no toca transforms ni parents.
     */
    @Volatile
    var test9Requested: Boolean = false
        private set

    fun toggleTest9(): Boolean {
        test9Requested = !test9Requested
        return test9Requested
    }

    @Volatile
    var test10Requested: Boolean = false
        private set

    fun toggleTest10(): Boolean {
        test10Requested = !test10Requested
        return test10Requested
    }

    @Volatile
    var test11Requested: Boolean = false
        private set

    fun toggleTest11(): Boolean {
        test11Requested = !test11Requested
        return test11Requested
    }

    @Volatile
    var test12Requested: Boolean = false
        private set

    fun toggleTest12(): Boolean {
        test12Requested = !test12Requested
        return test12Requested
    }

    @Volatile
    var test13Requested: Boolean = false
        private set

    fun toggleTest13(): Boolean {
        test13Requested = !test13Requested
        return test13Requested
    }

    @Volatile
    var test14Requested: Boolean = false
        private set

    fun toggleTest14(): Boolean {
        test14Requested = !test14Requested
        return test14Requested
    }

    @Volatile
    var test16Requested: Boolean = false
        private set

    fun toggleTest16(): Boolean {
        test16Requested = !test16Requested
        return test16Requested
    }

    /**
     * Fase 2.12: the four DEBUG switches of the render scalability work.
     *
     * Each one is a request the *render thread* applies on its next frame, so the
     * UI thread never touches the renderer. All four are reversible: turning a
     * switch back restores exactly the previous state, which is what makes the
     * before/after measurement honest — the only thing that differs between the
     * two runs is the flag itself.
     */
    /**
     * DIAG-VIS (temporal, reversible): ambos culling empiezan en OFF para
     * aislar la causa en una sola iteracion. Los toggles existentes los
     * vuelven a ON sin tocar codigo; el informe siempre dice el estado real.
     */
    @Volatile
    var frustumCullingRequested: Boolean = false
        private set

    @Volatile
    var distanceCullingRequested: Boolean = false
        private set

    @Volatile
    var shadowsRequested: Boolean = SHADOWS_ENABLED
        private set

    @Volatile
    var shadowLodRequested: Boolean = false
        private set

    fun toggleFrustumCulling(): Boolean {
        frustumCullingRequested = !frustumCullingRequested
        return frustumCullingRequested
    }

    fun toggleDistanceCulling(): Boolean {
        distanceCullingRequested = !distanceCullingRequested
        return distanceCullingRequested
    }

    fun toggleShadows(): Boolean {
        shadowsRequested = !shadowsRequested
        return shadowsRequested
    }

    fun toggleShadowLod(): Boolean {
        shadowLodRequested = !shadowLodRequested
        return shadowLodRequested
    }

    /**
     * The object the user follows (fase 2.10): `0` means none.
     *
     * Following an object changes nothing about how it is drawn. It turns on two
     * recorders — the parser's per-update history for that local id, and the
     * scene's entity-event trace — and the report then prints its whole chain
     * (`SL → Transform → renderer → motor`) next to a healthy object's. The id is
     * chosen at runtime from the HUD, never hard-coded in the source.
     */
    @Volatile
    var focusPrimLocalId: Int = 0
        private set

    /** Starts (or stops, with 0) following one object. */
    fun setFocusPrim(localId: Int) {
        focusPrimLocalId = localId
    }

    /** One line naming the followed object, for the HUD and the report. */
    fun focusLabel(): String =
        if (focusPrimLocalId == 0) {
            "-"
        } else {
            "#" + focusPrimLocalId + " " + sceneFocusLabel
        }

    /** Filled in on the render thread; the HUD reads it. */
    @Volatile
    var sceneFocusLabel: String = "sin datos"
        private set

    /** Points the camera the way the avatar is facing (the "recenter" button). */
    fun recenterCamera() {
        camera.lookAlong(SLClient.connection.world.agentHeadingRadians)
        camera.framing = SLCamera.FRAMING_AGENT
        pushYawToConnection()
    }

    /** The lines the debug HUD shows, in order. */
    fun hudLines(): List<String> = diagnostics.lines() + MovementAudit.hudLine()

    /**
     * The start-up log: the native environment, every step with START/SUCCESS/
     * FAIL, and every failure with its full throwable and stack trace. This is
     * the part that says *which call* failed instead of "Filament did not start".
     */
    fun startUpLines(): List<String> = diagnostics.startUpReport()

    /** The whole report as text (HUD + start-up log + the movement audit), for the panel. */
    fun fullReportText(): String = diagnostics.fullReport() + "\n" + MovementAudit.reportText()

    /** The three acceptance tests, as evidence (not as verdicts). */
    fun verdictLines(): List<String> {
        val probeExpected = if (content == RenderContent.PROBE) 4 else 0
        val regionExpected = if (content == RenderContent.REGION || content == RenderContent.TERRAIN) {
            diagnostics.regionObjects
        } else {
            0
        }
        val terrainExpected = if (content == RenderContent.TERRAIN || content == RenderContent.REGION) {
            diagnostics.terrainPatches
        } else {
            0
        }
        return diagnostics.verdicts(probeExpected, regionExpected, terrainExpected) + MovementAudit.verdictLine()
    }

    // ------------------------------------------------------------ surface glue

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface = holder.surface
        diagnostics.surfaceCreatedCalls += 1
        diagnostics.surfaceCallbackThread = Thread.currentThread().name
        diagnostics.surfaceEvent(
            "CREATED  id=" + System.identityHashCode(holder) +
                "  valida=" + (surface != null && surface.isValid) +
                "  vista=" + width + "x" + height
        )
        startRendering()
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
        diagnostics.surfaceEvent("DESTROYED  id=" + System.identityHashCode(holder))
        // The swap chain has to be released before this returns.
        requestSurfaceUpdate(true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        diagnostics.surfaceEvent(
            "onAttachedToWindow  vista=" + width + "x" + height + "  held=" + holder.surface.isValid
        )
    }

    override fun onDetachedFromWindow() {
        diagnostics.surfaceEvent("onDetachedFromWindow")
        stopRendering()
        super.onDetachedFromWindow()
    }

    fun startRendering() {
        val existing = renderThread
        if (existing == null) {
            val thread = RenderThread()
            renderThread = thread
            thread.start()
            return
        }
        synchronized(surfaceLock) {
            surfaceRequest += 1
        }
    }

    /** Stops rendering for good and releases every GPU resource. */
    fun stopRendering() {
        val thread = renderThread ?: return
        renderThread = null
        thread.shutdown()
        try {
            thread.join(RELEASE_TIMEOUT_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        ready = false
        statusText = ""
    }

    /** Keeps the render thread from drawing while the app is in the background. */
    fun setActive(active: Boolean) {
        renderThread?.active = active
    }

    fun isRendering(): Boolean = renderThread != null && renderThread!!.isAlive

    /**
     * Waits for the render thread to have applied the latest surface request.
     * Returns false if it did not answer in time, in which case [blocking]
     * callers know the surface is no longer guaranteed to be released.
     */
    private fun requestSurfaceUpdate(blocking: Boolean): Boolean {
        val target: Int
        synchronized(surfaceLock) {
            surfaceRequest += 1
            target = surfaceRequest
            if (!blocking) {
                return true
            }
            val deadline = System.currentTimeMillis() + SURFACE_TIMEOUT_MILLIS
            while (surfaceAcknowledged < target) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    Log.w(TAG, "render thread did not release the surface in time")
                    return false
                }
                try {
                    surfaceLock.wait(remaining)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Recorded, not assumed: this is the thread Android delivers input on,
        // and the report needs it to say whether the render loop shares a thread
        // with input (fase 2.13a revision 2, point 9).
        diagnostics.inputThreadName = Thread.currentThread().name
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cameraTouchActive = true
                lastCameraTouchNanos = System.nanoTime()
                lastTouchX = event.x
                lastTouchY = event.y
                lastSpan = span(event)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cameraTouchActive = true
                lastCameraTouchNanos = System.nanoTime()
                lastSpan = span(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                cameraTouchActive = true
                lastCameraTouchNanos = System.nanoTime()
                if (event.pointerCount >= 2) {
                    val current = span(event)
                    if (lastSpan > 4f && current > 4f) {
                        camera.zoom(lastSpan / current)
                    }
                    lastSpan = current
                    return true
                }
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                camera.orbit(dx, dy)
                camera.framing = SLCamera.FRAMING_AGENT
                pushYawToConnection()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cameraTouchActive = false
                lastCameraTouchNanos = System.nanoTime()
                lastSpan = 0f
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            // A gesture can be interrupted by a task switch/overlay without an
            // ACTION_UP. Do not leave the render thread permanently in the
            // camera-priority state in that case.
            cameraTouchActive = false
        }
    }

    private fun pushYawToConnection() {
        SLClient.connection.setViewYaw(camera.yaw)
    }

    private fun span(event: MotionEvent): Float {
        if (event.pointerCount < 2) {
            return 0f
        }
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return max(1f, hypot(dx, dy))
    }

    // ----------------------------------------------------------- render thread

    /**
     * The render thread. It lives as long as the view does, so the Filament
     * engine, the meshes and the textures survive the surface being recreated
     * (screen rotation, task switch, returning from another app).
     */
    private inner class RenderThread : Thread("EphoraRender") {

        @Volatile
        var running = true

        @Volatile
        var active = true

        /** The Looper of this thread when it is paced by Choreographer; null otherwise. */
        @Volatile
        private var renderLooper: Looper? = null

        private val world = SLWorld()
        private val pendingAdded = ArrayDeque<SLObject>()
        /** TEST 1: los updates esperan en su propia cola con el mismo mecanismo que los added. */
        private val pendingUpdated = ArrayDeque<SLObject>()
        private var pendingRegion: SLRegion? = null
        /**
         * Creating a Filament entity/material is native work and, on the target
         * device, the current 2.22 report measures about 135 ms per new object.
         * Applying 60 at once therefore monopolises the render thread for many
         * seconds and starves camera presentation. One new object per rendered
         * frame is the safe pacing floor; while the camera is touched we apply
         * zero new objects and let the existing scene remain responsive.
         */
        private val applyNewPerFrame = 1
        /**
         * TEST 1: los updates usan el mismo mecanismo que los added — como
         * maximo uno por frame, y cero con la camara activa. Mismo valor que
         * [applyNewPerFrame] a proposito: aislar la hipotesis sin re-sintonizar.
         */
        private val applyUpdatedPerFrame = 1
        private val cameraInputGraceNanos = 250_000_000L
        private var renderer: FilamentRenderer? = null
        private var scene: SLScene? = null
        private var probe: SLDiagnosticProbe? = null
        private var appliedRequest = -1
        private var appliedContent: RenderContent? = null
        private var appliedFraming: CameraFraming? = null
        private var appliedForce = false
        private var forcedLocalId = 0
        private var cameraProbe: SLCameraProbe? = null
        private var appliedCameraProbe = false
        private var appliedPrimLock = false
        private var appliedCulling = false
        /** DIAG-VIS (temporal, reversible): estado aplicado del lock al testigo. */
        private var appliedWitnessLock = false
        private var witnessLockId = 0
        private var witnessLockWaiting = false
        /** DIAG-VIS: anuncio una sola vez por sesion de vista. */
        private var diagVisAnnounced = false
        /** DIAG-VIS benchmark: flags previos para restaurar al terminar. */
        private var benchSavedFrustum = false
        private var benchSavedDistance = false
        private var benchSavedDiag = false
        private var benchSavedCull: Boolean? = null
        private var forensicSavedFrustum = false
        private var forensicSavedDiag = false
        private var forensicSavedCull: Boolean? = null
        private var appliedShadows = SHADOWS_ENABLED
        private var appliedShadowLod = false
        private var lockedPrimId = 0
        private var lastAuditMillis = 0L
        private var auditPublished = false
        private var testDelta: SLWorldDelta? = null
        private var testApplied = false
        private var boundsKnown = false
        private val boundsMin = floatArrayOf(0f, 0f, 0f)
        private val boundsMax = floatArrayOf(0f, 0f, 0f)
        private var framesDrawn = 0L
        private var firstFrameReported = false
        private var failureReported = false
        private var lastLogMillis = 0L
        private var lastFullReportMillis = 0L
        private var worldFramed = false
        private var agentFramed = false
        private var agentAimLogged = false
        private var startNanos = 0L

        fun shutdown() {
            running = false
            interrupt()
            // Paced by Choreographer the thread is kept alive by its Looper, not
            // by a while loop: quitting it is what makes run() return and the
            // finally block release the engine, the meshes and the swap chain.
            renderLooper?.quitSafely()
        }

        override fun run() {
            startNanos = System.nanoTime()
            diagnostics.renderThreadName = Thread.currentThread().name
            filamentThreadRef = Thread.currentThread()
            val ft0 = Thread.currentThread()
            diagnostics.filamentThread = ft0.name + "#" + ft0.id
            diagnostics.surfacePollMillis = SURFACE_POLL_MILLIS
            try {
                if (!runPacedByVsync()) {
                    runThrottled()
                }
            } catch (error: Throwable) {
                if (running) {
                    Log.e(TAG, "render thread stopped", error)
                    statusText = "Fallo del renderizador: " + (error.message ?: error.javaClass.simpleName)
                    diagnostics.fail("renderThread", error.message ?: error.javaClass.simpleName)
                }
            } finally {
                releaseEverything()
            }
        }

        /**
         * The loop, paced by the display: **one turn per VSYNC**, delivered by a
         * Choreographer created on this thread's own Looper.
         *
         * Why this replaces `while (running)`: the loop used to call `beginFrame`
         * as fast as the CPU allowed, and `beginFrame` returns false whenever the
         * swap chain has no buffer to hand out. On the device that came out as
         * 137469 refusals in 140673 attempts (97.7%), ~1573 attempts a second and
         * 68.8% of the render thread's time spent on frames that presented
         * nothing. That is a busy-spin, not a Filament fault, and waiting for
         * VSYNC is the mechanism Android and Filament expect a renderer to use.
         * It also makes the rate self-limiting: at most one attempt per display
         * frame, so a refused swap chain can no longer be hammered.
         *
         * Returns false when a Choreographer cannot be used on this thread, in
         * which case the caller falls back to a throttled loop (never a spin).
         */
        private fun runPacedByVsync(): Boolean {
            // The set-up is guarded: a thread that cannot host a Looper (or a
            // Choreographer) must fall back to the throttled loop, not lose the
            // renderer to an exception.
            val looper: Looper
            val choreographer: Choreographer
            try {
                Looper.prepare()
                looper = Looper.myLooper() ?: return false
                choreographer = Choreographer.getInstance()
            } catch (error: Throwable) {
                Log.w(TAG, "no VSYNC pacing on this thread; using the throttled loop", error)
                return false
            }
            renderLooper = looper
            filamentThreadRef = Thread.currentThread()
            val ft = Thread.currentThread()
            diagnostics.filamentThread = ft.name + "#" + ft.id
            diagnostics.displayRefreshRateHz = refreshRateHz()
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    diagnostics.vsyncCallbacks += 1
                    if (running) {
                        safeTurn()
                    }
                    if (running) {
                        choreographer.postFrameCallback(this)
                    } else {
                        // Nothing more to draw: stop the Looper so run() returns.
                        looper.quitSafely()
                    }
                }
            }
            diagnostics.pacingMode = "Choreographer (VSYNC): un turno por frame del display"
            choreographer.postFrameCallback(callback)
            Looper.loop()
            return true
        }

        /**
         * The fallback, used only if the Looper/Choreographer path cannot be set
         * up here: the same loop, but waiting out the remainder of a display
         * frame after every turn. Even this path cannot reproduce the busy-spin.
         */
        private fun runThrottled() {
            val periodNanos = throttledFrameNanos()
            diagnostics.pacingMode = "bucle con throttle (sin Choreographer): " +
                (periodNanos / 1_000_000L) + " ms entre turnos"
            while (running) {
                val turnStart = System.nanoTime()
                safeTurn()
                val remaining = periodNanos - (System.nanoTime() - turnStart)
                if (remaining > 0) {
                    sleepQuietly(remaining / 1_000_000L)
                }
            }
        }

        /**
         * One turn of the loop, exactly as it always was: bind the surface if it
         * changed, skip while the renderer or the view is not ready, and draw one
         * frame. Only the pacing around it changed (fase 2.13a revision 2).
         */
        private fun safeTurn() {
            diagnostics.loopIterations += 1
            if (!applySurfaceRequest()) {
                // No usable surface yet (or the app is in the background): wait for
                // the next request instead of spinning.
                diagnostics.noSurfaceSkips += 1
                sleepQuietly(SURFACE_POLL_MILLIS)
                return
            }
            val activeRenderer = renderer
            val activeScene = scene
            if (activeRenderer == null || activeScene == null || !activeRenderer.isReady) {
                diagnostics.rendererNotReadySkips += 1
                sleepQuietly(20)
                return
            }
            if (!active) {
                diagnostics.inactiveSkips += 1
                sleepQuietly(60)
                return
            }
            try {
                // Timing the frame is what turns "137469 beginFrame failures"
                // into a cost: the set-up happens before beginFrame is asked for
                // a buffer, so a refusal still burns a whole frame's work.
                val frameStart = System.nanoTime()
                val presented = drawFrame(activeRenderer, activeScene)
                val elapsed = System.nanoTime() - frameStart
                diagnostics.drawNanos += elapsed
                if (!presented) {
                    diagnostics.failedFrameNanos += elapsed
                    diagnostics.lastFailedFrameNanos = elapsed
                }
            } catch (error: Throwable) {
                // A failure in the scene layer (geometry, materials, transforms)
                // must not kill the thread and leave a black surface with no
                // explanation.
                Log.e(TAG, "frame failed", error)
                diagnostics.fail("frame", error.message ?: error.javaClass.simpleName)
                statusText = "Fallo del renderizador: " + (error.message ?: error.javaClass.simpleName)
                sleepQuietly(500)
            }
        }

        /** The display's refresh rate, when it can be read; 0 when it cannot. */
        private fun refreshRateHz(): Float = try {
            val current = display
            if (current != null && current.refreshRate > 0f) current.refreshRate else 0f
        } catch (error: Throwable) {
            0f
        }

        /** One display frame, derived from the refresh rate or assumed at 60 Hz. */
        private fun throttledFrameNanos(): Long {
            val hz = if (diagnostics.displayRefreshRateHz > 0f) diagnostics.displayRefreshRateHz else 60f
            return (1_000_000_000.0 / hz).toLong()
        }

        /**
         * Reacts to a surface change. Returns true when the surface is bound and
         * a frame may be drawn into it.
         */
        private fun applySurfaceRequest(): Boolean {
            val request: Int
            val width: Int
            val height: Int
            synchronized(surfaceLock) {
                request = surfaceRequest
                width = requestedWidth
                height = requestedHeight
            }
            if (request == appliedRequest && renderer != null) {
                return true
            }

            val surface: Surface? = holder.surface
            val surfaceUsable = surface != null && surface.isValid
            diagnostics.surfaceValid = surfaceUsable
            if (surfaceUsable && width > 0 && height > 0) {
                diagnostics.surfaceWidth = width
                diagnostics.surfaceHeight = height
            }
            val usable = surfaceUsable && width > 0 && height > 0 && active
            if (!usable) {
                // Recorded here rather than only from the SurfaceHolder
                // callbacks, so "the surface never arrived" and "the surface is
                // fine but the renderer failed" cannot be confused.
                diagnostics.step(
                    "SURFACE_BIND",
                    "NO (valida=" + surfaceUsable + ", " + width + "x" + height +
                        ", activa=" + active + ", request=" + request + ")"
                )
                detachSurface()
                appliedRequest = request
                acknowledge(request)
                return false
            }

            val activeRenderer = renderer ?: createRenderer()
            if (activeRenderer == null) {
                acknowledge(request)
                // The renderer failed; do not spin on it. The full failure is
                // already in the report, and each retry would only overwrite the
                // last-error line with the consequence of the first one.
                sleepQuietly(RENDERER_RETRY_MILLIS)
                appliedRequest = request
                return false
            }
            if (renderer == null) {
                renderer = activeRenderer
            }
            diagnostics.step("SURFACE_BIND", "SUCCESS " + width + "x" + height + " -> swap chain")
            Log.i(TAG, "surface " + width + "x" + height + " -> swap chain")
            activeRenderer.attachSurface(surface, width, height)
            diagnostics.surfaceValid = activeRenderer.hasSurface()
            appliedRequest = request
            acknowledge(request)
            return true
        }

        /**
         * Builds the scene's texture pipeline (fase 2.13b).
         *
         * The provider is the session's, not the scene's: `GetTexture` lives with
         * the connection, and a scene rebuild must not throw away textures that
         * are already cached or in flight. What is per scene is the decode side —
         * its workers upload through this scene's renderer, so they must not
         * outlive it.
         *
         * The decoder is the one this build ships; see [TEXTURE_DECODER_SYNTHETIC].
         */
        private fun texturePipeline(): TexturePipeline = TexturePipeline(
            provider = SLClient.connection.textures,
            decoder = if (TEXTURE_DECODER_SYNTHETIC) {
                SyntheticTextureDecoder()
            } else {
                val openJpeg = try {
                    OpenJpegTextureDecoder()
                } catch (error: Throwable) {
                    null
                }
                if (openJpeg != null && openJpeg.isAvailable) openJpeg else UnavailableTextureDecoder()
            }
        )

        private fun createRenderer(): FilamentRenderer? {
            // The device's native side, before anything else: which ABI it is,
            // which libraries the APK carries for it, and whether each one loads.
            diagnostics.environment(FilamentBootstrap.lastPreflightLines())
            return try {
                val created = FilamentRenderer(
                    backend = BACKEND,
                    enableShadows = SHADOWS_ENABLED,
                    msaaSampleCount = MSAA_SAMPLE_COUNT,
                    diagnostics = diagnostics
                )
                renderer = created
                val createdScene = SLScene(
                    created,
                    diagnostics = diagnostics,
                    texturePipeline = if (TEXTURES_ENABLED) texturePipeline() else null,
                    meshPipeline = if (MESH_ENABLED) MeshPipeline(SLClient.connection.meshes) else null
                )
                createdScene.test16Sink = Test16FileSink()
                scene = createdScene
                world.diagnostics = diagnostics
                backendName = created.name
                statusText = ""
                Log.i(TAG, "renderizador creado: " + created.name +
                    " (MSAA " + MSAA_SAMPLE_COUNT + ", sombras " + SHADOWS_ENABLED + ")")
                created
            } catch (error: Throwable) {
                // Never reduced to "Filament could not start": the class, the
                // message, the cause chain and the stack trace all go into the
                // report and to logcat, and the failing step was already named
                // by FilamentRenderer's own instrumentation.
                Log.e(TAG, "Filament no pudo iniciarse", error)
                diagnostics.failDetailed("createRenderer", error)
                statusText = "Filament no pudo iniciarse: " + diagnostics.describe(error)
                writeReportToFile()
                null
            }
        }

        /**
         * The report on disk, so the evidence can be copied off the device
         * without a logcat session or a screenshot.
         */
        private fun writeReportToFile() {
            val file = java.io.File(context.filesDir, REPORT_FILE)
            if (diagnostics.writeTo(file) != null) {
                Log.i(TAG, "informe escrito en " + file.absolutePath)
            }
        }

        private fun detachSurface() {
            renderer?.detachSurface()
            ready = false
        }

        private fun acknowledge(request: Int) {
            synchronized(surfaceLock) {
                if (request > surfaceAcknowledged) {
                    surfaceAcknowledged = request
                }
                surfaceLock.notifyAll()
            }
        }

        private fun drawFrame(activeRenderer: FilamentRenderer, activeScene: SLScene): Boolean {
            val now = System.nanoTime()
            val nowMillis = System.currentTimeMillis()
            if (diagnostics.appVersion == "-") {
                diagnostics.appVersion =
                    BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
            }
            applyContentChange(activeRenderer, activeScene)

            when (content) {
                RenderContent.PROBE -> {
                    // Nothing to sync: the probe is already in the scene.
                }
                RenderContent.PRIM_ROW -> ensureTestPattern(activeScene, now)
                RenderContent.TERRAIN -> {
                    activeScene.primsEnabled = false
                    syncWorld(activeScene, nowMillis, allowNewObjects = cameraInsertionsAllowed())
                }
                RenderContent.REGION -> {
                    activeScene.primsEnabled = true
                    syncWorld(activeScene, nowMillis, allowNewObjects = cameraInsertionsAllowed())
                }
            }

            applyCameraLock(activeRenderer, activeScene)
            applyWitnessLock(activeRenderer, activeScene)
            if (!diagVisAnnounced) {
                diagVisAnnounced = true
                Log.i(TAG, "DIAG-VIS activo: frustum=OFF distancia=OFF fallback=MAGENTA testigo=#" + activeScene.diagWitnessId() + " (todo reversible con toggles)")
            }
            applyAvatarCam()
            applyFraming(activeScene)
            applyForcedObject(activeScene)
            applyTest11Cam(activeScene)
            applyBenchmark(activeRenderer, activeScene)
            applyScalability(activeRenderer, activeScene)

            val cameraDesc = camera.desc()
            val aspect = CameraFrustum.aspectOf(width, height)
            diagnostics.cameraFraming = camera.framing
            activeScene.cameraAspect = aspect
            // Recovery 4 (método Lumiya, responsive mode): la misma señal que
            // raciona los objetos nuevos (recovery 3) pausa también las subidas
            // y los barridos de texturas. La cámara sigue actualizándose.
            activeScene.textureStreamer?.holdLoads = !cameraInsertionsAllowed()
            activeScene.prune(cameraDesc, nowMillis)
            applyCameraProbe(activeRenderer, cameraDesc)
            activeRenderer.setCamera(cameraDesc)

            val syncDoneNanos = System.nanoTime()
            val presented = activeRenderer.render(now)
            val renderDoneNanos = System.nanoTime()
            if (presented) {
                framesDrawn += 1
                stats = activeRenderer.stats()
                drawnObjects = activeScene.visibleEntities
                drawnTriangles = stats.triangles
                sceneText = activeScene.stats()
                // TEST7: recordar el ultimo conteo visible en cada estado para
                // la comparacion ON/OFF sin parpadear la pantalla.
                if (appliedCulling) {
                    diagnostics.cullOnVisible = diagnostics.visibleRenderables
                    diagnostics.cullOnInScene = diagnostics.renderablesInScene
                } else {
                    diagnostics.cullOffVisible = diagnostics.visibleRenderables
                    diagnostics.cullOffInScene = diagnostics.renderablesInScene
                }
                ready = true
            }
            publishCameraAudit(activeRenderer, activeScene, aspect, nowMillis)
            report(nowMillis)
            val postDoneNanos = System.nanoTime()
            diagnostics.frameSyncNanos += syncDoneNanos - now
            diagnostics.frameRenderNanos += renderDoneNanos - syncDoneNanos
            diagnostics.framePostNanos += postDoneNanos - renderDoneNanos
            if (postDoneNanos - now >= 1_000_000_000L) {
                diagnostics.slowFrames += 1
                diagnostics.slowFrameMain = mainThreadSnapshot()
            }
            return presented
        }

        private fun mainThreadSnapshot(): String {
            return try {
                val main = Looper.getMainLooper()?.thread ?: return "sin-hilo-main"
                val frames = main.stackTrace.take(6).joinToString(" <- ") {
                    it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber
                }
                main.state.toString() + (if (frames.isEmpty()) "" else " " + frames)
            } catch (t: Throwable) {
                "error-captura"
            }
        }

        /**
         * Tears the previous content down and sets the new one up. Everything
         * below the scene (mesh cache, engine, swap chain) is kept, so switching
         * back to the region is cheap.
         */
        private fun applyContentChange(activeRenderer: FilamentRenderer, activeScene: SLScene) {
            val requested = content
            if (appliedContent == requested) {
                return
            }
            appliedContent = requested
            probe?.destroy()
            probe = null
            activeScene.clear()
            world.reset()
            pendingAdded.clear()
            pendingUpdated.clear()
            pendingRegion = null
            testApplied = false
            testDelta = null
            forcedLocalId = 0
            appliedForce = false
            // The visibility phase's temporary instruments are per-content too: a
            // camera lock left over from the previous mode would otherwise
            // survive into the new one and make the picture lie.
            if (camera.isLocked) {
                camera.unlock()
            }
            primLockEnabled = false
            appliedPrimLock = false
            lockedPrimId = 0
            appliedWitnessLock = false
            witnessLockId = 0
            witnessLockWaiting = false
            benchmarkState = BENCH_IDLE
            benchmarkFrame = 0
            avatarCamRequested = false
            test8NoCullRequested = false
            test8WideBoxRequested = false
            test9Requested = false
            test10Requested = false
            test11Requested = false
            test12Requested = false
            test13Requested = false
            test14Requested = false
            test16Requested = false
            diagnostics.cameraLockedPrim = "-"
            diagnostics.cameraLockReport = "-"
            diagnostics.cameraAudit = "-"
            diagnostics.mode = requested.label
            diagnostics.forcedObject = "-"
            diagnostics.forcedDistance = 0f
            // Textures are the region's: in the probe and the test pattern there
            // is nothing of Second Life to texture, and in "terrain only" the
            // prims (and with them the faces) are not drawn either. Leaving the
            // streamer on would keep downloading a region nobody is looking at.
            activeScene.textureStreamer?.enabled = requested == RenderContent.REGION ||
                requested == RenderContent.PRIM_ROW
            Log.i(TAG, "contenido: " + requested.label + " (" + requested.description + ")")
            if (requested == RenderContent.PROBE) {
                val created = SLDiagnosticProbe.build(activeRenderer)
                probe = created
                diagnostics.probeEntities = created.entityCount
                diagnostics.probeVertices = created.vertices
                diagnostics.probeTriangles = created.triangles
                // The probe is a fixed, known scene, so the camera is put
                // somewhere fixed and known too: 10 m out, looking at the
                // middle of the three shapes. Touch orbiting still works (the
                // framing is only re-applied when the content changes).
                camera.lookAt(
                    SLDiagnosticProbe.FOCUS,
                    SLDiagnosticProbe.DISTANCE,
                    SLDiagnosticProbe.YAW,
                    SLDiagnosticProbe.PITCH
                )
                camera.followAgent = false
                camera.framing = SLCamera.FRAMING_TEST
                Log.i(TAG, "probe: " + created.entityCount + " entidades, " +
                    created.triangles + " triangulos, camara en " +
                    camera.currentEye.joinToString(","))
            } else {
                diagnostics.probeEntities = 0
                diagnostics.probeVertices = 0
                diagnostics.probeTriangles = 0
            }
        }

        private fun ensureTestPattern(activeScene: SLScene, now: Long) {
            if (!testApplied) {
                testDelta = testPattern?.invoke() ?: SLTestScene.buildDelta()
                activeScene.primsEnabled = true
                activeScene.apply(testDelta!!)
                testApplied = true
            }
            // A slow sweep, so the shapes are seen from several angles and a
            // frozen frame is immediately obvious.
            val seconds = (now - startNanos) / 1_000_000_000.0
            camera.yaw = (Math.PI / 2).toFloat() + (Math.sin(seconds * 0.35) * 0.85).toFloat()
        }

        private fun cameraInsertionsAllowed(): Boolean {
            if (cameraTouchActive) {
                return false
            }
            val lastTouch = lastCameraTouchNanos
            if (lastTouch == 0L) {
                return true
            }
            return System.nanoTime() - lastTouch >= cameraInputGraceNanos
        }

        private fun syncWorld(
            activeScene: SLScene,
            nowMillis: Long,
            allowNewObjects: Boolean = true
        ) {
            val model = SLClient.connection.world
            val worldStart = System.nanoTime()
            val delta = world.sync(model)
            diagnostics.frameWorldNanos += System.nanoTime() - worldStart
            if (delta != null) {
                pendingRegion = delta.region
                if (delta.added.isNotEmpty()) {
                    pendingAdded.addAll(delta.added)
                }
                if (delta.updated.isNotEmpty()) {
                    pendingUpdated.addAll(delta.updated)
                    diagnostics.updatedReceivedTotal += delta.updated.size.toLong()
                }
            }
            // Troceado: la versión 2.22 mide ~135 ms por objeto nuevo en el
            // dispositivo. Aplicar 60 en un frame bloqueaba el render thread
            // varios segundos. Removidos, terreno y bounds aplican
            // enteros; los nuevos se racionan a uno por frame y se pausarán
            // mientras el usuario interactúa con la cámara.
            // TEST 1: los updates entran en la misma disciplina incremental que
            // los added (uno por frame, cero con la camara activa). Solo asi se
            // aisla si el apply de ~53 s viene de updates masivos sin racionar.
            var addedNow: List<SLObject> = emptyList()
            var updatedNow: List<SLObject> = emptyList()
            if (allowNewObjects && pendingAdded.isNotEmpty()) {
                val take = minOf(pendingAdded.size, applyNewPerFrame)
                val head = ArrayList<SLObject>(take)
                repeat(take) { head.add(pendingAdded.removeFirst()) }
                addedNow = head
            }
            if (allowNewObjects && pendingUpdated.isNotEmpty()) {
                val take = minOf(pendingUpdated.size, applyUpdatedPerFrame)
                val head = ArrayList<SLObject>(take)
                repeat(take) { head.add(pendingUpdated.removeFirst()) }
                updatedNow = head
            }
            diagnostics.pendingAddedCount = pendingAdded.size
            diagnostics.pendingUpdatedCount = pendingUpdated.size
            var appliedNow = 0
            var appliedUpdatedNow = 0
            val applyStart = System.nanoTime()
            if (delta != null) {
                activeScene.apply(
                    SLWorldDelta(
                        delta.region, addedNow, updatedNow, delta.removed,
                        delta.avatars, delta.regionChanged, delta.terrain,
                        delta.boundsMin, delta.boundsMax, delta.boundsKnown, delta.counts
                    ),
                    nowMillis
                )
                if (delta.boundsKnown) {
                    boundsKnown = true
                    System.arraycopy(delta.boundsMin, 0, boundsMin, 0, 3)
                    System.arraycopy(delta.boundsMax, 0, boundsMax, 0, 3)
                }
                appliedNow = addedNow.size
                appliedUpdatedNow = updatedNow.size
                diagnostics.applyCalls += 1
                diagnostics.updatedAppliedPerFrameLast = appliedUpdatedNow
                if (appliedUpdatedNow > diagnostics.updatedAppliedMaxPerFrame) {
                    diagnostics.updatedAppliedMaxPerFrame = appliedUpdatedNow
                }
            } else if (addedNow.isNotEmpty() || updatedNow.isNotEmpty()) {
                val region = pendingRegion
                if (region != null) {
                    activeScene.apply(
                        SLWorldDelta(region, addedNow, updatedNow, emptyList(), 0, false),
                        nowMillis
                    )
                    appliedNow = addedNow.size
                    appliedUpdatedNow = updatedNow.size
                    diagnostics.applyCalls += 1
                    diagnostics.updatedAppliedPerFrameLast = appliedUpdatedNow
                    if (appliedUpdatedNow > diagnostics.updatedAppliedMaxPerFrame) {
                        diagnostics.updatedAppliedMaxPerFrame = appliedUpdatedNow
                    }
                } else {
                    pendingAdded.addAll(addedNow)
                    pendingUpdated.addAll(updatedNow)
                }
            }
            diagnostics.frameApplyNanos += System.nanoTime() - applyStart
            diagnostics.appliedAdded += appliedNow.toLong()
            diagnostics.appliedUpdated += appliedUpdatedNow.toLong()
            val position = model.agentPosition
            if (model.agentPositionKnown) {
                diagnostics.agentPositionKnown = true
                if (boundsKnown) {
                    val centreX = (boundsMin[0] + boundsMax[0]) * 0.5f
                    val centreY = (boundsMin[1] + boundsMax[1]) * 0.5f
                    val centreZ = (boundsMin[2] + boundsMax[2]) * 0.5f
                    val dx = centreX - position.x
                    val dy = centreY - position.y
                    val dz = centreZ - position.z
                    diagnostics.agentDistanceToWorld = sqrt(dx * dx + dy * dy + dz * dz)
                }
            } else {
                diagnostics.agentPositionKnown = false
            }
        }

        /**
         * TEST7: aplica la camara de prueba en el hilo de render. Usa
         * lockTo/unlock y restaura FOV/near/far al apagar; el orbit rig
         * (focus/yaw/pitch/distance) no se toca. Si el bloqueo de prim tambien
         * esta activo, la camara de prueba gana mientras esta encendida (se
         * indica en el informe) y el bloqueo se repone al apagarla.
         */
        private var appliedAvatarCam = false
        private var savedFov = 60f
        private var savedNear = 0.05f
        private var savedFar = 1024f
        private var test7Eye: FloatArray? = null
        private var test7Target: FloatArray? = null
        private var test7Note: String = "OFF (produccion)"

        private fun applyAvatarCam() {
            if (!avatarCamRequested) {
                if (appliedAvatarCam) {
                    appliedAvatarCam = false
                    if (camera.isLocked) {
                        camera.unlock()
                    }
                    camera.verticalFovDegrees = savedFov
                    camera.near = savedNear
                    camera.far = savedFar
                    if (primLockEnabled) {
                        appliedPrimLock = false
                    }
                    test7Eye = null
                    test7Target = null
                    test7Note = "OFF (produccion)"
                    Log.i(TAG, "camara TEST7 desactivada: encuadre normal restaurado")
                }
                return
            }
            val model = SLClient.connection.world
            if (!model.agentPositionKnown) {
                test7Note = "solicitada, avatar aun desconocido"
                return
            }
            val p = model.agentPosition
            val h = model.agentHeadingRadians
            val fx = kotlin.math.cos(h)
            val fy = kotlin.math.sin(h)
            val eye = floatArrayOf(p.x - fx * 3f, p.y - fy * 3f, p.z + 1.5f)
            val target = floatArrayOf(p.x, p.y, p.z + AGENT_EYE_HEIGHT)
            if (!appliedAvatarCam) {
                appliedAvatarCam = true
                savedFov = camera.verticalFovDegrees
                savedNear = camera.near
                savedFar = camera.far
                camera.verticalFovDegrees = 60f
                camera.near = 0.05f
                camera.far = 300f
                Log.i(TAG, "camara TEST7 activada: 3 m detras + 1.5 m arriba del avatar")
            }
            camera.lockTo(eye, target)
            test7Eye = eye
            test7Target = target
            test7Note = "ON" + if (primLockEnabled) " (gana al bloqueo de prim)" else ""
        }

        private var appliedTest11Cam = false
        private var filamentThreadRef: Thread? = null
        private val test12RouteMarks = HashMap<String, String>()
        private val test13RouteMarks = HashMap<String, String>()
        private val test14RouteMarks = HashMap<String, String>()
        private var test16CrashChecked = false
        private var test16PrevHandler: Thread.UncaughtExceptionHandler? = null
        private var test16HandlerInstalled = false

        private fun test16Prefs(): android.content.SharedPreferences =
            context.getSharedPreferences("ephora_test16", android.content.Context.MODE_PRIVATE)

        private fun test16Append(line: String) {
            try {
                java.io.File(context.filesDir, "test16-crash.log").appendText(line + "\n")
            } catch (_: Throwable) {
            }
        }

        private fun test16Pipe(step: String, objectId: String, entity: String, instance: String, thread: String, op: String): String =
            System.currentTimeMillis().toString() + " | " + step + " | " + objectId + " | " + entity + " | " + instance + " | " + thread + " | " + op

        private fun test16InstallHandler() {
            if (test16HandlerInstalled) return
            test16PrevHandler = Thread.getDefaultUncaughtExceptionHandler()
            val prev = test16PrevHandler
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    test16Append(test16Pipe("UNCAUGHT_EXCEPTION", "-", "-", "-", t.name, e.javaClass.name + " " + (e.message ?: "-")))
                    test16Append(Log.getStackTraceString(e))
                } catch (_: Throwable) {
                }
                try {
                    prev?.uncaughtException(t, e)
                } catch (_: Throwable) {
                }
            }
            test16HandlerInstalled = true
        }

        private fun test16RestoreHandler() {
            if (!test16HandlerInstalled) return
            try {
                Thread.setDefaultUncaughtExceptionHandler(test16PrevHandler)
            } catch (_: Throwable) {
            }
            test16PrevHandler = null
            test16HandlerInstalled = false
        }

        private inner class Test16FileSink : SLScene.Test16Sink {
            override fun runStart(total: Int) {
                test16Prefs().edit()
                    .putBoolean("test16_running", true)
                    .putString("test16_step", "RUN_START")
                    .putInt("test16_step_index", 0)
                    .putInt("test16_step_total", total)
                    .putString("test16_kind", "-")
                    .putString("test16_object", "-")
                    .putString("test16_entity", "-")
                    .putString("test16_instance", "-")
                    .putString("test16_thread", Thread.currentThread().name)
                    .putString("test16_timestamp", System.currentTimeMillis().toString())
                    .putString("test16_last_op", "RUN_START")
                    .putLong("test16_frame", -1L)
                    .commit()
                test16Append("========== TEST16 RUN START total=" + total + " ==========")
                test16InstallHandler()
            }
            override fun stepBegin(index: Int, total: Int, name: String, objectId: Int, entity: Int, instance: Int, op: String, frame: Long, thread: String) {
                test16Prefs().edit()
                    .putBoolean("test16_running", true)
                    .putString("test16_step", name)
                    .putInt("test16_step_index", index)
                    .putInt("test16_step_total", total)
                    .putString("test16_kind", name)
                    .putString("test16_object", if (objectId == 0) "-" else objectId.toString())
                    .putString("test16_entity", if (entity < 0) "-" else entity.toString())
                    .putString("test16_instance", instance.toString())
                    .putString("test16_thread", thread)
                    .putString("test16_timestamp", System.currentTimeMillis().toString())
                    .putString("test16_last_op", "STEP_BEGIN " + name)
                    .putLong("test16_frame", frame)
                    .commit()
                test16Append(test16Pipe("STEP_BEGIN index=" + index + "/" + total + " name=" + name, if (objectId == 0) "-" else objectId.toString(), if (entity < 0) "-" else entity.toString(), instance.toString(), thread, op))
                Log.i(TAG, "STEP_BEGIN index=" + index + "/" + total + " name=" + name + " object=" + objectId)
            }
            override fun stepEnd(index: Int, total: Int, name: String) {
                test16Append(test16Pipe("STEP_END index=" + index + "/" + total + " name=" + name, "-", "-", "-", Thread.currentThread().name, name))
                Log.i(TAG, "STEP_END index=" + index + "/" + total + " name=" + name)
            }
            override fun nativeBegin(step: String, op: String, objectId: Int, entity: Int, instance: Int, frame: Long, thread: String) {
                test16Append(test16Pipe("NATIVE_BEGIN step=" + step, if (objectId == 0) "-" else objectId.toString(), if (entity < 0) "-" else entity.toString(), instance.toString(), thread, op))
            }
            override fun nativeEnd(step: String, op: String) {
                test16Append(test16Pipe("NATIVE_END step=" + step, "-", "-", "-", Thread.currentThread().name, op))
            }
            override fun extra(line: String) {
                test16Append(test16Pipe("EXTRA", "-", "-", "-", Thread.currentThread().name, line))
            }
            override fun runEnd(state: String) {
                test16Append("========== TEST16 RUN END state=" + state + " ==========")
                test16RestoreHandler()
            }
            override fun finishOk() {
                try {
                    test16Prefs().edit().putBoolean("test16_running", false).commit()
                } catch (_: Throwable) {
                }
                test16RestoreHandler()
            }
            override fun crashSnapshot(): SLScene.Test16Crash {
                val p = test16Prefs()
                return SLScene.Test16Crash(
                    p.getBoolean("test16_running", false),
                    p.getString("test16_step", "-") ?: "-",
                    p.getString("test16_last_op", "-") ?: "-",
                    p.getString("test16_object", "-") ?: "-",
                    p.getString("test16_entity", "-") ?: "-",
                    p.getString("test16_instance", "-") ?: "-",
                    p.getString("test16_thread", "-") ?: "-",
                    p.getInt("test16_step_index", 0),
                    p.getInt("test16_step_total", 0)
                )
            }
        }

        private fun runOnFilamentThread(label: String, action: () -> Unit) {
            val t = Thread.currentThread()
            val v = t.name + "#" + t.id + if (t === filamentThreadRef) "" else " (NO_PROPIETARIO)"
            if (label.startsWith("TEST16_")) {
                test14RouteMarks[label] = v
            } else if (label.startsWith("TEST15_")) {
                test14RouteMarks[label] = v
            } else if (label.startsWith("TEST14_")) {
                test14RouteMarks[label] = v
            } else if (label.startsWith("TEST13_")) {
                test13RouteMarks[label] = v
            } else {
                test12RouteMarks[label] = v
            }
            action()
        }
        private var saved11Fov = 60f
        private var saved11Near = 0.05f
        private var saved11Far = 1024f
        private var saved11Force = false

        private fun applyTest11Cam(activeScene: SLScene) {
            if (!activeScene.test11CamActive) {
                if (appliedTest11Cam) {
                    appliedTest11Cam = false
                    if (camera.isLocked) {
                        camera.unlock()
                    }
                    camera.verticalFovDegrees = saved11Fov
                    camera.near = saved11Near
                    camera.far = saved11Far
                    forceObject = saved11Force
                    Log.i(TAG, "camara TEST11 restaurada: encuadre original")
                }
                return
            }
            val eye = activeScene.test11CamEye ?: return
            val target = activeScene.test11CamTarget ?: return
            if (!appliedTest11Cam) {
                appliedTest11Cam = true
                saved11Fov = camera.verticalFovDegrees
                saved11Near = camera.near
                saved11Far = camera.far
                saved11Force = forceObject
                forceObject = false
                camera.verticalFovDegrees = 60f
                camera.near = 0.05f
                camera.far = 1024f
                Log.i(TAG, "camara TEST11 activada: encuadre centrado temporal")
            }
            camera.lockTo(eye, target)
        }

        private fun dist3(a: FloatArray, b: FloatArray): Float {            val dx = a[0] - b[0]
            val dy = a[1] - b[1]
            val dz = a[2] - b[2]
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }

        private fun r2(x: Float): Float = kotlin.math.round(x * 100f) / 100f

        private fun applyFraming(activeScene: SLScene) {
            if (camera.isLocked) {
                // The reversible lock of the visibility phase outranks every
                // automatic framing: that is the entire point of it. Clearing
                // the lock restores the normal behaviour with no other change.
                camera.framing = SLCamera.FRAMING_PRIM
                return
            }
            when (content) {
                RenderContent.PROBE -> {
                    camera.followAgent = false
                    camera.framing = SLCamera.FRAMING_TEST
                }
                RenderContent.PRIM_ROW -> {
                    camera.followAgent = false
                    camera.setFocus(
                        SLTestScene.CAMERA_FOCUS[0],
                        SLTestScene.CAMERA_FOCUS[1],
                        SLTestScene.CAMERA_FOCUS[2]
                    )
                    camera.distance = 24f
                    camera.pitch = 0.32f
                    camera.framing = SLCamera.FRAMING_TEST
                }
                else -> {
                    val requested = framing
                    if (appliedFraming != requested) {
                        appliedFraming = requested
                        worldFramed = false
                        agentFramed = false
                        Log.i(TAG, "encuadre de camara: " + requested.label)
                    }
                    if (requested == CameraFraming.WORLD) {
                        camera.followAgent = false
                        if (!worldFramed && boundsKnown) {
                            val aspect = if (height > 0) width.toFloat() / height.toFloat() else 1f
                            if (camera.frameBounds(boundsMin, boundsMax, aspect)) {
                                worldFramed = true
                                Log.i(
                                    TAG,
                                    "camara encuadrando el mundo: min " +
                                        boundsMin.joinToString(",") + " max " +
                                        boundsMax.joinToString(",")
                                )
                            }
                        }
                    } else {
                        camera.followAgent = true
                        camera.framing = SLCamera.FRAMING_AGENT
                        val model = SLClient.connection.world
                        if (model.agentPositionKnown) {
                            camera.setFocus(
                                model.agentPosition.x,
                                model.agentPosition.y,
                                model.agentPosition.z + AGENT_EYE_HEIGHT
                            )
                            // The audit's link 5: the camera aimed at the
                            // agent's own position, every frame. If the position
                            // moves and this does not, nothing local follows.
                            MovementAudit.noteFocus(
                                model.agentPosition.x,
                                model.agentPosition.y,
                                model.agentPosition.z + AGENT_EYE_HEIGHT
                            )
                            if (!agentFramed) {
                                // Short distance, shallow pitch: the terrain and
                                // whatever is standing on it stay inside the
                                // frustum from the first frame, instead of the
                                // camera starting far away or aimed at the middle
                                // of the region (which is often behind it).
                                camera.distance = AGENT_DISTANCE
                                camera.pitch = AGENT_PITCH
                                agentFramed = true
                                Log.i(
                                    TAG,
                                    "camara sobre el agente: posicion " +
                                        model.agentPosition.x + ", " + model.agentPosition.y + ", " +
                                        model.agentPosition.z + " a " + AGENT_DISTANCE + " m"
                                )
                            }
                        } else {
                            // The agent's own position has not arrived yet. Aim at
                            // the nearest object that actually has geometry (a
                            // prim or the terrain), so the first frame already
                            // looks at something real instead of at the region's
                            // centre.
                            val nearest = activeScene.nearestRenderable(camera.currentEye)
                            val position = activeScene.positionOf(nearest)
                            if (position != null) {
                                camera.setFocus(position[0], position[1], position[2] + AGENT_EYE_HEIGHT * 0.5f)
                                if (!agentFramed) {
                                    camera.distance = AGENT_DISTANCE
                                    camera.pitch = AGENT_PITCH
                                    agentFramed = true
                                }
                                if (!agentAimLogged) {
                                    agentAimLogged = true
                                    Log.i(
                                        TAG,
                                        "camara apuntando al primer objeto con geometria (#" + nearest +
                                            ") en " + position[0] + ", " + position[1] + ", " + position[2]
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun applyForcedObject(activeScene: SLScene) {
            if (forceObject == appliedForce) {
                return
            }
            appliedForce = forceObject
            if (forceObject) {
                forcedLocalId = activeScene.nearestRenderable(camera.currentEye)
                if (forcedLocalId == 0) {
                    Log.w(TAG, "no hay ningun objeto dibujable para forzar delante de la camara")
                    activeScene.forceObjectInFront(0, 0f)
                } else {
                    Log.i(TAG, "forzando el objeto #" + forcedLocalId + " delante de la camara")
                    activeScene.forceObjectInFront(forcedLocalId, FORCED_DISTANCE)
                }
            } else {
                Log.i(TAG, "objeto forzado desactivado")
                activeScene.forceObjectInFront(0, 0f)
            }
        }

        /**
         * DIAG-VIS benchmark (temporal, reversible): secuencia REAL A/B/C/D.
         *
         * A = frustum ON + distancia ON (config de referencia).
         * B = frustum OFF + distancia ON (aisla A: frustum).
         * C = frustum OFF + distancia OFF (aisla A+B).
         * D = C + rojo UNLIT culling-NONE double-sided sin-depth en el
         * testigo + su culling OFF (aisla B/C/D al material).
         *
         * Cada estado se aplica de verdad (View + renderables + escena +
         * testigo) y se mantiene BENCHMARK_FRAMES_PER_STATE frames antes de
         * medir. START trae la configuracion pedida y la efectiva releida;
         * END trae los contadores del motor y el bloque TESTIGO. Al terminar
         * restaura los flags previos. No toca geometria, texturas reales,
         * ejes ni parents.
         */
        private fun applyBenchmark(activeRenderer: FilamentRenderer, activeScene: SLScene) {
            if (forensicEnabled) {
                applyForensic(activeRenderer, activeScene)
                return
            }
            if (!benchmarkEnabled) return
            if (content != RenderContent.REGION) return
            val st = benchmarkState
            if (st == BENCH_DONE) return
            if (st == BENCH_IDLE) {
                if (activeScene.positionOf(activeScene.diagWitnessId()) == null) return
                benchSavedFrustum = frustumCullingRequested
                benchSavedDistance = distanceCullingRequested
                benchSavedDiag = activeScene.witnessDiagNow()
                benchSavedCull = activeScene.witnessCullingNow()
                Log.i(TAG, "DIAG-VIS BENCHMARK: testigo presente; inicia secuencia A/B/C/D (" +
                    BENCHMARK_FRAMES_PER_STATE + " frames por estado)")
                enterBenchState(activeRenderer, activeScene, BENCH_A)
                return
            }
            benchmarkFrame += 1
            if (benchmarkFrame < BENCHMARK_FRAMES_PER_STATE) return
            closeBenchState(activeScene, st)
            val next = st + 1
            if (next > BENCH_D) {
                activeRenderer.setCullingEnabled(benchSavedFrustum)
                frustumCullingRequested = benchSavedFrustum
                appliedCulling = benchSavedFrustum
                activeScene.distanceCullingEnabled = benchSavedDistance
                distanceCullingRequested = benchSavedDistance
                activeScene.setWitnessDiagMaterial(benchSavedDiag)
                val rc = benchSavedCull
                if (rc != null) activeScene.setWitnessCulling(rc)
                benchmarkState = BENCH_DONE
                Log.i(TAG, "DIAG-VIS BENCHMARK completo; estado previo restaurado " +
                    "(frustum=" + (if (benchSavedFrustum) "ON" else "OFF") +
                    " distancia=" + (if (benchSavedDistance) "ON" else "OFF") + ")")
                return
            }
            enterBenchState(activeRenderer, activeScene, next)
        }

        private fun forensicName(s: Int): String = when (s) {
            FORENSIC_F1 -> "F1"
            FORENSIC_F2 -> "F2"
            FORENSIC_F3 -> "F3"
            FORENSIC_F4 -> "F4"
            FORENSIC_F5 -> "F5"
            else -> "F6"
        }

        private fun forensicLabel(s: Int): String = when (s) {
            FORENSIC_F1 -> "testigo-orig NONE + control / frustum OFF (mira la pantalla: deberias ver ROJO y VERDE)"
            FORENSIC_F2 -> "igual que F1 pero frustum ON (mira si desaparece algo)"
            FORENSIC_F3 -> "testigo-FLIP NONE visible, orig oculto (mira si el rojo sigue)"
            FORENSIC_F4 -> "testigo-orig BACK (mira si el rojo sigue)"
            FORENSIC_F5 -> "testigo-FLIP BACK visible, orig oculto (mira si el rojo sigue)"
            else -> "depth OFF en testigo y control (mira si aparece algo nuevo)"
        }

        private fun applyForensic(activeRenderer: FilamentRenderer, activeScene: SLScene) {
            if (content != RenderContent.REGION) return
            if (forensicState == FORENSIC_DONE) return
            if (forensicState == FORENSIC_IDLE) {
                val handle = activeScene.witnessHandle() ?: return
                if (activeScene.positionOf(activeScene.diagWitnessId()) == null) return
                if (!forensicSetup(activeRenderer, activeScene, handle)) {
                    forensicState = FORENSIC_DONE
                    Log.i(TAG, "FORENSIC abortado en F0 (ver lineas de arriba)")
                    return
                }
                enterForensic(activeRenderer, activeScene, handle, FORENSIC_F1)
                return
            }
            if (System.currentTimeMillis() - forensicPhaseStart < FORENSIC_DWELL_MILLIS) return
            closeForensic(forensicState)
            val next = forensicState + 1
            val handle = activeScene.witnessHandle()
            if (next > FORENSIC_F6 || handle == null) {
                exitForensic(activeRenderer, activeScene)
                return
            }
            enterForensic(activeRenderer, activeScene, handle, next)
        }

        private fun forensicSetup(activeRenderer: FilamentRenderer, activeScene: SLScene, handle: EntityHandle): Boolean {
            forensicSavedFrustum = frustumCullingRequested
            forensicSavedDiag = activeScene.witnessDiagNow()
            forensicSavedCull = activeScene.witnessCullingNow()
            val cd = camera.desc()
            val eye = cd.eye
            val target = cd.target
            val up = cd.up
            var fx = target[0] - eye[0]
            var fy = target[1] - eye[1]
            var fz = target[2] - eye[2]
            var fl = sqrt(fx * fx + fy * fy + fz * fz)
            if (fl < 1e-6f) fl = 1f
            fx /= fl
            fy /= fl
            fz /= fl
            var rx = fy * up[2] - fz * up[1]
            var ry = fz * up[0] - fx * up[2]
            var rz = fx * up[1] - fy * up[0]
            var rl = sqrt(rx * rx + ry * ry + rz * rz)
            if (rl < 1e-6f) rl = 1f
            rx /= rl
            ry /= rl
            rz /= rl
            val right = floatArrayOf(rx, ry, rz)
            val trueUp = floatArrayOf(ry * fz - rz * fy, rz * fx - rx * fz, rx * fy - ry * fx)
            Log.i(TAG, "=== FORENSIC F0 EVIDENCIA (una sola vez) ===")
            Log.i(TAG, "camara eye=" + eye.joinToString(",") + " target=" + target.joinToString(","))
            Log.i(TAG, "camara forward=" + fx + "," + fy + "," + fz + " up=" + up.joinToString(",") + " right=" + right.joinToString(","))
            Log.i(TAG, "camara lock=" + diagnostics.cameraLockedPrim)
            for (line in activeRenderer.forensicWindingReport(handle, eye, 10).split('\n')) {
                Log.i(TAG, line)
            }
            val matTags = arrayOf("rojo-NONE-normal", "rojo-BACK-normal", "rojo-NONE-sindepth", "verde-NONE-normal", "verde-NONE-sindepth")
            val matR = floatArrayOf(1f, 1f, 1f, 0f, 0f)
            val matG = floatArrayOf(0f, 0f, 0f, 1f, 1f)
            val matBack = booleanArrayOf(false, true, false, false, false)
            val matNoDepth = booleanArrayOf(false, false, true, false, true)
            for (i in matTags.indices) {
                val inst = activeRenderer.forensicMaterial(matR[i], matG[i], 0f, matBack[i], matNoDepth[i])
                Log.i(TAG, "--- material " + matTags[i] + " compilado=" + (inst != null) + " ---")
                for (line in activeRenderer.forensicMaterialLine(matR[i], matG[i], 0f, matBack[i], matNoDepth[i]).split('\n')) {
                    Log.i(TAG, matTags[i] + " " + line)
                }
            }
            Log.i(TAG, activeRenderer.forensicBuildFlippedVariant(handle))
            val center = floatArrayOf(eye[0] + fx * 7f, eye[1] + fy * 7f, eye[2] + fz * 7f)
            val green = activeRenderer.forensicMaterial(0f, 1f, 0f, false, false)
            val greenNoDepth = activeRenderer.forensicMaterial(0f, 1f, 0f, false, true)
            Log.i(TAG, activeRenderer.forensicCreateControl(center, right, trueUp, 1.5f, green, "normal"))
            Log.i(TAG, activeRenderer.forensicCreateControl(center, right, trueUp, 1.5f, greenNoDepth, "sindepth"))
            Log.i(TAG, activeRenderer.forensicControlVisible(1, false))
            Log.i(TAG, "control posicion centro=" + center.joinToString(","))
            return true
        }

        private fun enterForensic(activeRenderer: FilamentRenderer, activeScene: SLScene, handle: EntityHandle, s: Int) {
            val frustum = s == FORENSIC_F2
            val witBack = s == FORENSIC_F4 || s == FORENSIC_F5
            val witDepthOff = s == FORENSIC_F6
            val variantOn = s == FORENSIC_F3 || s == FORENSIC_F5
            val witLayer = if (variantOn) 0x0 else 0x1
            val viewLine = activeRenderer.forensicSetViewCulling(frustum)
            frustumCullingRequested = frustum
            appliedCulling = frustum
            val witCullLine = activeScene.setWitnessCulling(false)
            val witMat = activeRenderer.forensicMaterial(1f, 0f, 0f, witBack, witDepthOff)
            val witMatLine = activeRenderer.forensicApplyMaterial(handle, witMat)
            val layerLine = activeRenderer.forensicSetLayer(handle, witLayer)
            val varMat = activeRenderer.forensicMaterial(1f, 0f, 0f, witBack, false)
            val varVisLine = activeRenderer.forensicVariantVisible(variantOn)
            val varMatLine = if (variantOn) activeRenderer.forensicVariantMaterial(varMat) else "flip: oculto en esta fase"
            val varCullLine = if (variantOn) activeRenderer.forensicVariantCulling(false) else "flip: oculto en esta fase"
            val ctrl0Line = activeRenderer.forensicControlVisible(0, s != FORENSIC_F6)
            val ctrl1Line = activeRenderer.forensicControlVisible(1, s == FORENSIC_F6)
            val viewRead = try {
                activeRenderer.viewFrustumCullingEnabled()
            } catch (e: Throwable) {
                null
            }
            val witRead = try {
                activeRenderer.entityCullingEnabled(handle)
            } catch (e: Throwable) {
                null
            }
            forensicState = s
            forensicPhaseStart = System.currentTimeMillis()
            val name = forensicName(s)
            Log.i(TAG, "=== FORENSIC " + name + " START === " + forensicLabel(s))
            Log.i(TAG, "=== TEST CULLING START ===")
            Log.i(TAG, "viewFrustumCullingEnabled=" + viewRead)
            Log.i(TAG, "renderableCullingEnabled=" + witRead)
            Log.i(TAG, "=== TEST CULLING END ===")
            Log.i(TAG, viewLine)
            Log.i(TAG, witCullLine)
            Log.i(TAG, "testigo material forense: " + witMatLine)
            for (line in activeRenderer.forensicMaterialLine(1f, 0f, 0f, witBack, witDepthOff).split('\n')) {
                Log.i(TAG, "testigo " + line)
            }
            Log.i(TAG, layerLine)
            Log.i(TAG, varVisLine)
            Log.i(TAG, varMatLine)
            Log.i(TAG, varCullLine)
            Log.i(TAG, ctrl0Line)
            Log.i(TAG, ctrl1Line)
            for (line in activeScene.witnessStateLine(name).split('\n')) {
                Log.i(TAG, line)
            }
        }

        private fun closeForensic(s: Int) {
            val name = forensicName(s)
            Log.i(TAG, "--- FORENSIC " + name + " medicion (" + (FORENSIC_DWELL_MILLIS / 1000) + "s en pantalla) ---")
            Log.i(TAG, "motor: visibles=" + diagnostics.visibleRenderables +
                " enEscena=" + diagnostics.renderablesInScene +
                " culledFrustum=" + diagnostics.culledByFrustum +
                " presentados=" + diagnostics.presentedFrames)
            Log.i(TAG, "=== FORENSIC " + name + " END ===")
        }

        private fun exitForensic(activeRenderer: FilamentRenderer, activeScene: SLScene) {
            Log.i(TAG, activeRenderer.forensicRemoveControls())
            Log.i(TAG, activeRenderer.forensicRemoveVariant())
            val handle = activeScene.witnessHandle()
            if (handle != null) {
                Log.i(TAG, activeRenderer.forensicSetLayer(handle, 0x1))
                Log.i(TAG, activeRenderer.forensicRestoreMaterial(handle))
                val rc = forensicSavedCull
                if (rc != null) activeScene.setWitnessCulling(rc)
            }
            activeScene.setWitnessDiagMaterial(forensicSavedDiag)
            activeRenderer.forensicSetViewCulling(forensicSavedFrustum)
            frustumCullingRequested = forensicSavedFrustum
            appliedCulling = forensicSavedFrustum
            forensicState = FORENSIC_DONE
            Log.i(TAG, "FORENSIC completo; estado previo restaurado (frustum=" + (if (forensicSavedFrustum) "ON" else "OFF") + ")")
        }

        private fun benchName(s: Int): String = when (s) {
            BENCH_A -> "A"
            BENCH_B -> "B"
            BENCH_C -> "C"
            else -> "D"
        }

        private fun enterBenchState(activeRenderer: FilamentRenderer, activeScene: SLScene, s: Int) {
            val f: Boolean
            val d: Boolean
            val diag: Boolean
            val cull: Boolean
            when (s) {
                BENCH_A -> { f = true; d = true; diag = false; cull = true }
                BENCH_B -> { f = false; d = true; diag = false; cull = true }
                BENCH_C -> { f = false; d = false; diag = false; cull = true }
                else -> { f = false; d = false; diag = true; cull = false }
            }
            activeRenderer.setCullingEnabled(f)
            frustumCullingRequested = f
            appliedCulling = f
            activeScene.distanceCullingEnabled = d
            distanceCullingRequested = d
            val matLine = activeScene.setWitnessDiagMaterial(diag)
            val cullLine = activeScene.setWitnessCulling(cull)
            benchmarkState = s
            benchmarkFrame = 0
            val name = benchName(s)
            Log.i(TAG, "=== BENCHMARK " + name + " START ===")
            Log.i(TAG, "config pedida: frustum=" + (if (f) "ON" else "OFF") +
                " distancia=" + (if (d) "ON" else "OFF") +
                " testigoRojo=" + (if (diag) "SI" else "NO") +
                " testigoCulling=" + (if (cull) "ON" else "OFF"))
            Log.i(TAG, "config efectiva: frustumView=" + activeRenderer.frustumCullingEnabled +
                " distanciaEscena=" + (if (activeScene.distanceCullingEnabled) "ON" else "OFF"))
            Log.i(TAG, matLine)
            Log.i(TAG, cullLine)
        }

        private fun closeBenchState(activeScene: SLScene, s: Int) {
            val name = benchName(s)
            Log.i(TAG, "--- BENCHMARK " + name + " medicion (tras " +
                BENCHMARK_FRAMES_PER_STATE + " frames) ---")
            Log.i(TAG, "motor: visibles=" + diagnostics.visibleRenderables +
                " enEscena=" + diagnostics.renderablesInScene +
                " culledFrustum=" + diagnostics.culledByFrustum +
                " presentados=" + diagnostics.presentedFrames)
            for (line in activeScene.witnessStateLine(name).split('\n')) {
                Log.i(TAG, line)
            }
            Log.i(TAG, "=== BENCHMARK " + name + " END ===")
        }

        /**
         * Fase 2.12: the A/B switches, applied on the render thread.
         *
         * * **frustum culling** — the backend's own per-view test. It never removes
         *   an entity from the scene; it only skips submitting the renderables
         *   outside the frustum, using the world transform the parent hierarchy
         *   produced, so linkset children are culled by where the hierarchy puts
         *   them (fase 2.11).
         * * **distance culling** — a scene-level flag: the same objects stay in the
         *   scene and keep their entities, meshes and materials; a far one is only
         *   taken out of the draw set.
         * * **shadows** — the whole shadow pass on or off.
         * * **shadow LOD** — far prims stop *casting* shadows. Geometry, materials
         *   and transforms are never touched, so a shape cannot change.
         */
        private fun applyScalability(activeRenderer: FilamentRenderer, activeScene: SLScene) {
            val culling = frustumCullingRequested
            if (culling != appliedCulling) {
                appliedCulling = culling
                activeRenderer.setCullingEnabled(culling)
                Log.i(TAG, "escalado: culling de frustum " + (if (culling) "ON" else "OFF"))
            }
            if (distanceCullingRequested != activeScene.distanceCullingEnabled) {                activeScene.distanceCullingEnabled = distanceCullingRequested
                Log.i(
                    TAG,
                    "escalado: culling por distancia " + (if (distanceCullingRequested) "ON" else "OFF") +
                        " (" + activeScene.maxDrawDistance.toInt() + " m)"
                )
            }
            val shadows = shadowsRequested
            if (shadows != appliedShadows) {
                appliedShadows = shadows
                activeRenderer.setShadowingEnabled(shadows)
                Log.i(TAG, "escalado: sombras " + (if (shadows) "ON" else "OFF"))
            }
            val lod = shadowLodRequested
            if (lod != appliedShadowLod) {
                appliedShadowLod = lod
                activeScene.shadowLodDistance = if (lod) SHADOW_LOD_DISTANCE else 0f
                Log.i(
                    TAG,
                    "escalado: LOD de sombras " +
                        (if (lod) SHADOW_LOD_DISTANCE.toInt().toString() + " m" else "OFF")
                )
            }
            // DIAG-VIS (temporal, reversible): sincroniza el fallback magenta.
            if (diagFallbackRequested != activeRenderer.isDiagFallbackEnabled()) {
                activeRenderer.setDiagFallbackEnabled(diagFallbackRequested)
            }
        }

        /**
         * The reversible camera lock: puts the camera at `prim + offset` looking
         * straight at one real prim within [PRIM_LOCK_MAX_METRES].
         *
         * Everything here is reversible and touches nothing but the camera: the
         * object keeps its real transform, the scene keeps its real contents, and
         * turning the switch off restores the normal framing. The report carries
         * the frustum test *before* and *after*, plus the object's SL position and
         * the position the TransformManager actually holds, which is what makes
         * "it should be on screen now" a checkable claim.
         */
        private fun applyCameraLock(activeRenderer: FilamentRenderer, activeScene: SLScene) {
            val requested = primLockEnabled
            if (requested == appliedPrimLock) {
                return
            }
            appliedPrimLock = requested
            if (!requested) {
                val was = lockedPrimId
                if (camera.isLocked) {
                    camera.unlock()
                }
                lockedPrimId = 0
                diagnostics.cameraLockedPrim = "-"
                Log.i(TAG, "camara liberada: vuelve al encuadre normal" +
                    (if (was == 0) "" else " (el prim era #" + was + ")"))
                return
            }
            val beforeCamera = camera.desc()
            val localId = activeScene.nearestPrimId(beforeCamera.eye, PRIM_LOCK_MAX_METRES)
            val position = if (localId == 0) null else activeScene.positionOf(localId)
            if (localId == 0 || position == null) {
                diagnostics.cameraLockReport =
                    "REAL_PRIM_TEST: no hay ningun prim con entidad a menos de " +
                        PRIM_LOCK_MAX_METRES.toInt() + " m de la camara"
                Log.w(TAG, diagnostics.cameraLockReport)
                primLockEnabled = false
                appliedPrimLock = false
                return
            }
            // Keep the side the camera is already on, so the prim is seen from
            // the same direction (and every material/face that was facing us
            // still faces us). Degenerate case: approach from straight ahead.
            var dx = beforeCamera.eye[0] - position[0]
            var dy = beforeCamera.eye[1] - position[1]
            var dz = beforeCamera.eye[2] - position[2]
            var length = sqrt(dx * dx + dy * dy + dz * dz)
            if (length < 0.5f) {
                dx = beforeCamera.eye[0] - beforeCamera.target[0]
                dy = beforeCamera.eye[1] - beforeCamera.target[1]
                dz = beforeCamera.eye[2] - beforeCamera.target[2]
                length = sqrt(dx * dx + dy * dy + dz * dz)
                if (length < 1e-3f) {
                    dx = 1f
                    dy = 0f
                    dz = 0f
                    length = 1f
                }
            }
            val inverse = 1f / length
            val eye = floatArrayOf(
                position[0] + dx * inverse * PRIM_LOCK_DISTANCE,
                position[1] + dy * inverse * PRIM_LOCK_DISTANCE,
                position[2] + dz * inverse * PRIM_LOCK_DISTANCE + PRIM_LOCK_HEIGHT
            )
            val target = floatArrayOf(position[0], position[1], position[2])
            camera.lockTo(eye, target)
            lockedPrimId = localId
            val afterCamera = camera.desc()
            diagnostics.cameraLockedPrim = "#" + localId
            diagnostics.cameraLockReport = activeScene.realPrimTestReport(
                localId, beforeCamera, afterCamera, activeRenderer, eye, target
            )
            Log.i(TAG, "PRIM LOCK: camara puesta a mirar el prim #" + localId +
                " en " + position.joinToString(",") + " (el objeto no se mueve)")
            for (line in diagnostics.cameraLockReport.split('\n')) {
                Log.i(TAG, line)
            }
        }

        /**
         * DIAG-VIS (temporal, reversible): lleva la camara al testigo
         * #622043907 con el mismo encuadre del bloqueo manual (6 m + 1.5 m).
         * El objeto no se mueve, no se reemparenta y no cambia de material;
         * el bloqueo manual gana si tambien esta activo. Si el testigo aun
         * no tiene entidad, espera sin tocar la camara.
         */
        private fun applyWitnessLock(activeRenderer: FilamentRenderer, activeScene: SLScene) {
            val requested = witnessLockRequested && activeScene.diagWitnessEnabled
            if (!requested) {
                if (appliedWitnessLock) {
                    appliedWitnessLock = false
                    witnessLockId = 0
                    witnessLockWaiting = false
                    if (camera.isLocked && !primLockEnabled) {
                        camera.unlock()
                    }
                    if (diagnostics.cameraLockedPrim.startsWith("#" + activeScene.diagWitnessId())) {
                        diagnostics.cameraLockedPrim = "-"
                        diagnostics.cameraLockReport = "-"
                    }
                    Log.i(TAG, "DIAG-VIS: lock al testigo desactivado, encuadre normal")
                }
                return
            }
            if (primLockEnabled) {
                return
            }
            if (appliedWitnessLock) {
                return
            }
            val localId = activeScene.diagWitnessId()
            val position = activeScene.positionOf(localId)
            if (position == null) {
                if (!witnessLockWaiting) {
                    witnessLockWaiting = true
                    Log.i(TAG, "DIAG-VIS: testigo #" + localId + " aun sin entidad, esperando sin mover la camara")
                }
                return
            }
            witnessLockWaiting = false
            val beforeCamera = camera.desc()
            // DIAG-VIS punto 4: eye = posicion ACTUAL de la camara (no se
            // cambia la distancia), target = posicion world del testigo.
            // Solo si el eye coincide con el testigo se usa un offset.
            var ex = beforeCamera.eye[0]
            var ey = beforeCamera.eye[1]
            var ez = beforeCamera.eye[2]
            var dx = ex - position[0]
            var dy = ey - position[1]
            var dz = ez - position[2]
            var length = sqrt(dx * dx + dy * dy + dz * dz)
            if (length < 0.5f) {
                ex = position[0] + PRIM_LOCK_DISTANCE
                ey = position[1]
                ez = position[2] + PRIM_LOCK_HEIGHT
                dx = ex - position[0]
                dy = ey - position[1]
                dz = ez - position[2]
                length = sqrt(dx * dx + dy * dy + dz * dz)
                if (length < 1e-3f) {
                    length = 1f
                }
            }
            val eye = floatArrayOf(ex, ey, ez)
            val target = floatArrayOf(position[0], position[1], position[2])
            camera.lockTo(eye, target)
            appliedWitnessLock = true
            witnessLockId = localId
            val afterCamera = camera.desc()
            diagnostics.cameraLockedPrim = "#" + localId + " (testigo DIAG-VIS)"
            diagnostics.cameraLockReport = activeScene.realPrimTestReport(
                localId, beforeCamera, afterCamera, activeRenderer, eye, target
            )
            Log.i(TAG, "DIAG-VIS: camara al testigo #" + localId +
                " eye=" + eye.joinToString(",") + " target=" + target.joinToString(",") +
                " (distancia original conservada; el objeto no se mueve)")
            val afx = target[0] - eye[0]
            val afy = target[1] - eye[1]
            val afz = target[2] - eye[2]
            val afl = sqrt(afx * afx + afy * afy + afz * afz)
            val ai = if (afl > 1e-6f) 1f / afl else 0f
            val anx = afx * ai
            val any = afy * ai
            val anz = afz * ai
            var arx = any * afterCamera.up[2] - anz * afterCamera.up[1]
            var ary = anz * afterCamera.up[0] - anx * afterCamera.up[2]
            var arz = anx * afterCamera.up[1] - any * afterCamera.up[0]
            val arl = sqrt(arx * arx + ary * ary + arz * arz)
            val ari = if (arl > 1e-6f) 1f / arl else 0f
            Log.i(TAG, "DIAG-VIS camara: forward=" + anx + "," + any + "," + anz +
                " up=" + afterCamera.up.joinToString(",") +
                " right=" + (arx * ari) + "," + (ary * ari) + "," + (arz * ari) +
                " near=" + afterCamera.near + " far=" + afterCamera.far +
                " fov=" + afterCamera.verticalFovDegrees)
            for (line in diagnostics.cameraLockReport.split('\n')) {
                Log.i(TAG, line)
            }
        }

        /**
         * The pure-Filament camera probe: one bright cube, rebuilt in front of
         * wherever the camera is, every frame. Cheap (one transform update), and
         * it is created and destroyed only when the switch is thrown.
         */
        private fun applyCameraProbe(activeRenderer: FilamentRenderer, cameraDesc: CameraDesc) {
            if (cameraProbeEnabled != appliedCameraProbe) {
                appliedCameraProbe = cameraProbeEnabled
                if (cameraProbeEnabled) {
                    val created = SLCameraProbe(activeRenderer)
                    cameraProbe = created
                    diagnostics.cameraProbeOn = true
                    Log.i(TAG, "PRUEBA CAMARA: probe creado (entidad valida=" + created.valid + ")")
                    if (!created.valid) {
                        diagnostics.cameraProbeReport =
                            "CAMERA_TEST: el renderer RECHAZO el cubo del probe: el fallo esta en el renderer, no en los datos SL"
                    }
                } else {
                    cameraProbe?.destroy()
                    cameraProbe = null
                    diagnostics.cameraProbeOn = false
                    diagnostics.cameraProbeReport = "-"
                    Log.i(TAG, "PRUEBA CAMARA: probe destruido")
                    return
                }
            }
            cameraProbe?.place(cameraDesc)
        }

        /**
         * The audit, at the report's own rhythm rather than the frame's: it reads
         * matrices back out of the backend and formats a page of text, which is
         * not something to do sixty times a second.
         */
        private fun publishCameraAudit(
            activeRenderer: FilamentRenderer,
            activeScene: SLScene,
            aspect: Float,
            nowMillis: Long
        ) {
            if (nowMillis - lastAuditMillis < AUDIT_INTERVAL_MILLIS) {
                return
            }
            lastAuditMillis = nowMillis
            diagnostics.auditFrameCount = framesDrawn
            if (activeScene.focusLocalId != focusPrimLocalId) {
                activeScene.focusLocalId = focusPrimLocalId
                ObjectUpdateDiagnostics.setFocus(focusPrimLocalId)
                Log.i(TAG, "FOCO: objeto #" + focusPrimLocalId +
                    " (historial del parser y de la escena activados)")
            }
            diagnostics.engineFrustumCulling = when (activeRenderer.frustumCullingEnabled) {
                null -> "desconocido"
                true -> "ON"
                false -> "OFF"
            }
            diagnostics.cameraAudit = activeScene.cameraAudit(activeRenderer, AUDIT_ROWS)
            diagnostics.sceneFrameReport = buildSceneFrameReport(activeScene, aspect)
            val agentModel = SLClient.connection.world
            val agentPos: FloatArray? = if (agentModel.agentPositionKnown) {
                val p = agentModel.agentPosition
                floatArrayOf(p.x, p.y, p.z)
            } else {
                null
            }
            val agentFocus: FloatArray? = agentPos?.let {
                floatArrayOf(it[0], it[1], it[2] + AGENT_EYE_HEIGHT)
            }
            diagnostics.test3bReport = activeScene.parentCameraTestReport(camera.desc(), aspect, agentPos, agentFocus)
            diagnostics.test7Report = buildTest7Report(activeScene, agentPos)
            val test10Hold = test10Requested || activeScene.test10Active || test11Requested || activeScene.test11Active || test12Requested || activeScene.test12Active || test13Requested || activeScene.test13Active || test14Requested || activeScene.test14Active || test16Requested || activeScene.test16Active
            val test11Hold = test11Requested || activeScene.test11Active
            val test12Hold = test12Requested || activeScene.test12Active
            val test13Hold = test13Requested || activeScene.test13Active
            val test14Hold = test14Requested || activeScene.test14Active || test16Requested || activeScene.test16Active
            if (!test9Requested && !test10Hold && test8NoCullRequested && activeScene.test8NoCullIds.isEmpty()) {
                test8DrawnBeforeNoCull = diagnostics.visibleRenderables
                val applied = activeScene.test8ApplyNoCull(camera.desc(), aspect, 10)
                Log.i(TAG, "TEST8: culling individual OFF en " + applied.size + ": " + applied.joinToString(","))
            } else if (!test8NoCullRequested && activeScene.test8NoCullIds.isNotEmpty()) {
                val n = activeScene.test8ClearNoCull()
                Log.i(TAG, "TEST8: culling individual restaurado en " + n)
            }
            if (!test9Requested && !test10Hold && test8WideBoxRequested && activeScene.test8WideBoxIds.isEmpty()) {
                test8DrawnBeforeWide = diagnostics.visibleRenderables
                val applied = activeScene.test8ApplyWideBox(5)
                Log.i(TAG, "TEST8: AABB ampliado en " + applied.size + ": " + applied.joinToString(","))
            } else if (!test8WideBoxRequested && activeScene.test8WideBoxIds.isNotEmpty()) {
                val n = activeScene.test8ClearWideBox()
                Log.i(TAG, "TEST8: AABB restaurados en " + n)
            }
            diagnostics.test8Report = buildTest8Report(activeRenderer, activeScene, agentPos, camera.desc(), aspect)
            if (!test10Hold && test9Requested && !activeScene.test9Active && activeScene.test9Note != "completo") {
                Log.i(TAG, activeScene.test9Start(camera.desc(), aspect))
            } else if (!test10Hold && test9Requested && activeScene.test9Active) {
                activeScene.test9Tick()
            } else if (!test9Requested && (activeScene.test9Active || activeScene.test9Note.startsWith("en curso"))) {
                Log.i(TAG, activeScene.test9Stop())
            }
            diagnostics.test9Report = activeScene.test9ReportBlock(agentPos, camera.desc(), aspect, diagnostics.cullOnVisible, diagnostics.cullOffVisible)
            if (activeScene.test10Active && !frustumCullingRequested) {
                frustumCullingRequested = true
                Log.i(TAG, "TEST10: culling global forzado ON (reversible)")
            }
            if (!test11Hold && !test12Hold && !test13Hold && !test14Hold && test10Requested && !activeScene.test10Active && activeScene.test10Note != "completo") {
                Log.i(TAG, activeScene.test10Start())
            } else if (!test11Hold && !test12Hold && !test13Hold && !test14Hold && test10Requested && activeScene.test10Active) {
                activeScene.test10Tick()
            } else if (!test10Requested && (activeScene.test10Active || activeScene.test10Note.startsWith("en curso"))) {
                Log.i(TAG, activeScene.test10Stop())
            }
            diagnostics.test10Report = activeScene.test10ReportBlock(agentPos, camera.desc(), aspect, diagnostics.cullOnVisible, diagnostics.cullOffVisible)
            val (t10done, t10total) = activeScene.test10Progress()
            diagnostics.test10Done = t10done
            diagnostics.test10Total = t10total
            if (activeScene.test11Active && !frustumCullingRequested) {
                frustumCullingRequested = true
                Log.i(TAG, "TEST11: culling global forzado ON (reversible)")
            }
            if (test11Requested && !activeScene.test11Active && activeScene.test11State != "EJECUTANDO" && !test12Hold && !test13Hold && !test14Hold) {
                Log.i(TAG, activeScene.test11Start())
            } else if (test11Requested && activeScene.test11Active && !test12Hold && !test13Hold && !test14Hold) {
                activeScene.test11Tick()
                if (activeScene.test11State == "TERMINADO" || activeScene.test11State == "ERROR") {
                    test11Requested = false
                    Log.i(TAG, "TEST11 fin (" + activeScene.test11State + "): boton liberado, reporte persistente")
                }
            } else if (!test11Requested && activeScene.test11Active) {
                Log.i(TAG, activeScene.test11Stop())
            }
            diagnostics.test11Report = activeScene.test11ReportBlock()
            val (t11done, t11total) = activeScene.test11Progress()
            diagnostics.test11Done = t11done
            diagnostics.test11Total = t11total
            diagnostics.test11State = activeScene.test11State
            if (activeScene.test12Active && !frustumCullingRequested) {
                frustumCullingRequested = true
                Log.i(TAG, "TEST12: culling global forzado ON (reversible)")
            }
            if (test12Requested && !activeScene.test12Active && activeScene.test12State != "EJECUTANDO" && !test13Hold && !test14Hold) {
                runOnFilamentThread("TEST12_START") { Log.i(TAG, activeScene.test12Start()) }
            } else if (test12Requested && activeScene.test12Active && !test13Hold && !test14Hold) {
                runOnFilamentThread("TEST12_TICK") { activeScene.test12Tick() }
                if (activeScene.test12State == "TERMINADO" || activeScene.test12State == "ERROR") {
                    test12Requested = false
                    Log.i(TAG, "TEST12 fin (" + activeScene.test12State + "): boton liberado, reporte persistente")
                }
            } else if (!test12Requested && activeScene.test12Active) {
                runOnFilamentThread("TEST12_STOP") { Log.i(TAG, activeScene.test12Stop()) }
            }
            diagnostics.test12Report = activeScene.test12ReportBlock()
            val (t12done, t12total) = activeScene.test12Progress()
            diagnostics.test12Done = t12done
            diagnostics.test12Total = t12total
            diagnostics.test12State = activeScene.test12State
            if (test12RouteMarks.isNotEmpty()) {
                diagnostics.test12Route = test12RouteMarks.entries.sortedBy { it.key }.joinToString("\n") { it.key + " = " + it.value }
            }
            if (activeScene.test13Active && !frustumCullingRequested) {
                frustumCullingRequested = true
                Log.i(TAG, "TEST13: culling global forzado ON (reversible)")
            }
            if (test13Requested && !activeScene.test13Active && activeScene.test13State != "EJECUTANDO" && !test14Hold) {
                runOnFilamentThread("TEST13_START") { Log.i(TAG, activeScene.test13Start()) }
            } else if (test13Requested && activeScene.test13Active && !test14Hold) {
                runOnFilamentThread("TEST13_TICK") { activeScene.test13Tick() }
                if (activeScene.test13State == "TERMINADO" || activeScene.test13State == "ERROR") {
                    test13Requested = false
                    Log.i(TAG, "TEST13 fin (" + activeScene.test13State + "): boton liberado, reporte persistente")
                }
            } else if (!test13Requested && activeScene.test13Active) {
                runOnFilamentThread("TEST13_STOP") { Log.i(TAG, activeScene.test13Stop()) }
            }
            diagnostics.test13Report = activeScene.test13ReportBlock()
            val (t13done, t13total) = activeScene.test13Progress()
            diagnostics.test13Done = t13done
            diagnostics.test13Total = t13total
            diagnostics.test13State = activeScene.test13State
            if (test13RouteMarks.isNotEmpty()) {
                diagnostics.test13Route = test13RouteMarks.entries.sortedBy { it.key }.joinToString("\n") { it.key + " = " + it.value }
            }
            if (!test16CrashChecked) {
                test16CrashChecked = true
                try {
                    activeScene.test16CheckCrash()
                } catch (_: Throwable) {
                }
            }
            if (activeScene.test16Active && !frustumCullingRequested) {
                frustumCullingRequested = true
                Log.i(TAG, "TEST16: culling global forzado ON (reversible)")
            }
            if (test16Requested && !activeScene.test16Active && activeScene.test16State != "EJECUTANDO") {
                runOnFilamentThread("TEST16_START") { Log.i(TAG, activeScene.test16Start()) }
            } else if (test16Requested && activeScene.test16Active) {
                runOnFilamentThread("TEST16_TICK") { activeScene.test16Tick() }
                if (activeScene.test16State == "TERMINADO" || activeScene.test16State == "ERROR") {
                    test16Requested = false
                    Log.i(TAG, "TEST16 fin (" + activeScene.test16State + "): boton liberado, reporte persistente")
                }
            } else if (!test16Requested && activeScene.test16Active) {
                runOnFilamentThread("TEST16_STOP") { Log.i(TAG, activeScene.test16Stop()) }
            }
            diagnostics.test14Report = activeScene.test16ReportBlock()
            val (t14done, t14total) = activeScene.test16Progress()
            diagnostics.test14Done = t14done
            diagnostics.test14Total = t14total
            diagnostics.test14State = activeScene.test16State
            if (test14RouteMarks.isNotEmpty()) {
                diagnostics.test14Route = test14RouteMarks.entries.sortedBy { it.key }.joinToString("\n") { it.key + " = " + it.value }
            }
            diagnostics.parentAudit = activeScene.parentChainReport(activeRenderer, PARENT_AUDIT_ROWS)
            diagnostics.parentLinks = activeScene.parentLinkReport(activeRenderer, PARENT_LINK_ROWS)
            if (focusPrimLocalId != 0) {
                sceneFocusLabel = activeScene.focusLabel(focusPrimLocalId)
                diagnostics.focusObject = focusLabel()
                diagnostics.focusReport = activeScene.focusReport(activeRenderer, focusPrimLocalId)
            } else {
                sceneFocusLabel = "sin datos"
                diagnostics.focusObject = "-"
                diagnostics.focusReport = "-"
            }
            val probe = cameraProbe
            if (probe != null) {
                diagnostics.cameraProbePosition =
                    floatArrayOf(probe.position[0], probe.position[1], probe.position[2])
                diagnostics.cameraProbeReport = probe.describe(camera.desc(), aspect)
            }
            if (!auditPublished) {
                auditPublished = true
                Log.i(TAG, "traza de visibilidad lista: " + activeScene.entityCount +
                    " entidades en la Scene, " + activeScene.objectsAhead + " delante de la camara")
                // The file is the copy that survives the app dying, so the first
                // audit goes straight into it.
                writeReportToFile()
            }
        }

        /**
         * TEST 2: bloque pasivo avatar/cámara/mundo + muestra del frustum. Solo
         * lectura y al ritmo de la auditoría: no cambia ningún comportamiento.
         */
        /**
         * TEST7: escena controlada — camara de prueba, culling ON/OFF,
         * poblacion, ventana cercana, pendientes y tipos de linkset. Solo
         * lectura y al ritmo de la auditoria.
         */
        private fun buildTest7Report(activeScene: SLScene, agentPos: FloatArray?): String {
            val b = StringBuilder(5600)
            b.append("--- TEST7 escena controlada (diagnostico, sin cambios permanentes) ---\n")
            val eye = test7Eye
            val tgt = test7Target
            if (appliedAvatarCam && eye != null && tgt != null && agentPos != null) {
                val center = floatArrayOf(128f, 128f, 0f)
                b.append("camara TEST7: ").append(test7Note)
                    .append(" eye=(").append(r2(eye[0])).append(", ").append(r2(eye[1])).append(", ").append(r2(eye[2])).append(")")
                    .append(" target=(").append(r2(tgt[0])).append(", ").append(r2(tgt[1])).append(", ").append(r2(tgt[2])).append(")\n")
                b.append("  distCamAvatar=").append(r2(dist3(eye, agentPos)))
                    .append(" m  distCamCentro=").append(r2(dist3(eye, center)))
                    .append(" m  fov=60 near=0.05 far=300\n")
            } else {
                val cur = camera.desc()
                b.append("camara TEST7: ").append(test7Note).append(" (produccion eye=(")
                    .append(r2(cur.eye[0])).append(", ").append(r2(cur.eye[1])).append(", ").append(r2(cur.eye[2])).append(")")
                    .append(" target=(").append(r2(cur.target[0])).append(", ").append(r2(cur.target[1])).append(", ").append(r2(cur.target[2])).append("))\n")
            }
            b.append("culling: enScene=").append(diagnostics.renderablesInScene)
                .append(" dibujadosON=").append(diagnostics.cullOnVisible)
                .append(" dibujadosOFF=").append(diagnostics.cullOffVisible)
            if (diagnostics.cullOnVisible >= 0 && diagnostics.cullOffVisible >= 0) {
                b.append(" diferencia=").append(diagnostics.cullOffVisible - diagnostics.cullOnVisible).append('\n')
            } else {
                b.append(" (falta un lado: alterna el interruptor Culling y espera a la auditoria)\n")
            }
            b.append("poblacion: region=").append(diagnostics.regionObjects)
                .append(" creadas=").append(diagnostics.entitiesCreated)
                .append(" enScene=").append(diagnostics.entitiesInScene)
                .append(" pendientesAdd=").append(diagnostics.pendingAddedCount)
                .append(" pendientesUpd=").append(diagnostics.pendingUpdatedCount)
                .append(" esperandoParent=").append(activeScene.childrenStillWithoutParent)
                .append(" parentsPend=").append(activeScene.parentsPending)
                .append(" parentsPerdidos=").append(activeScene.parentsUnresolved)
                .append(" geometria=").append(activeScene.geometryBuildSucceeded)
                .append(" renderables=").append(activeScene.primsWithRenderable).append('\n')
            if (agentPos != null) {
                b.append(activeScene.test7NearbyReport(agentPos))
            } else {
                b.append("--- TEST7.3 ventana cercana: sin avatar ---\n")
            }
            b.append(activeScene.test7PendingBreakdown())
            b.append(activeScene.test7LinksetTypes())
            return b.toString()
        }

        private var test8DrawnBeforeNoCull = -1
        private var test8DrawnBeforeWide = -1

        /**
         * TEST8: AABB real, pruebas reversibles y conclusion (una de las 4).
         * Solo lectura salvo los dos interruptores reversibles del usuario.
         */
        private fun buildTest8Report(
            activeRenderer: FilamentRenderer,
            activeScene: SLScene,
            agentPos: FloatArray?,
            desc: com.lumiyaviewer.lumiya.renderer.CameraDesc,
            aspect: Float
        ): String {
            val b = StringBuilder(9000)
            b.append("--- TEST8 AABB real vs frustum real (diagnostico) ---\n")
            b.append("API renderable: ").append(activeRenderer.renderableApiLine).append('\n')
            b.append(activeRenderer.viewLayersLine()).append('\n')
            b.append("noCull individual: ").append(activeScene.test8NoCullIds.joinToString(",").ifEmpty { "-" })
                .append(" (dibujados antes=").append(test8DrawnBeforeNoCull)
                .append(" ahora=").append(diagnostics.visibleRenderables).append(")\n")
            b.append("wideBox x5: ").append(activeScene.test8WideBoxIds.joinToString(",").ifEmpty { "-" })
                .append(" (dibujados antes=").append(test8DrawnBeforeWide)
                .append(" ahora=").append(diagnostics.visibleRenderables).append(")\n")
            b.append(activeScene.test8AabbReport(agentPos, desc, aspect))
            val small = activeScene.test8Small
            val total = activeScene.test8Total
            b.append("conclusion TEST8: muestra=").append(total).append(" small=").append(small).append('\n')
            if (total == 0) {
                b.append("  -> sin muestra: el AABB no esta disponible en esta version (ver API renderable)\n")
            } else if (small > 0) {
                b.append("  -> 1) AABB demasiado pequeno CONFIRMADO en ").append(small).append(" de ").append(total)
                    .append(" (usa wideBox x5 + noCull x10 para decidir entre AABB y otra causa)\n")
            } else {
                b.append("  -> AABB contiene la geometria en la muestra: si siguen desapareciendo con ON,")
                    .append(" ir a 2)/3) con noCull x10, o 4) revisar la medida anterior\n")
            }
            return b.toString()
        }

        private fun buildSceneFrameReport(activeScene: SLScene, aspect: Float): String {
            val builder = StringBuilder(2200)
            builder.append("--- ENCUADRE TEST2 (pasivo: avatar/camara/mundo/frustum) ---\n")
            val model = SLClient.connection.world
            if (model.agentPositionKnown) {
                val p = model.agentPosition
                builder.append("avatar propio: pos=(").append(p.x).append(", ").append(p.y).append(", ").append(p.z).append(")\n")
            } else {
                builder.append("avatar propio: posicion desconocida\n")
            }
            val desc = camera.desc()
            val dx = desc.target[0] - desc.eye[0]
            val dy = desc.target[1] - desc.eye[1]
            val dz = desc.target[2] - desc.eye[2]
            val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            val inv = if (len > 0.0001f) 1f / len else 0f
            builder.append("camara: eye=(").append(desc.eye[0]).append(", ").append(desc.eye[1]).append(", ").append(desc.eye[2]).append(")")
                .append("  dir=(").append(dx * inv).append(", ").append(dy * inv).append(", ").append(dz * inv).append(")\n")
            if (boundsKnown) {
                val cx = (boundsMin[0] + boundsMax[0]) * 0.5f
                val cy = (boundsMin[1] + boundsMax[1]) * 0.5f
                val cz = (boundsMin[2] + boundsMax[2]) * 0.5f
                val ex = boundsMax[0] - boundsMin[0]
                val ey = boundsMax[1] - boundsMin[1]
                val ez = boundsMax[2] - boundsMin[2]
                val gx = cx - desc.eye[0]
                val gy = cy - desc.eye[1]
                val gz = cz - desc.eye[2]
                val dist = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
                builder.append("mundo: centro=(").append(cx).append(", ").append(cy).append(", ").append(cz).append(")")
                    .append("  extension=(").append(ex).append(", ").append(ey).append(", ").append(ez).append(")")
                    .append("  dist camara->centro=").append(dist).append(" m\n")
            } else {
                builder.append("mundo: sin posiciones conocidas (sin centro/extensión)\n")
            }
            builder.append("escena vs motor: ").append(activeScene.entityCount).append(" entidades en Scene")
                .append("  ·  renderables en escena ").append(diagnostics.renderablesInScene)
                .append("  ·  visibles ").append(diagnostics.visibleRenderables).append('\n')
            builder.append(activeScene.sceneFrameSample(desc, aspect))
            return builder.toString()
        }

        /**
         * Logs one compact line every [LOG_INTERVAL_MILLIS] and the whole report
         * when the first frame is presented or when something failed. This is the
         * trace to pull with `adb logcat -s EphoraDiag` after a black screen.
         */
        private fun report(nowMillis: Long) {
            publishRenderRates(nowMillis)
            if (!firstFrameReported && framesDrawn > 0) {
                firstFrameReported = true
                Log.i(TAG, "primer frame presentado")
                for (line in diagnostics.lines()) {
                    Log.i(TAG, line)
                }
                for (line in diagnostics.startUpReport()) {
                    Log.i(TAG, line)
                }
                writeReportToFile()
                lastFullReportMillis = nowMillis
                lastLogMillis = nowMillis
                return
            }
            if (diagnostics.errorMessage.isNotEmpty() && !failureReported) {
                // One full dump per distinct failure, so a black screen leaves
                // the failing call and its stack trace in logcat immediately.
                failureReported = true
                Log.e(TAG, "primer fallo registrado: " + diagnostics.errorStage)
                for (line in diagnostics.startUpReport()) {
                    Log.e(TAG, line)
                }
                writeReportToFile()
            }
            if (nowMillis - lastLogMillis >= LOG_INTERVAL_MILLIS) {
                lastLogMillis = nowMillis
                Log.i(
                    TAG,
                    "fps " + diagnostics.fps +
                        " | modo " + diagnostics.mode +
                        " | region " + diagnostics.regionObjects +
                        " (" + diagnostics.prims + " prims, " + diagnostics.avatars + " avatares)" +
                        " | escena " + diagnostics.sceneEntities + " visibles " + diagnostics.sceneVisible +
                        " | terreno " + diagnostics.terrainTriangles + " tris" +
                        " | probe " + diagnostics.probeEntities +
                        " | frames " + diagnostics.framesAttempted +
                        " (beginFrame OK " + diagnostics.beginFrameOk + ", fallo " + diagnostics.beginFrameFail + ")" +
                        " | dibujados " + diagnostics.drawnRenderables +
                        " | camara " + diagnostics.cameraEye.joinToString(",") +
                        (if (diagnostics.errorMessage.isNotEmpty()) {
                            " | ERROR " + diagnostics.errorStage + ": " + diagnostics.errorMessage
                        } else {
                            ""
                        })
                )
            }
            if (nowMillis - lastFullReportMillis >= FULL_REPORT_INTERVAL_MILLIS) {
                lastFullReportMillis = nowMillis
                Log.i(TAG, "--- informe completo (modo " + diagnostics.mode + ") ---")
                for (line in diagnostics.lines()) {
                    Log.i(TAG, line)
                }
                for (line in diagnostics.startUpReport()) {
                    Log.i(TAG, line)
                }
            }
        }

        /**
         * The render loop's own rates: attempts per second, and how much of the
         * thread's wall time the frames that presented nothing cost. The window
         * arithmetic lives in [RenderDiagnostics] so the harness can exercise it;
         * here we only feed it the thread's elapsed time.
         */
        private fun publishRenderRates(nowMillis: Long) {
            diagnostics.loopNanos = System.nanoTime() - startNanos
            diagnostics.publishRenderRates(nowMillis)
        }

        private fun releaseEverything() {
            cameraProbe?.destroy()
            cameraProbe = null
            appliedCameraProbe = false
            diagnostics.cameraProbeOn = false
            probe?.destroy()
            probe = null
            scene?.destroy()
            scene = null
            renderer?.destroy()
            renderer = null
            testApplied = false
            testDelta = null
            ready = false
            Log.i(TAG, "render thread terminado")
        }

        private fun sleepQuietly(millis: Long) {
            try {
                sleep(millis)
            } catch (interrupted: InterruptedException) {
                currentThread().interrupt()
            }
        }
    }

    companion object {
        const val TAG = "EphoraDiag"
        private const val RELEASE_TIMEOUT_MILLIS = 5000L
        private const val SURFACE_TIMEOUT_MILLIS = 3000L
        private const val SURFACE_POLL_MILLIS = 20L

        /** Height above the agent's origin that the camera aims at. */
        const val AGENT_EYE_HEIGHT = 1.3f

        /**
         * The camera starts this close to the agent (or to the first object with
         * geometry) and this shallow, so the ground and nearby prims are inside
         * the frustum from the very first frame. Touch orbiting and pinching
         * change both afterwards; these are only the initial values.
         */
        const val AGENT_DISTANCE = 6f
        const val AGENT_PITCH = 0.22f

        /** Where the debug hook parks the forced object. */
        const val FORCED_DISTANCE = 6f

        /** The widest "near" a real prim can be to be chosen for the camera lock. */
        const val PRIM_LOCK_MAX_METRES = 50f

        /** How far back from the locked prim the camera sits, and how far above. */
        const val PRIM_LOCK_DISTANCE = 6f
        const val PRIM_LOCK_HEIGHT = 1.5f

        /**
         * DIAG-VIS benchmark (temporal, reversible): estados reales A/B/C/D
         * sobre el testigo. Cada estado se mantiene estos frames para poder
         * observarse y medirse antes de pasar al siguiente.
         */
        const val BENCH_IDLE = 0
        const val BENCH_A = 1
        const val BENCH_B = 2
        const val BENCH_C = 3
        const val BENCH_D = 4
        const val BENCH_DONE = 5
        const val BENCHMARK_FRAMES_PER_STATE = 90
        const val FORENSIC_IDLE = 0
        const val FORENSIC_F1 = 1
        const val FORENSIC_F2 = 2
        const val FORENSIC_F3 = 3
        const val FORENSIC_F4 = 4
        const val FORENSIC_F5 = 5
        const val FORENSIC_F6 = 6
        const val FORENSIC_DONE = 7
        const val FORENSIC_DWELL_MILLIS = 12000L

        /**
         * How often the visibility audit is rebuilt. It is a report (it reads
         * matrices back out of the engine and formats a page), not render state.
         */
        private const val AUDIT_INTERVAL_MILLIS = 2000L

        /** How many objects the camera audit traces (the nearest ones). */
        const val AUDIT_ROWS = 8

        /** How many parent/child rows the fase 2.10 audit lists in full. */
        const val PARENT_AUDIT_ROWS = 8

        /** How many resolved children the fase 2.11 report lists in full. */
        const val PARENT_LINK_ROWS = 10

        private const val LOG_INTERVAL_MILLIS = 5000L
        private const val FULL_REPORT_INTERVAL_MILLIS = 30000L

        /** How long to wait before retrying a failed renderer creation. */
        private const val RENDERER_RETRY_MILLIS = 2000L

        /** Report file written next to the app's private files. */
        const val REPORT_FILE = "ephora-filament.log"

        /** OpenGL ES first, per the phase plan; Vulkan is a one-word change. */
        private val BACKEND = FilamentBackend.OPENGL

        /**
         * MSAA is off while the render path is being verified: it is a second,
         * driver-dependent path through Filament (a post-process resolve) and it
         * has nothing to do with whether geometry appears. Turn it back on in
         * Phase 11 together with the quality settings.
         */
        private const val MSAA_SAMPLE_COUNT = 0

        /** Shadows stay off in the region: they are part of fase 11 quality work.
         * The probe (PRUEBA A) enables them on its own scene; the region only
         * burns a whole shadow pass on the phone GPU for 1400+ renderables. */
        private const val SHADOWS_ENABLED = false

        /**
         * Fase 2.12 LOD: beyond this distance a prim stops casting shadows while
         * the LOD switch is on. It never affects geometry or lighting, only the
         * shadow-map contribution of far objects.
         */
        const val SHADOW_LOD_DISTANCE = 40f

        /**
         * Fase 2.13b: whether the world draws with real textures at all. Off
         * means the scene gets no pipeline, which is exactly the 2.13a
         * behaviour — the switch exists so the two can be compared on the same
         * device and in the same run, with the counters on one side and silence
         * on the other.
         */
        private const val TEXTURES_ENABLED = true

        /**
         * Fase 5: si el mundo dibuja mallas reales (GetMesh + decode + swap).
         * Off deja los objetos mesh con su prim básico, como antes de esta
         * fase — el interruptor existe para comparar en el mismo dispositivo.
         */
        private const val MESH_ENABLED = true

        /**
         * The JPEG2000 decoder to build. False ships [UnavailableTextureDecoder]:
         * textures are downloaded, counted and cached, and the faces keep their
         * `TextureEntry` tint because there are no pixels yet — which is what
         * fase 2.13b is for. True uses [SyntheticTextureDecoder], whose pixels are
         * derived from the UUID rather than decoded, so the *rest* of the chain
         * (decode queue, upload, per-face binding, rebind on arrival) can be
         * exercised and measured on the device before OpenJPEG lands in 2.13c.
         *
         * It is a constant and not a switch in the UI on purpose: a build that
         * shows invented pixels must be a deliberate act, not a stray tap.
         */
        private const val TEXTURE_DECODER_SYNTHETIC = false
    }
}
