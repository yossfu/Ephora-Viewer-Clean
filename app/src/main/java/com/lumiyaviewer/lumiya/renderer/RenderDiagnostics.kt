package com.lumiyaviewer.lumiya.renderer

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

/**
 * Every number needed to answer "why is the screen black", in one place.
 *
 * The viewer's render path crosses four threads' worth of layers (network →
 * world → scene → renderer → GPU), and a silent black screen can come from any
 * one of them: an object that never got geometry, an entity that was never
 * added to the Filament scene, a camera pointed the wrong way, a swap chain
 * that never produced a buffer, a driver that refused the whole context. This
 * class is the single record that the whole chain writes into and the debug HUD
 * reads, so one look at the screen (or one copy of the log) says which layer
 * stopped.
 *
 * All fields are `@Volatile`: the render thread writes them, the UI thread
 * reads them, and no field is a compound type that needs locking (the arrays
 * are only ever replaced wholesale). It deliberately has no methods that touch
 * GPU state, so it can be used from any layer, including the scene, the world
 * model reader and the test harness.
 *
 * Nothing here decides whether a picture is *correct* — that can only be seen on
 * a device. It reports what was requested, what was created and what was
 * actually submitted, so a wrong picture can be attributed to a specific stage.
 */
class RenderDiagnostics {

    // ------------------------------------------------------------ world (SL)

    /** Which content the view is drawing: "region", "terrain", "probe". */
    @Volatile var mode: String = "region"

    /** Objects the protocol's `WorldModel` holds (the user's "490"). */
    @Volatile var modelObjects = 0

    /** Objects the world layer has snapshotted into a region. */
    @Volatile var regionObjects = 0
    @Volatile var prims = 0
    @Volatile var trees = 0
    @Volatile var avatars = 0
    @Volatile var unknownKind = 0
    @Volatile var withoutPosition = 0

    /** `SLWorld.sync` passes that produced a change set. */
    @Volatile var syncCount = 0
    @Volatile var lastDelta: String = "-"

    // ------------------------------------- object shape state (incremental model)

    /**
     * The census of what the region has described so far, counted from the
     * stored per-object state (see `WorldModel.shapeCounts`). These are the
     * numbers that separate "objects received" from "objects with a usable
     * shape", which used to be reported as one number and hid a decoder bug.
     */
    @Volatile var objectsReceived = 0
    @Volatile var objectsWithCompleteShape = 0
    @Volatile var objectsWithPartialState = 0
    @Volatile var objectsWithMissingShape = 0
    @Volatile var objectsUsingShapeFallback = 0
    @Volatile var objectsWithPersistedShape = 0

    /** Update blocks the parser could not decode at all. */
    @Volatile var parseFailures = 0

    /**
     * The object-update parser's own report — offsets per field, bogus lengths,
     * failed blocks with stack traces, and the per-object state transitions.
     * Written by `SLWorld.sync` from `ObjectUpdateDiagnostics`.
     */
    @Volatile var parserReport: String = "-"

    // --------------------------------------------- object updates (spec §2/§3)

    /** Update messages the protocol decoder applied, by type. */
    @Volatile var updateFull = 0
    @Volatile var updateCompressed = 0
    @Volatile var updateTerse = 0
    @Volatile var updateKilled = 0

    /** Compressed blobs that could not be decoded into a full object. */
    @Volatile var compressedWithoutHeader = 0
    @Volatile var compressedWithoutParams = 0

    /** The first [OBJECT_LOG_LIMIT] objects, field by field; see `SLScene.logObject`. */
    private val objectLog = ArrayList<String>()

    /** How many objects went through the per-object log (the log itself is capped). */
    @Volatile var objectLogCount = 0

    /**
     * The whole pipeline for the first real prim that arrived: what the region
     * sent, how it was classified, the parameters, the generated geometry, the
     * material, the renderable and the entity in the scene. One prim, end to
     * end, with the numbers at every step — this is the evidence that the route
     * from `ObjectUpdate` to `Scene.addEntity` works, for one object, without
     * any invented geometry.
     */
    @Volatile var firstPrimReport: String = "-"

    /**
     * Why the first prim that *should* have become a renderable did not. Kept
     * separately from [firstPrimReport] so a failure is never mistaken for
     * "nothing arrived": if the first real prim is refused, this says so while
     * the report above stays empty.
     */
    @Volatile var firstPrimFailure: String = "-"

    // ------------------------------------------------- entity rejections

    /** Entities the renderer refused to build (invalid geometry or a failed build). */
    @Volatile var entityRejections = 0

    /** The first few rejections, each with the object and the machine-readable reason. */
    private val rejectionLog = ArrayList<String>()

    /** Native meshes that arrived without face groups (a generator bug). */
    @Volatile var faceGroupFallbacks = 0

    /** Meshes that draw but leave some indices outside every face group. */
    @Volatile var geometryWarnings = 0

    /** Bounding box of every object the region sent, in region metres (Z-up). */
    @Volatile var boundsKnown: Boolean = false
    @Volatile var worldMin = FloatArray(3)
    @Volatile var worldMax = FloatArray(3)

    // ------------------------------------------------------------- terrain

    @Volatile var terrainReady: Boolean = false
    @Volatile var terrainPatches: Int = 0
    @Volatile var terrainMinHeight: Float = 0f
    @Volatile var terrainMaxHeight: Float = 0f
    @Volatile var terrainVersion: Int = 0
    @Volatile var terrainRebuilds: Int = 0
    @Volatile var terrainVertices: Int = 0
    @Volatile var terrainTriangles: Int = 0
    @Volatile var terrainSpanMetres: Float = 0f

    // --------------------------------------------------------------- scene

    @Volatile var sceneEntities: Int = 0
    @Volatile var sceneVisible: Int = 0
    @Volatile var sceneMissingGeometry: Int = 0
    @Volatile var sceneSkippedAttachments: Int = 0
    @Volatile var sceneRecreated: Int = 0
    @Volatile var sceneMeshes: Int = 0
    @Volatile var sceneMeshTriangles: Int = 0

    /** Entities refused by the renderer, and the prim pipeline's own counters. */
    @Volatile var sceneRejections: Int = 0
    @Volatile var primAttempts: Int = 0
    @Volatile var primsWithRenderable: Int = 0

    /** Geometry the generator produced a mesh for (subset of [primAttempts]). */
    @Volatile var geometryBuildSucceeded: Int = 0

    /** Entities inside the draw radius, and the ones of those in front of the camera. */
    @Volatile var objectsInsideDrawDistance: Int = 0
    @Volatile var visibleInFrustum: Int = 0

    /** Per-object shape table (one line per object); see `SLScene.shapeTable`. */
    @Volatile var shapeTable: String = "-"

    /** One HUD line for the texture stages; see `SLScene.textureHudLine`. */
    @Volatile var textureHudLine: String = "texturas: -"

    /**
     * The texture block of the report; see `SLScene.textureReport`. It is a
     * report, not render state, so the scene rebuilds it on the same 2.5 Hz
     * rhythm as the shape table.
     */
    @Volatile var textureReport: String = "-"

    /** Faces currently drawn with pixels bound; filled by the scene. */
    @Volatile var texturedFaces: Int = 0

    /** Objects with at least one face drawn with pixels bound. */
    @Volatile var texturedEntities: Int = 0

    // ------------------------------------------------- geometry generation

    @Volatile var geometryAvailable: Boolean = false
    @Volatile var geometryReason: String = "not loaded"
    @Volatile var meshBuilt: Int = 0
    @Volatile var meshCacheHits: Int = 0
    @Volatile var meshFailures: Int = 0

    /** Vertex/index counts of the most recently built mesh (spec §6). */
    @Volatile var lastMeshVertices: Int = 0
    @Volatile var lastMeshIndices: Int = 0
    @Volatile var lastMeshFaces: Int = 0
    @Volatile var lastMeshKey: Long = 0L

    // ------------------------------------------------- `probe` (PRUEBA A)

    @Volatile var probeEntities: Int = 0
    @Volatile var probeVertices: Int = 0
    @Volatile var probeTriangles: Int = 0

    // ---------------------------------------------------- renderer / Filament

    @Volatile var backendName: String = "-"
    @Volatile var engineValid: Boolean = false
    @Volatile var frameRendererValid: Boolean = false
    @Volatile var sceneValid: Boolean = false
    @Volatile var viewValid: Boolean = false
    @Volatile var cameraValid: Boolean = false
    @Volatile var swapChainValid: Boolean = false
    @Volatile var surfaceValid: Boolean = false
    @Volatile var surfaceWidth: Int = 0
    @Volatile var surfaceHeight: Int = 0
    @Volatile var viewportWidth: Int = 0
    @Volatile var viewportHeight: Int = 0

    @Volatile var renderablesCreated: Int = 0
    @Volatile var renderableInstances: Int = 0
    @Volatile var entitiesCreated: Int = 0
    @Volatile var entitiesInScene: Int = 0
    @Volatile var entityFailures: Int = 0
    /** 2.26: vivos ahora mismo (no acumulados), para la linea RECURSOS. */
    @Volatile var entitiesLive: Int = 0
    @Volatile var meshesLive: Int = 0
    @Volatile var texturesLive: Int = 0
    @Volatile var materialInstancesLive: Int = 0
    @Volatile var materialsCreated: Int = 0
    @Volatile var materialFailures: Int = 0
    @Volatile var vertexBuffers: Int = 0
    @Volatile var indexBuffers: Int = 0
    @Volatile var texturesCreated: Int = 0

    @Volatile var framesAttempted: Long = 0L
    @Volatile var beginFrameOk: Long = 0L
    @Volatile var beginFrameFail: Long = 0L
    @Volatile var renderCalls: Long = 0L
    @Volatile var endFrameCalls: Long = 0L
    @Volatile var drawnRenderables: Int = 0
    @Volatile var extraRenderables: Int = 0

    // ------------------------------------------- el bucle de render (2.13a-rev)
    //
    // Why these exist: the report showed 251325 "frames attempted" against 7058
    // beginFrame successes, and a counter on its own cannot say whether Filament
    // is broken or whether the loop is simply calling it far too often. The
    // render loop has no pacing of its own — no Choreographer, no frame callback,
    // no sleep between frames — so beginFrame (which returns false when the swap
    // chain has no free buffer) is the only thing that slows it down, and every
    // refusal costs a whole frame's set-up work. These counters measure the loop
    // itself: how often it tried, how long it spent, how much of that presented
    // nothing, and how long the worst run of refusals was.

    /** Iterations of the render thread's main loop (one per surface/renderer check). */
    @Volatile var loopIterations: Long = 0L

    /** Loop turns that stopped because no usable surface was bound. */
    @Volatile var noSurfaceSkips: Long = 0L

    /** Loop turns that stopped because the renderer was not ready yet. */
    @Volatile var rendererNotReadySkips: Long = 0L

    /** Loop turns that stopped because the view is not active (app in background). */
    @Volatile var inactiveSkips: Long = 0L

    /** beginFrame refusals in a row right now, and the worst run seen. */
    @Volatile var consecutiveBeginFrameFails: Long = 0L
    @Volatile var maxConsecutiveBeginFrameFails: Long = 0L

    // ------------------------------------------------- el pacing (2.13a-rev2)
    //
    // The first device run of these counters said why the refusals were so
    // frequent: the loop had no pacer at all. What these fields add is the
    // *evidence* of the correction — which thread draws, which thread owns
    // input, how the loop is paced now, and the frame rates that result. They
    // are the A side of the A/B the fix has to be judged by.

    /** The thread that draws (the view's render thread). */
    @Volatile var renderThreadName: String = "-"

    /** The thread the view was built on: the one that owns input and the UI. */
    @Volatile var uiThreadName: String = "-"

    /** The thread a touch event was last delivered on (evidence, not inference). */
    @Volatile var inputThreadName: String = "-"

    /** How the loop is paced, in words, so the report cannot be misread. */
    @Volatile var pacingMode: String = "-"

    /** Display refresh rate in Hz when it could be read (0 = unknown). */
    @Volatile var displayRefreshRateHz: Float = 0f

    /** Choreographer frame callbacks delivered since the loop started. */
    @Volatile var vsyncCallbacks: Long = 0L

    /** Frames that actually got a buffer and were presented. */
    @Volatile var presentedFrames: Long = 0L

    /** Wall time inside `drawFrame` that ended in a beginFrame refusal. */
    @Volatile var failedFrameNanos: Long = 0L

    /** Duration of the most recent frame that presented nothing. */
    @Volatile var lastFailedFrameNanos: Long = 0L

    /** Wall time inside `drawFrame`, presented or not. */
    @Volatile var drawNanos: Long = 0L

    /** Fase sync de drawFrame (mundo + camara, antes de render()). */
    @Volatile var frameSyncNanos: Long = 0L
    /** Fase render() de drawFrame (begin + render + endFrame de Filament). */
    @Volatile var frameRenderNanos: Long = 0L
    /** Fase post de drawFrame (stats + auditoria + informe). */
    @Volatile var framePostNanos: Long = 0L
    /** Version de la app que produce este informe. */
    @Volatile var appVersion: String = "-"
    /** Frames con mas de 1 s de pared. */
    @Volatile var slowFrames: Long = 0L
    /** Estado y cima de pila del hilo main en el ultimo frame lento. */
    @Volatile var slowFrameMain: String = "-"
    /** Wall en world.sync (leer el modelo del mundo). */
    @Volatile var frameWorldNanos: Long = 0L
    /** Wall en scene.apply (crear entidades, materiales y mallas). */
    @Volatile var frameApplyNanos: Long = 0L
    /** Objetos nuevos aplicados (acumulado) y updates aplicados. */
    @Volatile var appliedAdded: Long = 0L
    @Volatile var appliedUpdated: Long = 0L
    /** Objetos nuevos en espera de aplicacion. */
    @Volatile var pendingAddedCount: Int = 0
    /** TEST 1: updates recibidos (acumulado), en espera y aplicados por frame. */
    @Volatile var updatedReceivedTotal: Long = 0L
    @Volatile var pendingUpdatedCount: Int = 0
    @Volatile var updatedAppliedPerFrameLast: Int = 0
    @Volatile var updatedAppliedMaxPerFrame: Int = 0
    /** Llamadas a scene.apply (denominador del apply por frame). */
    @Volatile var applyCalls: Long = 0L
    /** TEST 1: desglose del upsert dentro de apply (nanos acumulados). */
    @Volatile var applyGeometryNanos: Long = 0L
    @Volatile var applyMaterialNanos: Long = 0L
    @Volatile var applyRendererNanos: Long = 0L
    @Volatile var applyTransformNanos: Long = 0L
    /** TEST 1: destino de cada upsert con geometria. */
    @Volatile var upsertFastPath: Long = 0L
    @Volatile var upsertMaterialChanged: Long = 0L
    @Volatile var upsertCreate: Long = 0L
    /** TEST 1: materiales nuevos vs reutilizados (deltas de SLTextureCache). */
    @Volatile var materialNew: Long = 0L
    @Volatile var materialReuse: Long = 0L
    /** TEST 2: camino real de materiales (FilamentMaterials, solo medición). */
    @Volatile var materialCacheHits: Long = 0L
    @Volatile var materialCacheMiss: Long = 0L
    @Volatile var materialBuildAttempts: Long = 0L
    @Volatile var materialBuildOk: Long = 0L
    @Volatile var materialFilamatBuildNanos: Long = 0L
    @Volatile var materialBuildNanos: Long = 0L
    @Volatile var materialInstanceCreates: Long = 0L
    @Volatile var materialInstanceAllocNanos: Long = 0L
    @Volatile var materialParamApplies: Long = 0L
    @Volatile var materialParamApplyNanos: Long = 0L
    @Volatile var materialRebuilds: Long = 0L
    @Volatile var materialRebuildNanos: Long = 0L
    @Volatile var materialCompiledCount: Int = 0
    @Volatile var materialKeySummary: String = "-"
    @Volatile var materialSigSummary: String = "-"
    /** 2.27c: que peldano de la escalera compila en este dispositivo. */
    @Volatile var materialTierSummary: String = "-"
    /** 2.27c: ultimo rechazo de filamat con detalle (peldano, clave, api). */
    @Volatile var materialTierFailure: String = "-"
    /** TEST 2: encuadre pasivo avatar/cámara/mundo (solo lectura, ritmo de auditoría). */
    @Volatile var sceneFrameReport: String = "-"
    /** TEST 3B: auditoría de padres + prueba diagnóstica de cámara (solo lectura). */
    @Volatile var test3bReport: String = "-"
    /** TEST7: escena controlada (camara avatar + culling ON/OFF + poblacion). */
    @Volatile var test7Report: String = "-"
    /** TEST8: AABB real vs frustum real (solo lectura + pruebas reversibles). */
    @Volatile var test8Report: String = "-"
    /** TEST9: reporte unico AABB/culling (secuencias por objeto, reversible). */
    @Volatile var test9Report: String = "-"
    /** TEST10: auditoria de escala + AABB correcto (5 fijos, A-E por objeto). */
    @Volatile var test10Report: String = "-"
    /** TEST10: paso actual y total de la secuencia (barra de progreso, -1 = sin datos). */
    @Volatile var test10Done: Int = -1
    @Volatile var test10Total: Int = -1
    /** TEST11: culling centrado (reporte + panel con estado LISTO/EJECUTANDO/TERMINADO/ERROR). */
    @Volatile var test11Report: String = "-"
    @Volatile var test11Done: Int = -1
    @Volatile var test11Total: Int = -1
    @Volatile var test11State: String = "LISTO"
    /** TEST12: causa del culling (probes + hilos + escala). */
    @Volatile var test12Report: String = "-"
    /** TEST12: hilo de cada llamada runOnFilamentThread (etiqueta = hilo). */
    @Volatile var test12Route: String = "-"
    @Volatile var test12Done: Int = -1
    @Volatile var test12Total: Int = -1
    @Volatile var test12State: String = "LISTO"
    /** TEST13: reconstruccion del Renderable (fingerprint + camino + fases). */
    @Volatile var test13Report: String = "-"
    /** TEST13: hilo de cada llamada runOnFilamentThread (etiqueta = hilo). */
    @Volatile var test13Route: String = "-"
    @Volatile var test13Done: Int = -1
    @Volatile var test13Total: Int = -1
    @Volatile var test13State: String = "LISTO"
    /** TEST14: jerarquía TransformManager + orden build/culling (reporte + panel). */
    @Volatile var test14Report: String = "-"
    /** TEST14: hilo de cada llamada runOnFilamentThread (etiqueta = hilo). */
    @Volatile var test14Route: String = "-"
    @Volatile var test14Done: Int = -1
    @Volatile var test14Total: Int = -1
    @Volatile var test14State: String = "LISTO"
    /** TEST12: frames presentados en la ultima auditoria (settleFrames). */
    @Volatile var auditFrameCount: Long = -1L
    /** TEST12: hilo propietario del Engine (nombre#id). */
    @Volatile var filamentThread: String = "-"
    /** TEST7: ultimo conteo visible con culling ON (-1 = aun no medido). */
    @Volatile var cullOnVisible: Int = -1
    /** TEST7: ultimo conteo visible con culling OFF (-1 = aun no medido). */
    @Volatile var cullOffVisible: Int = -1
    /** TEST7: renderables en escena en cada medicion. */
    @Volatile var cullOnInScene: Int = -1
    @Volatile var cullOffInScene: Int = -1

    /** Wall time the render thread has been running. */
    @Volatile var loopNanos: Long = 0L

    /**
     * The capture's rates: the counters above divided by the wall time the render
     * thread has been running ([loopNanos]).
     *
     * They are recomputed from scratch on every report (see [publishRenderRates])
     * and never accumulated, so they cannot drift from the counters printed on the
     * same line: `failedRatePerSecond == beginFrameFail / captureSeconds`, and
     * `failedDutyPercent == 100 * failedFrameNanos / loopNanos`, by construction.
     * That is what makes the whole report checkable by hand — the device asked for
     * exactly that after seeing a window-based rate next to cumulative counters.
     */
    @Volatile var attemptRatePerSecond: Double = 0.0
    @Volatile var presentedRatePerSecond: Double = 0.0
    @Volatile var failedRatePerSecond: Double = 0.0
    @Volatile var failedMillisPerSecond: Double = 0.0
    @Volatile var drawMillisPerSecond: Double = 0.0

    /** Share of the thread's wall time spent inside `drawFrame`, and inside refusals. */
    @Volatile var failedDutyPercent: Double = 0.0
    @Volatile var drawDutyPercent: Double = 0.0

    /** The denominator every capture rate above was divided by, so it can be printed. */
    @Volatile var captureSeconds: Double = 0.0

    /**
     * The same rates over the last [RATE_WINDOW_MILLIS]: how fast the loop is going
     * *now*, which an average over a capture that includes idle time cannot say.
     * Printed on its own line, with its own duration, so the two never mix.
     */
    @Volatile var windowAttemptsPerSecond: Double = 0.0
    @Volatile var windowPresentedPerSecond: Double = 0.0
    @Volatile var windowFailedPerSecond: Double = 0.0
    @Volatile var windowSeconds: Double = 0.0

    // The window sample the rates above are computed from. It lives here, next to
    // the counters, so the arithmetic is a pure function of (counters, window)
    // and the harness can exercise it without a device.
    private var rateSampleMillis = 0L
    private var rateSampleAttempts = 0L
    private var rateSamplePresented = 0L
    private var rateSampleFailedAttempts = 0L

    /** Calls to the swap chain's frame-rate hint, and the rate it was given. */
    @Volatile var frameRateHintCalls: Int = 0
    @Volatile var frameRateHintFps: Float = 0f

    /** The surface-poll interval the render loop uses, so the report can name it. */
    @Volatile var surfacePollMillis: Long = 0L

    @Volatile var msaaSampleCount: Int = 0
    @Volatile var cullingEnabled: Boolean = false
    @Volatile var shadowingEnabled: Boolean = false

    @Volatile var fps: Double = 0.0
    @Volatile var frameMillis: Double = 0.0
    @Volatile var gpuFrameMillis: Double = 0.0

    /** First error seen, with the stage that produced it. */
    @Volatile var errorStage: String = ""
    @Volatile var errorMessage: String = ""

    /** The same first failure, in full: class, message, cause chain, stack trace. */
    @Volatile var errorFull: String = ""

    /**
     * The most recent failure. It is kept separately because a failing start-up
     * is retried (the render thread asks for a renderer every frame until it has
     * one), and the *last* message is often the informative one: a class whose
     * initializer failed reports its own name on every later attempt
     * (`NoClassDefFoundError: <class>`), which is a symptom, while the first
     * message is the cause.
     */
    @Volatile var lastErrorStage: String = ""
    @Volatile var lastErrorMessage: String = ""

    // ------------------------------------------------- start-up step log

    /** START/SUCCESS/FAIL for every step of the graphics start-up, in order. */
    private val startUpSteps = ArrayList<String>()

    /** Full text (with stack traces) of every start-up failure, in order. */
    private val startUpFaults = ArrayList<String>()

    /** Environment report from the native-library preflight; see FilamentBootstrap. */
    private val environment = ArrayList<String>()

    // ------------------------------------------------------- surface (spec §4)

    @Volatile var surfaceCreatedCalls: Int = 0
    @Volatile var surfaceChangedCalls: Int = 0
    @Volatile var surfaceDestroyedCalls: Int = 0
    @Volatile var surfaceCreatedWidth: Int = 0
    @Volatile var surfaceCreatedHeight: Int = 0
    @Volatile var surfaceLastFormat: Int = 0
    @Volatile var surfaceCallbackThread: String = "-"

    /** The last few SurfaceHolder callbacks, so the handshake is visible. */
    private val surfaceEvents = ArrayList<String>()

    // --------------------------------------------------------------- camera

    @Volatile var cameraEye = FloatArray(3)
    @Volatile var cameraTarget = FloatArray(3)
    @Volatile var cameraForward = FloatArray(3)
    @Volatile var cameraUp = floatArrayOf(0f, 0f, 1f)
    @Volatile var cameraNear: Float = 0f
    @Volatile var cameraFar: Float = 0f
    @Volatile var cameraFov: Float = 0f
    @Volatile var cameraAspect: Float = 0f
    /** How the camera decided where to sit: "agente", "mundo", "prueba". */
    @Volatile var cameraFraming: String = "agente"
    @Volatile var agentPositionKnown: Boolean = false
    @Volatile var agentDistanceToWorld: Float = 0f

    /**
     * Entities that are in front of the camera (positive along its forward
     * axis), out of every entity in the scene. This is the honest version of
     * "is there anything in front of me": the world's bounding-box centre is a
     * poor proxy, because a region is 256 m across and its centre is often
     * behind a viewer standing near an edge.
     */
    @Volatile var objectsAhead: Int = 0

    /** Debug hook from the spec: one real object forced in front of the camera. */
    @Volatile var forcedObject: String = "-"
    @Volatile var forcedDistance: Float = 0f

    // -------------------------- visibilidad (fase 2.9: SL -> entidad -> camara)

    /**
     * The camera probe: one bright cube glued in front of the camera, built by
     * this viewer and *not* by Second Life. It is the control experiment that
     * separates "the render path works" from "the SL transforms are wrong".
     */
    @Volatile var cameraProbeOn: Boolean = false
    @Volatile var cameraProbePosition = FloatArray(3)
    @Volatile var cameraProbeReport: String = "-"

    /**
     * The reversible camera lock: the eye/target the camera was pinned to so it
     * looks straight at one real prim. Empty when the camera is free.
     */
    @Volatile var cameraLockedPrim: String = "-"
    @Volatile var cameraLockReport: String = "-"

    /**
     * DIAG-VIS (temporal, reversible): que fallback dibuja las caras sin
     * material ("MAGENTA (diagnostico)" o "BLANCO (normal)").
     */
    @Volatile var diagFallbackActive: String = "-"

    /**
     * DIAG-VIS (temporal, reversible): bloque del objeto testigo
     * (#622043907) escrito por SLScene; "-" cuando aun no hay testigo.
     */
    @Volatile var witnessReport: String = "-"

    /** The backend's own frustum culling, as the engine reports it. */
    @Volatile var engineFrustumCulling: String = "-"

    /**
     * The whole `SL position → entity matrix → camera → frustum` trace for the
     * objects nearest the camera, plus the camera read back from the backend and
     * the agreement between the two independent frustum computations.
     */
    @Volatile var cameraAudit: String = "-"

    // ----------------------------- parent/child y foco de objeto (fase 2.10)

    /**
     * The parent/child audit: every object the scene drew whose Second Life
     * record says it belongs to a parent, with the `Transform` the scene handed
     * over and what the renderer received. It is the report that says whether a
     * `ParentID` survived the whole chain or where it was dropped.
     */
    @Volatile var parentAudit: String = "-"

    /**
     * The fase-2.11 report: the counters of the parent/child resolution and, for
     * the first children, the whole route `SL parentLocalId → Transform.parent →
     * Filament parentEntity`. It is the line that proves a child whose parent is
     * in the scene carries a real relation, and that a child whose parent is not
     * yet there kept its local transform instead of being moved or dropped.
     */
    @Volatile var parentLinks: String = "-"

    /** The object being followed (0 = none), as a label for the HUD. */
    @Volatile var focusObject: String = "-"

    /**
     * The whole life of the followed object: parser history, the scene's own
     * entity events, the matrix the scene built, the matrix the backend holds for
     * the cached and for the current component instance, and the same reading for
     * one healthy object next to it.
     */
    @Volatile var focusReport: String = "-"

    // ------------------------------------------------- escalabilidad (fase 2.12)

    /**
     * The draw-distance culling switch and the distance it uses. It is reported
     * so an A/B measurement always says which state produced the numbers.
     */
    @Volatile var distanceCullingEnabled: Boolean = true
    @Volatile var maxDrawDistance: Float = 0f

    /**
     * Renderables the last frame left out because the object was beyond the draw
     * distance. Counted by the scene, where the distance test happens.
     */
    @Volatile var culledByDistance: Int = 0

    /** Renderables of the entities in the Filament scene, before the view culls. */
    @Volatile var renderablesInScene: Int = 0

    /** Renderables the view actually submitted in the last frame. */
    @Volatile var visibleRenderables: Int = 0

    /**
     * Renderables the view's own frustum culling left out of the last frame
     * (`renderablesInScene - visibleRenderables`). This is the number that moves
     * when `culling` is switched on: it is the engine's own measurement, not a
     * second, independent estimate.
     */
    @Volatile var culledByFrustum: Int = 0

    /**
     * Shadow LOD (fase 2.12): beyond this distance an object stops casting
     * shadows. 0 means the LOD is off and nothing about the picture is changed.
     */
    @Volatile var shadowLodDistance: Float = 0f

    /** Objects the LOD turned into non-casters in the last frame. */
    @Volatile var shadowLodReduced: Int = 0

    // ------------------------------------------------------- per-object dump

    /** Full field dump of one real object (spec §6). */
    @Volatile var objectDump: String = "-"

    /** One avatar exactly as the region sent it (spec §9): never invented. */
    @Volatile var avatarDump: String = "-"

    /** EventQueue state, kept separate from the graphics diagnosis (spec §10). */
    @Volatile var eventQueue: String = "-"

    // ----------------------------------------------------------------- writes

    /** Records an error with the stage it happened in; the first one is kept. */
    fun fail(stage: String, message: String) {
        // The first failure is the interesting one; later ones are usually its
        // consequence (a frame that cannot draw because the swap chain is gone).
        if (errorMessage.isEmpty()) {
            errorStage = stage
            errorMessage = message
        }
        lastErrorStage = stage
        lastErrorMessage = message
    }

    /**
     * Records a failure with the **whole** throwable: class, message, cause
     * chain and full stack trace. This is the method start-up code must use —
     * "createRenderer failed" without the exception is not a diagnosis, and the
     * difference between a missing library (`UnsatisfiedLinkError`), a class
     * whose initializer failed (`NoClassDefFoundError`) and a rejected API call
     * is exactly the difference between three different fixes.
     */
    fun failDetailed(stage: String, error: Throwable) {
        val summary = describe(error)
        val full = "FAIL " + stage + "\n" + summary + "\n" + stackTraceOf(error)
        synchronized(startUpSteps) {
            startUpFaults.add(full)
        }
        fail(stage, summary)
        if (errorFull.isEmpty()) {
            errorFull = full
        }
    }

    /** START/SUCCESS/FAIL for one start-up step, in order. */
    fun step(stage: String, status: String) {
        val line = status + "  " + stage
        synchronized(startUpSteps) {
            startUpSteps.add(line)
        }
    }

    /** Adds a block of environment lines (the native library preflight). */
    fun environment(lines: List<String>) {
        synchronized(startUpSteps) {
            environment.clear()
            environment.addAll(lines)
        }
    }

    /** The start-up report: environment, steps, then every failure in full. */
    fun startUpReport(): List<String> {
        val out = ArrayList<String>(32)
        synchronized(startUpSteps) {
            if (environment.isNotEmpty()) {
                out.add("--- entorno nativo ---")
                out.addAll(environment)
            }
            out.add("--- pasos de arranque ---")
            out.addAll(startUpSteps)
            if (startUpFaults.isNotEmpty()) {
                out.add("--- fallos (completos) ---")
                out.addAll(startUpFaults)
            }
        }
        return out
    }

    /** The whole report — HUD lines plus the start-up log — as one string. */
    fun fullReport(): String {
        val builder = StringBuilder(2048)
        for (line in lines()) {
            builder.append(line).append('\n')
        }
        builder.append('\n')
        for (line in startUpReport()) {
            builder.append(line).append('\n')
        }
        return builder.toString()
    }

    /**
     * The same report on disk, so it can be copied off the device without a
     * screenshot or a logcat session. Returns the file, or null when writing
     * failed (in which case the in-memory report is still available on screen).
     */
    fun writeTo(file: File): File? = try {
        file.writeText(fullReport())
        file
    } catch (error: Throwable) {
        fail("writeReport", describe(error))
        null
    }

    /** One line naming the throwable class and message, cause chain included. */
    fun describe(error: Throwable): String {
        val builder = StringBuilder(120)
        var current: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth < 8 && seen.add(current)) {
            if (depth > 0) {
                builder.append("  <-  ")
            }
            builder.append(current.javaClass.name)
            val message = current.message
            if (!message.isNullOrEmpty()) {
                builder.append(": ").append(message)
            }
            current = current.cause
            depth += 1
        }
        val frames = error.stackTrace
        if (frames.isNotEmpty()) {
            builder.append("\n    en ").append(frames[0].toString())
        }
        return builder.toString()
    }

    /** The full stack trace, cause chains included, without needing android.util.Log. */
    fun stackTraceOf(error: Throwable): String {
        val writer = StringWriter(1024)
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    /** Remembers one SurfaceHolder callback (spec §4). */
    fun surfaceEvent(event: String) {
        synchronized(startUpSteps) {
            surfaceEvents.add(event)
            while (surfaceEvents.size > 12) {
                surfaceEvents.removeAt(0)
            }
        }
        step("SURFACE", event)
    }

    private fun surfaceEventLines(): List<String> = synchronized(startUpSteps) {
        ArrayList(surfaceEvents)
    }

    fun clearError() {
        errorStage = ""
        errorMessage = ""
    }

    /**
     * Remembers one object of the region, field by field (spec §2). Only the
     * first [OBJECT_LOG_LIMIT] are kept — enough to tell a misclassification
     * from a decoding bug, and it counts all of them so the truncation is
     * visible.
     */
    fun addObjectLog(line: String) {
        synchronized(objectLog) {
            objectLogCount += 1
            if (objectLog.size < OBJECT_LOG_LIMIT) {
                objectLog.add(line)
            }
        }
    }

    fun objectLogLines(): List<String> = synchronized(objectLog) { ArrayList(objectLog) }

    /** Remembers one refused entity, with the reason the renderer gave. */
    fun rejectEntity(line: String) {
        synchronized(rejectionLog) {
            entityRejections += 1
            if (rejectionLog.size < REJECTION_LOG_LIMIT) {
                rejectionLog.add(line)
            }
        }
    }

    fun rejectionLines(): List<String> = synchronized(rejectionLog) { ArrayList(rejectionLog) }

    /**
     * Forgets the per-object log, the refusals and the first-prim report. Called
     * when the scene is rebuilt for different content, so the report always
     * describes what is on screen now.
     */
    fun resetObjectLog() {
        synchronized(objectLog) {
            objectLog.clear()
            objectLogCount = 0
        }
        synchronized(rejectionLog) {
            rejectionLog.clear()
            entityRejections = 0
        }
        firstPrimReport = "-"
        firstPrimFailure = "-"
    }

    fun recordWorldBounds(min: FloatArray, max: FloatArray) {
        boundsKnown = true
        worldMin = floatArrayOf(min[0], min[1], min[2])
        worldMax = floatArrayOf(max[0], max[1], max[2])
    }

    // ----------------------------------------------------------------- report

    private fun f2(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun f1(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun vec(v: FloatArray, digits: Int = 2): String {
        val format = "%.${digits}f, %.${digits}f, %.${digits}f"
        return String.format(Locale.US, format, v[0], v[1], v[2])
    }

    private fun ok(value: Boolean): String = if (value) "OK" else "NO"

    /**
     * The share of the *attempts* that `beginFrame` refused: `beginFrameFail /
     * framesAttempted`, the same denominator the `Frames:` line prints as
     * "intentados". An attempt that never reached `beginFrame` (no swap chain, no
     * engine) is not a refusal and is named separately on that line, so
     * `OK + fallo + sin llegar a beginFrame == intentados` always holds.
     */
    private fun beginFrameFailPercent(): Double {
        return if (framesAttempted > 0) 100.0 * beginFrameFail / framesAttempted else 0.0
    }

    /**
     * Fase 2.12c: names the A/B/C/D benchmark state from the engine's *applied*
     * flags, so a copied report says which of the four measurements it is. "A"
     * is the shipped default (culling ON, distance ON, shadows ON, LOD OFF) and
     * "-" means the switches are in some other combination.
     */
    private fun benchmarkState(): String {
        if (shadowLodDistance > 0f) return "-"
        return when {
            cullingEnabled && distanceCullingEnabled && shadowingEnabled -> "A"
            !cullingEnabled && distanceCullingEnabled && shadowingEnabled -> "B"
            cullingEnabled && !distanceCullingEnabled && shadowingEnabled -> "C"
            cullingEnabled && distanceCullingEnabled && !shadowingEnabled -> "D"
            else -> "-"
        }
    }

    /** The content of the debug HUD, one line per fact. Nothing invented here. */
    fun lines(): List<String> {
        val out = ArrayList<String>(30)
        out.add("FILAMENT DEBUG  ·  modo: $mode  ·  ${backendName}  ·  $appVersion")
        out.add("FPS ${f1(fps)}  ·  frame ${f1(frameMillis)} ms cpu / ${f1(gpuFrameMillis)} ms gpu")
        out.add("Viewport ${viewportWidth}x${viewportHeight}  (surface ${surfaceWidth}x${surfaceHeight} ${ok(surfaceValid)})")
        out.add("SurfaceView: created ${surfaceCreatedCalls} (${surfaceCreatedWidth}x${surfaceCreatedHeight} fmt ${surfaceLastFormat}), changes ${surfaceChangedCalls}, destruidos ${surfaceDestroyedCalls}  ·  hilo ${surfaceCallbackThread}")
        out.add("SL mundo: $modelObjects objetos  ·  region: $regionObjects")
        out.add(
            "SL formas (estado acumulado): recibidos $objectsReceived  ·  con forma completa $objectsWithCompleteShape" +
                "  ·  estado parcial $objectsWithPartialState  ·  SIN forma $objectsWithMissingShape" +
                "  ·  forma persistida $objectsWithPersistedShape  ·  usando fallback $objectsUsingShapeFallback (debe ser 0)"
        )
        out.add(
            "SL updates: $updateFull completos, $updateCompressed comprimidos, $updateTerse terse, $updateKilled borrados" +
                "  ·  comprimidos sin cabecera $compressedWithoutHeader / sin parametros $compressedWithoutParams" +
                " / FALLOS de parseo $parseFailures"
        )
        out.add("SL clases: $prims prims, $trees arboles, $avatars avatares, $unknownKind otros, $withoutPosition sin pos")
        out.add("SL escena: $sceneEntities entidades ($sceneVisible visibles)")
        out.add(
            "SL prims: geometria intentada $primAttempts  ·  geometria generada $geometryBuildSucceeded" +
                "  ·  renderable creado $primsWithRenderable  ·  en la Scene $sceneEntities" +
                "  ·  dentro del radio de dibujo $objectsInsideDrawDistance  ·  delante (frustum) $visibleInFrustum"
        )
        out.add(
            "SL prims sin forma: $sceneMissingGeometry  ·  adjuntos omitidos: $sceneSkippedAttachments" +
                "  ·  reconstruidos: $sceneRecreated  ·  rechazados $sceneRejections"
        )
        out.add("Mallas: $sceneMeshes ($meshBuilt creadas, $meshCacheHits cache, $meshFailures fallos)")
        out.add("Vertices/indices ultima malla: $lastMeshVertices/$lastMeshIndices en $lastMeshFaces caras")
        out.add("slcore: ${ok(geometryAvailable)}${if (geometryAvailable) "" else " (" + geometryReason + ")"}  ·  fallbacks de caras $faceGroupFallbacks, avisos de geometria $geometryWarnings")
        out.add("Entidades rechazadas por el renderer: $entityRejections")
        out.add("Terreno: ${ok(terrainReady)} $terrainPatches parches  ·  ${f1(terrainMinHeight.toDouble())}..${f1(terrainMaxHeight.toDouble())} m  ·  v$terrainVersion")
        out.add("Terreno malla: $terrainTriangles tris, $terrainVertices verts, ${terrainRebuilds} reconstrucciones, ${f1(terrainSpanMetres.toDouble())} m")
        out.add("Probe (PRUEBA A): $probeEntities entidades, $probeVertices verts, $probeTriangles tris")
        out.add("Filament: engine ${ok(engineValid)}  renderer ${ok(frameRendererValid)}  scene ${ok(sceneValid)}  view ${ok(viewValid)}  camera ${ok(cameraValid)}  swapchain ${ok(swapChainValid)}")
        out.add("Entidades: $entitiesCreated creadas / $entitiesInScene en Scene / $entityFailures fallos")
        out.add("Renderables: $renderablesCreated creados  ·  instancias $renderableInstances")
        out.add("Buffers: $vertexBuffers VBO, $indexBuffers IBO  ·  materiales $materialsCreated ($materialFailures fallos)  ·  texturas $texturesCreated")
        out.add(textureHudLine)
        val withoutBeginFrame = framesAttempted - beginFrameOk - beginFrameFail
        out.add(
            "Frames: $framesAttempted intentados  ·  beginFrame OK $beginFrameOk / fallo $beginFrameFail (" +
                f1(beginFrameFailPercent()) + "% de los intentos)" +
                (if (withoutBeginFrame > 0) "  ·  sin llegar a beginFrame $withoutBeginFrame" else "") +
                "  ·  render $renderCalls  ·  endFrame $endFrameCalls"
        )
        out.add(
            "Hilos: render '" + renderThreadName + "'  ·  UI '" + uiThreadName + "'" +
                "  ·  ultimo input '" + inputThreadName + "'" +
                (if (renderThreadName != "-" && uiThreadName != "-") {
                    if (renderThreadName == uiThreadName) {
                        "  ATENCION: el render comparte hilo con la UI"
                    } else {
                        "  (el bucle de render NO comparte hilo con la UI ni con el input)"
                    }
                } else {
                    ""
                })
        )
        out.add(
            "Pacing: " + pacingMode +
                "  ·  refresco del display " +
                (if (displayRefreshRateHz > 0f) f1(displayRefreshRateHz.toDouble()) + " Hz" else "desconocido") +
                "  ·  callbacks de frame " + vsyncCallbacks
        )
        out.add(
            "Bucle de render (2.13a-rev4): $loopIterations iteraciones  ·  sin superficie $noSurfaceSkips" +
                "  ·  renderer no listo $rendererNotReadySkips  ·  inactivo $inactiveSkips" +
                "  ·  presentados $presentedFrames" +
                "  ·  fallo consecutivo actual $consecutiveBeginFrameFails / maximo $maxConsecutiveBeginFrameFails"
        )
        //    Every rate below is the counter next to it divided by the capture's
        //    own duration, printed so the division can be checked by hand: the
        //    device saw a window rate next to cumulative counters and asked for
        //    exactly this.
        out.add(
            "Bucle de render, ritmo de la captura (" + f1(captureSeconds) + " s de hilo de render): " +
                f1(attemptRatePerSecond) + " intentos/s = " + framesAttempted + " / " +
                f1(captureSeconds) + " s" +
                "  ·  " + f1(presentedRatePerSecond) + " presentados/s = " + presentedFrames + " / " +
                f1(captureSeconds) + " s" +
                "  ·  " + f1(failedRatePerSecond) + " rechazados/s = " + beginFrameFail + " / " +
                f1(captureSeconds) + " s"
        )
        out.add(
            "Bucle de render, ritmo reciente (ventana de " + f1(windowSeconds) + " s): " +
                f1(windowAttemptsPerSecond) + " intentos/s  ·  " +
                f1(windowPresentedPerSecond) + " presentados/s  ·  " +
                f1(windowFailedPerSecond) + " rechazados/s"
        )
        out.add(
            "Bucle de render, coste (tiempo de pared del hilo): dentro de drawFrame " +
                f1(drawNanos / 1_000_000.0) + " ms de " + f1(loopNanos / 1_000_000.0) + " ms de hilo" +
                " (" + f1(drawDutyPercent) + "%)" +
                "  ·  de ese, intentos que no presentaron nada " + f1(failedFrameNanos / 1_000_000.0) + " ms" +
                " (" + f1(failedDutyPercent) + "% del hilo, " + f1(failedMillisPerSecond) + " ms/s sobre " +
                f1(captureSeconds) + " s)" +
                "  ·  coste medio por frame presentado " +
                f1(if (presentedFrames > 0) drawNanos / 1_000_000.0 / presentedFrames else 0.0) + " ms" +
                "  ·  por intento fallido " +
                f1(if (beginFrameFail > 0) failedFrameNanos / 1_000_000.0 / beginFrameFail else 0.0) + " ms"
        )
        val phaseDenom = if (presentedFrames > 0) presentedFrames.toDouble() else 1.0
        out.add(
            "Fases del frame (acumulado): sync " + f1(frameSyncNanos / 1_000_000.0) + " ms" +
                "  ·  render " + f1(frameRenderNanos / 1_000_000.0) + " ms" +
                "  ·  post " + f1(framePostNanos / 1_000_000.0) + " ms" +
                "  (medias por presentado: " + f1(frameSyncNanos / 1_000_000.0 / phaseDenom) + " / " +
                f1(frameRenderNanos / 1_000_000.0 / phaseDenom) + " / " +
                f1(framePostNanos / 1_000_000.0 / phaseDenom) + " ms)"
        )
        out.add(
            "Frames lentos (>1 s de pared): $slowFrames" +
                (if (slowFrames > 0) "  ·  main en el ultimo: $slowFrameMain" else "")
        )
        out.add(
            "Aplicacion por tiempo: mundo " + f1(frameWorldNanos / 1_000_000.0) + " ms" +
                "  ·  apply " + f1(frameApplyNanos / 1_000_000.0) + " ms" +
                " (por llamada " + f1(if (applyCalls > 0) frameApplyNanos / 1_000_000.0 / applyCalls.toDouble() else 0.0) + " ms en " + applyCalls + " llamadas)" +
                "  ·  nuevos " + appliedAdded + " (media " +
                f1(if (appliedAdded > 0) frameApplyNanos / 1_000_000.0 / appliedAdded.toDouble() else 0.0) + " ms)" +
                "  ·  updates " + appliedUpdated + "  ·  en espera $pendingAddedCount" +
                "  ·  TEST1 updates: recibidos " + updatedReceivedTotal +
                "  ·  pendientes " + pendingUpdatedCount +
                "  ·  ultimo frame " + updatedAppliedPerFrameLast +
                "  ·  max/frame " + updatedAppliedMaxPerFrame
        )
        out.add(
            "Upsert desglose TEST1 (dentro de apply): geometria " + f1(applyGeometryNanos / 1_000_000.0) + " ms" +
                "  ·  material " + f1(applyMaterialNanos / 1_000_000.0) + " ms" +
                "  ·  renderer " + f1(applyRendererNanos / 1_000_000.0) + " ms" +
                "  ·  transform " + f1(applyTransformNanos / 1_000_000.0) + " ms" +
                "  ·  rapidos " + upsertFastPath +
                "  ·  con rematerial " + upsertMaterialChanged +
                "  ·  creados " + upsertCreate +
                "  ·  mat nuevos " + materialNew +
                "  ·  mat reutilizados " + materialReuse
        )
        out.add(
            "Material TEST2 (camino real): cache hit " + materialCacheHits +
                "  ·  miss " + materialCacheMiss +
                "  ·  builds " + materialBuildAttempts + " (ok " + materialBuildOk + ")" +
                "  ·  filamat " + f1(materialFilamatBuildNanos / 1_000_000.0) + " ms" +
                "  ·  Material.build " + f1(materialBuildNanos / 1_000_000.0) + " ms" +
                "  ·  instancias " + materialInstanceCreates +
                " (" + f1(materialInstanceAllocNanos / 1_000_000.0) + " ms)" +
                "  ·  params " + materialParamApplies +
                " (" + f1(materialParamApplyNanos / 1_000_000.0) + " ms)" +
                "  ·  rebuilds " + materialRebuilds +
                " (" + f1(materialRebuildNanos / 1_000_000.0) + " ms)" +
                "  ·  compilados vivos " + materialCompiledCount
        )
        out.add("Claves TEST2 (qué se pide compilar): " + materialKeySummary)
        out.add("Firmas TEST2 (qué distingue instancias): " + materialSigSummary)
        out.add("Peldanos 2.27c (qué variante compila): " + materialTierSummary)
        out.add("Ultimo rechazo filamat: " + materialTierFailure)
        out.add(
            "Bucle de render, politicas: " + pacingMode +
                "  ·  setTargetFrameRate llamado: " +
                (if (frameRateHintCalls > 0) {
                    "si (" + frameRateHintCalls + ", " + f1(frameRateHintFps.toDouble()) + " fps)"
                } else {
                    "NO"
                }) +
                "  ·  esperas existentes: superficie no usable " + surfacePollMillis +
                " ms, renderer no listo 20 ms, inactivo 60 ms, excepcion de frame 500 ms" +
                "  ·  tras un beginFrame=false no hay espera propia: el siguiente turno" +
                " llega con el VSYNC (en el camino de respaldo, un periodo del display)"
        )
        out.add("Utilizados por el ultimo frame: $drawnRenderables dibujados  ·  $extraRenderables renderables en la escena")
        out.add("MSAA ${msaaSampleCount}x  ·  culling ${if (cullingEnabled) "ON" else "OFF"}  ·  sombras ${if (shadowingEnabled) "ON" else "OFF"}")
        out.add(
            "Escalado (fase 2.12): renderables en escena $renderablesInScene  ·  visibles $visibleRenderables" +
                "  ·  culled por frustum $culledByFrustum  ·  culled por distancia $culledByDistance" +
                "  ·  entidades $entitiesInScene de $entitiesCreated creadas"
        )
        out.add(
            "Escalado DEBUG: culling frustum ${if (cullingEnabled) "ON" else "OFF"}" +
                "  ·  distancia ${if (distanceCullingEnabled) "ON" else "OFF"} (${f1(maxDrawDistance.toDouble())} m)" +
                "  ·  sombras ${if (shadowingEnabled) "ON" else "OFF"}" +
                "  ·  LOD de sombras " +
                (if (shadowLodDistance > 0f) f1(shadowLodDistance.toDouble()) + " m ($shadowLodReduced reducidos)" else "OFF")
        )
        out.add(
            "Benchmark state " + benchmarkState() +
                "  (A = culling ON · B = culling OFF · C = distancia OFF · D = sombras OFF;" +
                " los cuatro con distancia/sombras ON y LOD OFF)"
        )
        out.add(
            "Recursos: $sceneMeshes mallas ($meshBuilt creadas, $meshCacheHits cache, $meshFailures fallos)" +
                "  ·  materiales $materialsCreated ($materialFailures fallos)  ·  buffers $vertexBuffers VBO / $indexBuffers IBO" +
                "  ·  texturas $texturesCreated"
        )
        out.add("Camara: eye ${vec(cameraEye)}")
        out.add("Camara: target ${vec(cameraTarget)}  dir ${vec(cameraForward)}")
        out.add("Camara: near ${f2(cameraNear)}  far ${f2(cameraFar)}  fov ${f2(cameraFov)}  aspect ${f2(cameraAspect)}")
        out.add("Camara: encuadre $cameraFraming  ·  agente " + (if (agentPositionKnown) ok(true) else "desconocido") + "  ·  distancia agente-mundo " + f1(agentDistanceToWorld.toDouble()) + " m")
        out.add("Objetos delante de la camara: $objectsAhead de $sceneEntities entidades ($sceneVisible dentro del radio de dibujo)")
        out.add("Culling de frustum del propio motor: $engineFrustumCulling")
        if (cameraProbeOn) {
            out.add("PRUEBA CAMARA ACTIVA: cubo probe en ${vec(cameraProbePosition)} (si NO se ve, el fallo es de camara/render; si se ve y los prims no, es de transformaciones SL)")
        }
        if (cameraLockedPrim != "-") {
            out.add("Camara BLOQUEADA mirando al prim $cameraLockedPrim (reversible; el objeto no se ha movido)")
        }
        if (boundsKnown) {
            out.add("Mundo min ${vec(worldMin)}  ·  max ${vec(worldMax)}  ·  centro ${vec(center())}")
            out.add(centreAheadLine())
        } else {
            out.add("Mundo: sin posiciones conocidas")
        }
        if (forcedDistance > 0f) {
            out.add("Forzado: objeto $forcedObject a ${f1(forcedDistance.toDouble())} m de la camara")
        }
        out.add("EventQueue: $eventQueue")
        if (avatarDump != "-") {
            out.add("Avatar (sin dibujar, fase 8): $avatarDump")
        }
        if (firstPrimReport != "-") {
            out.add("Primer prim SL real (ObjectUpdate -> Renderable):")
            out.add(firstPrimReport)
        }
        if (firstPrimFailure != "-") {
            out.add("Primer prim SL real RECHAZADO: " + firstPrimFailure)
        }
        val objects = objectLogLines()
        if (objects.isNotEmpty()) {
            out.add("Primeros $objectLogCount objetos recibidos (se registran los ${objects.size} primeros):")
            out.addAll(objects)
        }
        if (shapeTable != "-") {
            out.add("--- tabla de formas por objeto (SLWorld/SLPrimitive -> lista y -> geometria) ---")
            out.add(shapeTable)
        }
        if (textureReport != "-") {
            out.add(textureReport)
        }
        if (cameraAudit != "-") {
            out.add(cameraAudit)
        }
        if (sceneFrameReport != "-") {
            out.add(sceneFrameReport)
        }
        if (test3bReport != "-") {
            out.add(test3bReport)
        }
        if (test7Report != "-") {
            out.add(test7Report)
        }
        if (test8Report != "-") {
            out.add(test8Report)
        }
        if (test9Report != "-") {
            out.add(test9Report)
        }
        if (test10Report != "-") {
            out.add(test10Report)
        }
        if (test11Report != "-") {
            out.add(test11Report)
        }
        if (test12Report != "-") {
            out.add(test12Report)
        }
        if (test13Report != "-") {
            out.add(test13Report)
        }
        if (test14Report != "-") {
            out.add(test14Report)
        }
        if (parentAudit != "-") {
            out.add(parentAudit)
        }
        if (parentLinks != "-") {
            out.add(parentLinks)
        }
        if (focusReport != "-") {
            out.add("Objeto en FOCO: $focusObject")
            out.add(focusReport)
        }
        if (cameraProbeReport != "-") {
            out.add(cameraProbeReport)
        }
        if (cameraLockReport != "-") {
            out.add(cameraLockReport)
        }
        out.add("DIAG-VIS fallback sin material: $diagFallbackActive (MAGENTA = diagnostico, sin textura; con textura real = su textura)")
        if (witnessReport != "-") {
            out.add(witnessReport)
        }
        if (parserReport != "-") {
            out.add("--- parser de object updates ---")
            out.add(parserReport)
        }
        val rejected = rejectionLines()
        if (rejected.isNotEmpty()) {
            out.add("Entidades rechazadas (se listan las ${rejected.size} primeras de $entityRejections):")
            out.addAll(rejected)
        }
        if (errorMessage.isNotEmpty()) {
            out.add("ERROR en $errorStage: $errorMessage")
        }
        if (lastErrorMessage.isNotEmpty() && lastErrorMessage != errorMessage) {
            out.add("ULTIMO error en $lastErrorStage: $lastErrorMessage")
        }
        for (event in surfaceEventLines()) {
            out.add("SurfaceCallback: $event")
        }
        return out
    }

    /** Distance from the camera to the middle of the world's bounding box. */
    fun centreDistance(): Float {
        val c = center()
        val dx = c[0] - cameraEye[0]
        val dy = c[1] - cameraEye[1]
        val dz = c[2] - cameraEye[2]
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * The spec's section 4 question, answered in one line: is the middle of the
     * received world actually in front of the camera, or is the camera pointing
     * away from everything? A negative dot means the camera can be framed
     * perfectly and still see nothing, which is precisely the case a black
     * screen hides.
     */
    private fun centreAheadLine(): String {
        val c = center()
        val dx = c[0] - cameraEye[0]
        val dy = c[1] - cameraEye[1]
        val dz = c[2] - cameraEye[2]
        val distance = centreDistance()
        if (distance < 0.01f) {
            return "Centro del mundo delante de la camara: la camara esta en el centro"
        }
        val dot = (cameraForward[0] * dx + cameraForward[1] * dy + cameraForward[2] * dz) / distance
        return "Centro del mundo delante de la camara: " + (if (dot > 0f) "SI" else "NO") +
            " (dot " + f2(dot) + ", a " + f1(distance.toDouble()) + " m)"
    }

    fun center(): FloatArray = floatArrayOf(
        (worldMin[0] + worldMax[0]) * 0.5f,
        (worldMin[1] + worldMax[1]) * 0.5f,
        (worldMin[2] + worldMax[2]) * 0.5f
    )

    /**
     * One line per acceptance test. These are *evidence*, not verdicts: whether
     * a shape is on screen is only observable on the device, so each line says
     * what the counters prove and what still has to be confirmed by eye.
     */
    fun verdicts(probeExpected: Int, regionExpected: Int, terrainExpected: Int): List<String> {
        val out = ArrayList<String>(4)
        val renderablesForObjects = renderablesCreated - probeEntities
        out.add("PRUEBA A (Filament puro): pedidas $probeExpected entidades, creadas $probeEntities, " +
            "tris $probeTriangles, presentados $beginFrameOk frames " +
            if (probeEntities > 0 && beginFrameOk > 0 && probeTriangles > 0) {
                "-> MIRA LA PANTALLA: deberias ver 3 formas (cubo rojo iluminado, cubo verde fullbright, esfera azul) sobre un suelo gris"
            } else {
                "-> FALLA ANTES DEL DIBUJO: revisa el ERROR de arriba"
            })
        out.add("PRUEBA B (objeto SL real): recibidos $regionObjects (prims $prims, arboles $trees, " +
            "avatares $avatars, otros $unknownKind), mallas SL $sceneMeshes ($meshBuilt creadas, " +
            "$meshFailures fallos), entidades $sceneEntities, renderables SL $renderablesForObjects, " +
            "rechazados $entityRejections " +
            when {
                regionExpected == 0 ->
                    "-> sin datos de region (¿has elegido el modo region con sesion activa?)"
                entityRejections > 0 ->
                    "-> FALLA: el renderer rechazo " + entityRejections +
                        " entidad(es); el motivo y el objeto estan en el informe (seccion de rechazos)"
                sceneEntities > 0 && renderablesForObjects > 0 ->
                    "-> hay al menos un prim SL con geometria valida y renderable en la escena; " +
                        "confirma por pantalla que el terreno y los prims aparecen bajo la camara"
                sceneEntities > 0 ->
                    "-> FALLA: hay entidades pero ninguna con renderable (geometria o material)"
                else ->
                    "-> FALLA: la region tiene objetos pero ninguno llego a entidad"
            })
        out.add("PRUEBA C (terreno SL real): parches $terrainPatches, tris $terrainTriangles " +
            if (terrainExpected > 0 && terrainTriangles == 0) {
                "-> FALLA: hay terreno recibido pero no se genero malla"
            } else if (terrainTriangles > 0) {
                "-> malla generada; confirma por pantalla que hay suelo bajo el agente"
            } else {
                "-> sin terreno recibido"
            })
        return out
    }

    /**
     * Recomputes both sets of rates from the counters. Nothing is accumulated
     * here: this is a pure function of (counters, [loopNanos], `nowMillis`), which
     * is what guarantees the numbers on the report always add up.
     *
     * * The **capture** rates divide the counters by the wall time the render
     *   thread has been running ([loopNanos], which the view feeds). That is the
     *   same denominator the cost line uses, so the two can never disagree and
     *   every figure can be re-derived by hand from the counters printed above it.
     * * The **window** rates divide the counters' growth since the last window by
     *   that window's real duration: the current speed, next to the average.
     */
    fun publishRenderRates(nowMillis: Long, windowMillis: Long = RATE_WINDOW_MILLIS) {
        val seconds = loopNanos / 1_000_000_000.0
        captureSeconds = seconds
        if (seconds > 0.0) {
            attemptRatePerSecond = framesAttempted / seconds
            presentedRatePerSecond = presentedFrames / seconds
            failedRatePerSecond = beginFrameFail / seconds
            failedMillisPerSecond = failedFrameNanos / 1_000_000.0 / seconds
            drawMillisPerSecond = drawNanos / 1_000_000.0 / seconds
        }
        failedDutyPercent = if (loopNanos > 0L) 100.0 * failedFrameNanos / loopNanos else 0.0
        drawDutyPercent = if (loopNanos > 0L) 100.0 * drawNanos / loopNanos else 0.0

        if (rateSampleMillis == 0L) {
            snapshotRenderRates(nowMillis)
            return
        }
        val elapsed = nowMillis - rateSampleMillis
        if (elapsed < windowMillis) {
            return
        }
        val window = elapsed / 1000.0
        windowSeconds = window
        windowAttemptsPerSecond = (framesAttempted - rateSampleAttempts) / window
        windowPresentedPerSecond = (presentedFrames - rateSamplePresented) / window
        windowFailedPerSecond = (beginFrameFail - rateSampleFailedAttempts) / window
        snapshotRenderRates(nowMillis)
    }

    private fun snapshotRenderRates(nowMillis: Long) {
        rateSampleMillis = nowMillis
        rateSampleAttempts = framesAttempted
        rateSamplePresented = presentedFrames
        rateSampleFailedAttempts = beginFrameFail
    }

    companion object {
        /** How many objects the per-object log keeps (spec §2). */
        const val OBJECT_LOG_LIMIT = 20

        /** How many refused entities keep their reason in the report. */
        const val REJECTION_LOG_LIMIT = 5

        /**
         * How often the render-loop rates are recomputed. One *attempt* can be
         * hundreds of a second apart when the swap chain refuses, so a rate
         * measured over a couple of milliseconds would say nothing.
         */
        const val RATE_WINDOW_MILLIS = 2000L
    }
}
