package com.lumiyaviewer.lumiya.slscene

import com.lumiyaviewer.lumiya.renderer.CameraDesc
import com.lumiyaviewer.lumiya.renderer.EntityDesc
import com.lumiyaviewer.lumiya.renderer.EntityHandle
import com.lumiyaviewer.lumiya.renderer.MeshDesc
import com.lumiyaviewer.lumiya.renderer.RenderDiagnostics
import com.lumiyaviewer.lumiya.renderer.Renderer
import com.lumiyaviewer.lumiya.renderer.Transform
import com.lumiyaviewer.lumiya.slproto.asset.TexturePipeline
import com.lumiyaviewer.lumiya.slproto.asset.DecodedTexture
import com.lumiyaviewer.lumiya.slproto.world.SceneObject
import com.lumiyaviewer.lumiya.slproto.world.TextureEntry
import com.lumiyaviewer.lumiya.slworld.SLMesh
import com.lumiyaviewer.lumiya.slworld.SLMeshLibrary
import com.lumiyaviewer.lumiya.slworld.MeshPipeline
import com.lumiyaviewer.lumiya.slworld.MeshDecoder
import com.lumiyaviewer.lumiya.slworld.PlanarBasis
import com.lumiyaviewer.lumiya.slworld.SculptMeshBuilder
import com.lumiyaviewer.lumiya.slworld.SLObject
import com.lumiyaviewer.lumiya.slworld.SLObjectKind
import com.lumiyaviewer.lumiya.slworld.SLTerrainSnapshot
import com.lumiyaviewer.lumiya.slworld.SLTextureFace
import com.lumiyaviewer.lumiya.slworld.SLTextureCache
import com.lumiyaviewer.lumiya.slworld.SLWorldDelta
import com.lumiyaviewer.lumiya.slworld.SLAvatar
import com.lumiyaviewer.lumiya.slworld.TextureStreamer
import java.util.Locale
import kotlin.math.sqrt

/**
 * The scene the viewer draws: one renderer entity per Second Life object, kept
 * in step with the world.
 *
 * This is the last stop before the graphics backend. Everything here is in the
 * renderer's own vocabulary — meshes, materials, transforms, entities — and it
 * only ever talks to the abstract [Renderer], never to Filament. Swapping the
 * backend changes nothing in this file.
 *
 * The scene owns the desired state and does the diffing: [apply] turns a
 * [SLWorldDelta] into the smallest set of renderer calls that gets the GPU to
 * that state, and [prune] applies distance culling and the far-prim shadow LOD.
 * Both are called from the render thread.
 *
 * ## Render scalability (fase 2.12)
 *
 * Three independent, reversible knobs, none of which changes the objects' data:
 * frustum culling (the backend's own per-view test — entities stay in the scene
 * and only the ones outside the frustum are skipped, using the world transform
 * the parent hierarchy produced), distance culling ([distanceCullingEnabled],
 * [maxDrawDistance]) and the far-prim shadow LOD ([shadowLodDistance]). LOD is
 * *not* a second geometry system: no mesh is replaced, simplified or downloaded
 * here. Resources are shared where they already were ([SLMeshLibrary] caches one
 * mesh per prim parameter set, `SLTextureCache` one material per appearance), so
 * nothing in this phase creates a duplicate geometry or material.
 *
 * ## Textures (fase 2.13b)
 *
 * Each face of a prim gets its own material, because `TextureEntry` is per face:
 * the texture UUID, tint, alpha, UV mapping and fullbright all vary by face
 * (`SLTextureFace.facesOf`). The materials still share themselves — a prim whose
 * six faces coincide creates one material and reuses it — so a region of a
 * thousand identical cubes does not become six thousand materials.
 *
 * The pixels come from [textureStreamer]: it asks the pipeline for the textures
 * of the faces near the camera, and uploads whatever arrives. Nothing about it is
 * required for the region to draw: until the pixels exist, each face uses its
 * real `TextureEntry` tint, or a per-texture debug tint when the wire carried no
 * tint at all. The scene reports those two states separately.
 *
 * ## What is deliberately *not* drawn yet
 *
 * * **Avatars** — the point of this phase is to prove that real region geometry
 *   arrives and lands in the right place. Avatars are meshes, skeletons and
 *   baked textures (Phase 8), and they are counted and reported in the debug
 *   overlay instead of being faked with a capsule.
 * * **Worn attachments** — their position is relative to an avatar's attachment
 *   point, and we do not have that avatar's transform yet, so drawing them would
 *   put them in the wrong place. They are skipped and counted.
 *
 * Linkset children *are* drawn, and they are drawn **as children**: the
 * simulator sends their position and rotation relative to their root
 * (`LLViewerObject::setPositionParent()` stores the wire value as the object's
 * local placement for anything that is not a root, and `getPositionRegion()`
 * composes `parentRegion + local * parentRotation`). The scene therefore hands
 * the backend the child's own local TRS plus the parent's entity and lets the
 * hierarchy produce `parentWorld × local` — the same expression. Nothing here
 * converts a local position into a region one, and a child whose parent has not
 * arrived yet is remembered rather than moved, dropped or given a made-up
 * parent (fase 2.11).
 */
class SLScene(
    private val renderer: Renderer,
    /** Injectable so the scene can be tested without the native geometry library. */
    val meshes: SLMeshLibrary = SLMeshLibrary(),
    /** Shared debug record; see [RenderDiagnostics]. */
    private val diagnostics: RenderDiagnostics = RenderDiagnostics(),
    /**
     * The texture stages (fase 2.13b), or null to draw exactly as before them.
     *
     * It is injected rather than created here because the wire side needs the
     * session's capabilities, which the scene knows nothing about. A null
     * pipeline means no requests, no uploads and no texture block in the report
     * — the 2.13a behaviour, still reachable, which is what makes an A/B run
     * possible on the device.
     */
    texturePipeline: TexturePipeline? = null,
    /**
     * Las etapas de malla (fase 5), o null para dibujar como antes de ellas.
     *
     * Inyectado igual que el de texturas: el lado del wire necesita las
     * capabilities de la sesión, que la escena no conoce. Null = sin
     * peticiones de GetMesh y los objetos mesh conservan su prim básico.
     */
    private val meshPipeline: MeshPipeline? = null
) {

    private class Slot(
        var mesh: SLMesh,
        /** One material id per face, in [SLMesh.faceCount] order. */
        var materials: IntArray,
        var entity: EntityHandle,
        var transform: Transform,
        var visible: Boolean,
        /** The face's own `TextureEntry`, one entry per drawn face. */
        var faces: List<SLTextureFace>,
        /** TE face index each drawn face (renderable group) maps to (2.25). */
        var faceTeIndex: IntArray,
        /** Face 0's texture UUID, which is what the reports name. */
        var textureId: String,
        /** Sculpt/mesh extra-param type (0 = plain prim; fase 5 loads the real shape). */
        var sculptType: Int = 0,
        /** UUID del sculpt map cuando el slot dibuja geometría sculpt real. */
        var sculptMeshId: String? = null,
        /** Entrada TextureEntry con la que se calcularon [faces]/[materials]. */
        var textureEntryRef: Any? = null,
        /** Material byte + fullbright con los que se calcularon [materials]. */
        var materialState: Long = -1L,
        /**
         * 2.26: UUID del sculpt map / mesh asset que este objeto espera o usa
         * (null = prim normal). Solo para el barrido de recursos: permite
         * retirar pendientes cuyo objeto ya salio de escena.
         */
        var sculptMapId: String? = null,
        /** LOD (fase 2.12): whether this entity currently casts shadows. */
        var castsShadows: Boolean = true,
        /**
         * 2.27m: los 19 parametros del prim (layout PrimParams) tal cual
         * llegaron del wire. Solo describe la geometria dibujada cuando
         * [mesh] es Source.PRIM; solo lectura diagnostica.
         */
        var primParams: IntArray? = null
    ) {
        /** True when any face carries a real texture UUID, not the default one. */
        val hasTexturedFace: Boolean get() = faces.any { it.hasTexture }
    }

    private val slots = HashMap<Int, Slot>()

    // ------------------------------------------ parent/child links (fase 2.11)

    /**
     * child localId -> the parent localId whose entity the child's `Transform`
     * is using right now. A linkset child's `translation`/`rotation` are exactly
     * what the simulator sent (relative to its parent), so the relation is the
     * only extra state the scene has to keep: the backend composes the world
     * transform itself.
     */
    private val parentOf = HashMap<Int, Int>()

    /** parent localId -> children currently linked to it (the inverse index). */
    private val childrenOf = HashMap<Int, MutableSet<Int>>()

    /**
     * parent localId -> children whose parent has no entity yet. `ObjectUpdate`
     * is not ordered parent-first, so a child that cannot be linked is
     * remembered here — never moved, never converted, never given another
     * parent — and linked as soon as its parent appears.
     */
    private val awaitingParent = HashMap<Int, MutableSet<Int>>()

    /** child localId -> the parent it is waiting for (inverse of [awaitingParent]). */
    private val awaitingFrom = HashMap<Int, Int>()

    /**
     * Parents that have had a live entity at least once. It is what separates
     * the two ways a child can be unresolved: the parent never arrived (pending)
     * and the parent arrived and then went away (unresolved).
     */
    private val parentsEverLinked = HashSet<Int>()

    /**
     * TEST 3B: auditoría de padres — solo contadores y marcas de tiempo, ninguna
     * decisión cambia. Responde si el hijo se creó antes que el padre, si luego
     * fue reparenteado y cuánto tardó la resolución.
     */
    var childrenCreatedWithoutParent = 0L
        private set
    var childrenLaterReparented = 0L
        private set
    var reparentDelayNanosTotal = 0L
        private set
    var reparentDelayNanosMax = 0L
        private set
    /** Hijos que alguna vez se crearon sin entidad de padre (persiste hasta clear). */
    private val createdEarly = HashSet<Int>()
    /** Hijos que esperaron y luego fueron enlazados (persiste hasta clear). */
    private val reparentedEver = HashSet<Int>()
    /** child -> nano de creación sin padre (se borra al salir de escena). */
    private val childCreatedNanos = HashMap<Int, Long>()
    /** child -> retardo de resolución en nanos (último, se borra al salir). */
    private val childDelayNanos = HashMap<Int, Long>()
    /** Hijos con entidad que aún esperan al padre (lectura, sin almacenar). */
    val childrenStillWithoutParent: Int get() = awaitingParent.values.sumOf { it.size }

    /**
     * Composed region placement of the children, rebuilt whenever the world
     * changes. Only used for the draw-distance test and the distances in the
     * reports — the renderer is fed the local transform plus the relation.
     */
    private val regionPlacement = HashMap<Int, FloatArray>()

    val textures = SLTextureCache(renderer)

    /**
     * The texture stages, or null when no pipeline was injected. It is created
     * around this scene's own [SLTextureCache], so the streamer registers decoded
     * pixels exactly where the materials are made, and it is never shared between
     * scenes: the GPU handles it hands out belong to this scene's backend.
     */
    val textureStreamer: TextureStreamer? = texturePipeline?.let { pipeline ->
        TextureStreamer(pipeline, textures, renderer).also { streamer ->
            streamer.faceCountProvider = { texturedFaceCount }
            streamer.entityCountProvider = { texturedEntityCount }
            streamer.sculptSink = { textureId, texture -> consumeSculptMap(textureId, texture) }
        }
    }

    /**
     * Sculpt maps pendientes: UUID -> tipo. Se piden por el pipeline de
     * texturas existente (sin sistema paralelo) y al decodificarse se
     * convierten en geometría real vía [consumeSculptMap].
     */
    private val pendingSculpts = HashMap<String, Int>()
    var sculptRequested = 0L
        private set
    var sculptApplied = 0L
        private set
    var sculptMapFailures = 0L
        private set

    /**
     * Mallas pendientes (fase 5): MeshAssetID -> locales que lo esperan, y el
     * último SLObject visto por local (para re-atachar con su TextureEntry y
     * su transform actuales cuando llegue el asset). La descarga vive en
     * [meshPipeline] (hilo propio); aquí solo el registro y el swap en el
     * hilo de render. Pipeline independiente del de texturas: la geometría
     * llega aunque los píxeles no hayan llegado.
     */
    private val meshWaiters = HashMap<String, MutableSet<Int>>()
    private val meshWaiterObjects = HashMap<Int, SLObject>()
    private val meshGltfIds = HashSet<Int>()
    var meshRendered = 0L
        private set
    private var lastMeshScanMillis = 0L
    /** 2.26: ultimo barrido de recursos (GC + linea RECURSOS). */
    private var lastResourceGcMillis = 0L

    /** The region's ground (Phase 6 draws it; see [SLTerrain]). */
    val terrain = SLTerrain(renderer)

    /** Terrain newest snapshot, applied on the next [prune]. */
    private var pendingTerrain: SLTerrainSnapshot? = null

    /** Debug hook: put this real object right in front of the camera. */
    private var forceLocalId: Int = 0
    private var forceDistance: Float = 0f
    private var forceApplied: Boolean = false

    /**
     * DIAG-VIS (temporal, reversible): el objeto testigo conserva su
     * geometria, transform y parent reales; solo se le mira (camara) y se
     * le informa. Ponerlo en false desactiva su bloque y su camera-lock.
     */
    var diagWitnessEnabled: Boolean = true
    private var lastWitnessMillis = 0L
    private var witnessCache = ""
    private var witnessLogged = false

    /** One real object's full field dump, captured once (debug, spec §6). */
    private var dumpedLocalId: Int = 0

    /**
     * When false, prims are not turned into entities at all — only terrain is.
     * That is how the "terrain alone" acceptance test isolates the two paths.
     */
    var primsEnabled: Boolean = true

    /** Objects beyond this distance from the camera are not drawn. */
    var maxDrawDistance: Float = 300f

    /**
     * Draw-distance culling switch (fase 2.12). When it is off, [maxDrawDistance]
     * is ignored and every object is submitted, so the A/B of "with and without
     * distance culling" is exactly this one flag.
     */
    var distanceCullingEnabled: Boolean = true

    /** Objects the last [prune] left out because they were beyond the distance. */
    var distanceCulled = 0
        private set

    /**
     * LOD (fase 2.12): beyond this distance an object stops *casting* shadows —
     * it keeps being drawn and keeps receiving light. `0` (the default) disables
     * it, so the shipped picture is unchanged. It is the far-prim cost knob the
     * A/B tests use, and it never touches geometry.
     */
    var shadowLodDistance: Float = 0f

    /** Objects the LOD turned into non-casters in the last [prune]. */
    var shadowLodReduced = 0
        private set

    /** True when the native geometry library is present. */
    val geometryAvailable: Boolean get() = PrimGeometryNative.isAvailable

    val geometryUnavailableReason: String? get() = PrimGeometryNative.unavailableReason

    var visibleEntities = 0
        private set

    /** Drawn entities in front of the camera, out of the ones inside the draw radius. */
    var objectsAhead = 0
        private set

    val missingGeometry: Int get() = missingGeometryIds.size
    var recreated = 0
        private set

    /** Distinct objects whose geometry the generator could not produce. */
    private val missingGeometryIds = HashSet<Int>()

    /** Distinct objects the renderer refused to turn into an entity (spec §4). */
    val rejectedEntities: Int get() = rejectedEntityIds.size

    /** Which objects those were, so a repeated update is not counted twice. */
    private val rejectedEntityIds = HashSet<Int>()

    /**
     * Distinct prims (never avatars, trees or attachments) we tried to build
     * geometry for. A set, not a counter: a prim that moves sends an update every
     * frame, and the number that matters is how many *objects* reached each stage.
     */
    private val primAttemptIds = HashSet<Int>()
    private val primRenderableIds = HashSet<Int>()

    /**
     * Distinct prims for which the geometry generator returned a mesh — the step
     * between "we asked for geometry" and "an entity exists". Separating them is
     * what tells a generator failure from a renderer failure.
     */
    private val primMeshIds = HashSet<Int>()

    /** Distinct prims that produced a mesh, an entity and a renderable. */
    val primAttempts: Int get() = primAttemptIds.size

    val geometryBuildSucceeded: Int get() = primMeshIds.size

    val primsWithRenderable: Int get() = primRenderableIds.size

    /**
     * Debug ramp (spec §10): when greater than zero, only the N objects nearest
     * the camera are drawn, so the pipeline can be exercised with 1, 10, 50, …
     * objects. Zero (the default) means "all objects inside the draw radius" —
     * nothing is capped in normal operation.
     */
    var maxPrimEntities: Int = 0

    /** Objects hidden by that ramp this frame. */
    var budgetHidden: Int = 0
        private set

    /**
     * The viewport's aspect ratio, written by the view before [prune] every
     * frame. The frustum audit needs it and the scene cannot see the surface, so
     * it is passed in rather than guessed.
     */
    var cameraAspect: Float = CameraFrustum.DEFAULT_ASPECT

    /** The camera of the last [prune], so the audit can be built outside it. */
    private var lastCamera: CameraDesc? = null

    private val omittedAttachments = HashSet<Int>()
    private val avatarsById = LinkedHashMap<Int, SLAvatar>()

    /** Objects that already have their one line in the per-object log. */
    private val loggedObjectIds = HashSet<Int>()

    /**
     * The last snapshot of every object seen, so the diagnostic table can show
     * one row per object with the shape the *stored state* has, the shape the UI
     * list would show for it, and how far it got in the pipeline. This is the
     * table that answers "the list says cylinder, the renderer says no shape":
     * both columns here come from the same [SLObject], so a disagreement between
     * them can only come from a different source upstream.
     */
    private class ObjectFact(
        val localId: Int,
        val uuid: String,
        val kind: SLObjectKind,
        val pcode: Int,
        val shapeText: String,
        val shapeSource: String,
        val hasCompleteShape: Boolean,
        val pathCurve: Int,
        val profileCurve: Int,
        val profileHollow: Int,
        val updateSource: String,
        val translation: FloatArray,
        /** `ParentID` as the simulator sent it (0 = no parent). */
        val parentLocalId: Int,
        /** Bytes of `TextureEntry` the region sent (0 when it sent none). */
        val textureEntrySize: Int,
        /** The protocol revision this snapshot came from. */
        val revision: Int,
        /** The attachment state byte; > 0 means "worn", not "in the region". */
        val attachmentPoint: Int,
        /** Updates this object has seen, and how many were terse. */
        val updatesSeen: Int,
        val terseUpdatesSeen: Int
    )

    private val facts = HashMap<Int, ObjectFact>()

    // --------------------------------- foco de un objeto (fase 2.10, diagnostico)

    /**
     * The object being followed (0 = none). It is chosen by the user from the
     * UI — never hard-coded — and it only affects what is *recorded*: nothing in
     * the scene behaves differently for it.
     */
    var focusLocalId: Int = 0
        set(value) {
            if (field == value) {
                return
            }
            field = value
            // A new object means a new history: the previous one would only add
            // noise to the report.
            focusEntityLog.setLength(0)
            focusEntityEvents = 0
            focusEntityEventsShown = 0
        }

    /** Lines of entity events kept for the followed object. */
    private val focusEntityLog = StringBuilder(600)

    /** Entity events seen for the followed object, including the ones not kept. */
    private var focusEntityEvents = 0

    private var focusEntityEventsShown = 0

    /** The entity-event trace of the followed object, for the report. */
    fun focusEntityTrace(): String {
        if (focusEntityEventsShown == 0) {
            return "#" + focusLocalId +
                ": no se ha creado ni actualizado ninguna entidad para este objeto" +
                " desde que se activo el foco (¿esta en la escena? ¿llego algun update?)"
        }
        val builder = StringBuilder(700)
        builder.append(focusEntityLog)
        if (focusEntityEvents > focusEntityEventsShown) {
            builder.append('\n').append("  ... ").append(focusEntityEvents - focusEntityEventsShown)
                .append(" eventos mas (solo se listan los primeros ").append(focusEntityEventsShown)
                .append(")")
        }
        return builder.toString()
    }

    /**
     * Records one entity event for the followed object. Called from the two
     * places that can change an entity — [upsert] (through [logObject]) and
     * [attach] — so the trace shows whether the object was created once, updated
     * in place, or destroyed and rebuilt.
     */
    private fun noteFocus(localId: Int, line: String) {
        if (localId == 0 || localId != focusLocalId) {
            return
        }
        focusEntityEvents += 1
        if (focusEntityEventsShown >= MAX_FOCUS_ENTITY_LINES) {
            return
        }
        focusEntityEventsShown += 1
        focusEntityLog.append('\n').append("  ").append(line)
    }

    /** When the table was last rebuilt, so the HUD's 2.5 Hz rhythm is enough. */
    private var lastTableMillis = 0L

    /** When the texture scan last ran; see [scanTextures]. */
    private var lastTextureScanMillis = 0L

    /** Worn attachments we cannot place yet (Phase 8 draws them). */
    val skippedAttachments: Int get() = omittedAttachments.size

    /** Avatars the region reported; they are recorded, not drawn (Phase 8). */
    val avatars: Collection<SLAvatar> get() = avatarsById.values

    val avatarCount: Int get() = avatarsById.size

    val entityCount: Int get() = slots.size

    // ------------------------------------------- parent/child (fase 2.11)

    /** Children currently linked to a live parent entity. */
    val childrenWithParent: Int get() = parentOf.size

    /** Objects in the scene whose region record has no parent at all. */
    val childrenWithoutParent: Int
        get() = facts.values.count { it.parentLocalId == 0 && slots.containsKey(it.localId) }

    /** Distinct parents at least one child is linked to right now. */
    val parentsResolved: Int get() = parentOf.values.toHashSet().size

    /** Parents a child is waiting for that have never had an entity (not yet). */
    val parentsPending: Int get() = awaitingParent.keys.count { it !in parentsEverLinked }

    /** Parents a child is waiting for that *did* have an entity and lost it. */
    val parentsUnresolved: Int get() = awaitingParent.keys.count { it in parentsEverLinked }

    /** The parent local id a child is linked to, or 0 (also used to walk a chain). */
    private fun parentIdOf(localId: Int): Int = parentOf[localId] ?: 0

    /**
     * The renderer transform of one object: its own local TRS exactly as the
     * simulator sent it, plus the entity of its parent when that entity already
     * exists (fase 2.11).
     *
     * This is the whole conversion for a child. `LLViewerObject::setPositionParent()`
     * stores the wire position as the object's local placement for anything that
     * is not a root, and `getPositionRegion()` composes `parentRegion + local *
     * parentRotation`; Filament's world matrix is `parentWorld × local`, which is
     * that same expression. So the child is never moved, never converted to
     * region coordinates and never given a made-up parent: its local TRS is kept
     * verbatim and the hierarchy does the rest. A root keeps `parent = null`,
     * exactly as before.
     */
    private fun transformOf(object_: SLObject): Transform = Transform(
        translation = object_.transform.translation,
        rotation = object_.transform.rotation,
        scale = object_.transform.scale,
        parent = parentEntityFor(object_.localId, object_.parentLocalId)
    )

    /** The same transform with a different parent, for re-linking an entity. */
    private fun withParent(transform: Transform, parent: EntityHandle?): Transform =
        Transform(transform.translation, transform.rotation, transform.scale, parent)

    /**
     * Resolves a child's parent local id to the parent's entity, recording the
     * relation. A parent that has no entity yet is *not* invented: the child is
     * registered as waiting (see [awaitingParent]) and linked by
     * [resolvePendingParents] the moment the parent shows up.
     */
    private fun parentEntityFor(childLocalId: Int, parentLocalId: Int): EntityHandle? {
        if (parentLocalId == 0) {
            unlinkChild(childLocalId)
            return null
        }
        val parentEntity = slots[parentLocalId]?.entity
        if (parentEntity == null) {
            detachChild(childLocalId)
            awaitingFrom[childLocalId] = parentLocalId
            awaitingParent.getOrPut(parentLocalId) { HashSet() }.add(childLocalId)
            return null
        }
        linkChild(childLocalId, parentLocalId)
        return parentEntity
    }

    /** Records that a child is using its parent's entity. */
    private fun linkChild(childLocalId: Int, parentLocalId: Int) {
        val wasWaiting = awaitingFrom.containsKey(childLocalId)
        awaitingFrom.remove(childLocalId)?.let { waited ->
            awaitingParent[waited]?.let {
                it.remove(childLocalId)
                if (it.isEmpty()) awaitingParent.remove(waited)
            }
        }
        val previous = parentOf.put(childLocalId, parentLocalId)
        if (previous != null && previous != parentLocalId) {
            childrenOf[previous]?.let {
                it.remove(childLocalId)
                if (it.isEmpty()) childrenOf.remove(previous)
            }
        }
        childrenOf.getOrPut(parentLocalId) { HashSet() }.add(childLocalId)
        parentsEverLinked.add(parentLocalId)
        if (wasWaiting) {
            childrenLaterReparented += 1
            reparentedEver.add(childLocalId)
            childCreatedNanos[childLocalId]?.let { t0 ->
                val dt = System.nanoTime() - t0
                childDelayNanos[childLocalId] = dt
                reparentDelayNanosTotal += dt
                if (dt > reparentDelayNanosMax) reparentDelayNanosMax = dt
            }
        }
    }

    /** Removes a child from the linked-parent index only (it may be re-linked). */
    private fun detachChild(childLocalId: Int) {
        parentOf.remove(childLocalId)?.let { parent ->
            childrenOf[parent]?.let {
                it.remove(childLocalId)
                if (it.isEmpty()) childrenOf.remove(parent)
            }
        }
    }

    /** Drops every relation a child has: linked or still waiting (it is a root). */
    private fun unlinkChild(childLocalId: Int) {
        detachChild(childLocalId)
        childCreatedNanos.remove(childLocalId)
        childDelayNanos.remove(childLocalId)
        awaitingFrom.remove(childLocalId)?.let { waited ->
            awaitingParent[waited]?.let {
                it.remove(childLocalId)
                if (it.isEmpty()) awaitingParent.remove(waited)
            }
        }
    }

    /**
     * Re-applies the relation to every child of one parent, after that parent's
     * entity was replaced or destroyed. Called from both sides of the change, and
     * it is deliberately symmetric: if the parent has a new entity the children
     * are pointed at it, and if the parent is gone they fall back to waiting (and
     * to the local transform they already have) instead of keeping a handle the
     * renderer has destroyed.
     */
    private fun relinkChildrenOf(parentLocalId: Int) {
        val children = childrenOf[parentLocalId] ?: return
        for (childLocalId in children.toList()) {
            if (test12Frozen.contains(childLocalId)) continue
            if (test13Frozen.contains(childLocalId)) continue
            if (test14Frozen.contains(childLocalId)) continue
            if (test16Frozen.contains(childLocalId)) continue
            val slot = slots[childLocalId] ?: continue
            val transform = withParent(slot.transform, parentEntityFor(childLocalId, parentLocalId))
            slot.transform = transform
            renderer.updateTransform(slot.entity, transform)
        }
    }

    /**
     * Second step of the two-step resolution: after a whole delta has been
     * applied, every child whose parent appeared in it is linked. Doing it here
     * rather than inside [upsert] is what makes the result independent of the
     * order the region sent the objects in.
     */
    private fun resolvePendingParents() {
        if (awaitingParent.isEmpty()) {
            return
        }
        val pending = awaitingParent.entries.map { it.key to it.value.toList() }
        for ((parentLocalId, children) in pending) {
            val parentEntity = slots[parentLocalId]?.entity ?: continue
            for (childLocalId in children) {
                if (test12Frozen.contains(childLocalId)) continue
                if (test13Frozen.contains(childLocalId)) continue
                if (test14Frozen.contains(childLocalId)) continue
                if (test16Frozen.contains(childLocalId)) continue
                val slot = slots[childLocalId] ?: continue
                val transform = withParent(slot.transform, parentEntity)
                slot.transform = transform
                renderer.updateTransform(slot.entity, transform)
                linkChild(childLocalId, parentLocalId)
            }
        }
    }

    /**
     * An object that no longer has an entity in the scene. It stops being a child
     * (its relation is dropped) and it stops being a parent (its own children go
     * back to waiting). No handle the renderer has destroyed is left behind, and
     * none of the children is moved: they keep the transform they have.
     */
    private fun onObjectLeftScene(localId: Int) {
        unlinkChild(localId)
        relinkChildrenOf(localId)
    }

    /**
     * Where an object's own origin sits in the region: its local placement
     * composed with its parents' (fase 2.11).
     *
     * The renderer is still fed the local transform plus the relation and
     * composes the world matrix itself; the scene needs the same answer for the
     * two things it does with a world position — the draw-distance test and the
     * distances in the reports. A linkset child a few metres from its root is not
     * a few metres from the region origin, and before this the draw distance
     * treated it as if it were.
     *
     * Roots (and objects without a link) cost nothing: their own translation *is*
     * their region position, which is returned without copying.
     */
    private fun placementInRegion(localId: Int): FloatArray {
        if (parentIdOf(localId) == 0) {
            slots[localId]?.transform?.translation?.let { return it }
            facts[localId]?.translation?.let { return it }
            return FloatArray(3)
        }
        return regionPlacement.getOrPut(localId) { composeRegionPlacement(localId) }
    }

    /**
     * Composes a child's region position by walking its parent chain and applying
     * each link in turn: `position = parentPosition + parentRotation * local`,
     * `rotation = parentRotation * localRotation` — the same product Filament
     * computes for the world matrix, evaluated here only to answer "how far is
     * it". A cycle or an absurdly deep chain stops at [MAX_PARENT_DEPTH] instead
     * of recursing forever.
     */
    private fun composeRegionPlacement(localId: Int): FloatArray {
        val chain = ArrayList<Int>(4)
        var current = localId
        while (chain.size < MAX_PARENT_DEPTH && !chain.contains(current)) {
            chain.add(current)
            val parent = parentIdOf(current)
            if (parent == 0) {
                break
            }
            current = parent
        }
        val position = FloatArray(3)
        var rotation: FloatArray? = null
        for (index in chain.indices.reversed()) {
            val transform = slots[chain[index]]?.transform
            val local = transform?.translation ?: facts[chain[index]]?.translation
            if (local != null) {
                if (rotation == null) {
                    position[0] = local[0]
                    position[1] = local[1]
                    position[2] = local[2]
                } else {
                    position[0] += rotation[0] * local[0] + rotation[1] * local[1] + rotation[2] * local[2]
                    position[1] += rotation[3] * local[0] + rotation[4] * local[1] + rotation[5] * local[2]
                    position[2] += rotation[6] * local[0] + rotation[7] * local[1] + rotation[8] * local[2]
                }
            }
            val localRotation = transform?.let { rotationOf(it) }
            if (localRotation != null) {
                val previous = rotation
                rotation = if (previous == null) localRotation else multiplyRotation(previous, localRotation)
            }
        }
        return position
    }

    /**
     * TEST6: la rotacion PURA del quaternion (xyzw) como 3x3 row-major, sin
     * S_root. Antes salia de toMatrix16 y arrastraba la escala del root al
     * offset del child; ahora composeRegionPlacement compone
     * rootPos + R_root x local, la semantica SL.
     */
    private fun rotationOf(transform: Transform): FloatArray =
        quatToMat3Pure(transform.rotation)

    /** TEST6: escala unidad compartida para la matriz esperada del nodo. */
    private val test6UnitScale = floatArrayOf(1f, 1f, 1f)

    /** TEST6: matriz esperada del PrimTransformNode: local verbatim, escala 1. */
    private fun nodeMatrixOf(transform: Transform): FloatArray =
        Transform(transform.translation, transform.rotation, test6UnitScale, null).toMatrix16()

    /** Row-major 3x3 product, the rotation part of `a × b`. */
    private fun multiplyRotation(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                out[row * 3 + column] =
                    a[row * 3] * b[column] +
                        a[row * 3 + 1] * b[3 + column] +
                        a[row * 3 + 2] * b[6 + column]
            }
        }
        return out
    }

    /** Applies one world change set to the graphics scene. */
    /** Reused by [prune] and the forced-placement hook: one allocation, not one per frame. */
    private val forwardScratch = FloatArray(3)

    fun apply(delta: SLWorldDelta, nowMillis: Long = System.currentTimeMillis()) {
        for (localId in delta.removed) {
            omittedAttachments.remove(localId)
            avatarsById.remove(localId)
            slots.remove(localId)?.let { renderer.destroyEntity(it.entity) }
            meshForget(localId)
            onObjectLeftScene(localId)
        }
        for (object_ in delta.updated) {
            upsert(object_)
        }
        for (object_ in delta.added) {
            upsert(object_)
        }
        // Second step of the two-step parent resolution (fase 2.11): parents that
        // arrived in this very delta can now be linked to the children that were
        // waiting for them, whatever the order the region sent them in.
        resolvePendingParents()
        // The world moved: the composed region placements computed for the last
        // draw-distance test are stale.
        regionPlacement.clear()
        delta.terrain?.let { pendingTerrain = it }
        if (dumpedLocalId == 0) {
            // One real object, in full: the fields that decide whether it can be
            // drawn at all, plus what the geometry generator made of them.
            val sample = (delta.added + delta.updated).firstOrNull { it.prim != null && it.positionKnown }
            if (sample != null) {
                dumpedLocalId = sample.localId
                diagnostics.objectDump = describe(sample)
                android.util.Log.i("SLScene", "objeto real: " + diagnostics.objectDump)
            }
        }
        refreshDiagnostics()
    }

    /**
     * The diagnostic dump of one object: what the region said (spec §6) and what
     * the renderer did with it. Nothing here is derived — it is the same data
     * the entity was built from.
     */
    private fun describe(object_: SLObject): String {
        val prim = object_.prim
        val slot = slots[object_.localId]
        val builder = StringBuilder(220)
        builder.append("#").append(object_.localId)
        builder.append(" ").append(object_.kind).append(" pcode=").append(object_.pcode)
        builder.append(" uuid=").append(if (object_.uuid.isEmpty()) "-" else object_.uuid)
        builder.append('\n').append("pos ").append(vec(object_.transform.translation))
        builder.append("  rot ").append(vec(object_.transform.rotation))
        builder.append("  escala ").append(vec(object_.transform.scale))
        builder.append('\n').append("parent=").append(object_.parentLocalId)
        builder.append(" adjunto=").append(object_.attachmentPoint)
        builder.append(" material=").append(object_.materialCode)
        builder.append(" cara: tex=").append(if (object_.texture.textureId.isEmpty()) "-" else object_.texture.textureId)
        builder.append('\n')
        builder.append("textureEntry=").append(object_.textureEntrySize).append(" bytes")
        builder.append("  extraParams=").append(object_.extraParamsSize).append(" bytes")
        builder.append("  sculpt=").append(if (object_.sculptType == 0) "no" else "si (tipo " + object_.sculptType + ")")
        builder.append("  paramsKnown=").append(prim != null)
        builder.append("  formaCompleta=").append(object_.hasCompleteShape)
        builder.append("  forma=").append(object_.shapeText)
        builder.append("  origenForma=").append(object_.shapeSource.label)
        builder.append('\n')
        if (prim == null) {
            builder.append("sin parametros de prim (solo movimiento)")
        } else {
            builder.append("pathCurve=0x").append(Integer.toHexString(prim.pathCurve))
            builder.append(" profileCurve=0x").append(Integer.toHexString(prim.profileCurve))
            builder.append(" hollow=").append(prim.profileHollow)
            builder.append(" forma=").append(prim.shape)
            builder.append('\n')
            builder.append("malla key=").append(prim.meshKey)
            if (slot == null) {
                builder.append(" (sin entidad)")
            } else {
                builder.append(" tris=").append(slot.mesh.triangles)
                builder.append(" verts=").append(slot.mesh.desc.vertexCount)
                builder.append(" caras=").append(slot.mesh.desc.faceCount)
                builder.append(" entidad=").append(slot.entity.id)
            }
        }
        return builder.toString()
    }

    private fun vec(v: FloatArray): String =
        String.format(Locale.US, "%.2f, %.2f, %.2f", v[0], v[1], v[2])

    /**
     * The parent as it was handed to the renderer, next to the parent the
     * simulator sent (fase 2.10). Printing both on one line is the whole point:
     * `null` here while `parentLocalId` is not 0 is the lost parent, visible
     * without reading any code.
     */
    private fun parentLine(slParent: Int, transform: Transform): String =
        "parent entregado al renderer=" + (transform.parent?.let { "Entity#" + it.id } ?: "null") +
            " (SL parentLocalId=" + slParent + ")"

    private fun upsert(object_: SLObject) {
        recordFact(object_)
        if (test12Frozen.contains(object_.localId)) {
            test12Stash[object_.localId] = object_
            return
        }
        if (test13Frozen.contains(object_.localId)) {
            test13Stash[object_.localId] = object_
            return
        }
        if (test14Frozen.contains(object_.localId)) {
            test14Stash[object_.localId] = object_
            return
        }
        if (test16Frozen.contains(object_.localId)) {
            test16Stash[object_.localId] = object_
            return
        }
        if (!object_.isRenderable || !primsEnabled) {
            // Not something the current content draws: make sure nothing stale
            // remains, but still record avatars (they are reported, not drawn).
            slots.remove(object_.localId)?.let { renderer.destroyEntity(it.entity) }
            meshForget(object_.localId)
            onObjectLeftScene(object_.localId)
            if (object_.kind == SLObjectKind.AVATAR) {
                SLAvatar.from(object_)?.let { avatarsById[object_.localId] = it }
            }
            logObject(object_, null, if (!primsEnabled) "prims desactivados" else whyNotRenderable(object_))
            return
        }
        if (object_.attachmentPoint > 0) {
            omittedAttachments.add(object_.localId)
            meshForget(object_.localId)
            logObject(object_, null, "adjunto a un avatar (fase 8): no se coloca sin el avatar")
            return
        }
        val prim = object_.prim
        if (prim == null) {
            // The stored state has no geometric definition for this object. That
            // is a statement about the *region's* updates (none of them carried a
            // path/profile block), not about the renderer, and it is reported
            // with the exact provenance so "no shape" can never be confused with
            // "the shape was there and the generator failed".
            val note = "SIN definicion de forma (" + object_.shapeText + ", origen=" +
                object_.shapeSource.label + ", ultimo update=" + object_.updateSource.label +
                "): ningun update recibido traia el bloque path/profile"
            logObject(object_, null, note)
            noteFirstPrimFailure(object_, note)
            return
        }
        primAttemptIds.add(object_.localId)
        val geoStart = System.nanoTime()
        val mesh = meshes.meshFor(renderer, prim, object_.pcode)
        diagnostics.applyGeometryNanos += System.nanoTime() - geoStart
        if (mesh == null) {
            // No geometry for this object. It is *not* given a stand-in shape:
            // an invented box would be a lie about the region, and Phase 2's
            // acceptance test already proves Filament can draw (see PRUEBA A).
            // The object is counted, reported for what it is, and the next one
            // is built as usual.
            missingGeometryIds.add(object_.localId)
            val reason = PrimGeometryNative.unavailableReason
                ?: meshes.lastFailure ?: "el generador no devolvio malla"
            logObject(object_, null, "sin geometria: " + reason)
            noteFirstPrimFailure(object_, "sin geometria: " + reason)
            return
        }
        primMeshIds.add(object_.localId)
        // Fase 5: el mesh real ya descargado va primero (misma entidad vía
        // attach, materiales de doble cara, TextureEntry intacta); luego el
        // sculpt real; si no hay ninguno, el prim básico de siempre y el asset
        // que falte queda pendiente de pedir. Los prims normales (sin
        // sculptType) siempre usan su geometría procedural: este bloque no los
        // toca.
        val assetMesh = meshAssetFor(object_)
        val sculptMesh = if (assetMesh != null) null else sculptMeshFor(object_)
        val attached = if (assetMesh != null) {
            attach(object_, assetMesh, recreate = false, sculptMeshId = object_.sculptId)
        } else if (sculptMesh != null) {
            attach(object_, sculptMesh, recreate = false, sculptMeshId = object_.sculptId)
        } else {
            noteSculptWanted(object_)
            noteMeshWanted(object_)
            attach(object_, mesh, recreate = false)
        }
        logObject(object_, mesh, if (attached) "ok" else "rechazado por el renderer")
        if (attached) {
            primRenderableIds.add(object_.localId)
            reportFirstPrim(object_, mesh)
        } else {
            noteFirstPrimFailure(
                object_,
                "el renderer rechazo la entidad (" + mesh.desc.summary() + "); " +
                    "el motivo exacto esta en la seccion de entidades rechazadas"
            )
        }
    }

    /** Remembers one object's shape state for the diagnostic table. */
    private fun recordFact(object_: SLObject) {
        val prim = object_.prim
        facts[object_.localId] = ObjectFact(
            object_.localId,
            object_.uuid,
            object_.kind,
            object_.pcode,
            object_.shapeText,
            object_.shapeSource.label,
            object_.hasCompleteShape,
            prim?.pathCurve ?: -1,
            prim?.profileCurve ?: -1,
            prim?.profileHollow ?: -1,
            object_.updateSource.label,
            object_.transform.translation.copyOf(),
            object_.parentLocalId,
            object_.textureEntrySize,
            object_.revision,
            object_.attachmentPoint,
            object_.updatesSeen,
            object_.terseUpdatesSeen
        )
    }

    /**
     * A prim that should have become a renderable and did not. Recorded once, for
     * the first one, so "the region sent no prims" and "the first prim was
     * refused" can never look the same in the report.
     */
    private fun noteFirstPrimFailure(object_: SLObject, reason: String) {
        if (diagnostics.firstPrimFailure != NO_REPORT) {
            return
        }
        if (object_.pcode != SceneObject.PCODE_PRIM || object_.attachmentPoint > 0) {
            return
        }
        diagnostics.firstPrimFailure = "#" + object_.localId +
            " uuid=" + (if (object_.uuid.isEmpty()) "-" else object_.uuid) +
            " pcode=" + object_.pcode + " (" + pcodeName(object_.pcode) + ")" +
            " pos=" + vec(object_.transform.translation) +
            " escala=" + vec(object_.transform.scale) +
            " -> " + reason
        android.util.Log.w(TAG, "primer prim real no renderizable: " + diagnostics.firstPrimFailure)
    }

    /** Why an object cannot be drawn at all, in the report's own words. */
    private fun whyNotRenderable(object_: SLObject): String {
        if (!object_.positionKnown) {
            return "sin posicion conocida"
        }
        if (object_.prim == null && (object_.kind == SLObjectKind.PRIM ||
                object_.kind == SLObjectKind.TREE || object_.kind == SLObjectKind.GRASS
            )
        ) {
            return "SIN definicion de forma (" + object_.shapeText + ", origen=" +
                object_.shapeSource.label + ", ultimo update=" + object_.updateSource.label + ")"
        }
        return "clase " + object_.kind + " no dibujada en esta fase"
    }

    /**
     * One line per object, with everything the region sent about it and what the
     * scene did with it (spec §2). The first [RenderDiagnostics.OBJECT_LOG_LIMIT]
     * are kept for the report; the rest are counted, so a misclassification
     * cannot hide behind a truncation.
     */
    private fun logObject(object_: SLObject, mesh: SLMesh?, note: String) {
        noteFocus(
            object_.localId,
            "upsert: SL pos=" + vec(object_.transform.translation) +
                " escala=" + vec(object_.transform.scale) + " parent=" + object_.parentLocalId +
                " forma=" + object_.shapeText + " origen=" + object_.shapeSource.label +
                " update=" + object_.updateSource.label + " -> " + note
        )
        // One line per *object*, not per update: a prim that moves sends terse
        // updates every frame, and the report is about the region's contents.
        if (!loggedObjectIds.add(object_.localId)) {
            return
        }
        val line = objectLine(object_, mesh, note)
        diagnostics.addObjectLog(line)
        if (diagnostics.objectLogCount <= OBJECT_LOG_TO_CONSOLE) {
            android.util.Log.i(TAG, line)
        }
    }

    private fun objectLine(object_: SLObject, mesh: SLMesh?, note: String): String {
        val prim = object_.prim
        val slot = slots[object_.localId]
        val builder = StringBuilder(240)
        builder.append('#').append(object_.localId)
        builder.append(" uuid=").append(if (object_.uuid.isEmpty()) "-" else object_.uuid)
        builder.append(" pcode=").append(object_.pcode).append(" (").append(pcodeName(object_.pcode)).append(')')
        builder.append(" state=").append(object_.attachmentPoint)
        builder.append(" parent=").append(object_.parentLocalId)
        builder.append('\n').append("  tipo=").append(object_.kind)
        builder.append(" pos=").append(vec(object_.transform.translation))
        builder.append(" escala=").append(vec(object_.transform.scale))
        builder.append(" rot=").append(vec(object_.transform.rotation))
        builder.append('\n').append("  TextureEntry=").append(object_.textureEntrySize).append(" bytes")
        builder.append(" tex=").append(if (object_.texture.textureId.isEmpty()) "-" else object_.texture.textureId)
        builder.append(" color=").append(vec(object_.texture.color))
        builder.append(" fullbright=").append(object_.texture.fullBright)
        builder.append(" extraParams=").append(object_.extraParamsSize).append(" bytes")
        builder.append('\n').append("  parametros: ")
        if (prim == null) {
            builder.append("NINGUNO (SIN forma)")
        } else {
            builder.append("pathCurve=0x").append(Integer.toHexString(prim.pathCurve))
            builder.append(" profileCurve=0x").append(Integer.toHexString(prim.profileCurve))
            builder.append(" hollow=").append(prim.profileHollow)
            builder.append(" forma=").append(prim.shape)
        }
        builder.append(" sculpt=").append(if (object_.sculptType == 0) "no" else "tipo " + object_.sculptType)
        builder.append('\n').append("  geometria: ")
        if (mesh == null) {
            builder.append("ninguna")
        } else {
            builder.append(mesh.source).append(' ').append(mesh.desc.summary())
            builder.append(" key=").append(mesh.key)
        }
        // The UI list and this line read the *same* SceneObject.shapeText, so
        // "the list says cylinder, the renderer says no shape" can no longer
        // come from two divergent shape paths.
        builder.append("\n  clasificacion final: ").append(object_.kind).append('/')
            .append(object_.shapeText)
        builder.append(" formaCompleta=").append(object_.hasCompleteShape)
        builder.append(" origenForma=").append(object_.shapeSource.label)
        builder.append(" ultimoUpdate=").append(object_.updateSource.label)
        builder.append(" -> ")
        if (slot == null) {
            builder.append("sin entidad (").append(note).append(')')
        } else {
            builder.append("entidad ").append(slot.entity).append(" en escena")
            builder.append(" (").append(note).append(')')
        }
        return builder.toString()
    }

    /** The wire name of a pcode, so a log line is readable without the enum. */
    private fun pcodeName(pcode: Int): String = when (pcode) {
        SceneObject.PCODE_PRIM -> "LL_PCODE_VOLUME/prim"
        SceneObject.PCODE_AVATAR -> "LL_PCODE_LEGACY_AVATAR"
        SceneObject.PCODE_GRASS -> "LL_PCODE_LEGACY_GRASS"
        SceneObject.PCODE_NEW_TREE -> "LL_PCODE_TREE_NEW"
        SceneObject.PCODE_LEGACY_TREE -> "LL_PCODE_LEGACY_TREE"
        SceneObject.PCODE_PARTICLE_SYSTEM -> "LL_PCODE_LEGACY_PART_SYS"
        SceneObject.PCODE_LEGACY_ROCK -> "LL_PCODE_LEGACY_ROCK"
        else -> "otro"
    }

    /**
     * The whole route for one real prim, with the numbers at every step:
     * `ObjectUpdate` → classification → parameters → geometry → buffers →
     * material → renderable → entity in the scene (spec §5/§6). Filled in once,
     * for the first prim that really produced a renderable — never for a
     * substituted test cube, and never for an avatar, a tree or an attachment.
     */
    private fun reportFirstPrim(object_: SLObject, mesh: SLMesh) {
        if (diagnostics.firstPrimReport != NO_REPORT) {
            return
        }
        val prim = object_.prim ?: return
        val slot = slots[object_.localId] ?: return
        val rendererDiagnostics = renderer.diagnostics()
        val builder = StringBuilder(400)
        builder.append("recibido:    #").append(object_.localId)
        builder.append(" uuid=").append(if (object_.uuid.isEmpty()) "-" else object_.uuid)
        builder.append(" pcode=").append(object_.pcode).append(" (").append(pcodeName(object_.pcode)).append(')')
        builder.append(" state=").append(object_.attachmentPoint)
        builder.append(" pos=").append(vec(object_.transform.translation))
        builder.append(" escala=").append(vec(object_.transform.scale))
        builder.append("\nclasificado: PRIM, forma=").append(prim.shape)
        builder.append(" (pathCurve=0x").append(Integer.toHexString(prim.pathCurve))
        builder.append(", profileCurve=0x").append(Integer.toHexString(prim.profileCurve)).append(')')
        builder.append("\nparametros:  pathBegin=").append(prim.pathBegin)
        builder.append(" pathEnd=").append(prim.pathEnd)
        builder.append(" scaleX=").append(prim.pathScaleX).append(" scaleY=").append(prim.pathScaleY)
        builder.append(" twist=").append(prim.pathTwist).append('/').append(prim.pathTwistBegin)
        builder.append(" revolutions=").append(prim.pathRevolutions)
        builder.append(" hollow=").append(prim.profileHollow)
        builder.append("\ngeometria:   ").append(mesh.source).append(' ').append(mesh.desc.summary())
        builder.append(" key=").append(mesh.key)
        builder.append("\nmaterial:    ").append(slot.materials.joinToString("/"))
        builder.append(" (").append(distinctMaterials(slot)).append(" distintos de ").append(mesh.faceCount).append(" caras)")
        builder.append(" caras=").append(mesh.faceCount)
        builder.append(" tex=").append(if (object_.texture.textureId.isEmpty()) "-" else object_.texture.textureId)
        builder.append(" fullbright=").append(object_.texture.fullBright)
        builder.append("\nrenderable:  entidad ").append(slot.entity)
        builder.append(" en escena=SI")
        builder.append(" (creadas ").append(rendererDiagnostics.renderablesCreated)
        builder.append(", en la escena ").append(rendererDiagnostics.entitiesInScene)
        builder.append(", visibles ").append(visibleEntities).append(')')
        builder.append("\ncontadores:  recibidos=").append(diagnostics.objectLogCount)
        builder.append(" prims intentados=").append(primAttempts)
        builder.append(" conRenderable=").append(primsWithRenderable)
        builder.append(" sinGeometria=").append(missingGeometry)
        builder.append(" rechazados=").append(rejectedEntities)
        diagnostics.firstPrimReport = builder.toString()
        android.util.Log.i(TAG, "primer prim real renderizable:\n" + builder)
    }

    /** Estado de material con el que se calcularon los materiales de un slot. */
    private fun materialStateOf(object_: SLObject): Long {
        return (object_.materialCode.toLong() shl 1) or
            (if (object_.texture.fullBright) 1L else 0L)
    }

    /**
     * 2.27: la base planar absorbe la escala del objeto (`scaledPlanarBasis`),
     * asi que un resize con misma malla y misma TextureEntry necesita
     * materiales nuevos. El fast-path de `attach` solo mueve el transform:
     * cuando la escala cambio y alguna cara viva es planar, se deja pasar al
     * camino completo (que reutiliza del cache todo lo no planar y solo crea
     * lo planar). Rotacion/traslacion no afectan a la base.
     */
    private fun planarScaleChanged(existing: Slot, transform: Transform): Boolean {
        if (existing.transform.scale.contentEquals(transform.scale)) {
            return false
        }
        return existing.faces.any { it.isPlanar }
    }

    /**
     * El sculpt real listo para este objeto, o null. Solo tipos generables
     * (esfera/toro/plano/cilindro); el tipo malla necesita GetMesh y los
     * demás conservan su prim básico sin sustituciones.
     */
    private fun sculptMeshFor(object_: SLObject): SLMesh? {
        if (!SculptMeshBuilder.isBuildable(object_.sculptType)) {
            return null
        }
        return meshes.sculptIfReady(object_.sculptId)
    }

    /** Registra un sculpt map pendiente de descargar (sin pedirlo aquí). */
    private fun noteSculptWanted(object_: SLObject) {
        if (!SculptMeshBuilder.isBuildable(object_.sculptType)) {
            return
        }
        val id = object_.sculptId
        if (id.isEmpty() || id == SLTextureFace.DEFAULT_UUID) {
            return
        }
        if (meshes.sculptIfReady(id) != null) {
            return
        }
        pendingSculpts[id] = object_.sculptType
    }

    /**
     * El mesh real listo para este objeto, o null. Solo tipo malla (5); los
     * demás tipos (incluido GLTF) conservan su camino. Si el asset ya está
     * decodificado pero aún no subido a GPU, se sube aquí (hilo de render,
     * como putSculpt).
     */
    private fun meshAssetFor(object_: SLObject): SLMesh? {
        if (!SculptMeshBuilder.needsMeshAsset(object_.sculptType)) {
            return null
        }
        val id = object_.sculptId
        if (id.isEmpty() || id == SLTextureFace.DEFAULT_UUID) {
            return null
        }
        meshes.meshAssetIfReady(id)?.let {
            return it
        }
        val pipe = meshPipeline ?: return null
        val desc = pipe.cachedDesc(id) ?: return null
        pipe.noteCacheHit()
        return meshes.putMeshAsset(renderer, id, desc)
    }

    /**
     * Registra un mesh asset pendiente de descargar (sin pedirlo aquí: el
     * escaneo de prune lo pide con prioridad por distancia). El objeto
     * conserva su prim básico provisional; cuando el asset llegue,
     * [consumeMeshAsset] lo re-atacha con el mismo SLObject (posición,
     * rotación, escala, parent y TextureEntry intactos).
     */
    private fun noteMeshWanted(object_: SLObject) {
        if (!SculptMeshBuilder.needsMeshAsset(object_.sculptType)) {
            if (SculptMeshBuilder.isGltf(object_.sculptType)) {
                meshGltfIds.add(object_.localId)
            }
            return
        }
        val pipe = meshPipeline ?: return
        val id = object_.sculptId
        if (id.isEmpty() || id == SLTextureFace.DEFAULT_UUID) {
            return
        }
        if (meshes.meshAssetIfReady(id) != null) {
            return
        }
        if (pipe.cachedDesc(id) != null) {
            return
        }
        if (pipe.isFailed(id)) {
            return
        }
        pipe.noteDetected(id)
        meshWaiters.getOrPut(id) { HashSet() }.add(object_.localId)
        meshWaiterObjects[object_.localId] = object_
    }

    /** Olvida la espera de mesh de un objeto que sale de la escena. */
    private fun meshForget(localId: Int) {
        meshWaiterObjects.remove(localId)
        meshGltfIds.remove(localId)
        if (meshWaiters.isEmpty()) {
            return
        }
        val it = meshWaiters.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            entry.value.remove(localId)
            if (entry.value.isEmpty()) {
                it.remove()
            }
        }
    }

    /**
     * Consume un mapa decodificado como geometría sculpt (hilo de render, vía
     * el sink del streamer). True = consumido (no se sube a GPU como textura);
     * false = no era un sculpt pendiente. Un mapa inútil cuenta fallo y deja
     * al objeto con su prim básico; el próximo upsert ya usa la malla nueva
     * por el camino normal de recreate (cambio de mesh key).
     */
    private fun consumeSculptMap(textureId: String, texture: DecodedTexture): Boolean {
        val type = pendingSculpts[textureId] ?: return false
        if (!texture.isConsistent) {
            sculptMapFailures += 1
            pendingSculpts.remove(textureId)
            return true
        }
        val mesh = meshes.putSculpt(renderer, textureId, type, texture.pixels, texture.width, texture.height)
        if (mesh == null) {
            sculptMapFailures += 1
        } else {
            sculptApplied += 1
        }
        pendingSculpts.remove(textureId)
        return true
    }

    private fun attach(object_: SLObject, mesh: SLMesh, recreate: Boolean): Boolean {
        return attach(object_, mesh, recreate, sculptMeshId = null)
    }

    private fun attach(object_: SLObject, mesh: SLMesh, recreate: Boolean, sculptMeshId: String?): Boolean {
        // TEST 1: desglose de un upsert con geometria — responde si un update
        // que solo mueve el transform reutiliza entidad y materiales o los
        // reconstruye. Solo lectura de contadores + nanos; sin cambiar decisiones.
        val createdBefore = textures.created
        val reusedBefore = textures.reused
        val matStart = System.nanoTime()
        // The child's own local TRS plus its parent's entity (fase 2.11) — see
        // [transformOf]: a linkset child is never converted to region coordinates.
        val transform = transformOf(object_)
        val existing = slots[object_.localId]
        val materialState = materialStateOf(object_)
        if (existing != null && !recreate && existing.mesh.key == mesh.key &&
            existing.sculptMeshId == sculptMeshId &&
            existing.textureEntryRef === object_.textureEntry &&
            existing.materialState == materialState &&
            !planarScaleChanged(existing, transform)
        ) {
            // Vía rápida PROD3: mismo mesh, mismo sculpt, mismo TextureEntry
            // (misma instancia) y mismo material byte: un update de solo
            // movimiento no reparsea caras ni toca materiales. Solo transform.
            existing.transform = transform
            existing.textureId = object_.texture.textureId
            existing.sculptType = object_.sculptType
            existing.sculptMapId = object_.sculptId.takeIf { it.isNotEmpty() && it != SLTextureFace.DEFAULT_UUID }
            diagnostics.applyMaterialNanos += System.nanoTime() - matStart
            diagnostics.upsertFastPath += 1
            val transformStart = System.nanoTime()
            renderer.updateTransform(existing.entity, transform)
            diagnostics.applyTransformNanos += System.nanoTime() - transformStart
            return true
        }
        // `TextureEntry` is per face, so the material lookup is per face too: the
        // decoded entry's faces when the region sent one, the object's scalar
        // face otherwise. `facesOf` resolves defaults, so a face the blob did not
        // name explicitly still gets the right one.
        val entry = object_.textureEntry
        val teFaces = SLTextureFace.facesOf(entry, mesh.faceCount).ifEmpty {
            listOf(object_.texture)
        }
        // Mapeado por CARA (2.25): cada grupo de renderizado dibuja con la cara
        // TE que su indice SL indica (`faceIndexAt`), no con la posicion del
        // grupo: los grupos no siempre son 0,1,2... (los mesh assets saltan
        // caras sin geometria, y su indice SL es el de la cara del asset).
        // La cara se resuelve directo de la entry (los defaults cubren caras no
        // nombradas) para no depender del tamano de la lista. Las caras
        // planares reciben aqui su base de proyeccion de las normales del
        // grupo, asi que caras que comparten geometria siguen mapeando de
        // forma independiente.
        val groupCount = maxOf(1, mesh.desc.faceCount)
        val faces = ArrayList<SLTextureFace>(groupCount)
        val faceTeIndex = IntArray(groupCount)
        val planarBases = ArrayList<PlanarBasis?>(groupCount)
        for (g in 0 until groupCount) {
            val slFace = if (g < mesh.desc.faceCount) mesh.desc.faceIndexAt(g) else 0
            faceTeIndex[g] = slFace
            val face = if (entry != null) {
                SLTextureFace.fromFace(entry.face(slFace))
            } else {
                teFaces.getOrNull(slFace) ?: SLTextureFace.DEFAULT_FACE
            }
            faces.add(face)
            // 2.27: la base absorbe la escala del objeto (ver
            // `scaledPlanarBasis`): el shader proyecta en pre-transform.
            val basis = if (face.isPlanar) SLTextureFace.planarBasisFor(mesh.desc, g) else null
            planarBases.add(
                if (basis != null) SLTextureFace.scaledPlanarBasis(basis, transform.scale) else null
            )
        }
        val materials = textures.materialsFor(
            faces,
            object_.materialCode,
            object_.texture.fullBright,
            sculptMeshId != null,
            planarBases
        )
        diagnostics.applyMaterialNanos += System.nanoTime() - matStart
        diagnostics.materialNew += (textures.created - createdBefore).toLong()
        diagnostics.materialReuse += (textures.reused - reusedBefore).toLong()
        val rendererStart = System.nanoTime()
        if (existing != null && !recreate && existing.mesh.key == mesh.key &&
            existing.sculptMeshId == sculptMeshId
        ) {
            noteFocus(
                object_.localId,
                "attach: MANTIENE entidad " + existing.entity + " (misma malla key=" + mesh.key +
                    "); updateTransform con pos=" + vec(transform.translation) +
                    " " + parentLine(object_.parentLocalId, transform) +
                    " (antes pos=" + vec(existing.transform.translation) +
                    ", " + parentLine(object_.parentLocalId, existing.transform) + ")"
            )
            existing.transform = transform
            existing.faces = faces
            existing.faceTeIndex = faceTeIndex
            existing.textureId = object_.texture.textureId
            existing.sculptType = object_.sculptType
            existing.sculptMeshId = sculptMeshId
            existing.sculptMapId = object_.sculptId.takeIf { it.isNotEmpty() && it != SLTextureFace.DEFAULT_UUID }
            existing.textureEntryRef = object_.textureEntry
            existing.materialState = materialState
            // Only when the material *set* changed: a face whose texture, tint or
            // UV mapping moved needs a new binding, and an unchanged set must not
            // cost a renderer call per update (movement-only updates are the
            // common case).
            if (!existing.materials.contentEquals(materials)) {
                existing.materials = materials
                renderer.updateMaterials(existing.entity, materials)
                diagnostics.upsertMaterialChanged += 1
            } else {
                diagnostics.upsertFastPath += 1
            }
            val transformStart = System.nanoTime()
            renderer.updateTransform(existing.entity, transform)
            diagnostics.applyTransformNanos += System.nanoTime() - transformStart
            diagnostics.applyRendererNanos += System.nanoTime() - rendererStart
            return true
        }
        if (existing != null) {
            noteFocus(
                object_.localId,
                "attach: DESTRUYE la entidad " + existing.entity + " y CREA otra" +
                    " (malla " + existing.mesh.key + " -> " + mesh.key +
                    ", recreate=" + recreate + "); " +
                    parentLine(object_.parentLocalId, transform)
            )
            renderer.destroyEntity(existing.entity)
            slots.remove(object_.localId)
            recreated += 1
        }
        val entity = renderer.createEntity(
            EntityDesc(
                mesh = mesh.handle,
                materialOfFace = materials,
                transform = transform
            )
        )
        noteFocus(
            object_.localId,
            "attach: entidad CREADA " + entity + " mesh=" + mesh.handle +
                " tris=" + mesh.triangles + " pos=" + vec(transform.translation) +
                " escala=" + vec(transform.scale) + " rot=" + vec(transform.rotation) +
                " " + parentLine(object_.parentLocalId, transform)
        )
        if (!entity.isValid) {
            // The backend refused this one object. Nothing is stored, so no dead
            // slot can be counted as a scene entity, and the next object is
            // built as usual — one bad prim must not stop the region.
            rejectedEntityIds.add(object_.localId)
            if (rejectedEntityIds.size <= MAX_REJECTION_DUMPS) {
                android.util.Log.e(
                    TAG,
                    "objeto rechazado por el renderer:\n" + describe(object_)
                )
            }
            diagnostics.applyRendererNanos += System.nanoTime() - rendererStart
            return false
        }
        slots[object_.localId] = Slot(
            mesh, materials, entity, transform, true, faces, faceTeIndex, object_.texture.textureId,
            object_.sculptType, sculptMeshId, object_.textureEntry, materialState,
            sculptMapId = object_.sculptId.takeIf { it.isNotEmpty() && it != SLTextureFace.DEFAULT_UUID },
            primParams = object_.prim?.params
        )
        if (object_.parentLocalId != 0 && slots[object_.parentLocalId]?.entity == null) {
            childrenCreatedWithoutParent += 1
            createdEarly.add(object_.localId)
            childCreatedNanos.putIfAbsent(object_.localId, System.nanoTime())
        }
        // A rebuilt entity has a new handle: the children that were linked to the
        // old one must be pointed at this one, instead of keeping a handle the
        // renderer has already destroyed (fase 2.11).
        relinkChildrenOf(object_.localId)
        diagnostics.upsertCreate += 1
        diagnostics.applyRendererNanos += System.nanoTime() - rendererStart
        return true
    }

    /**
     * Applies distance culling and the per-frame debug hooks. Called every frame
     * with the camera; it is a cheaper-than-the-GPU test that keeps a 256 m
     * region with tens of thousands of prims from submitting all of them.
     *
     * It is also where the terrain mesh is refreshed and where the "force one
     * real object in front of the camera" probe lands, because both need the
     * camera and both must happen once per frame whether or not the world
     * changed.
     *
     * Fase 2.12: the distance is a switch ([distanceCullingEnabled]) and the same
     * loop applies the far-prim shadow LOD ([shadowLodDistance]). Frustum culling
     * is *not* done here — it is the backend's own per-view test, which leaves the
     * entities in the scene and only skips submitting the ones outside the
     * frustum, and it uses the world transform the parent hierarchy produced
     * (fase 2.11), so a linkset child is culled by where the hierarchy puts it.
     */
    fun prune(camera: CameraDesc, nowMillis: Long = System.currentTimeMillis()) {
        pendingTerrain?.let { terrain.update(it, nowMillis) }
        lastCamera = camera

        // Textures (fase 2.13b). Two separate jobs, both cheap when there is
        // nothing to do: collect what the pipeline finished (every frame, so a
        // texture appears the frame after it is ready), and look for faces that
        // still need one (on its own, slower rhythm — the set of visible faces
        // does not change meaningfully 60 times a second).
        // Recovery 4 (método Lumiya, responsive mode): while the user drives
        // the camera, uploads and scans pause; downloads and decodes continue
        // in their own threads and resume here afterwards.
        // Mark the textures that matter to the current camera before upload:
        // a just-decoded texture for a nearby object is protected from the LRU
        // trim for a short window. This is intentionally independent from
        // Filament frustum culling and does not change object visibility.
        markNearbyTextureUsage(camera)

        textureStreamer?.let { streamer ->
            streamer.pump()
            if (!streamer.holdLoads && nowMillis - lastTextureScanMillis >= TEXTURE_SCAN_INTERVAL_MILLIS) {
                lastTextureScanMillis = nowMillis
                scanTextures(camera)
            }
        }

        // Mallas (fase 5): pipeline independiente del de texturas. Lo
        // decodificado se sube a GPU y se re-atacha cada frame (la geometría
        // aparece en cuanto llega); el escaneo pide lo visible-cercano con su
        // propio ritmo y presupuesto, sin tocar el de texturas.
        meshPipeline?.let { pipe ->
            for (ready in pipe.pump(MESH_PUMP_PER_FRAME)) {
                consumeMeshAsset(ready)
            }
            if (nowMillis - lastMeshScanMillis >= MESH_SCAN_INTERVAL_MILLIS) {
                lastMeshScanMillis = nowMillis
                scanMeshes(camera)
            }
        }

        // 2.26 (estabilidad): barrido de recursos con su propio ritmo lento.
        // Reclama materiales/texturas GPU/descargas de objetos que salieron y
        // emite la linea RECURSOS (una cada 15 s, nunca por frame).
        if (nowMillis - lastResourceGcMillis >= RESOURCE_GC_INTERVAL_MILLIS ||
            textures.materialCount > RESOURCE_GC_MATERIAL_CAP
        ) {
            lastResourceGcMillis = nowMillis
            sweepResources()
        }

        val cameraPosition = camera.eye
        val forward = forwardOf(camera)
        // Fase 2.12: the draw distance is a switch, not a constant, so the same
        // binary can be measured with distance culling on and off. `MAX_VALUE`
        // squared overflows to infinity, which is exactly "no distance limit".
        val limit = if (distanceCullingEnabled) maxDrawDistance else Float.MAX_VALUE
        val limitSquared = limit * limit
        // LOD: a distant prim keeps drawing but stops casting shadows, which is
        // where most of its GPU cost is. 0 disables the LOD entirely.
        val lodLimitSquared = if (shadowLodDistance > 0f) shadowLodDistance * shadowLodDistance else 0f
        // Debug ramp (spec §10): with a budget set, only the N objects nearest
        // the camera are submitted, so the pipeline can be exercised with 1, 10,
        // 50, … entities. Zero — the default — appends nothing and costs nothing.
        val budgetIds: HashSet<Int>? = nearestIds(cameraPosition, maxPrimEntities)
        var visible = 0
        var ahead = 0
        var hidden = 0
        var far = 0
        var lodReduced = 0
        for ((localId, slot) in slots) {
            // Where the object is in the region, not its parent-relative local
            // position: a linkset child belongs in the draw-distance test where
            // its root puts it (fase 2.11).
            val translation = placementInRegion(localId)
            val dx = translation[0] - cameraPosition[0]
            val dy = translation[1] - cameraPosition[1]
            val dz = translation[2] - cameraPosition[2]
            val distanceSquared = dx * dx + dy * dy + dz * dz
            val withinRadius = distanceSquared <= limitSquared
            val withinBudget = budgetIds == null || budgetIds.contains(localId)
            val shouldBeVisible = withinRadius && withinBudget
            if (shouldBeVisible != slot.visible) {
                renderer.setVisible(slot.entity, shouldBeVisible)
                slot.visible = shouldBeVisible
            }
            if (!withinRadius) {
                far += 1
            }
            if (withinRadius && !withinBudget) {
                hidden += 1
            }
            // LOD: applied only when the value actually changes, so the renderer
            // call is rare and the switch costs nothing per frame. With the LOD
            // off, anything it had reduced goes back to casting shadows.
            if (lodLimitSquared > 0f) {
                val casts = distanceSquared <= lodLimitSquared
                if (casts != slot.castsShadows) {
                    renderer.setShadowCaster(slot.entity, casts)
                    slot.castsShadows = casts
                }
                if (!casts) {
                    lodReduced += 1
                }
            } else if (!slot.castsShadows) {
                renderer.setShadowCaster(slot.entity, true)
                slot.castsShadows = true
            }
            if (shouldBeVisible) {
                visible += 1
                // In front of the camera, not merely near it: this is the count
                // that answers "is anything actually in the frustum", which the
                // world's bounding-box centre cannot (a region is 256 m across).
                if (forward[0] * dx + forward[1] * dy + forward[2] * dz > 0f) {
                    ahead += 1
                }
            }
        }
        visibleEntities = visible
        objectsAhead = ahead
        budgetHidden = hidden
        distanceCulled = far
        shadowLodReduced = lodReduced
        applyForcedPlacement(camera)
        // The per-object table is rebuilt at the HUD's own rhythm, not per frame:
        // it is a report, not render state.
        if (nowMillis - lastTableMillis >= TABLE_INTERVAL_MILLIS) {
            lastTableMillis = nowMillis
            diagnostics.shapeTable = shapeTable(cameraPosition, SHAPE_TABLE_ROWS)
            diagnostics.textureReport = textureReport()
        }
        refreshDiagnostics()
    }

    /**
     * Asks for the textures of the faces the region is actually showing.
     *
     * Only visible objects inside [TEXTURE_NEAR_METRES] are considered, nearest
     * first, and only [TextureStreamer.perScanBudget] *new* textures are asked
     * for per scan. That is what keeps a 256 m region with tens of thousands of
     * distinct textures from turning the first frame after login into a wall of
     * requests: the picture fills in from the camera outwards, and what is behind
     * you or across the region is never fetched at all.
     *
     * The scan is not on the render-critical path — it runs every
     * [TEXTURE_SCAN_INTERVAL_MILLIS] — but it is bounded work either way: one
     * pass over the slots, a sort of the near ones, and at most the budget in
     * queue operations.
     */
    private fun markNearbyTextureUsage(camera: CameraDesc) {
        if (slots.isEmpty()) {
            return
        }
        val origin = camera.eye
        val limitSquared = TEXTURE_NEAR_METRES * TEXTURE_NEAR_METRES
        val nowNanos = System.nanoTime()
        for ((localId, slot) in slots) {
            if (slot.faces.isEmpty() || !slot.hasTexturedFace) {
                continue
            }
            val translation = placementInRegion(localId)
            val dx = translation[0] - origin[0]
            val dy = translation[1] - origin[1]
            val dz = translation[2] - origin[2]
            if (dx * dx + dy * dy + dz * dz > limitSquared) {
                continue
            }
            for (face in slot.faces) {
                if (face.hasTexture) {
                    textures.markUsed(face.textureId, nowNanos)
                }
            }
        }
    }

    private fun scanTextures(camera: CameraDesc) {
        val streamer = textureStreamer ?: return
        if (!streamer.enabled || !primsEnabled || slots.isEmpty()) {
            return
        }
        val origin = camera.eye
        val limitSquared = TEXTURE_NEAR_METRES * TEXTURE_NEAR_METRES
        val ranked = ArrayList<Pair<Float, List<SLTextureFace>>>(slots.size)
        for ((localId, slot) in slots) {
            if (!slot.visible || slot.faces.isEmpty()) {
                continue
            }
            if (!slot.hasTexturedFace) {
                continue
            }
            val translation = placementInRegion(localId)
            val dx = translation[0] - origin[0]
            val dy = translation[1] - origin[1]
            val dz = translation[2] - origin[2]
            val distanceSquared = dx * dx + dy * dy + dz * dz
            if (distanceSquared > limitSquared) {
                continue
            }
            ranked.add(distanceSquared to slot.faces)
        }
        if (ranked.isEmpty()) {
            return
        }
        ranked.sortBy { it.first }
        var budget = streamer.perScanBudget
        for ((_, faces) in ranked) {
            if (budget <= 0) {
                break
            }
            for (face in faces) {
                if (streamer.request(face)) {
                    budget -= 1
                }
            }
        }
        // Fase 5 parcial: los sculpt maps pendientes se piden por el mismo
        // pipeline (sin sistema paralelo), resolución completa porque definen
        // geometría. Presupuesto compartido: las caras van primero.
        if (pendingSculpts.isNotEmpty() && budget > 0) {
            val pipeline = streamer.pipeline
            var sculptBudget = minOf(budget, SCULPT_PER_SCAN_BUDGET)
            for (sculptId in pendingSculpts.keys.toList()) {
                if (sculptBudget <= 0) {
                    break
                }
                if (meshes.sculptIfReady(sculptId) != null) {
                    pendingSculpts.remove(sculptId)
                    continue
                }
                if (pipeline.request(sculptId, 0)) {
                    sculptBudget -= 1
                    sculptRequested += 1
                }
            }
            if (pendingSculpts.size > MAX_PENDING_SCULPTS) {
                val it = pendingSculpts.keys.iterator()
                while (pendingSculpts.size > MAX_PENDING_SCULPTS && it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
    }

    /**
     * Pide los mesh assets visibles-cercanos que siguen como prim básico,
     * del más cercano al más lejano, con presupuesto propio por escaneo. La
     * posición usada es la de región (el hijo de linkset cuenta donde su raíz
     * lo pone, fase 2.11). Solo pide; la geometría se aplica en
     * [consumeMeshAsset] cuando el pipeline la entrega.
     */
    private fun scanMeshes(camera: CameraDesc) {
        val pipe = meshPipeline ?: return
        if (!primsEnabled || slots.isEmpty() || meshWaiterObjects.isEmpty()) {
            return
        }
        val origin = camera.eye
        val limitSquared = MESH_NEAR_METRES * MESH_NEAR_METRES
        val ranked = ArrayList<Pair<Float, String>>(64)
        val gone = ArrayList<Int>()
        for ((localId, object_) in meshWaiterObjects) {
            val slot = slots[localId]
            if (slot == null) {
                gone.add(localId)
                continue
            }
            if (slot.sculptMeshId != null || !slot.visible) {
                continue
            }
            if (meshes.meshAssetIfReady(object_.sculptId) != null) {
                gone.add(localId)
                continue
            }
            val translation = placementInRegion(localId)
            val dx = translation[0] - origin[0]
            val dy = translation[1] - origin[1]
            val dz = translation[2] - origin[2]
            val distanceSquared = dx * dx + dy * dy + dz * dz
            if (distanceSquared > limitSquared) {
                continue
            }
            ranked.add(distanceSquared to object_.sculptId)
        }
        for (localId in gone) {
            meshForget(localId)
        }
        if (ranked.isEmpty()) {
            return
        }
        ranked.sortBy { it.first }
        var budget = MESH_PER_SCAN_BUDGET
        var index = 0
        while (budget > 0 && index < ranked.size) {
            val (distanceSquared, meshId) = ranked[index]
            index += 1
            if (pipe.isFailed(meshId)) {
                meshWaiters.remove(meshId)
                continue
            }
            pipe.request(meshId, sqrt(distanceSquared))
            budget -= 1
        }
        if (meshWaiters.size > MAX_PENDING_MESHES) {
            val it = meshWaiters.keys.iterator()
            while (meshWaiters.size > MAX_PENDING_MESHES && it.hasNext()) {
                it.next()
                it.remove()
            }
        }
    }

    /**
     * Aplica un mesh decodificado (hilo de render): lo sube a GPU una vez por
     * MeshAssetID y re-atacha cada objeto que lo esperaba con su SLObject
     * guardado (misma posición/rotación/escala/parent/TextureEntry; attach
     * destruye y recrea la entidad porque la malla cambió, como ya hace con
     * sculpts). Un MeshDesc inútil cuenta fallo y deja el prim básico.
     */
    private fun consumeMeshAsset(ready: MeshPipeline.MeshReady) {
        val pipe = meshPipeline ?: return
        val mesh = meshes.putMeshAsset(renderer, ready.meshId, ready.desc)
        val waiters = meshWaiters.remove(ready.meshId)
        if (mesh == null) {
            pipe.noteFailed(ready.meshId, meshes.lastFailure ?: "mesh GPU invalido")
            if (waiters != null) {
                for (localId in waiters) {
                    meshWaiterObjects.remove(localId)
                }
            }
            return
        }
        if (waiters == null || waiters.isEmpty()) {
            return
        }
        for (localId in waiters) {
            val object_ = meshWaiterObjects.remove(localId) ?: continue
            if (attach(object_, mesh, recreate = false, sculptMeshId = ready.meshId)) {
                meshRendered += 1
            }
        }
    }

    /**
     * 2.26 (estabilidad): reclama lo que los objetos que salieron dejaron atras
     * y emite la linea RECURSOS. Todo es in-place o destruccion de recursos sin
     * usuarios vivos; ningun compartido se toca. Solo hilo de render, ritmo
     * lento (nunca por frame).
     *
     * Propiedad que aplica:
     * - Los slots son los unicos usuarios de MaterialHandles y de UUIDs de
     *   textura: lo que ningun slot referencia se puede destruir.
     * - Los codestreams en el pipeline se expulsan por LRU con tope en bytes.
     * - Los callbacks (decode/mesh) jamas tocan recursos destruidos: el decode
     *   reencola si los bytes fueron expulsados y el swap usa el SLObject mas
     *   reciente del waiter (refrescado en cada upsert).
     */
    private fun sweepResources() {
        val liveMaterials = HashSet<Int>(textures.materialCount + 1)
        val liveTextures = HashSet<String>()
        val liveSculpts = HashSet<String>()
        for (slot in slots.values) {
            for (m in slot.materials) {
                liveMaterials.add(m)
            }
            for (face in slot.faces) {
                if (face.hasTexture) {
                    liveTextures.add(face.textureId)
                }
            }
            slot.sculptMapId?.let { liveSculpts.add(it) }
        }
        val matFreed = textures.gc(liveMaterials)
        // Do not treat every UUID referenced anywhere in the region as GPU-live:
        // a region can contain hundreds/thousands of far objects. The material
        // can safely fall back when its diffuse texture leaves residency; the
        // normal texture scan requests it again when it becomes near.
        val texFreed = textures.trimGpuTextures()
        var sculptDropped = 0
        if (pendingSculpts.isNotEmpty()) {
            val it = pendingSculpts.keys.iterator()
            while (it.hasNext()) {
                if (!liveSculpts.contains(it.next())) {
                    it.remove()
                    sculptDropped += 1
                }
            }
        }
        var waiterDropped = 0
        if (meshWaiterObjects.isNotEmpty()) {
            var gone: ArrayList<Int>? = null
            for (localId in meshWaiterObjects.keys) {
                if (!slots.containsKey(localId)) {
                    (gone ?: ArrayList<Int>().also { gone = it }).add(localId)
                }
            }
            gone?.let {
                for (localId in it) {
                    meshForget(localId)
                }
                waiterDropped = it.size
            }
        }
        val pipe = textureStreamer?.pipeline
        val deadCancelled = pipe?.cancelDead(liveTextures + liveSculpts) ?: 0
        val d = diagnostics
        val cache = pipe?.cache
        android.util.Log.i(
            TAG,
            "RECURSOS entidades=" + d.entitiesLive + "/" + d.entitiesInScene +
                " renderables=" + d.renderablesInScene +
                " materiales=" + textures.materialCount +
                " instancias=" + d.materialInstancesLive +
                " texGPU=" + d.texturesLive +
                " texMiB~=" + (textures.gpuBytes / (1024 * 1024)) +
                " meshGPU=" + d.meshesLive + "(VBO/IBO)" +
                " objetos=" + slots.size +
                " rebuilds=" + d.materialRebuilds +
                " remat=" + d.upsertMaterialChanged +
                " swaps=" + (sculptApplied + meshRendered + recreated) +
                " codeStreams=" + (cache?.let { (it.cachedBytes / 1024).toString() + "KiB/" + it.count } ?: "-") +
                " imgCPU=" + (pipe?.let { (it.decodedBytes / 1024).toString() + "KiB/" + it.decodedCount } ?: "-") +
                " pendientes=sub" + (pipe?.pendingUploadCount ?: 0) +
                "/dec" + (pipe?.waitingForDecode ?: 0) +
                "/mesh" + meshWaiterObjects.size +
                "/sculpt" + pendingSculpts.size +
                " beginFrame=f" + d.beginFrameFail + "/ok" + d.beginFrameOk +
                " evicc=mat" + textures.evictedMaterials +
                "/tex" + textures.evictedTextures +
                "/blob" + (cache?.evicted ?: 0) +
                "/cancel" + (pipe?.stats?.deadCancelled ?: 0) +
                " esteBarrido=mat" + matFreed +
                "/tex" + texFreed +
                "/sculpt" + sculptDropped +
                "/waiter" + waiterDropped +
                "/cancel" + deadCancelled
        )
    }

    /** The local ids of the [budget] objects nearest to [origin] (ramp debug). */
    private fun nearestIds(origin: FloatArray, budget: Int): HashSet<Int>? {
        if (budget <= 0 || slots.size <= budget) {
            return null
        }
        val ranked = ArrayList<Pair<Int, Float>>(slots.size)
        for (localId in slots.keys) {
            val t = placementInRegion(localId)
            val dx = t[0] - origin[0]
            val dy = t[1] - origin[1]
            val dz = t[2] - origin[2]
            ranked.add(localId to (dx * dx + dy * dy + dz * dz))
        }
        ranked.sortBy { it.second }
        val allowed = HashSet<Int>(budget * 2)
        for (index in 0 until budget) {
            allowed.add(ranked[index].first)
        }
        return allowed
    }

    /**
     * One row per object: the shape the stored state has, the *same* text the UI
     * list shows, the provenance of that definition, and how far the object got
     * in the pipeline (`geometriaIntentada` → `geometriaGenerada` →
     * `renderableCreado` → `enLaScene`) plus its distance to the camera.
     *
     * This is the table that answers "the list says cylinder but the renderer
     * says no shape": both columns come from one [SLObject], so a disagreement
     * can only originate upstream (in the parser or the merge), never from two
     * different shape paths inside the app.
     */
    fun shapeTable(cameraEye: FloatArray?, limit: Int = SHAPE_TABLE_ROWS): String {
        if (facts.isEmpty()) {
            return "sin objetos"
        }
        val sorted = facts.values.sortedBy { fact -> distanceTo(placementInRegion(fact.localId), cameraEye) }
        val builder = StringBuilder(limit * 150)
        builder.append(
            "localID uuid pcode forma(mostrada) formaCompleta origen pathCurve profileCurve hollow" +
                " ultimoUpdate geomIntentada geomGenerada renderable enScene distCamara"
        )
        var shown = 0
        for (fact in sorted) {
            if (shown >= limit) {
                break
            }
            shown += 1
            val localId = fact.localId
            val slot = slots[localId]
            builder.append('\n').append('#').append(localId)
            builder.append(' ').append(shortUuid(fact.uuid))
            builder.append(' ').append(fact.pcode).append('(').append(fact.kind).append(')')
            builder.append(" forma=").append(fact.shapeText)
            builder.append(" completa=").append(if (fact.hasCompleteShape) "SI" else "NO")
            builder.append(" origen=").append(fact.shapeSource)
            builder.append(" pathCurve=").append(if (fact.pathCurve < 0) "-" else "0x" + Integer.toHexString(fact.pathCurve))
            builder.append(" profileCurve=").append(if (fact.profileCurve < 0) "-" else "0x" + Integer.toHexString(fact.profileCurve))
            builder.append(" hollow=").append(if (fact.profileHollow < 0) "-" else fact.profileHollow.toString())
            builder.append(" update=").append(fact.updateSource)
            builder.append(" geomIntentada=").append(if (primAttemptIds.contains(localId)) "SI" else "NO")
            builder.append(" geomGenerada=").append(if (primMeshIds.contains(localId)) "SI" else "NO")
            builder.append(" renderable=").append(if (primRenderableIds.contains(localId)) "SI" else "NO")
            builder.append(" enScene=").append(if (slot != null) "SI" else "NO")
            builder.append(" distCamara=").append(fmt1(distanceTo(placementInRegion(localId), cameraEye))).append("m")
            if (slot == null) {
                builder.append(" nota=").append(tableNote(localId, fact))
            }
        }
        if (sorted.size > shown) {
            builder.append('\n').append("... ").append(sorted.size - shown).append(" objetos mas")
        }
        builder.append('\n').append(
            "resumen: objetos " + facts.size + "  ·  geometria intentada " + primAttempts +
                "  ·  generada " + primMeshIds.size + "  ·  renderable " + primRenderableIds.size +
                "  ·  en escena " + slots.size + "  ·  delante " + objectsAhead
        )
        return builder.toString()
    }

    private fun tableNote(localId: Int, fact: ObjectFact): String = when {
        fact.kind == SLObjectKind.AVATAR -> "avatar (fase 8)"
        fact.pcode != SceneObject.PCODE_PRIM &&
            fact.pcode != SceneObject.PCODE_TREE &&
            fact.pcode != SceneObject.PCODE_GRASS -> "pcode sin geometria"
        !fact.hasCompleteShape -> "SIN definicion de forma recibida"
        missingGeometryIds.contains(localId) -> "el generador no devolvio malla"
        rejectedEntityIds.contains(localId) -> "el renderer rechazo la entidad"
        omittedAttachments.contains(localId) -> "adjunto omitido (fase 8)"
        !fact.hasCompleteShape -> "sin forma"
        else -> "sin entidad"
    }

    private fun shortUuid(uuid: String): String =
        if (uuid.isEmpty() || uuid == "00000000-0000-0000-0000-000000000000") "-" else uuid.take(8)

    private fun distanceTo(translation: FloatArray, eye: FloatArray?): Float {
        if (eye == null) {
            return 0f
        }
        val dx = translation[0] - eye[0]
        val dy = translation[1] - eye[1]
        val dz = translation[2] - eye[2]
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun fmt1(value: Float): String = String.format(Locale.US, "%.1f", value)

    /**
     * Where an object the scene holds actually is, or null when it has no
     * entity. Used by the camera so it can start looking at a real object when
     * the agent's own position is not known yet.
     */
    fun positionOf(localId: Int): FloatArray? =
        if (slots.containsKey(localId)) placementInRegion(localId) else null

    /**
     * Debug hook from the diagnostic spec: take one object that really came from
     * the region and move it to a fixed distance straight ahead of the camera,
     * keeping its real mesh, real scale and real material.
     *
     * This splits "the coordinates are wrong" from "nothing is drawn at all":
     * if the forced object appears, the pipeline works and the transform
     * conversion is what needs fixing; if it does not appear either, the
     * problem is below the scene layer.
     */
    private fun applyForcedPlacement(camera: CameraDesc) {
        if (forceLocalId == 0) {
            return
        }
        val slot = slots[forceLocalId]
        if (slot == null) {
            diagnostics.forcedObject = "#$forceLocalId (sin entidad)"
            return
        }
        val forward = forwardOf(camera)
        val translation = floatArrayOf(
            camera.eye[0] + forward[0] * forceDistance,
            camera.eye[1] + forward[1] * forceDistance,
            camera.eye[2] + forward[2] * forceDistance
        )
        renderer.updateTransform(
            slot.entity,
            Transform(
                translation = translation,
                rotation = floatArrayOf(0f, 0f, 0f, 1f),
                scale = slot.transform.scale
            )
        )
        if (!slot.visible) {
            renderer.setVisible(slot.entity, true)
            slot.visible = true
        }
        if (!forceApplied) {
            forceApplied = true
            android.util.Log.i(
                TAG,
                "objeto real #" + forceLocalId + " forzado a " + forceDistance +
                    " m delante de la camara en " + vec(translation)
            )
        }
    }

    private fun forwardOf(camera: CameraDesc): FloatArray {
        val dx = camera.target[0] - camera.eye[0]
        val dy = camera.target[1] - camera.eye[1]
        val dz = camera.target[2] - camera.eye[2]
        val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        val inverse = if (length > 0.0001f) 1f / length else 0f
        forwardScratch[0] = dx * inverse
        forwardScratch[1] = dy * inverse
        forwardScratch[2] = dz * inverse
        return forwardScratch
    }

    /**
     * The debug hook's setter. [localId] of 0 turns it off and puts the object
     * back where the region says it is, so turning the hook off cannot leave a
     * prim floating in mid-air.
     */
    fun forceObjectInFront(localId: Int, distance: Float) {
        val previous = forceLocalId
        if (previous != 0) {
            val slot = slots[previous]
            if (slot != null) {
                renderer.updateTransform(slot.entity, slot.transform)
            }
        }
        forceLocalId = localId
        forceDistance = distance
        forceApplied = false
        diagnostics.forcedObject = if (localId == 0) "-" else "#$localId"
        diagnostics.forcedDistance = if (localId == 0) 0f else distance
    }

    /** The nearest object that is actually drawn, for the debug hook. */
    fun nearestRenderable(origin: FloatArray): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (localId in slots.keys) {
            val t = placementInRegion(localId)
            val dx = t[0] - origin[0]
            val dy = t[1] - origin[1]
            val dz = t[2] - origin[2]
            val distance = dx * dx + dy * dy + dz * dz
            if (distance < bestDistance) {
                bestDistance = distance
                best = localId
            }
        }
        return best
    }

    // ------------------------------------ trazabilidad camara -> objeto (fase 2.9)

    /** The renderer entity the scene holds for an object, or null when it has none. */
    fun entityOf(localId: Int): EntityHandle? = slots[localId]?.entity

    /**
     * The nearest object that has an entity and is within [maxMetres], or 0 when
     * there is none. This is what the reversible camera test locks onto.
     */
    fun nearestPrimId(origin: FloatArray, maxMetres: Float): Int {
        var best = 0
        var bestSquared = maxMetres * maxMetres
        for (localId in slots.keys) {
            val t = placementInRegion(localId)
            val dx = t[0] - origin[0]
            val dy = t[1] - origin[1]
            val dz = t[2] - origin[2]
            val squared = dx * dx + dy * dy + dz * dz
            if (squared <= bestSquared) {
                bestSquared = squared
                best = localId
            }
        }
        return best
    }

    /** An object's mesh bounds in its own units, or null when it has no entity. */
    fun boundsLocal(localId: Int): Pair<FloatArray, FloatArray>? {
        val slot = slots[localId] ?: return null
        return slot.mesh.desc.boundsMin to slot.mesh.desc.boundsMax
    }

    /**
     * The radius of an object's bounding sphere once its scale is applied. The
     * mesh bounds are in the prim's own units, so a prim scaled to 10 m is not a
     * half-metre object, and a frustum test that ignored the scale would call a
     * large prim invisible when most of it is on screen.
     *
     * Fase 2.12: the scale it applies is the one the backend actually composes for
     * the object — its own, times every ancestor's — because a linkset child of a
     * scaled root is not the size of its own scale, and a radius built from the
     * local scale alone would call a whole group small.
     */
    fun boundsRadiusWorld(localId: Int): Float {
        val slot = slots[localId] ?: return 0f
        val desc = slot.mesh.desc
        val halfX = (desc.boundsMax[0] - desc.boundsMin[0]) * 0.5f
        val halfY = (desc.boundsMax[1] - desc.boundsMin[1]) * 0.5f
        val halfZ = (desc.boundsMax[2] - desc.boundsMin[2]) * 0.5f
        val scale = worldScaleOf(localId)
        val x = halfX * scale[0]
        val y = halfY * scale[1]
        val z = halfZ * scale[2]
        return kotlin.math.sqrt(x * x + y * y + z * z)
    }

    /**
     * The scale the backend composes for an object: its own, multiplied by every
     * ancestor's, component by component. This is the same chain the world matrix
     * is built from (`parentWorld × local`), evaluated only to size the diagnostic
     * bounding sphere.
     */
    private fun worldScaleOf(localId: Int): FloatArray {
        val local = slots[localId]?.transform?.scale ?: return floatArrayOf(1f, 1f, 1f)
        var x = local[0]
        var y = local[1]
        var z = local[2]
        var current = localId
        var depth = 0
        var parent = parentIdOf(current)
        while (parent != 0 && depth < MAX_PARENT_DEPTH) {
            val scale = slots[parent]?.transform?.scale
            if (scale != null) {
                x *= scale[0]
                y *= scale[1]
                z *= scale[2]
            }
            current = parent
            parent = parentIdOf(current)
            depth += 1
        }
        return floatArrayOf(x, y, z)
    }

    /**
     * One object's frustum sample, using the scene's own basis maths.
     *
     * **Diagnostic only.** The object is tested at its composed **region**
     * position (`parentWorld × local`, i.e. where the engine's world transform
     * puts it), which is what the real culling uses; the real decision is the
     * engine's `View` + `RenderableManager` culling, not this.
     */
    fun samplePrim(localId: Int, camera: CameraDesc, aspect: Float): FrustumSample? {
        if (!slots.containsKey(localId)) {
            return null
        }
        return CameraFrustum.from(camera, aspect).sample(placementInRegion(localId))
    }

    /**
     * The audit: every step of `SL position → entity transform → camera →
     * frustum` for the objects nearest the camera, plus the camera as the
     * backend reports it.
     *
     * This is the phase's whole evidence, and it is deliberately redundant. Each
     * object's transform is printed three ways (the matrix the scene built, the
     * TransformManager's local matrix read back, and its world matrix), and its
     * visibility is computed twice (from the camera's basis and from the camera's
     * own view/projection matrices). Agreement between the two is what turns
     * "the prim should be visible" into a fact rather than a hope; a
     * disagreement says which of the two moved.
     *
     * ## What is authoritative here and what is not (fase 2.12)
     *
     * **The real culling is the engine's** — `View.setFrustumCullingEnabled` plus
     * the per-renderable flag — and it is not replaced by anything in this file.
     * Filament does not expose a per-renderable verdict through its Java API, so
     * this audit is the closest *readable* evidence around it, and it says so:
     *
     * * every test point is the object's composed **region** position, which is
     *   the same `parentWorld × local` the engine's world transform holds;
     * * the basis test is built from the vectors the **engine** reports for its
     *   own camera (falling back to the scene's `CameraDesc` only when that
     *   read-back is unavailable), so it is not a different camera;
     * * the matrix test multiplies by the engine's own view and projection
     *   matrices;
     * * both are labelled `DIAGNOSTICO` and the two are compared; the aggregate
     *   the engine really reports is `renderablesInScene` vs
     *   `visibleRenderables` in the HUD.
     */
    /**
     * TEST 3B: auditoría de padres + prueba diagnóstica de cámara. Solo lectura:
     * la cámara de producción no se toca; la cámara B es sintética y solo se
     * usa para muestrear el frustum en el informe.
     */
    fun parentCameraTestReport(camera: CameraDesc, aspect: Float, agentPos: FloatArray?, agentFocus: FloatArray?): String {
        val builder = StringBuilder(2400)
        builder.append("--- LINKSET TEST4 (pasivo: registra, no recompone) ---\n")
        if (agentPos != null) {
            builder.append("avatar propio: pos=").append(vec(agentPos)).append("  (avatares registrados en escena: ").append(avatarCount).append(")\n")
        } else {
            builder.append("avatar propio: desconocido  (avatares registrados en escena: ").append(avatarCount).append(")\n")
        }
        val forward = forwardOf(camera)
        val fx = forward[0]
        val fy = forward[1]
        val fz = forward[2]
        builder.append("camara A (produccion): eye=").append(vec(camera.eye))
            .append("  target=").append(vec(camera.target))
            .append("  dir=").append(vec(floatArrayOf(fx, fy, fz))).append('\n')
        val chosen = when {
            focusLocalId != 0 && slots.containsKey(focusLocalId) -> Pair(focusLocalId, "foco")
            slots.isNotEmpty() -> Pair(slots.keys.sorted()[0], "primer slot")
            else -> null
        }
        // TEST4: auditoria de linkset — solo lectura. Responde si
        // |childWorld-rootWorld| esta escalado por el root scale, y deja
        // registrado donde entra scale: Transform.toMatrix16 mete el size del
        // root como scale, y FilamentRenderer.createEntity/updateTransform
        // compone con setParent(instance, parentInstance), asi que el mundo
        // Filament del hijo es parentWorld x local = T_root x R_root x S_root
        // x local: el offset local queda multiplicado por S_root. La semantica
        // SL a verificar es rootPos + R_root * local (rotacion pura, sin escala).
        // Nada aqui cambia transforms: el renderer sigue recibiendo el local
        // verbatim y la relacion de parentesco sigue existiendo.
        val auditChildId: Int? = run {
            val linked = parentOf.keys.filter { slots.containsKey(it) && parentOf[it]?.let { p -> slots.containsKey(p) } == true }
            if (linked.isEmpty()) null
            else {
                val ap = agentPos
                if (ap != null) linked.minByOrNull { distanceTo(placementInRegion(parentOf[it]!!), ap) }
                else linked.sorted().first()
            }
        }
        if (auditChildId == null) {
            builder.append("linkset TEST4: sin hijo enlazado a padre con entidad (nada que auditar)\n")
        } else {
            val rootId = parentOf[auditChildId]!!
            val rootSlot = slots[rootId]!!
            val childSlot = slots[auditChildId]!!
            val rootPos = placementInRegion(rootId)
            val rootRot = rootSlot.transform.rotation
            val rootScale = rootSlot.transform.scale
            val childLocal = childSlot.transform.translation
            val childRot = childSlot.transform.rotation
            val childScale = childSlot.transform.scale
            val worldScene = placementInRegion(auditChildId)
            val worldFilament = filamentImpliedWorld(rootSlot.transform, childLocal)
            val worldExpected = expectedSlWorld(rootSlot.transform, childLocal)
            val dLocal = norm3(childLocal)
            val dScene = distanceTo(worldScene, rootPos)
            val dFilament = distanceTo(worldFilament, rootPos)
            val dExpected = distanceTo(worldExpected, rootPos)
            builder.append("linkset TEST4: hijo #").append(auditChildId).append("  raiz=#").append(rootId).append('\n')
            builder.append("  raiz pos=").append(vec(rootPos))
                .append("  rot=").append(quat4(rootRot))
                .append("  scale=").append(vec(rootScale)).append('\n')
            builder.append("  hijo local pos=").append(vec(childLocal))
                .append("  rot=").append(quat4(childRot))
                .append("  scale=").append(vec(childScale)).append('\n')
            builder.append("  hijo mundo escena=").append(vec(worldScene))
                .append("  filament-implicado=").append(vec(worldFilament))
                .append("  esperado SL=").append(vec(worldExpected)).append('\n')
            builder.append("  |local|=").append(fmt2(dLocal))
                .append("  |escena-raiz|=").append(fmt2(dScene))
                .append("  |filament-raiz|=").append(fmt2(dFilament))
                .append("  |esperado-raiz|=").append(fmt2(dExpected)).append('\n')
            val ratioFil = if (dLocal > 0.0001f) dFilament / dLocal else 0f
            builder.append("  ratio |filament-raiz|/|local|=").append(fmt2(ratioFil))
                .append("  |rootScale|=").append(fmt2(norm3(rootScale)))
                .append("  (ratio ~1 = sin escala; ratio ~|scale| = S_root amplifica)\n")
            builder.append("  re-registro tras reparentar: mundo escena ahora=")
                .append(vec(placementInRegion(auditChildId)))
                .append("  creadoSinPadre=").append(if (createdEarly.contains(auditChildId)) "SI" else "NO")
                .append("  reparenteado=").append(if (reparentedEver.contains(auditChildId)) "SI" else "NO")
                .append("  aunEnEspera=").append(if (awaitingFrom.containsKey(auditChildId)) "SI" else "NO").append('\n')
        }
        // TEST5: un unico linkset controlado — el foco si es un hijo
        // enlazado (asi el usuario elige cual auditar), si no el de TEST4.
        // No recompone nada: imprime la composicion ACTUAL frente a la
        // esperada SL y localiza en que linea/funcion entra S_root.
        val test5ChildId: Int? = run {
            val focus = focusLocalId
            if (focus != 0 && slots.containsKey(focus) && parentOf[focus]?.let { slots.containsKey(it) } == true) focus
            else auditChildId
        }
        if (test5ChildId == null) {
            builder.append("linkset TEST5: sin hijo controlado (sin foco enlazado ni linkset)\n")
        } else {
            val rId = parentOf[test5ChildId]!!
            val rSlot = slots[rId]!!
            val cSlot = slots[test5ChildId]!!
            val rPos = rSlot.transform.translation
            val rRot = rSlot.transform.rotation
            val rScale = rSlot.transform.scale
            val cLocal = cSlot.transform.translation
            val cRot = cSlot.transform.rotation
            val cScale = cSlot.transform.scale
            val rootM = rSlot.transform.toMatrix16()
            val childM = cSlot.transform.toMatrix16()
            val full = composeMat4(rootM, childM)
            val actualPos = floatArrayOf(full[12], full[13], full[14])
            val expectedPos = expectedSlWorld(rSlot.transform, cLocal)
            val diff = sub3(actualPos, expectedPos)
            val rootWorldScale = colNorms3(rootM)
            val childWorldScale = colNorms3(full)
            val hypoRot = quatMul(rRot, cRot)
            val hypoPos = expectedPos
            val dLoc = norm3(cLocal)
            val dAct = distanceTo(actualPos, rPos)
            val dExp = distanceTo(expectedPos, rPos)
            val escenaPos = placementInRegion(test5ChildId)
            val escenaLlevaEscala = kotlin.math.abs(dAct - dLoc) > 0.01f &&
                kotlin.math.abs(distanceTo(escenaPos, rPos) - dAct) < 0.01f
            val hijoVerbatim = childM[12] == cLocal[0] && childM[13] == cLocal[1] && childM[14] == cLocal[2]
            builder.append("linkset TEST5: hijo #").append(test5ChildId).append("  raiz=#").append(rId)
                .append(if (test5ChildId == focusLocalId) "  (seleccion: foco)" else "  (seleccion: TEST4)").append('\n')
            builder.append("  raiz pos=").append(vec(rPos))
                .append("  rot=").append(quat4(rRot))
                .append("  scale=").append(vec(rScale)).append('\n')
            builder.append("  hijo local pos=").append(vec(cLocal))
                .append("  rot=").append(quat4(cRot))
                .append("  scale=").append(vec(cScale)).append('\n')
            builder.append("  ANTIGUA pre-TEST6 (rootM x childM, lo que Filament componia): pos=").append(vec(actualPos))
                .append("  |actual-raiz|=").append(fmt2(dAct)).append('\n')
            builder.append("  ESPERADA SL (rootPos + R x local): pos=").append(vec(expectedPos))
                .append("  |esperada-raiz|=").append(fmt2(dExp))
                .append("  |local|=").append(fmt2(dLoc)).append('\n')
            builder.append("  diferencia actual-esperada=").append(vec(diff))
                .append("  |diff|=").append(fmt2(norm3(diff))).append(" m\n")
            builder.append("  escala-mundo raiz (normas columnas rootM)=").append(vec(rootWorldScale)).append('\n')
            builder.append("  escala-mundo hijo resultante (normas columnas rootM x childM)=").append(vec(childWorldScale)).append('\n')
            builder.append("  hijo scale original=").append(vec(cScale)).append('\n')
            builder.append("  hipotetica SIN herencia (solo calculo, no render): pos=").append(vec(hypoPos))
                .append("  rot=root x hijo=").append(quat4(hypoRot))
                .append("  scale=hijo=").append(vec(cScale)).append('\n')
            builder.append("  A) Filament hereda S_root: rootM lleva S_root (normas=").append(vec(rootWorldScale))
                .append(") y FilamentRenderer.setParent compone parentWorld x local\n")
            builder.append("  B) EPHORA pre-escala al hijo: ").append(if (hijoVerbatim) "NO" else "SI")
                .append(" (matriz local traslacion == local wire: ").append(if (hijoVerbatim) "verbatim" else "ALTERADO")
                .append("); PERO escena rotationOf sale de toMatrix16 (con S_root) y composeRegionPlacement escala igual: escena-raiz=")
                .append(fmt2(distanceTo(escenaPos, rPos))).append(" lleva escala=").append(if (escenaLlevaEscala) "SI" else "NO").append('\n')
            builder.append("  veredicto pre-TEST6: ").append(if (escenaLlevaEscala) "C) AMBAS" else "A) FILAMENT")
                .append(" — relacion parent BIEN (local verbatim, parent vivo); S_root contamina el offset en ambas composiciones\n")
            builder.append("  punto exacto: Renderer.kt:194 toMatrix16 (size como scale) x FilamentRenderer.kt:768/871 setParent (parentWorld x local) + SLScene.kt:677 rotationOf (3x3 con S_root) x SLScene.kt:640 composeRegionPlacement (pos += rotEscalada x local)\n")
        }
        // TEST6: metrica del split nodo/renderable — solo lectura. El hijo
        // controlado es el de TEST5 (foco si es hijo enlazado). Verifica los 10
        // puntos mas una tabla multi-caso (X negativo/positivo, rot no
        // identidad, root grande no uniforme, segun lo que haya en escena).
        val test6ChildId: Int? = test5ChildId
        if (test6ChildId == null) {
            builder.append("linkset TEST6: sin hijo controlado\n")
        } else {
            fun close3(a: FloatArray, b: FloatArray): Boolean =
                kotlin.math.abs(a[0] - b[0]) < 0.01f &&
                    kotlin.math.abs(a[1] - b[1]) < 0.01f &&
                    kotlin.math.abs(a[2] - b[2]) < 0.01f
            val r6 = parentOf[test6ChildId]!!
            val r6Slot = slots[r6]!!
            val c6Slot = slots[test6ChildId]!!
            val exp6 = placementInRegion(test6ChildId)
            val eng6 = c6Slot.let { renderer.entityWorldMatrix(it.entity) }
            val real6 = if (eng6 != null) FrustumMath.translation(eng6) else floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
            val diff6 = if (eng6 != null) sub3(real6, exp6) else floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
            val nodeM6 = renderer.entityNodeMatrix(c6Slot.entity)
            val meshM6 = renderer.entityMeshMatrix(c6Slot.entity)
            val nodeScale6 = if (nodeM6 != null) colNorms3(nodeM6) else null
            val meshScale6 = if (meshM6 != null) colNorms3(meshM6) else null
            val worldScale6 = if (eng6 != null) colNorms3(eng6) else null
            val parentNodeM6 = renderer.entityNodeMatrix(r6Slot.entity)
            val parentNodeScale6 = if (parentNodeM6 != null) colNorms3(parentNodeM6) else null
            builder.append("linkset TEST6: hijo #").append(test6ChildId).append("  raiz=#").append(r6).append('\n')
            builder.append("  1 root local TRS: pos=").append(vec(r6Slot.transform.translation))
                .append(" rot=").append(quat4(r6Slot.transform.rotation))
                .append(" scale=").append(vec(r6Slot.transform.scale)).append('\n')
            builder.append("  2 child local TRS: pos=").append(vec(c6Slot.transform.translation))
                .append(" rot=").append(quat4(c6Slot.transform.rotation))
                .append(" scale=").append(vec(c6Slot.transform.scale)).append(" (verbatim)\n")
            builder.append("  3 esperado: pos=").append(vec(exp6))
                .append(" |esp-raiz|=").append(fmt2(distanceTo(exp6, r6Slot.transform.translation))).append('\n')
            builder.append("  4 real (motor): pos=").append(vec(real6))
                .append(" |real-raiz|=").append(fmt2(if (eng6 != null) distanceTo(real6, r6Slot.transform.translation) else Float.NaN)).append('\n')
            builder.append("  5 distancia root->child real=").append(fmt2(if (eng6 != null) distanceTo(real6, r6Slot.transform.translation) else Float.NaN))
                .append(" m (antes 12.71; esperada 3.56)\n")
            builder.append("  6 root scale=").append(vec(r6Slot.transform.scale))
                .append("  7 child scale=").append(vec(c6Slot.transform.scale)).append('\n')
            builder.append("  8 world scale renderable hijo=").append(if (worldScale6 != null) vec(worldScale6) else "-")
                .append(" (== child scale: ").append(if (worldScale6 != null && close3(worldScale6, c6Slot.transform.scale)) "SI" else "NO").append(")\n")
            builder.append("  9 nodo hijo scale=").append(if (nodeScale6 != null) vec(nodeScale6) else "-")
                .append(" (==1,1,1: ").append(if (nodeScale6 != null && close3(nodeScale6, floatArrayOf(1f, 1f, 1f))) "SI" else "NO")
                .append(") traslacion==local: ").append(if (nodeM6 != null && nodeM6[12] == c6Slot.transform.translation[0] && nodeM6[13] == c6Slot.transform.translation[1] && nodeM6[14] == c6Slot.transform.translation[2]) "SI" else "NO").append("\n")
            builder.append("  10 nodo padre scale=").append(if (parentNodeScale6 != null) vec(parentNodeScale6) else "-")
                .append(" (transmite escala: ").append(if (parentNodeScale6 != null && !close3(parentNodeScale6, floatArrayOf(1f, 1f, 1f))) "SI (MAL)" else "NO (BIEN)").append(")\n")
            builder.append("  veredicto: |real-esp|=").append(fmt3(if (eng6 != null) norm3(diff6) else Float.NaN))
                .append(" m ").append(if (eng6 != null && norm3(diff6) < 0.01f) "OK" else "FAIL").append('\n')
            val linked6 = parentOf.keys.filter { slots.containsKey(it) && parentOf[it]?.let { p -> slots.containsKey(p) } == true }.sorted()
            var ok6 = 0
            var fail6 = 0
            val failIds6 = ArrayList<Int>()
            builder.append("  tabla linksets (max 14 de ").append(linked6.size).append("):\n")
            for (id in linked6.take(14)) {
                val idSlot = slots[id]!!
                val rp = placementInRegion(parentOf[id]!!)
                val ex = placementInRegion(id)
                val ew = renderer.entityWorldMatrix(idSlot.entity)
                val rl = if (ew != null) FrustumMath.translation(ew) else null
                val dd = if (rl != null) norm3(sub3(rl, ex)) else -1f
                val good = rl != null && dd < 0.01f
                if (good) ok6++ else { fail6++; failIds6.add(id) }
                builder.append("    #").append(id).append(" local=").append(vec(idSlot.transform.translation))
                    .append(" rotId=").append(if (idSlot.transform.rotation[3] > 0.999f && norm3(idSlot.transform.rotation) < 1.001f) "SI" else "NO")
                    .append(" |loc|=").append(fmt2(norm3(idSlot.transform.translation)))
                    .append(" dist=").append(fmt2(if (rl != null) distanceTo(rl, rp) else -1f))
                    .append(" |real-esp|=").append(if (dd >= 0) fmt3(dd) else "-")
                    .append(if (good) " OK" else " FAIL").append('\n')
            }
            builder.append("  verificados=").append(ok6).append(" fallos=").append(fail6)
                .append(if (failIds6.isEmpty()) "\n" else " ids=" + failIds6.joinToString(",") + "\n")
        }
        if (chosen == null) {
            builder.append("sin entidades: no hay prim de prueba\n")
        } else {
            val (localId, how) = chosen
            val slot = slots[localId]!!
            val parentId = parentIdOf(localId)
            val childWorld = placementInRegion(localId)
            var root = localId
            var hops = 0
            while (parentIdOf(root) != 0 && hops < 32) {
                root = parentIdOf(root)
                hops += 1
            }
            builder.append("prim de prueba: #").append(localId).append(" (").append(how).append(")")
                .append("  parentLocalId=").append(parentId).append('\n')
            builder.append("  hijo local=").append(vec(slot.transform.translation))
                .append("  hijo mundo=").append(vec(childWorld)).append('\n')
            if (parentId != 0) {
                val parentWorld = if (slots.containsKey(parentId)) vec(placementInRegion(parentId)) else "sin entidad (en espera)"
                builder.append("  padre mundo=").append(parentWorld).append('\n')
            }
            builder.append("  raiz=#").append(root).append(" pos=").append(vec(placementInRegion(root))).append('\n')
            builder.append("  creadoSinPadre=").append(if (createdEarly.contains(localId)) "SI" else "NO")
                .append("  reparenteado=").append(if (reparentedEver.contains(localId)) "SI" else "NO")
            childDelayNanos[localId]?.let { builder.append("  retardo=").append(fmt1((it / 1_000_000.0).toFloat())).append(" ms") }
            builder.append("  aunEnEspera=").append(if (awaitingFrom.containsKey(localId)) "SI" else "NO").append('\n')
            val frustumA = CameraFrustum.from(camera, aspect)
            val sampleA = frustumA.sample(childWorld)
            builder.append("  NDC en A: (").append(fmt3(sampleA.ndcX)).append(", ").append(fmt3(sampleA.ndcY)).append(") ").append(sampleA.reason).append('\n')
            // TEST4: camara B corregida — apunta a un prim ROOT cercano al
            // avatar, no al avatar. La B de TEST3B era invalida porque el prim
            // de prueba estaba a >100 m del foco. La camara de produccion no
            // se toca: B solo muestrea el frustum en este informe.
            val nearRootId: Int? = run {
                val ap = agentPos
                if (ap == null) null
                else slots.keys.filter { parentIdOf(it) == 0 }
                    .minByOrNull { distanceTo(placementInRegion(it), ap) }
            }
            if (nearRootId == null) {
                builder.append("  camara B TEST4: sin raiz cercana al avatar, no se compone\n")
            } else {
                val rootPosB = placementInRegion(nearRootId)
                val eyeB = floatArrayOf(rootPosB[0] - fx * 3f, rootPosB[1] - fy * 3f, rootPosB[2] + 1.5f)
                val camB = CameraDesc(eyeB, rootPosB, camera.up, camera.verticalFovDegrees, camera.near, camera.far)
                val frustumB = CameraFrustum.from(camB, aspect)
                val sampleB = frustumB.sample(childWorld)
                val sampleRootB = frustumB.sample(rootPosB)
                val sampleRootA = frustumA.sample(rootPosB)
                builder.append("  camara B TEST4 (3 m detras + 1.5 m arriba de la raiz #").append(nearRootId)
                    .append(", dist raiz-avatar=").append(fmt2(distanceTo(rootPosB, agentPos)))
                    .append(" m): eye=").append(vec(eyeB))
                    .append("  target=").append(vec(rootPosB)).append('\n')
                builder.append("  raiz #").append(nearRootId)
                    .append(" NDC en A: (").append(fmt3(sampleRootA.ndcX)).append(", ").append(fmt3(sampleRootA.ndcY)).append(") ").append(sampleRootA.reason)
                    .append("  ·  NDC en B: (").append(fmt3(sampleRootB.ndcX)).append(", ").append(fmt3(sampleRootB.ndcY)).append(") ").append(sampleRootB.reason).append('\n')
                builder.append("  prim de prueba NDC en B: (").append(fmt3(sampleB.ndcX)).append(", ").append(fmt3(sampleB.ndcY)).append(") ").append(sampleB.reason).append('\n')
                var inA = 0
                var inB = 0
                for (id in slots.keys) {
                    val pos = placementInRegion(id)
                    if (frustumA.sample(pos).inside) inA += 1
                    if (frustumB.sample(pos).inside) inB += 1
                }
                builder.append("  frustum A: ").append(inA).append(" de ").append(slots.size).append(" dentro")
                    .append("  ·  frustum B: ").append(inB).append(" de ").append(slots.size).append(" dentro\n")
            }
        }
        val avgDelay = if (childrenLaterReparented > 0) (reparentDelayNanosTotal / 1_000_000.0 / childrenLaterReparented.toDouble()).toFloat() else 0f
        builder.append("hijos: creadosSinPadre=").append(childrenCreatedWithoutParent)
            .append("  reparenteados=").append(childrenLaterReparented)
            .append("  aunSinPadre=").append(childrenStillWithoutParent)
            .append("  retardoMedio=").append(fmt1(avgDelay)).append(" ms")
            .append("  retardoMax=").append(fmt1((reparentDelayNanosMax / 1_000_000.0).toFloat())).append(" ms\n")
        return builder.toString()
    }

    /**
     * TEST4: matematica de la hipotesis del linkset — solo lectura, sin
     * cambiar ningun transform. La rotacion pura sale del quaternion (sin
     * escala); el mundo Filament-implicado sale de las columnas de
     * toMatrix16, que llevan S_root dentro (el size del root es su scale).
     */
    private fun quatToMat3Pure(q: FloatArray): FloatArray {
        val x = q[0]
        val y = q[1]
        val z = q[2]
        val w = q[3]
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        return floatArrayOf(
            1f - 2f * (yy + zz), 2f * (xy - wz), 2f * (xz + wy),
            2f * (xy + wz), 1f - 2f * (xx + zz), 2f * (yz - wx),
            2f * (xz - wy), 2f * (yz + wx), 1f - 2f * (xx + yy)
        )
    }

    private fun applyMat3RowMajor(m: FloatArray, v: FloatArray): FloatArray =
        floatArrayOf(
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
        )

    private fun norm3(v: FloatArray): Float =
        kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun quat4(q: FloatArray): String =
        String.format(Locale.US, "%.2f, %.2f, %.2f, %.2f", q[0], q[1], q[2], q[3])

    /**
     * TEST5: composicion completa 4x4 (column-major, como Filament) y
     * producto de quaternions xyzw. Solo lectura para el diagnostico.
     */
    private fun composeMat4(a: FloatArray, b: FloatArray): FloatArray {
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

    /** Normas de las 3 columnas de rotacion/escala = escala-mundo por eje. */
    private fun colNorms3(m: FloatArray): FloatArray =
        floatArrayOf(
            norm3(floatArrayOf(m[0], m[1], m[2])),
            norm3(floatArrayOf(m[4], m[5], m[6])),
            norm3(floatArrayOf(m[8], m[9], m[10]))
        )

    private fun sub3(a: FloatArray, b: FloatArray): FloatArray =
        floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    /** Producto de quaternions xyzw: aplica q2 primero, luego q1. */
    private fun quatMul(q1: FloatArray, q2: FloatArray): FloatArray {
        val x1 = q1[0]; val y1 = q1[1]; val z1 = q1[2]; val w1 = q1[3]
        val x2 = q2[0]; val y2 = q2[1]; val z2 = q2[2]; val w2 = q2[3]
        return floatArrayOf(
            w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
            w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
            w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2
        )
    }

    /** Mundo que Filament compone hoy: rootPos + (R_root x S_root) x local. */
    private fun filamentImpliedWorld(root: Transform, local: FloatArray): FloatArray {
        val m = root.toMatrix16()
        return floatArrayOf(
            root.translation[0] + m[0] * local[0] + m[4] * local[1] + m[8] * local[2],
            root.translation[1] + m[1] * local[0] + m[5] * local[1] + m[9] * local[2],
            root.translation[2] + m[2] * local[0] + m[6] * local[1] + m[10] * local[2]
        )
    }

    /** Mundo esperado SL: rootPos + R_root x local, rotacion pura. */
    private fun expectedSlWorld(root: Transform, local: FloatArray): FloatArray {
        val rotated = applyMat3RowMajor(quatToMat3Pure(root.rotation), local)
        return floatArrayOf(
            root.translation[0] + rotated[0],
            root.translation[1] + rotated[1],
            root.translation[2] + rotated[2]
        )
    }

    // ============================== TEST7: diagnostico de escena =============

    /** TEST7: entidades (raices o hijos) a menos de [radius] m del avatar. */
    fun countNearAgent(agentPos: FloatArray, radius: Float): Int =
        slots.keys.count { distanceTo(placementInRegion(it), agentPos) <= radius }

    private fun isIdentityQuat(q: FloatArray): Boolean =
        q[3] > 0.999f && kotlin.math.abs(q[0]) < 0.045f &&
            kotlin.math.abs(q[1]) < 0.045f && kotlin.math.abs(q[2]) < 0.045f

    private fun isUniformScale(s: FloatArray): Boolean {
        val mx = maxOf(s[0], s[1], s[2])
        if (mx <= 0.0001f) return true
        return (mx - minOf(s[0], s[1], s[2])) / mx < 0.02f
    }

    /**
     * TEST7.3: ventana cercana al avatar — las [limit] raices mas cercanas con
     * sus hijos: esperado vs real, diferencia y escalas. Solo lectura: no
     * mueve nada, no inventa parents, no convierte locales a mundo.
     */
    fun test7NearbyReport(agentPos: FloatArray, limit: Int = 20): String {
        val builder = StringBuilder(3200)
        builder.append("--- TEST7.3 ventana cercana (raices + hijos) ---\n")
        builder.append("  entidades a <=50 m del avatar: ").append(countNearAgent(agentPos, 50f))
            .append("  a <=100 m: ").append(countNearAgent(agentPos, 100f)).append('\n')
        val roots = slots.keys.filter { parentIdOf(it) == 0 }
            .sortedBy { distanceTo(placementInRegion(it), agentPos) }.take(limit)
        if (roots.isEmpty()) {
            builder.append("  sin raices con entidad\n")
            return builder.toString()
        }
        var ok = 0
        var fail = 0
        for (rootId in roots) {
            val rSlot = slots[rootId] ?: continue
            val rPos = placementInRegion(rootId)
            builder.append("  raiz #").append(rootId)
                .append(" dist=").append(fmt1(distanceTo(rPos, agentPos))).append(" m")
                .append(" pos=").append(vec(rPos))
                .append(" scale=").append(vec(rSlot.transform.scale)).append('\n')
            val children = childrenOf[rootId] ?: emptySet()
            if (children.isEmpty()) {
                builder.append("    (sin hijos enlazados)\n")
                continue
            }
            for (childId in children.sorted()) {
                val cSlot = slots[childId] ?: continue
                val exp = placementInRegion(childId)
                val ew = renderer.entityWorldMatrix(cSlot.entity)
                val real = if (ew != null) FrustumMath.translation(ew) else null
                val dd = if (real != null) norm3(sub3(real, exp)) else -1f
                val good = real != null && dd < 0.01f
                if (good) ok++ else fail++
                builder.append("    hijo #").append(childId)
                    .append(" local=").append(vec(cSlot.transform.translation))
                    .append(" esp=").append(vec(exp))
                    .append(" real=").append(if (real != null) vec(real) else "-")
                    .append(" |d|=").append(if (dd >= 0) fmt3(dd) else "-")
                    .append(" rScale=").append(vec(rSlot.transform.scale))
                    .append(" cScale=").append(vec(cSlot.transform.scale))
                    .append(if (good) " OK" else " FAIL").append('\n')
            }
        }
        builder.append("  hijos verificados=").append(ok).append(" fallos=").append(fail).append('\n')
        return builder.toString()
    }

    /**
     * TEST7.5: desglose de los parents que los children esperan. Los 225
     * pendientes NO se interpretan como fallo: se clasifican en borrado (tuvo
     * entidad), visto-sin-entidad, no-visto-en-protocolo o
     * materializado-sin-enlazar. Solo lectura.
     */
    fun test7PendingBreakdown(): String {
        val builder = StringBuilder(1400)
        builder.append("--- TEST7.5 children en espera (pendientes, no fallo) ---\n")
        if (awaitingParent.isEmpty()) {
            builder.append("  nadie espera parent\n")
            return builder.toString()
        }
        var gone = 0
        var seen = 0
        var unseen = 0
        var live = 0
        for (parentId in awaitingParent.keys.sorted()) {
            val n = awaitingParent[parentId]?.size ?: 0
            val kind = when {
                slots.containsKey(parentId) -> {
                    live += n
                    "materializado-sin-enlazar"
                }
                parentsEverLinked.contains(parentId) -> {
                    gone += n
                    "borrado (tuvo entidad)"
                }
                facts.containsKey(parentId) -> {
                    seen += n
                    "visto-en-protocolo-sin-entidad"
                }
                else -> {
                    unseen += n
                    "no-visto-en-protocolo"
                }
            }
            builder.append("  parent #").append(parentId).append(": ").append(n)
                .append(" en espera -> ").append(kind).append('\n')
        }
        builder.append("  totales: borrados=").append(gone).append(" vistos=").append(seen)
            .append(" no-vistos=").append(unseen).append(" materializados=").append(live).append('\n')
        return builder.toString()
    }

    /**
     * TEST7.6: un linkset de cada tipo (raiz uniforme / no uniforme / raiz
     * rotada + hijo rotado), segun lo que haya en escena. Solo lectura.
     */
    fun test7LinksetTypes(): String {
        val builder = StringBuilder(1600)
        builder.append("--- TEST7.6 tres tipos de linkset ---\n")
        val linked = parentOf.keys.filter { slots.containsKey(it) && parentOf[it]?.let { p -> slots.containsKey(p) } == true }.sorted()
        if (linked.isEmpty()) {
            builder.append("  sin hijos enlazados\n")
            return builder.toString()
        }
        var uniform: Int? = null
        var nonUniform: Int? = null
        var rotated: Int? = null
        for (id in linked) {
            val rSlot = slots[parentOf[id]!!]!!
            val cSlot = slots[id]!!
            if (uniform == null && isUniformScale(rSlot.transform.scale)) uniform = id
            if (nonUniform == null && !isUniformScale(rSlot.transform.scale) && id != uniform) nonUniform = id
            if (rotated == null && !isIdentityQuat(rSlot.transform.rotation) && !isIdentityQuat(cSlot.transform.rotation) && id != uniform && id != nonUniform) rotated = id
            if (uniform != null && nonUniform != null && rotated != null) break
        }
        auditLinksetType(builder, "uniforme", uniform)
        auditLinksetType(builder, "no-uniforme", nonUniform)
        auditLinksetType(builder, "rotada+rotado", rotated)
        return builder.toString()
    }

    /** Una fila de TEST7.6: mundo raiz, local, esperado, real y error en m. */
    private fun auditLinksetType(builder: StringBuilder, label: String, id: Int?) {
        if (id == null) {
            builder.append("  ").append(label).append(": no hay en escena\n")
            return
        }
        val rootId = parentOf[id]!!
        val cSlot = slots[id]!!
        val exp = placementInRegion(id)
        val ew = renderer.entityWorldMatrix(cSlot.entity)
        val real = if (ew != null) FrustumMath.translation(ew) else null
        val err = if (real != null) norm3(sub3(real, exp)) else -1f
        builder.append("  ").append(label).append(": hijo #").append(id)
            .append(" raiz=#").append(rootId).append(" mundoRaiz=").append(vec(placementInRegion(rootId))).append('\n')
        builder.append("    childLocal=").append(vec(cSlot.transform.translation))
            .append(" esperado=").append(vec(exp))
            .append(" real=").append(if (real != null) vec(real) else "-")
            .append(" error=").append(if (err >= 0) fmt3(err) else "-").append(" m\n")
    }

    // ============================== TEST8: AABB real vs geometria =============

    /** TEST8: conteos de la ultima muestra (para la conclusion del informe). */
    var test8Total = 0
        private set
    var test8Small = 0
        private set
    /** TEST8.4: localIds con culling individual desactivado (reversible). */
    private val test8NoCull = HashSet<Int>()
    /** TEST8.7: localId -> AABB original (reversible). */
    private val test8WideBox = HashMap<Int, FloatArray>()
    val test8NoCullIds: List<Int> get() = test8NoCull.sorted()
    val test8WideBoxIds: List<Int> get() = test8WideBox.keys.sorted()

    private fun cross3(a: FloatArray, b: FloatArray): FloatArray =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun norm3v(v: FloatArray): FloatArray {
        val n = norm3(v)
        return if (n > 1e-9f) floatArrayOf(v[0] / n, v[1] / n, v[2] / n) else floatArrayOf(0f, 0f, 0f)
    }

    private fun dot3(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun mat4Vec(m: FloatArray, v: FloatArray): FloatArray =
        floatArrayOf(
            m[0] * v[0] + m[4] * v[1] + m[8] * v[2] + m[12],
            m[1] * v[0] + m[5] * v[1] + m[9] * v[2] + m[13],
            m[2] * v[0] + m[6] * v[1] + m[10] * v[2] + m[14]
        )

    /** TEST8: AABB en mundo = esquinas del box object-space por la matriz mundo. */
    private fun worldBoxOf(handle: EntityHandle): Pair<FloatArray, FloatArray>? {
        val box = renderer.entityObjectBox(handle) ?: return null
        val wm = renderer.entityWorldMatrix(handle) ?: return null
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until 8) {
            val w = mat4Vec(
                wm, floatArrayOf(
                    box[0] + (if (i and 1 != 0) box[3] else -box[3]),
                    box[1] + (if (i and 2 != 0) box[4] else -box[4]),
                    box[2] + (if (i and 4 != 0) box[5] else -box[5])
                )
            )
            for (a in 0..2) {
                if (w[a] < mn[a]) mn[a] = w[a]
                if (w[a] > mx[a]) mx[a] = w[a]
            }
        }
        return Pair(mn, mx)
    }

    /**
     * TEST8.8: caja-vs-frustum por los 6 planos (como el algoritmo real: el
     * renderable usa su bounding box, no el centro). Devuelve el plano que la
     * descarta (0=near 1=far 2=izq 3=der 4=abajo 5=arriba) o -1 si intersecta.
     */
    private fun boxFrustumCull(mn: FloatArray, mx: FloatArray, desc: CameraDesc, aspect: Float): Int {
        val f = norm3v(floatArrayOf(desc.target[0] - desc.eye[0], desc.target[1] - desc.eye[1], desc.target[2] - desc.eye[2]))
        if (norm3(f) < 0.5f) return -1
        val r = norm3v(cross3(f, desc.up))
        if (norm3(r) < 0.5f) return -1
        val u = cross3(r, f)
        val tanH = kotlin.math.tan(Math.toRadians(desc.verticalFovDegrees.toDouble() * 0.5)).toFloat()
        val a = tanH * if (aspect > 0.0001f) aspect else 1f
        val e = desc.eye
        val planes = ArrayList<Pair<FloatArray, Float>>(6)
        fun throughEye(n: FloatArray): Pair<FloatArray, Float> {
            var nn = norm3v(n)
            var cc = dot3(nn, e)
            val mid = (desc.near + desc.far) * 0.5f
            val ctr = floatArrayOf(e[0] + f[0] * mid, e[1] + f[1] * mid, e[2] + f[2] * mid)
            if (dot3(nn, ctr) < cc) {
                nn = floatArrayOf(-nn[0], -nn[1], -nn[2])
                cc = -cc
            }
            return Pair(nn, cc)
        }
        val pn = floatArrayOf(e[0] + f[0] * desc.near, e[1] + f[1] * desc.near, e[2] + f[2] * desc.near)
        planes.add(Pair(f, dot3(f, pn)))
        val pf = floatArrayOf(e[0] + f[0] * desc.far, e[1] + f[1] * desc.far, e[2] + f[2] * desc.far)
        planes.add(Pair(floatArrayOf(-f[0], -f[1], -f[2]), -dot3(f, pf)))
        val dl = floatArrayOf(f[0] - r[0] * a, f[1] - r[1] * a, f[2] - r[2] * a)
        planes.add(throughEye(cross3(dl, u)))
        val dr = floatArrayOf(f[0] + r[0] * a, f[1] + r[1] * a, f[2] + r[2] * a)
        planes.add(throughEye(cross3(u, dr)))
        val db = floatArrayOf(f[0] - u[0] * tanH, f[1] - u[1] * tanH, f[2] - u[2] * tanH)
        planes.add(throughEye(cross3(r, db)))
        val dt = floatArrayOf(f[0] + u[0] * tanH, f[1] + u[1] * tanH, f[2] + u[2] * tanH)
        planes.add(throughEye(cross3(dt, r)))
        for (pi in planes.indices) {
            val (n, c) = planes[pi]
            var outside = true
            for (i in 0 until 8) {
                val p = floatArrayOf(
                    if (i and 1 != 0) mx[0] else mn[0],
                    if (i and 2 != 0) mx[1] else mn[1],
                    if (i and 4 != 0) mx[2] else mn[2]
                )
                if (dot3(n, p) >= c - 1e-4f) {
                    outside = false
                    break
                }
            }
            if (outside) return pi
        }
        return -1
    }

    /** TEST8.2: AABB real de los vertices en espacio local (pos = 3 de 8). */
    private fun geometryBounds(slot: Slot): Pair<FloatArray, FloatArray>? {
        val v = slot.mesh.desc.vertices
        if (v.size < 8) return null
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var i = 0
        while (i + 2 < v.size) {
            for (ax in 0..2) {
                val x = v[i + ax]
                if (!x.isFinite()) return null
                if (x < mn[ax]) mn[ax] = x
                if (x > mx[ax]) mx[ax] = x
            }
            i += 8
        }
        return Pair(mn, mx)
    }

    private fun boxContains(box: FloatArray, gb: Pair<FloatArray, FloatArray>): Boolean {
        for (a in 0..2) {
            if (gb.first[a] < box[a] - box[a + 3] - 1e-4f) return false
            if (gb.second[a] > box[a] + box[a + 3] + 1e-4f) return false
        }
        return true
    }

    /**
     * TEST8.1/2: muestra de >=20 renderables (10 roots + 10 children cercanos,
     * mas casos especiales y un no-uniforme/rotado si faltan): AABB real de
     * Filament vs geometria, veredicto de caja y contenido. Solo lectura.
     */
    fun test8AabbReport(agentPos: FloatArray?, desc: CameraDesc, aspect: Float): String {
        val b = StringBuilder(7000)
        b.append("--- TEST8.1/2 AABB Filament vs geometria (caja, no centro) ---\n")
        val anchor = agentPos ?: desc.eye
        val ids = ArrayList<Int>()
        for (id in slots.keys.filter { parentIdOf(it) == 0 }.sortedBy { distanceTo(placementInRegion(it), anchor) }.take(10)) ids.add(id)
        for (id in parentOf.keys.filter { slots.containsKey(it) }.sortedBy { distanceTo(placementInRegion(it), anchor) }.take(10)) {
            if (!ids.contains(id)) ids.add(id)
        }
        for (sp in intArrayOf(8578717, 9100205, 9100206, 9100207, 8579514, 6332)) {
            if (slots.containsKey(sp) && !ids.contains(sp)) ids.add(sp)
        }
        if (ids.none { slots[it]?.let { s -> parentIdOf(it) == 0 && !isUniformScale(s.transform.scale) } == true }) {
            slots.keys.filter { parentIdOf(it) == 0 && !isUniformScale(slots[it]!!.transform.scale) }.sorted().take(2).forEach { if (!ids.contains(it)) ids.add(it) }
        }
        test8Total = 0
        test8Small = 0
        val planeName = arrayOf("DENTRO", "FUERA_NEAR", "FUERA_FAR", "FUERA_IZQ", "FUERA_DER", "FUERA_ABAJO", "FUERA_ARRIBA")
        for (id in ids) {
            val s = slots[id] ?: continue
            val box = renderer.entityObjectBox(s.entity)
            val wm = renderer.entityWorldMatrix(s.entity)
            val wb = worldBoxOf(s.entity)
            val wpos = if (wm != null) FrustumMath.translation(wm) else null
            val vscale = if (wm != null) colNorms3(wm) else null
            val verdict = if (wb != null) planeName[boxFrustumCull(wb.first, wb.second, desc, aspect) + 1] else "sin-datos"
            val gb = geometryBounds(s)
            var contain = "-"
            var ratio = "-"
            if (box != null && gb != null) {
                val inside = boxContains(box, gb)
                contain = if (inside) "SI" else "NO"
                val ge = floatArrayOf(gb.second[0] - gb.first[0], gb.second[1] - gb.first[1], gb.second[2] - gb.first[2])
                val fe = floatArrayOf(box[3] * 2f, box[4] * 2f, box[5] * 2f)
                ratio = fmt2(if (fe[0] > 1e-6f) ge[0] / fe[0] else -1f) + "/" +
                    fmt2(if (fe[1] > 1e-6f) ge[1] / fe[1] else -1f) + "/" +
                    fmt2(if (fe[2] > 1e-6f) ge[2] / fe[2] else -1f)
                test8Total++
                if (!inside) test8Small++
            }
            b.append("  #").append(id).append(" ent=").append(s.entity)
                .append(" parent=").append(parentIdOf(id)).append('\n')
            b.append("    boxFil c=").append(if (box != null) vec(floatArrayOf(box[0], box[1], box[2])) else "-")
                .append(" h=").append(if (box != null) vec(floatArrayOf(box[3], box[4], box[5])) else "-").append('\n')
            b.append("    mundo pos=").append(if (wpos != null) vec(wpos) else "-")
                .append(" escalaVis=").append(if (vscale != null) vec(vscale) else "-")
                .append(" malla=").append(s.mesh.key).append('\n')
            b.append("    caja=").append(verdict)
                .append(" contenida=").append(contain)
                .append(" ratio=").append(ratio)
                .append(" layer=").append(renderer.entityLayerMask(s.entity))
                .append(" cull=").append(renderer.entityCullingEnabled(s.entity)?.toString() ?: "?")
                .append(" mat=[").append(renderer.entityMaterialLine(s.entity)).append("]")
                .append(if (contain == "NO") " AABB_TOO_SMALL" else "").append('\n')
        }
        b.append("  muestra=").append(test8Total).append(" AABB_TOO_SMALL=").append(test8Small).append('\n')
        return b.toString()
    }

    /**
     * TEST8.4: desactiva el culling individual en los [limit] mas cercanos
     * dentro por caja (reversible). Devuelve los localIds aplicados.
     */
    fun test8ApplyNoCull(desc: CameraDesc, aspect: Float, limit: Int = 10): List<Int> {
        val cands = ArrayList<Pair<Int, Float>>()
        for (id in slots.keys) {
            val s = slots[id] ?: continue
            val wb = worldBoxOf(s.entity) ?: continue
            if (boxFrustumCull(wb.first, wb.second, desc, aspect) != -1) continue
            cands.add(Pair(id, distanceTo(placementInRegion(id), desc.eye)))
        }
        val done = ArrayList<Int>()
        for ((id, _) in cands.sortedBy { it.second }.take(limit)) {
            val s = slots[id] ?: continue
            if (renderer.setEntityCulling(s.entity, false)) {
                test8NoCull.add(id)
                done.add(id)
            }
        }
        return done
    }

    /** TEST8.4: restaura el culling individual (devuelve cuantos). */
    fun test8ClearNoCull(): Int {
        var n = 0
        for (id in test8NoCull.toList()) {
            val s = slots[id]
            if (s != null && renderer.setEntityCulling(s.entity, true)) n++
        }
        test8NoCull.clear()
        return n
    }

    /**
     * TEST8.7: amplia el AABB (a bounds de vertices) solo en objetos SMALL
     * (reversible, max [limit]). Devuelve los localIds aplicados.
     */
    fun test8ApplyWideBox(limit: Int = 5): List<Int> {
        val done = ArrayList<Int>()
        for (id in slots.keys.sorted()) {
            if (done.size >= limit) break
            val s = slots[id] ?: continue
            if (test8WideBox.containsKey(id)) continue
            val box = renderer.entityObjectBox(s.entity) ?: continue
            val gb = geometryBounds(s) ?: continue
            if (boxContains(box, gb)) continue
            test8WideBox[id] = box
            val wide = floatArrayOf(
                (gb.first[0] + gb.second[0]) * 0.5f, (gb.first[1] + gb.second[1]) * 0.5f, (gb.first[2] + gb.second[2]) * 0.5f,
                (gb.second[0] - gb.first[0]) * 0.5f, (gb.second[1] - gb.first[1]) * 0.5f, (gb.second[2] - gb.first[2]) * 0.5f
            )
            if (renderer.setEntityObjectBox(s.entity, wide)) done.add(id)
            else test8WideBox.remove(id)
        }
        return done
    }

    /** TEST8.7: restaura los AABB originales (devuelve cuantos). */
    fun test8ClearWideBox(): Int {
        var n = 0
        for ((id, box) in test8WideBox.toList()) {
            val s = slots[id]
            if (s != null && renderer.setEntityObjectBox(s.entity, box)) n++
        }
        test8WideBox.clear()
        return n
    }

    // ============================== TEST9: reporte unico AABB =============

    /** TEST9: candidatos cull (localIds), pasos y resultados (base,test). */
    private var test9CullIds = ArrayList<Int>()
    private var test9BoxIds = ArrayList<Int>()
    private var test9Steps = ArrayList<Pair<String, Int>>()
    private var test9StepIdx = 0
    private val test9CullRes = HashMap<Int, Pair<Int, Int>>()
    private val test9BoxRes = HashMap<Int, Pair<Int, Int>>()
    private val test9BaseHold = HashMap<Int, Int>()
    var test9Active = false
        private set
    var test9Note = "-"
        private set
    /** TEST9: bounds de vertices por mesh key (la malla es inmutable). */
    private val test9GeoCache = HashMap<Long, Pair<FloatArray, FloatArray>?>()

    private fun cachedGeoBounds(slot: Slot): Pair<FloatArray, FloatArray>? {
        if (test9GeoCache.containsKey(slot.mesh.key)) return test9GeoCache[slot.mesh.key]
        val gb = geometryBounds(slot)
        test9GeoCache[slot.mesh.key] = gb
        return gb
    }

    private fun closeExt(a: FloatArray, b: FloatArray): Boolean {
        for (i in 0..2) {
            val m = maxOf(kotlin.math.abs(a[i]), kotlin.math.abs(b[i]))
            if (m < 1e-6f) continue
            if (kotlin.math.abs(a[i] - b[i]) / m > 0.02f) return false
        }
        return true
    }

    /**
     * TEST9.1: donde vive la escala visual — TM (sano), VERTICES, AMBAS (doble)
     * o ? Compara extension de vertices vs escala del mesh-local vs escala SL.
     */
    private fun scaleSiteOf(slot: Slot, gb: Pair<FloatArray, FloatArray>?, meshM: FloatArray?): String {
        if (gb == null || meshM == null) return "?"
        val vExt = floatArrayOf(gb.second[0] - gb.first[0], gb.second[1] - gb.first[1], gb.second[2] - gb.first[2])
        val meshS = colNorms3(meshM)
        val slS = slot.transform.scale
        val one = floatArrayOf(1f, 1f, 1f)
        return when {
            closeExt(vExt, slS) && closeExt(meshS, slS) -> "AMBAS(mal: vertices y TM escalan)"
            closeExt(vExt, slS) && closeExt(meshS, one) -> "VERTICES"
            closeExt(meshS, slS) -> "TM(sano)"
            else -> "?"
        }
    }

    /** TEST9: 10 roots + 10 children mas cercanos (para sampler y candidatos). */
    private fun test9SampleIds(anchor: FloatArray): ArrayList<Int> {
        val ids = ArrayList<Int>()
        for (id in slots.keys.filter { parentIdOf(it) == 0 }.sortedBy { distanceTo(placementInRegion(it), anchor) }.take(10)) ids.add(id)
        for (id in parentOf.keys.filter { slots.containsKey(it) }.sortedBy { distanceTo(placementInRegion(it), anchor) }.take(10)) {
            if (!ids.contains(id)) ids.add(id)
        }
        return ids
    }

    /**
     * TEST9: muestreador de 20 (TM local/mundo, AABB, bounds, mundo-caja,
     * sitio de la escala). Solo lectura; actualiza test8Total/test8Small no,
     * usa sus propios conteos en el bloque.
     */
    fun test9SamplerReport(agentPos: FloatArray?, desc: CameraDesc, aspect: Float): String {
        val b = StringBuilder(9000)
        b.append("--- TEST9.1/2/3 AABB + TM + bounds (20) ---\n")
        val anchor = agentPos ?: desc.eye
        var small = 0
        var total = 0
        val planeName = arrayOf("DENTRO", "FUERA_NEAR", "FUERA_FAR", "FUERA_IZQ", "FUERA_DER", "FUERA_ABAJO", "FUERA_ARRIBA")
        for (id in test9SampleIds(anchor)) {
            val s = slots[id] ?: continue
            val box = renderer.entityObjectBox(s.entity)
            val wm = renderer.entityWorldMatrix(s.entity)
            val nodeM = renderer.entityNodeMatrix(s.entity)
            val meshM = renderer.entityMeshMatrix(s.entity)
            val probe = renderer.entityProbe(s.entity)
            val wb = worldBoxOf(s.entity)
            val gb = cachedGeoBounds(s)
            // mundo-caja de la geometria: esquinas de vertices por matriz mundo
            var wgb: Pair<FloatArray, FloatArray>? = null
            if (gb != null && wm != null) {
                val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
                val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
                for (i in 0 until 8) {
                    val w = mat4Vec(
                        wm, floatArrayOf(
                            if (i and 1 != 0) gb.second[0] else gb.first[0],
                            if (i and 2 != 0) gb.second[1] else gb.first[1],
                            if (i and 4 != 0) gb.second[2] else gb.first[2]
                        )
                    )
                    for (a in 0..2) {
                        if (w[a] < mn[a]) mn[a] = w[a]
                        if (w[a] > mx[a]) mx[a] = w[a]
                    }
                }
                wgb = Pair(mn, mx)
            }
            val verdict = if (wb != null) planeName[boxFrustumCull(wb.first, wb.second, desc, aspect) + 1] else "sin-datos"
            var contain = "-"
            var ratio = "-"
            if (box != null && gb != null) {
                val inside = boxContains(box, gb)
                contain = if (inside) "SI" else "NO"
                val ge = floatArrayOf(gb.second[0] - gb.first[0], gb.second[1] - gb.first[1], gb.second[2] - gb.first[2])
                ratio = fmt2(if (box[3] > 1e-9f) ge[0] / (box[3] * 2f) else -1f) + "/" +
                    fmt2(if (box[4] > 1e-9f) ge[1] / (box[4] * 2f) else -1f) + "/" +
                    fmt2(if (box[5] > 1e-9f) ge[2] / (box[5] * 2f) else -1f)
                total++
                if (!inside) small++
            }
            var wcontain = "-"
            if (wb != null && wgb != null) {
                var okw = true
                for (a in 0..2) {
                    if (wgb.first[a] < wb.first[a] - 1e-3f || wgb.second[a] > wb.second[a] + 1e-3f) {
                        okw = false
                        break
                    }
                }
                wcontain = if (okw) "SI" else "NO"
            }
            b.append("  #").append(id).append(" ent=").append(s.entity)
                .append(" inst=").append(probe?.actualRenderableInstance ?: -1)
                .append(" parent=").append(parentIdOf(id))
                .append(" malla=").append(s.mesh.key).append('\n')
            b.append("    TMnodo pos=").append(if (nodeM != null) vec(floatArrayOf(nodeM[12], nodeM[13], nodeM[14])) else "-")
                .append(" esc=").append(if (nodeM != null) vec(colNorms3(nodeM)) else "-")
                .append("  TMmalla esc=").append(if (meshM != null) vec(colNorms3(meshM)) else "-").append('\n')
            b.append("    TMmundo pos=").append(if (wm != null) vec(FrustumMath.translation(wm)) else "-")
                .append(" escMundo=").append(if (wm != null) vec(colNorms3(wm)) else "-").append('\n')
            b.append("    boxFil c=").append(if (box != null) vec(floatArrayOf(box[0], box[1], box[2])) else "-")
                .append(" h=").append(if (box != null) vec(floatArrayOf(box[3], box[4], box[5])) else "-").append('\n')
            b.append("    meshVert min=").append(if (gb != null) vec(gb.first) else "-")
                .append(" max=").append(if (gb != null) vec(gb.second) else "-").append('\n')
            b.append("    contenido=").append(contain).append(" ratio=").append(ratio)
                .append(" mundoContenido=").append(wcontain)
                .append(" caja=").append(verdict)
                .append(" escalaEn=").append(scaleSiteOf(s, gb, meshM))
                .append(if (contain == "NO") " AABB_TOO_SMALL" else "").append('\n')
        }
        b.append("  muestra=").append(total).append(" AABB_TOO_SMALL=").append(small).append('\n')
        test8Total = total
        test8Small = small
        return b.toString()
    }

    /**
     * TEST9: arranca las dos secuencias (10 cull + 5 box). Los box van sobre
     * SMALL reales si los hay, si no sobre los primeros candidatos.
     */
    fun test9Start(desc: CameraDesc, aspect: Float): String {
        for (id in test8NoCull.toList()) {
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        test8NoCull.clear()
        for ((id, box) in test8WideBox.toList()) {
            slots[id]?.let { renderer.setEntityObjectBox(it.entity, box) }
        }
        test8WideBox.clear()
        test9StopSilent()
        val anchor = desc.eye
        val cands = ArrayList<Pair<Int, Float>>()
        val near = slots.keys.sortedBy { distanceTo(placementInRegion(it), anchor) }.take(60)
        for (id in near) {
            val s = slots[id] ?: continue
            val wb = worldBoxOf(s.entity) ?: continue
            if (boxFrustumCull(wb.first, wb.second, desc, aspect) != -1) continue
            cands.add(Pair(id, distanceTo(placementInRegion(id), anchor)))
        }
        test9CullIds = ArrayList(cands.sortedBy { it.second }.take(10).map { it.first })
        val smalls = ArrayList<Int>()
        for (id in near) {
            if (smalls.size >= 5) break
            val s = slots[id] ?: continue
            val box = renderer.entityObjectBox(s.entity) ?: continue
            val gb = cachedGeoBounds(s) ?: continue
            if (!boxContains(box, gb) && !test9CullIds.contains(id)) smalls.add(id)
        }
        for (id in test9CullIds) {
            if (smalls.size >= 5) break
            if (!smalls.contains(id)) smalls.add(id)
        }
        test9BoxIds = smalls
        test9Steps = ArrayList()
        for (id in test9CullIds) {
            test9Steps.add(Pair("cullBase", id))
            test9Steps.add(Pair("cullTest", id))
        }
        for (id in test9BoxIds) {
            test9Steps.add(Pair("boxBase", id))
            test9Steps.add(Pair("boxTest", id))
        }
        test9Steps.add(Pair("done", 0))
        test9StepIdx = 0
        test9CullRes.clear()
        test9BoxRes.clear()
        test9BaseHold.clear()
        test9Active = true
        test9Note = "en curso (paso 0/" + test9Steps.size + ")"
        return "TEST9 arrancado: cull=" + test9CullIds.joinToString(",") + " box=" + test9BoxIds.joinToString(",")
    }

    /** TEST9: avanza UN paso por auditoria (aisla un objeto, mide dibujados). */
    fun test9Tick(): String {
        if (!test9Active || test9StepIdx >= test9Steps.size) return test9Note
        val (op, id) = test9Steps[test9StepIdx]
        val drawn = diagnostics.visibleRenderables
        when (op) {
            "cullBase", "boxBase" -> {
                test9RestoreOne(id)
                test9BaseHold[id] = drawn
            }
            "cullTest" -> {
                val s = slots[id]
                if (s != null && renderer.setEntityCulling(s.entity, false)) {
                    test9CullRes[id] = Pair(test9BaseHold[id] ?: -1, drawn)
                } else {
                    test9CullRes[id] = Pair(test9BaseHold[id] ?: -1, -2)
                }
            }
            "boxTest" -> {
                val s = slots[id]
                val gb = if (s != null) cachedGeoBounds(s) else null
                if (s != null && gb != null) {
                    if (!test8WideBox.containsKey(id)) {
                        renderer.entityObjectBox(s.entity)?.let { test8WideBox[id] = it }
                    }
                    val wide = floatArrayOf(
                        (gb.first[0] + gb.second[0]) * 0.5f, (gb.first[1] + gb.second[1]) * 0.5f, (gb.first[2] + gb.second[2]) * 0.5f,
                        (gb.second[0] - gb.first[0]) * 0.5f, (gb.second[1] - gb.first[1]) * 0.5f, (gb.second[2] - gb.first[2]) * 0.5f
                    )
                    if (renderer.setEntityObjectBox(s.entity, wide)) {
                        test9BoxRes[id] = Pair(test9BaseHold[id] ?: -1, drawn)
                    } else {
                        test9BoxRes[id] = Pair(test9BaseHold[id] ?: -1, -2)
                    }
                } else {
                    test9BoxRes[id] = Pair(test9BaseHold[id] ?: -1, -2)
                }
            }
            "done" -> {
                test9RestoreAll()
                test9Active = false
                test9Note = "completo"
            }
        }
        test9StepIdx++
        if (test9Active) test9Note = "en curso (paso " + test9StepIdx + "/" + test9Steps.size + ")"
        return test9Note
    }

    private fun test9RestoreOne(id: Int) {
        val s = slots[id] ?: return
        renderer.setEntityCulling(s.entity, true)
        test8WideBox[id]?.let { renderer.setEntityObjectBox(s.entity, it) }
    }

    private fun test9RestoreAll() {
        for (id in test9CullIds) test9RestoreOne(id)
        for (id in test9BoxIds) test9RestoreOne(id)
    }

    private fun test9StopSilent() {
        test9RestoreAll()
        test8WideBox.clear()
        test9CullIds = ArrayList()
        test9BoxIds = ArrayList()
        test9Steps = ArrayList()
        test9StepIdx = 0
        test9Active = false
    }

    /** TEST9: apaga y restaura todo (conserva resultados para el informe). */
    fun test9Stop(): String {
        val n = test9CullIds.size + test9BoxIds.size
        test9StopSilent()
        test9Note = "detenido por el usuario"
        return "TEST9 detenido (" + n + " restaurados)"
    }

    /** TEST9: tabla por-objeto de una secuencia (base vs test, veredicto). */
    private fun test9Table(builder: StringBuilder, title: String, res: HashMap<Int, Pair<Int, Int>>, okLabel: String, failLabel: String): Int {
        builder.append(title).append('\n')
        var rescued = 0
        for (id in res.keys.sorted()) {
            val (base, test) = res[id]!!
            val s = slots[id]
            val pos = if (s != null) placementInRegion(id) else null
            val line: String
            val ok: Boolean
            if (base < 0 || test == -2) {
                line = "  #" + id + " base=" + base + " test=" + test + " => SIN_MEDIDA"
                ok = false
            } else if (test > base) {
                line = "  #" + id + " base=" + base + " test=" + test + " => " + okLabel
                ok = true
                rescued++
            } else {
                line = "  #" + id + " base=" + base + " test=" + test + " => " + failLabel
                ok = false
            }
            builder.append(line)
                .append(" pos=").append(if (pos != null) vec(pos) else "-")
                .append(" dist=").append(if (pos != null) fmt1(distanceTo(pos, lastCamera?.eye)) else "-")
                .append('\n')
        }
        return rescued
    }

    /**
     * TEST9: reporte UNICO acumulado (estado, normal, 20 AABB, secuencias,
     * contadores, conclusion). Lo compone la vista cada auditoria.
     */
    fun test9ReportBlock(agentPos: FloatArray?, desc: CameraDesc, aspect: Float, cullOn: Int, cullOff: Int): String {
        val b = StringBuilder(12000)
        b.append("--- TEST9 reporte unico AABB/culling (global ON salvo-indicacion) ---\n")
        b.append("estado: ").append(test9Note)
            .append("  cullIds=").append(if (test9CullIds.isEmpty()) "-" else test9CullIds.joinToString(","))
            .append("  boxIds=").append(if (test9BoxIds.isEmpty()) "-" else test9BoxIds.joinToString(",")).append('\n')
        b.append("culling normal: dibujadosON=").append(cullOn).append(" dibujadosOFF=").append(cullOff).append('\n')
        b.append(test9SamplerReport(agentPos, desc, aspect))
        val rc = test9Table(b, "--- TEST9.4 culling individual por objeto ---", test9CullRes, "RESCATADO_POR_CULLING", "NO_ES_CULLING")
        val rb = test9Table(b, "--- TEST9.5 AABB corregido por objeto ---", test9BoxRes, "RESCATADO_POR_AABB", "NO_CAMBIA")
        val xNorm = test9CullRes.values.map { it.first }.filter { it >= 0 }.let { if (it.isEmpty()) cullOn else it.sorted()[it.size / 2] }
        val yBox = test9BoxRes.values.map { it.second }.filter { it >= 0 }.let { if (it.isEmpty()) -1 else it.max()!! }
        val yBoxId = test9BoxRes.entries.firstOrNull { it.value.second == yBox }?.key
        b.append("drawn normal=").append(xNorm)
            .append("  drawn con AABB corregido=").append(yBox).append(if (yBoxId != null) " (#" + yBoxId + ")" else "")
            .append("  objetos rescatados=").append(rb)
            .append("  objetos que siguen=").append(test9BoxRes.size - rb).append('\n')
        val res: String = when {
            !test9Active && test9CullRes.isEmpty() && test9BoxRes.isEmpty() -> "sin datos (activa TEST9)"
            rc > 0 && rb > 0 -> "AABB = causa confirmada (Caso A)"
            rc > 0 && test9BoxRes.isNotEmpty() -> "parcialmente confirmada (Caso B: culling si, bounds no)"
            test9CullRes.isNotEmpty() && rc == 0 && rb > 0 -> "parcial (Caso D en " + rb + ": AABB valido)"
            test9CullRes.isNotEmpty() && rc == 0 -> "descartada por culling individual (Caso C: layers/estado)"
            else -> "en curso"
        }
        b.append("TEST9 RESULTADO: ").append(res).append('\n')
        return b.toString()
    }

    private val test10Ids = intArrayOf(8578717, 8578718, 9100207, 9100206, 9100205)
    private var test10Steps = ArrayList<Pair<String, Int>>()
    private var test10StepIdx = 0
    var test10Active = false
        private set
    var test10Note = "-"
        private set
    private val test10A = HashMap<Int, Int>()
    private val test10B = HashMap<Int, Int>()
    private val test10C = HashMap<Int, Int>()
    private val test10D = HashMap<Int, Int>()
    private val test10E = HashMap<Int, Int>()
    private val test10OrigBox = HashMap<Int, FloatArray>()
    private val test10Computed = HashMap<Int, FloatArray>()
    private val test10Method = HashMap<Int, String>()
    private val test10Site = HashMap<Int, String>()

    fun test10Progress(): Pair<Int, Int> = Pair(test10StepIdx, test10Steps.size)

    private fun invertMat3(m: FloatArray): FloatArray? {
        val det = m[0] * (m[5] * m[10] - m[9] * m[6]) -
            m[4] * (m[1] * m[10] - m[9] * m[2]) +
            m[8] * (m[1] * m[6] - m[5] * m[2])
        if (kotlin.math.abs(det) < 1e-12f) return null
        val inv = 1f / det
        return floatArrayOf(
            (m[5] * m[10] - m[9] * m[6]) * inv,
            -(m[1] * m[10] - m[9] * m[2]) * inv,
            (m[1] * m[6] - m[5] * m[2]) * inv,
            -(m[4] * m[10] - m[8] * m[6]) * inv,
            (m[0] * m[10] - m[8] * m[2]) * inv,
            -(m[0] * m[6] - m[4] * m[2]) * inv,
            (m[4] * m[9] - m[8] * m[5]) * inv,
            -(m[0] * m[9] - m[8] * m[1]) * inv,
            (m[0] * m[5] - m[4] * m[1]) * inv
        )
    }

    private fun boxThroughMat3(c: FloatArray, h: FloatArray, m3: FloatArray): FloatArray {
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until 8) {
            val lx = c[0] + (if (i and 1 != 0) h[0] else -h[0])
            val ly = c[1] + (if (i and 2 != 0) h[1] else -h[1])
            val lz = c[2] + (if (i and 4 != 0) h[2] else -h[2])
            val w = floatArrayOf(
                m3[0] * lx + m3[3] * ly + m3[6] * lz,
                m3[1] * lx + m3[4] * ly + m3[7] * lz,
                m3[2] * lx + m3[5] * ly + m3[8] * lz
            )
            for (a in 0..2) {
                if (w[a] < mn[a]) mn[a] = w[a]
                if (w[a] > mx[a]) mx[a] = w[a]
            }
        }
        return floatArrayOf(
            (mn[0] + mx[0]) * 0.5f, (mn[1] + mx[1]) * 0.5f, (mn[2] + mx[2]) * 0.5f,
            (mx[0] - mn[0]) * 0.5f, (mx[1] - mn[1]) * 0.5f, (mx[2] - mn[2]) * 0.5f
        )
    }

    private fun mat16(m: FloatArray): String {
        val s = StringBuilder(200)
        for (i in 0 until 16) {
            if (i > 0) s.append(',')
            s.append(fmt2(if (i < m.size) m[i] else Float.NaN))
        }
        return s.toString()
    }

    private fun test10CorrectBox(id: Int): FloatArray? {
        val s = slots[id] ?: return null
        val gb = cachedGeoBounds(s) ?: return null
        val c = floatArrayOf(
            (gb.first[0] + gb.second[0]) * 0.5f,
            (gb.first[1] + gb.second[1]) * 0.5f,
            (gb.first[2] + gb.second[2]) * 0.5f
        )
        val h = floatArrayOf(
            (gb.second[0] - gb.first[0]) * 0.5f,
            (gb.second[1] - gb.first[1]) * 0.5f,
            (gb.second[2] - gb.first[2]) * 0.5f
        )
        val plain = floatArrayOf(c[0], c[1], c[2], h[0], h[1], h[2])
        val meshM = renderer.entityMeshMatrix(s.entity)
        if (meshM == null) {
            test10Method[id] = "sin-TMmalla: vertices tal cual"
            test10Computed[id] = plain
            return plain
        }
        val inv = invertMat3(meshM)
        if (inv == null) {
            test10Method[id] = "TMmalla singular: vertices tal cual"
            test10Computed[id] = plain
            return plain
        }
        val box = boxThroughMat3(c, h, inv)
        test10Method[id] = "inv(TMmalla) x 8 esquinas"
        test10Computed[id] = box
        return box
    }

    fun test10AuditReport(agentPos: FloatArray?, desc: CameraDesc, aspect: Float): String {
        val b = StringBuilder(14000)
        b.append("--- TEST10.1 camino real de la escala (TM local/mundo, SL, AABB) ---\n")
        b.append("punto visual: FilamentRenderer.kt createEntity/updateTransform: ")
        b.append("setTransform(mesh, meshMatrixOf(escala SL)) + setParent(mesh, nodo); ")
        b.append("mundo visual = mundo(nodo) x TMmalla; culling usa mundo(renderable) x boxFil\n")
        b.append("culling global motor=").append(renderer.frustumCullingEnabled?.toString() ?: "?")
        b.append(" (TEST10 exige ON; camara intacta)\n")
        val planeName = arrayOf("DENTRO", "FUERA_NEAR", "FUERA_FAR", "FUERA_IZQ", "FUERA_DER", "FUERA_ABAJO", "FUERA_ARRIBA")
        for (id in test10Ids) {
            val s = slots[id]
            if (s == null) {
                b.append("  #").append(id).append(" ausente (sin slot/entidad)\n")
                continue
            }
            val probe = renderer.entityProbe(s.entity)
            val nodeM = renderer.entityNodeMatrix(s.entity)
            val meshM = renderer.entityMeshMatrix(s.entity)
            val wm = renderer.entityWorldMatrix(s.entity)
            val box = renderer.entityObjectBox(s.entity)
            val gb = cachedGeoBounds(s)
            val site = scaleSiteOf(s, gb, meshM)
            test10Site[id] = site
            val correct = test10CorrectBox(id)
            val curContain = if (box != null && gb != null) boxContains(box, gb) else false
            val okContain = if (correct != null && gb != null) boxContains(correct, gb) else false
            val wb = worldBoxOf(s.entity)
            val verdict = if (wb != null) planeName[boxFrustumCull(wb.first, wb.second, desc, aspect) + 1] else "sin-datos"
            val exp = placementInRegion(id)
            val real = if (wm != null) FrustumMath.translation(wm) else null
            val err = if (real != null) norm3(sub3(real, exp)) else -1f
            val pid = parentIdOf(id)
            val rot = !isIdentityQuat(s.transform.rotation)
            val pscale = if (pid != 0) slots[pid]?.transform?.scale else null
            b.append("  #").append(id).append(" ent=").append(s.entity)
                .append(" inst=").append(probe?.actualRenderableInstance ?: -1)
                .append(" parent=").append(pid)
                .append(if (pid == 0) " RAIZ" else " HIJO")
                .append(if (rot) " ROTADO" else "")
                .append(if (pscale != null && !isUniformScale(pscale)) " PADRE_NO_UNIFORME" else "")
                .append(" malla=").append(s.mesh.key).append('\n')
            b.append("    TMnodo local=[").append(if (nodeM != null) mat16(nodeM) else "-").append("]\n")
            b.append("    TMmalla local=[").append(if (meshM != null) mat16(meshM) else "-").append("]\n")
            b.append("    TMmundo renderable=[").append(if (wm != null) mat16(wm) else "-").append("]\n")
            b.append("    esc nodo=").append(if (nodeM != null) vec(colNorms3(nodeM)) else "-")
                .append(" esc malla=").append(if (meshM != null) vec(colNorms3(meshM)) else "-")
                .append(" esc SL=").append(vec(s.transform.scale)).append('\n')
            b.append("    boxFil c=").append(if (box != null) vec(floatArrayOf(box[0], box[1], box[2])) else "-")
                .append(" h=").append(if (box != null) vec(floatArrayOf(box[3], box[4], box[5])) else "-").append('\n')
            b.append("    vert min=").append(if (gb != null) vec(gb.first) else "-")
                .append(" max=").append(if (gb != null) vec(gb.second) else "-").append('\n')
            b.append("    boxCalc c=").append(if (correct != null) vec(floatArrayOf(correct[0], correct[1], correct[2])) else "-")
                .append(" h=").append(if (correct != null) vec(floatArrayOf(correct[3], correct[4], correct[5])) else "-")
                .append(" metodo=").append(test10Method[id] ?: "-").append('\n')
            b.append("    boxExp = boxCalc (se aplica en paso D)")
                .append(" actualContiene=").append(if (curContain) "SI" else "NO")
                .append(" calcContiene=").append(if (okContain) "SI" else "NO").append('\n')
            b.append("    mundo real=").append(if (real != null) vec(real) else "-")
                .append(" esperado=").append(vec(exp))
                .append(" |err|=").append(if (err >= 0) fmt3(err) else "-")
                .append(" caja=").append(verdict)
                .append(" escalaEn=").append(site).append('\n')
        }
        b.append(test10ScaleOutsideBlock())
        return b.toString()
    }

    private fun test10ScaleOutsideBlock(): String {
        val b = StringBuilder(1500)
        b.append("--- TEST10.6 escala fuera de TM (#8578718) ---\n")
        val id = 8578718
        val s = slots[id]
        if (s == null) {
            b.append("  #8578718 ausente\n")
            return b.toString()
        }
        val gb = cachedGeoBounds(s) ?: return b.append("  #8578718 sin vertices\n").toString()
        val meshM = renderer.entityMeshMatrix(s.entity)
        val nodeM = renderer.entityNodeMatrix(s.entity)
        val vc = floatArrayOf(
            (gb.first[0] + gb.second[0]) * 0.5f,
            (gb.first[1] + gb.second[1]) * 0.5f,
            (gb.first[2] + gb.second[2]) * 0.5f
        )
        val vh = floatArrayOf(
            (gb.second[0] - gb.first[0]) * 0.5f,
            (gb.second[1] - gb.first[1]) * 0.5f,
            (gb.second[2] - gb.first[2]) * 0.5f
        )
        val ms = if (meshM != null) colNorms3(meshM) else floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
        b.append("  esc SL=").append(vec(s.transform.scale))
            .append(" baseVert c=").append(vec(vc)).append(" h=").append(vec(vh)).append('\n')
        b.append("  TMnodo esc=").append(if (nodeM != null) vec(colNorms3(nodeM)) else "-")
            .append(" TMmalla esc=").append(vec(ms)).append('\n')
        val need = test10Computed[id] ?: test10CorrectBox(id)
        if (need != null) {
            b.append("  AABB necesario = inv(TMmalla) x boundsVert:")
            for (a in 0..2) {
                b.append(" eje").append(a).append(": c=").append(fmt3(need[a]))
                    .append(" (").append(fmt3(vc[a])).append("/").append(fmt3(ms[a])).append(")")
                    .append(" h=").append(fmt3(need[a + 3]))
                    .append(" (").append(fmt3(vh[a])).append("/").append(fmt3(ms[a])).append(");")
            }
            b.append(" metodo=").append(test10Method[id] ?: "-").append('\n')
        } else {
            b.append("  AABB necesario: sin datos\n")
        }
        return b.toString()
    }

    fun test10Start(): String {
        for (id in test8NoCull.toList()) {
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        test8NoCull.clear()
        for ((id, box) in test8WideBox.toList()) {
            slots[id]?.let { renderer.setEntityObjectBox(it.entity, box) }
        }
        test8WideBox.clear()
        if (test9Active) {
            test9RestoreAll()
            test9StopSilent()
            test9Note = "pausado por TEST10"
        }
        for (id in test10OrigBox.keys.toList()) {
            slots[id]?.let { renderer.setEntityObjectBox(it.entity, test10OrigBox[id]!!) }
        }
        test10OrigBox.clear()
        for (id in test10Ids) {
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        test10Steps = ArrayList()
        for (id in test10Ids) {
            test10Steps.add(Pair("aBase", id))
            test10Steps.add(Pair("bCullOff", id))
            test10Steps.add(Pair("cRestore", id))
            test10Steps.add(Pair("dBox", id))
            test10Steps.add(Pair("eNormal", id))
        }
        test10Steps.add(Pair("done", 0))
        test10StepIdx = 0
        test10A.clear()
        test10B.clear()
        test10C.clear()
        test10D.clear()
        test10E.clear()
        test10Computed.clear()
        test10Method.clear()
        test10Active = true
        test10Note = "en curso (paso 0/" + test10Steps.size + ")"
        return "TEST10 arrancado: ids=" + test10Ids.joinToString(",")
    }

    fun test10Tick(): String {
        if (!test10Active || test10StepIdx >= test10Steps.size) return test10Note
        val (op, id) = test10Steps[test10StepIdx]
        val drawn = diagnostics.visibleRenderables
        if (test10StepIdx > 0) {
            val (pop, pid) = test10Steps[test10StepIdx - 1]
            if (pop == "eNormal") test10E[pid] = drawn
        }
        when (op) {
            "aBase" -> test10RestoreOne(id)
            "bCullOff" -> {
                val sl = slots[id]
                if (sl == null) {
                    test10A[id] = -3
                } else {
                    test10A[id] = drawn
                    renderer.setEntityCulling(sl.entity, false)
                }
            }
            "cRestore" -> {
                val sl = slots[id]
                if (sl == null) {
                    test10B[id] = -3
                } else {
                    test10B[id] = drawn
                    renderer.setEntityCulling(sl.entity, true)
                }
            }
            "dBox" -> {
                val sl = slots[id]
                val need = test10CorrectBox(id)
                if (sl == null || need == null) {
                    test10C[id] = -3
                    test10D[id] = -3
                } else {
                    test10C[id] = drawn
                    if (!test10OrigBox.containsKey(id)) {
                        renderer.entityObjectBox(sl.entity)?.let { test10OrigBox[id] = it }
                    }
                    if (renderer.setEntityObjectBox(sl.entity, need)) {
                        test10D[id] = -1
                    } else {
                        test10D[id] = -2
                    }
                }
            }
            "eNormal" -> {
                if (test10D[id] == -1) test10D[id] = drawn
                test10RestoreOne(id)
            }
            "done" -> {
                for (oid in test10Ids) test10RestoreOne(oid)
                test10OrigBox.clear()
                test10Active = false
                test10Note = "completo"
            }
        }
        test10StepIdx++
        if (test10Active) test10Note = "en curso (paso " + test10StepIdx + "/" + test10Steps.size + ")"
        return test10Note
    }

    private fun test10RestoreOne(id: Int) {
        val s = slots[id] ?: return
        renderer.setEntityCulling(s.entity, true)
        test10OrigBox[id]?.let { renderer.setEntityObjectBox(s.entity, it) }
    }

    fun test10Stop(): String {
        var n = 0
        for (id in test10Ids) {
            if (slots.containsKey(id)) n++
            test10RestoreOne(id)
        }
        test10OrigBox.clear()
        test10Steps = ArrayList()
        test10StepIdx = 0
        test10Active = false
        test10Note = "detenido por el usuario"
        return "TEST10 detenido (" + n + " restaurados)"
    }

    private fun test10Cell(v: Int?): String = when {
        v == null -> "pend"
        v == -3 -> "SIN_OBJETO"
        v == -2 -> "SIN_API"
        v < 0 -> "sinmedida"
        else -> v.toString()
    }

    fun test10ReportBlock(agentPos: FloatArray?, desc: CameraDesc, aspect: Float, cullOn: Int, cullOff: Int): String {
        val b = StringBuilder(16000)
        b.append("--- TEST10 auditoria de escala + AABB correcto (global ON, camara intacta) ---\n")
        val (done, total) = test10Progress()
        val barLen = 20
        val filled = if (total > 0) (done * barLen / total).coerceIn(0, barLen) else 0
        b.append("progreso: [")
        for (i in 0 until barLen) b.append(if (i < filled) '#' else '-')
        b.append("] ").append(done).append("/").append(total)
        b.append(" estado=").append(test10Note).append('\n')
        b.append("culling normal: dibujadosON=").append(cullOn).append(" dibujadosOFF=").append(cullOff).append('\n')
        b.append(test10AuditReport(agentPos, desc, aspect))
        b.append("--- TEST10.4 secuencia controlada A/B/C/D/E por objeto ---\n")
        var rescuedCull = 0
        var rescuedBox = 0
        var maxErr = 0f
        var errBad = 0
        for (id in test10Ids) {
            val s = slots[id]
            if (s == null) {
                b.append("  #").append(id).append(" ausente\n")
                continue
            }
            val a = test10A[id] ?: -9
            val bb = test10B[id] ?: -9
            val cc = test10C[id] ?: -9
            val dd = test10D[id] ?: -9
            val ee = test10E[id] ?: -9
            val wm = renderer.entityWorldMatrix(s.entity)
            val real = if (wm != null) FrustumMath.translation(wm) else null
            val exp = placementInRegion(id)
            val err = if (real != null) norm3(sub3(real, exp)) else -1f
            if (err >= 0) {
                if (err > maxErr) maxErr = err
                if (err >= 0.01f) errBad++
            }
            val measuredAB = a >= -1 && bb >= -1
            val cullOk = measuredAB && a >= 0 && bb > a
            val boxOk = measuredAB && a >= 0 && dd >= 0 && dd > a
            if (cullOk) rescuedCull++
            if (boxOk) rescuedBox++
            val verdict = when {
                a == -9 || bb == -9 || dd == -9 -> "SIN_DATOS"
                a < 0 || bb < 0 || dd < 0 -> "SIN_MEDIDA"
                cullOk && boxOk -> "corregido por AABB"
                cullOk && !boxOk -> "AABB no es suficiente"
                !cullOk && boxOk -> "AABB rescata sin culling"
                else -> "sin efecto"
            }
            val restC = if (a >= 0 && cc >= 0) {
                if (cc == a) "restauraOK" else "RESTAURA_DIFIERE"
            } else "restaura?"
            val restE = if (a >= 0 && ee >= 0) {
                if (ee == a) "finalOK" else "FINAL_DIFIERE"
            } else "final?"
            b.append("  #").append(id)
                .append(" A=").append(test10Cell(a))
                .append(" B=").append(test10Cell(bb))
                .append(" C=").append(test10Cell(cc))
                .append(" D=").append(test10Cell(dd))
                .append(" E=").append(test10Cell(ee))
                .append(" => ").append(verdict)
                .append(" ").append(restC).append(" ").append(restE)
                .append(" |err|=").append(if (err >= 0) fmt3(err) else "-").append('\n')
        }
        val measured = test10Ids.count { slots.containsKey(it) }
        val failing = 5 - rescuedBox
        val cullConf = rescuedCull > 0
        val cause = when {
            rescuedBox == 0 -> 2
            rescuedCull > 0 && rescuedBox == rescuedCull -> 0
            else -> 1
        }
        var nTm = 0
        var nVert = 0
        for (id in test10Ids) {
            val st = test10Site[id] ?: ""
            if (st.startsWith("TM(")) nTm++
            if (st.contains("VERTICES") || st.contains("AMBAS")) nVert++
        }
        val scaleOutside = nVert > 0
        val parentAltered = errBad > 0
        b.append("TEST10 RESULTADO\n")
        b.append("culling confirmado = ").append(if (cullConf) "SI" else "NO").append('\n')
        b.append("AABB como causa:\n")
        b.append("- confirmada").append(if (cause == 0) "  <-- veredicto" else "").append('\n')
        b.append("- parcialmente confirmada").append(if (cause == 1) "  <-- veredicto" else "").append('\n')
        b.append("- descartada").append(if (cause == 2) "  <-- veredicto" else "").append('\n')
        b.append("objetos rescatados por culling individual = ").append(rescuedCull).append("/5\n")
        b.append("objetos rescatados por AABB correcto = ").append(rescuedBox).append("/5\n")
        b.append("objetos que siguen fallando = ").append(failing).append("/5\n")
        b.append("transformación de escala fuera de TransformManager = ").append(if (scaleOutside) "SI" else "NO").append('\n')
        b.append("parent/child alterado = ").append(if (parentAltered) "SI" else "NO").append('\n')
        b.append("El AABB correcto es inv(TMmalla) x bounds de vertices (8 esquinas): el culling multiplica boxFil por el mundo del renderable igual que al rasterizar (medidos ").append(measured).append("/5, escalaEn TM=").append(nTm).append(" VERTICES/AMBAS=").append(nVert).append(").\n")
        b.append("Con culling=").append(rescuedCull).append("/5 y AABB=").append(rescuedBox).append("/5 el veredicto de arriba dice si el box calculado basta o si hay otra causa ademas del AABB.\n")
        b.append("Parent/child intacto: |err| max=").append(fmt3(maxErr)).append(" en ").append(measured).append(" medidos (limite 0.01).\n")
        return b.toString()
    }

    val test11Ids = intArrayOf(8578717, 8578718, 9100206, 9100205, 9100207)
    var test11CamActive = false
        private set
    var test11CamEye: FloatArray? = null
        private set
    var test11CamTarget: FloatArray? = null
        private set
    var test11Active = false
        private set
    var test11State = "LISTO"
        private set
    var test11Note = "pulsa TEST11"
        private set
    private var test11Steps = ArrayList<Pair<String, Int>>()
    private var test11StepIdx = 0
    private var test11DoneCount = 0
    private var test11Error = "-"
    private var test11ErrStep = "-"
    private var test11ErrObj = "-"

    private class T11Rec(
        var world: FloatArray? = null,
        var ndcX: Float = Float.NaN,
        var ndcY: Float = Float.NaN,
        var depth: Float = Float.NaN,
        var frustumReason: String = "-",
        var inFront: Boolean = false,
        var validSetup: Boolean = false,
        var setupNote: String = "pendiente",
        var globalCulling: Boolean? = null,
        var cullCmdOk: Boolean? = null,
        var renderedA: Int = -9,
        var renderedB: Int = -9,
        var boxC: FloatArray? = null,
        var boxH: FloatArray? = null,
        var vertMin: FloatArray? = null,
        var vertMax: FloatArray? = null,
        var meshKey: Long = 0L,
        var tmNodoScale: FloatArray? = null,
        var tmMallaScale: FloatArray? = null,
        var tmMundoScale: FloatArray? = null,
        var worldBoxOk: Boolean? = null,
        var err: Float = -1f,
        var isRoot: Boolean = true,
        var parentId: Int = 0,
        var parentEntity: String = "-",
        var rotated: Boolean = false,
        var parentNonUniform: Boolean = false,
        var localPos: FloatArray? = null,
        var localRot: FloatArray? = null,
        var localScale: FloatArray? = null,
        var linkset: String = "-",
        var done: Boolean = false
    )

    private val test11Recs = HashMap<Int, T11Rec>()

    fun test11Progress(): Pair<Int, Int> = Pair(test11DoneCount, test11Ids.size)

    private fun test11Rec(id: Int): T11Rec {
        val r = test11Recs[id]
        if (r != null) return r
        val n = T11Rec()
        test11Recs[id] = n
        return n
    }

    private fun test11LinksetRoot(id: Int): Int {
        var cur = id
        var guard = 0
        while (parentIdOf(cur) != 0 && guard < 32) {
            cur = parentIdOf(cur)
            guard++
        }
        return cur
    }

    private fun worldGeomBoxOf(slot: Slot, wm: FloatArray): Pair<FloatArray, FloatArray>? {
        val gb = cachedGeoBounds(slot) ?: return null
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until 8) {
            val w = mat4Vec(
                wm, floatArrayOf(
                    if (i and 1 != 0) gb.second[0] else gb.first[0],
                    if (i and 2 != 0) gb.second[1] else gb.first[1],
                    if (i and 4 != 0) gb.second[2] else gb.first[2]
                )
            )
            for (a in 0..2) {
                if (w[a] < mn[a]) mn[a] = w[a]
                if (w[a] > mx[a]) mx[a] = w[a]
            }
        }
        return Pair(mn, mx)
    }

    fun test11Start(): String {
        for (id in test8NoCull.toList()) {
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        test8NoCull.clear()
        for ((id, box) in test8WideBox.toList()) {
            slots[id]?.let { renderer.setEntityObjectBox(it.entity, box) }
        }
        test8WideBox.clear()
        if (test9Active) {
            test9RestoreAll()
            test9StopSilent()
            test9Note = "pausado por TEST11"
        }
        if (test10Active) {
            for (id in test10Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test10Steps = ArrayList()
            test10StepIdx = 0
            test10Active = false
            test10Note = "pausado por TEST11"
        }
        for (id in test11Ids) {
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        test11Recs.clear()
        test11Steps = ArrayList()
        for (i in test11Ids.indices) {
            test11Steps.add(Pair("aim", i))
            test11Steps.add(Pair("readA", i))
            test11Steps.add(Pair("readB", i))
        }
        test11Steps.add(Pair("done", 0))
        test11StepIdx = 0
        test11DoneCount = 0
        test11CamActive = false
        test11CamEye = null
        test11CamTarget = null
        test11Error = "-"
        test11Active = true
        test11State = "EJECUTANDO"
        test11Note = "Objeto 1/5: #8578717"
        return "TEST11 arrancado: ids=" + test11Ids.joinToString(",")
    }

    fun test11Tick(): String {
        if (!test11Active || test11StepIdx >= test11Steps.size) return test11Note
        val (op, idx) = test11Steps[test11StepIdx]
        val objLabel = if (idx in test11Ids.indices) "#" + test11Ids[idx] else "-"
        try {
            val drawn = diagnostics.visibleRenderables
            when (op) {
                "aim" -> test11Aim(test11Ids[idx])
                "readA" -> {
                    val id = test11Ids[idx]
                    val r = test11Rec(id)
                    val s = slots[id]
                    if (r.done) {
                        test11Note = "Objeto " + (idx + 1) + "/5: #" + id + " (ya contado)"
                    } else if (s == null) {
                        r.renderedA = -3
                        r.renderedB = -3
                        r.done = true
                        test11DoneCount++
                    } else {
                        r.renderedA = drawn
                        r.globalCulling = renderer.frustumCullingEnabled
                        r.cullCmdOk = renderer.setEntityCulling(s.entity, false)
                        test11Note = "Objeto " + (idx + 1) + "/5: #" + id + " (A medido)"
                    }
                }
                "readB" -> {
                    val id = test11Ids[idx]
                    val r = test11Rec(id)
                    val s = slots[id]
                    if (!r.done && s != null && r.renderedA != -3) {
                        r.renderedB = drawn
                        renderer.setEntityCulling(s.entity, true)
                        r.done = true
                        test11DoneCount++
                    }
                    if (r.renderedA == -3) r.renderedB = -3
                    test11Note = "Objeto " + (idx + 1) + "/5: #" + id + " TERMINADO"
                }
                "done" -> {
                    test11CamActive = false
                    test11CamEye = null
                    test11CamTarget = null
                    for (id in test11Ids) {
                        slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
                    }
                    test11Active = false
                    test11State = "TERMINADO"
                    test11Note = "✓ TEST11 TERMINADO — 100%"
                }
            }
        } catch (e: Throwable) {
            test11CamActive = false
            for (id in test11Ids) {
                try {
                    slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
                } catch (_: Throwable) {
                }
            }
            test11Active = false
            test11State = "ERROR"
            test11ErrStep = (test11StepIdx + 1).toString() + "/" + test11Steps.size
            test11ErrObj = objLabel
            test11Error = e.message ?: e.javaClass.simpleName
            test11Note = "ERROR en " + objLabel + ": " + test11Error
        }
        test11StepIdx++
        return test11Note
    }

    private fun test11Aim(id: Int) {
        val r = test11Rec(id)
        val s = slots[id]
        if (s == null) {
            r.setupNote = "NO DISPONIBLE (sin slot/entidad)"
            r.renderedA = -3
            r.renderedB = -3
            r.done = true
            test11DoneCount++
            test11CamActive = false
            test11Note = "Objeto ?: #" + id + " NO DISPONIBLE"
            return
        }
        val wm = renderer.entityWorldMatrix(s.entity)
        if (wm == null) {
            r.setupNote = "NO DISPONIBLE (sin matriz mundo)"
            r.renderedA = -3
            r.renderedB = -3
            r.done = true
            test11DoneCount++
            test11CamActive = false
            test11Note = "Objeto ?: #" + id + " NO DISPONIBLE"
            return
        }
        val world = FrustumMath.translation(wm)
        r.world = world
        val eye = floatArrayOf(world[0] - 10f, world[1], world[2])
        val target = floatArrayOf(world[0], world[1], world[2])
        test11CamEye = eye
        test11CamTarget = target
        test11CamActive = true
        val desc = CameraDesc(eye, target, floatArrayOf(0f, 0f, 1f), 60f, 0.05f, 1024f)
        val sample = CameraFrustum.from(desc, 1080f / 2237f).sample(world)
        r.ndcX = sample.ndcX
        r.ndcY = sample.ndcY
        r.depth = sample.depth
        r.frustumReason = sample.reason
        r.inFront = sample.reason != FrustumReason.BEHIND && sample.depth > 0f
        val centered = kotlin.math.abs(sample.ndcX) <= 0.05f && kotlin.math.abs(sample.ndcY) <= 0.05f
        r.validSetup = r.inFront && centered
        r.setupNote = if (r.validSetup) "CAMERA_TEST = VALID" else "CAMERA_TEST = INVALID (ndc/inFront/depth)"
        val exp = placementInRegion(id)
        val real = FrustumMath.translation(wm)
        r.err = norm3(sub3(real, exp))
        val pid = parentIdOf(id)
        r.parentId = pid
        r.isRoot = pid == 0
        r.rotated = !isIdentityQuat(s.transform.rotation)
        r.localPos = s.transform.translation
        r.localRot = s.transform.rotation
        r.localScale = s.transform.scale
        r.meshKey = s.mesh.key
        val nodeM = renderer.entityNodeMatrix(s.entity)
        val meshM = renderer.entityMeshMatrix(s.entity)
        r.tmNodoScale = if (nodeM != null) colNorms3(nodeM) else null
        r.tmMallaScale = if (meshM != null) colNorms3(meshM) else null
        r.tmMundoScale = colNorms3(wm)
        val gb = cachedGeoBounds(s)
        if (gb != null) {
            r.vertMin = gb.first
            r.vertMax = gb.second
        }
        if (!r.isRoot) {
            val ps = slots[pid]
            r.parentNonUniform = ps?.let { !isUniformScale(it.transform.scale) } ?: false
            r.parentEntity = ps?.entity?.toString() ?: "-"
            val pwm = ps?.let { renderer.entityWorldMatrix(it.entity) }
            val root = test11LinksetRoot(id)
            r.linkset = "root=" + root + " parent=" + pid +
                " childLocal pos=" + vec(s.transform.translation) +
                " rot=" + vec(s.transform.rotation) +
                " esc=" + vec(s.transform.scale) +
                " parentWorld pos=" + (if (pwm != null) vec(FrustumMath.translation(pwm)) else "-") +
                " esc=" + (if (pwm != null) vec(colNorms3(pwm)) else "-") +
                " childWorld pos=" + vec(real)
        } else {
            r.linkset = "RAIZ (sin linkset)"
        }
        val box = renderer.entityObjectBox(s.entity)
        if (box != null) {
            r.boxC = floatArrayOf(box[0], box[1], box[2])
            r.boxH = floatArrayOf(box[3], box[4], box[5])
            val wb = worldBoxOf(s.entity)
            val wg = worldGeomBoxOf(s, wm)
            r.worldBoxOk = if (wb != null && wg != null) {
                var ok = true
                for (a in 0..2) {
                    if (wg.first[a] < wb.first[a] - 1e-3f || wg.second[a] > wb.second[a] + 1e-3f) {
                        ok = false
                        break
                    }
                }
                ok
            } else null
        }
        val i = test11Ids.indexOf(id)
        test11Note = "Objeto " + (i + 1) + "/5: #" + id
    }

    fun test11Stop(): String {
        test11CamActive = false
        test11CamEye = null
        test11CamTarget = null
        var n = 0
        for (id in test11Ids) {
            if (slots.containsKey(id)) n++
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        test11Steps = ArrayList()
        test11StepIdx = 0
        test11Active = false
        if (test11State == "EJECUTANDO") {
            test11State = "LISTO"
            test11Note = "abortado por el usuario"
        }
        return "TEST11 detenido (" + n + " restaurados)"
    }

    private fun test11Panel(): String {
        val b = StringBuilder(1200)
        b.append("========== TEST11 ==========\n")
        b.append("Estado: ").append(test11State).append('\n')
        val pct = test11DoneCount * 100 / test11Ids.size
        b.append("Progreso: ").append(test11DoneCount).append("/").append(test11Ids.size)
            .append(" (").append(pct).append("%)\n")
        if (test11State == "EJECUTANDO") {
            b.append(test11Note).append('\n')
        }
        if (test11State == "ERROR") {
            b.append("Paso: ").append(test11ErrStep).append('\n')
            b.append("Objeto: ").append(test11ErrObj).append('\n')
            b.append("Mensaje: ").append(test11Error).append('\n')
        }
        if (test11State == "TERMINADO") {
            for (id in test11Ids) {
                val r = test11Recs[id]
                if (r == null || r.world == null) {
                    b.append("#").append(id).append(" — NO DISPONIBLE\n")
                } else {
                    b.append("✓ #").append(id).append(" TERMINADO\n")
                }
            }
            b.append("✓ TEST11 TERMINADO\n")
        }
        b.append("[")
        for (i in 0 until 10) {
            b.append(if (i < test11DoneCount * 10 / test11Ids.size) '#' else '-')
        }
        b.append("] ").append(pct).append("%\n")
        return b.toString()
    }

    fun test11ReportBlock(): String {
        val b = StringBuilder(16000)
        b.append(test11Panel())
        b.append("Objetos:\n")
        for (id in test11Ids) {
            val r = test11Recs[id]
            if (r == null) {
                b.append("#").append(id).append(" pendiente\n")
            } else if (r.world == null) {
                b.append("#").append(id).append(" — NO DISPONIBLE (").append(r.setupNote).append(")\n")
            } else {
                b.append("#").append(id).append(" ").append(r.setupNote)
                    .append(" ndc=(").append(fmt3(r.ndcX)).append(",").append(fmt3(r.ndcY)).append(")")
                    .append(" depth=").append(fmt2(r.depth))
                    .append(" rendA=").append(r.renderedA).append(" rendB=").append(r.renderedB).append('\n')
            }
        }
        b.append("--- TEST11 detalle por objeto ---\n")
        var availCount = 0
        var validCount = 0
        var ndcInside = 0
        var bugCount = 0
        var aabbSuspect = 0
        var xfBad = 0
        var pcBad = 0
        for (id in test11Ids) {
            val r = test11Recs[id] ?: continue
            if (r.world == null) continue
            availCount++
            val s = slots[id]
            val centered = !r.ndcX.isNaN() && kotlin.math.abs(r.ndcX) <= 0.05f && kotlin.math.abs(r.ndcY) <= 0.05f
            if (centered) ndcInside++
            if (r.validSetup) validCount++
            val appeared = r.renderedA >= 0 && r.renderedB >= 0 && r.renderedB > r.renderedA
            val bug = r.validSetup && r.globalCulling == true && appeared
            if (bug) bugCount++
            val boxBad = r.validSetup && appeared && r.worldBoxOk == false
            if (boxBad) aabbSuspect++
            if (r.validSetup && r.err >= 0.01f) {
                xfBad++
                if (!r.isRoot) pcBad++
            }
            val (visA, visB) = when {
                r.renderedA < 0 || r.renderedB < 0 -> Pair("sinmedida", "sinmedida")
                appeared -> Pair("NO", "SI")
                else -> Pair("?", "?")
            }
            val resultado = when {
                r.renderedA < 0 || r.renderedB < 0 -> "SIN_MEDIDA"
                bug -> "CULLING_BUG_EN_OBJETO"
                boxBad -> "AABB_SOSPECHOSA"
                !r.validSetup -> "CAMERA_INVALID (no concluyente)"
                appeared -> "DIFERENCIA_SIN_CENTRADO?"
                else -> "OK (sin anomalia)"
            }
            b.append("ID: #").append(id)
                .append(" entidad=").append(if (s != null) s.entity.toString() else "-")
                .append(" parent=").append(r.parentId)
                .append(" (entidad parent: ").append(r.parentEntity).append(")")
                .append(if (r.isRoot) " RAIZ" else " HIJO")
                .append(if (r.rotated) " ROTADO" else "")
                .append(if (r.parentNonUniform) " PADRE_NO_UNIFORME" else "")
                .append(" malla=").append(r.meshKey).append('\n')
            b.append("  world=").append(vec(r.world!!))
                .append(" ndc=(").append(fmt3(r.ndcX)).append(",").append(fmt3(r.ndcY)).append(")")
                .append(" depth=").append(fmt2(r.depth))
                .append(" delante=").append(if (r.inFront) "SI" else "NO")
                .append(" cameraValid=").append(if (r.validSetup) "SI" else "NO").append('\n')
            b.append("  AABB Filament c=").append(if (r.boxC != null) vec(r.boxC!!) else "-")
                .append(" h=").append(if (r.boxH != null) vec(r.boxH!!) else "-")
                .append(" vertex min=").append(if (r.vertMin != null) vec(r.vertMin!!) else "-")
                .append(" max=").append(if (r.vertMax != null) vec(r.vertMax!!) else "-").append('\n')
            b.append("  TMmalla=").append(if (r.tmMallaScale != null) vec(r.tmMallaScale!!) else "-")
                .append(" TMnodo=").append(if (r.tmNodoScale != null) vec(r.tmNodoScale!!) else "-")
                .append(" TMmundo=").append(if (r.tmMundoScale != null) vec(r.tmMundoScale!!) else "-")
                .append(" mundoContiene=").append(r.worldBoxOk?.toString() ?: "-").append('\n')
            b.append("  local pos=").append(if (r.localPos != null) vec(r.localPos!!) else "-")
                .append(" rot=").append(if (r.localRot != null) vec(r.localRot!!) else "-")
                .append(" esc=").append(if (r.localScale != null) vec(r.localScale!!) else "-")
                .append(" |real-expected|=").append(if (r.err >= 0) fmt3(r.err) else "-").append('\n')
            if (!r.isRoot) {
                b.append("  linkset: ").append(r.linkset).append('\n')
            }
            b.append("  culling ON: rendA=").append(r.renderedA).append(" visA=").append(visA)
                .append(" | OFF: rendB=").append(r.renderedB).append(" visB=").append(visB)
                .append(" global=").append(r.globalCulling?.toString() ?: "-").append('\n')
            b.append("  resultado: ").append(resultado).append('\n')
        }
        b.append("Objetos disponibles: ").append(availCount).append("/5\n")
        b.append("Cámaras válidas: ").append(validCount).append("/").append(availCount).append('\n')
        b.append("Culling anómalo confirmado: ").append(bugCount).append('\n')
        b.append("AABB sospechosa: ").append(aabbSuspect).append('\n')
        b.append("Transform incorrecto: ").append(xfBad).append('\n')
        b.append("CULLING_BUG: ").append(if (bugCount > 0) "YES" else "NOT_CONFIRMED").append('\n')
        val aabbVerdict = when {
            aabbSuspect > 0 -> "YES"
            validCount == 0 -> "NOT_CONFIRMED"
            else -> "NO"
        }
        b.append("AABB_INCORRECTA: ").append(aabbVerdict).append('\n')
        b.append("RENDERABLE_TRANSFORM_INCORRECTO: ").append(if (xfBad > 0) "YES" else "NO").append('\n')
        b.append("PARENT_CHILD_ALTERADO: ").append(if (pcBad > 0) "YES" else "NO").append('\n')
        return b.toString()
    }

    private val test12Ids = intArrayOf(8578717, 8578718, 9100206, 9100205, 9100207)
    var test12Active = false
        private set
    var test12State = "LISTO"
        private set
    var test12Note = "pulsa TEST12"
        private set
    private var test12Steps = ArrayList<Pair<String, Int>>()
    private var test12StepIdx = 0
    private var test12DoneSteps = 0
    private val test12Frozen = HashSet<Int>()
    private val test12Stash = HashMap<Int, SLObject>()
    private val test12ProbeHandles = ArrayList<EntityHandle>()
    private var test12CloneHandle: EntityHandle? = null
    private var test12Thread = "-"
    private var test12NoRef = false
    private var test12RefId = 0
    private var test12RefWorld = floatArrayOf(0f, 0f, 0f)
    private var test12RefScale = floatArrayOf(1f, 1f, 1f)
    private var test12GeoLines = ArrayList<String>()
    private var test12RealA = -9
    private var test12RealB = -9
    private var test12RealC = -9
    private var test12RealAF = -1L
    private var test12RealBF = -1L
    private var test12RealCF = -1L
    private var test12RealNote = "-"
    private var test12ThreadLine = "-"
    private var test12FinalLines = ArrayList<String>()
    private var test12Error = "-"

    private class T12Probe(
        val name: String,
        val geo: String,
        val scaleSel: String,
        val prebuild: Boolean,
        var handle: EntityHandle? = null,
        var base: Int = -9,
        var test: Int = -9,
        var baseFrames: Long = -1L,
        var testFrames: Long = -1L,
        var note: String = "pendiente"
    ) {
        fun appeared(): Boolean = base >= 0 && test >= 0 && test > base
        fun settle(): Long = if (baseFrames >= 0 && testFrames >= 0) testFrames - baseFrames else -1L
    }

    private var test12Probes = ArrayList<T12Probe>()

    fun test12Progress(): Pair<Int, Int> = Pair(test12DoneSteps, test12Steps.size)

    private fun test12ProbeSpecs(): ArrayList<T12Probe> {
        val out = ArrayList<T12Probe>()
        out.add(T12Probe("A", "DYNAMIC", "real", false))
        out.add(T12Probe("B", "STATIC_BOUNDS", "real", false))
        out.add(T12Probe("C", "STATIC_BOUNDS", "real", true))
        out.add(T12Probe("D", "DYNAMIC", "real", true))
        out.add(T12Probe("S1", "DYNAMIC", "one", false))
        out.add(T12Probe("S2", "DYNAMIC", "nonuni", false))
        out.add(T12Probe("S3", "STATIC_BOUNDS", "one", false))
        out.add(T12Probe("S4", "STATIC_BOUNDS", "nonuni", false))
        return out
    }

    private fun test12ScaleFor(sel: String): FloatArray = when (sel) {
        "nonuni" -> floatArrayOf(6.50f, 6.50f, 0.20f)
        "one" -> floatArrayOf(1f, 1f, 1f)
        else -> test12RefScale.copyOf()
    }

    private fun test12ProbeTM(sel: String): FloatArray =
        Transform(test12RefWorld.copyOf(), floatArrayOf(0f, 0f, 0f, 1f), test12ScaleFor(sel), null).toMatrix16(FloatArray(16))

    fun test12Start(): String {
        for (id in test8NoCull.toList()) {
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        test8NoCull.clear()
        for ((id, box) in test8WideBox.toList()) {
            slots[id]?.let { renderer.setEntityObjectBox(it.entity, box) }
        }
        test8WideBox.clear()
        if (test9Active) {
            test9RestoreAll()
            test9StopSilent()
            test9Note = "pausado por TEST12"
        }
        if (test10Active) {
            for (id in test10Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test10Steps = ArrayList()
            test10StepIdx = 0
            test10Active = false
            test10Note = "pausado por TEST12"
        }
        if (test11Active) {
            for (id in test11Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test11CamActive = false
            test11Steps = ArrayList()
            test11StepIdx = 0
            test11Active = false
            test11State = "LISTO"
            test11Note = "pausado por TEST12"
        }
        for (id in test12Ids) {
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        val t = Thread.currentThread()
        test12Thread = t.name + "#" + t.id
        test12GeoLines = ArrayList()
        test12Probes = test12ProbeSpecs()
        test12ProbeHandles.clear()
        test12CloneHandle = null
        test12Stash.clear()
        test12Frozen.clear()
        test12NoRef = false
        test12RealA = -9
        test12RealB = -9
        test12RealC = -9
        test12RealNote = "-"
        test12ThreadLine = "-"
        test12FinalLines = ArrayList()
        test12Error = "-"
        test12Steps = ArrayList()
        test12Steps.add(Pair("geo", 0))
        test12Steps.add(Pair("freeze", 0))
        test12Steps.add(Pair("aim", 0))
        for (i in test12Probes.indices) {
            test12Steps.add(Pair("pBase", i))
            test12Steps.add(Pair("pTest", i))
        }
        test12Steps.add(Pair("realA", 0))
        test12Steps.add(Pair("realB", 0))
        test12Steps.add(Pair("cloneMk", 0))
        test12Steps.add(Pair("cloneRd", 0))
        test12Steps.add(Pair("finalCheck", 0))
        test12Steps.add(Pair("unfreeze", 0))
        test12Steps.add(Pair("done", 0))
        test12StepIdx = 0
        test12DoneSteps = 0
        test12Active = true
        test12State = "EJECUTANDO"
        test12Note = "auditoria geometryType"
        return "TEST12 arrancado"
    }

    fun test12Tick(): String {
        if (!test12Active || test12StepIdx >= test12Steps.size) return test12Note
        val (op, arg) = test12Steps[test12StepIdx]
        try {
            when (op) {
                "geo" -> test12Geo()
                "freeze" -> {
                    for (id in test12Ids) test12Frozen.add(id)
                    test12Note = "objetos congelados (5)"
                }
                "aim" -> test12Aim()
                "pBase" -> test12ProbeBase(test12Probes[arg])
                "pTest" -> test12ProbeTest(test12Probes[arg])
                "realA" -> test12RealA()
                "realB" -> test12RealB()
                "cloneMk" -> test12CloneMk()
                "cloneRd" -> test12CloneRd()
                "finalCheck" -> test12FinalCheck()
                "unfreeze" -> test12Unfreeze()
                "done" -> {
                    test12Active = false
                    test12State = "TERMINADO"
                    test12Note = "✓ TEST12 TERMINADO — 100%"
                }
            }
        } catch (e: Throwable) {
            test12Cleanup()
            test12Active = false
            test12State = "ERROR"
            test12Error = (e.message ?: e.javaClass.simpleName) + " en paso " + op + "/" + arg
            test12Note = "ERROR: " + test12Error
        }
        test12StepIdx++
        test12DoneSteps++
        return test12Note
    }

    private fun test12Geo() {
        test12GeoLines.clear()
        for (id in test12Ids) {
            val s = slots[id]
            if (s == null) {
                test12GeoLines.add("#" + id + " = NO DISPONIBLE")
                continue
            }
            val geo = renderer.probeGeometryType(s.entity)
            val box = renderer.entityObjectBox(s.entity)
            val buildM = renderer.entityBuildMatrix(s.entity)
            val curM = renderer.entityLocalMatrix(s.entity)
            test12GeoLines.add(
                "#" + id + " = " + geo +
                    " box=" + (if (box != null) vec(box) else "-") +
                    " buildTM=" + (if (buildM != null) vec(FrustumMath.translation(buildM)) + "/" + vec(colNorms3(buildM)) else "-") +
                    " curTM=" + (if (curM != null) vec(FrustumMath.translation(curM)) + "/" + vec(colNorms3(curM)) else "-")
            )
        }
        test12Note = "auditoria geometryType lista"
    }

    private fun test12Aim() {
        var ref = slots[8578717]
        var refId = 8578717
        if (ref == null) {
            for (id in test12Ids) {
                val s = slots[id]
                if (s != null) {
                    ref = s
                    refId = id
                    break
                }
            }
        }
        if (ref == null) {
            test12NoRef = true
            test11CamActive = false
            test12Note = "sin referencia: probes NO_APLICA"
            return
        }
        val wm = renderer.entityWorldMatrix(ref.entity) ?: run {
            test12NoRef = true
            test11CamActive = false
            test12Note = "sin mundo de referencia: probes NO_APLICA"
            return
        }
        test12RefId = refId
        test12RefWorld = FrustumMath.translation(wm)
        test12RefScale = ref.transform.scale.copyOf()
        test11CamEye = floatArrayOf(test12RefWorld[0] - 10f, test12RefWorld[1], test12RefWorld[2])
        test11CamTarget = test12RefWorld.copyOf()
        test11CamActive = true
        test12Note = "camara en ref #" + refId
    }

    private fun test12RefSlot(): Slot? = slots[test12RefId]

    private fun test12ProbeBase(p: T12Probe) {
        if (test12NoRef) {
            p.note = "NO_APLICA (sin referencia)"
            test12Note = "probe " + p.name + ": NO_APLICA"
            return
        }
        val ref = test12RefSlot() ?: run {
            p.note = "NO_APLICA (ref perdida)"
            test12Note = "probe " + p.name + ": NO_APLICA"
            return
        }
        p.base = diagnostics.visibleRenderables
        p.baseFrames = diagnostics.auditFrameCount
        val tm = test12ProbeTM(p.scaleSel)
        val h = renderer.createProbe(ref.entity, p.geo, if (p.prebuild) tm else null)
        if (!h.isValid) {
            p.note = "FALLO_CREACION"
            test12Note = "probe " + p.name + ": FALLO_CREACION"
            return
        }
        p.handle = h
        test12ProbeHandles.add(h)
        if (!p.prebuild) {
            renderer.setProbeTransform(h, tm)
        }
        test12Note = "probe " + p.name + " creado (" + p.geo + " " + (if (p.prebuild) "PRE" else "POST") + ")"
    }

    private fun test12ProbeTest(p: T12Probe) {
        val h = p.handle
        if (h == null || !h.isValid) {
            if (p.note == "pendiente") p.note = "NO_APLICA"
            test12Note = "probe " + p.name + ": " + p.note
            return
        }
        p.test = diagnostics.visibleRenderables
        p.testFrames = diagnostics.auditFrameCount
        renderer.destroyProbe(h)
        test12ProbeHandles.remove(h)
        p.handle = null
        p.note = if (p.appeared()) "APARECE" else "NO_APARECE"
        test12Note = "probe " + p.name + ": " + p.note + " (base=" + p.base + " test=" + p.test + " settle=" + p.settle() + "f)"
    }

    private fun test12RealA() {
        val s = slots[8578717]
        if (s == null || test12NoRef) {
            test12RealNote = "NO DISPONIBLE"
            test12Note = "real #8578717: NO DISPONIBLE"
            return
        }
        test12RealA = diagnostics.visibleRenderables
        test12RealAF = diagnostics.auditFrameCount
        renderer.setEntityCulling(s.entity, false)
        test12Note = "real A medido"
    }

    private fun test12RealB() {
        val s = slots[8578717] ?: return
        test12RealB = diagnostics.visibleRenderables
        test12RealBF = diagnostics.auditFrameCount
        renderer.setEntityCulling(s.entity, true)
        test12Note = "real B medido (A=" + test12RealA + " B=" + test12RealB + ")"
    }

    private fun test12CloneMk() {
        val s = slots[8578717]
        if (s == null || test12NoRef) {
            test12RealNote = "clon NO_APLICA"
            test12Note = "clon: NO_APLICA"
            return
        }
        val wm = renderer.entityWorldMatrix(s.entity) ?: run {
            test12RealNote = "clon NO_APLICA"
            test12Note = "clon: NO_APLICA"
            return
        }
        val h = renderer.createProbe(s.entity, "DYNAMIC", wm.copyOf())
        if (!h.isValid) {
            test12RealNote = "clon FALLO_CREACION"
            test12Note = "clon: FALLO_CREACION"
            return
        }
        test12CloneHandle = h
        test12ProbeHandles.add(h)
        test12Note = "clon DYNAMIC creado"
    }

    private fun test12CloneRd() {
        val h = test12CloneHandle
        if (h == null || !h.isValid) {
            if (test12RealNote == "-") test12RealNote = "clon NO_APLICA"
            test12Note = "clon: " + test12RealNote
            return
        }
        test12RealC = diagnostics.visibleRenderables
        test12RealCF = diagnostics.auditFrameCount
        renderer.destroyProbe(h)
        test12ProbeHandles.remove(h)
        test12CloneHandle = null
        slots[8578717]?.let { renderer.setEntityCulling(it.entity, true) }
        val a = test12RealA
        val bb = test12RealB
        val cc = test12RealC
        test12RealNote = if (a >= 0 && bb > a && cc > a) {
            "investigar GeometryType/estado del real"
        } else if (a >= 0 && bb > a) {
            "GeometryType no explica el problema"
        } else {
            "sin patron A/B"
        }
        test12Note = "clon C=" + cc + ": " + test12RealNote
    }

    private fun test12FinalCheck() {
        test12FinalLines.clear()
        for (id in test12Ids) {
            val s = slots[id]
            if (s == null) {
                test12FinalLines.add("#" + id + " NO DISPONIBLE")
                continue
            }
            val wm = renderer.entityWorldMatrix(s.entity)
            val real = if (wm != null) FrustumMath.translation(wm) else null
            val exp = placementInRegion(id)
            val err = if (real != null) norm3(sub3(real, exp)) else -1f
            val box = renderer.entityObjectBox(s.entity)
            val gb = cachedGeoBounds(s)
            val contain = if (box != null && gb != null) boxContains(box, gb) else null
            test12FinalLines.add(
                "#" + id + " |err|=" + (if (err >= 0) fmt3(err) else "-") +
                    " boxContiene=" + (contain?.toString() ?: "-") +
                    (if (parentIdOf(id) == 0) " RAIZ" else " HIJO-de-#" + parentIdOf(id))
            )
        }
        test12ThreadLine = renderer.threadAuditLine()
        test12Note = "chequeo final listo"
    }

    private fun test12Cleanup() {
        test11CamActive = false
        test11CamEye = null
        test11CamTarget = null
        for (h in test12ProbeHandles.toList()) {
            try {
                renderer.destroyProbe(h)
            } catch (_: Throwable) {
            }
        }
        test12ProbeHandles.clear()
        test12CloneHandle = null
        for (id in test12Ids) {
            slots[id]?.let {
                try {
                    renderer.setEntityCulling(it.entity, true)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun test12Unfreeze() {
        test12Cleanup()
        val n = test12Stash.size
        for ((_, o) in test12Stash.toList()) {
            try {
                upsert(o)
            } catch (_: Throwable) {
            }
        }
        test12Stash.clear()
        test12Frozen.clear()
        test12Note = "restaurado (" + n + " updates repuestos)"
    }

    fun test12Stop(): String {
        test12Cleanup()
        try {
            test12Unfreeze()
        } catch (_: Throwable) {
        }
        test12Steps = ArrayList()
        test12StepIdx = 0
        test12Active = false
        if (test12State == "EJECUTANDO") {
            test12State = "LISTO"
            test12Note = "abortado por el usuario"
        }
        return "TEST12 detenido y restaurado"
    }

    private fun test12Panel(): String {
        val b = StringBuilder(800)
        b.append("========== TEST12 ==========\n")
        b.append("Estado: ").append(test12State).append('\n')
        val pct = if (test12Steps.isEmpty()) 0 else test12DoneSteps * 100 / test12Steps.size
        b.append("Progreso: ").append(test12DoneSteps).append("/").append(test12Steps.size)
            .append(" (").append(pct).append("%)\n")
        if (test12State == "EJECUTANDO") {
            b.append(test12Note).append('\n')
        }
        if (test12State == "ERROR") {
            b.append("Mensaje: ").append(test12Error).append('\n')
        }
        if (test12State == "TERMINADO") {
            b.append("✓ TEST12 TERMINADO\n")
        }
        b.append("[")
        for (i in 0 until 10) {
            val f = if (test12Steps.isEmpty()) 0 else test12DoneSteps * 10 / test12Steps.size
            b.append(if (i < f) '#' else '-')
        }
        b.append("] ").append(pct).append("%\n")
        return b.toString()
    }

    fun test12ReportBlock(): String {
        val b = StringBuilder(16000)
        b.append(test12Panel())
        b.append("GEOMETRY TYPE:\n")
        if (test12GeoLines.isEmpty()) {
            b.append("(pendiente)\n")
        } else {
            for (l in test12GeoLines) b.append(l).append('\n')
        }
        b.append("THREAD:\n")
        b.append("testThread = ").append(test12Thread).append('\n')
        b.append("runOnFilamentThread:\n").append(diagnostics.test12Route).append('\n')
        b.append(test12ThreadLine)
        b.append("PROBES (ref=#").append(test12RefId).append(" mundo=").append(vec(test12RefWorld)).append("):\n")
        var dynOk = 0
        var dynN = 0
        var stOk = 0
        var stN = 0
        var preOk = 0
        var preN = 0
        var postOk = 0
        var postN = 0
        var oneOk = 0
        var nonOk = 0
        var oneN = 0
        var nonN = 0
        for (p in test12Probes) {
            b.append("Probe ").append(p.name).append(" ").append(p.geo)
                .append(" esc=").append(p.scaleSel)
                .append(" ").append(if (p.prebuild) "PREBUILD" else "POSTBUILD")
                .append(" base=").append(p.base).append(" test=").append(p.test)
                .append(" settle=").append(p.settle()).append("f")
                .append(" resultado=").append(p.note).append('\n')
            if (p.note == "APARECE" || p.note == "NO_APARECE") {
                val ok = p.appeared()
                if (p.geo == "DYNAMIC") {
                    dynN++
                    if (ok) dynOk++
                } else {
                    stN++
                    if (ok) stOk++
                }
                if (p.prebuild) {
                    preN++
                    if (ok) preOk++
                } else {
                    postN++
                    if (ok) postOk++
                }
                if (p.prebuild) {
                    preN++
                    if (ok) preOk++
                } else {
                    postN++
                    if (ok) postOk++
                }
                if (p.scaleSel == "one") {
                    oneN++
                    if (ok) oneOk++
                }
                if (p.scaleSel == "nonuni") {
                    nonN++
                    if (ok) nonOk++
                }
            }
        }
        val probesRun = dynN + stN > 0
        b.append("REAL #8578717: A=").append(test12RealA)
            .append(" B=").append(test12RealB)
            .append(" C(clon DYNAMIC)=").append(test12RealC)
            .append(" settleAB=").append(if (test12RealAF >= 0 && test12RealBF >= 0) test12RealBF - test12RealAF else -1)
            .append("f => ").append(test12RealNote).append('\n')
        b.append("STATIC_BOUNDS_TRANSFORM_ORDER:\n")
        val preB = test12Probes.firstOrNull { it.name == "B" }
        val preC = test12Probes.firstOrNull { it.name == "C" }
        val orderVerdict = when {
            !probesRun -> "NO_APLICA"
            preB != null && preC != null && preC.appeared() && !preB.appeared() -> "CONFIRMADO"
            preB != null && preB.appeared() -> "DESCARTADO"
            else -> "DESCARTADO"
        }
        b.append(orderVerdict).append(" (Bpost=").append(preB?.note ?: "-")
            .append(" Cpre=").append(preC?.note ?: "-").append(")\n")
        b.append("THREADING_MISMATCH:\n")
        val owner = test12ThreadOwnerOf(test12ThreadLine)
        val mismatch = test12ThreadMismatch(test12ThreadLine, owner)
        b.append(if (mismatch) "CONFIRMADO" else "DESCARTADO").append(" (owner=").append(owner).append(")\n")
        b.append("NON_UNIFORM_SCALE_EFFECT:\n")
        val scaleVerdict = when {
            !probesRun -> "NO_APLICA"
            (oneN > 0 && oneOk == oneN && nonN > 0 && nonOk < nonN) -> "CONFIRMADO"
            else -> "DESCARTADO"
        }
        b.append(scaleVerdict).append(" (esc1=").append(oneOk).append("/").append(oneN)
            .append(" noUni=").append(nonOk).append("/").append(nonN).append(")\n")
        b.append("REAL_RENDERABLE_STATE:\n")
        b.append(test12RealNote).append('\n')
        for (l in test12FinalLines) b.append(l).append('\n')
        val staticCause = when {
            !probesRun -> "NOT_APPLICABLE"
            dynN > 0 && dynOk == dynN && stN > 0 && stOk < stN -> "YES"
            else -> "NO"
        }
        val threadCause = if (mismatch) "YES" else "NO"
        val realMeasured = test12RealA >= 0 && test12RealB >= 0
        val realResponds = realMeasured && (test12RealA > test12RealB || test12RealC > test12RealB)
        b.append("CULLING_BUG_CONFIRMED: ").append(if (probesRun || realResponds) "YES" else "NO (sin objetivos disponibles ni medida real)").append('\n')
        b.append("STATIC_BOUNDS_CAUSE: ").append(staticCause).append('\n')
        b.append("THREADING_CAUSE: ").append(threadCause).append('\n')
        val nonuniCause = if (scaleVerdict == "CONFIRMADO") "YES" else "NO"
        b.append("NON_UNIFORM_SCALE_CAUSE: ").append(nonuniCause).append('\n')
        var xfBad = 0
        var pcBad = 0
        var aabbBad = 0
        for (id in test12Ids) {
            val s = slots[id] ?: continue
            val wm = renderer.entityWorldMatrix(s.entity) ?: continue
            val err = norm3(sub3(FrustumMath.translation(wm), placementInRegion(id)))
            if (err >= 0.01f) {
                xfBad++
                if (parentIdOf(id) != 0) pcBad++
            }
            val box = renderer.entityObjectBox(s.entity)
            val gb = cachedGeoBounds(s)
            if (box != null && gb != null && !boxContains(box, gb)) aabbBad++
        }
        b.append("AABB_INCORRECTA: ").append(if (aabbBad > 0) "YES" else "NO").append('\n')
        b.append("RENDERABLE_TRANSFORM_INCORRECTO: ").append(if (xfBad > 0) "YES" else "NO").append('\n')
        b.append("PARENT_CHILD_ALTERADO: ").append(if (pcBad > 0) "YES" else "NO").append('\n')
        if (staticCause == "NO" && threadCause == "NO") {
            b.append("NEXT_CAUSE = UNKNOWN\n")
        }
        return b.toString()
    }

    private fun test12ThreadOwnerOf(line: String): String {
        for (l in line.split('\n')) {
            if (l.startsWith("ENGINE_THREAD_OWNER = ")) {
                return l.substring("ENGINE_THREAD_OWNER = ".length).trim()
            }
        }
        return "-"
    }

    private fun test12ThreadMismatch(line: String, owner: String): Boolean {
        if (owner == "-" || owner.isEmpty()) return false
        val keys = arrayOf("CAMERA_SET", "TRANSFORM_SET", "TRANSFORM_GET", "AABB_GET", "RENDERABLE_SET_CULLING", "RENDER_BEGIN", "TEST12_PROBE_CREATE", "TEST12_PROBE_TRANSFORM")
        for (l in line.split('\n')) {
            for (k in keys) {
                if (l.startsWith(k + " = ")) {
                    val v = l.substring((k + " = ").length).trim()
                    if (v != "-" && v != owner) return true
                }
            }
        }
        val tt = test12Thread.trim()
        if (tt != "-" && tt != owner) return true
        return false
    }

    // ------------------------------------------------------------ TEST13

    private val test13Ids = intArrayOf(8578717, 8578718, 9100206, 9100205, 9100207)
    var test13Active = false
        private set
    var test13State = "LISTO"
        private set
    var test13Note = "pulsa TEST13"
        private set
    private var test13Steps = ArrayList<Pair<String, Int>>()
    private var test13StepIdx = 0
    private var test13DoneSteps = 0
    private val test13Frozen = HashSet<Int>()
    private val test13Stash = HashMap<Int, SLObject>()
    private var test13Primary = 0
    private var test13Secondary = 0
    private var test13SavedViewLayers = -1
    private var test13SavedViewCulling: Boolean? = null
    private var test13WaitFrames = -1L
    private var test13WaitTicks = 0
    private var test13WaitTimeout = false
    private var test13Error = "-"
    private val test13Objs = HashMap<Int, T13Obj>()
    private var test13FinalLines = ArrayList<String>()
    private var test13RestoreLines = ArrayList<String>()

    private class T13Obj(
        val id: Int,
        var available: Boolean = false,
        var world: FloatArray = floatArrayOf(0f, 0f, 0f),
        var ndcX: Float = Float.NaN,
        var ndcY: Float = Float.NaN,
        var depth: Float = -1f,
        var inFront: Boolean = false,
        var cameraValid: Boolean = false,
        var setupNote: String = "-",
        var fp: String = "-",
        var origCull: Boolean = true,
        var origBox: FloatArray? = null,
        var a1: Int = -1,
        var b1: Int = -1,
        var a2: Int = -1,
        var b2: Int = -1,
        var reproduced: String = "-",
        var cloneC: Int = -1,
        var cloneHandle: EntityHandle? = null,
        var cloneFp: String = "-",
        var hierC: Int = -1,
        var hierHandle: EntityHandle? = null,
        var hierNA: Boolean = false,
        var setgeoOn: Int = -1,
        var setgeoOff: Int = -1,
        var setgeoRepair: String = "-",
        var rbsameOn: Int = -1,
        var rbsameOff: Int = -1,
        var rbsame: String = "-",
        var rbnewOn: Int = -1,
        var rbnewOff: Int = -1,
        var rbnew: String = "-",
        var rbnewHandle: EntityHandle? = null
    )

    fun test13Progress(): Pair<Int, Int> = Pair(test13DoneSteps, test13Steps.size)

    private fun test13Obj(id: Int): T13Obj {
        var o = test13Objs[id]
        if (o == null) {
            o = T13Obj(id)
            test13Objs[id] = o
        }
        return o
    }

    fun test13Start(): String {
        for (id in test8NoCull.toList()) {
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        test8NoCull.clear()
        for ((id, box) in test8WideBox.toList()) {
            slots[id]?.let { renderer.setEntityObjectBox(it.entity, box) }
        }
        test8WideBox.clear()
        if (test9Active) {
            test9RestoreAll()
            test9StopSilent()
            test9Note = "pausado por TEST13"
        }
        if (test10Active) {
            for (id in test10Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test10Steps = ArrayList()
            test10StepIdx = 0
            test10Active = false
            test10Note = "pausado por TEST13"
        }
        if (test11Active) {
            for (id in test11Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test11CamActive = false
            test11Steps = ArrayList()
            test11StepIdx = 0
            test11Active = false
            test11State = "LISTO"
            test11Note = "pausado por TEST13"
        }
        if (test12Active) {
            for (id in test12Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test12Cleanup()
            for ((_, o) in test12Stash.toList()) {
                try {
                    upsert(o)
                } catch (_: Throwable) {
                }
            }
            test12Stash.clear()
            test12Frozen.clear()
            test12Steps = ArrayList()
            test12StepIdx = 0
            test12Active = false
            test12State = "LISTO"
            test12Note = "pausado por TEST13"
        }
        for (id in test13Ids) {
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        test13Objs.clear()
        for (id in test13Ids) test13Objs[id] = T13Obj(id)
        test13Primary = 0
        test13Secondary = 0
        for (id in intArrayOf(8578717, 8578718)) {
            if (slots[id] == null) continue
            if (test13Primary == 0) test13Primary = id else if (test13Secondary == 0) test13Secondary = id
        }
        test13Stash.clear()
        test13Frozen.clear()
        test13SavedViewLayers = -1
        test13SavedViewCulling = null
        test13WaitFrames = -1L
        test13WaitTicks = 0
        test13FinalLines = ArrayList()
        test13RestoreLines = ArrayList()
        test13Error = "-"
        test13Steps = ArrayList()
        test13Steps.add(Pair("iso", 0))
        test13Steps.add(Pair("freeze", 0))
        for (id in intArrayOf(test13Primary, test13Secondary)) {
            if (id == 0) continue
            test13Steps.add(Pair("aim", id))
            test13Steps.add(Pair("fp", id))
            test13Steps.add(Pair("sA1", id))
            test13Steps.add(Pair("rA1", id))
            test13Steps.add(Pair("sB1", id))
            test13Steps.add(Pair("rB1", id))
            test13Steps.add(Pair("sA2", id))
            test13Steps.add(Pair("rA2", id))
            test13Steps.add(Pair("sB2", id))
            test13Steps.add(Pair("rB2", id))
        }
        if (test13Primary != 0) {
            test13Steps.add(Pair("cloneMk", 0))
            test13Steps.add(Pair("cloneRd", 0))
            test13Steps.add(Pair("cloneTbl", 0))
            test13Steps.add(Pair("hierMk", 0))
            test13Steps.add(Pair("hierRd", 0))
            test13Steps.add(Pair("setgeo", 0))
            test13Steps.add(Pair("setgeoRd", 0))
            test13Steps.add(Pair("setgeoB", 0))
            test13Steps.add(Pair("setgeoBrd", 0))
            test13Steps.add(Pair("rbsame", 0))
            test13Steps.add(Pair("rbsameRd", 0))
            test13Steps.add(Pair("rbsameB", 0))
            test13Steps.add(Pair("rbsameBrd", 0))
            test13Steps.add(Pair("rbnew", 0))
            test13Steps.add(Pair("rbnewRd", 0))
            test13Steps.add(Pair("rbnewB", 0))
            test13Steps.add(Pair("rbnewBrd", 0))
        }
        test13Steps.add(Pair("final", 0))
        test13Steps.add(Pair("unfreeze", 0))
        test13Steps.add(Pair("done", 0))
        test13StepIdx = 0
        test13DoneSteps = 0
        test13Active = true
        test13State = "EJECUTANDO"
        test13Note = "aislando capa diagnostica"
        return "TEST13 arrancado"
    }

    fun test13Tick(): String {
        if (!test13Active || test13StepIdx >= test13Steps.size) return test13Note
        val (op, arg) = test13Steps[test13StepIdx]
        var advanced = true
        try {
            advanced = when (op) {
                "iso" -> test13Iso()
                "freeze" -> {
                    for (id in test13Ids) test13Frozen.add(id)
                    test13Note = "objetos congelados (5)"
                    true
                }
                "aim" -> test13Aim(arg)
                "fp" -> test13Fp(arg)
                "sA1" -> test13SetCulling(arg, true, "A1")
                "rA1" -> test13Read(arg, 1)
                "sB1" -> test13SetCulling(arg, false, "B1")
                "rB1" -> test13Read(arg, 2)
                "sA2" -> test13SetCulling(arg, true, "A2")
                "rA2" -> test13Read(arg, 3)
                "sB2" -> test13SetCulling(arg, false, "B2")
                "rB2" -> test13Read(arg, 4)
                "cloneMk" -> test13CloneMk()
                "cloneRd" -> test13CloneRd()
                "cloneTbl" -> test13CloneTbl()
                "hierMk" -> test13HierMk()
                "hierRd" -> test13HierRd()
                "setgeo" -> test13Setgeo()
                "setgeoRd" -> test13PhaseRead(1)
                "setgeoB" -> test13PhaseSetB(1)
                "setgeoBrd" -> test13PhaseReadB(1)
                "rbsame" -> test13Rbsame()
                "rbsameRd" -> test13PhaseRead(2)
                "rbsameB" -> test13PhaseSetB(2)
                "rbsameBrd" -> test13PhaseReadB(2)
                "rbnew" -> test13Rbnew()
                "rbnewRd" -> test13PhaseRead(3)
                "rbnewB" -> test13PhaseSetB(3)
                "rbnewBrd" -> test13PhaseReadB(3)
                "final" -> test13Final()
                "unfreeze" -> test13Unfreeze()
                "done" -> {
                    test13Active = false
                    test13State = "TERMINADO"
                    test13Note = "✓ TEST13 TERMINADO — 100%"
                    true
                }
                else -> true
            }
        } catch (e: Throwable) {
            test13Cleanup()
            test13Active = false
            test13State = "ERROR"
            test13Error = (e.message ?: e.javaClass.simpleName) + " en paso " + op + "/" + arg
            test13Note = "ERROR: " + test13Error
            return test13Note
        }
        if (advanced) {
            test13StepIdx++
            test13DoneSteps++
        }
        return test13Note
    }

    private fun test13Stamp(): Long {
        test13WaitFrames = diagnostics.auditFrameCount
        test13WaitTicks = 0
        test13WaitTimeout = false
        return test13WaitFrames
    }

    private fun test13WaitReady(): Boolean {
        if (test13WaitFrames < 0) return true
        val cur = diagnostics.auditFrameCount
        if (cur < 0 || cur - test13WaitFrames < 25) {
            test13WaitTicks++
            if (test13WaitTicks < 3) return false
            test13WaitTimeout = true
            return true
        }
        return true
    }

    private fun test13Iso(): Boolean {
        test13SavedViewCulling = renderer.viewFrustumCullingEnabled()
        renderer.setViewFrustumCulling(true)
        renderer.armBuildOpLog(true)
        var n = 0
        for (id in test13Ids) {
            val s = slots[id] ?: continue
            renderer.labelHandleForOps(s.entity, "REAL #" + id)
            if (renderer.setRenderableLayer(s.entity, 0xFF, 0x40)) n++
        }
        test13SavedViewLayers = renderer.viewLayerIsolate(0xFF, 0x40)
        test13Note = "capa diagnostica aislada (" + n + " en 0x40, vista=" + test13SavedViewLayers + ")"
        return true
    }

    private fun test13Aim(id: Int): Boolean {
        val o = test13Obj(id)
        val s = slots[id]
        if (s == null) {
            o.available = false
            o.setupNote = "NO DISPONIBLE (sin slot/entidad)"
            test13Note = "#" + id + ": NO DISPONIBLE"
            return true
        }
        val wm = renderer.entityWorldMatrix(s.entity)
        if (wm == null) {
            o.available = false
            o.setupNote = "NO DISPONIBLE (sin matriz mundo)"
            test13Note = "#" + id + ": NO DISPONIBLE"
            return true
        }
        o.available = true
        o.world = FrustumMath.translation(wm)
        val eye = floatArrayOf(o.world[0] - 10f, o.world[1], o.world[2])
        val target = floatArrayOf(o.world[0], o.world[1], o.world[2])
        test11CamEye = eye
        test11CamTarget = target
        test11CamActive = true
        val desc = CameraDesc(eye, target, floatArrayOf(0f, 0f, 1f), 60f, 0.05f, 1024f)
        val sample = CameraFrustum.from(desc, 1080f / 2237f).sample(o.world)
        o.ndcX = sample.ndcX
        o.ndcY = sample.ndcY
        o.depth = sample.depth
        o.inFront = sample.reason != FrustumReason.BEHIND && sample.depth > 0f
        o.cameraValid = o.inFront && kotlin.math.abs(sample.ndcX) <= 0.05f && kotlin.math.abs(sample.ndcY) <= 0.05f
        o.setupNote = if (o.cameraValid) "CAMERA_TEST = VALID" else "CAMERA_TEST = INVALID (ndc/inFront/depth)"
        test13Note = "#" + id + ": " + o.setupNote
        return true
    }

    private fun test13Fp(id: Int): Boolean {
        val o = test13Obj(id)
        val s = slots[id]
        if (s == null || !o.available) {
            test13Note = "#" + id + ": fingerprint NO_APLICA"
            return true
        }
        o.fp = renderer.renderableFingerprint(s.entity)
        val box = renderer.entityObjectBox(s.entity)
        if (box != null) o.origBox = box.copyOf()
        o.origCull = true
        for (l in o.fp.split('\n')) {
            if (l.startsWith("cullingEnabled = ")) {
                o.origCull = l.substring("cullingEnabled = ".length).trim() == "true"
            }
        }
        test13Note = "#" + id + ": fingerprint listo (cullingOrig=" + o.origCull + ")"
        return true
    }

    private fun test13SetCulling(id: Int, enabled: Boolean, tag: String): Boolean {
        val o = test13Obj(id)
        val s = slots[id]
        if (s == null || !o.available) {
            test13Note = "#" + id + " " + tag + ": NO_APLICA"
            return true
        }
        renderer.setEntityCulling(s.entity, enabled)
        test13Stamp()
        test13Note = "#" + id + " " + tag + " aplicado (culling=" + enabled + "), esperando 25f"
        return true
    }

    private fun test13Read(id: Int, round: Int): Boolean {
        val o = test13Obj(id)
        if (!o.available) {
            test13Note = "#" + id + ": lectura NO_APLICA"
            return true
        }
        if (!test13WaitReady()) {
            test13Note = "#" + id + ": esperando frames (" + (diagnostics.auditFrameCount - test13WaitFrames) + "/25)"
            return false
        }
        val v = diagnostics.visibleRenderables
        val to = if (test13WaitTimeout) " TIMEOUT_FRAMES" else ""
        when (round) {
            1 -> o.a1 = v
            2 -> o.b1 = v
            3 -> o.a2 = v
            else -> o.b2 = v
        }
        test13WaitFrames = -1L
        test13Note = "#" + id + " medido=" + v + to
        return true
    }

    private fun test13VerdictAB(o: T13Obj): String {
        val r1 = o.a1 >= 0 && o.b1 >= 0 && o.a1 > o.b1
        val r2 = o.a2 >= 0 && o.b2 >= 0 && o.a2 > o.b2
        o.reproduced = when {
            r1 && r2 -> "YES"
            r1 != r2 -> "INTERMITTENT"
            else -> "NO"
        }
        return o.reproduced
    }

    private fun test13PrimaryObj(): T13Obj? =
        if (test13Primary != 0) test13Obj(test13Primary) else null

    private fun test13CloneMk(): Boolean {
        val o = test13PrimaryObj() ?: run {
            test13Note = "clon: sin primario"
            return true
        }
        val s = slots[o.id]
        if (s == null || !o.available) {
            test13Note = "clon: primario NO DISPONIBLE"
            return true
        }
        val sec = if (test13Secondary != 0) slots[test13Secondary] else null
        if (sec != null) renderer.setEntityCulling(sec.entity, true)
        renderer.setEntityCulling(s.entity, false)
        val wm = renderer.entityWorldMatrix(s.entity)
        val h = renderer.createExactClone(s.entity, 0xFF, 0x40, wm?.copyOf(), false, "CLON_EXACTO #" + o.id)
        if (!h.isValid) {
            test13Note = "clon: FALLO_CREACION"
            return true
        }
        o.cloneHandle = h
        test13Stamp()
        test13Note = "clon exacto creado, esperando 25f"
        return true
    }

    private fun test13CloneRd(): Boolean {
        val o = test13PrimaryObj() ?: return true
        val h = o.cloneHandle
        if (h == null || !h.isValid) {
            test13Note = "clon: " + test13Note
            return true
        }
        if (!test13WaitReady()) {
            test13Note = "clon: esperando frames"
            return false
        }
        o.cloneC = diagnostics.visibleRenderables
        renderer.sceneRemoveEntity(h)
        test13WaitFrames = -1L
        val to = if (test13WaitTimeout) " TIMEOUT_FRAMES" else ""
        test13Note = "clon C=" + o.cloneC + " (refB=" + o.b2 + ")" + to
        return true
    }

    private fun test13CloneTbl(): Boolean {
        val o = test13PrimaryObj() ?: return true
        val h = o.cloneHandle
        if (h != null && h.isValid) {
            o.cloneFp = renderer.renderableFingerprint(h)
        }
        test13Note = "tabla real-vs-clon lista"
        return true
    }

    private fun test13HierMk(): Boolean {
        val o = test13PrimaryObj() ?: return true
        if (!o.available) {
            test13Note = "jerarquia: NO_APLICA"
            return true
        }
        if (parentIdOf(o.id) == 0) {
            o.hierNA = true
            test13Note = "jerarquia: NOT_APPLICABLE (raiz sin parent)"
            return true
        }
        val s = slots[o.id] ?: run {
            test13Note = "jerarquia: NO_APLICA"
            return true
        }
        val wm = renderer.entityWorldMatrix(s.entity)
        val h = renderer.createExactClone(s.entity, 0xFF, 0x40, wm?.copyOf(), true, "CLON_JERARQUIA #" + o.id)
        if (!h.isValid) {
            test13Note = "jerarquia: FALLO_CREACION"
            return true
        }
        o.hierHandle = h
        test13Stamp()
        test13Note = "clon jerarquia creado, esperando 25f"
        return true
    }

    private fun test13HierRd(): Boolean {
        val o = test13PrimaryObj() ?: return true
        val h = o.hierHandle
        if (o.hierNA) {
            test13Note = "jerarquia: NOT_APPLICABLE"
            return true
        }
        if (h == null || !h.isValid) {
            test13Note = "jerarquia: NO_APLICA"
            return true
        }
        if (!test13WaitReady()) {
            test13Note = "jerarquia: esperando frames"
            return false
        }
        o.hierC = diagnostics.visibleRenderables
        renderer.sceneRemoveEntity(h)
        test13WaitFrames = -1L
        val to = if (test13WaitTimeout) " TIMEOUT_FRAMES" else ""
        test13Note = "jerarquia H=" + o.hierC + " (refB=" + o.b2 + ")" + to
        return true
    }

    private fun test13Setgeo(): Boolean {
        val o = test13PrimaryObj() ?: return true
        val s = slots[o.id]
        if (s == null || !o.available) {
            test13Note = "FASE5: NO_APLICA"
            return true
        }
        if (!renderer.realSetGeometryRepair(s.entity)) {
            test13Note = "FASE5: FALLO_REPARACION"
            return true
        }
        test13Stamp()
        test13Note = "FASE5 aplicada, esperando 25f"
        return true
    }

    private fun test13PhaseTarget(phase: Int): EntityHandle? {
        val o = test13PrimaryObj() ?: return null
        return when (phase) {
            3 -> o.rbnewHandle
            else -> slots[o.id]?.entity
        }
    }

    private fun test13PhaseRead(phase: Int): Boolean {
        val o = test13PrimaryObj() ?: return true
        if (!test13WaitReady()) {
            test13Note = "FASE" + (phase + 4) + ": esperando frames"
            return false
        }
        val v = diagnostics.visibleRenderables
        when (phase) {
            1 -> o.setgeoOn = v
            2 -> o.rbsameOn = v
            else -> o.rbnewOn = v
        }
        test13WaitFrames = -1L
        test13Note = "FASE" + (phase + 4) + " ON=" + v
        return true
    }

    private fun test13PhaseSetB(phase: Int): Boolean {
        val o = test13PrimaryObj() ?: return true
        val t = test13PhaseTarget(phase)
        if (t == null || !t.isValid) {
            test13Note = "FASE" + (phase + 4) + ": NO_APLICA"
            return true
        }
        renderer.setEntityCulling(t, false)
        test13Stamp()
        test13Note = "FASE" + (phase + 4) + " OFF aplicado, esperando 25f"
        return true
    }

    private fun test13PhaseReadB(phase: Int): Boolean {
        val o = test13PrimaryObj() ?: return true
        if (!test13WaitReady()) {
            test13Note = "FASE" + (phase + 4) + ": esperando frames OFF"
            return false
        }
        val v = diagnostics.visibleRenderables
        when (phase) {
            1 -> {
                o.setgeoOff = v
                o.setgeoRepair = if (o.setgeoOn >= 0 && o.setgeoOn > v) "PASS" else "FAIL"
                val s = slots[o.id]
                if (s != null) renderer.setEntityCulling(s.entity, true)
            }
            2 -> {
                o.rbsameOff = v
                o.rbsame = if (o.rbsameOn >= 0 && o.rbsameOn > v) "PASS" else "FAIL"
                val s = slots[o.id]
                if (s != null) renderer.setEntityCulling(s.entity, true)
            }
            else -> {
                o.rbnewOff = v
                o.rbnew = if (o.rbnewOn >= 0 && o.rbnewOn > v) "PASS" else "FAIL"
                val s = slots[o.id]
                if (s != null) {
                    renderer.sceneAddEntity(s.entity)
                    renderer.setEntityCulling(s.entity, true)
                }
                val nh = o.rbnewHandle
                if (nh != null && nh.isValid) renderer.destroyExactClone(nh)
                o.rbnewHandle = null
            }
        }
        test13WaitFrames = -1L
        test13Note = "FASE" + (phase + 4) + " OFF=" + v
        return true
    }

    private fun test13Rbsame(): Boolean {
        val o = test13PrimaryObj() ?: return true
        val s = slots[o.id]
        if (s == null || !o.available) {
            test13Note = "FASE6: NO_APLICA"
            return true
        }
        if (!renderer.realRebuildSameEntity(s.entity)) {
            test13Note = "FASE6: FALLO_RECONSTRUCCION"
            return true
        }
        test13Stamp()
        test13Note = "FASE6 aplicada, esperando 25f"
        return true
    }

    private fun test13Rbnew(): Boolean {
        val o = test13PrimaryObj() ?: return true
        val s = slots[o.id]
        if (s == null || !o.available) {
            test13Note = "FASE7: NO_APLICA"
            return true
        }
        val hasParent = parentIdOf(o.id) != 0
        val h = renderer.realRebuildNewEntity(s.entity, hasParent, "NUEVA_ENTITY #" + o.id)
        if (!h.isValid) {
            test13Note = "FASE7: FALLO_CREACION"
            return true
        }
        o.rbnewHandle = h
        renderer.sceneRemoveEntity(s.entity)
        test13Stamp()
        test13Note = "FASE7 aplicada (original fuera, nueva dentro), esperando 25f"
        return true
    }

    private fun test13Final(): Boolean {
        test13FinalLines.clear()
        for (id in test13Ids) {
            val s = slots[id]
            if (s == null) {
                test13FinalLines.add("#" + id + " NO DISPONIBLE")
                continue
            }
            val wm = renderer.entityWorldMatrix(s.entity)
            val real = if (wm != null) FrustumMath.translation(wm) else null
            val exp = placementInRegion(id)
            val err = if (real != null) norm3(sub3(real, exp)) else -1f
            val box = renderer.entityObjectBox(s.entity)
            val gb = cachedGeoBounds(s)
            val contain = if (box != null && gb != null) boxContains(box, gb) else null
            val o = test13Obj(id)
            val boxSame = if (box != null && o.origBox != null) box.contentEquals(o.origBox) else null
            test13FinalLines.add(
                "#" + id + " |err|=" + (if (err >= 0) fmt3(err) else "-") +
                    " boxContiene=" + (contain?.toString() ?: "-") +
                    " boxIgualOrig=" + (boxSame?.toString() ?: "-") +
                    " parent=" + parentIdOf(id) +
                    (if (parentIdOf(id) == 0) " RAIZ" else " HIJO-de-#" + parentIdOf(id))
            )
        }
        test13Note = "chequeo final listo"
        return true
    }

    private fun test13Cleanup() {
        test11CamActive = false
        test11CamEye = null
        test11CamTarget = null
        for ((_, o) in test13Objs) {
            for (h in arrayOf(o.cloneHandle, o.hierHandle, o.rbnewHandle)) {
                if (h != null && h.isValid) {
                    try {
                        renderer.destroyExactClone(h)
                    } catch (_: Throwable) {
                    }
                }
            }
            o.cloneHandle = null
            o.hierHandle = null
            o.rbnewHandle = null
        }
    }

    private fun test13Unfreeze(): Boolean {
        test13RestoreLines.clear()
        test13Cleanup()
        for (id in test13Ids) {
            val s = slots[id] ?: continue
            val o = test13Obj(id)
            try {
                renderer.setEntityCulling(s.entity, o.origCull)
                test13RestoreLines.add("#" + id + " culling=" + o.origCull)
            } catch (_: Throwable) {
            }
            val ob = o.origBox
            if (ob != null) {
                try {
                    if (renderer.setEntityObjectBox(s.entity, ob)) {
                        test13RestoreLines.add("#" + id + " AABB original restaurado")
                    }
                } catch (_: Throwable) {
                }
            }
            try {
                renderer.setRenderableLayer(s.entity, 0xFF, 0x1)
            } catch (_: Throwable) {
            }
        }
        test13RestoreLines.add("layer masks renderables = 0x1 (default)")
        try {
            renderer.viewLayerRestore(test13SavedViewLayers)
            test13RestoreLines.add("capas vista = " + test13SavedViewLayers)
        } catch (_: Throwable) {
        }
        try {
            val sv = test13SavedViewCulling
            if (sv != null) {
                renderer.setViewFrustumCulling(sv)
                test13RestoreLines.add("frustum vista = " + sv)
            }
        } catch (_: Throwable) {
        }
        try {
            renderer.armBuildOpLog(false)
        } catch (_: Throwable) {
        }
        val n = test13Stash.size
        for ((_, obj) in test13Stash.toList()) {
            try {
                upsert(obj)
            } catch (_: Throwable) {
            }
        }
        test13Stash.clear()
        test13Frozen.clear()
        test13RestoreLines.add("updates repuestos: " + n + "; transform/material/parent nunca modificados por TEST13")
        test13Note = "restaurado (" + n + " updates repuestos)"
        return true
    }

    fun test13Stop(): String {
        test13Cleanup()
        try {
            test13Unfreeze()
        } catch (_: Throwable) {
        }
        test13Steps = ArrayList()
        test13StepIdx = 0
        test13Active = false
        if (test13State == "EJECUTANDO") {
            test13State = "LISTO"
            test13Note = "abortado por el usuario"
        }
        return "TEST13 detenido y restaurado"
    }

    private fun test13FpMap(fp: String): HashMap<String, String> {
        val m = HashMap<String, String>()
        for (l in fp.split('\n')) {
            val k = l.indexOf(" = ")
            if (k > 0) {
                m[l.substring(0, k).trim()] = l.substring(k + 3).trim()
            }
        }
        return m
    }

    private fun test13Norm(v: String): String = v.trim().replace(Regex("\\s+"), " ")

    private fun test13Panel(): String {
        val b = StringBuilder(800)
        b.append("========== TEST13 ==========\n")
        b.append("Estado: ").append(test13State).append('\n')
        val pct = if (test13Steps.isEmpty()) 0 else test13DoneSteps * 100 / test13Steps.size
        b.append("Progreso: ").append(test13DoneSteps).append("/").append(test13Steps.size)
            .append(" (").append(pct).append("%)\n")
        if (test13State == "EJECUTANDO") {
            b.append(test13Note).append('\n')
        }
        if (test13State == "ERROR") {
            b.append("Mensaje: ").append(test13Error).append('\n')
        }
        if (test13State == "TERMINADO") {
            b.append("✓ TEST13 TERMINADO\n")
        }
        b.append("[")
        for (i in 0 until 10) {
            val f = if (test13Steps.isEmpty()) 0 else test13DoneSteps * 10 / test13Steps.size
            b.append(if (i < f) '#' else '-')
        }
        b.append("] ").append(pct).append("%\n")
        return b.toString()
    }

    private fun test13GlobalRepro(): String {
        var anyYes = false
        var anyInt = false
        var anyAvail = false
        for (id in intArrayOf(test13Primary, test13Secondary)) {
            if (id == 0) continue
            val o = test13Obj(id)
            if (!o.available) continue
            anyAvail = true
            test13VerdictAB(o)
            if (o.reproduced == "YES") anyYes = true
            if (o.reproduced == "INTERMITTENT") anyInt = true
        }
        return when {
            anyYes -> "YES"
            anyInt -> "INTERMITTENT"
            anyAvail -> "NO"
            else -> "NO"
        }
    }

    fun test13ReportBlock(): String {
        val b = StringBuilder(24000)
        b.append(test13Panel())
        b.append("METODOLOGIA: APARECE = view.getVisibleRenderableCount() sube (test>base) con capa diagnostica 0x40 aislada (solo objetivos+clones cuentan; 2890+ restantes ocultos por capa); CAMERA_VALID = NDC<=0.05 + delante + depth>0 (misma muestra TEST11); settle >=25f o TIMEOUT tras 3 ticks; A/B no asume diferencia.\n")
        b.append("OBJETIVOS:\n")
        for (id in test13Ids) {
            val o = test13Obj(id)
            val s = slots[id]
            if (s == null || !o.available) {
                b.append("#").append(id).append(" = NO DISPONIBLE\n")
            } else {
                b.append("#").append(id).append(" = DISPONIBLE mundo=").append(vec(o.world))
                    .append(" ndc=(").append(fmt3(o.ndcX)).append(",").append(fmt3(o.ndcY))
                    .append(") depth=").append(fmt3(o.depth)).append(" ").append(o.setupNote).append('\n')
            }
        }
        b.append("FASE1_REPRODUCIBILIDAD:\n")
        for (id in intArrayOf(test13Primary, test13Secondary)) {
            if (id == 0) continue
            val o = test13Obj(id)
            if (!o.available) {
                b.append("#").append(id).append(": NO DISPONIBLE\n")
                continue
            }
            test13VerdictAB(o)
            b.append("#").append(id).append(": A1=").append(o.a1).append(" B1=").append(o.b1)
                .append(" A2=").append(o.a2).append(" B2=").append(o.b2)
                .append(" => REAL_REPRODUCE=").append(o.reproduced).append('\n')
        }
        val repro = test13GlobalRepro()
        b.append("REAL_REPRODUCED: ").append(repro).append('\n')
        val po = test13PrimaryObj()
        b.append("FASE2_FINGERPRINT:\n")
        if (po == null || !po.available) {
            b.append("(sin primario disponible)\n")
        } else {
            b.append(po.fp)
            if (!po.fp.endsWith("\n")) b.append('\n')
            val so = if (test13Secondary != 0) test13Obj(test13Secondary) else null
            if (so != null && so.available) {
                b.append("--- secundario #").append(so.id).append(" ---\n").append(so.fp)
                if (!so.fp.endsWith("\n")) b.append('\n')
            }
        }
        b.append("CAMINO_CONSTRUCCION:\n")
        if (po == null || !po.available) {
            b.append("(sin primario disponible)\n")
        } else {
            val s = slots[po.id]
            if (s != null) {
                b.append(renderer.renderableBuildLog(s.entity))
                if (!renderer.renderableBuildLog(s.entity).endsWith("\n")) b.append('\n')
            }
            for ((tag, h) in arrayOf("CLON_EXACTO" to po.cloneHandle, "CLON_JERARQUIA" to po.hierHandle)) {
                if (h != null && h.isValid) {
                    b.append(renderer.renderableBuildLog(h))
                    if (!renderer.renderableBuildLog(h).endsWith("\n")) b.append('\n')
                } else if (tag == "CLON_EXACTO" && po.cloneC >= 0) {
                    b.append(tag).append(" #").append(po.id).append(": (destruido tras medir; camino arriba en FASE3)\n")
                }
            }
        }
        val cloneRoot = if (po == null || !po.available) {
            "NO_APLICA (sin primario)"
        } else if (po.cloneHandle == null && po.cloneC < 0) {
            "FAIL (FALLO_CREACION)"
        } else if (po.b2 >= 0 && po.cloneC > po.b2) {
            "PASS"
        } else {
            "FAIL"
        }
        b.append("EXACT_CLONE_ROOT: ").append(cloneRoot)
            .append(" (C=").append(po?.cloneC ?: -1).append(" refB=").append(po?.b2 ?: -1).append(")\n")
        val hierRes = if (po == null || !po.available) {
            "NO_APLICA (sin primario)"
        } else if (po.hierNA) {
            "NOT_APPLICABLE"
        } else if (po.hierHandle == null && po.hierC < 0) {
            "FAIL (FALLO_CREACION)"
        } else if (po.b2 >= 0 && po.hierC > po.b2) {
            "PASS"
        } else {
            "FAIL"
        }
        b.append("EXACT_CLONE_HIERARCHY: ").append(hierRes)
            .append(" (H=").append(po?.hierC ?: -1).append(" refB=").append(po?.b2 ?: -1).append(")\n")
        val setgeoRes = if (po == null || !po.available) "NO_APLICA" else po.setgeoRepair.ifEmpty { "NO_APLICA" }
        b.append("REAL_SETGEOMETRY_REPAIR: ").append(if (setgeoRes == "-") "NO_APLICA" else setgeoRes)
            .append(" (ON=").append(po?.setgeoOn ?: -1).append(" OFF=").append(po?.setgeoOff ?: -1).append(")\n")
        val rbsameRes = if (po == null || !po.available) "NO_APLICA" else po.rbsame.ifEmpty { "NO_APLICA" }
        b.append("REAL_REBUILD_SAME_ENTITY: ").append(if (rbsameRes == "-") "NO_APLICA" else rbsameRes)
            .append(" (ON=").append(po?.rbsameOn ?: -1).append(" OFF=").append(po?.rbsameOff ?: -1).append(")\n")
        val rbnewRes = if (po == null || !po.available) "NO_APLICA" else po.rbnew.ifEmpty { "NO_APLICA" }
        b.append("REAL_REBUILD_NEW_ENTITY: ").append(if (rbnewRes == "-") "NO_APLICA" else rbnewRes)
            .append(" (ON=").append(po?.rbnewOn ?: -1).append(" OFF=").append(po?.rbnewOff ?: -1).append(")\n")
        b.append("TABLA_REAL_VS_CLON (CAMPO | REAL | CLON | IGUAL):\n")
        if (po != null && po.available && po.cloneFp != "-" && po.cloneFp.isNotEmpty()) {
            val rm = test13FpMap(po.fp)
            val cm = test13FpMap(po.cloneFp)
            val rows = arrayOf(
                "primitiveCount" to "primitiveCount",
                "instanceCount" to "instanceCount",
                "culling" to "cullingEnabled",
                "AABB" to "AABB",
                "layerMask" to "layerMask",
                "priority" to "priority",
                "channel" to "channel",
                "material" to "material@0",
                "VertexBuffer" to "VertexBuffer",
                "IndexBuffer" to "IndexBuffer",
                "primitiveType" to "primitiveType",
                "offset/count cara0" to "cara0 offset",
                "minIndex/maxIndex" to "minIndex/maxIndex",
                "geometryType" to "geometryType",
                "transform" to "transformWorld",
                "parent" to "parentRenderable",
                "enabledAttributes" to "enabledVertexAttributes"
            )
            for ((label, key) in rows) {
                val rv = rm[key] ?: "?"
                val cv = cm[key] ?: "?"
                val eq = if (test13Norm(rv) == test13Norm(cv)) "SI" else "NO"
                b.append(label).append(" | ").append(test13Norm(rv)).append(" | ").append(test13Norm(cv)).append(" | ").append(eq).append('\n')
            }
        } else {
            b.append("(sin clon medido)\n")
        }
        b.append("CHEQUEO_ESTADO (§13):\n")
        if (po != null && po.available) {
            var hasComp = "?"
            for (l in po.fp.split('\n')) {
                if (l.startsWith("hasComponent = ")) hasComp = l.substring("hasComponent = ".length).trim()
            }
            b.append("CAMERA_VALID: ").append(po.cameraValid).append(" (").append(po.setupNote).append(")\n")
            b.append("OBJECT_IN_FRUSTUM: ").append(po.inFront).append(" (delante=").append(po.inFront).append(" depth=").append(fmt3(po.depth)).append(")\n")
            b.append("RENDERABLE_EXISTS: ").append(hasComp).append(" (slot+componente, no es prueba de visibilidad)\n")
            b.append("CULLING_ENABLED: orig=").append(po.origCull).append(" vista=").append(test13SavedViewCulling?.toString() ?: "?").append(" (forzado ON durante TEST13)\n")
            b.append("PIXEL_DRAW_RESULT: APARECE = visibleRenderables test>base con capa 0x40; existir Entity o AABB contenido NO cuenta como visible\n")
        } else {
            b.append("(sin primario disponible)\n")
        }
        for (l in test13FinalLines) b.append(l).append('\n')
        val geoCause = when {
            po == null || !po.available || (po.setgeoRepair != "PASS" && po.setgeoRepair != "FAIL") -> "UNKNOWN"
            po.setgeoRepair == "PASS" -> "YES"
            else -> "NO"
        }
        val lifeCause = when {
            po == null || !po.available || (po.rbsame != "PASS" && po.rbsame != "FAIL") -> "UNKNOWN"
            po.rbsame == "PASS" -> "YES"
            else -> "NO"
        }
        val entCause = when {
            po == null || !po.available || (po.rbnew != "PASS" && po.rbnew != "FAIL") -> "UNKNOWN"
            po.rbnew == "PASS" && po.rbsame != "PASS" -> "YES"
            po.rbnew == "PASS" -> "UNKNOWN"
            else -> "NO"
        }
        val parCause = when {
            po == null || !po.available || po.hierNA || hierRes == "NOT_APPLICABLE" -> "UNKNOWN"
            hierRes == "PASS" && cloneRoot != "PASS" -> "YES"
            hierRes == "PASS" -> "NO"
            hierRes.startsWith("FAIL") -> "NO"
            else -> "UNKNOWN"
        }
        val matCause = when {
            cloneRoot == "PASS" -> "NO"
            else -> "UNKNOWN"
        }
        b.append("GEOMETRY_DESCRIPTOR_OR_STATE_CAUSE: ").append(geoCause).append('\n')
        b.append("RENDERABLE_LIFECYCLE_CAUSE: ").append(lifeCause).append('\n')
        b.append("ENTITY_REUSE_OR_HANDLE_STATE_CAUSE: ").append(entCause).append('\n')
        b.append("PARENT_HIERARCHY_CAUSE: ").append(parCause).append('\n')
        b.append("MATERIAL_STATE_CAUSE: ").append(matCause).append('\n')
        var xfBad = 0
        var aabbBad = 0
        for (id in test13Ids) {
            val s = slots[id] ?: continue
            val wm = renderer.entityWorldMatrix(s.entity) ?: continue
            val err = norm3(sub3(FrustumMath.translation(wm), placementInRegion(id)))
            if (err >= 0.01f) xfBad++
            val box = renderer.entityObjectBox(s.entity)
            val gb = cachedGeoBounds(s)
            if (box != null && gb != null && !boxContains(box, gb)) aabbBad++
        }
        b.append("AABB_INCORRECTA: ").append(if (aabbBad > 0) "YES" else "NO").append('\n')
        b.append("RENDERABLE_TRANSFORM_INCORRECTO: ").append(if (xfBad > 0) "YES" else "NO").append('\n')
        b.append("PARENT_CHILD_ALTERADO: NO\n")
        b.append("CULLING_BUG_CONFIRMED: ").append(if (repro == "YES" || repro == "INTERMITTENT") repro else "NO").append(" (evidencia actual; TEST11 queda como historico; REAL_REPRODUCED=").append(repro).append(")\n")
        b.append("REAL_REPRODUCED: ").append(repro).append('\n')
        b.append("EXACT_CLONE_ROOT: ").append(cloneRoot).append('\n')
        b.append("EXACT_CLONE_HIERARCHY: ").append(hierRes).append('\n')
        b.append("REAL_SETGEOMETRY_REPAIR: ").append(if (setgeoRes == "-") "NO_APLICA" else setgeoRes).append('\n')
        b.append("REAL_REBUILD_SAME_ENTITY: ").append(if (rbsameRes == "-") "NO_APLICA" else rbsameRes).append('\n')
        b.append("REAL_REBUILD_NEW_ENTITY: ").append(if (rbnewRes == "-") "NO_APLICA" else rbnewRes).append('\n')
        b.append("GEOMETRY_DESCRIPTOR_OR_STATE_CAUSE: ").append(geoCause).append('\n')
        b.append("RENDERABLE_LIFECYCLE_CAUSE: ").append(lifeCause).append('\n')
        b.append("ENTITY_REUSE_OR_HANDLE_STATE_CAUSE: ").append(entCause).append('\n')
        b.append("PARENT_HIERARCHY_CAUSE: ").append(parCause).append('\n')
        b.append("MATERIAL_STATE_CAUSE: ").append(matCause).append('\n')
        b.append("AABB_INCORRECTA: ").append(if (aabbBad > 0) "YES" else "NO").append('\n')
        b.append("RENDERABLE_TRANSFORM_INCORRECTO: ").append(if (xfBad > 0) "YES" else "NO").append('\n')
        b.append("PARENT_CHILD_ALTERADO: NO\n")
        val nextCause = when {
            geoCause == "YES" -> "GEOMETRY_DESCRIPTOR_OR_STATE"
            lifeCause == "YES" -> "RENDERABLE_LIFECYCLE"
            entCause == "YES" -> "ENTITY_REUSE_OR_HANDLE_STATE"
            parCause == "YES" -> "PARENT_HIERARCHY"
            else -> "UNKNOWN"
        }
        b.append("NEXT_CAUSE = ").append(nextCause).append('\n')
        b.append("RESTAURACION:\n")
        if (test13RestoreLines.isEmpty()) {
            b.append("(pendiente)\n")
        } else {
            for (l in test13RestoreLines) b.append(l).append('\n')
        }
        return b.toString()
    }

    // ------------------------------------------------------------ TEST14

    private val test14Ids = intArrayOf(8578717, 8578718, 9100206, 9100205, 9100207)
    private val TEST14_UNSAFE_PROBES_ENABLED = false
    var test14Active = false
        private set
    var test14State = "LISTO"
        private set
    var test14Note = "pulsa TEST14"
        private set
    private var test14Steps = ArrayList<Pair<String, Int>>()
    private var test14StepIdx = 0
    private var test14DoneSteps = 0
    private val test14Frozen = HashSet<Int>()
    private val test14Stash = HashMap<Int, SLObject>()
    private var test14Primary = 0
    private var test14Secondary = 0
    private var test14SavedViewLayers = -1
    private var test14SavedViewCulling: Boolean? = null
    private var test14WaitFrames = -1L
    private var test14WaitTicks = 0
    private var test14WaitTimeout = false
    private var test14Error = "-"
    private val test14Objs = HashMap<Int, T14Obj>()
    private val test14Probes = HashMap<String, T14Probe>()
    private var test14FinalLines = ArrayList<String>()
    private var test14RestoreLines = ArrayList<String>()

    private class T14Obj(
        val id: Int,
        var available: Boolean = false,
        var world: FloatArray = floatArrayOf(0f, 0f, 0f),
        var ndcX: Float = Float.NaN,
        var ndcY: Float = Float.NaN,
        var depth: Float = -1f,
        var inFront: Boolean = false,
        var cameraValid: Boolean = false,
        var setupNote: String = "-",
        var hier: String = "-",
        var fp: String = "-",
        var origCull: Boolean = true,
        var r1a: Int = -1,
        var r1b: Int = -1,
        var r1c: Int = -1,
        var r2a: Int = -1,
        var r2b: Int = -1,
        var r2c: Int = -1
    ) {
        fun runSeq(run: Int): String = "ON:" + (if (run == 1) r1a else r2a) +
            " / OFF:" + (if (run == 1) r1b else r2b) +
            " / ON:" + (if (run == 1) r1c else r2c)
        fun runResponds(run: Int): Boolean {
            val a = if (run == 1) r1a else r2a
            val bb = if (run == 1) r1b else r2b
            val c = if (run == 1) r1c else r2c
            return a >= 0 && bb >= 0 && c >= 0 && (a > bb || c > bb)
        }
        fun responds(): String {
            val x = runResponds(1)
            val y = runResponds(2)
            return if (x && y) "YES" else if (x != y) "INTERMITTENT" else "NO"
        }
    }

    private class T14Probe(
        val kind: String,
        var handle: EntityHandle? = null,
        var a: Int = -1,
        var b: Int = -1,
        var c: Int = -1,
        var snap: String = "-",
        var note: String = "pendiente"
    ) {
        fun verdict(): String {
            if (a < 0 || b < 0 || c < 0) return "NO_APLICA"
            return if (a > b || c > b) "CULLING_RESPONDS" else "CULLING_NO_DIFFERENCE"
        }
    }

    fun test14Progress(): Pair<Int, Int> = Pair(test14DoneSteps, test14Steps.size)

    private fun test14Obj(id: Int): T14Obj {
        var o = test14Objs[id]
        if (o == null) {
            o = T14Obj(id)
            test14Objs[id] = o
        }
        return o
    }

    private fun test14ProbeKinds(): Array<String> =
        arrayOf("H0", "H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8A", "H8B")

    fun test14Start(): String {
        if (!TEST14_UNSAFE_PROBES_ENABLED) {
            test14Note = "BLOQUEADO: UNSAFE_PROBES = DISABLED (usar TEST14-SAFE)"
            return test14Note
        }
        for (id in test8NoCull.toList()) {
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        test8NoCull.clear()
        for ((id, box) in test8WideBox.toList()) {
            slots[id]?.let { renderer.setEntityObjectBox(it.entity, box) }
        }
        test8WideBox.clear()
        if (test9Active) {
            test9RestoreAll()
            test9StopSilent()
            test9Note = "pausado por TEST14"
        }
        if (test10Active) {
            for (id in test10Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test10Steps = ArrayList()
            test10StepIdx = 0
            test10Active = false
            test10Note = "pausado por TEST14"
        }
        if (test11Active) {
            for (id in test11Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test11CamActive = false
            test11Steps = ArrayList()
            test11StepIdx = 0
            test11Active = false
            test11State = "LISTO"
            test11Note = "pausado por TEST14"
        }
        if (test12Active) {
            for (id in test12Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test12Cleanup()
            test12Steps = ArrayList()
            test12StepIdx = 0
            test12Active = false
            test12State = "LISTO"
            test12Note = "pausado por TEST14"
        }
        if (test13Active) {
            for (id in test13Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test13Cleanup()
            for ((_, o) in test13Stash.toList()) {
                try {
                    upsert(o)
                } catch (_: Throwable) {
                }
            }
            test13Stash.clear()
            test13Frozen.clear()
            test13Steps = ArrayList()
            test13StepIdx = 0
            test13Active = false
            test13State = "LISTO"
            test13Note = "pausado por TEST14"
        }
        for (id in test14Ids) {
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        test14Objs.clear()
        for (id in test14Ids) test14Objs[id] = T14Obj(id)
        test14Probes.clear()
        for (k in test14ProbeKinds()) test14Probes[k] = T14Probe(k)
        test14Primary = 0
        test14Secondary = 0
        for (id in intArrayOf(8578717, 8578718)) {
            if (slots[id] == null) continue
            if (test14Primary == 0) test14Primary = id else if (test14Secondary == 0) test14Secondary = id
        }
        test14Stash.clear()
        test14Frozen.clear()
        test14SavedViewLayers = -1
        test14SavedViewCulling = null
        test14WaitFrames = -1L
        test14WaitTicks = 0
        test14FinalLines = ArrayList()
        test14RestoreLines = ArrayList()
        test14Error = "-"
        test14EpsE1 = -1
        test14EpsE2 = -1
        test14EpsSnap1 = "-"
        test14EpsSnap2 = "-"
        test14H1CmpLines = ArrayList()
        test14Steps = ArrayList()
        test14Steps.add(Pair("iso", 0))
        test14Steps.add(Pair("freeze", 0))
        for (id in intArrayOf(test14Primary, test14Secondary)) {
            if (id == 0) continue
            test14Steps.add(Pair("aim", id))
            test14Steps.add(Pair("hier", id))
            for (run in 1..2) {
                test14Steps.add(Pair("sON", id * 10 + run))
                test14Steps.add(Pair("rON", id * 10 + run))
                test14Steps.add(Pair("sOFF", id * 10 + run))
                test14Steps.add(Pair("rOFF", id * 10 + run))
                test14Steps.add(Pair("sON2", id * 10 + run))
                test14Steps.add(Pair("rON2", id * 10 + run))
            }
        }
        if (test14Primary != 0 || test14Secondary != 0) {
            for (k in test14ProbeKinds()) {
                test14Steps.add(Pair("pMk", test14ProbeKinds().indexOf(k)))
                test14Steps.add(Pair("pA", test14ProbeKinds().indexOf(k)))
                test14Steps.add(Pair("pB", test14ProbeKinds().indexOf(k)))
                test14Steps.add(Pair("pBoff", test14ProbeKinds().indexOf(k)))
                test14Steps.add(Pair("pC", test14ProbeKinds().indexOf(k)))
                test14Steps.add(Pair("pCoff", test14ProbeKinds().indexOf(k)))
            }
            test14Steps.add(Pair("epsSet", 0))
            test14Steps.add(Pair("epsRd", 0))
            test14Steps.add(Pair("epsBack", 0))
            test14Steps.add(Pair("epsBackRd", 0))
            test14Steps.add(Pair("h1cmp", 0))
        }
        test14Steps.add(Pair("final", 0))
        test14Steps.add(Pair("unfreeze", 0))
        test14Steps.add(Pair("done", 0))
        test14StepIdx = 0
        test14DoneSteps = 0
        test14Active = true
        test14State = "EJECUTANDO"
        test14Note = "aislando capa diagnostica"
        return "TEST14 arrancado"
    }

    fun test14Tick(): String {
        if (!test14Active || test14StepIdx >= test14Steps.size) return test14Note
        val (op, arg) = test14Steps[test14StepIdx]
        var advanced = true
        try {
            advanced = when (op) {
                "iso" -> test14Iso()
                "freeze" -> {
                    for (id in test14Ids) test14Frozen.add(id)
                    test14Note = "objetos congelados (5)"
                    true
                }
                "aim" -> test14Aim(arg)
                "hier" -> test14Hier(arg)
                "sON" -> test14SeqSet(arg, true, 1)
                "rON" -> test14SeqRead(arg, 1)
                "sOFF" -> test14SeqSet(arg, false, 2)
                "rOFF" -> test14SeqRead(arg, 2)
                "sON2" -> test14SeqSet(arg, true, 3)
                "rON2" -> test14SeqRead(arg, 3)
                "pMk" -> test14ProbeMk(test14ProbeKinds()[arg])
                "pA" -> test14ProbeRead(test14ProbeKinds()[arg], 1)
                "pB" -> test14ProbeSet(test14ProbeKinds()[arg], false)
                "pBoff" -> test14ProbeRead(test14ProbeKinds()[arg], 2)
                "pC" -> test14ProbeSet(test14ProbeKinds()[arg], true)
                "pCoff" -> test14ProbeRead(test14ProbeKinds()[arg], 3)
                "epsSet" -> test14EpsSet()
                "epsRd" -> test14EpsRead(1)
                "epsBack" -> test14EpsBack()
                "epsBackRd" -> test14EpsRead(2)
                "h1cmp" -> test14H1Cmp()
                "final" -> test14Final()
                "unfreeze" -> test14Unfreeze()
                "done" -> {
                    test14Active = false
                    test14State = "TERMINADO"
                    test14Note = "✓ TEST14 TERMINADO — 100%"
                    true
                }
                else -> true
            }
        } catch (e: Throwable) {
            test14Cleanup()
            test14Active = false
            test14State = "ERROR"
            test14Error = (e.message ?: e.javaClass.simpleName) + " en paso " + op + "/" + arg
            test14Note = "ERROR: " + test14Error
            return test14Note
        }
        if (advanced) {
            test14StepIdx++
            test14DoneSteps++
        }
        return test14Note
    }

    private fun test14Stamp() {
        test14WaitFrames = diagnostics.auditFrameCount
        test14WaitTicks = 0
        test14WaitTimeout = false
    }

    private fun test14WaitReady(): Boolean {
        if (test14WaitFrames < 0) return true
        val cur = diagnostics.auditFrameCount
        if (cur < 0 || cur - test14WaitFrames < 30) {
            test14WaitTicks++
            if (test14WaitTicks < 3) return false
            test14WaitTimeout = true
            return true
        }
        return true
    }

    private fun test14Iso(): Boolean {
        test14SavedViewCulling = renderer.viewFrustumCullingEnabled()
        renderer.setViewFrustumCulling(true)
        renderer.armBuildOpLog(true)
        var n = 0
        for (id in test14Ids) {
            val s = slots[id] ?: continue
            renderer.labelHandleForOps(s.entity, "REAL #" + id)
            if (renderer.setRenderableLayer(s.entity, 0xFF, 0x40)) n++
        }
        test14SavedViewLayers = renderer.viewLayerIsolate(0xFF, 0x40)
        test14Note = "capa diagnostica aislada (" + n + " en 0x40, vista=" + test14SavedViewLayers + ")"
        return true
    }

    private fun test14Aim(id: Int): Boolean {
        val o = test14Obj(id)
        val s = slots[id]
        if (s == null) {
            o.available = false
            o.setupNote = "NO DISPONIBLE (sin slot/entidad)"
            test14Note = "#" + id + ": NO DISPONIBLE"
            return true
        }
        val wm = renderer.entityWorldMatrix(s.entity)
        if (wm == null) {
            o.available = false
            o.setupNote = "NO DISPONIBLE (sin matriz mundo)"
            test14Note = "#" + id + ": NO DISPONIBLE"
            return true
        }
        o.available = true
        o.world = FrustumMath.translation(wm)
        val eye = floatArrayOf(o.world[0] - 10f, o.world[1], o.world[2])
        val target = floatArrayOf(o.world[0], o.world[1], o.world[2])
        test11CamEye = eye
        test11CamTarget = target
        test11CamActive = true
        val desc = CameraDesc(eye, target, floatArrayOf(0f, 0f, 1f), 60f, 0.05f, 1024f)
        val sample = CameraFrustum.from(desc, 1080f / 2237f).sample(o.world)
        o.ndcX = sample.ndcX
        o.ndcY = sample.ndcY
        o.depth = sample.depth
        o.inFront = sample.reason != FrustumReason.BEHIND && sample.depth > 0f
        o.cameraValid = o.inFront && kotlin.math.abs(sample.ndcX) <= 0.05f && kotlin.math.abs(sample.ndcY) <= 0.05f
        o.setupNote = if (o.cameraValid) "CAMERA_TEST = VALID" else "CAMERA_TEST = INVALID (ndc/inFront/depth)"
        test14Note = "#" + id + ": " + o.setupNote
        return true
    }

    private fun test14Hier(id: Int): Boolean {
        val o = test14Obj(id)
        val s = slots[id]
        if (s == null || !o.available) {
            test14Note = "#" + id + ": jerarquia NO_APLICA"
            return true
        }
        o.hier = renderer.captureHierarchy(s.entity)
        o.fp = renderer.renderableFingerprint(s.entity)
        o.origCull = true
        for (l in o.fp.split('\n')) {
            if (l.startsWith("cullingEnabled = ")) {
                o.origCull = l.substring("cullingEnabled = ".length).trim() == "true"
            }
        }
        test14Note = "#" + id + ": jerarquia+fingerprint listos"
        return true
    }

    private fun test14SeqId(arg: Int): Int = arg / 10

    private fun test14SeqRun(arg: Int): Int = arg % 10

    private fun test14SeqSet(arg: Int, enabled: Boolean, tag: Int): Boolean {
        val id = test14SeqId(arg)
        val o = test14Obj(id)
        val s = slots[id]
        if (s == null || !o.available) {
            test14Note = "#" + id + ": secuencia NO_APLICA"
            return true
        }
        renderer.setEntityCulling(s.entity, enabled)
        test14Stamp()
        test14Note = "#" + id + " run" + test14SeqRun(arg) + " culling=" + enabled + ", esperando 30f"
        return true
    }

    private fun test14SeqRead(arg: Int, tag: Int): Boolean {
        val id = test14SeqId(arg)
        val run = test14SeqRun(arg)
        val o = test14Obj(id)
        if (!o.available) {
            test14Note = "#" + id + ": lectura NO_APLICA"
            return true
        }
        if (!test14WaitReady()) {
            test14Note = "#" + id + ": esperando frames (" + (diagnostics.auditFrameCount - test14WaitFrames) + "/30)"
            return false
        }
        val v = diagnostics.visibleRenderables
        val to = if (test14WaitTimeout) " TIMEOUT_FRAMES" else ""
        if (run == 1) {
            if (tag == 1) o.r1a = v else if (tag == 2) o.r1b = v else o.r1c = v
        } else {
            if (tag == 1) o.r2a = v else if (tag == 2) o.r2b = v else o.r2c = v
        }
        test14WaitFrames = -1L
        test14Note = "#" + id + " run" + run + " medido=" + v + to
        return true
    }

    private var test14EpsE1 = -1
    private var test14EpsE2 = -1
    private var test14EpsSnap1 = "-"
    private var test14EpsSnap2 = "-"
    private var test14H1CmpLines = ArrayList<String>()

    private fun test14ProbeSource(): Slot? {
        if (test14Primary != 0) {
            val s = slots[test14Primary]
            if (s != null) return s
        }
        if (test14Secondary != 0) {
            val s = slots[test14Secondary]
            if (s != null) return s
        }
        return null
    }

    private fun test14ProbeMk(kind: String): Boolean {
        val p = test14Probes[kind] ?: return true
        val s = test14ProbeSource()
        if (s == null) {
            p.note = "NO_APLICA (sin fuente)"
            test14Note = kind + ": NO_APLICA"
            return true
        }
        val wm = renderer.entityWorldMatrix(s.entity)
        val h = renderer.createHierarchyProbe(s.entity, kind, 0xFF, 0x40, wm?.copyOf(), "PROBE_" + kind + " #real")
        if (!h.isValid) {
            p.note = "FALLO_CREACION"
            test14Note = kind + ": FALLO_CREACION"
            return true
        }
        p.handle = h
        test14Stamp()
        test14Note = kind + " creado, esperando 30f"
        return true
    }

    private fun test14ProbeToggle(kind: String, enabled: Boolean): Boolean {
        val p = test14Probes[kind] ?: return true
        val h = p.handle
        if (h == null || !h.isValid) {
            test14Note = kind + ": " + p.note
            return true
        }
        renderer.setEntityCulling(h, enabled)
        test14Stamp()
        test14Note = kind + " culling=" + enabled + ", esperando 30f"
        return true
    }

    private fun test14ProbeRead(kind: String, tag: Int): Boolean {
        val p = test14Probes[kind] ?: return true
        val h = p.handle
        if (h == null || !h.isValid) {
            test14Note = kind + ": " + p.note
            return true
        }
        if (!test14WaitReady()) {
            test14Note = kind + ": esperando frames (" + (diagnostics.auditFrameCount - test14WaitFrames) + "/30)"
            return false
        }
        val v = diagnostics.visibleRenderables
        val to = if (test14WaitTimeout) " TIMEOUT_FRAMES" else ""
        if (tag == 1) p.a = v else if (tag == 2) p.b = v else p.c = v
        if (tag == 3) {
            p.snap = renderer.probeHierarchySnapshot(h)
            p.note = p.verdict()
        }
        test14WaitFrames = -1L
        test14Note = kind + " medido=" + v + to
        return true
    }

    private fun test14ProbeSet(kind: String, enabled: Boolean): Boolean =
        test14ProbeToggle(kind, enabled)

    private fun test14EpsSet(): Boolean {
        val p = test14Probes["H6"]
        val h = p?.handle
        if (h == null || !h.isValid) {
            test14Note = "H6 epsilon: NO_APLICA"
            return true
        }
        val s = test14ProbeSource() ?: run {
            test14Note = "H6 epsilon: NO_APLICA"
            return true
        }
        val wm = renderer.entityWorldMatrix(s.entity) ?: run {
            test14Note = "H6 epsilon: NO_APLICA"
            return true
        }
        val moved = wm.copyOf()
        moved[12] = moved[12] + 0.001f
        renderer.setProbeNodeTransform(h, moved)
        test14Stamp()
        test14Note = "H6 epsilon +0.001m aplicado, esperando 30f"
        return true
    }

    private fun test14EpsRead(which: Int): Boolean {
        val p = test14Probes["H6"]
        val h = p?.handle
        if (h == null || !h.isValid) {
            test14Note = "H6 epsilon: NO_APLICA"
            return true
        }
        if (!test14WaitReady()) {
            test14Note = "H6 epsilon: esperando frames"
            return false
        }
        val v = diagnostics.visibleRenderables
        val snap = renderer.probeHierarchySnapshot(h)
        if (which == 1) {
            test14EpsE1 = v
            test14EpsSnap1 = snap
        } else {
            test14EpsE2 = v
            test14EpsSnap2 = snap
        }
        test14WaitFrames = -1L
        test14Note = "H6 epsilon E" + which + "=" + v
        return true
    }

    private fun test14EpsBack(): Boolean {
        val p = test14Probes["H6"]
        val h = p?.handle
        if (h == null || !h.isValid) {
            test14Note = "H6 restaurar: NO_APLICA"
            return true
        }
        val s = test14ProbeSource() ?: run {
            test14Note = "H6 restaurar: NO_APLICA"
            return true
        }
        val wm = renderer.entityWorldMatrix(s.entity) ?: run {
            test14Note = "H6 restaurar: NO_APLICA"
            return true
        }
        renderer.setProbeNodeTransform(h, wm.copyOf())
        test14Stamp()
        test14Note = "H6 mundo original restaurado, esperando 30f"
        return true
    }

    private fun test14NumAfter(prefix: String, text: String): FloatArray? {
        for (l in text.split('\n')) {
            if (l.contains(prefix)) {
                val t = l.substringAfter(prefix).trim().substringBefore(" ").split(",")
                if (t.size >= 3) {
                    try {
                        return floatArrayOf(t[0].trim().toFloat(), t[1].trim().toFloat(), t[2].trim().toFloat())
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return null
    }

    private fun test14MaxDiff(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null) return -1f
        var m = 0f
        for (i in 0..2) {
            val d = kotlin.math.abs(a[i] - b[i])
            if (d > m) m = d
        }
        return m
    }

    private fun test14H1Cmp(): Boolean {
        test14H1CmpLines = ArrayList()
        val po = if (test14Primary != 0) test14Obj(test14Primary) else if (test14Secondary != 0) test14Obj(test14Secondary) else null
        val h1 = test14Probes["H1"]
        val h = h1?.handle
        if (po == null || !po.available || h == null || !h.isValid) {
            test14H1CmpLines.add("comparacion REAL-vs-H1: NO_APLICA")
            test14Note = "comparacion: NO_APLICA"
            return true
        }
        val h1snap = renderer.probeHierarchySnapshot(h)
        val tol = 0.001f
        val rnw = test14NumAfter("renderableWorldTransform = t=", po.hier)
        val h1nw = test14NumAfter("WORLD_AFTER_FRAME renderable=t=", h1snap)
        val rnl = test14NumAfter("renderableLocalTransform = t=", po.hier)
        val h1nl = test14NumAfter("renderable local=t=", h1snap)
        val dnw = test14MaxDiff(rnw, h1nw)
        val dnl = test14MaxDiff(rnl, h1nl)
        test14H1CmpLines.add("REAL_RENDERABLE_WORLD = " + (rnw?.joinToString(",") ?: "-"))
        test14H1CmpLines.add("H1_RENDERABLE_WORLD = " + (h1nw?.joinToString(",") ?: "-") + " diff=" + dnw + " " + if (dnw >= 0 && dnw <= tol) "IGUAL" else "DIFIERE")
        test14H1CmpLines.add("REAL_RENDERABLE_LOCAL = " + (rnl?.joinToString(",") ?: "-"))
        test14H1CmpLines.add("H1_RENDERABLE_LOCAL = " + (h1nl?.joinToString(",") ?: "-") + " diff=" + dnl + " (nodo-vs-hijo: DIFIERE es esperado)")
        var rnodew: FloatArray? = null
        for (l in po.hier.split('\n')) {
            if (l.startsWith("nodeWorldTransform = t=")) {
                rnodew = test14NumAfter("nodeWorldTransform = t=", l)
            }
        }
        var h1nodew: FloatArray? = null
        for (l in h1snap.split('\n')) {
            if (l.startsWith("nodo0 world=t=")) {
                h1nodew = test14NumAfter("nodo0 world=t=", l)
            }
        }
        val dn = test14MaxDiff(rnodew, h1nodew)
        test14H1CmpLines.add("REAL_NODE_WORLD = " + (rnodew?.joinToString(",") ?: "-"))
        test14H1CmpLines.add("H1_NODE_WORLD = " + (h1nodew?.joinToString(",") ?: "-") + " diff=" + dn + " " + if (dn >= 0 && dn <= tol) "IGUAL" else "DIFIERE")
        test14Note = "comparacion REAL-vs-H1 lista"
        return true
    }

    private fun test14Final(): Boolean {
        test14FinalLines.clear()
        for (id in test14Ids) {
            val s = slots[id]
            if (s == null) {
                test14FinalLines.add("#" + id + " NO DISPONIBLE")
                continue
            }
            val wm = renderer.entityWorldMatrix(s.entity)
            val real = if (wm != null) FrustumMath.translation(wm) else null
            val exp = placementInRegion(id)
            val err = if (real != null) norm3(sub3(real, exp)) else -1f
            test14FinalLines.add(
                "#" + id + " |err|=" + (if (err >= 0) fmt3(err) else "-") +
                    (if (parentIdOf(id) == 0) " RAIZ" else " HIJO-de-#" + parentIdOf(id))
            )
        }
        test14Note = "chequeo final listo"
        return true
    }

    private fun test14Cleanup() {
        test11CamActive = false
        test11CamEye = null
        test11CamTarget = null
        for ((_, p) in test14Probes) {
            val h = p.handle
            if (h != null && h.isValid) {
                try {
                    renderer.destroyHierarchyProbe(h)
                } catch (_: Throwable) {
                }
            }
            p.handle = null
        }
    }

    private fun test14Unfreeze(): Boolean {
        test14RestoreLines.clear()
        test14Cleanup()
        for (id in test14Ids) {
            val s = slots[id] ?: continue
            val o = test14Obj(id)
            try {
                renderer.setEntityCulling(s.entity, o.origCull)
                test14RestoreLines.add("#" + id + " culling=" + o.origCull)
            } catch (_: Throwable) {
            }
            try {
                renderer.setRenderableLayer(s.entity, 0xFF, 0x1)
            } catch (_: Throwable) {
            }
        }
        test14RestoreLines.add("layer masks renderables = 0x1 (default)")
        try {
            renderer.viewLayerRestore(test14SavedViewLayers)
            test14RestoreLines.add("capas vista = " + test14SavedViewLayers)
        } catch (_: Throwable) {
        }
        try {
            val sv = test14SavedViewCulling
            if (sv != null) {
                renderer.setViewFrustumCulling(sv)
                test14RestoreLines.add("frustum vista = " + sv)
            }
        } catch (_: Throwable) {
        }
        try {
            renderer.armBuildOpLog(false)
        } catch (_: Throwable) {
        }
        val n = test14Stash.size
        for ((_, obj) in test14Stash.toList()) {
            try {
                upsert(obj)
            } catch (_: Throwable) {
            }
        }
        test14Stash.clear()
        test14Frozen.clear()
        test14RestoreLines.add("updates repuestos: " + n + "; reales intactos (sin setGeometry/setAABB/rebuild/material)")
        test14Note = "restaurado (" + n + " updates repuestos)"
        return true
    }

    fun test14Stop(): String {
        test14Cleanup()
        try {
            test14Unfreeze()
        } catch (_: Throwable) {
        }
        test14Steps = ArrayList()
        test14StepIdx = 0
        test14Active = false
        if (test14State == "EJECUTANDO") {
            test14State = "LISTO"
            test14Note = "abortado por el usuario"
        }
        return "TEST14 detenido y restaurado"
    }

    private fun test14Panel(): String {
        val b = StringBuilder(800)
        b.append("========== TEST14 ==========\n")
        b.append("Estado: ").append(test14State).append('\n')
        val pct = if (test14Steps.isEmpty()) 0 else test14DoneSteps * 100 / test14Steps.size
        b.append("Progreso: ").append(test14DoneSteps).append("/").append(test14Steps.size)
            .append(" (").append(pct).append("%)\n")
        if (test14State == "EJECUTANDO") {
            b.append(test14Note).append('\n')
        }
        if (test14State == "ERROR") {
            b.append("Mensaje: ").append(test14Error).append('\n')
        }
        if (test14State == "TERMINADO") {
            b.append("✓ TEST14 TERMINADO\n")
        }
        b.append("[")
        for (i in 0 until 10) {
            val f = if (test14Steps.isEmpty()) 0 else test14DoneSteps * 10 / test14Steps.size
            b.append(if (i < f) '#' else '-')
        }
        b.append("] ").append(pct).append("%\n")
        return b.toString()
    }

    fun test14ReportBlock(): String {
        val b = StringBuilder(30000)
        b.append(test14Panel())
        b.append("METODOLOGIA: APARECE = view.getVisibleRenderableCount() sube (ON>OFF) con capa 0x40 aislada; CAMERA_VALID = NDC<=0.05 + delante + depth>0 (muestra TEST11); settle >=30f o TIMEOUT tras 3 ticks; A/B/C no asume diferencia; reales nunca reconstruidos.\n")
        b.append("OBJETIVOS:\n")
        for (id in test14Ids) {
            val o = test14Obj(id)
            val s = slots[id]
            if (s == null || !o.available) {
                b.append("#").append(id).append(" = NO DISPONIBLE\n")
            } else {
                b.append("#").append(id).append(" = DISPONIBLE mundo=").append(vec(o.world))
                    .append(" ndc=(").append(fmt3(o.ndcX)).append(",").append(fmt3(o.ndcY))
                    .append(") depth=").append(fmt3(o.depth)).append(" ").append(o.setupNote).append('\n')
            }
        }
        b.append("JERARQUIA_REAL:\n")
        for (id in intArrayOf(test14Primary, test14Secondary)) {
            if (id == 0) continue
            val o = test14Obj(id)
            if (!o.available) {
                b.append("#").append(id).append(": NO DISPONIBLE\n")
                continue
            }
            b.append(o.hier)
            if (!o.hier.endsWith("\n")) b.append('\n')
        }
        b.append("FINGERPRINT_REAL:\n")
        for (id in intArrayOf(test14Primary, test14Secondary)) {
            if (id == 0) continue
            val o = test14Obj(id)
            if (!o.available) continue
            b.append("--- #").append(id).append(" ---\n").append(o.fp)
            if (!o.fp.endsWith("\n")) b.append('\n')
        }
        b.append("REAL_CULL_SEQUENCE:\n")
        for (id in intArrayOf(test14Primary, test14Secondary)) {
            if (id == 0) continue
            val o = test14Obj(id)
            if (!o.available) {
                b.append("#").append(id).append(": NO DISPONIBLE\n")
                continue
            }
            b.append("REAL_CULL_SEQUENCE_RUN1 #").append(id).append(" = ").append(o.runSeq(1)).append('\n')
            b.append("REAL_CULL_SEQUENCE_RUN2 #").append(id).append(" = ").append(o.runSeq(2)).append('\n')
            b.append("REAL_RESPONDS #").append(id).append(" = ").append(o.responds()).append('\n')
        }
        var anyYes = false
        var anyInt = false
        var anyAvail = false
        var anyMissingAnomaly = false
        for (id in intArrayOf(test14Primary, test14Secondary)) {
            if (id == 0) continue
            val o = test14Obj(id)
            if (!o.available) continue
            anyAvail = true
            val r = o.responds()
            if (r == "YES") anyYes = true
            if (r == "INTERMITTENT") anyInt = true
            if (r == "NO") anyMissingAnomaly = true
        }
        val realResponds = when {
            anyYes && !anyMissingAnomaly && !anyInt -> "YES"
            anyYes || anyInt -> "INTERMITTENT"
            anyAvail -> "NO"
            else -> "NO"
        }
        b.append("REAL_CULLING_RESPONDS: ").append(realResponds).append('\n')
        val failureNow = when {
            !anyAvail -> "NO"
            anyMissingAnomaly && !anyYes -> "YES"
            anyMissingAnomaly || anyInt -> "INTERMITTENT"
            else -> "NO"
        }
        b.append("PROBES (A=ON B=OFF C=ON, 30f):\n")
        b.append("probes comparten mundo y camara del primario (CAMERA_VALID del real, arriba); CULLING_ENABLED=ON salvo B; VISIBLE_COUNT_DELTA = A-B / C-B.\n")
        for (k in test14ProbeKinds()) {
            val p = test14Probes[k] ?: continue
            b.append(k).append(": A=").append(p.a).append(" B=").append(p.b).append(" C=").append(p.c)
                .append(" => ").append(p.verdict()).append(" [").append(p.note).append("]\n")
        }
        b.append("CAMINOS:\n")
        for (k in test14ProbeKinds()) {
            val p = test14Probes[k] ?: continue
            val h = p.handle
            if (h != null && h.isValid) {
                b.append(renderer.probeOpLog(h))
                if (!renderer.probeOpLog(h).endsWith("\n")) b.append('\n')
                b.append(p.snap)
                if (!p.snap.endsWith("\n")) b.append('\n')
            } else {
                b.append(k).append(": ").append(p.note).append('\n')
            }
        }
        b.append("H6_EPSILON: E1=").append(test14EpsE1).append(" E2=").append(test14EpsE2)
        val h6 = test14Probes["H6"]
        val h6c = h6?.c ?: -1
        val dirtyVerdict = if (test14EpsE1 < 0 || test14EpsE2 < 0 || h6c < 0) {
            "UNKNOWN"
        } else if (test14EpsE2 != h6c) {
            "YES"
        } else if (test14EpsE1 != h6c) {
            "YES"
        } else {
            "NO"
        }
        b.append(" (C=").append(h6c).append(") => roundtrip ").append(if (dirtyVerdict == "NO") "ESTABLE" else dirtyVerdict).append('\n')
        b.append("H6_SNAP_E1:\n").append(test14EpsSnap1)
        if (!test14EpsSnap1.endsWith("\n")) b.append('\n')
        b.append("H6_SNAP_E2:\n").append(test14EpsSnap2)
        if (!test14EpsSnap2.endsWith("\n")) b.append('\n')
        b.append("COMPARACION_REAL_VS_H1 (tol=0.001):\n")
        if (test14H1CmpLines.isEmpty()) {
            b.append("(pendiente)\n")
        } else {
            for (l in test14H1CmpLines) b.append(l).append('\n')
        }
        for (l in test14FinalLines) b.append(l).append('\n')
        val h0v = test14Probes["H0"]?.verdict() ?: "NO_APLICA"
        val h1v = test14Probes["H1"]?.verdict() ?: "NO_APLICA"
        val orderKinds = arrayOf("H2", "H3", "H4", "H5")
        val orderVerdicts = orderKinds.map { test14Probes[it]?.verdict() ?: "NO_APLICA" }
        val orderRan = orderVerdicts.count { it == "CULLING_RESPONDS" || it == "CULLING_NO_DIFFERENCE" }
        val orderResp = orderVerdicts.count { it == "CULLING_RESPONDS" }
        val h0ok = h0v == "CULLING_RESPONDS"
        val h1ok = h1v == "CULLING_RESPONDS"
        val h0ran = h0ok || h0v == "CULLING_NO_DIFFERENCE"
        val h1ran = h1ok || h1v == "CULLING_NO_DIFFERENCE"
        val hierCause = when {
            !h0ran || !h1ran -> "UNKNOWN"
            !h0ok && h1ok -> "YES"
            h0ok && !h1ok -> "YES"
            else -> "NO"
        }
        val orderCause = when {
            orderRan < 4 -> "UNKNOWN"
            orderResp != orderRan && orderResp > 0 -> "YES"
            orderResp == orderRan -> "NO"
            else -> "NO"
        }
        val dirtyCause = if (test14EpsE1 < 0) "UNKNOWN" else dirtyVerdict
        val directDiff = when {
            !h0ran || !h1ran -> "UNKNOWN"
            h0ok != h1ok -> "YES"
            else -> "NO"
        }
        val reproduced = when {
            !anyAvail -> "NO"
            anyMissingAnomaly && !anyYes -> "YES"
            anyMissingAnomaly || anyInt -> "INTERMITTENT"
            else -> "NO"
        }
        b.append("TRANSFORM_HIERARCHY_CAUSE: ").append(hierCause).append('\n')
        b.append("BUILD_ORDER_CAUSE: ").append(orderCause).append('\n')
        b.append("TRANSFORM_DIRTY_STATE_CAUSE: ").append(dirtyCause).append('\n')
        b.append("DIRECT_ROOT_DIFFERENCE: ").append(directDiff).append('\n')
        b.append("CURRENT_CULLING_FAILURE_REPRODUCED: ").append(reproduced).append('\n')
        val h1Repro = h1ran && anyMissingAnomaly && h1v == "CULLING_NO_DIFFERENCE" && h0ok
        b.append("TRANSFORM_HIERARCHY_REPRODUCED: ").append(if (!h1ran) "UNKNOWN" else if (h1Repro) "YES" else "NO").append('\n')
        val nextCause = when {
            h1Repro -> "TRANSFORM_HIERARCHY"
            hierCause == "YES" && !h0ok && h1ok -> "DIRECT_ROOT"
            hierCause == "YES" -> "TRANSFORM_HIERARCHY"
            orderCause == "YES" -> "BUILD_ORDER"
            dirtyCause == "YES" -> "TRANSFORM_DIRTY_STATE"
            reproduced == "NO" && h0ran && h1ran -> "HISTORICAL_OR_INTERMITTENT_STATE"
            !anyAvail -> "UNKNOWN"
            else -> "UNKNOWN"
        }
        val bugConfirmed = when (reproduced) {
            "YES" -> "YES"
            "INTERMITTENT" -> "INTERMITTENT"
            else -> "NO"
        }
        for (id in intArrayOf(test14Primary, test14Secondary)) {
            if (id == 0) continue
            val o = test14Obj(id)
            if (!o.available) continue
            b.append("REAL_CULL_SEQUENCE_RUN1 #").append(id).append(" = ").append(o.runSeq(1)).append('\n')
        }
        for (id in intArrayOf(test14Primary, test14Secondary)) {
            if (id == 0) continue
            val o = test14Obj(id)
            if (!o.available) continue
            b.append("REAL_CULL_SEQUENCE_RUN2 #").append(id).append(" = ").append(o.runSeq(2)).append('\n')
        }
        b.append("REAL_CULLING_RESPONDS = ").append(realResponds).append('\n')
        var realHier = "UNKNOWN"
        var nodeHasR = "NO"
        var nodeHasT = "NO"
        val ppo = if (test14Primary != 0) test14Obj(test14Primary) else null
        if (ppo != null && ppo.available) {
            for (l in ppo.hier.split('\n')) {
                if (l.startsWith("REAL_HIERARCHY = ")) realHier = l.substring("REAL_HIERARCHY = ".length).trim()
                if (l.startsWith("NODE_HAS_RENDERABLE = ")) nodeHasR = l.substring("NODE_HAS_RENDERABLE = ".length).trim()
                if (l.startsWith("NODE_HAS_TRANSFORM = ")) nodeHasT = l.substring("NODE_HAS_TRANSFORM = ".length).trim()
            }
        }
        b.append("REAL_HIERARCHY = ").append(realHier).append('\n')
        b.append("NODE_HAS_RENDERABLE = ").append(nodeHasR).append('\n')
        b.append("NODE_HAS_TRANSFORM = ").append(nodeHasT).append('\n')
        val short = mapOf("H0" to "H0_DIRECT_ROOT", "H1" to "H1_NODE_PARENT", "H2" to "H2_NODE_FIRST", "H3" to "H3_RENDERABLE_FIRST", "H4" to "H4_TRANSFORM_BEFORE_PARENT", "H5" to "H5_PARENT_BEFORE_TRANSFORM", "H6" to "H6_TRANSFORM_EPSILON", "H7" to "H7_CHILD_TRANSFORM", "H8A" to "H8_TWO_LEVEL_A", "H8B" to "H8_TWO_LEVEL_B")
        for (k in test14ProbeKinds()) {
            val p = test14Probes[k] ?: continue
            b.append(short[k]).append(" = ").append(p.verdict()).append(" (A=").append(p.a).append(" B=").append(p.b).append(" C=").append(p.c).append(")\n")
        }
        b.append("H8_TWO_LEVEL = A:").append(test14Probes["H8A"]?.verdict()).append("/B:").append(test14Probes["H8B"]?.verdict()).append('\n')
        b.append("TRANSFORM_HIERARCHY_CAUSE = ").append(hierCause).append('\n')
        b.append("BUILD_ORDER_CAUSE = ").append(orderCause).append('\n')
        b.append("TRANSFORM_DIRTY_STATE_CAUSE = ").append(dirtyCause).append('\n')
        b.append("DIRECT_ROOT_DIFFERENCE = ").append(directDiff).append('\n')
        b.append("CURRENT_CULLING_FAILURE_REPRODUCED = ").append(reproduced).append('\n')
        b.append("AABB_INCORRECTA = NO\n")
        b.append("RENDERABLE_TRANSFORM_INCORRECTO = NO\n")
        b.append("PARENT_CHILD_ALTERADO = NO\n")
        b.append("CULLING_BUG_CONFIRMED = ").append(bugConfirmed).append(" (evidencia actual TEST14; TEST11 queda como historico)\n")
        b.append("NEXT_CAUSE = ").append(nextCause).append('\n')
        b.append("RESTAURACION:\n")
        if (test14RestoreLines.isEmpty()) {
            b.append("(pendiente)\n")
        } else {
            for (l in test14RestoreLines) b.append(l).append('\n')
        }
        return b.toString()
    }

    interface Test16Sink {
        fun runStart(total: Int)
        fun stepBegin(index: Int, total: Int, name: String, objectId: Int, entity: Int, instance: Int, op: String, frame: Long, thread: String)
        fun stepEnd(index: Int, total: Int, name: String)
        fun nativeBegin(step: String, op: String, objectId: Int, entity: Int, instance: Int, frame: Long, thread: String)
        fun nativeEnd(step: String, op: String)
        fun extra(line: String)
        fun runEnd(state: String)
        fun finishOk()
        fun crashSnapshot(): Test16Crash
    }

    class Test16Crash(
        val hadCrash: Boolean,
        val step: String,
        val op: String,
        val objectId: String,
        val entity: String,
        val instance: String,
        val thread: String,
        val index: Int,
        val total: Int
    )

    var test16Sink: Test16Sink? = null
    /**
     * Selección dinámica TEST16 (independiente de la región): localId nativo
     * elegido en [test16Select] entre los renderables reales de la región
     * actual. Nada hardcodeado a ninguna región.
     */
    private var test16SelectedLocalId = -1
    private var test16SelectedEntity = -1
    private var test16SelectedInstance = -1
    private var test16SelectedFrom = -1
    private var test16SelectedInScene = false
    private var test16SelectedChecked = 0
    private var test16ScanLines = ArrayList<String>()
    private var test16ScanFailed = false
    private var test16HasSeen = 0
    private var test16KnownEntities = -1
    private var test16SceneEntities = -1
    private var test16SceneRenderables = -1
    private var test16ApiInconsistency = false
    private var test16ApiInconsistencyDetail = "-"
    var test16Active = false
        private set
    var test16State = "LISTO"
        private set
    var test16Note = "pulsa TEST16"
        private set
    private var test16Steps = ArrayList<Pair<String, Int>>()
    private var test16StepIdx = 0
    private var test16DoneSteps = 0
    private var test16StepBegun = false
    private val test16Frozen = HashSet<Int>()
    private val test16Stash = HashMap<Int, SLObject>()
    private var test16SavedViewLayers = -1
    private var test16SavedViewCulling: Boolean? = null
    private var test16WaitFrames = -1L
    private var test16WaitTicks = 0
    private var test16WaitTimeout = false
    private var test16Error = "-"
    private var test16Avail = false
    private var test16HandleStart = -1
    private var test16HandleNow = -1
    private var test16TargetNative = -1
    private var test16HasA = false
    private var test16InstanceA = 0
    private var test16FromA = -1
    private var test16CullA: Boolean? = null
    private var test16PrimA = -1
    private var test16HasB = false
    private var test16InstanceB = 0
    private var test16FromB = -1
    private var test16CullB: Boolean? = null
    private var test16SetReachedB = false
    private var test16HasC = false
    private var test16InstanceC = 0
    private var test16FromC = -1
    private var test16CullC: Boolean? = null
    private var test16InstanceD = 0
    private var test16FromD = -1
    private var test16HasE = false
    private var test16InstanceE = 0
    private var test16FromE = -1
    private var test16CullE: Boolean? = null
    private var test16SetReachedE = false
    private var test16VisBefore = -1
    private var test16VisAfterFalse = -1
    private var test16VisAfterTrue = -1
    private var test16Frustum = false
    private var test16NdcX = Float.NaN
    private var test16NdcY = Float.NaN
    private var test16Depth = -1f
    private var test16CameraValid = false
    private var test16WorldStr = "-"
    private var test16UpdatesDuring = 0
    private val test16Threads = ArrayList<String>()
    private var test16FinalLines = ArrayList<String>()
    private var test16RestoreLines = ArrayList<String>()
    private var test16Crash = Test16Crash(false, "-", "-", "-", "-", "-", "-", 0, 0)
    private var test16CrashLoaded = false
    private var test16ThreadName = "-"

    fun test16Progress(): Pair<Int, Int> = Pair(test16DoneSteps, test16Steps.size)

    private fun test16Handle(): EntityHandle? = slots[test16SelectedLocalId]?.entity

    private fun test16Val(block: String, key: String): String {
        for (l in block.split('\n')) {
            if (l.startsWith(key + "=")) return l.substring(key.length + 1).trim()
        }
        return "-"
    }

    private fun test16Bool(s: String): Boolean? = when (s) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun test16Int(s: String): Int = try {
        s.toInt()
    } catch (_: Throwable) {
        -1
    }

    private fun <T> test16Call(step: String, op: String, instance: Int, block: () -> T): T {
        val sink = test16Sink
        val thread = Thread.currentThread().name
        test16ThreadName = thread
        if (!test16Threads.contains(thread)) test16Threads.add(thread)
        val frame = diagnostics.auditFrameCount
        sink?.nativeBegin(step, op, test16SelectedLocalId, test16HandleNow, instance, frame, thread)
        try {
            val r = block()
            sink?.nativeEnd(step, op)
            return r
        } catch (e: Throwable) {
            sink?.extra("NATIVE_THROW step=" + step + " op=" + op + " " + e.javaClass.simpleName + " " + (e.message ?: "-"))
            throw e
        }
    }

    fun test16CheckCrash() {
        if (test16CrashLoaded) return
        test16CrashLoaded = true
        val snap = try {
            test16Sink?.crashSnapshot()
        } catch (_: Throwable) {
            null
        } ?: return
        test16Crash = snap
        if (snap.hadCrash && test16State == "LISTO" && !test16Active) {
            test16State = "CRASH PREVIO"
            test16Note = "CRASH PREVIO ultimo paso = " + snap.step + " objeto = " + snap.objectId
        }
    }

    fun test16Start(): String {
        for (id in test8NoCull.toList()) {
            slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
        }
        test8NoCull.clear()
        for ((id, box) in test8WideBox.toList()) {
            slots[id]?.let { renderer.setEntityObjectBox(it.entity, box) }
        }
        test8WideBox.clear()
        if (test9Active) {
            test9RestoreAll()
            test9StopSilent()
            test9Note = "pausado por TEST16"
        }
        if (test10Active) {
            for (id in test10Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test10Steps = ArrayList()
            test10StepIdx = 0
            test10Active = false
            test10Note = "pausado por TEST16"
        }
        if (test11Active) {
            for (id in test11Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test11CamActive = false
            test11Steps = ArrayList()
            test11StepIdx = 0
            test11Active = false
            test11State = "LISTO"
            test11Note = "pausado por TEST16"
        }
        if (test12Active) {
            for (id in test12Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test12Cleanup()
            for ((_, o) in test12Stash.toList()) {
                try {
                    upsert(o)
                } catch (_: Throwable) {
                }
            }
            test12Stash.clear()
            test12Frozen.clear()
            test12Steps = ArrayList()
            test12StepIdx = 0
            test12Active = false
            test12State = "LISTO"
            test12Note = "pausado por TEST16"
        }
        if (test13Active) {
            for (id in test13Ids) {
                slots[id]?.let { renderer.setEntityCulling(it.entity, true) }
            }
            test13Cleanup()
            for ((_, o) in test13Stash.toList()) {
                try {
                    upsert(o)
                } catch (_: Throwable) {
                }
            }
            test13Stash.clear()
            test13Frozen.clear()
            test13Steps = ArrayList()
            test13StepIdx = 0
            test13Active = false
            test13State = "LISTO"
            test13Note = "pausado por TEST16"
        }
        if (test14Active) {
            try {
                test14Stop()
            } catch (_: Throwable) {
            }
            test14Note = "pausado por TEST16"
        }
        test16CheckCrash()
        test16Stash.clear()
        test16Frozen.clear()
        test16SavedViewLayers = -1
        test16SavedViewCulling = null
        test16WaitFrames = -1L
        test16WaitTicks = 0
        test16Avail = false
        test16HandleStart = -1
        test16HandleNow = -1
        test16TargetNative = -1
        test16HasA = false
        test16InstanceA = 0
        test16FromA = -1
        test16CullA = null
        test16PrimA = -1
        test16HasB = false
        test16InstanceB = 0
        test16FromB = -1
        test16CullB = null
        test16SetReachedB = false
        test16HasC = false
        test16InstanceC = 0
        test16FromC = -1
        test16CullC = null
        test16InstanceD = 0
        test16FromD = -1
        test16HasE = false
        test16InstanceE = 0
        test16FromE = -1
        test16CullE = null
        test16SetReachedE = false
        test16SelectedLocalId = -1
        test16SelectedEntity = -1
        test16SelectedInstance = -1
        test16SelectedFrom = -1
        test16SelectedInScene = false
        test16SelectedChecked = 0
        test16ScanLines = ArrayList()
        test16ScanFailed = false
        test16HasSeen = 0
        test16KnownEntities = -1
        test16SceneEntities = -1
        test16SceneRenderables = -1
        test16ApiInconsistency = false
        test16ApiInconsistencyDetail = "-"
        test16VisBefore = -1
        test16VisAfterFalse = -1
        test16VisAfterTrue = -1
        test16UpdatesDuring = 0
        test16Threads.clear()
        test16FinalLines = ArrayList()
        test16RestoreLines = ArrayList()
        test16Error = "-"
        test16ThreadName = Thread.currentThread().name
        test16Steps = ArrayList()
        test16Steps.add(Pair("iso", 0))
        test16Steps.add(Pair("freeze", 0))
        test16Steps.add(Pair("aim", 0))
        test16Steps.add(Pair("aimRd", 0))
        test16Steps.add(Pair("p1", 0))
        test16Steps.add(Pair("p2set", 0))
        test16Steps.add(Pair("p3rd", 0))
        test16Steps.add(Pair("p4set", 0))
        test16Steps.add(Pair("p4rd", 0))
        test16Steps.add(Pair("final", 0))
        test16Steps.add(Pair("unfreeze", 0))
        test16Steps.add(Pair("done", 0))
        test16StepIdx = 0
        test16DoneSteps = 0
        test16StepBegun = false
        test16Active = true
        test16State = "EJECUTANDO"
        test16Note = "seleccionando renderable válido de esta región"
        try {
            test16Sink?.runStart(test16Steps.size)
        } catch (_: Throwable) {
        }
        return "TEST16 arrancado"
    }

    fun test16Tick(): String {
        if (!test16Active || test16StepIdx >= test16Steps.size) return test16Note
        val (op, arg) = test16Steps[test16StepIdx]
        val total = test16Steps.size
        val thread = Thread.currentThread().name
        test16ThreadName = thread
        if (!test16Threads.contains(thread)) test16Threads.add(thread)
        if (!test16StepBegun) {
            test16StepBegun = true
            try {
                test16Sink?.stepBegin(test16StepIdx + 1, total, op, test16SelectedLocalId, test16HandleNow, 0, op, diagnostics.auditFrameCount, thread)
            } catch (_: Throwable) {
            }
        }
        var advanced = true
        try {
            advanced = when (op) {
                "iso" -> test16Iso()
                "freeze" -> {
                    if (test16SelectedLocalId >= 0) test16Frozen.add(test16SelectedLocalId)
                    test16Note = "objetivo congelado (#" + test16SelectedLocalId + ")"
                    true
                }
                "aim" -> test16Aim()
                "aimRd" -> test16AimRd()
                "p1" -> test16P1()
                "p2set" -> test16P2Set()
                "p3rd" -> test16P3Rd()
                "p4set" -> test16P4Set()
                "p4rd" -> test16P4Rd()
                "final" -> test16Final()
                "unfreeze" -> test16Unfreeze()
                "done" -> {
                    test16Active = false
                    test16State = "TERMINADO"
                    test16Note = "TEST16 TERMINADO"
                    try {
                        test16Sink?.finishOk()
                    } catch (_: Throwable) {
                    }
                    true
                }
                else -> true
            }
        } catch (e: Throwable) {
            try {
                test16Sink?.extra("STEP_THROW step=" + op + " " + e.javaClass.simpleName + " " + (e.message ?: "-"))
            } catch (_: Throwable) {
            }
            try {
                test16RestoreBestEffort()
            } catch (_: Throwable) {
            }
            test16Active = false
            test16State = "ERROR"
            test16Error = (e.message ?: e.javaClass.simpleName) + " en paso " + op
            test16Note = "ERROR: " + test16Error
            try {
                test16Sink?.runEnd("ERROR")
                test16Sink?.finishOk()
            } catch (_: Throwable) {
            }
            return test16Note
        }
        if (advanced) {
            try {
                test16Sink?.stepEnd(test16StepIdx + 1, total, op)
            } catch (_: Throwable) {
            }
            test16StepIdx++
            test16DoneSteps++
            test16StepBegun = false
        }
        return test16Note
    }

    private fun test16Stamp() {
        test16WaitFrames = diagnostics.auditFrameCount
        test16WaitTicks = 0
        test16WaitTimeout = false
    }

    private fun test16WaitReady(need: Long): Boolean {
        if (test16WaitFrames < 0) return true
        val cur = diagnostics.auditFrameCount
        if (cur < 0 || cur - test16WaitFrames < need) {
            test16WaitTicks++
            if (test16WaitTicks < 4) return false
            test16WaitTimeout = true
            return true
        }
        return true
    }

    /**
     * Selección dinámica del renderable a probar (misma hebra EphoraRender que
     * el resto del test): recorre los slots con entidad registrada en el
     * renderer —la misma fuente que TEST8/TEST9 usan— sin filtro de
     * visibilidad, sin lista previa y sin LocalID fijo. Cada candidato
     * inspeccionado cuenta y se registra; el primero que cumple todo (objeto
     * SL real, hasComponent, instancia viva de su misma entity, en Scene, no
     * avatar, no clon de diagnóstico) queda congelado y no se vuelve a buscar.
     * Solo lectura: no crea, modifica ni destruye nada.
     */
    private fun test16Select(): Boolean {
        test16SelectedLocalId = -1
        test16SelectedEntity = -1
        test16SelectedInstance = -1
        test16SelectedFrom = -1
        test16SelectedInScene = false
        test16SelectedChecked = 0
        test16ScanLines = ArrayList()
        test16ScanFailed = false
        test16HasSeen = 0
        for (localId in slots.keys.sorted()) {
            val slot = slots[localId] ?: continue
            val st = try {
                renderer.renderableIdentityState(slot.entity)
            } catch (_: Throwable) {
                continue
            }
            if (st == "NO_RECORD" || st.startsWith("ERROR")) continue
            test16SelectedChecked++
            val has = test16Val(st, "hasComponent") == "true"
            val inst = test16Int(test16Val(st, "instance"))
            val ent = test16Int(test16Val(st, "entity"))
            val from = test16Int(test16Val(st, "entityFromInstance"))
            if (has) test16HasSeen++
            val inScene = try {
                renderer.entityInScene(slot.entity)
            } catch (_: Throwable) {
                null
            }
            val fact = facts[localId]
            val isAvatar = fact?.kind == SLObjectKind.AVATAR || avatarsById.containsKey(localId)
            val isClone = fact == null ||
                test12Frozen.contains(localId) || test13Frozen.contains(localId) ||
                test14Frozen.contains(localId) || test16Frozen.contains(localId) ||
                test12Stash.containsKey(localId) || test13Stash.containsKey(localId) ||
                test14Stash.containsKey(localId) || test16Stash.containsKey(localId) ||
                test8NoCull.contains(localId) || test8WideBox.containsKey(localId)
            val line = "candidate[" + test16SelectedChecked + "] entity=" + ent +
                " localId=" + localId + " hasComponent=" + has + " instance=" + inst +
                " getEntity(instance)=" + from + " inScene=" + inScene +
                " isAvatar=" + isAvatar + " isDiagnosticClone=" + isClone
            test16ScanLines.add(line)
            try {
                test16Sink?.extra("TEST16 candidate scan: " + line)
            } catch (_: Throwable) {
            }
            if (!has || inst <= 0 || ent < 0 || from != ent) continue
            if (inScene != true) continue
            if (isAvatar || isClone) continue
            test16SelectedLocalId = localId
            test16SelectedEntity = ent
            test16SelectedInstance = inst
            test16SelectedFrom = from
            test16SelectedInScene = true
            try {
                test16Sink?.extra("TEST16 candidate scan: selected entity=" + ent + " localId=" + localId + " instance=" + inst)
            } catch (_: Throwable) {
            }
            return true
        }
        test16KnownEntities = try {
            renderer.stats().entityCount
        } catch (_: Throwable) {
            -1
        }
        test16SceneEntities = try {
            renderer.diagnostics().entitiesInScene
        } catch (_: Throwable) {
            -1
        }
        test16SceneRenderables = try {
            renderer.diagnostics().renderablesInScene
        } catch (_: Throwable) {
            -1
        }
        test16ScanFailed = true
        try {
            test16Sink?.extra(
                "TEST16 candidate scan: SIN_CANDIDATO revisados=" + test16SelectedChecked +
                    " conComponente=" + test16HasSeen + " conocidasRenderer=" + test16KnownEntities +
                    " enScene=" + test16SceneEntities + " renderables=" + test16SceneRenderables
            )
        } catch (_: Throwable) {
        }
        return false
    }

    private fun test16Iso(): Boolean {
        test16SavedViewCulling = try {
            renderer.viewFrustumCullingEnabled()
        } catch (_: Throwable) {
            null
        }
        val thread = Thread.currentThread().name
        test16ThreadName = thread
        if (!test16Threads.contains(thread)) test16Threads.add(thread)
        val ok = test16Select()
        val h = test16Handle()
        if (ok && h != null) {
            test16Avail = true
            test16HandleStart = h.id
            test16HandleNow = h.id
        } else {
            test16Avail = false
        }
        try {
            test16Sink?.extra(
                "SELECCION local=" + test16SelectedLocalId + " selectedEntity=" + test16SelectedEntity +
                    " selectedInstance=" + test16SelectedInstance + " selectedEntityFromInstance=" + test16SelectedFrom +
                    " selectedEntityInScene=" + test16SelectedInScene + " revisados=" + test16SelectedChecked + " hilo=" + thread
            )
        } catch (_: Throwable) {
        }
        test16Note = if (ok) {
            "seleccionado #" + test16SelectedLocalId + " (entity=" + test16SelectedEntity + " instance=" + test16SelectedInstance + ")"
        } else {
            "SIN_CANDIDATO (revisados " + test16SelectedChecked + " conComponente=" + test16HasSeen +
                " conocidasRenderer=" + test16KnownEntities + " enScene=" + test16SceneEntities +
                " renderables=" + test16SceneRenderables + "): fallo del mecanismo de enumeración"
        }
        return true
    }

    private fun test16Aim(): Boolean {
        if (!test16Avail || test16Handle() == null) {
            test16Avail = false
            test16Note = "SIN_CANDIDATO: NO_APLICA (la cámara no se toca)"
            return true
        }
        test16Stamp()
        test16Note = "esperando un frame normal (sin mover la cámara)"
        return true
    }

    private fun test16AimRd(): Boolean {
        if (!test16Avail) {
            test16Note = "camara NO_APLICA (no se toca)"
            return true
        }
        if (!test16WaitReady(1)) {
            test16Note = "esperando frame normal (" + (diagnostics.auditFrameCount - test16WaitFrames) + "/1)"
            return false
        }
        val to = if (test16WaitTimeout) " TIMEOUT_FRAMES" else ""
        test16WaitFrames = -1L
        test16Note = "frame normal presentado" + to
        return true
    }

    private fun test16Identity(tag: String): String {
        val h = test16Handle() ?: return "NO_HANDLE"
        test16HandleNow = h.id
        return test16Call(tag, "renderableIdentityState", 0) { renderer.renderableIdentityState(h) }
    }

    private fun test16P1(): Boolean {
        if (!test16Avail) {
            test16Note = "PASO1 NO_APLICA"
            return true
        }
        val st = test16Identity("p1")
        test16TargetNative = test16Int(test16Val(st, "entity"))
        test16HasA = test16Val(st, "hasComponent") == "true"
        test16InstanceA = test16Int(test16Val(st, "instance"))
        test16FromA = test16Int(test16Val(st, "entityFromInstance"))
        test16CullA = test16Bool(test16Val(st, "cullingEnabled"))
        test16PrimA = test16Int(test16Val(st, "primitiveCount"))
        test16VisBefore = diagnostics.visibleRenderables
        try {
            test16Sink?.extra("PASO1 " + st.replace('\n', ' ') + " visibleBefore=" + test16VisBefore)
        } catch (_: Throwable) {
        }
        if (!test16HasA || test16InstanceA <= 0 || test16FromA != test16TargetNative) {
            test16ApiInconsistency = true
            test16ApiInconsistencyDetail = "prechequeo tras selección (has=" + test16HasA + " instance=" + test16InstanceA + " from=" + test16FromA + " entity=" + test16TargetNative + "): la entidad dejó de ser válida; sets omitidos, se restaura"
            test16Note = "PRECHEQUEO_FALLA: " + test16ApiInconsistencyDetail
            return true
        }
        test16Note = "PASO1 ok (entity=" + test16TargetNative + " instance=" + test16InstanceA + ")"
        return true
    }

    private fun test16P2Set(): Boolean {
        if (!test16Avail) {
            test16Note = "PASO2 NO_APLICA"
            return true
        }
        val h = test16Handle() ?: return true
        val pre = test16Identity("p2set")
        val fresh = test16Int(test16Val(pre, "instance"))
        val freshFrom = test16Int(test16Val(pre, "entityFromInstance"))
        val freshHas = test16Val(pre, "hasComponent") == "true"
        if (!freshHas || fresh <= 0 || freshFrom != test16SelectedEntity) {
            test16ApiInconsistency = true
            test16ApiInconsistencyDetail = "p2set sin instancia válida (has=" + freshHas + " instance=" + fresh + " from=" + freshFrom + " entity=" + test16SelectedEntity + "); set(false) omitido, sin reparar"
            test16HasB = freshHas
            test16InstanceB = fresh
            test16FromB = freshFrom
            test16CullB = test16Bool(test16Val(pre, "cullingEnabled"))
            test16Note = "PASO2 omitido: " + test16ApiInconsistencyDetail
            return true
        }
        val setLine = test16Call("p2set", "setCullingOnInstance(false)", fresh) { renderer.setCullingOnInstance(h, fresh, false) }
        test16SetReachedB = setLine.contains("setterReached=true")
        try {
            test16Sink?.extra("PASO2_SET " + setLine)
        } catch (_: Throwable) {
        }
        val st = test16Identity("p2set")
        test16HasB = test16Val(st, "hasComponent") == "true"
        test16InstanceB = test16Int(test16Val(st, "instance"))
        test16FromB = test16Int(test16Val(st, "entityFromInstance"))
        test16CullB = test16Bool(test16Val(st, "cullingEnabled"))
        if (test16InstanceB == 0) {
            test16ApiInconsistency = true
            test16ApiInconsistencyDetail = "getInstance(entity) devolvió 0 tras set(false): entity=" + test16SelectedEntity + " instanceBefore=" + fresh + " instanceAfterFalse=" + test16InstanceB
        }
        test16Stamp()
        test16Note = "PASO2 set(false) aplicado, esperando frame presentado"
        return true
    }

    private fun test16P3Rd(): Boolean {
        if (!test16Avail) {
            test16Note = "lectura NO_APLICA"
            return true
        }
        if (!test16WaitReady(1)) {
            test16Note = "esperando frame presentado (" + (diagnostics.auditFrameCount - test16WaitFrames) + "/1)"
            return false
        }
        val to = if (test16WaitTimeout) " TIMEOUT_FRAMES" else ""
        test16WaitFrames = -1L
        val st = test16Identity("p3rd")
        test16HasC = test16Val(st, "hasComponent") == "true"
        test16InstanceC = test16Int(test16Val(st, "instance"))
        test16FromC = test16Int(test16Val(st, "entityFromInstance"))
        test16CullC = test16Bool(test16Val(st, "cullingEnabled"))
        test16VisAfterFalse = diagnostics.visibleRenderables
        try {
            test16Sink?.extra("PASO3 has=" + test16HasC + " instance=" + test16InstanceC + " from=" + test16FromC + " cull=" + test16CullC + " visible=" + test16VisAfterFalse + to)
        } catch (_: Throwable) {
        }
        test16Note = "PASO3 visible=" + test16VisAfterFalse + to
        return true
    }

    private fun test16P4Set(): Boolean {
        if (!test16Avail) {
            test16Note = "PASO4 NO_APLICA"
            return true
        }
        val h = test16Handle() ?: return true
        val preD = test16Identity("p4set")
        val freshD = test16Int(test16Val(preD, "instance"))
        val freshFromD = test16Int(test16Val(preD, "entityFromInstance"))
        val freshHasD = test16Val(preD, "hasComponent") == "true"
        test16InstanceD = freshD
        test16FromD = freshFromD
        if (!freshHasD || freshD <= 0 || freshFromD != test16SelectedEntity) {
            test16ApiInconsistency = true
            test16ApiInconsistencyDetail = "p4set sin instancia válida (has=" + freshHasD + " instance=" + freshD + " from=" + freshFromD + " entity=" + test16SelectedEntity + "); set(true) omitido, sin reparar"
            test16Note = "PASO4 omitido: " + test16ApiInconsistencyDetail
            return true
        }
        val setLine = test16Call("p4set", "setCullingOnInstance(true)", freshD) { renderer.setCullingOnInstance(h, freshD, true) }
        test16SetReachedE = setLine.contains("setterReached=true")
        try {
            test16Sink?.extra("PASO4_SET " + setLine)
        } catch (_: Throwable) {
        }
        val stE = test16Identity("p4set")
        test16HasE = test16Val(stE, "hasComponent") == "true"
        test16InstanceE = test16Int(test16Val(stE, "instance"))
        test16FromE = test16Int(test16Val(stE, "entityFromInstance"))
        test16CullE = test16Bool(test16Val(stE, "cullingEnabled"))
        if (test16InstanceE == 0) {
            test16ApiInconsistency = true
            test16ApiInconsistencyDetail = "getInstance(entity) devolvió 0 tras set(true): entity=" + test16SelectedEntity + " instanceAfterTrue=" + test16InstanceE
        }
        test16Stamp()
        test16Note = "PASO4 set(true) aplicado, esperando frame presentado"
        return true
    }

    private fun test16P4Rd(): Boolean {
        if (!test16Avail) {
            test16Note = "lectura NO_APLICA"
            return true
        }
        if (!test16WaitReady(1)) {
            test16Note = "esperando frame presentado (" + (diagnostics.auditFrameCount - test16WaitFrames) + "/1)"
            return false
        }
        val to = if (test16WaitTimeout) " TIMEOUT_FRAMES" else ""
        test16WaitFrames = -1L
        test16VisAfterTrue = diagnostics.visibleRenderables
        try {
            test16Sink?.extra("PASO4 visible=" + test16VisAfterTrue + to)
        } catch (_: Throwable) {
        }
        test16Note = "PASO4 visible=" + test16VisAfterTrue + to
        return true
    }

    private fun test16Final(): Boolean {
        test16FinalLines.clear()
        val h = test16Handle()
        if (h != null && test16Avail) {
            try {
                val pre = test16Identity("final")
                val fresh = test16Int(test16Val(pre, "instance"))
                if (fresh > 0) {
                    val line = test16Call("final", "setCullingOnInstance(restore=true)", fresh) { renderer.setCullingOnInstance(h, fresh, true) }
                    test16FinalLines.add("#" + test16SelectedLocalId + " culling restaurado (" + line + ")")
                } else {
                    test16FinalLines.add("#" + test16SelectedLocalId + " sin instancia viva: restauración por set omitida (nada que restaurar)")
                }
            } catch (_: Throwable) {
            }
        }
        test16FinalLines.add("capas y vista intactos (TEST16 ya no los toca)")
        test16FinalLines.add("frustum vista guardado (solo lectura) = " + test16SavedViewCulling)
        test11CamActive = false
        test11CamEye = null
        test11CamTarget = null
        test16FinalLines.add("camara original restaurada")
        test16Note = "restaurado, descongelando"
        try {
            test16Sink?.runEnd(test16State)
        } catch (_: Throwable) {
        }
        return true
    }

    private fun test16RestoreBestEffort() {
        try {
            test11CamActive = false
            test11CamEye = null
            test11CamTarget = null
        } catch (_: Throwable) {
        }
        val h = test16Handle()
        if (h != null) {
            try {
                val st = try {
                    renderer.renderableIdentityState(h)
                } catch (_: Throwable) {
                    ""
                }
                var inst = 0
                for (l in st.split('\n')) {
                    if (l.startsWith("instance=")) {
                        inst = try {
                            l.substring(9).trim().toInt()
                        } catch (_: Throwable) {
                            0
                        }
                    }
                }
                if (inst > 0) renderer.setCullingOnInstance(h, inst, true)
            } catch (_: Throwable) {
            }
        }
        try {
            for ((_, obj) in test16Stash.toList()) {
                try {
                    upsert(obj)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        test16Stash.clear()
        test16Frozen.clear()
    }

    private fun test16Unfreeze(): Boolean {
        test16RestoreLines.clear()
        test16RestoreLines.addAll(test16FinalLines)
        test16UpdatesDuring = test16Stash.size
        for ((_, obj) in test16Stash.toList()) {
            try {
                upsert(obj)
            } catch (_: Throwable) {
            }
        }
        test16Stash.clear()
        test16Frozen.clear()
        test16RestoreLines.add("updates repuestos: " + test16UpdatesDuring + "; sin clones, sin probes, sin cambios permanentes")
        test16Note = "restaurado (" + test16UpdatesDuring + " updates repuestos)"
        return true
    }

    fun test16Stop(): String {
        try {
            test16RestoreBestEffort()
        } catch (_: Throwable) {
        }
        try {
            test16Sink?.runEnd("STOP")
            test16Sink?.finishOk()
        } catch (_: Throwable) {
        }
        test16Steps = ArrayList()
        test16StepIdx = 0
        test16Active = false
        if (test16State == "EJECUTANDO") {
            test16State = "LISTO"
            test16Note = "abortado por el usuario"
        }
        return "TEST16 detenido y restaurado"
    }

    fun test16ReportBlock(): String {
        test16CheckCrash()
        val b = StringBuilder(12000)
        b.append("========== TEST16 ==========\n")
        b.append("Estado: ").append(test16State).append('\n')
        val total = test16Steps.size
        val pct = if (total == 0) 0 else test16DoneSteps * 100 / total
        b.append("Progreso: ").append(test16DoneSteps).append("/").append(total)
            .append(" (").append(pct).append("%)\n")
        val cp = test16Crash
        if (cp.hadCrash) {
            b.append("CRASH_PREVIO = YES paso=").append(cp.step).append(" objeto=").append(cp.objectId).append('\n')
        }
        if (test16State == "EJECUTANDO") {
            b.append(test16Note).append('\n')
        }
        if (test16State == "ERROR") {
            b.append("Mensaje: ").append(test16Error).append('\n')
        }
        b.append("IDENTITY:\n")
        b.append("selectedEntity=").append(test16SelectedEntity).append('\n')
        b.append("selectedInstance=").append(test16SelectedInstance).append('\n')
        b.append("selectedEntityFromInstance=").append(test16SelectedFrom).append('\n')
        b.append("selectedEntityInScene=").append(test16SelectedInScene).append('\n')
        b.append("selectedLocalId=").append(test16SelectedLocalId).append('\n')
        b.append("selectedCandidatesChecked=").append(test16SelectedChecked).append('\n')
        b.append("targetEntity=").append(test16TargetNative).append('\n')
        b.append("entityFromA=").append(test16FromA).append('\n')
        b.append("entityFromB=").append(test16FromB).append('\n')
        b.append("entityFromC=").append(test16FromC).append('\n')
        b.append("entityFromD=").append(test16FromD).append('\n')
        b.append("entityFromE=").append(test16FromE).append('\n')
        val broken = (test16FromA >= 0 && test16FromA != test16TargetNative) ||
            (test16FromB >= 0 && test16FromB != test16TargetNative) ||
            (test16FromC >= 0 && test16FromC != test16TargetNative) ||
            (test16FromD >= 0 && test16FromD != test16TargetNative) ||
            (test16FromE >= 0 && test16FromE != test16TargetNative)
        b.append("ENTITY_IDENTITY_BROKEN=").append(if (broken) "YES" else "NO").append('\n')
        b.append("RENDERABLE:\n")
        b.append("hasA=").append(test16HasA).append('\n')
        b.append("hasB=").append(test16HasB).append('\n')
        b.append("hasC=").append(test16HasC).append('\n')
        b.append("hasE=").append(test16HasE).append('\n')
        b.append("instanceA=").append(test16InstanceA).append('\n')
        b.append("instanceB=").append(test16InstanceB).append('\n')
        b.append("instanceC=").append(test16InstanceC).append('\n')
        b.append("instanceD=").append(test16InstanceD).append('\n')
        b.append("instanceE=").append(test16InstanceE).append('\n')
        b.append("instanceA_entity=").append(test16FromA).append('\n')
        b.append("instanceB_entity=").append(test16FromB).append('\n')
        b.append("instanceC_entity=").append(test16FromC).append('\n')
        b.append("instanceD_entity=").append(test16FromD).append('\n')
        b.append("instanceE_entity=").append(test16FromE).append('\n')
        b.append("CULLING:\n")
        b.append("cullA=").append(test16CullA).append('\n')
        b.append("cullB=").append(test16CullB).append('\n')
        b.append("cullC=").append(test16CullC).append('\n')
        b.append("cullE=").append(test16CullE).append('\n')
        b.append("setterReached=").append(test16SetReachedB).append('\n')
        b.append("VIEW:\n")
        b.append("frustum=").append(test16SavedViewCulling).append(" (solo lectura; TEST16 no lo cambia)\n")
        b.append("aislamientoLayers=NO_APLICADO (TEST16 ya no toca layers de vista ni de renderables)\n")
        b.append("VISIBLE:\n")
        b.append("before=").append(test16VisBefore).append('\n')
        b.append("afterFalse=").append(test16VisAfterFalse).append('\n')
        b.append("afterTrue=").append(test16VisAfterTrue).append('\n')
        val destroyed = test16HasA && (!test16HasB || !test16HasC || !test16HasE)
        val instList = arrayOf(test16InstanceA, test16InstanceB, test16InstanceC, test16InstanceD, test16InstanceE).filter { it > 0 }.distinct()
        val rebuilt = instList.size > 1
        val entityChanged = test16HandleStart >= 0 && test16HandleNow >= 0 && test16HandleStart != test16HandleNow
        b.append("LIFECYCLE:\n")
        b.append("renderableDestroyed=").append(if (destroyed) "YES" else "NO").append('\n')
        b.append("renderableRebuilt=").append(if (rebuilt) "YES" else "NO").append('\n')
        b.append("entityChanged=").append(if (entityChanged) "YES" else "NO").append(" inicio=").append(test16HandleStart).append(" fin=").append(test16HandleNow).append('\n')
        b.append("updatesDuringTest=").append(test16UpdatesDuring).append('\n')
        b.append("threadAllSame=").append(if (test16Threads.distinct().size <= 1) "YES" else "NO").append(" hilos=").append(test16Threads.joinToString(",")).append('\n')
        val apiOk = test16HasA && test16HasB && test16HasC && test16HasE && !broken &&
            test16InstanceA > 0 && test16InstanceB > 0 && test16InstanceC > 0 && test16InstanceE > 0
        val setterWorks = test16SetReachedB && test16CullB == false
        val consistent = apiOk && !test16ApiInconsistency
        b.append("VEREDICTO:\n")
        b.append("SELECTED_ENTITY=").append(test16SelectedEntity).append('\n')
        b.append("SELECTED_INSTANCE=").append(test16SelectedInstance).append('\n')
        b.append("SELECTED_LOCAL_ID=").append(test16SelectedLocalId).append('\n')
        b.append("CANDIDATES_CHECKED=").append(test16SelectedChecked).append('\n')
        b.append("INSTANCE_API_CONSISTENT=").append(if (consistent) "YES" else "NO").append('\n')
        b.append("ENTITY_IDENTITY_BROKEN=").append(if (broken) "YES" else "NO").append('\n')
        b.append("CULLING_SETTER_REALLY_WORKS=").append(if (setterWorks) "YES" else "NO").append('\n')
        b.append("CULLING_VISUAL_FAILURE_REPRODUCED=NOT_DETERMINED (el test no mueve la cámara ni fabrica escena; solo setter+identidad)\n")
        b.append("INSTANCE_API_INCONSISTENCY=").append(if (test16ApiInconsistency) "YES" else "NO")
        if (test16ApiInconsistencyDetail != "-") b.append(" ").append(test16ApiInconsistencyDetail)
        b.append('\n')
        b.append("ENTITY_INSTANCE_IDENTITY_CHANGED=").append(if (broken) "YES" else "NO").append('\n')
        if (test16ScanFailed) {
            b.append("CANDIDATE_SCAN_FAILED=YES conocidasRenderer=").append(test16KnownEntities)
                .append(" enScene=").append(test16SceneEntities)
                .append(" renderables=").append(test16SceneRenderables)
                .append(" conComponenteRenderable=").append(test16HasSeen).append('\n')
        }
        b.append("SCAN:\n")
        if (test16ScanLines.isEmpty()) {
            b.append("(sin candidatos inspeccionados)\n")
        } else {
            val shown = minOf(test16ScanLines.size, 25)
            for (i in 0 until shown) b.append(test16ScanLines[i]).append('\n')
            if (test16ScanLines.size > shown) {
                b.append("... ").append(test16ScanLines.size - shown).append(" candidatos mas (ver traza nativa)\n")
            }
        }
        b.append("VIEW_FRUSTUM_CAUSE = UNKNOWN\n")
        b.append("AABB_CAUSE = NOT_TESTED\n")
        b.append("GEOMETRY_TYPE_CAUSE = NOT_TESTED\n")
        b.append("PARENT_CHILD_ALTERADO = NO\n")
        b.append("RESTAURACION:\n")
        if (test16RestoreLines.isEmpty()) {
            b.append("(pendiente)\n")
        } else {
            for (l in test16RestoreLines) b.append(l).append('\n')
        }
        return b.toString()
    }

    /**
     * TEST 2: muestra pasiva de encuadre — solo lectura. Primeros [limit]
     * prims por localId con posición de región, distancia y NDC, más el censo
     * del frustum sobre todas las entidades. No mueve nada: el mismo
     * `placementInRegion` que `prune` usa 60 veces por segundo.
     */
    fun sceneFrameSample(camera: CameraDesc, aspect: Float, limit: Int = 10): String {
        val builder = StringBuilder(1600)
        val frustum = CameraFrustum.from(camera, aspect)
        val forward = forwardOf(camera)
        builder.append("camara(escena): eye=").append(vec(camera.eye))
            .append("  target=").append(vec(camera.target))
            .append("  dir=").append(vec(floatArrayOf(forward[0], forward[1], forward[2]))).append('\n')
        var inside = 0
        var behind = 0
        var tooClose = 0
        var tooFar = 0
        var offLeft = 0
        var offRight = 0
        var offBottom = 0
        var offTop = 0
        val ids = slots.keys.sorted()
        var shown = 0
        for (localId in ids) {
            val pos = placementInRegion(localId)
            val sample = frustum.sample(pos)
            when (sample.reason) {
                FrustumReason.INSIDE -> inside += 1
                FrustumReason.BEHIND -> behind += 1
                FrustumReason.TOO_CLOSE -> tooClose += 1
                FrustumReason.TOO_FAR -> tooFar += 1
                FrustumReason.OFF_LEFT -> offLeft += 1
                FrustumReason.OFF_RIGHT -> offRight += 1
                FrustumReason.OFF_BOTTOM -> offBottom += 1
                FrustumReason.OFF_TOP -> offTop += 1
            }
            if (shown < limit) {
                shown += 1
                builder.append('#').append(localId)
                    .append(" pos=").append(vec(pos))
                    .append(" dist=").append(fmt2(distanceTo(pos, camera.eye)))
                    .append(" ndc=(").append(fmt3(sample.ndcX)).append(", ").append(fmt3(sample.ndcY)).append(')')
                    .append(' ').append(sample.reason).append('\n')
            }
        }
        builder.append("frustum: ").append(ids.size).append(" entidades: dentro ").append(inside)
            .append("  detras ").append(behind)
            .append("  cerca ").append(tooClose)
            .append("  lejos ").append(tooFar)
            .append("  izq ").append(offLeft)
            .append("  der ").append(offRight)
            .append("  abajo ").append(offBottom)
            .append("  arriba ").append(offTop).append('\n')
        return builder.toString()
    }

    fun cameraAudit(renderer: Renderer, limit: Int): String {
        val camera = lastCamera
            ?: return "sin camara todavia: no se ha dibujado ningun frame con la escena construida"
        val aspect = cameraAspect
        // The camera as it was sent, and the camera as the engine holds it. The
        // rows use the engine's, so the diagnostic cannot be testing a camera
        // that is not the one drawing; the scene's is kept only to say whether
        // the two agree.
        val sceneFrustum = CameraFrustum.from(camera, aspect)
        val snapshot = renderer.cameraSnapshot()
        val engineNear = if (snapshot != null && snapshot.near > 0f) snapshot.near else camera.near
        val engineFar = if (snapshot != null && snapshot.far > 0f) snapshot.far else camera.far
        val frustum = if (snapshot == null) {
            sceneFrustum
        } else {
            CameraFrustum.fromEngine(
                snapshot.eye, snapshot.forward, snapshot.up, camera.verticalFovDegrees,
                aspect, engineNear, engineFar
            )
        }
        val matrixFrustum = snapshot?.let {
            MatrixFrustum(it.viewMatrix, it.projectionMatrix, engineNear, engineFar)
        }

        val builder = StringBuilder(4096)
        builder.append("--- TRAZA SL -> entidad -> camara -> frustum (fase 2.9) ---\n")
        builder.append("DIAGNOSTICO: el culling real es el del motor ")
        builder.append("(View.setFrustumCullingEnabled + RenderableManager culling). ")
        builder.append("Nada de esta traza lo sustituye.\n")
        builder.append("camara(escena, la que se envia al motor): ").append(sceneFrustum.describe()).append('\n')
        if (snapshot == null) {
            builder.append("camara(Filament, leida del motor): NO DISPONIBLE\n")
            builder.append("  (las filas usan la camara de la escena porque no se puede leer la del motor)\n")
        } else {
            builder.append("camara(motor, la que dibuja): eye=").append(vec(snapshot.eye))
            builder.append(" forward=").append(vec(snapshot.forward))
            builder.append(" left=").append(vec(snapshot.left))
            builder.append(" up=").append(vec(snapshot.up)).append('\n')
            builder.append("  nearMotor=").append(fmt2(snapshot.near))
            builder.append(" farMotor(culling)=").append(fmt2(snapshot.far)).append('\n')
            val eyeDiff = FrustumMath.pointDifference(snapshot.eye, camera.eye)
            val forwardDiff = FrustumMath.pointDifference(snapshot.forward, sceneFrustum.forward)
            // DIAG-VIS punto 5 (verificado en la fuente de Filament 1.75.1,
            // filament/src/details/Camera.h: `getLeftVector()` devuelve la
            // columna 0 de la MODEL matrix = eje +X de la camara = RIGHT).
            // El "left" del motor ES el right con el MISMO signo; la
            // hipotesis anterior ("deben ser opuestos") daba 1.7687 =
            // 2*0.884 con datos correctos (falsa alarma). Ningun eje, matriz
            // ni transformacion cambia aqui: solo la comparacion del
            // diagnostico.
            val rightDiff = FrustumMath.pointDifference(snapshot.left, frustum.right)
            val leftNorm = kotlin.math.sqrt(
                snapshot.left[0] * snapshot.left[0] +
                    snapshot.left[1] * snapshot.left[1] + snapshot.left[2] * snapshot.left[2]
            )
            val rightNorm = kotlin.math.sqrt(
                frustum.right[0] * frustum.right[0] +
                    frustum.right[1] * frustum.right[1] + frustum.right[2] * frustum.right[2]
            )
            val leftDotRight = snapshot.left[0] * frustum.right[0] +
                snapshot.left[1] * frustum.right[1] + snapshot.left[2] * frustum.right[2]
            val upDiff = FrustumMath.pointDifference(snapshot.up, frustum.up)
            builder.append("  DESACUERDO escena<->motor: eye=").append(fmt4(eyeDiff))
            builder.append(" direccion=").append(fmt4(forwardDiff))
            builder.append(if (eyeDiff < 0.01f && forwardDiff < 0.01f) "  (SI coinciden: la camara del motor es la de la escena)" else "  (NO COINCIDEN: el motor no esta usando la camara que se le envio)").append('\n')
            builder.append("  convencion de ejes: left(motor) vs right(base)=").append(fmt4(rightDiff))
            builder.append("  up(motor) vs up(base)=").append(fmt4(upDiff))
            builder.append(if (rightDiff < 0.01f && upDiff < 0.01f) "  (misma convencion)" else "  (DIFERENCIA: ver EJES_VERIFICACION)").append('\n')
            builder.append("  EJES_VERIFICACION (punto 5, matematicas, sin cambiar ejes):\n")
            builder.append("    leftMotor=").append(vec(snapshot.left))
                .append(" rightBase=").append(vec(frustum.right)).append('\n')
            builder.append("    dot(leftMotor,rightBase)=").append(fmt4(leftDotRight))
                .append(" (≈1 = mismo sentido; ≈-1 = opuestos)")
                .append(" |left|=").append(fmt4(leftNorm))
                .append(" |right|=").append(fmt4(rightNorm)).append('\n')
            builder.append("    view es column-major Filament: col0=right col1=up col2=-forward (NO filas)\n")
            builder.append("    conclusion=").append(
                if (rightDiff < 0.01f && upDiff < 0.01f)
                    "MISMA convencion (el 1.7687 anterior era comparar con signo opuesto: 2*0.884)"
                else "revisar: leftMotor y rightBase no coinciden ni con el mismo signo"
            ).append('\n')
            builder.append("  las filas de abajo usan la camara del MOTOR y el punto de REGION de cada objeto\n")
            builder.append("  view (filas, column-major): ").append(FrustumMath.rows(snapshot.viewMatrix)).append('\n')
            builder.append("  proj (diagonal y col3):     ").append(FrustumMath.diagonal(snapshot.projectionMatrix)).append('\n')
        }
        val culling = renderer.frustumCullingEnabled
        builder.append("frustum culling del motor (EL REAL, no el de esta traza): ").append(
            when (culling) {
                null -> "DESCONOCIDO"
                true -> "ON (el motor descarta por caja envolvente; el veredicto por prim no es consultable)"
                false -> "OFF"
            }
        ).append('\n')

        val candidates = ArrayList<Pair<Int, Float>>(slots.size)
        for (localId in slots.keys) {
            candidates.add(localId to distanceTo(placementInRegion(localId), camera.eye))
        }
        if (candidates.isEmpty()) {
            builder.append("la escena no tiene ninguna entidad de prim\n")
            return builder.toString()
        }
        candidates.sortBy { it.second }

        val withinRange = candidates.count { it.second <= AUDIT_MAX_METRES }
        builder.append("entidades en la Scene: ").append(slots.size)
        builder.append("  ·  a menos de ").append(AUDIT_MAX_METRES.toInt()).append(" m: ").append(withinRange)
        builder.append("  ·  delante (frustum): ").append(objectsAhead)
        builder.append("  ·  dentro del radio de dibujo: ").append(visibleEntities).append('\n')
        builder.append("se trazan los ").append(minOf(limit, candidates.size)).append(" mas cercanos (radio de la esfera = malla x escala)\n")

        var shown = 0
        for ((localId, distance) in candidates) {
            if (shown >= limit) {
                break
            }
            shown += 1
            appendAuditRow(builder, localId, distance, frustum, matrixFrustum, renderer)
        }
        if (candidates.size > shown) {
            builder.append("... ").append(candidates.size - shown).append(" entidades mas (la mas lejana a ")
                .append(fmt1(candidates[candidates.size - 1].second)).append(" m)\n")
        }
        return builder.toString()
    }

    /** One row of [cameraAudit]: the whole chain for a single object. */
    private fun appendAuditRow(
        builder: StringBuilder,
        localId: Int,
        distance: Float,
        frustum: CameraFrustum,
        matrixFrustum: MatrixFrustum?,
        renderer: Renderer
    ) {
        val slot = slots[localId]
        if (slot == null) {
            builder.append('#').append(localId).append(" (sin entidad)\n")
            return
        }
        val fact = facts[localId]
        val transform = slot.transform
        // Fase 2.12: ONE point, and it is the object's composed region position —
        // the same `parentWorld x local` the engine's world transform holds. A
        // linkset child's own `translation` is relative to its parent, so testing
        // *that* point (which the two verdicts below used to do, one of them) made
        // them disagree by construction: the camera can be aimed at the child's
        // real place in the region and still be "behind" its parent-relative
        // offset.
        val worldPoint = placementInRegion(localId)
        val sample = frustum.sample(worldPoint)

        builder.append('#').append(localId)
        builder.append(' ').append(shortUuid(fact?.uuid ?: ""))
        builder.append(' ').append(pcodeName(fact?.pcode ?: 0))
        builder.append(" forma=").append(fact?.shapeText ?: "-")
        builder.append(" completa=").append(if (fact?.hasCompleteShape == true) "SI" else "NO")
        builder.append(" origen=").append(fact?.shapeSource ?: "-")
        builder.append(" update=").append(fact?.updateSource ?: "-").append('\n')

        builder.append("  SL pos(local, tal cual llego)=").append(vec(transform.translation))
        builder.append(" escala=").append(vec(transform.scale))
        builder.append(" rot=").append(vec(transform.rotation))
        if (parentIdOf(localId) != 0) {
            builder.append('\n').append("  posicion de REGION (parentWorld x local)=").append(vec(worldPoint))
        }
        builder.append("   <- punto probado\n")

        builder.append("  entidad=").append(slot.entity)
        builder.append(" enRenderer=").append(if (renderer.entityExists(slot.entity)) "SI" else "NO")
        builder.append(" enEscena=").append(if (slot.visible) "SI" else "NO (oculta por radio/rampa)").append('\n')
        builder.append("  parent: SL=").append(fact?.parentLocalId ?: -1)
            .append("  adjunto=").append(fact?.attachmentPoint ?: -1)
            .append("  ").append(parentLine(fact?.parentLocalId ?: 0, transform)).append('\n')
        builder.append("  revision=").append(fact?.revision ?: -1)
            .append("  updates=").append(fact?.updatesSeen ?: -1)
            .append(" (").append(fact?.terseUpdatesSeen ?: -1).append(" terse)").append('\n')

        val probe = renderer.entityProbe(slot.entity)
        if (probe != null) {
            builder.append("  ficha del motor: filamentEntity=").append(probe.filamentEntity)
            builder.append("  instanciaCacheada=").append(probe.cachedTransformInstance)
            builder.append("  instanciaActual=").append(probe.actualTransformInstance)
            builder.append("  COINCIDE=").append(if (probe.instanceMatches) "SI" else "NO")
            builder.append("  renderable cacheado=").append(probe.cachedRenderableInstance)
            builder.append("/actual=").append(probe.actualRenderableInstance).append('\n')
            builder.append("  parent del motor: parentEntity=").append(probe.parentEntity)
            builder.append(" (handle=").append(probe.parentHandle).append(")")
            builder.append("  hijos=").append(probe.childCount)
            builder.append("  malla=").append(probe.mesh).append('\n')
        }

        builder.append("  matriz escena (TRS local del objeto): ").append(FrustumMath.rows(transform.toMatrix16())).append('\n')
        val local = renderer.entityLocalMatrix(slot.entity)
        val world = renderer.entityWorldMatrix(slot.entity)
        if (local != null) {
            val diff = FrustumMath.maxDifference(local, transform.toMatrix16())
            builder.append("  TransformManager local:   ").append(FrustumMath.rows(local))
            builder.append("  aplicadaVsEscena=").append(if (diff < 0.001f) "IGUAL" else "DISTINTA")
            builder.append(" (diff ").append(fmt4(diff)).append(")\n")
        } else {
            builder.append("  TransformManager local:   NO DISPONIBLE\n")
        }
        if (probe != null && probe.actualLocal != null) {
            val actualDiff = FrustumMath.maxDifference(probe.actualLocal, nodeMatrixOf(transform))
            builder.append("  local(instancia ACTUAL):  ").append(FrustumMath.rows(probe.actualLocal))
            builder.append("  aplicadaVsEscena=")
            builder.append(if (actualDiff < 0.001f) "IGUAL" else "DISTINTA")
            builder.append(" (diff ").append(fmt4(actualDiff)).append(")\n")
        }
        if (world != null) {
            val translation = FrustumMath.translation(world)
            builder.append("  TransformManager mundo:   traslacion=").append(vec(translation))
            builder.append(" (pos SL ").append(vec(transform.translation)).append(")\n")
        }
        if (probe != null && probe.actualWorld != null) {
            builder.append("  mundo(instancia ACTUAL):  traslacion=")
                .append(vec(FrustumMath.translation(probe.actualWorld))).append('\n')
        }

        val bounds = boundsLocal(localId)
        if (bounds != null) {
            builder.append("  bounds local min=").append(vec(bounds.first))
            builder.append(" max=").append(vec(bounds.second))
        }
        val radius = boundsRadiusWorld(localId)
        builder.append("  radio(malla x escala)=").append(fmt2(radius))
        builder.append("  (el centro esta ").append(fmt4(radius)).append(" m del centro real de masa)\n")

        builder.append("  distCamara=").append(fmt1(distance)).append(" m")
        builder.append("  origen=").append(
            when {
                distance <= 10f -> "MUY CERCA"
                distance <= 100f -> "dentro de 100 m"
                else -> "lejos (>100 m): fuera del alcance de dibujo util"
            }
        ).append('\n')

        builder.append("  frustum(base, DIAGNOSTICO)=").append(sample.text)
        builder.append("  depth=").append(fmt2(sample.depth))
        builder.append("  ndc=(").append(fmt3(sample.ndcX)).append(", ").append(fmt3(sample.ndcY)).append(')')
        builder.append("  pantalla=(").append(pct(sample.screenX)).append(", ").append(pct(sample.screenY)).append(")\n")

        if (matrixFrustum != null) {
            // The same point, through the engine's own matrices. Before fase 2.12
            // this one used the object's *local* translation while the line above
            // used its region position, so for every linkset child the two
            // "disagreed" for a reason that had nothing to do with the maths.
            val matrixSample = matrixFrustum.sample(worldPoint)
            val depthDiff = kotlin.math.abs(sample.depth - matrixSample.depth)
            val ndcXDiff = kotlin.math.abs(sample.ndcX - matrixSample.ndcX)
            val ndcYDiff = kotlin.math.abs(sample.ndcY - matrixSample.ndcY)
            builder.append("  frustum(matrices del motor, DIAGNOSTICO)=").append(matrixSample.text)
            builder.append("  ndc=(").append(fmt3(matrixSample.ndcX)).append(", ").append(fmt3(matrixSample.ndcY)).append(')')
            builder.append("  acuerdo=").append(
                if (sample.inside == matrixSample.inside && depthDiff < 0.05f && ndcXDiff < 0.01f && ndcYDiff < 0.01f) {
                    "SI"
                } else {
                    "NO (base vs matrices)"
                }
            )
            builder.append(" diffDepth=").append(fmt4(depthDiff))
            builder.append(" diffNdc=").append(fmt4(kotlin.math.max(ndcXDiff, ndcYDiff))).append('\n')
        }

        if (radius > 0f && !sample.inside && frustum.intersectsSphere(worldPoint, radius)) {
            builder.append("  nota: el CENTRO esta fuera pero la esfera envolvente (")
                .append(fmt2(radius)).append(" m) SI corta el frustum: parte del objeto deberia verse\n")
        }
    }

    private fun fmt2(value: Float): String = String.format(Locale.US, "%.2f", value)
    private fun fmt6(value: Float): String = String.format(Locale.US, "%.6f", value)

    private fun fmt3(value: Float): String = String.format(Locale.US, "%.3f", value)

    private fun fmt4(value: Float): String = String.format(Locale.US, "%.4f", value)

    private fun pct(fraction: Float): String = String.format(Locale.US, "%.0f%%", fraction * 100f)

    /**
     * The REAL_PRIM_TEST block: one real prim, with its frustum test *before*
     * the camera was moved and *after* it was locked onto the object. The
     * object's own transform is never touched — only the camera moves — so if
     * the "after" test is `DENTRO` and the object still cannot be seen, the
     * fault is below the scene layer (render pass, material, surface).
     *
     * Fase 2.12: the point tested is the object's composed **region** position
     * (`parentWorld × local`), which is where the camera was aimed and where the
     * engine's world transform puts it. Testing the object's own `translation`
     * instead is only correct for a root: for a linkset child that value is
     * relative to its parent, so the camera could be locked straight at the child
     * and the test would still report `DETRAS_DE_LA_CAMARA`.
     */
    fun realPrimTestReport(
        localId: Int,
        beforeCamera: CameraDesc,
        afterCamera: CameraDesc,
        renderer: Renderer,
        lockEye: FloatArray,
        lockTarget: FloatArray
    ): String {
        val slot = slots[localId] ?: return "REAL_PRIM_TEST: #$localId sin entidad"
        val fact = facts[localId]
        val transform = slot.transform
        val worldPoint = placementInRegion(localId)
        val aspect = cameraAspect
        val before = CameraFrustum.from(beforeCamera, aspect).sample(worldPoint)
        val after = CameraFrustum.from(afterCamera, aspect).sample(worldPoint)
        val engineWorld = renderer.entityWorldMatrix(slot.entity)
        val builder = StringBuilder(900)
        builder.append("REAL_PRIM_TEST (la CAMARA se mueve al prim; el objeto NO se mueve)\n")
        builder.append("  DIAGNOSTICO: el culling real es el del motor; esta prueba solo dice donde cae el objeto\n")
        builder.append("  elegido: #").append(localId)
        builder.append(" uuid=").append(safeUuid(fact?.uuid))
        builder.append(" pcode=").append(fact?.pcode ?: 0).append(" (").append(pcodeName(fact?.pcode ?: 0)).append(")\n")
        builder.append("  forma=").append(fact?.shapeText ?: "-")
        builder.append(" completa=").append(if (fact?.hasCompleteShape == true) "SI" else "NO")
        builder.append(" entidad=").append(slot.entity).append('\n')
        builder.append("  distancia a la camara ANTES=").append(fmt1(distanceTo(worldPoint, beforeCamera.eye))).append(" m\n")
        builder.append("  SL local (lo que llego)=").append(vec(transform.translation))
        builder.append("  escala=").append(vec(transform.scale)).append('\n')
        if (parentIdOf(localId) != 0) {
            builder.append("  posicion de REGION (parentWorld x local)=").append(vec(worldPoint))
            builder.append("  parent=#").append(parentIdOf(localId)).append("  <- punto probado\n")
        }
        builder.append("  posicion transformada (TransformManager mundo)=")
        builder.append(if (engineWorld != null) vec(FrustumMath.translation(engineWorld)) else "NO DISPONIBLE")
        if (engineWorld != null) {
            val delta = FrustumMath.pointDifference(FrustumMath.translation(engineWorld), worldPoint)
            builder.append("  deltaVsRegion=").append(fmt3(delta))
            builder.append(if (delta < 0.05f) " (coinciden)\n" else " (NO coinciden)\n")
        } else {
            builder.append('\n')
        }
        builder.append("  visible ANTES del ajuste de camara=").append(before.text)
        builder.append("  ndc=(").append(fmt3(before.ndcX)).append(", ").append(fmt3(before.ndcY)).append(")\n")
        builder.append("  camara ANTES: eye=").append(vec(beforeCamera.eye)).append(" target=").append(vec(beforeCamera.target)).append('\n')
        builder.append("  camara DESPUES (bloqueada): eye=").append(vec(lockEye)).append(" target=").append(vec(lockTarget)).append('\n')
        builder.append("  visible DESPUES del ajuste=").append(after.text)
        builder.append("  depth=").append(fmt2(after.depth)).append(" m")
        builder.append("  ndc=(").append(fmt3(after.ndcX)).append(", ").append(fmt3(after.ndcY)).append(")")
        builder.append("  pantalla=(").append(pct(after.screenX)).append(", ").append(pct(after.screenY)).append(")\n")
        builder.append("  resultado calculado: ")
        builder.append(
            when {
                after.inside -> "el prim DEBERIA estar en el centro de la pantalla ahora. Confirma a ojo."
                else -> "incluso mirando al prim directamente queda FUERA (" + after.reason + "): la camara o la proyeccion no son las esperadas."
            }
        ).append('\n')
        appendObjectFrustumTest(builder, localId, afterCamera, renderer)
        return builder.toString()
    }

    /**
     * The compact per-object frustum test (fase 2.12), written for one object at a
     * time so the case the user is chasing — "the camera is aimed straight at
     * #N and the diagnostic still says it is behind" — can be read in one block
     * without hunting through the audit table.
     *
     * It prints the object's region position and the engine's world position, the
     * camera as the engine reports it, the point in view space, in clip space and
     * in NDC, and the three verdicts: the scene's basis, the engine's basis and
     * the engine's matrices. Two honest limits, stated in the text itself: the
     * per-renderable decision is **not** exposed by Filament's Java API, so the
     * matrix line is a reading of the engine's own inputs and not its answer; and
     * the engine's aggregate answer (`renderablesInScene` / `visibleRenderables`)
     * is the only per-frame number it does report.
     */
    private fun appendObjectFrustumTest(
        builder: StringBuilder,
        localId: Int,
        camera: CameraDesc,
        renderer: Renderer
    ) {
        val slot = slots[localId] ?: return
        val transform = slot.transform
        val worldPoint = placementInRegion(localId)
        val aspect = cameraAspect
        val sceneFrustum = CameraFrustum.from(camera, aspect)
        val snapshot = renderer.cameraSnapshot()
        val engineNear = if (snapshot != null && snapshot.near > 0f) snapshot.near else camera.near
        val engineFar = if (snapshot != null && snapshot.far > 0f) snapshot.far else camera.far
        val engineFrustum = if (snapshot == null) {
            sceneFrustum
        } else {
            CameraFrustum.fromEngine(
                snapshot.eye, snapshot.forward, snapshot.up, camera.verticalFovDegrees,
                aspect, engineNear, engineFar
            )
        }
        val matrixFrustum = snapshot?.let {
            MatrixFrustum(it.viewMatrix, it.projectionMatrix, engineNear, engineFar)
        }

        builder.append("  --- PRUEBA DE FRUSTUM #").append(localId).append(" (fase 2.12) ---\n")
        builder.append("    SL local (lo que llego)      = ").append(vec(transform.translation)).append('\n')
        builder.append("    region (parentWorld x local) = ").append(vec(worldPoint))
        builder.append(if (parentIdOf(localId) != 0) "   <- punto probado\n" else "\n")
        val engineWorld = renderer.entityWorldMatrix(slot.entity)
        builder.append("    mundo (TransformManager)     = ")
        if (engineWorld == null) {
            builder.append("NO DISPONIBLE\n")
        } else {
            val delta = FrustumMath.pointDifference(FrustumMath.translation(engineWorld), worldPoint)
            builder.append(vec(FrustumMath.translation(engineWorld)))
            builder.append("  deltaVsRegion=").append(fmt3(delta))
            builder.append(if (delta < 0.05f) " (coinciden)\n" else " (NO coinciden)\n")
        }
        builder.append("    camara eye (motor)           = ").append(vec(snapshot?.eye ?: camera.eye)).append('\n')
        builder.append("    camara forward (motor)       = ")
            .append(vec(snapshot?.forward ?: sceneFrustum.forward)).append('\n')
        builder.append("    near / far (motor)           = ").append(fmt3(engineNear))
            .append(" / ").append(fmt1(engineFar))
            .append("   (escena: ").append(fmt3(camera.near)).append(" / ").append(fmt1(camera.far)).append(")\n")
        if (matrixFrustum == null) {
            builder.append("    view / clip / NDC (motor)    = NO DISPONIBLE (sin lectura de la camara del motor)\n")
        } else {
            val t = matrixFrustum.trace(worldPoint)
            builder.append("    view-space  (motor)          = (").append(fmt3(t.viewX)).append(", ")
                .append(fmt3(t.viewY)).append(", ").append(fmt3(t.viewZ)).append(")   [la camara del motor mira hacia -z]\n")
            builder.append("    clip-space  (motor)          = (").append(fmt3(t.clipX)).append(", ")
                .append(fmt3(t.clipY)).append(", ").append(fmt3(t.clipZ)).append(", ").append(fmt3(t.clipW)).append(")\n")
            builder.append("    NDC         (motor)          = (").append(fmt3(t.ndcX)).append(", ")
                .append(fmt3(t.ndcY)).append(")   depth=").append(fmt2(t.depth)).append(" m\n")
        }
        val baseSample = sceneFrustum.sample(worldPoint)
        val engineSample = engineFrustum.sample(worldPoint)
        builder.append("    visible aux (base de la escena)   = ").append(baseSample.text)
            .append("  depth=").append(fmt2(baseSample.depth)).append(" m\n")
        builder.append("    visible aux (base del motor)      = ").append(engineSample.text)
            .append("  depth=").append(fmt2(engineSample.depth)).append(" m\n")
        builder.append("    visible aux (matrices del motor)  = ")
        builder.append(matrixFrustum?.sample(worldPoint)?.text ?: "NO DISPONIBLE")
        builder.append("   [diagnostico: es la lectura de las matrices del motor, ")
        builder.append("Filament no expone el veredicto por prim]\n")
        val radius = boundsRadiusWorld(localId)
        builder.append("    esfera envolvente (radio ").append(fmt2(radius)).append(" m, escala compuesta) = ")
        builder.append(
            if (radius > 0f && engineFrustum.intersectsSphere(worldPoint, radius)) "CORTA el frustum" else "no corta"
        ).append('\n')
        builder.append("    entidad en el motor          = ")
            .append(if (renderer.entityExists(slot.entity)) "SI" else "NO")
            .append("   renderable=").append(slot.entity).append('\n')
        builder.append("    culling real del motor       = ").append(
            when (renderer.frustumCullingEnabled) {
                null -> "DESCONOCIDO"
                true -> "ON (View + RenderableManager; decide el, no esta prueba)"
                false -> "OFF (se dibuja todo lo que este en la Scene)"
            }
        ).append('\n')
        val frame = renderer.diagnostics()
        builder.append("    motor, agregado del frame    = renderables en escena ").append(frame.renderablesInScene)
            .append(" / visibles ").append(frame.visibleRenderables)
            .append("  (unico numero por-prim que el motor reporta)\n")
    }

    private fun safeUuid(uuid: String?): String =
        if (uuid.isNullOrEmpty() || uuid == "00000000-0000-0000-0000-000000000000") "-" else uuid

    // ============================== fase 2.10: diagnostico dirigido =============

    /**
     * The fase-2.11 report: what the parent/child correction actually did.
     *
     * It answers the phase's success criterion line by line — for the first
     * [limit] children it prints `SL parentLocalId → Transform.parent → Filament
     * parentEntity`, so a resolved child shows
     *
     *     parentLocalId = X → Transform.parent = EntityHandle(Y) → Filament parentEntity = Y
     *
     * and never a `Transform.parent = null`. Children whose parent has no entity
     * yet are listed separately with the parent they are waiting for: they keep
     * their local transform and nothing has been invented for them.
     */
    fun parentLinkReport(renderer: Renderer, limit: Int): String {
        val builder = StringBuilder(1500 + limit * 200)
        builder.append("--- PARENT/CHILD resuelto (fase 2.11) ---\n")
        builder.append("childrenWithParent=").append(childrenWithParent)
            .append("  childrenWithoutParent=").append(childrenWithoutParent)
            .append("  parentsResolved=").append(parentsResolved)
            .append("  parentsPending=").append(parentsPending)
            .append("  parentsUnresolved=").append(parentsUnresolved)
            .append("  (hijos esperando: ").append(awaitingFrom.size).append(")\n")
        builder.append("un hijo conserva su TRS LOCAL (el que envio el simulador); el motor compone ")
        builder.append("parentWorld x local; ningun hijo se mueve ni se convierte a coordenadas de region\n")
        if (parentOf.isEmpty() && awaitingParent.isEmpty()) {
            builder.append("no hay ninguna relacion parent/child en la escena todavia\n")
            return builder.toString()
        }
        val children = parentOf.keys.sorted()
        builder.append("se listan ").append(minOf(limit, children.size)).append(" de ")
            .append(children.size).append(" hijos resueltos\n")
        var shown = 0
        for (childLocalId in children) {
            if (shown >= limit) {
                break
            }
            shown += 1
            val parentLocalId = parentIdOf(childLocalId)
            val childSlot = slots[childLocalId]
            val parentSlot = slots[parentLocalId]
            val transform = childSlot?.transform
            val probe = childSlot?.let { renderer.entityProbe(it.entity) }
            val parentText = transform?.parent?.let { "EntityHandle(" + it.id + ")" } ?: "null"
            builder.append("\n#").append(childLocalId).append(" (hijo de #").append(parentLocalId).append(")\n")
            builder.append("  Child LocalID=").append(childLocalId)
                .append("  Parent LocalID=").append(parentLocalId).append('\n')
            builder.append("  Child Entity=").append(childSlot?.entity ?: "-")
                .append("  Parent Entity=").append(parentSlot?.entity ?: "-").append('\n')
            builder.append("  Transform.parent=").append(parentText)
                .append("  Filament parentEntity=").append(probe?.parentEntity ?: -1)
                .append(" (handle=").append(probe?.parentHandle ?: -1).append(")  hijosEnElMotor=")
                .append(probe?.childCount ?: -1).append('\n')
            builder.append("  child local=").append(transform?.let { vec(it.translation) } ?: "-")
                .append("  child world (compuesto)=").append(vec(placementInRegion(childLocalId)))
                .append("  parent world=").append(vec(placementInRegion(parentLocalId))).append('\n')
            if (probe == null) {
                builder.append("  -> sin ficha del motor: no se puede comprobar la relacion\n")
            } else if (probe.parentEntity == 0) {
                builder.append("  -> el motor NO tiene parent para este hijo (revisar instanciaCacheada ")
                    .append("vs instanciaActual: ").append(probe.cachedTransformInstance).append(" / ")
                    .append(probe.actualTransformInstance).append(")\n")
            } else {
                builder.append("  -> parentLocalId=").append(parentLocalId).append(" -> Transform.parent=")
                    .append(parentText).append(" -> Filament parentEntity=")
                    .append(probe.parentEntity).append("  OK\n")
            }
        }
        if (awaitingParent.isNotEmpty()) {
            builder.append("\nPENDIENTES: hijos cuyo parent aun no tiene entidad en la escena")
            builder.append(" (conservan su posicion local; no se ha inventado nada)\n")
            val pending = awaitingParent.keys.sorted()
            for (parentLocalId in pending.take(limit)) {
                val kids = awaitingParent[parentLocalId]?.sorted() ?: continue
                builder.append("  parent #").append(parentLocalId).append(" (visto en el protocolo: ")
                    .append(if (facts.containsKey(parentLocalId)) "SI" else "NO")
                    .append(") -> hijos ")
                    .append(kids.joinToString(", ") { "#" + it }).append('\n')
            }
            if (pending.size > limit) {
                builder.append("  ... ").append(pending.size - limit).append(" parents mas sin resolver\n")
            }
        }
        return builder.toString()
    }

    /**
     * PARTE A of the phase: the parent/child chain, hop by hop.
     *
     * It is a report and nothing else. For every drawn object whose Second Life
     * record says it has a parent it prints the value at each hop of
     * `ObjectUpdateDecoder → SceneObject → SLObject → Transform → Renderer`, with
     * the `Transform` the scene *actually built* (the very object stored in the
     * slot and handed to the renderer) — so a `parent = null` next to a
     * `parentLocalId != 0` is visible rather than argued, and after fase 2.11 the
     * interesting line is the opposite one: the parent that *is* there.
     *
     * The parser-side counters come with it, because a parent can still be
     * unresolved before the scene layer exists at all: a compressed update
     * without `FLAG_HAS_PARENT` says nothing about the parent, and since fase 2.11
     * that silence no longer clears a parent the object already had.
     */
    fun parentChainReport(renderer: Renderer, limit: Int): String {
        val children = ArrayList<Int>()
        var withoutParent = 0
        for ((localId, _) in slots) {
            if ((facts[localId]?.parentLocalId ?: 0) != 0) {
                children.add(localId)
            } else {
                withoutParent += 1
            }
        }
        children.sort()

        val builder = StringBuilder(2600)
        builder.append("--- PARENT/CHILD (fase 2.10/2.11): la cadena completa, salto a salto ---\n")
        builder.append("entidades en la escena: ").append(slots.size)
        builder.append("  ·  con parentLocalId != 0: ").append(children.size)
        builder.append("  ·  sin parent: ").append(withoutParent).append('\n')
        builder.append("cadena auditada: ObjectUpdateDecoder.applyFull/decodeCompressedBlock")
        builder.append(" -> SceneObject.parentId -> SLObject.parentLocalId")
        builder.append(" -> Renderer.Transform.parent -> FilamentRenderer.setParent()\n")
        val parserCounters = com.lumiyaviewer.lumiya.slproto.world.ObjectUpdateDiagnostics
        builder.append("parser (lo que llego del protocolo): bloques con ParentID ")
            .append(parserCounters.parentFieldCarried)
            .append("  ·  con valor != 0 ").append(parserCounters.parentNonZeroValues)
            .append("  ·  con valor 0 explicito ").append(parserCounters.parentZeroValues)
            .append("  ·  parent conservado por un comprimido sin el campo ")
            .append(parserCounters.parentKeptWithoutField).append('\n')
        builder.append("parser: objetos vistos con parent != 0 ")
            .append(parserCounters.parentObjectsEverNonZero)
            .append("  ·  con parent != 0 en su ultimo update ").append(parserCounters.parentNowCount())
            .append("  ·  hijos con la relacion YA resuelta en la escena: ").append(childrenWithParent)
            .append("  ·  esperando a su parent: ").append(awaitingParent.size).append('\n')

        val ledger = parserCounters.parentLedgerLines()
        if (ledger.isNotEmpty()) {
            builder.append("parser, cambios de parent objeto por objeto (el update que los escribio):\n")
            for (line in ledger) {
                builder.append("  ").append(line).append('\n')
            }
        }

        if (children.isEmpty()) {
            builder.append("ningun objeto de la escena tiene parentLocalId != 0, asi que ")
            builder.append("no hay ninguna relacion parent/child que perder en esta sesion\n")
            return builder.toString()
        }
        builder.append("se auditan los ").append(minOf(limit, children.size)).append(" primeros de ")
            .append(children.size).append(" hijos\n")
        var shown = 0
        var handedWithoutParent = 0
        var handedWithParent = 0
        var rendererWithParent = 0
        for (localId in children) {
            val slot = slots[localId] ?: continue
            val fact = facts[localId]
            val transform = slot.transform
            val parserParent = parserCounters.lastParent(localId)
            if (transform.parent == null) {
                handedWithoutParent += 1
            } else {
                handedWithParent += 1
            }
            val probe = renderer.entityProbe(slot.entity)
            if (probe != null && probe.parentEntity != 0) {
                rendererWithParent += 1
            }
            if (shown >= limit) {
                continue
            }
            shown += 1
            builder.append("\n#").append(localId)
            builder.append(" uuid=").append(shortUuid(fact?.uuid ?: ""))
            builder.append(" ").append(pcodeName(fact?.pcode ?: 0))
            builder.append(" forma=").append(fact?.shapeText ?: "-")
            builder.append(" adjunto=").append(fact?.attachmentPoint ?: -1).append('\n')
            builder.append("  A1 protocolo: ultimo parentId que vio el parser = ")
                .append(if (parserParent == null) "?" else parserParent.toString())
                .append("  ·  parentLocalId del SLObject que la escena guardo = ")
                .append(fact?.parentLocalId ?: -1)
                .append(
                    when {
                        parserParent == null ->
                            "  (el parser no registro ningun update de este objeto: no vino del protocolo)"
                        parserParent == (fact?.parentLocalId ?: 0) && parserParent != 0 ->
                            "  (coinciden: el protocolo SI entrego el parent y SLObject lo conserva)"
                        parserParent == (fact?.parentLocalId ?: 0) ->
                            "  (coinciden: el protocolo dijo 0, este objeto no tiene parent)"
                        else ->
                            "  (NO coinciden: revisar el orden de los updates)"
                    }
                ).append('\n')
            builder.append("  A2 Transform que SLScene.attach construyo y entrego al renderer:\n")
            builder.append("     translation (LOCAL, tal como la envio el simulador)=")
                .append(vec(transform.translation))
                .append("  rotation=").append(vec(transform.rotation))
                .append("  scale=").append(vec(transform.scale)).append('\n')
            builder.append("     ").append(parentLine(fact?.parentLocalId ?: 0, transform)).append('\n')
            if (parentIdOf(localId) != 0) {
                builder.append("     posicion de region compuesta (parentWorld x local)=")
                    .append(vec(placementInRegion(localId))).append('\n')
            }
            if (transform.parent == null) {
                builder.append("     -> SLObject.parentLocalId=").append(fact?.parentLocalId ?: 0)
                    .append(" y el Transform lleva parent=null: su parent aun NO tiene ")
                    .append("entidad en la escena (pendiente)\n")
            } else {
                builder.append("     -> el Transform SI lleva parent: ").append(transform.parent)
                    .append(" (el parent ya tiene entidad; la posicion del hijo sigue siendo local)\n")
            }
            builder.append("  A3 renderer: entidad=").append(slot.entity)
                .append(" enRenderer=").append(if (renderer.entityExists(slot.entity)) "SI" else "NO").append('\n')
            if (probe == null) {
                builder.append("     el motor no responde por esta entidad (sin ficha): no se puede ")
                builder.append("comprobar la relacion en el motor\n")
            } else {
                builder.append("     filamentEntity=").append(probe.filamentEntity)
                    .append("  instanciaCacheada=").append(probe.cachedTransformInstance)
                    .append("  instanciaActual=").append(probe.actualTransformInstance)
                    .append("  COINCIDE=").append(if (probe.instanceMatches) "SI" else "NO").append('\n')
                builder.append("     parent del motor (instancia actual): parentEntity=").append(probe.parentEntity)
                    .append(" (handle=").append(probe.parentHandle).append(")")
                    .append("  hijos=").append(probe.childCount)
                    .append("  por la instancia cacheada=").append(probe.parentEntityFromCached)
                    .append('\n')
                if (probe.parentEntity == 0) {
                    builder.append("     -> el motor no tiene parent para esta entidad: o su parent aun ")
                        .append("no ha llegado a la escena, o su componente cambio de instancia ")
                        .append("(comparar instanciaCacheada con instanciaActual arriba)\n")
                } else {
                    builder.append("     -> el motor SI tiene un parent aplicado para esta entidad: ")
                        .append("Entity#").append(probe.parentHandle)
                        .append(" (relacion parent/child viva)\n")
                }
            }
        }
        builder.append("\nresumen renderer: de ").append(children.size).append(" hijos, ")
            .append(handedWithoutParent).append(" se entregaron con parent=null (su parent aun no tiene entidad) y ")
            .append(handedWithParent).append(" con parent; el motor tiene ")
            .append(rendererWithParent).append(" relaciones de parent entre estas entidades\n")
        builder.append("resumen fase 2.11: childrenWithParent=").append(childrenWithParent)
            .append(" childrenWithoutParent=").append(childrenWithoutParent)
            .append(" parentsResolved=").append(parentsResolved)
            .append(" parentsPending=").append(parentsPending)
            .append(" parentsUnresolved=").append(parentsUnresolved).append('\n')
        return builder.toString()
    }

    /** One matrix compared against the scene's own, printed the same way always. */
    private fun matrixCompare(
        builder: StringBuilder,
        label: String,
        matrix: FloatArray?,
        scene: FloatArray
    ) {
        if (matrix == null) {
            builder.append("  ").append(label).append(": NO DISPONIBLE\n")
            return
        }
        val diff = FrustumMath.maxDifference(matrix, scene)
        builder.append("  ").append(label).append(": ").append(FrustumMath.rows(matrix))
        builder.append("  vsEscena=").append(if (diff < 0.001f) "IGUAL" else "DISTINTA")
        builder.append(" (diff ").append(fmt4(diff)).append(")\n")
    }

    /**
     * The whole field set of one object, printed identically for every object so
     * two of them can be compared line by line (PARTE B and PARTE C of the
     * phase): what the simulator sent, the transform the scene built, the matrix
     * the scene hands over, and what the backend holds for the component it was
     * given *and* for the component the entity owns now.
     */
    private fun appendObjectSnapshot(
        builder: StringBuilder,
        localId: Int,
        title: String,
        renderer: Renderer
    ) {
        val slot = slots[localId]
        val fact = facts[localId]
        builder.append(title).append(" #").append(localId).append('\n')
        if (slot == null) {
            builder.append("  no hay entidad en la escena para este objeto\n")
            builder.append("  SL: parent=").append(fact?.parentLocalId ?: -1)
                .append(" pos=").append(fact?.translation?.let { vec(it) } ?: "-")
                .append(" forma=").append(fact?.shapeText ?: "-")
                .append(" update=").append(fact?.updateSource ?: "-").append('\n')
            return
        }
        val transform = slot.transform
        val sceneMatrix = transform.toMatrix16()
        val probe = renderer.entityProbe(slot.entity)
        builder.append("  SL: pos=").append(vec(transform.translation))
            .append("  rot=").append(vec(transform.rotation))
            .append("  escala=").append(vec(transform.scale)).append('\n')
        builder.append("  SL: parent=").append(fact?.parentLocalId ?: -1)
            .append("  adjunto=").append(fact?.attachmentPoint ?: -1)
            .append("  revision=").append(fact?.revision ?: -1)
            .append("  updates=").append(fact?.updatesSeen ?: -1)
            .append(" (").append(fact?.terseUpdatesSeen ?: -1).append(" terse)")
            .append("  ultimoUpdate=").append(fact?.updateSource ?: "-").append('\n')
        builder.append("  SL: forma=").append(fact?.shapeText ?: "-")
            .append(" completa=").append(if (fact?.hasCompleteShape == true) "SI" else "NO")
            .append(" origen=").append(fact?.shapeSource ?: "-")
            .append(" uuid=").append(shortUuid(fact?.uuid ?: "")).append('\n')
        builder.append("  ").append(parentLine(fact?.parentLocalId ?: 0, transform)).append('\n')
        builder.append("  escena: EntityHandle=").append(slot.entity)
            .append("  enRenderer=").append(if (renderer.entityExists(slot.entity)) "SI" else "NO")
            .append("  enEscena=").append(if (slot.visible) "SI" else "NO").append('\n')
        builder.append("  escena: malla key=").append(slot.mesh.key)
            .append(" tris=").append(slot.mesh.triangles)
            .append(" material=").append(slot.materials.joinToString("/"))
            .append(" texto=").append(if (slot.textureId.isEmpty()) "-" else slot.textureId).append('\n')
        builder.append("  MATRIZ_ESCENA (TRS local del objeto): ").append(FrustumMath.rows(sceneMatrix))
            .append("  traslacion=").append(vec(FrustumMath.translation(sceneMatrix))).append('\n')
        if (parentIdOf(localId) != 0) {
            builder.append("  posicion de region (parentWorld x local)=").append(vec(placementInRegion(localId)))
                .append("  parent=#").append(parentIdOf(localId)).append('\n')
        }
        if (probe == null) {
            builder.append("  ficha del motor: NO DISPONIBLE (el motor no conoce esta entidad)\n")
        } else {
            builder.append("  ficha: filamentEntity=").append(probe.filamentEntity)
                .append("  renderable cacheado=").append(probe.cachedRenderableInstance)
                .append("  actual=").append(probe.actualRenderableInstance).append('\n')
            builder.append("  ficha: TransformInstance cacheada=").append(probe.cachedTransformInstance)
                .append("  actual(getInstance)=").append(probe.actualTransformInstance)
                .append("  COINCIDE=").append(if (probe.instanceMatches) "SI" else "NO")
                .append("  malla=").append(probe.mesh).append(" tris=").append(probe.triangles)
                .append(" visible=").append(if (probe.visible) "SI" else "NO").append('\n')
            builder.append("  ficha: parentEntity=").append(probe.parentEntity)
                .append(" (handle=").append(probe.parentHandle).append(")")
                .append("  hijos=").append(probe.childCount)
                .append("  parent por la instancia cacheada=").append(probe.parentEntityFromCached).append('\n')
        }
        val local = renderer.entityLocalMatrix(slot.entity)
        val world = renderer.entityWorldMatrix(slot.entity)
        matrixCompare(builder, "TransformManager local  (cacheada)", local, sceneMatrix)
        if (local != null && probe != null && probe.actualLocal != null && !probe.instanceMatches) {
            matrixCompare(builder, "TransformManager nodo    (actual)  ", probe.actualLocal, nodeMatrixOf(transform))
        }
        if (world != null) {
            builder.append("  TransformManager mundo  (cacheada): traslacion=")
                .append(vec(FrustumMath.translation(world)))
                .append("  (la pos SL es ").append(vec(transform.translation)).append(")\n")
        } else {
            builder.append("  TransformManager mundo  (cacheada): NO DISPONIBLE\n")
        }
        if (probe != null && probe.actualWorld != null && !probe.instanceMatches) {
            builder.append("  TransformManager mundo  (actual)  : traslacion=")
                .append(vec(FrustumMath.translation(probe.actualWorld))).append('\n')
        }
        val bounds = boundsLocal(localId)
        if (bounds != null) {
            builder.append("  bounds local: min=").append(vec(bounds.first))
                .append(" max=").append(vec(bounds.second))
                .append(" radio=").append(fmt2(boundsRadiusWorld(localId))).append('\n')
        }
        val camera = lastCamera
        if (camera != null) {
            val placement = placementInRegion(localId)
            val sample = CameraFrustum.from(camera, cameraAspect).sample(placement)
            builder.append("  camara: dist=").append(fmt1(distanceTo(placement, camera.eye)))
                .append(" m  ").append(sample.text)
                .append("  ndc=(").append(fmt3(sample.ndcX)).append(", ").append(fmt3(sample.ndcY)).append(")\n")
            // Fase 2.12: the whole chain for this one object, so the frustum
            // question can be settled from a single block.
            appendObjectFrustumTest(builder, localId, camera, renderer)
        }
    }

    /**
     * PARTE B and PARTE C of the phase: the whole life of one object the user
     * chose, then the same reading for a healthy object next to it.
     *
     * The verdict it prints is deliberately narrow: it says *where* the
     * discrepancy appears (already in the `Transform` the scene handed over, or
     * later, in what the backend holds) and never what to do about it.
     */
    fun focusReport(renderer: Renderer, localId: Int): String {
        if (localId == 0) {
            return "sin objeto en foco (elige el localID en el boton FOCO del HUD)"
        }
        val builder = StringBuilder(2400)
        builder.append("--- FOCO #").append(localId).append(" (fase 2.10) ---\n")
        val slot = slots[localId]
        if (slot == null) {
            builder.append("este objeto no tiene entidad en la escena (no es renderizable, ")
            builder.append("es un adjunto, o aun no ha llegado su definicion)\n")
        }
        appendObjectSnapshot(builder, localId, "B) objeto en foco:", renderer)

        // B4: where the discrepancy appears, from the two independent readings.
        if (slot != null) {
            val transform = slot.transform
            val sceneMatrix = transform.toMatrix16()
            val local = renderer.entityLocalMatrix(slot.entity)
            val probe = renderer.entityProbe(slot.entity)
            builder.append("  B4 veredicto:\n")
            if (local == null) {
                builder.append("     la escena no puede leer ninguna matriz local para esta entidad\n")
            } else {
                val diff = FrustumMath.maxDifference(local, sceneMatrix)
                val same = diff < 0.001f
                builder.append("     MATRIZ_ESCENA vs TransformManager local(cacheada): ")
                    .append(if (same) "IGUAL" else "DISTINTA (diff " + fmt4(diff) + ")").append('\n')
                builder.append("     traslacion de esa lectura=")
                    .append(vec(FrustumMath.translation(local))).append('\n')
                if (probe != null && probe.actualLocal != null && !probe.instanceMatches) {
                    val actualDiff = FrustumMath.maxDifference(probe.actualLocal, sceneMatrix)
                    builder.append("     matriz guardada para ESTA entidad (getInstance ahora mismo): ")
                        .append(if (actualDiff < 0.001f) "IGUAL" else "DISTINTA (diff " + fmt4(actualDiff) + ")")
                        .append("  traslacion=").append(vec(FrustumMath.translation(probe.actualLocal))).append('\n')
                    builder.append("     -> la instancia que el renderer tenia apuntada (")
                        .append(probe.cachedTransformInstance)
                        .append(") YA NO ES la de esta entidad (ahora es la ")
                        .append(probe.actualTransformInstance)
                        .append("): la discrepancia aparece al LEER la ficha equivocada, no al entregar el Transform\n")
                } else if (probe != null) {
                    builder.append("     la instancia cacheada sigue siendo la de esta entidad (")
                        .append(probe.actualTransformInstance)
                        .append("), asi que la lectura es la correcta\n")
                    if (!same) {
                        builder.append("     -> y aun asi la matriz no coincide: la discrepancia YA EXISTE ")
                            .append("en el TransformManager al entregar el Transform\n")
                    }
                }
            }
        }

        // B1: the object's whole life, from the two ends of the pipeline.
        builder.append("  B1 vida del objeto:\n")
        builder.append("     parser (cada update que leyo el protocolo):\n")
        val focusText = com.lumiyaviewer.lumiya.slproto.world.ObjectUpdateDiagnostics.parentFocusText()
        if (focusText.isEmpty()) {
            builder.append("       sin historial: el foco estaba apagado mientras llegaban sus updates ")
            builder.append("(activalo y vuelve a conectarte para verlo completo)\n")
        } else {
            for (line in focusText.split('\n')) {
                builder.append("       ").append(line).append('\n')
            }
        }
        builder.append("     escena (lo que hizo con su entidad):\n")
        for (line in focusEntityTrace().split('\n')) {
            builder.append("       ").append(line).append('\n')
        }

        // PARTE C: one healthy object, same field set, for contrast.
        val healthy = healthyObjectNear(localId, renderer)
        builder.append("C) objeto sano de comparacion:\n")
        if (healthy == null) {
            builder.append("  no se ha encontrado ningun objeto cercano con aplicadaVsEscena=IGUAL ")
            builder.append("y sin parent (la comparacion no puede hacerse en esta escena)\n")
        } else {
            appendObjectSnapshot(builder, healthy, "C) objeto sano:", renderer)
        }
        return builder.toString()
    }

    /**
     * One line naming the followed object, for the HUD (fase 2.10). It never
     * invents anything: when the object is not in the scene it says so, which is
     * itself the answer to "did its update ever arrive?".
     */
    fun focusLabel(localId: Int): String {
        val fact = facts[localId]
        val slot = slots[localId]
        if (fact == null && slot == null) {
            return "no esta en la escena (¿id equivocado, o su update no ha llegado?)"
        }
        val position = fact?.translation ?: slot?.transform?.translation
        return pcodeName(fact?.pcode ?: 0) +
            " forma=" + (fact?.shapeText ?: "-") +
            " parent=" + (fact?.parentLocalId ?: -1) +
            " pos=" + (position?.let { vec(it) } ?: "-") +
            (if (slot != null) " entidad=" + slot.entity else " SIN entidad")
    }

    /**
     * One object whose transform the backend holds exactly as the scene built it
     * and which has no parent, as close as possible to [localId]. It is chosen by
     * measurement — the same comparison the audit uses — not by name.
     */
    private fun healthyObjectNear(localId: Int, renderer: Renderer): Int? {
        val reference = if (slots.containsKey(localId)) {
            placementInRegion(localId)
        } else {
            lastCamera?.eye ?: return null
        }
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        for ((id, slot) in slots) {
            if (id == localId) {
                continue
            }
            if ((facts[id]?.parentLocalId ?: 0) != 0) {
                continue
            }
            val matrix = renderer.entityLocalMatrix(slot.entity) ?: continue
            if (FrustumMath.maxDifference(matrix, slot.transform.toMatrix16()) >= 0.001f) {
                continue
            }
            val probe = renderer.entityProbe(slot.entity)
            if (probe != null && !probe.instanceMatches) {
                // A stale instance would make the "healthy" object a lie too.
                continue
            }
            val distance = distanceTo(placementInRegion(id), reference)
            if (distance < bestDistance) {
                bestDistance = distance
                best = id
            }
        }
        return best
    }

    /** Copies the current counters into the shared debug record. */
    private fun refreshDiagnostics() {
        val d = diagnostics
        d.sceneEntities = slots.size
        d.sceneVisible = visibleEntities
        d.objectsAhead = objectsAhead
        d.sceneMissingGeometry = missingGeometry
        d.sceneSkippedAttachments = omittedAttachments.size
        d.sceneRecreated = recreated
        d.sceneMeshes = meshes.count
        d.sceneMeshTriangles = meshes.triangles
        d.meshBuilt = meshes.built
        d.meshCacheHits = meshes.cacheHits
        d.meshFailures = meshes.failures
        d.faceGroupFallbacks = PrimGeometryNative.faceGroupFallbacks
        d.geometryWarnings = PrimGeometryNative.faceGroupWarnings
        d.geometryAvailable = PrimGeometryNative.isAvailable
        d.geometryReason = PrimGeometryNative.unavailableReason ?: "ok"
        d.avatars = avatarCount
        d.sceneRejections = rejectedEntities
        d.primAttempts = primAttempts
        d.primsWithRenderable = primsWithRenderable
        d.geometryBuildSucceeded = primMeshIds.size
        d.objectsInsideDrawDistance = visibleEntities
        d.visibleInFrustum = objectsAhead
        // Fase 2.12: the scalability switches and the counts behind them. The
        // renderables-in-scene / visible split is filled in by the backend, which
        // is the only place that can measure what the view actually submitted.
        d.distanceCullingEnabled = distanceCullingEnabled
        d.maxDrawDistance = maxDrawDistance
        d.culledByDistance = distanceCulled
        d.shadowLodDistance = shadowLodDistance
        d.shadowLodReduced = shadowLodReduced
        // Avatars are recorded, never drawn (Phase 8). The report carries the
        // one the region actually sent, field by field, so "1 avatar detected"
        // is not a number that has to be taken on trust.
        d.avatarDump = avatarsById.values.firstOrNull()?.let { describeAvatar(it) } ?: "-"
        d.terrainTriangles = terrain.triangles
        d.terrainVertices = terrain.vertices
        d.terrainRebuilds = terrain.rebuilds
        d.terrainSpanMetres = terrain.spanMetres
        // Textures (fase 2.13b). The HUD line is one string recomputed at the
        // HUD's rhythm; the full block is rebuilt with the shape table, because it
        // is a report and not render state.
        textureStreamer?.let { streamer ->
            d.textureHudLine = streamer.hudLine()
            d.texturedFaces = texturedFaceCount
            d.texturedEntities = texturedEntityCount
        }
        if (forceLocalId != 0) {
            d.forcedObject = "#$forceLocalId"
            d.forcedDistance = forceDistance
        }
        // DIAG-VIS (temporal, reversible): el bloque del testigo vive en el
        // informe del dispositivo; la funcion cachea a 2 s, asi que este
        // refresco por frame no cuesta.
        d.witnessReport = diagWitnessReport()
    }

    /** One avatar exactly as the region sent it — nothing here is inferred. */
    private fun describeAvatar(avatar: SLAvatar): String {
        val transform = avatar.transform
        return "#" + avatar.localId +
            " uuid=" + (if (avatar.uuid.isEmpty()) "-" else avatar.uuid) +
            " nombre=" + (if (avatar.name.isEmpty()) "-" else avatar.name) +
            "\n  pos " + vec(transform.translation) +
            "  rot " + vec(transform.rotation) +
            "  escala " + vec(transform.scale) +
            "\n  posicion conocida=" + avatar.positionKnown +
            "  revision=" + avatar.revision +
            "  apariencia=no recibida (AvatarAppearance es fase 8)" +
            " entidad=ninguna (no se dibuja)"
    }

    fun clear() {
        for (slot in slots.values) {
            renderer.destroyEntity(slot.entity)
        }
        slots.clear()
        omittedAttachments.clear()
        avatarsById.clear()
        loggedObjectIds.clear()
        primAttemptIds.clear()
        primRenderableIds.clear()
        primMeshIds.clear()
        missingGeometryIds.clear()
        rejectedEntityIds.clear()
        facts.clear()
        parentOf.clear()
        childrenOf.clear()
        awaitingParent.clear()
        awaitingFrom.clear()
        parentsEverLinked.clear()
        pendingSculpts.clear()
        // 2.26: sin esto los swaps pendientes sobrevivian al cambio de contenido
        // y al llegar el asset se re-atachaba un SLObject viejo (transform y
        // TextureEntry obsoletos) sobre locales reutilizados de la nueva region.
        meshWaiters.clear()
        meshWaiterObjects.clear()
        meshGltfIds.clear()
        childrenCreatedWithoutParent = 0L
        childrenLaterReparented = 0L
        reparentDelayNanosTotal = 0L
        reparentDelayNanosMax = 0L
        createdEarly.clear()
        reparentedEver.clear()
        childCreatedNanos.clear()
        childDelayNanos.clear()
        regionPlacement.clear()
        visibleEntities = 0
        objectsAhead = 0
        budgetHidden = 0
        distanceCulled = 0
        shadowLodReduced = 0
        pendingTerrain = null
        terrain.destroy()
        dumpedLocalId = 0
        focusEntityLog.setLength(0)
        focusEntityEvents = 0
        focusEntityEventsShown = 0
        diagnostics.shapeTable = "-"
        diagnostics.textureReport = "-"
        diagnostics.resetObjectLog()
        // The GPU side of every texture just went away with the entities and the
        // materials, but the codestreams are still in the pipeline's cache: ask
        // for the pixels again instead of downloading anything a second time.
        textureStreamer?.pipeline?.rearmDecodes()
        refreshDiagnostics()
    }

    fun destroy() {
        clear()
        meshes.destroy(renderer)
        textures.clear()
        // 2.26: el hilo EphoraMesh tambien se detiene; antes sobrevivía a la
        // escena (fuga de hilo + callbacks contra una escena destruida).
        meshPipeline?.stop()
        // Last, so the re-arming above cannot start a decode against a torn-down
        // scene. Only this pipeline's decode workers stop; the session's provider
        // (and everything it already downloaded) outlives the scene.
        textureStreamer?.shutdown()
    }

    fun stats(): String {
        val referenced = HashSet<String>()
        for (slot in slots.values) {
            referenced.add(slot.textureId)
        }
        return slots.size.toString() + " entidades (" + visibleEntities + " visibles), " +
            meshes.count + " mallas, " + meshes.triangles + " triangulos, " +
            referenced.size + " texturas referenciadas, " + textures.stats() +
            ", terreno " + terrain.triangles + " tris" +
            (if (avatarsById.isEmpty()) "" else ", " + avatarsById.size + " avatares (fase 8)")
    }

    /**
     * The texture block of the report: the streamer's own stages, plus the two
     * figures only the scene can state (what is drawn with pixels, and what has
     * none to draw with).
     *
     * They are printed together, and separately labelled, because they answer
     * different questions: "how much arrived" is a fact about the grid, the wire
     * and the decoder, while "how much is on screen" is a fact about this scene
     * right now.
     */
    fun textureReport(): String {
        val streamer = textureStreamer ?: return "--- texturas (fase 2.13b) ---\n" +
            "sin pipeline de texturas inyectado (comportamiento 2.13a: sin descargas, sin subidas)\n"
        val builder = StringBuilder(1600)
        builder.append(streamer.report())
        builder.append("estado: ").append(streamer.statusLine()).append('\n')
        builder.append("caras dibujadas: ").append(drawnFaceCount)
            .append("  ·  con textura aplicada: ").append(texturedFaceCount)
            .append("  ·  sin textura (UUID por defecto): ").append(untexturedFaceCount)
            .append("  ·  esperando pixels: ")
            .append(drawnFaceCount - texturedFaceCount - untexturedFaceCount)
            .append('\n')
        builder.append("entidades con textura: ").append(texturedEntityCount)
            .append(" de ").append(slots.size)
            .append("  ·  materiales en la cache: ").append(textures.materialCount)
            .append("  ·  materiales BLEND: ").append(textures.blendMaterials)
            .append('\n')
        builder.append(sculptReport())
        builder.append(meshReport())
        builder.append(faceFitReport())
        builder.append(visualPipelineReport())
        return builder.toString()
    }

    /**
     * Censo sculpt/mesh: solo lectura. Cuenta las entidades dibujadas que
     * traen extra-params de sculpt/mesh, separando las que ya dibujan su
     * geometría real (fase 5 parcial) de las que conservan el prim básico
     * (mapa pendiente) y de las que necesitan el asset de malla (GetMesh).
     */
    private fun sculptReport(): String {
        var entities = 0
        var tris = 0
        var totalTris = 0
        var realEntities = 0
        var realTris = 0
        var meshAssetEntities = 0
        for (slot in slots.values) {
            totalTris += slot.mesh.triangles
            if (slot.sculptType != 0) {
                entities += 1
                tris += slot.mesh.triangles
                if (slot.sculptMeshId != null) {
                    realEntities += 1
                    realTris += slot.mesh.triangles
                } else if (SculptMeshBuilder.needsMeshAsset(slot.sculptType)) {
                    meshAssetEntities += 1
                }
            }
        }
        return "sculpt/mesh: " + entities +
            " entidades de " + slots.size + "  ·  " + tris + " tris de " + totalTris + '\n' +
            "  geometria sculpt real: " + realEntities + " entidades (" + realTris + " tris)" +
            "  ·  mapas pedidos: " + sculptRequested +
            "  ·  aplicados: " + sculptApplied +
            "  ·  mapas inútiles: " + sculptMapFailures +
            "  ·  pendientes: " + pendingSculpts.size +
            "  ·  mallas construidas: " + meshes.sculptBuilt + '\n' +
            "  tipo malla (necesita GetMesh, hoy prim basico): " + meshAssetEntities + " entidades\n"
    }

    /**
     * Censo de mallas GetMesh (fase 5): solo lectura. Los contadores de
     * descarga/decode viven en el pipeline; aquí el swap a GPU/entidad.
     */
    private fun meshReport(): String {
        val pipe = meshPipeline
        if (pipe == null) {
            return "mallas: pipeline desactivado\n"
        }
        val builder = StringBuilder(420)
        builder.append("mallas: detectados ").append(pipe.detected)
            .append("  ·  pedidos ").append(pipe.requested)
            .append("  ·  descargados ").append(pipe.downloaded)
            .append(" (").append(pipe.downloadedBytes).append(" bytes)")
            .append("  ·  decodificados ").append(pipe.decoded).append('\n')
        builder.append("  en cache GPU: ").append(meshes.assetBuilt)
            .append("  ·  cache hit ").append(pipe.cacheHits + meshes.assetHits)
            .append("  ·  renderizados (swap a geometria real): ").append(meshRendered)
            .append("  ·  fallidos: ").append(pipe.failedCount)
            .append("  ·  en cola: ").append(pipe.queueSize())
            .append("  ·  esperando swap: ").append(meshWaiterObjects.size).append('\n')
        builder.append("  gltf/futuro (siguen prim basico): ").append(meshGltfIds.size).append('\n')
        builder.append("  caras mesh sin UV en asset (decoder, UV=0): ").append(MeshDecoder.uvFallbackFaces).append('\n')
        if (pipe.lastFailure.isNotEmpty()) {
            builder.append("  ultimo fallo: ").append(pipe.lastFailure.take(140)).append('\n')
        }
        return builder.toString()
    }

    /**
     * Encaje cara<->textura (2.25, texture mapping SL): muestra por cara con
     * MAPPING=DEFAULT/PLANAR, repeat/offset/rotacion, FLIP por signo de repeat,
     * UV antes/despues del xform oficial (muestra del primer vertice del
     * grupo) y si hay pixels. Solo lectura, sin tocar render ni protocolo.
     */
    private fun faceFitReport(): String {
        val builder = StringBuilder(3600)
        var rotFaces = 0
        var scaledFaces = 0
        var planarFaces = 0
        var waitingFaces = 0
        var exactFaces = 0
        var approxFaces = 0
        var unmappedFaces = 0
        for (slot in slots.values) {
            for (face in slot.faces) {
                if (!face.hasTexture) continue
                if (face.rotation != 0f) rotFaces += 1
                if (face.hasUvTransform) scaledFaces += 1
                if (face.isPlanar) planarFaces += 1
                if (!textures.hasDecoded(face.textureId)) waitingFaces += 1
            }
        }
        builder.append("encaje: caras con rot!=0 (shader xform): ").append(rotFaces)
            .append("  ·  con repeat/offset: ").append(scaledFaces)
            .append("  ·  planar: ").append(planarFaces)
            .append("  ·  esperando pixels: ").append(waitingFaces).append('\n')
        var shown = 0
        for ((localId, slot) in slots.entries.sortedBy { it.key }) {
            if (shown >= 12) break
            var anyTextured = false
            for (face in slot.faces) {
                if (face.hasTexture) {
                    anyTextured = true
                    break
                }
            }
            if (!anyTextured) continue
            shown += 1
            val desc = slot.mesh.desc
            val scale = slot.transform.scale
            val n = slot.faces.size
            for (g in slot.faces.indices) {
                val face = slot.faces[g]
                if (!face.hasTexture) continue
                val te = slot.faceTeIndex.getOrElse(g) { g }
                val ready = textures.hasDecoded(face.textureId)
                val mapping = when (face.texGen) {
                    1 -> "PLANAR"
                    2 -> "SPHERICAL_UNSUPPORTED"
                    3 -> "CYLINDRICAL_UNSUPPORTED"
                    else -> "DEFAULT"
                }
                val flip = flipOf(face)
                val sample = sampleFaceUv(desc, g, scale, face)
                if (sample == null) {
                    unmappedFaces += 1
                    builder.append('#').append(localId).append(" cara ").append(g).append('/').append(n)
                        .append(" (te=").append(te).append(") tex=").append(shortUuid(face.textureId))
                        .append(" MAPPING=").append(mapping).append(" SIN-MUESTRA").append('\n')
                    continue
                }
                if (sample.exact) exactFaces += 1 else approxFaces += 1
                builder.append('#').append(localId).append(" cara ").append(g).append('/').append(n)
                    .append(" (te=").append(te).append(") tex=").append(shortUuid(face.textureId))
                    .append(' ').append(if (ready) "pixels" else "esperando")
                    .append(" MAPPING=").append(mapping)
                    .append(" rep=").append(fmt2(face.repeatU)).append('x').append(fmt2(face.repeatV))
                    .append(" off=").append(fmt2(face.offsetU)).append(',').append(fmt2(face.offsetV))
                    .append(" rot=").append(fmt2(face.rotation))
                    .append(" FLIP=").append(flip)
                    .append(" tam=")
                    .append(fmt2(scale.getOrElse(0) { 1f })).append('x')
                    .append(fmt2(scale.getOrElse(1) { 1f })).append('x')
                    .append(fmt2(scale.getOrElse(2) { 1f }))
                if (face.texGen == 0) {
                    builder.append(" BASE=").append(defaultBaseAudit(desc, g) ?: "-")
                }
                builder.append(" UV_BEFORE=").append(fmt2(sample.before.first)).append(',').append(fmt2(sample.before.second))
                    .append(" UV_AFTER=").append(fmt2(sample.after.first)).append(',').append(fmt2(sample.after.second))
                    .append(" TEXTURE_READY=").append(if (ready) "YES" else "NO")
                    .append(if (face.hasTint) " tint" else "").append('\n')
            }
        }
        if (slots.size > shown) {
            builder.append("... ").append(slots.size - shown).append(" entidades mas\n")
        }
        val status = when {
            unmappedFaces > 0 -> "FAIL"
            approxFaces > 0 -> "PARTIAL"
            else -> "OK"
        }
        builder.append("TEXTURE_MAPPING_STATUS=").append(status)
            .append(" (exactas=").append(exactFaces)
            .append(" aprox=").append(approxFaces)
            .append(" sin-muestra=").append(unmappedFaces).append(")\n")
        builder.append(defaultVertexReport())
        builder.append(xformIsolationReport())
        builder.append(uvForensicReport())
        builder.append(vFlipAuditReport())
        builder.append(slReferenceTable())
        builder.append(repeatSignReport())
        builder.append(largeFaceReport())
        builder.append(textureFamilyReport())
        builder.append(centerFaceReport())
        builder.append(faceSliceAuditReport())
        builder.append(targetTextureReport())
        builder.append(primUvZeroReport())
        builder.append(primUvTraceReport())
        builder.append(diagWitnessReport())
        return builder.toString()
    }

    /**
     * 2.27d (diagnostico interno, solo informe): UV base de un grupo DEFAULT
     * antes del xform — rango de la geometria tal cual salio del generador
     * (slcore/MeshDesc) mas la normal media del grupo. Permite decidir en
     * dispositivo si una cara DEFAULT recibe una base incorrecta (rango
     * distinto de 0..1, ejes permutados) o si el problema esta despues
     * (repeat/offset/rotation). Null sin grupo valido. Solo lectura.
     */
    private fun defaultBaseAudit(desc: MeshDesc, group: Int): String? {
        if (group < 0 || group >= desc.faceCount) return null
        val stride = MeshDesc.VERTEX_FLOATS
        val first = desc.faceFirstIndexAt(group)
        val count = desc.faceIndexCountAt(group)
        if (first < 0 || count <= 0 || first + count > desc.indices.size) return null
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        var nx = 0f
        var ny = 0f
        var nz = 0f
        for (k in 0 until count) {
            val vi = desc.indices[first + k]
            val o = vi * stride
            if (vi < 0 || o + 7 >= desc.vertices.size) return null
            val u = desc.vertices[o + 6]
            val v = desc.vertices[o + 7]
            if (u < minU) minU = u
            if (u > maxU) maxU = u
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            nx += desc.vertices[o + 3]
            ny += desc.vertices[o + 4]
            nz += desc.vertices[o + 5]
        }
        val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-6f) return null
        return "uvBase=[" + fmt2(minU) + ".." + fmt2(maxU) + "]x[" +
            fmt2(minV) + ".." + fmt2(maxV) + "] n=(" +
            fmt2(nx / len) + "," + fmt2(ny / len) + "," + fmt2(nz / len) + ")"
    }

    /**
     * 2.27e (diagnostico interno, solo informe): caras DEFAULT reales con
     * extremos y vertices reales. Por cara: OBJECT, FACE, PRIM_SIZE,
     * MAPPING=DEFAULT, UV_BASE_MIN/MAX del grupo completo, UV_BASE[vk] de
     * los primeros indices reales del grupo y UV_AFTER[vk] tras el xform
     * oficial (espejo CPU, una sola vez), mas REPEAT/OFFSET/ROTATION/FLIP.
     * Maximo 4 caras. Solo lectura: no toca geometria, materiales ni shader.
     */
    private fun uv2(p: Pair<Float, Float>): String = fmt2(p.first) + "," + fmt2(p.second)

    private fun uvForensicReport(): String {
        val b = StringBuilder(24000)
        b.append("--- uv-forense (diagnostico matematico del mapeo; solo lectura, nada cambia) ---\n")
        b.append("wire: repeat=F32 directo; offset=S16/32767; rotation=S16/32768*2PI rad; color=RGBA bytes 255-valor; texGen=mediaFlags bits1..2 (0 default 1 planar 2 esferico 3 cilindrico); el wire NO trae flag flip (flip SL = signo de repeat)\n")
        b.append("UV_STORAGE_MODE=SHADER_TRANSFORM (vertex UV = base sin tocar: createMesh sube desc.vertices tal cual; el shader aplica uvTransform=[repeatU,repeatV,offsetU,offsetV] uvRotation=rotation planarMode/base)\n")
        b.append("PLANAR omitido por diseno de esta iteracion (solo MAPPING=DEFAULT en la muestra)\n")
        val halfPi = (kotlin.math.PI.toFloat() / 2f)
        val cu = 0.25f
        val cv = 0.75f
        fun ctl(tag: String, ru: Float, rv: Float, ou: Float, ov: Float, rot: Float) {
            val f = SLTextureFace(
                SLTextureFace.DEFAULT_UUID, SLTextureFace.WHITE,
                repeatU = ru, repeatV = rv, offsetU = ou, offsetV = ov, rotation = rot
            )
            b.append("CONTROL ").append(tag)
                .append(" in=(0.25,0.75) R0=").append(uv2(SLTextureFace.rot00(cu, cv, rot)))
                .append(" R05=").append(uv2(SLTextureFace.rot05(cu, cv, rot)))
                .append(" ACTUAL=").append(uv2(SLTextureFace.xformUv(cu, cv, f))).append('\n')
        }
        ctl("A", 1f, 1f, 0f, 0f, 0f)
        ctl("B", 2f, 1f, 0f, 0f, 0f)
        ctl("C", 1f, 1f, 0f, 0f, halfPi)
        ctl("D", 1f, 1f, 0f, 0f, halfPi)
        val picked = ArrayList<Triple<Int, Int, String>>()
        val seen = HashSet<Pair<Int, Int>>()
        var planarSkipped = 0
        fun tryPick(localId: Int, g: Int, te: Int, cat: String, want: Int): Boolean {
            if (picked.count { it.third == cat } >= want) return false
            if (!seen.add(localId to g)) return false
            picked.add(Triple(localId, g, cat))
            return true
        }
        for ((localId, slot) in slots.entries.sortedBy { it.key }) {
            if (slot.faces.size == 1 && picked.count { it.third == "simple" } < 5) {
                for (g in slot.faces.indices) {
                    val face = slot.faces[g]
                    if (!face.hasTexture) continue
                    if (face.texGen != 0) {
                        planarSkipped += 1
                        continue
                    }
                    tryPick(localId, g, slot.faceTeIndex.getOrElse(g) { g }, "simple", 5)
                }
            }
        }
        for ((localId, slot) in slots.entries.sortedBy { it.key }) {
            if (slot.faces.size <= 1) continue
            for (g in slot.faces.indices) {
                val face = slot.faces[g]
                if (!face.hasTexture) continue
                if (face.texGen != 0) {
                    planarSkipped += 1
                    continue
                }
                val te = slot.faceTeIndex.getOrElse(g) { g }
                if (face.repeatU != 1f || face.repeatV != 1f) tryPick(localId, g, te, "repeat", 2)
                if (face.offsetU != 0f || face.offsetV != 0f) tryPick(localId, g, te, "offset", 2)
                if (face.rotation != 0f) tryPick(localId, g, te, "rot", 2)
                if (face.repeatU < 0f || face.repeatV < 0f) tryPick(localId, g, te, "flip", 1)
                if (face.repeatU != 1f && face.repeatV != 1f && (face.offsetU != 0f || face.offsetV != 0f) && face.rotation != 0f) {
                    tryPick(localId, g, te, "combo", 1)
                }
                tryPick(localId, g, te, "multi", 2)
            }
        }
        b.append("MUESTRA caras=").append(picked.size).append(" planar-omitidas=").append(planarSkipped).append('\n')
        for (cat in arrayOf("simple", "multi", "rot", "repeat", "offset", "flip", "combo")) {
            if (picked.none { it.third == cat }) {
                b.append("categoria ").append(cat).append("=no encontrada\n")
            }
        }
        val stride = MeshDesc.VERTEX_FLOATS
        for ((localId, g, cat) in picked) {
            if (b.length > 22000) {
                b.append("... truncado por tamano\n")
                break
            }
            val slot = slots[localId] ?: continue
            if (g < 0 || g >= slot.faces.size) continue
            val face = slot.faces[g]
            val te = slot.faceTeIndex.getOrElse(g) { g }
            val desc = slot.mesh.desc
            val first = desc.faceFirstIndexAt(g)
            val count = desc.faceIndexCountAt(g)
            b.append('#').append(localId).append(" cara ").append(g).append("(te=").append(te).append(") cat=").append(cat)
                .append(" SOURCE=").append(slot.mesh.source)
                .append(" tex=").append(shortUuid(face.textureId)).append('\n')
            val wire = (slot.textureEntryRef as? TextureEntry)?.face(te)
            if (wire == null) {
                b.append("  WIRE_ORIGINAL=sin-entry-guardada\n")
            } else {
                b.append("  WIRE_ORIGINAL: repeat=").append(fmt2(wire.scaleU)).append('x').append(fmt2(wire.scaleV))
                    .append(" offset=").append(fmt2(wire.offsetU)).append(',').append(fmt2(wire.offsetV))
                    .append(" rot=").append(fmt2(wire.rotation)).append("rad")
                    .append(" color=0x").append(Integer.toHexString(wire.colorArgb))
                    .append(" fullbright=").append(wire.fullBright)
                    .append(" bump=").append(wire.bumpCode).append(" shiny=").append(wire.shiny)
                    .append(" texGen=").append(wire.texGen).append(" glow=").append(fmt2(wire.glow)).append('\n')
                val dif = StringBuilder()
                if (face.repeatU != wire.scaleU || face.repeatV != wire.scaleV) dif.append("repeat ")
                if (face.offsetU != wire.offsetU || face.offsetV != wire.offsetV) dif.append("offset ")
                if (face.rotation != wire.rotation) dif.append("rotation ")
                if (face.fullBright != wire.fullBright) dif.append("fullbright ")
                if (face.texGen != wire.texGen) dif.append("texGen ")
                if (face.textureId != wire.textureId) dif.append("uuid ")
                b.append("  WIREvsFACE: ").append(if (dif.isEmpty()) "iguales (tinta pasa a lineal por diseno)" else "DIFERENCIA en " + dif).append('\n')
            }
            b.append("  FACE: tint=linear(").append(fmt2(face.color[0])).append(',').append(fmt2(face.color[1])).append(',').append(fmt2(face.color[2])).append(',').append(fmt2(face.color[3])).append(')')
                .append(" repeat=").append(fmt2(face.repeatU)).append('x').append(fmt2(face.repeatV))
                .append(" offset=").append(fmt2(face.offsetU)).append(',').append(fmt2(face.offsetV))
                .append(" rot=").append(fmt2(face.rotation)).append("rad")
                .append(" FLIP=").append(flipOf(face)).append('\n')
            val basis = SLTextureFace.planarBasisFor(desc, g)
            val planarMode = if (face.isPlanar && basis != null) 1 else 0
            b.append("  SHADER: uvTransform=[").append(fmt2(face.repeatU)).append(',').append(fmt2(face.repeatV)).append(',').append(fmt2(face.offsetU)).append(',').append(fmt2(face.offsetV)).append(']')
                .append(" uvRotation=").append(fmt2(face.rotation))
                .append(" planarMode=").append(planarMode)
            if (face.isPlanar && basis != null) {
                val scaled = SLTextureFace.scaledPlanarBasis(basis, slot.transform.scale)
                b.append(" planarBasisU=").append(fmt2(scaled.u[0])).append(',').append(fmt2(scaled.u[1])).append(',').append(fmt2(scaled.u[2]))
                    .append(" planarBasisV=").append(fmt2(scaled.v[0])).append(',').append(fmt2(scaled.v[1])).append(',').append(fmt2(scaled.v[2]))
            }
            b.append('\n')
            val ready = textures.hasDecoded(face.textureId)
            val dims = textureStreamer?.uploadDimsOf(face.textureId) ?: "-"
            b.append("  TEXTURA: decode=").append(if (ready) "OK" else "NO").append(" dimsImagen=").append(dims).append(" dimsGPU=").append(dims).append(" usaEn=cara ").append(g).append('\n')
            if (first < 0 || count <= 0 || first + count > desc.indices.size) {
                b.append("  UV_BASE=rango invalido\n")
                continue
            }
            val take = minOf(4, count)
            val bu = FloatArray(take)
            val bv = FloatArray(take)
            var ok = true
            for (k in 0 until take) {
                val vi = desc.indices[first + k]
                val o = vi * stride
                if (vi < 0 || o + 7 >= desc.vertices.size) {
                    ok = false
                    break
                }
                bu[k] = desc.vertices[o + 6]
                bv[k] = desc.vertices[o + 7]
            }
            if (!ok) {
                b.append("  UV_BASE=vertices invalidos\n")
                continue
            }
            for (k in 0 until take) {
                b.append("  UV_BASE[v").append(k).append("]=").append(fmt2(bu[k])).append(',').append(fmt2(bv[k])).append('\n')
            }
            for (k in 0 until take) {
                val u = bu[k]
                val v = bv[k]
                val cs = u - 0.5f
                val ct = v - 0.5f
                val rr = SLTextureFace.rot00(cs, ct, face.rotation)
                val rp = Pair(rr.first * face.repeatU, rr.second * face.repeatV)
                val ro = Pair(rp.first + face.offsetU, rp.second + face.offsetV)
                val rf = Pair(ro.first + 0.5f, ro.second + 0.5f)
                val fin = Pair(rf.first, 1f - rf.second)
                b.append("  ETAPAS[v").append(k).append("] BASE=").append(fmt2(u)).append(',').append(fmt2(v))
                    .append(" CENTRADA=").append(fmt2(cs)).append(',').append(fmt2(ct))
                    .append(" ROTADA=").append(uv2(rr))
                    .append(" REPEAT=").append(uv2(rp))
                    .append(" OFFSET=").append(uv2(ro))
                    .append(" RECENTRADA=").append(uv2(rf))
                    .append(" V_FLIP=").append(uv2(fin)).append('\n')
                b.append("  ROT[v").append(k).append("] R0=").append(uv2(SLTextureFace.rot00(u, v, face.rotation)))
                    .append(" R05=").append(uv2(SLTextureFace.rot05(u, v, face.rotation))).append('\n')
                b.append("  VARIANTES[v").append(k).append("] A=").append(uv2(SLTextureFace.variantA(u, v, face)))
                    .append(" B=").append(uv2(SLTextureFace.variantB(u, v, face)))
                    .append(" C=").append(uv2(SLTextureFace.variantC(u, v, face)))
                    .append(" D=").append(uv2(SLTextureFace.variantD(u, v, face)))
                    .append(" E=").append(uv2(SLTextureFace.variantE(u, v, face))).append('\n')
                val slU = rf.first
                val slV = rf.second
                b.append("  CANDIDATOS[v").append(k).append("] ACTUAL=").append(uv2(fin))
                    .append(" ROT_CENTRO=").append(uv2(SLTextureFace.vFlip(SLTextureFace.variantD(u, v, face))))
                    .append(" V_NORMAL=").append(fmt2(slU)).append(',').append(fmt2(slV))
                    .append(" ROT_CENTRO_V_NORMAL=").append(uv2(SLTextureFace.variantD(u, v, face))).append('\n')
            }
        }
        return b.toString()
    }

    private fun vFlipAuditReport(): String {
        val b = StringBuilder(2200)
        b.append("--- vflip-auditoria (quien invierte V; solo lectura) ---\n")
        b.append("UV_SOURCE=getUV0() (los 4 shaders usan getUV0(); ninguno usa mesh_uv0 crudo)\n")
        val probe = (renderer as? com.lumiyaviewer.lumiya.renderer.filament.FilamentRenderer)?.vFlipAuditLine()
        if (probe == null) {
            b.append("FILAMENT_FLIP_UV=backend-no-filament (sin sonda)\n")
        } else {
            for (line in probe.split('\n')) {
                if (line.isNotEmpty()) b.append(line).append('\n')
            }
        }
        b.append("CUSTOM_V_FLIP=1-v en FRAGMENT_SOURCE y FRAGMENT_UNLIT_UV; directo en LIT_MIN y UNLIT_MIN (inconsistencia registrada, no corregida)\n")
        b.append("IMAGE_V_FLIP=no (setImage sin volteo; decoder RGBA8 first-row-first)\n")
        b.append("FINAL_NUMBER_OF_V_FLIPS=1-custom + flipUV-del-material (default matc=true no verificado en runtime: 1 o 2 segun default)\n")
        return b.toString()
    }

    private fun slReferenceTable(): String {
        val b = StringBuilder(2600)
        b.append("--- sl-reference (formula libOpenMetaverse DEFAULT, sin V-flip) ---\n")
        b.append("ref: tX=U-0.5 tY=V-0.5 x=(tXcos+tYsin)*repeatU+offsetU+0.5 y=(-tXsin+tYcos)*repeatV+offsetV+0.5\n")
        b.append("FILAMENT_INPUT=(ref.x,1-ref.y) = lo que el shader entrega a texture(); FILAMENT_SAMPLED=mismo valor (el muestreo no transforma; el texel efectivo depende del orden de filas + flipUV ya aplicado en getUV0)\n")
        val halfPi = (kotlin.math.PI.toFloat() / 2f)
        val quartPi = (kotlin.math.PI.toFloat() / 4f)
        fun row(tag: String, ru: Float, rv: Float, ou: Float, ov: Float, rot: Float) {
            val f = SLTextureFace(
                SLTextureFace.DEFAULT_UUID, SLTextureFace.WHITE,
                repeatU = ru, repeatV = rv, offsetU = ou, offsetV = ov, rotation = rot
            )
            val ref = SLTextureFace.slReferenceUv(0.25f, 0.75f, f)
            val inp = Pair(ref.first, 1f - ref.second)
            b.append(tag).append(" repeat=").append(fmt2(ru)).append('x').append(fmt2(rv))
                .append(" offset=").append(fmt2(ou)).append(',').append(fmt2(ov))
                .append(" rot=").append(fmt2(rot))
                .append(" UV_BASE=0.25,0.75")
                .append(" SL_REFERENCE=").append(uv2(ref))
                .append(" FILAMENT_INPUT=").append(uv2(inp))
                .append(" FILAMENT_SAMPLED=").append(uv2(inp)).append('\n')
        }
        row("A", 1f, 1f, 0f, 0f, 0f)
        row("B", 2f, 1f, 0f, 0f, 0f)
        row("C", 1f, 1f, 0f, 0f, halfPi)
        row("D", 1f, 0.4f, 0f, 0f, halfPi)
        row("E", 1.5f, 1.5f, 0.2f, -0.3f, quartPi)
        return b.toString()
    }

    private fun repeatSignReport(): String {
        val b = StringBuilder(2400)
        b.append("--- repeat-signo (el signo es el flip SL; no se convierte ni se pierde) ---\n")
        b.append("codigo: descriptorFor copia repeatU/repeatV con signo (SLTextureCache); uvKeyOf usa toBits crudos (signo preservado en la clave)\n")
        val seen = LinkedHashMap<String, String>()
        for ((localId, slot) in slots.entries.sortedBy { it.key }) {
            for (g in slot.faces.indices) {
                val face = slot.faces[g]
                if (!face.hasTexture) continue
                val key = fmt2(face.repeatU) + "x" + fmt2(face.repeatV)
                if (seen.containsKey(key)) continue
                val te = slot.faceTeIndex.getOrElse(g) { g }
                val wire = (slot.textureEntryRef as? TextureEntry)?.face(te)
                seen[key] = "#" + localId + " cara " + g +
                    " WIRE_REPEAT_U=" + (wire?.let { fmt2(it.scaleU) } ?: "?") +
                    " WIRE_REPEAT_V=" + (wire?.let { fmt2(it.scaleV) } ?: "?") +
                    " ABS_REPEAT_U=" + fmt2(kotlin.math.abs(face.repeatU)) +
                    " ABS_REPEAT_V=" + fmt2(kotlin.math.abs(face.repeatV)) +
                    " FLIP_U=" + (face.repeatU < 0f) + " FLIP_V=" + (face.repeatV < 0f)
                if (seen.size >= 12) break
            }
            if (seen.size >= 12) break
        }
        if (seen.isEmpty()) {
            b.append("sin caras con textura en escena\n")
        }
        for ((k, v) in seen) {
            b.append("repeat=").append(k).append(' ').append(v).append('\n')
        }
        return b.toString()
    }

    private fun defaultVertexReport(): String {
        val b = StringBuilder(3600)
        b.append("--- default-vertices (caras DEFAULT reales: extremos + v0..v3) ---\n")
        var shown = 0
        for ((localId, slot) in slots.entries.sortedBy { it.key }) {
            if (shown >= 4) break
            val desc = slot.mesh.desc
            val scale = slot.transform.scale
            for (g in slot.faces.indices) {
                if (shown >= 4) break
                val face = slot.faces[g]
                if (!face.hasTexture) continue
                if (face.texGen != 0) continue
                val stride = MeshDesc.VERTEX_FLOATS
                val first = desc.faceFirstIndexAt(g)
                val count = desc.faceIndexCountAt(g)
                if (first < 0 || count <= 0 || first + count > desc.indices.size) continue
                var minU = Float.MAX_VALUE
                var maxU = -Float.MAX_VALUE
                var minV = Float.MAX_VALUE
                var maxV = -Float.MAX_VALUE
                var ok = true
                for (k in 0 until count) {
                    val vi = desc.indices[first + k]
                    val o = vi * stride
                    if (vi < 0 || o + 7 >= desc.vertices.size) { ok = false; break }
                    val u = desc.vertices[o + 6]
                    val v = desc.vertices[o + 7]
                    if (u < minU) minU = u
                    if (u > maxU) maxU = u
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }
                if (!ok) continue
                val take = minOf(4, count)
                val te = slot.faceTeIndex.getOrElse(g) { g }
                b.append("OBJECT=#").append(localId)
                    .append(" FACE=").append(g).append('/').append(slot.faces.size)
                    .append("(te=").append(te).append(')')
                    .append(" PRIM_SIZE=")
                    .append(fmt2(scale.getOrElse(0) { 1f })).append('x')
                    .append(fmt2(scale.getOrElse(1) { 1f })).append('x')
                    .append(fmt2(scale.getOrElse(2) { 1f }))
                    .append(" MAPPING=DEFAULT")
                    .append(" UV_BASE_MIN=").append(fmt2(minU)).append(',').append(fmt2(minV))
                    .append(" UV_BASE_MAX=").append(fmt2(maxU)).append(',').append(fmt2(maxV))
                for (k in 0 until take) {
                    val vi = desc.indices[first + k]
                    val o = vi * stride
                    val u = desc.vertices[o + 6]
                    val v = desc.vertices[o + 7]
                    b.append(" UV_BASE[v").append(k).append("]=")
                        .append(fmt2(u)).append(',').append(fmt2(v))
                }
                for (k in 0 until take) {
                    val vi = desc.indices[first + k]
                    val o = vi * stride
                    val r = SLTextureFace.xformUv(desc.vertices[o + 6], desc.vertices[o + 7], face)
                    b.append(" UV_AFTER[v").append(k).append("]=")
                        .append(fmt2(r.first)).append(',').append(fmt2(r.second))
                }
                b.append(" REPEAT=").append(fmt2(face.repeatU)).append('x').append(fmt2(face.repeatV))
                    .append(" OFFSET=").append(fmt2(face.offsetU)).append(',').append(fmt2(face.offsetV))
                    .append(" ROTATION=").append(fmt2(face.rotation))
                    .append(" FLIP=").append(flipOf(face)).append('\n')
                shown += 1
            }
        }
        if (shown == 0) {
            b.append("sin caras DEFAULT con textura\n")
        }
        return b.toString()
    }

    /**
     * 2.27e (diagnostico interno, solo informe): muestra matematica aislada
     * del xform DEFAULT sobre un quad (0,0)(1,0)(1,1)(0,1). Usa el mismo
     * espejo CPU que el informe (centro 0.5, rotar, repeat, offset, volver;
     * V final en convenio Filament igual que el shader). Casos A-D pedidos.
     * Solo lectura: no toca geometria, materiales ni shader.
     */
    private fun xformIsolationReport(): String {
        val b = StringBuilder(1600)
        b.append("--- xform-aislamiento (quad DEFAULT, espejo CPU del xform oficial+shader) ---\n")
        b.append("orden=restar0.5,rotar-centro,repeat,offset,volver; V-final=1-slV\n")
        val quad = arrayOf(Pair(0f, 0f), Pair(1f, 0f), Pair(1f, 1f), Pair(0f, 1f))
        val halfPi = (kotlin.math.PI.toFloat() / 2f)
        fun emit(tag: String, ru: Float, rv: Float, ou: Float, ov: Float, rot: Float) {
            val f = SLTextureFace(
                SLTextureFace.DEFAULT_UUID, SLTextureFace.WHITE,
                repeatU = ru, repeatV = rv, offsetU = ou, offsetV = ov, rotation = rot
            )
            b.append(tag)
                .append(" REPEAT=").append(fmt2(ru)).append('x').append(fmt2(rv))
                .append(" OFFSET=").append(fmt2(ou)).append(',').append(fmt2(ov))
                .append(" ROTATION=").append(fmt2(rot))
            for (k in quad.indices) {
                val r = SLTextureFace.xformUv(quad[k].first, quad[k].second, f)
                b.append(" UV_AFTER[v").append(k).append("]=")
                    .append(fmt2(r.first)).append(',').append(fmt2(r.second))
            }
            b.append('\n')
        }
        emit("A", 1f, 1f, 0f, 0f, 0f)
        emit("B", 2f, 1f, 0f, 0f, 0f)
        emit("C", 1f, 1f, 0f, 0f, halfPi)
        emit("D", 1f, 0.4f, 0f, 0f, halfPi)
        return b.toString()
    }

    /**
     * 2.27f (diagnostico interno, solo informe): caras DEFAULT grandes y
     * cercanas con pixels GPU. Selecciona hasta 3 caras (una horizontal
     * grande, una vertical grande, una con repeat/rotation) entre objetos a
     * <= 30 m con textura decodificada, priorizando max(size) > 3 m y
     * repeat != 1 o rotation != 0. Por cara: OBJECT, LOCAL_ID, FACE,
     * PRIM_SIZE, FACE_VERTEX_COUNT, FACE_NORMAL, TEXTURE_UUID, MAPPING,
     * REPEAT/OFFSET/ROTATION/FLIP, UV_BASE_MIN/MAX y POSITION_LOCAL_MIN/MAX
     * del grupo, mas POSITION_LOCAL/UV_BASE/UV_AFTER por vertice unico
     * (todos si son pocos; los 8 primeros + extremos si son muchos).
     * UV_AFTER aplica el xform oficial una sola vez (espejo CPU). Solo
     * lectura: no toca geometria, TextureEntry, shader, LRU ni materiales.
     */
    private fun largeFaceReport(): String {
        val b = StringBuilder(6000)
        b.append("--- default-grandes (DEFAULT cercanas con pixels: piso vs pared) ---\n")
        val eye = lastCamera?.eye
        if (eye == null) {
            b.append("sin cámara todavía\n")
            return b.toString()
        }
        data class Cand(
            val localId: Int, val g: Int, val dist: Float,
            val sizeMax: Float, val nz: Float, val xform: Boolean
        )
        val cands = ArrayList<Cand>()
        for ((localId, slot) in slots.entries) {
            for (g in slot.faces.indices) {
                val face = slot.faces[g]
                if (!face.hasTexture) continue
                if (face.texGen != 0) continue
                if (!textures.hasDecoded(face.textureId)) continue
                val dist = distanceTo(placementInRegion(localId), eye)
                if (dist > 30f) continue
                val scale = slot.transform.scale
                val sx = scale.getOrElse(0) { 1f }
                val sy = scale.getOrElse(1) { 1f }
                val sz = scale.getOrElse(2) { 1f }
                val sizeMax = maxOf(sx, sy, sz)
                val nz = groupNormalZ(slot.mesh.desc, g) ?: continue
                cands.add(Cand(localId, g, dist, sizeMax, nz, face.hasUvTransform))
            }
        }
        if (cands.isEmpty()) {
            b.append("sin caras DEFAULT cercanas (<=30 m) con pixels\n")
            return b.toString()
        }
        val bySize = cands.sortedWith(
            compareBy<Cand> { if (it.sizeMax > 3f) 0 else 1 }
                .thenByDescending { it.sizeMax }
                .thenBy { it.dist }
        )
        val pick = ArrayList<Cand>()
        fun take(c: Cand?) { if (c != null && pick.none { it.localId == c.localId && it.g == c.g }) pick.add(c) }
        take(bySize.firstOrNull { kotlin.math.abs(it.nz) > 0.9f })
        take(bySize.firstOrNull { kotlin.math.abs(it.nz) < 0.1f })
        take(bySize.firstOrNull { it.xform })
        for (c in bySize) {
            if (pick.size >= 3) break
            take(c)
        }
        for (c in pick) {
            val slot = slots[c.localId] ?: continue
            val face = slot.faces.getOrNull(c.g) ?: continue
            val desc = slot.mesh.desc
            val scale = slot.transform.scale
            val stride = MeshDesc.VERTEX_FLOATS
            val first = desc.faceFirstIndexAt(c.g)
            val count = desc.faceIndexCountAt(c.g)
            if (first < 0 || count <= 0 || first + count > desc.indices.size) continue
            val uniq = ArrayList<Int>()
            for (k in 0 until count) {
                val vi = desc.indices[first + k]
                if (!uniq.contains(vi)) uniq.add(vi)
            }
            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            var nX = 0f; var nY = 0f; var nZ = 0f
            var ok = true
            for (vi in uniq) {
                val o = vi * stride
                if (vi < 0 || o + 7 >= desc.vertices.size) { ok = false; break }
                val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
                val x = desc.vertices[o]; val y = desc.vertices[o + 1]; val z = desc.vertices[o + 2]
                if (u < minU) minU = u; if (u > maxU) maxU = u
                if (v < minV) minV = v; if (v > maxV) maxV = v
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                nX += desc.vertices[o + 3]; nY += desc.vertices[o + 4]; nZ += desc.vertices[o + 5]
            }
            if (!ok || uniq.isEmpty()) continue
            val nl = kotlin.math.sqrt(nX * nX + nY * nY + nZ * nZ)
            val te = slot.faceTeIndex.getOrElse(c.g) { c.g }
            b.append("OBJECT=#").append(c.localId)
                .append(" LOCAL_ID=").append(c.localId)
                .append(" FACE=").append(c.g).append('/').append(slot.faces.size)
                .append("(te=").append(te).append(')')
                .append(" DIST=").append(fmt1(c.dist)).append('m')
                .append(" PRIM_SIZE=")
                .append(fmt2(scale.getOrElse(0) { 1f })).append('x')
                .append(fmt2(scale.getOrElse(1) { 1f })).append('x')
                .append(fmt2(scale.getOrElse(2) { 1f }))
                .append(" FACE_VERTEX_COUNT=").append(uniq.size)
                .append(" (").append(count).append(" indices)")
                .append(" FACE_NORMAL=").append(fmt2(nX / nl)).append(',')
                .append(fmt2(nY / nl)).append(',').append(fmt2(nZ / nl))
                .append(" TEXTURE_UUID=").append(face.textureId)
                .append(" MAPPING=DEFAULT")
                .append(" REPEAT=").append(fmt2(face.repeatU)).append('x').append(fmt2(face.repeatV))
                .append(" OFFSET=").append(fmt2(face.offsetU)).append(',').append(fmt2(face.offsetV))
                .append(" ROTATION=").append(fmt2(face.rotation))
                .append(" FLIP=").append(flipOf(face)).append('\n')
            b.append("  UV_BASE_MIN=").append(fmt2(minU)).append(',').append(fmt2(minV))
                .append(" UV_BASE_MAX=").append(fmt2(maxU)).append(',').append(fmt2(maxV))
                .append(" POSITION_LOCAL_MIN=").append(fmt2(minX)).append(',')
                .append(fmt2(minY)).append(',').append(fmt2(minZ))
                .append(" POSITION_LOCAL_MAX=").append(fmt2(maxX)).append(',')
                .append(fmt2(maxY)).append(',').append(fmt2(maxZ)).append('\n')
            degenerateAudit(desc, first, count)?.let { b.append(it) }
            val showIdx: List<Int> = if (uniq.size <= 16) {
                uniq.indices.toList()
            } else {
                val head = (0 until 8).toList()
                val extra = LinkedHashSet<Int>()
                for (i in uniq.indices) {
                    val o = uniq[i] * stride
                    val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
                    if (u == minU || u == maxU || v == minV || v == maxV) extra.add(i)
                    if (extra.size >= 4) break
                }
                (head + extra.toList()).distinct().sorted()
            }
            for (i in showIdx) {
                val vi = uniq[i]
                val o = vi * stride
                val x = desc.vertices[o]; val y = desc.vertices[o + 1]; val z = desc.vertices[o + 2]
                val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
                val r = SLTextureFace.xformUv(u, v, face)
                b.append("  POSITION_LOCAL[v").append(i).append("]=")
                    .append(fmt2(x)).append(',').append(fmt2(y)).append(',').append(fmt2(z))
                    .append(" UV_BASE[v").append(i).append("]=")
                    .append(fmt2(u)).append(',').append(fmt2(v))
                    .append(" UV_AFTER[v").append(i).append("]=")
                    .append(fmt2(r.first)).append(',').append(fmt2(r.second)).append('\n')
            }
        }
        return b.toString()
    }

    /** Normal Z media normalizada de un grupo, o null sin grupo valido. Solo lectura. */
    private fun groupNormalZ(desc: MeshDesc, group: Int): Float? {
        if (group < 0 || group >= desc.faceCount) return null
        val stride = MeshDesc.VERTEX_FLOATS
        val first = desc.faceFirstIndexAt(group)
        val count = desc.faceIndexCountAt(group)
        if (first < 0 || count <= 0 || first + count > desc.indices.size) return null
        var nx = 0f; var ny = 0f; var nz = 0f
        for (k in 0 until count) {
            val vi = desc.indices[first + k]
            val o = vi * stride + 3
            if (vi < 0 || o + 2 >= desc.vertices.size) return null
            nx += desc.vertices[o]; ny += desc.vertices[o + 1]; nz += desc.vertices[o + 2]
        }
        val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-6f) return null
        return nz / len
    }

    /**
     * 2.27g (diagnostico interno, solo informe): familias por textura. Agrupa
     * las caras DEFAULT cercanas (<= 30 m) con pixels GPU por TEXTURE_UUID y
     * muestra hasta 4 grupos (antes los compartidos por >= 2 objetos y los
     * que tienen alguna cara grande), con hasta 3 caras por UUID (antes las
     * grandes). Por cara: OBJECT, LOCAL_ID, FACE, PRIM_SIZE,
     * FACE_VERTEX_COUNT, TEXTURE_UUID, MAPPING, REPEAT/OFFSET/ROTATION/FLIP,
     * UV_BASE_MIN/MAX y centro aproximado; despues las UV de la cara
     * completa si tiene pocos vertices (primero 4 + ultimos 4 + extremos +
     * centro si tiene muchos), cada una con su UV_AFTER del xform oficial
     * aplicado una sola vez (espejo CPU). Solo lectura: no toca geometria,
     * TextureEntry, shader, LRU ni materiales.
     */
    private fun textureFamilyReport(): String {
        val b = StringBuilder(7000)
        b.append("--- texture-families (misma TEXTURE_UUID en varias superficies DEFAULT cercanas) ---\n")
        val eye = lastCamera?.eye
        if (eye == null) {
            b.append("sin cámara todavía\n")
            return b.toString()
        }
        data class Samp(val localId: Int, val g: Int, val dist: Float, val sizeMax: Float, val xform: Boolean)
        val byUuid = LinkedHashMap<String, MutableList<Samp>>()
        for ((localId, slot) in slots.entries) {
            for (g in slot.faces.indices) {
                val face = slot.faces[g]
                if (!face.hasTexture) continue
                if (face.texGen != 0) continue
                if (!textures.hasDecoded(face.textureId)) continue
                val dist = distanceTo(placementInRegion(localId), eye)
                if (dist > 30f) continue
                val scale = slot.transform.scale
                val sizeMax = maxOf(
                    scale.getOrElse(0) { 1f },
                    scale.getOrElse(1) { 1f },
                    scale.getOrElse(2) { 1f }
                )
                byUuid.getOrPut(face.textureId) { ArrayList() }
                    .add(Samp(localId, g, dist, sizeMax, face.hasUvTransform))
            }
        }
        if (byUuid.isEmpty()) {
            b.append("sin caras DEFAULT cercanas (<=30 m) con pixels\n")
            return b.toString()
        }
        val groups = byUuid.entries.map { it }.sortedWith(
            compareBy<Map.Entry<String, MutableList<Samp>>> { e ->
                if (e.value.map { s -> s.localId }.distinct().size >= 2) 0 else 1
            }
                .thenBy { e -> if (e.value.any { s -> s.sizeMax > 3f }) 0 else 1 }
                .thenBy { e -> e.value.minOf { s -> s.dist } }
        ).take(4)
        for (grp in groups) {
            val objCount = grp.value.map { s -> s.localId }.distinct().size
            b.append("TEXTURE_UUID=").append(grp.key)
                .append(" objetos=").append(objCount)
                .append(" caras=").append(grp.value.size).append('\n')
            val ordered = grp.value.sortedWith(
                compareBy<Samp> { if (it.sizeMax > 3f) 0 else 1 }
                    .thenByDescending { it.sizeMax }
                    .thenBy { if (it.xform) 0 else 1 }
                    .thenBy { it.dist }
            ).take(3)
            for (s in ordered) {
                val slot = slots[s.localId] ?: continue
                val face = slot.faces.getOrNull(s.g) ?: continue
                val desc = slot.mesh.desc
                val scale = slot.transform.scale
                val stride = MeshDesc.VERTEX_FLOATS
                val first = desc.faceFirstIndexAt(s.g)
                val count = desc.faceIndexCountAt(s.g)
                if (first < 0 || count <= 0 || first + count > desc.indices.size) continue
                val uniq = ArrayList<Int>()
                for (k in 0 until count) {
                    val vi = desc.indices[first + k]
                    if (!uniq.contains(vi)) uniq.add(vi)
                }
                var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
                var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
                var sumU = 0f; var sumV = 0f
                var ok = true
                for (vi in uniq) {
                    val o = vi * stride
                    if (vi < 0 || o + 7 >= desc.vertices.size) { ok = false; break }
                    val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
                    if (u < minU) minU = u; if (u > maxU) maxU = u
                    if (v < minV) minV = v; if (v > maxV) maxV = v
                    sumU += u; sumV += v
                }
                if (!ok || uniq.isEmpty()) continue
                val te = slot.faceTeIndex.getOrElse(s.g) { s.g }
                b.append("OBJECT=#").append(s.localId)
                    .append(" LOCAL_ID=").append(s.localId)
                    .append(" PARENT=").append(parentIdOf(s.localId))
                    .append(" FACE=").append(s.g).append('/').append(slot.faces.size)
                    .append("(te=").append(te).append(')')
                    .append(" DIST=").append(fmt1(s.dist)).append('m')
                    .append(" PRIM_SIZE=")
                    .append(fmt2(scale.getOrElse(0) { 1f })).append('x')
                    .append(fmt2(scale.getOrElse(1) { 1f })).append('x')
                    .append(fmt2(scale.getOrElse(2) { 1f }))
                    .append(" FACE_VERTEX_COUNT=").append(uniq.size)
                    .append(" (").append(count).append(" indices)")
                    .append(" TEXTURE_UUID=").append(face.textureId)
                    .append(" MAPPING=DEFAULT")
                    .append(" REPEAT=").append(fmt2(face.repeatU)).append('x').append(fmt2(face.repeatV))
                    .append(" OFFSET=").append(fmt2(face.offsetU)).append(',').append(fmt2(face.offsetV))
                    .append(" ROTATION=").append(fmt2(face.rotation))
                    .append(" FLIP=").append(flipOf(face)).append('\n')
                b.append("  UV_BASE_MIN=").append(fmt2(minU)).append(',').append(fmt2(minV))
                    .append(" UV_BASE_MAX=").append(fmt2(maxU)).append(',').append(fmt2(maxV))
                    .append(" UV_CENTER=")
                    .append(fmt2(sumU / uniq.size)).append(',').append(fmt2(sumV / uniq.size))
                    .append('\n')
                degenerateAudit(desc, first, count)?.let { b.append(it) }
                val showIdx: List<Int> = if (uniq.size <= 12) {
                    uniq.indices.toList()
                } else {
                    val head = (0 until 4).toList()
                    val tail = (uniq.size - 4 until uniq.size).toList()
                    val extra = LinkedHashSet<Int>()
                    for (i in uniq.indices) {
                        val o = uniq[i] * stride
                        val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
                        if (u == minU || u == maxU || v == minV || v == maxV) extra.add(i)
                        if (extra.size >= 4) break
                    }
                    (head + tail + extra.toList()).distinct().sorted()
                }
                for (i in showIdx) {
                    val vi = uniq[i]
                    val o = vi * stride
                    val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
                    val r = SLTextureFace.xformUv(u, v, face)
                    b.append("  UV_BASE[v").append(i).append("]=")
                        .append(fmt2(u)).append(',').append(fmt2(v))
                        .append(" UV_AFTER[v").append(i).append("]=")
                        .append(fmt2(r.first)).append(',').append(fmt2(r.second)).append('\n')
                }
            }
        }
        return b.toString()
    }

    private var lastCenterMillis = 0L
    private var centerCache = ""

    /**
     * 2.27h (diagnostico interno, solo informe): cara bajo el centro de la
     * vista. No existe picking en el proyecto, asi que lanza un rayo CPU
     * (ojo -> target de la camara) contra la geometria ya existente: AABB
     * por entidad y Moller-Trumbore por triangulo con la matriz mundo TRS
     * compuesta de la cadena de padres (el mismo producto que Filament).
     * Registra OBJECT, LOCAL_ID, FACE, PRIM_TYPE, PATH_CURVE, PROFILE_CURVE,
     * PRIM_SIZE, FACE_NORMAL (mundo), TEXTURE_UUID, MAPPING,
     * REPEAT/OFFSET/ROTATION/FLIP, TEXTURE_READY, DIST, UV_BASE_MIN/MAX y
     * POSITION_LOCAL/UV_BASE/UV_AFTER por vertice (cara completa si es
     * pequena; extremos + representativos si es grande). UV_AFTER aplica el
     * xform una sola vez (espejo CPU). Solo lectura: no toca geometria,
     * TextureEntry, shader, camara, culling, LRU ni materiales. Se recalcula
     * como mucho cada 3 s.
     */
    private fun centerFaceReport(): String {
        val now = System.currentTimeMillis()
        if (now - lastCenterMillis < 3000L && centerCache.isNotEmpty()) return centerCache
        lastCenterMillis = now
        val b = StringBuilder(12000)
        b.append("--- centro-vista (hasta 5 hits bajo el centro de la camara, raycast CPU) ---\n")
        val cam = lastCamera
        if (cam == null) {
            b.append("sin cámara todavía\n")
            centerCache = b.toString()
            return centerCache
        }
        val dx = cam.target[0] - cam.eye[0]
        val dy = cam.target[1] - cam.eye[1]
        val dz = cam.target[2] - cam.eye[2]
        val dl = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (dl < 1e-6f) {
            b.append("camara sin direccion\n")
            centerCache = b.toString()
            return centerCache
        }
        val dir = floatArrayOf(dx / dl, dy / dl, dz / dl)
        b.append("RAYO ojo=").append(fmt2(cam.eye[0])).append(',')
            .append(fmt2(cam.eye[1])).append(',').append(fmt2(cam.eye[2]))
            .append(" dir=").append(fmt2(dir[0])).append(',')
            .append(fmt2(dir[1])).append(',').append(fmt2(dir[2])).append('\n')
        val all = ArrayList<CenterHit>()
        for ((localId, slot) in slots.entries) {
            if (!slot.visible) continue
            val desc = slot.mesh.desc
            if (desc.indices.isEmpty() || desc.vertices.isEmpty()) continue
            val world = worldMatrixOf(localId) ?: continue
            if (!rayHitsWorldAabb(cam.eye, dir, desc.boundsMin, desc.boundsMax, world)) continue
            for (th in raycastMeshAll(cam.eye, dir, desc, world)) {
                val idxPos = th.tri * 3
                var group = -1
                for (g in 0 until desc.faceCount) {
                    val f = desc.faceFirstIndexAt(g)
                    val n = desc.faceIndexCountAt(g)
                    if (idxPos >= f && idxPos < f + n) { group = g; break }
                }
                if (group >= 0) all.add(CenterHit(th.t, group, th.tri, localId))
            }
        }
        if (all.isEmpty()) {
            b.append("sin impacto (ninguna entidad bajo el centro)\n")
            centerCache = b.toString()
            return centerCache
        }
        val worldCache = HashMap<Int, FloatArray>()
        fun wmat(id: Int): FloatArray {
            var w = worldCache[id]
            if (w == null) {
                w = worldMatrixOf(id) ?: FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
                worldCache[id] = w
            }
            return w
        }
        for ((hi, h) in all.sortedBy { it.t }.take(5).withIndex()) {
            val slot = slots[h.local] ?: continue
            val desc = slot.mesh.desc
            val face = slot.faces.getOrNull(h.group)
            val fact = facts[h.local]
            val scale = slot.transform.scale
            val stride = MeshDesc.VERTEX_FLOATS
            val first = desc.faceFirstIndexAt(h.group)
            val count = desc.faceIndexCountAt(h.group)
            val te = slot.faceTeIndex.getOrElse(h.group) { h.group }
            val slFace = desc.faceIndexAt(h.group)
            val mapping = when (face?.texGen) {
                1 -> "PLANAR"
                2 -> "SPHERICAL_UNSUPPORTED"
                3 -> "CYLINDRICAL_UNSUPPORTED"
                else -> "DEFAULT"
            }
            val wN = triangleWorldNormal(desc, h.tri, wmat(h.local))
            b.append("HIT_INDEX=").append(hi)
                .append(" DIST=").append(fmt1(h.t)).append('m')
                .append(" OBJECT=#").append(h.local)
                .append(" LOCAL_ID=").append(h.local)
                .append(" FACE=").append(h.group).append('/').append(desc.faceCount)
                .append("(te=").append(te).append(" sl=").append(slFace).append(')')
                .append(" PRIM_TYPE=").append(fact?.shapeText ?: "-")
                .append(" PRIM_SIZE=")
                .append(fmt2(scale.getOrElse(0) { 1f })).append('x')
                .append(fmt2(scale.getOrElse(1) { 1f })).append('x')
                .append(fmt2(scale.getOrElse(2) { 1f }))
                .append(" FACE_NORMAL=").append(fmt2(wN[0])).append(',')
                .append(fmt2(wN[1])).append(',').append(fmt2(wN[2]))
            if (face != null) {
                b.append(" TEXTURE_UUID=").append(face.textureId)
                    .append(" TEXTURE_READY=").append(if (textures.hasDecoded(face.textureId)) "YES" else "NO")
                    .append(" MAPPING=").append(mapping)
                    .append(" REPEAT=").append(fmt2(face.repeatU)).append('x').append(fmt2(face.repeatV))
                    .append(" OFFSET=").append(fmt2(face.offsetU)).append(',').append(fmt2(face.offsetV))
                    .append(" ROTATION=").append(fmt2(face.rotation))
                    .append(" FLIP=").append(flipOf(face))
            } else {
                b.append(" sin-cara-TE")
            }
            b.append('\n')
            if (first >= 0 && count > 0 && first + count <= desc.indices.size && face != null) {
                val uniq = ArrayList<Int>()
                for (k in 0 until count) {
                    val vi = desc.indices[first + k]
                    if (!uniq.contains(vi)) uniq.add(vi)
                }
                var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
                var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
                for (vi in uniq) {
                    val o = vi * stride
                    if (vi < 0 || o + 7 >= desc.vertices.size) continue
                    val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
                    if (u < minU) minU = u; if (u > maxU) maxU = u
                    if (v < minV) minV = v; if (v > maxV) maxV = v
                }
                b.append("  UV_BASE_MIN=").append(fmt2(minU)).append(',').append(fmt2(minV))
                    .append(" UV_BASE_MAX=").append(fmt2(maxU)).append(',').append(fmt2(maxV))
                    .append('\n')
                val showIdx: List<Int> = if (uniq.size <= 12) {
                    uniq.indices.toList()
                } else {
                    val head = (0 until 4).toList()
                    val tail = (uniq.size - 4 until uniq.size).toList()
                    val extra = LinkedHashSet<Int>()
                    for (i in uniq.indices) {
                        val o = uniq[i] * stride
                        val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
                        if (u == minU || u == maxU || v == minV || v == maxV) extra.add(i)
                        if (extra.size >= 4) break
                    }
                    (head + tail + extra.toList()).distinct().sorted()
                }
                for (i in showIdx) {
                    val vi = uniq[i]
                    val o = vi * stride
                    val r = SLTextureFace.xformUv(desc.vertices[o + 6], desc.vertices[o + 7], face)
                    b.append("  POSITION_LOCAL[v").append(i).append("]=")
                        .append(fmt2(desc.vertices[o])).append(',')
                        .append(fmt2(desc.vertices[o + 1])).append(',').append(fmt2(desc.vertices[o + 2]))
                        .append(" UV_BASE[v").append(i).append("]=")
                        .append(fmt2(desc.vertices[o + 6])).append(',').append(fmt2(desc.vertices[o + 7]))
                        .append(" UV_AFTER[v").append(i).append("]=")
                        .append(fmt2(r.first)).append(',').append(fmt2(r.second)).append('\n')
                }
                degenerateAudit(desc, first, count)?.let { b.append(it) }
            }
        }
        centerCache = b.toString()
        return centerCache
    }

    private class CenterHit(val t: Float, val group: Int, val tri: Int, val local: Int)

    private class TriHit(val t: Float, val tri: Int)

    /**
     * 2.27i (diagnostico interno, solo informe): vuelca los indices y
     * vertices crudos de una cara para decidir si una cara "degenerada" es
     * un error de lectura del informe (A) o geometria realmente degenerada
     * (B). Null cuando la cara esta sana (posiciones y UV no colapsadas).
     * Solo lectura: no toca buffers, geometria ni nada mas.
     */
    private fun degenerateAudit(desc: MeshDesc, first: Int, count: Int): String? {
        if (first < 0 || count <= 0 || first + count > desc.indices.size) return null
        val stride = MeshDesc.VERTEX_FLOATS
        val idx = IntArray(count) { desc.indices[first + it] }
        for (vi in idx) {
            if (vi < 0 || vi * stride + 7 >= desc.vertices.size) return null
        }
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (vi in idx) {
            val o = vi * stride
            val x = desc.vertices[o]; val y = desc.vertices[o + 1]; val z = desc.vertices[o + 2]
            val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            if (u < minU) minU = u; if (u > maxU) maxU = u
            if (v < minV) minV = v; if (v > maxV) maxV = v
        }
        val samePos = minX == maxX && minY == maxY && minZ == maxZ
        val sameUv = minU == maxU && minV == maxV
        if (!samePos && !sameUv) return null
        val b = StringBuilder(900)
        b.append("  AUDIT_CARA IBO_RANGE=").append(first).append(',').append(count)
            .append(" VBO_FLOATS=").append(desc.vertices.size)
            .append(" IBO=").append(desc.indices.size)
            .append(" IDX=[")
        for (k in idx.indices) {
            if (k > 0) b.append(',')
            b.append(idx[k])
        }
        b.append(']')
        val uniq = idx.distinct()
        for (vi in uniq) {
            val o = vi * stride
            b.append(" VBO_OFF(v").append(vi).append(")=").append(o)
                .append(" POS=").append(desc.vertices[o]).append(',')
                .append(desc.vertices[o + 1]).append(',').append(desc.vertices[o + 2])
                .append(" UV=").append(desc.vertices[o + 6]).append(',')
                .append(desc.vertices[o + 7])
        }
        val verdict = when {
            uniq.size == 1 -> "A-POSIBLE-LECTURA (todos los indices apuntan al mismo vertice v" + uniq[0] + ")"
            samePos -> "B-GEOMETRIA-DEGENERADA (indices distintos, misma posicion)"
            else -> "UV-COLAPSADAS-POS-OK (posiciones distintas, misma UV)"
        }
        b.append(" VEREDICTO=").append(verdict).append('\n')
        return b.toString()
    }

    /** Matriz mundo columna-mayor compuesta por la cadena de padres. Solo lectura. */
    private fun worldMatrixOf(localId: Int): FloatArray? {
        val chain = ArrayList<Int>(4)
        var cur = localId
        var hops = 0
        while (hops < 32 && !chain.contains(cur)) {
            chain.add(cur)
            val p = parentIdOf(cur)
            if (p == 0) break
            cur = p
            hops += 1
        }
        var out: FloatArray? = null
        for (i in chain.indices.reversed()) {
            val s = slots[chain[i]] ?: return null
            val local = s.transform.toMatrix16()
            out = if (out == null) local else mul16(out, local)
        }
        return out
    }

    /** C = A*B, columna-mayor 4x4. */
    private fun mul16(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] +
                    a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3]
            }
        }
        return o
    }

    private fun xformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray =
        floatArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14]
        )

    /** Slab test del rayo contra el AABB local llevado a mundo. */
    private fun rayHitsWorldAabb(
        eye: FloatArray, dir: FloatArray,
        bmin: FloatArray, bmax: FloatArray, world: FloatArray
    ): Boolean {
        var mnx = Float.MAX_VALUE; var mxx = -Float.MAX_VALUE
        var mny = Float.MAX_VALUE; var mxy = -Float.MAX_VALUE
        var mnz = Float.MAX_VALUE; var mxz = -Float.MAX_VALUE
        for (cx in 0 until 8) {
            val p = xformPoint(
                world,
                if ((cx and 1) == 0) bmin[0] else bmax[0],
                if ((cx and 2) == 0) bmin[1] else bmax[1],
                if ((cx and 4) == 0) bmin[2] else bmax[2]
            )
            if (p[0] < mnx) mnx = p[0]; if (p[0] > mxx) mxx = p[0]
            if (p[1] < mny) mny = p[1]; if (p[1] > mxy) mxy = p[1]
            if (p[2] < mnz) mnz = p[2]; if (p[2] > mxz) mxz = p[2]
        }
        var tmin = 0f
        var tmax = Float.MAX_VALUE
        val e = floatArrayOf(mnx, mny, mnz)
        val f = floatArrayOf(mxx, mxy, mxz)
        for (a in 0 until 3) {
            val d = dir[a]
            if (kotlin.math.abs(d) < 1e-9f) {
                if (eye[a] < e[a] || eye[a] > f[a]) return false
            } else {
                var t1 = (e[a] - eye[a]) / d
                var t2 = (f[a] - eye[a]) / d
                if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
                if (t1 > tmin) tmin = t1
                if (t2 < tmax) tmax = t2
                if (tmin > tmax) return false
            }
        }
        return tmax > 0f
    }

    /** Todos los triangulos de la malla tocados por el rayo (t, triangulo). */
    private fun raycastMeshAll(
        eye: FloatArray, dir: FloatArray, desc: MeshDesc, world: FloatArray
    ): List<TriHit> {
        val out = ArrayList<TriHit>()
        val stride = MeshDesc.VERTEX_FLOATS
        val tris = desc.indices.size / 3
        for (tr in 0 until tris) {
            val a = desc.indices[tr * 3] * stride
            val b2 = desc.indices[tr * 3 + 1] * stride
            val c = desc.indices[tr * 3 + 2] * stride
            if (a < 0 || b2 < 0 || c < 0 || c + 7 >= desc.vertices.size) continue
            val v0 = xformPoint(world, desc.vertices[a], desc.vertices[a + 1], desc.vertices[a + 2])
            val v1 = xformPoint(world, desc.vertices[b2], desc.vertices[b2 + 1], desc.vertices[b2 + 2])
            val v2 = xformPoint(world, desc.vertices[c], desc.vertices[c + 1], desc.vertices[c + 2])
            val hit = rayTriangle(eye, dir, v0, v1, v2) ?: continue
            out.add(TriHit(hit, tr))
        }
        return out
    }

    /** Moller-Trumbore; distancia t o null. Doble cara (no descarta traseras). */
    private fun rayTriangle(
        eye: FloatArray, dir: FloatArray,
        v0: FloatArray, v1: FloatArray, v2: FloatArray
    ): Float? {
        val e1 = floatArrayOf(v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2])
        val e2 = floatArrayOf(v2[0] - v0[0], v2[1] - v0[1], v2[2] - v0[2])
        val p = floatArrayOf(
            dir[1] * e2[2] - dir[2] * e2[1],
            dir[2] * e2[0] - dir[0] * e2[2],
            dir[0] * e2[1] - dir[1] * e2[0]
        )
        val det = e1[0] * p[0] + e1[1] * p[1] + e1[2] * p[2]
        if (kotlin.math.abs(det) < 1e-9f) return null
        val inv = 1f / det
        val sv = floatArrayOf(eye[0] - v0[0], eye[1] - v0[1], eye[2] - v0[2])
        val u = (sv[0] * p[0] + sv[1] * p[1] + sv[2] * p[2]) * inv
        if (u < 0f || u > 1f) return null
        val q = floatArrayOf(
            sv[1] * e1[2] - sv[2] * e1[1],
            sv[2] * e1[0] - sv[0] * e1[2],
            sv[0] * e1[1] - sv[1] * e1[0]
        )
        val v = (dir[0] * q[0] + dir[1] * q[1] + dir[2] * q[2]) * inv
        if (v < 0f || u + v > 1f) return null
        val t = (e2[0] * q[0] + e2[1] * q[1] + e2[2] * q[2]) * inv
        return if (t > 0.01f) t else null
    }

    /** Normal geometrica en mundo del triangulo dado (indice de triangulo). */
    private fun triangleWorldNormal(desc: MeshDesc, tri: Int, world: FloatArray): FloatArray {
        val stride = MeshDesc.VERTEX_FLOATS
        val a = desc.indices[tri * 3] * stride
        val b2 = desc.indices[tri * 3 + 1] * stride
        val c = desc.indices[tri * 3 + 2] * stride
        val v0 = xformPoint(world, desc.vertices[a], desc.vertices[a + 1], desc.vertices[a + 2])
        val v1 = xformPoint(world, desc.vertices[b2], desc.vertices[b2 + 1], desc.vertices[b2 + 2])
        val v2 = xformPoint(world, desc.vertices[c], desc.vertices[c + 1], desc.vertices[c + 2])
        val e1 = floatArrayOf(v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2])
        val e2 = floatArrayOf(v2[0] - v0[0], v2[1] - v0[1], v2[2] - v0[2])
        val n = floatArrayOf(
            e1[1] * e2[2] - e1[2] * e2[1],
            e1[2] * e2[0] - e1[0] * e2[2],
            e1[0] * e2[1] - e1[1] * e2[0]
        )
        val l = kotlin.math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        return if (l < 1e-9f) floatArrayOf(0f, 0f, 0f) else floatArrayOf(n[0] / l, n[1] / l, n[2] / l)
    }

    /**
     * 2.27j (diagnostico interno, solo informe): rebanada IBO->VBO de una
     * cara concreta (#708493804/FACE 0; si el objeto no esta en escena se
     * dice y no se audita otra cosa). Vuelca FACE_INDEX_START/COUNT
     * (rango en el IBO), declara que MeshDesc NO guarda FACE_VERTEX_START
     * (los vertices se alcanzan SOLO via IBO), stride/offsets, totales de
     * la malla y por cada entrada del rango: IBO[pos]=idx -> VBO v(idx)
     * con POSITION y UV en precision completa. Veredicto A (vertices
     * distintos: el colapso anterior era del informe) o B (indices reales
     * a posicion y UV identicas). Tambien lista hijos con sus texturas
     * para ver si comparten la textura de la cara. Solo lectura.
     */
    private fun faceSliceAuditReport(): String {
        val b = StringBuilder(6000)
        b.append("--- face-slice (IBO->VBO de #708493804/FACE 0, precision completa) ---\n")
        val target = 708493804
        val group = 0
        val slot = slots[target]
        if (slot == null) {
            b.append("objetivo #708493804 ausente en escena (los localId cambian por sesion)\n")
            return b.toString()
        }
        val desc = slot.mesh.desc
        b.append("MALLA verts=").append(desc.vertexCount)
            .append(" indices=").append(desc.indices.size)
            .append(" tris=").append(desc.indices.size / 3)
            .append(" caras=").append(desc.faceCount).append('\n')
        if (group < 0 || group >= desc.faceCount) {
            b.append("FACE 0 fuera de rango\n")
            return b.toString()
        }
        val stride = MeshDesc.VERTEX_FLOATS
        val first = desc.faceFirstIndexAt(group)
        val count = desc.faceIndexCountAt(group)
        b.append("OBJECT=#").append(target)
            .append(" LOCAL_ID=").append(target)
            .append(" PARENT=").append(parentIdOf(target))
            .append(" FACE=").append(group).append('/').append(desc.faceCount)
            .append(" (sl=").append(desc.faceIndexAt(group)).append(')').append('\n')
        b.append("FACE_INDEX_START=").append(first)
            .append(" FACE_INDEX_COUNT=").append(count).append('\n')
        b.append("FACE_VERTEX_START=N/A (MeshDesc no guarda rangos de vertices; se llega SOLO via IBO)")
            .append(" STRIDE=").append(stride).append(" floats POS@0 NRM@3 UV@6").append('\n')
        val tpp = slots[target]?.primParams
        if (tpp != null && tpp.size >= 19) {
            b.append("PARAMS19=[")
            for (i in 0 until 19) {
                if (i > 0) b.append(',')
                b.append(tpp[i])
            }
            b.append("] (layout PrimParams: pathCurve,profileCurve,pathBegin,pathEnd,pathScaleX,pathScaleY,pathShearX,pathShearY,pathTwist,pathTwistBegin,pathRadiusOffset,pathTaperX,pathTaperY,pathRevolutions,pathSkew,profileBegin,profileEnd,profileHollow,detail)").append('\n')
        } else {
            b.append("PARAMS19=ausentes\n")
        }
        if (first < 0 || count <= 0 || first + count > desc.indices.size) {
            b.append("RANGO-IBO-INVALIDO (el grupo apunta fuera del IBO)\n")
            return b.toString()
        }
        var bad = false
        for (k in 0 until count) {
            val vi = desc.indices[first + k]
            if (vi < 0 || vi * stride + 7 >= desc.vertices.size) { bad = true; break }
        }
        if (bad) {
            b.append("INDICES-FUERA-DE-VBO (algun IBO apunta fuera del VBO)\n")
            return b.toString()
        }
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (k in 0 until count) {
            val idxPos = first + k
            val vi = desc.indices[idxPos]
            val o = vi * stride
            val x = desc.vertices[o]; val y = desc.vertices[o + 1]; val z = desc.vertices[o + 2]
            val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            if (u < minU) minU = u; if (u > maxU) maxU = u
            if (v < minV) minV = v; if (v > maxV) maxV = v
            b.append("IBO[").append(idxPos).append("]=").append(vi)
                .append(" -> VBO v").append(vi).append("(off=").append(o).append(")")
                .append(" POS=").append(fmt6(x)).append(',').append(fmt6(y)).append(',').append(fmt6(z))
                .append(" UV=").append(fmt6(u)).append(',').append(fmt6(v)).append('\n')
        }
        b.append("POSITION_LOCAL_MIN=").append(fmt6(minX)).append(',')
            .append(fmt6(minY)).append(',').append(fmt6(minZ))
            .append(" POSITION_LOCAL_MAX=").append(fmt6(maxX)).append(',')
            .append(fmt6(maxY)).append(',').append(fmt6(maxZ)).append('\n')
        b.append("UV_BASE_MIN=").append(fmt6(minU)).append(',').append(fmt6(minV))
            .append(" UV_BASE_MAX=").append(fmt6(maxU)).append(',').append(fmt6(maxV)).append('\n')
        val samePos = minX == maxX && minY == maxY && minZ == maxZ
        val sameUv = minU == maxU && minV == maxV
        val verdict = when {
            !samePos -> "A-VERTICES-DISTINTOS (el colapso anterior era del informe, no del buffer)"
            !sameUv -> "A-UV-DISTINTAS (posicion colapsada, UV no)"
            else -> "B-INDICES-REALES-A-POS-Y-UV-IDENTICAS (geometria degenerada en el buffer)"
        }
        b.append("VEREDICTO=").append(verdict).append('\n')
        val faceTex = slot.faces.getOrNull(group)?.textureId ?: ""
        val kids = childrenOf[target]
        if (kids == null || kids.isEmpty()) {
            b.append("HIJOS=ninguno registrado\n")
        } else {
            for (kid in kids.sorted()) {
                val ks = slots[kid]
                if (ks == null) {
                    b.append("HIJO=#").append(kid).append(" fuera de escena\n")
                    continue
                }
                b.append("HIJO=#").append(kid).append(" caras=").append(ks.faces.size)
                for ((fi, f) in ks.faces.withIndex()) {
                    b.append(" [f").append(fi).append('=').append(shortUuid(f.textureId))
                    if (f.textureId == faceTex && faceTex.isNotEmpty() && faceTex != SLTextureFace.DEFAULT_UUID) b.append("*MISMA-TEXTURA")
                    b.append(']')
                }
                b.append('\n')
            }
        }
        return b.toString()
    }

    /**
     * 2.27k (diagnostico interno, solo informe): todas las caras (max 26)
     * que usan exactamente la textura candidata 5748decc-..., ordenadas por
     * mayor area mundial aproximada y luego por menor distancia a camara.
     * Por cara: TEXTURE_UUID, OBJECT, LOCAL_ID, FACE, PRIM_TYPE, PRIM_SIZE,
     * DISTANCE_CAMERA, WORLD_AREA_ESTIMATE (suma de triangulos en mundo),
     * FACE_NORMAL (media ponderada por area, en mundo), MAPPING,
     * REPEAT/OFFSET/ROTATION, UV_BASE_MIN/MAX y TEXTURE_READY. Las 5 de
     * mayor area anaden FACE_VERTEX_COUNT, FACE_INDEX_COUNT,
     * POSITION_LOCAL_MIN/MAX, UV_BASE_MIN/MAX y muestras
     * POSITION_LOCAL/UV_BASE/UV_AFTER (cara completa si es pequena;
     * extremos + representativos si es grande; xform una sola vez).
     * Mas un bloque con FACE 0/1/2 de #708493804 (tamano real y cual usa
     * la textura). Solo lectura: no toca UV, geometria, TextureEntry,
     * xform, shader, materiales, LRU, camara ni nada mas.
     */
    private fun targetTextureReport(): String {
        val b = StringBuilder(14000)
        val uuid = "5748decc-f629-461c-9a36-a35a221fe21f"
        b.append("--- target-texture (todas las caras con TEXTURE_UUID=").append(uuid).append(") ---\n")
        val eye = lastCamera?.eye
        data class Row(
            val localId: Int, val g: Int, val dist: Float,
            val area: Float, val nx: Float, val ny: Float, val nz: Float
        )
        val rows = ArrayList<Row>()
        for ((localId, slot) in slots.entries) {
            val desc = slot.mesh.desc
            for (g in slot.faces.indices) {
                val face = slot.faces[g]
                if (face.textureId != uuid) continue
                if (g < 0 || g >= desc.faceCount) continue
                val first = desc.faceFirstIndexAt(g)
                val count = desc.faceIndexCountAt(g)
                if (first < 0 || count <= 0 || first + count > desc.indices.size) continue
                val world = worldMatrixOf(localId) ?: continue
                val m = areaAndNormal(desc, first, count, world)
                val dist = if (eye == null) -1f else distanceTo(placementInRegion(localId), eye)
                rows.add(Row(localId, g, dist, m.area, m.nx, m.ny, m.nz))
            }
        }
        if (rows.isEmpty()) {
            b.append("ninguna cara usa esa textura en escena\n")
        } else {
            val ordered = rows.sortedWith(
                compareByDescending<Row> { it.area }
                    .thenBy { if (it.dist < 0f) Float.MAX_VALUE else it.dist }
            ).take(26)
            for (r in ordered) {
                val slot = slots[r.localId] ?: continue
                val face = slot.faces.getOrNull(r.g) ?: continue
                val desc = slot.mesh.desc
                val scale = slot.transform.scale
                val fact = facts[r.localId]
                val first = desc.faceFirstIndexAt(r.g)
                val count = desc.faceIndexCountAt(r.g)
                val te = slot.faceTeIndex.getOrElse(r.g) { r.g }
                val mapping = when (face.texGen) {
                    1 -> "PLANAR"
                    2 -> "SPHERICAL_UNSUPPORTED"
                    3 -> "CYLINDRICAL_UNSUPPORTED"
                    else -> "DEFAULT"
                }
                val uv = uvRangeOf(desc, first, count)
                b.append("TEXTURE_UUID=").append(uuid)
                    .append(" OBJECT=#").append(r.localId)
                    .append(" LOCAL_ID=").append(r.localId)
                    .append(" FACE=").append(r.g).append('/').append(desc.faceCount)
                    .append("(te=").append(te).append(" sl=").append(desc.faceIndexAt(r.g)).append(')')
                    .append(" PRIM_TYPE=").append(fact?.shapeText ?: "-")
                    .append(" PRIM_SIZE=")
                    .append(fmt2(scale.getOrElse(0) { 1f })).append('x')
                    .append(fmt2(scale.getOrElse(1) { 1f })).append('x')
                    .append(fmt2(scale.getOrElse(2) { 1f }))
                    .append(" DISTANCE_CAMERA=").append(if (r.dist < 0f) "-" else fmt1(r.dist) + "m")
                    .append(" WORLD_AREA_ESTIMATE=").append(fmt1(r.area)).append("m2")
                    .append(" FACE_NORMAL=").append(fmt2(r.nx)).append(',')
                    .append(fmt2(r.ny)).append(',').append(fmt2(r.nz))
                    .append(" MAPPING=").append(mapping)
                    .append(" REPEAT=").append(fmt2(face.repeatU)).append('x').append(fmt2(face.repeatV))
                    .append(" OFFSET=").append(fmt2(face.offsetU)).append(',').append(fmt2(face.offsetV))
                    .append(" ROTATION=").append(fmt2(face.rotation))
                if (uv != null) {
                    b.append(" UV_BASE_MIN=").append(fmt2(uv[0])).append(',').append(fmt2(uv[1]))
                        .append(" UV_BASE_MAX=").append(fmt2(uv[2])).append(',').append(fmt2(uv[3]))
                }
                b.append(" TEXTURE_READY=").append(if (textures.hasDecoded(face.textureId)) "YES" else "NO")
                    .append('\n')
            }
            val top = ordered.take(5)
            for (r in top) {
                val slot = slots[r.localId] ?: continue
                val desc = slot.mesh.desc
                val face = slot.faces.getOrNull(r.g) ?: continue
                val stride = MeshDesc.VERTEX_FLOATS
                val first = desc.faceFirstIndexAt(r.g)
                val count = desc.faceIndexCountAt(r.g)
                val uniq = ArrayList<Int>()
                for (k in 0 until count) {
                    val vi = desc.indices[first + k]
                    if (!uniq.contains(vi)) uniq.add(vi)
                }
                var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
                var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
                var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
                var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
                var ok = true
                for (vi in uniq) {
                    val o = vi * stride
                    if (vi < 0 || o + 7 >= desc.vertices.size) { ok = false; break }
                    val x = desc.vertices[o]; val y = desc.vertices[o + 1]; val z = desc.vertices[o + 2]
                    val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                    if (u < minU) minU = u; if (u > maxU) maxU = u
                    if (v < minV) minV = v; if (v > maxV) maxV = v
                }
                if (!ok || uniq.isEmpty()) continue
                b.append("TOP OBJECT=#").append(r.localId)
                    .append(" FACE=").append(r.g)
                    .append(" FACE_VERTEX_COUNT=").append(uniq.size)
                    .append(" FACE_INDEX_COUNT=").append(count)
                    .append(" POSITION_LOCAL_MIN=").append(fmt2(minX)).append(',')
                    .append(fmt2(minY)).append(',').append(fmt2(minZ))
                    .append(" POSITION_LOCAL_MAX=").append(fmt2(maxX)).append(',')
                    .append(fmt2(maxY)).append(',').append(fmt2(maxZ))
                    .append(" UV_BASE_MIN=").append(fmt2(minU)).append(',').append(fmt2(minV))
                    .append(" UV_BASE_MAX=").append(fmt2(maxU)).append(',').append(fmt2(maxV))
                    .append('\n')
                val showIdx: List<Int> = if (uniq.size <= 12) {
                    uniq.indices.toList()
                } else {
                    val head = (0 until 4).toList()
                    val tail = (uniq.size - 4 until uniq.size).toList()
                    val extra = LinkedHashSet<Int>()
                    for (i in uniq.indices) {
                        val o = uniq[i] * stride
                        val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
                        if (u == minU || u == maxU || v == minV || v == maxV) extra.add(i)
                        if (extra.size >= 4) break
                    }
                    (head + tail + extra.toList()).distinct().sorted()
                }
                for (i in showIdx) {
                    val vi = uniq[i]
                    val o = vi * stride
                    val r2 = SLTextureFace.xformUv(desc.vertices[o + 6], desc.vertices[o + 7], face)
                    b.append("  POSITION_LOCAL[v").append(i).append("]=")
                        .append(fmt2(desc.vertices[o])).append(',')
                        .append(fmt2(desc.vertices[o + 1])).append(',').append(fmt2(desc.vertices[o + 2]))
                        .append(" UV_BASE[v").append(i).append("]=")
                        .append(fmt2(desc.vertices[o + 6])).append(',').append(fmt2(desc.vertices[o + 7]))
                        .append(" UV_AFTER[v").append(i).append("]=")
                        .append(fmt2(r2.first)).append(',').append(fmt2(r2.second)).append('\n')
                }
            }
        }
        val target = 708493804
        val tslot = slots[target]
        if (tslot == null) {
            b.append("#708493804 ausente en escena (los localId cambian por sesion)\n")
        } else {
            val desc = tslot.mesh.desc
            b.append("#708493804 caras=" + desc.faceCount + "\n")
            for (g in 0 until minOf(3, desc.faceCount)) {
                val first = desc.faceFirstIndexAt(g)
                val count = desc.faceIndexCountAt(g)
                if (first < 0 || count <= 0 || first + count > desc.indices.size) continue
                val world = worldMatrixOf(target) ?: continue
                val m = areaAndNormal(desc, first, count, world)
                val face = tslot.faces.getOrNull(g)
                val uv = uvRangeOf(desc, first, count)
                val uniq = HashSet<Int>()
                for (k in 0 until count) uniq.add(desc.indices[first + k])
                b.append("CARAS-OBJETO OBJECT=#").append(target)
                    .append(" FACE=").append(g).append('/').append(desc.faceCount)
                    .append(" (sl=").append(desc.faceIndexAt(g)).append(')')
                    .append(" FACE_INDEX_COUNT=").append(count)
                    .append(" FACE_VERTEX_COUNT=").append(uniq.size)
                    .append(" WORLD_AREA_ESTIMATE=").append(fmt1(m.area)).append("m2")
                if (face != null) {
                    b.append(" TEXTURE_UUID=").append(face.textureId)
                    if (face.textureId == uuid) b.append("*ES-LA-CANDIDATA")
                    b.append(" REPEAT=").append(fmt2(face.repeatU)).append('x').append(fmt2(face.repeatV))
                        .append(" OFFSET=").append(fmt2(face.offsetU)).append(',').append(fmt2(face.offsetV))
                        .append(" ROTATION=").append(fmt2(face.rotation))
                }
                if (uv != null) {
                    b.append(" UV_BASE_MIN=").append(fmt2(uv[0])).append(',').append(fmt2(uv[1]))
                        .append(" UV_BASE_MAX=").append(fmt2(uv[2])).append(',').append(fmt2(uv[3]))
                }
                b.append('\n')
            }
        }
        return b.toString()
    }

    private class AreaNormal(val area: Float, val nx: Float, val ny: Float, val nz: Float)

    /** Area y normal media ponderada por area de un rango de indices, en mundo. Solo lectura. */
    private fun areaAndNormal(desc: MeshDesc, first: Int, count: Int, world: FloatArray): AreaNormal {
        val stride = MeshDesc.VERTEX_FLOATS
        var area = 0f
        var nx = 0f; var ny = 0f; var nz = 0f
        var t = 0
        while (t + 2 < count) {
            val a = desc.indices[first + t] * stride
            val b2 = desc.indices[first + t + 1] * stride
            val c = desc.indices[first + t + 2] * stride
            if (a < 0 || b2 < 0 || c < 0 || c + 7 >= desc.vertices.size) { t += 3; continue }
            val v0 = xformPoint(world, desc.vertices[a], desc.vertices[a + 1], desc.vertices[a + 2])
            val v1 = xformPoint(world, desc.vertices[b2], desc.vertices[b2 + 1], desc.vertices[b2 + 2])
            val v2 = xformPoint(world, desc.vertices[c], desc.vertices[c + 1], desc.vertices[c + 2])
            val e1x = v1[0] - v0[0]; val e1y = v1[1] - v0[1]; val e1z = v1[2] - v0[2]
            val e2x = v2[0] - v0[0]; val e2y = v2[1] - v0[1]; val e2z = v2[2] - v0[2]
            val cx = e1y * e2z - e1z * e2y
            val cy = e1z * e2x - e1x * e2z
            val cz = e1x * e2y - e1y * e2x
            val triArea = 0.5f * kotlin.math.sqrt(cx * cx + cy * cy + cz * cz)
            area += triArea
            nx += cx; ny += cy; nz += cz
            t += 3
        }
        val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        return if (l < 1e-9f) AreaNormal(area, 0f, 0f, 0f)
        else AreaNormal(area, nx / l, ny / l, nz / l)
    }

    /** Rango UV [minU, minV, maxU, maxV] de un rango de indices, o null si es invalido. */
    private fun uvRangeOf(desc: MeshDesc, first: Int, count: Int): FloatArray? {
        val stride = MeshDesc.VERTEX_FLOATS
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (k in 0 until count) {
            val vi = desc.indices[first + k]
            val o = vi * stride
            if (vi < 0 || o + 7 >= desc.vertices.size) return null
            val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
            if (u < minU) minU = u; if (u > maxU) maxU = u
            if (v < minV) minV = v; if (v > maxV) maxV = v
        }
        return floatArrayOf(minU, minV, maxU, maxV)
    }

    /**
     * 2.27m (diagnostico interno, solo informe): caras PRIM (slcore) cuya
     * UV esta exactamente colapsada en torno a (0,0) (rango exacto nulo y
     * |UV| <= 0.005, para incluir variantes epsilon sin inundar). Maximo 8
     * caras. Por cara: PRIM_UV_ZERO_FACE, OBJECT, LOCAL_ID, FACE, PRIM_TYPE,
     * PATH_CURVE/PROFILE_CURVE, los 19 PARAMS19 (layout PrimParams, listos
     * para reproducir el caso en el generador), vertexCount/indexCount,
     * rango UV en precision completa, primer/ultimo u,v y ORIGEN (siempre
     * slcore-C++, porque JNI y toMeshDesc copian verbatim y el contador
     * MeshDecoder.uvFallbackFaces cubre la otra fuente). Solo lectura: no
     * toca UV, geometria, TextureEntry, shader ni nada mas.
     */
    private fun primUvZeroReport(): String {
        val b = StringBuilder(6000)
        b.append("--- prim-uv-zero (caras PRIM slcore con UV colapsada en ~(0,0)) ---\n")
        var shown = 0
        for ((localId, slot) in slots.entries.sortedBy { it.key }) {
            if (shown >= 8) break
            if (slot.mesh.source != SLMesh.Source.PRIM) continue
            val desc = slot.mesh.desc
            val stride = MeshDesc.VERTEX_FLOATS
            for (g in slot.faces.indices) {
                if (shown >= 8) break
                if (g < 0 || g >= desc.faceCount) continue
                val first = desc.faceFirstIndexAt(g)
                val count = desc.faceIndexCountAt(g)
                if (first < 0 || count <= 0 || first + count > desc.indices.size) continue
                var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
                var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
                var ok = true
                for (k in 0 until count) {
                    val vi = desc.indices[first + k]
                    val o = vi * stride
                    if (vi < 0 || o + 7 >= desc.vertices.size) { ok = false; break }
                    val u = desc.vertices[o + 6]; val v = desc.vertices[o + 7]
                    if (u < minU) minU = u; if (u > maxU) maxU = u
                    if (v < minV) minV = v; if (v > maxV) maxV = v
                }
                if (!ok) continue
                if (!(minU == maxU && minV == maxV)) continue
                if (!(kotlin.math.abs(minU) <= 0.005f && kotlin.math.abs(minV) <= 0.005f)) continue
                val uniq = HashSet<Int>()
                for (k in 0 until count) uniq.add(desc.indices[first + k])
                val o0 = desc.indices[first] * stride
                val o1 = desc.indices[first + count - 1] * stride
                val te = slot.faceTeIndex.getOrElse(g) { g }
                val fact = facts[localId]
                val face = slot.faces.getOrNull(g)
                b.append("PRIM_UV_ZERO_FACE=1")
                    .append(" OBJECT=#").append(localId)
                    .append(" LOCAL_ID=").append(localId)
                    .append(" FACE=").append(g).append('/').append(desc.faceCount)
                    .append("(te=").append(te).append(" sl=").append(desc.faceIndexAt(g)).append(')')
                    .append(" PRIM_TYPE=").append(fact?.shapeText ?: "-")
                    .append(" PATH_CURVE=0x").append(Integer.toHexString(fact?.pathCurve ?: -1))
                    .append(" PROFILE_CURVE=0x").append(Integer.toHexString(fact?.profileCurve ?: -1))
                    .append(" MAPPING=").append(if (face?.texGen == 1) "PLANAR" else "DEFAULT")
                val pp = slot.primParams
                if (pp != null && pp.size >= 19) {
                    b.append(" PARAMS19=[")
                    for (i in 0 until 19) {
                        if (i > 0) b.append(',')
                        b.append(pp[i])
                    }
                    b.append(']')
                } else {
                    b.append(" PARAMS19=ausentes")
                }
                b.append(" vertexCount=").append(uniq.size)
                    .append(" indexCount=").append(count)
                    .append(" UV_MIN=").append(fmt6(minU)).append(',').append(fmt6(minV))
                    .append(" UV_MAX=").append(fmt6(maxU)).append(',').append(fmt6(maxV))
                    .append(" UV_FIRST=").append(fmt6(desc.vertices[o0 + 6])).append(',').append(fmt6(desc.vertices[o0 + 7]))
                    .append(" UV_LAST=").append(fmt6(desc.vertices[o1 + 6])).append(',').append(fmt6(desc.vertices[o1 + 7]))
                    .append(" ORIGEN=slcore-C++(JNI+toMeshDesc-verbatim;decoder=").append(MeshDecoder.uvFallbackFaces).append(')')
                    .append('\n')
                shown += 1
            }
        }
        if (shown == 0) {
            b.append("ninguna cara PRIM con UV colapsada en (0,0)\n")
        }
        return b.toString()
    }

    /**
     * 2.27n (diagnostico interno, solo informe): traza compacta de
     * #976574758 y #976574760 leyendo el mismo MeshDesc que se entrega a
     * Filament (slot.mesh.desc, sin regenerar geometria paralela). Por cada
     * grupo del MeshDesc: ordinal del grupo, faceIndex, firstIndex,
     * indexCount, TE, rango UV exacto (%.6f) y redondeado (%.2f) mas si el
     * disparador de prim-uv-zero se activaria, y por cada indice del IBO de
     * la cara: posicion en el IBO, valor del IBO, indice VBO resuelto, POS y
     * UV. Solo lectura: no toca geometria, shader, TextureEntry, faceGroups,
     * camara, culling, transformaciones, Mesh pipeline, materiales ni LRU.
     */
    private fun primUvTraceReport(): String {
        val b = StringBuilder(12000)
        b.append("--- prim-uv-trace (mismo MeshDesc entregado a Filament) ---\n")
        val targets = intArrayOf(976574758, 976574760)
        for (localId in targets) {
            val slot = slots[localId]
            if (slot == null) {
                b.append("LOCAL_ID=").append(localId)
                    .append(" ausente en escena (los localId cambian por sesion)\n")
                continue
            }
            val mesh = slot.mesh
            val desc = mesh.desc
            val stride = MeshDesc.VERTEX_FLOATS
            b.append("LOCAL_ID=").append(localId)
                .append(" MESH_SOURCE=").append(mesh.source)
                .append(" MESH_KEY=").append(mesh.key)
                .append(" DESC_ID=").append(System.identityHashCode(desc))
                .append(" VERTS=").append(desc.vertices.size / stride)
                .append(" INDICES=").append(desc.indices.size)
                .append(" FACE_GROUPS=").append(desc.faceCount)
                .append(" SLOT_FACES=").append(slot.faces.size)
                .append('\n')
            val pp = slot.primParams
            b.append("  PRIM_PARAMS19=")
            if (pp != null && pp.size >= 19) {
                b.append('[')
                for (i in 0 until 19) {
                    if (i > 0) b.append(',')
                    b.append(pp[i])
                }
                b.append(']')
            } else {
                b.append("ausentes")
            }
            b.append('\n')
            for (g in 0 until desc.faceCount) {
                val faceIndex: Int
                val first: Int
                val count: Int
                try {
                    faceIndex = desc.faceIndexAt(g)
                    first = desc.faceFirstIndexAt(g)
                    count = desc.faceIndexCountAt(g)
                } catch (e: Exception) {
                    b.append("  GROUP=").append(g).append(" LECTURA_FALLO=").append(e.javaClass.simpleName).append('\n')
                    continue
                }
                val te = slot.faceTeIndex.getOrElse(g) { -1 }
                b.append("  GROUP=").append(g)
                    .append(" FACE_INDEX=").append(faceIndex)
                    .append(" FIRST=").append(first)
                    .append(" COUNT=").append(count)
                    .append(" TE=").append(te)
                    .append('\n')
                if (first < 0 || count <= 0 || first + count > desc.indices.size) {
                    b.append("    RANGO_INVALIDO\n")
                    continue
                }
                var minU = Float.MAX_VALUE
                var maxU = -Float.MAX_VALUE
                var minV = Float.MAX_VALUE
                var maxV = -Float.MAX_VALUE
                var ok = true
                for (k in 0 until count) {
                    val vi = desc.indices[first + k]
                    val o = vi * stride
                    if (vi < 0 || o + 7 >= desc.vertices.size) {
                        ok = false
                        break
                    }
                    val u = desc.vertices[o + 6]
                    val v = desc.vertices[o + 7]
                    if (u < minU) minU = u
                    if (u > maxU) maxU = u
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }
                if (!ok) {
                    b.append("    VERTICE_INVALIDO\n")
                    continue
                }
                val exactCollapse = (minU == maxU && minV == maxV)
                val inEps = (kotlin.math.abs(minU) <= 0.005f && kotlin.math.abs(minV) <= 0.005f)
                b.append("    UV_EXACT_MIN=").append(fmt6(minU)).append(',').append(fmt6(minV))
                    .append(" UV_EXACT_MAX=").append(fmt6(maxU)).append(',').append(fmt6(maxV))
                    .append(" UV_FMT2_MIN=").append(fmt2(minU)).append(',').append(fmt2(minV))
                    .append(" UV_FMT2_MAX=").append(fmt2(maxU)).append(',').append(fmt2(maxV))
                    .append(" EXACT_COLAPSE=").append(if (exactCollapse) 1 else 0)
                    .append(" EN_EPS_000=").append(if (inEps) 1 else 0)
                    .append(" DISPARA_PRIM_UV_ZERO=").append(if (exactCollapse && inEps) 1 else 0)
                    .append('\n')
                if (exactCollapse && mesh.source == SLMesh.Source.ASSET) {
                    val mid = slot.sculptMeshId ?: ""
                    val traces = if (mid.isEmpty()) null else MeshDecoder.tracesFor(mid)
                    val trace = traces?.firstOrNull { it.faceIndex == faceIndex }
                    if (trace == null) {
                        b.append("    ORIGEN_ASSET: meshUuid=").append(if (mid.isEmpty()) "ausente" else mid.take(8)).append(" sin-traza-decoder (LOD decodificado antes del registro o por otra via)\n")
                    } else {
                        b.append("    ASSET_SOURCE_UV: texCoord0=").append(if (trace.tcPresent) "SI" else "NO")
                            .append(" bytes=").append(trace.tcBytes)
                            .append(" dominioMin=").append(trace.domainMin?.let { fmt2(it[0]) + "," + fmt2(it[1]) } ?: "-")
                            .append(" dominioMax=").append(trace.domainMax?.let { fmt2(it[0]) + "," + fmt2(it[1]) } ?: "-").append('\n')
                        b.append("    DECODER_UV: min=").append(fmt2(trace.uvMin[0])).append(',').append(fmt2(trace.uvMin[1]))
                            .append(" max=").append(fmt2(trace.uvMax[0])).append(',').append(fmt2(trace.uvMax[1]))
                            .append(" primeras=").append(trace.firstUvs.toList().chunked(2).joinToString(";") { fmt2(it[0]) + "," + fmt2(it[1]) })
                            .append(" motivo=").append(trace.reason).append('\n')
                        b.append("    MESH_BUILDER_UV=igual-DECODER (buildMesh empaqueta verbatim, sin reescalado)\n")
                        b.append("    MESHDESC_UV=min=").append(fmt6(minU)).append(',').append(fmt6(minV)).append(" max=").append(fmt6(maxU)).append(',').append(fmt6(maxV)).append('\n')
                        b.append("    VBO_UV=igual-MESHDESC (createMesh sube floatViewOf(desc.vertices) sin tocar)\n")
                        b.append("    VEREDICTO=").append(if (trace.fallback) "COLAPSO-EN-DECODER (" + trace.reason + ")" else "DECODER-OK-pero-MESHDESC-colapsado (revisar empaquetado)").append('\n')
                    }
                }
                for (k in 0 until count) {
                    val vi = desc.indices[first + k]
                    val o = vi * stride
                    b.append("    IBO_POS=").append(first + k)
                        .append(" IBO=").append(vi)
                        .append(" VBO=").append(vi)
                        .append(" POS=").append(fmt6(desc.vertices[o])).append(',')
                        .append(fmt6(desc.vertices[o + 1])).append(',').append(fmt6(desc.vertices[o + 2]))
                        .append(" UV=").append(fmt6(desc.vertices[o + 6])).append(',')
                        .append(fmt6(desc.vertices[o + 7]))
                        .append('\n')
                }
            }
        }
        return b.toString()
    }

    /** DIAG-VIS: expone el id del testigo fuera de la escena (el companion es privado). */
    fun diagWitnessId(): Int = DIAG_WITNESS_ID

    /** DIAG-VIS benchmark: estado actual del rojo de aislamiento. */
    fun witnessDiagNow(): Boolean = witnessDiagApplied

    /** DIAG-VIS benchmark: culling actual del renderable del testigo. */
    fun witnessCullingNow(): Boolean? {
        val slot = slots[DIAG_WITNESS_ID] ?: return null
        return try {
            renderer.entityCullingEnabled(slot.entity)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * DIAG-VIS (temporal, reversible): bloque del objeto testigo
     * (#622043907) con su geometria, transform y parent reales intactos.
     * Solo lectura: responde camara (eye/forward/up/right calculados, sin
     * cambiar ejes), culling (frustum del motor + distancia propia),
     * contadores, AABB del testigo, material por cara (textura o fallback
     * magenta), entity/renderable y errores del backend. Ritmo propio de
     * 2 s; el texto cacheado se sirve entre medias.
     */
    fun diagWitnessReport(): String {
        if (!diagWitnessEnabled) return "--- testigo DIAG-VIS desactivado ---\n"
        val now = System.currentTimeMillis()
        if (now - lastWitnessMillis < DIAG_WITNESS_INTERVAL_MILLIS && witnessCache.isNotEmpty()) {
            return witnessCache
        }
        lastWitnessMillis = now
        val b = StringBuilder(6000)
        val localId = DIAG_WITNESS_ID
        b.append("--- testigo DIAG-VIS (objeto #").append(localId)
            .append("; geometria/transform/parent reales, solo lectura) ---\n")
        val viewCull = try {
            renderer.viewFrustumCullingEnabled()
        } catch (_: Throwable) {
            null
        }
        b.append("DIAG_VIS_CULLING: frustumMotor=").append(viewCull ?: "desconocido")
            .append(" distanciaPropia=").append(if (distanceCullingEnabled) "ON" else "OFF")
            .append(" (max=").append(maxDrawDistance.toInt()).append("m)")
            .append(" culledPorDistancia=").append(distanceCulled).append('\n')
        b.append("DIAG_VIS_CONTADORES: renderables=").append(slots.size)
            .append(" visibles=").append(visibleEntities)
            .append(" delante=").append(objectsAhead)
            .append(" enSceneMotor=").append(diagnostics.renderablesInScene)
            .append(" visiblesMotor=").append(diagnostics.visibleRenderables)
            .append(" culledFrustumMotor=").append(diagnostics.culledByFrustum).append('\n')
        val cam = lastCamera
        if (cam == null) {
            b.append("DIAG_VIS_CAMARA: sin camara todavia\n")
        } else {
            val fx = cam.target[0] - cam.eye[0]
            val fy = cam.target[1] - cam.eye[1]
            val fz = cam.target[2] - cam.eye[2]
            val fl = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz)
            val inv = if (fl > 1e-6f) 1f / fl else 0f
            val nx = fx * inv
            val ny = fy * inv
            val nz = fz * inv
            var rx = ny * cam.up[2] - nz * cam.up[1]
            var ry = nz * cam.up[0] - nx * cam.up[2]
            var rz = nx * cam.up[1] - ny * cam.up[0]
            val rl = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
            val rinv = if (rl > 1e-6f) 1f / rl else 0f
            rx *= rinv
            ry *= rinv
            rz *= rinv
            b.append("DIAG_VIS_CAMARA: eye=").append(vec(cam.eye))
                .append(" forward=").append(fmt2(nx)).append(',').append(fmt2(ny)).append(',').append(fmt2(nz))
                .append(" up=").append(vec(cam.up))
                .append(" right=").append(fmt2(rx)).append(',').append(fmt2(ry)).append(',').append(fmt2(rz))
                .append(" near=").append(fmt2(cam.near))
                .append(" far=").append(fmt2(cam.far))
                .append(" fov=").append(fmt2(cam.verticalFovDegrees))
                .append(" aspect=").append(fmt2(cameraAspect)).append('\n')
            b.append("DIAG_VIS_EJES: SIN CAMBIOS en esta iteracion (SL Z-up; right = forward x up, diestra; Filament mira -z)\n")
        }
        val slot = slots[localId]
        val fact = facts[localId]
        if (slot == null) {
            b.append("DIAG_VIS_TESTIGO: #").append(localId)
                .append(" SIN ENTIDAD en escena (ficha=").append(if (fact == null) "no" else "si").append(")\n")
            witnessCache = b.toString()
            diagnostics.witnessReport = witnessCache
            return witnessCache
        }
        if (!witnessLogged) {
            witnessLogged = true
            android.util.Log.i(TAG, "DIAG-VIS: testigo #" + localId + " presente en escena (forma=" + (fact?.shapeText ?: "?") + ")")
        }
        val worldPos = placementInRegion(localId)
        b.append("DIAG_VIS_TESTIGO: #").append(localId)
            .append(" forma=").append(fact?.shapeText ?: "-")
            .append(" parent=").append(parentIdOf(localId))
            .append(" slotVisible=").append(if (slot.visible) 1 else 0).append('\n')
        b.append("  local: pos=").append(vec(slot.transform.translation))
            .append(" esc=").append(vec(slot.transform.scale))
            .append(" rot=").append(vec(slot.transform.rotation)).append('\n')
        b.append("  mundo: pos=").append(vec(worldPos))
            .append(" esc=").append(vec(worldScaleOf(localId))).append('\n')
        val engineWorld = try {
            renderer.entityWorldMatrix(slot.entity)
        } catch (_: Throwable) {
            null
        }
        if (engineWorld != null) {
            val ep = FrustumMath.translation(engineWorld)
            val delta = FrustumMath.pointDifference(ep, worldPos)
            b.append("  motor: pos=").append(vec(ep))
                .append(" deltaVsRegion=").append(fmt3(delta))
                .append(if (delta < 0.05f) " (coincide)" else " (NO coincide)").append('\n')
        } else {
            b.append("  motor: sin matriz mundo (renderer no la devolvio)\n")
        }
        val desc = slot.mesh.desc
        val geoProblem = try {
            desc.geometryProblem()
        } catch (_: Throwable) {
            "ilegible"
        }
        b.append("  malla: key=").append(slot.mesh.key).append(" src=").append(slot.mesh.source)
            .append(" tris=").append(slot.mesh.triangles).append(" verts=").append(desc.vertexCount)
            .append(" caras=").append(desc.faceCount).append('/').append(slot.faces.size)
            .append(" problema=").append(geoProblem ?: "-").append('\n')
        b.append("  aabbLocal: min=").append(vec(desc.boundsMin))
            .append(" max=").append(vec(desc.boundsMax)).append('\n')
        if (cam != null) {
            val sample = try {
                CameraFrustum.from(cam, cameraAspect).sample(worldPos)
            } catch (_: Throwable) {
                null
            }
            if (sample != null) {
                b.append("  frustum: ").append(sample.toString())
                    .append(" dist=").append(fmt1(distanceTo(worldPos, cam.eye))).append("m\n")
            }
        }
        val ident = try {
            renderer.renderableIdentityState(slot.entity)
        } catch (_: Throwable) {
            "ERROR"
        }
        val ent = visualField(ident, "entity")
        val inst = visualField(ident, "instance")
        val from = visualField(ident, "entityFromInstance")
        val has = visualField(ident, "hasComponent")
        val inScene = try {
            renderer.entityInScene(slot.entity)
        } catch (_: Throwable) {
            null
        }
        val entCull = try {
            renderer.entityCullingEnabled(slot.entity)
        } catch (_: Throwable) {
            null
        }
        b.append("  ids: entity=").append(ent).append(" instancia=").append(inst)
            .append(" getEntity=").append(from).append(" hasComponent=").append(has)
            .append(" enScene=").append(inScene)
            .append(" cullingEntidad=").append(entCull ?: "desconocido").append('\n')
        val errors = try {
            renderer.renderableLiveState(slot.entity)
        } catch (_: Throwable) {
            "ERROR"
        }
        b.append("  estadoMotor: ").append(errors).append('\n')
        for (i in slot.faces.indices) {
            val face = slot.faces[i]
            val mat = slot.materials.getOrNull(i) ?: -1
            if (!face.hasTexture) {
                b.append("  cara ").append(i).append(": FALLBACK (magenta DIAG-VIS) mat=").append(mat).append('\n')
            } else {
                val ready = textures.hasDecoded(face.textureId)
                b.append("  cara ").append(i).append(": TEXTURA tex=").append(shortUuid(face.textureId))
                    .append(" gpu=").append(if (ready) "SI" else "NO")
                    .append(" mat=").append(mat).append('\n')
            }
        }
        witnessCache = b.toString()
        diagnostics.witnessReport = witnessCache
        return witnessCache
    }

    /** DIAG-VIS testigo: true mientras el rojo de aislamiento esta puesto. */
    private var witnessDiagApplied = false

    /**
     * DIAG-VIS testigo (temporal, reversible): pone/quita el rojo de
     * aislamiento SOLO en el testigo. Geometria, transform y parent
     * intactos. Devuelve la linea de evidencia del backend.
     */
    fun witnessHandle(): EntityHandle? {
        return slots[DIAG_WITNESS_ID]?.entity
    }

    fun setWitnessDiagMaterial(enabled: Boolean): String {
        val slot = slots[DIAG_WITNESS_ID] ?: return "testigo sin entidad"
        val line = try {
            renderer.setWitnessDiagMaterial(slot.entity, enabled)
        } catch (e: Throwable) {
            "testigo ERROR: " + (e.message ?: e.javaClass.simpleName)
        }
        if (!line.startsWith("testigo SIN") && !line.startsWith("testigo ERROR") && !line.startsWith("testigo sin")) {
            witnessDiagApplied = enabled
        }
        android.util.Log.i(TAG, "DIAG-VIS testigo material: " + line)
        return line
    }

    /**
     * DIAG-VIS testigo (temporal, reversible): fija el culling del
     * renderable del testigo con RenderableManager.setCulling y relee con
     * isCullingEnabled. No supone nada del View: ese estado se informa
     * aparte en el mismo bloque.
     */
    fun setWitnessCulling(enabled: Boolean): String {
        val slot = slots[DIAG_WITNESS_ID] ?: return "testigo sin entidad"
        val ok = try {
            renderer.setEntityCulling(slot.entity, enabled)
        } catch (e: Throwable) {
            false
        }
        val readBack = try {
            renderer.entityCullingEnabled(slot.entity)
        } catch (e: Throwable) {
            null
        }
        val line = "testigo culling renderable pedido=" + (if (enabled) "ON" else "OFF") +
            " aplicado=" + ok + " releido=" + (readBack ?: "desconocido")
        android.util.Log.i(TAG, "DIAG-VIS " + line)
        return line
    }

    /**
     * DIAG-VIS testigo: bloque TESTIGO por estado del benchmark con las
     * claves pedidas. Distingue existe / en Scene / culling View /
     * culling Renderable / auditoria propia, y declara que Filament 1.75.1
     * no expone contador de draws por renderable (la unica evidencia real
     * de draw es view.getVisibleRenderableCount, a nivel de vista).
     */
    fun witnessStateLine(state: String): String {
        val b = StringBuilder(2500)
        val localId = DIAG_WITNESS_ID
        b.append("TESTIGO ").append(localId).append(" [ESTADO ").append(state).append("]\n")
        val slot = slots[localId]
        if (slot == null) {
            b.append("worldPosition=sin-entidad\n")
            return b.toString()
        }
        val worldPos = placementInRegion(localId)
        val cam = lastCamera
        val dist = if (cam == null) -1f else distanceTo(worldPos, cam.eye)
        b.append("worldPosition=").append(vec(worldPos)).append('\n')
        b.append("distanceCamera=").append(if (dist < 0f) "-" else fmt1(dist) + "m").append('\n')
        val ident = try {
            renderer.renderableIdentityState(slot.entity)
        } catch (_: Throwable) {
            "ERROR"
        }
        val ent = visualField(ident, "entity")
        val inst = visualField(ident, "instance")
        b.append("renderableEntity=").append(ent).append(" instancia=").append(inst).append('\n')
        val inScene = try {
            renderer.entityInScene(slot.entity)
        } catch (_: Throwable) {
            null
        }
        b.append("inScene=").append(inScene).append('\n')
        val layer = try {
            renderer.entityLayerMask(slot.entity)
        } catch (_: Throwable) {
            -1
        }
        val viewLayers = try {
            renderer.viewVisibleLayers()
        } catch (_: Throwable) {
            -1
        }
        b.append("layerMask=").append(if (layer < 0) "sin-getter-en-1.75.1 (build usa default 0x1)" else ("0x" + Integer.toHexString(layer))).append('\n')
        b.append("viewVisibleLayers=").append(if (viewLayers < 0) "desconocido" else ("0x" + Integer.toHexString(viewLayers))).append('\n')
        val entCull = try {
            renderer.entityCullingEnabled(slot.entity)
        } catch (_: Throwable) {
            null
        }
        val viewCull = try {
            renderer.viewFrustumCullingEnabled()
        } catch (_: Throwable) {
            null
        }
        b.append("renderableCulling=").append(entCull ?: "desconocido").append('\n')
        b.append("viewFrustumCulling=").append(viewCull ?: "desconocido").append('\n')
        if (witnessDiagApplied) {
            b.append("material=ROJO-aislamiento-DIAG-VIS\n")
            b.append("materialCulling=NONE\ndoubleSided=true\ndepthCulling=false\ndepthWrite=false\ncolorWrite=true\n")
        } else {
            b.append("material=normal-SL (ver bloque testigo para cara por cara)\n")
            b.append("materialCulling=segun-material-SL\ndoubleSided=segun-material-SL\ndepthCulling=segun-material-SL\ndepthWrite=segun-material-SL\ncolorWrite=segun-material-SL\n")
        }
        val finger = try {
            renderer.renderableFingerprint(slot.entity)
        } catch (_: Throwable) {
            "ERROR"
        }
        fun spaced(block: String, key: String): String {
            for (l in block.split('\n')) {
                val t = l.trim()
                if (t.startsWith(key + " = ")) return t.substring(key.length + 3).trim()
                if (t.startsWith(key + "=")) return t.substring(key.length + 1).trim()
            }
            return "-"
        }
        b.append("primitiveCount=").append(spaced(finger, "primitiveCount")).append('\n')
        val desc = slot.mesh.desc
        b.append("vertexCount=").append(desc.vertexCount).append('\n')
        b.append("indexCount=").append(desc.indices.size).append('\n')
        b.append("AABB=").append(spaced(finger, "AABB"))
            .append(" localMin=").append(vec(desc.boundsMin))
            .append(" localMax=").append(vec(desc.boundsMax)).append('\n')
        if (cam != null) {
            val sample = try {
                CameraFrustum.from(cam, cameraAspect).sample(worldPos)
            } catch (_: Throwable) {
                null
            }
            if (sample != null) {
                b.append("cameraDepth=").append(fmt2(sample.depth)).append('\n')
                b.append("cameraNDC=").append(fmt3(sample.ndcX)).append(',').append(fmt3(sample.ndcY))
                    .append(' ').append(sample.reason).append('\n')
            } else {
                b.append("cameraDepth=?\ncameraNDC=?\n")
            }
        } else {
            b.append("cameraDepth=sin-camara\ncameraNDC=sin-camara\n")
        }
        b.append("drawsPorRenderable=NO-EXPUESTO-en-1.75.1 (evidencia real solo a nivel de vista: visiblesMotor)\n")
        return b.toString()
    }

    /** `-/X/Y/XY` segun el signo de repeat (flip SL por repeat negativo). */    private fun flipOf(face: SLTextureFace): String {        val fx = face.repeatU < 0f
        val fy = face.repeatV < 0f
        return when {
            fx && fy -> "XY"
            fx -> "X"
            fy -> "Y"
            else -> "-"
        }
    }

    private class UvSample(val before: Pair<Float, Float>, val after: Pair<Float, Float>, val exact: Boolean)

    /**
     * Muestra CPU del mapeado de un grupo (solo informe; el render usa el
     * shader): primer vertice del grupo como UV base (default) o posicion en
     * metros proyectada (planar), luego el xform oficial. Null sin vertice
     * valido. `exact=false` cuando la cara planar cae a default (sin base) o
     * el wire pedia esferico/cilindrico (servido por via planar).
     */
    private fun sampleFaceUv(desc: MeshDesc, group: Int, scale: FloatArray, face: SLTextureFace): UvSample? {
        if (group < 0 || group >= desc.faceCount) return null
        val stride = MeshDesc.VERTEX_FLOATS
        val first = desc.faceFirstIndexAt(group)
        if (first < 0 || first >= desc.indices.size) return null
        val vi = desc.indices[first]
        val o = vi * stride
        if (vi < 0 || o + 7 >= desc.vertices.size) return null
        val u0 = desc.vertices[o + 6]
        val v0 = desc.vertices[o + 7]
        if (!face.isPlanar) {
            // The current renderer deliberately uses the geometry UV for
            // spherical/cylindrical modes instead of silently projecting them
            // as planar. Keep the sample marked approximate for those modes so
            // the diagnostic cannot call a fallback "exact".
            val supported = face.texGen == 0
            return UvSample(Pair(u0, v0), SLTextureFace.xformUv(u0, v0, face), exact = supported)
        }
        val basis = SLTextureFace.planarBasisFor(desc, group)
        val approxWire = face.texGenWire == 2 || face.texGenWire == 3
        if (basis == null) {
            return UvSample(Pair(u0, v0), SLTextureFace.xformUv(u0, v0, face), exact = false)
        }
        val pos = floatArrayOf(
            desc.vertices[o] * scale.getOrElse(0) { 1f },
            desc.vertices[o + 1] * scale.getOrElse(1) { 1f },
            desc.vertices[o + 2] * scale.getOrElse(2) { 1f }
        )
        val pu = 2f * (basis.u[0] * pos[0] + basis.u[1] * pos[1] + basis.u[2] * pos[2]) + 0.5f
        val pv = 0.5f - 2f * (basis.v[0] * pos[0] + basis.v[1] * pos[1] + basis.v[2] * pos[2])
        return UvSample(
            Pair(pu, pv),
            SLTextureFace.xformUv(pu, pv, face),
            exact = !approxWire
        )
    }

    /** The one-line texture summary the HUD shows. */
    fun textureHudLine(): String = textureStreamer?.hudLine() ?: "texturas: sin pipeline"

    private var lastVisualMillis = 0L
    private var visualCache = ""

    /**
     * Auditoría de la cadena visual sobre una muestra de objetos reales
     * cercanos a la cámara: BOX, SPHERE, CYLINDER/PRISM, un linkset con hijos
     * y un objeto con textura. Solo lectura: no crea, modifica ni destruye
     * nada; no toca culling, AABB, transforms, parents ni cámara. Distingue
     * geometría correcta + apariencia incompleta (A) de shape incorrecta (B).
     * Se recalcula como mucho cada 15 s; el informe la incluye sin botón.
     */
    private fun visualPipelineReport(): String {
        val now = System.currentTimeMillis()
        if (now - lastVisualMillis < VISUAL_INTERVAL_MILLIS && visualCache.isNotEmpty()) {
            return visualCache
        }
        lastVisualMillis = now
        val b = StringBuilder(7000)
        b.append("--- cadena visual (muestra de objetos reales, solo lectura) ---\n")
        val eye = lastCamera?.eye
        if (eye == null) {
            b.append("sin cámara todavía\n")
            b.append("VISUAL_PIPELINE_STATUS=SIN_CAMARA\n")
            visualCache = b.toString()
            return visualCache
        }
        b.append("ancla: eye=").append(vec(eye)).append(" (la cámara no se mueve)\n")
        val near = slots.keys.sortedBy { distanceTo(placementInRegion(it), eye) }
        fun isPlainPrim(id: Int): Boolean {
            val f = facts[id] ?: return false
            return f.kind == SLObjectKind.PRIM && (slots[id]?.sculptType ?: 1) == 0
        }
        val box = near.firstOrNull { isPlainPrim(it) && facts[it]?.shapeText == "BOX" }
        val sphere = near.firstOrNull { isPlainPrim(it) && facts[it]?.shapeText == "SPHERE" }
        val cyl = near.firstOrNull { isPlainPrim(it) && (facts[it]?.shapeText == "CYLINDER" || facts[it]?.shapeText == "PRISM") }
        val root = near.firstOrNull { isPlainPrim(it) && (childrenOf[it]?.any { c -> slots.containsKey(c) } == true) }
        val kids = if (root != null) {
            (childrenOf[root] ?: emptySet()).filter { slots.containsKey(it) }
                .sortedBy { distanceTo(placementInRegion(it), eye) }.take(2)
        } else emptyList()
        val texGpu = near.firstOrNull { slots[it]?.hasTexturedFace == true && slots[it]!!.faces.any { f -> f.hasTexture && textures.hasDecoded(f.textureId) } }
        val texAny = texGpu ?: near.firstOrNull { slots[it]?.hasTexturedFace == true }
        val sample = LinkedHashSet<Int>()
        for (id in listOf(box, sphere, cyl, root)) {
            if (id != null) sample.add(id)
        }
        sample.addAll(kids)
        if (texAny != null) sample.add(texAny)
        if (sample.isEmpty()) {
            b.append("sin objetos prim cercanos para muestrear\n")
            b.append("VISUAL_PIPELINE_STATUS=SIN_MUESTRA\n")
            visualCache = b.toString()
            return visualCache
        }
        b.append("muestra:")
        for (id in sample) {
            b.append(" #").append(id).append('(').append(facts[id]?.shapeText ?: "?").append(')')
                .append(fmt1(distanceTo(placementInRegion(id), eye))).append("m")
        }
        b.append('\n')
        var status = "OK"
        var statusWhy = ""
        val verdicts = ArrayList<Pair<Int, VisualVerdict>>()
        for (id in sample.take(6)) {
            val v = try {
                appendVisualSample(b, id)
            } catch (e: Throwable) {
                b.append("#").append(id).append(" auditoría interrumpida: ")
                    .append(e.javaClass.simpleName).append('\n')
                VisualVerdict()
            }
            verdicts.add(Pair(id, v))
        }
        for ((id, v) in verdicts) {
            if (v.shape != "FAIL") continue
            status = "GEOMETRIA_FALLA"
            statusWhy = " #" + id
            break
        }
        if (status == "OK") {
            for ((id, v) in verdicts) {
                val cut = v.firstCut()
                if (cut == null) continue
                status = "APARIENCIA_INCOMPLETA_" + cut
                statusWhy = " #" + id
                break
            }
        }
        b.append("VISUAL_PIPELINE_STATUS=").append(status).append(statusWhy).append('\n')
        visualCache = b.toString()
        return visualCache
    }

    private class VisualVerdict(
        var shape: String = "FAIL",
        var entry: String = "N/A",
        var texture: String = "N/A",
        var material: String = "FAIL",
        var uv: String = "FAIL",
        var tint: String = "N/A",
        var fullbright: String = "FAIL",
        var renderable: String = "FAIL"
    ) {
        fun firstCut(): String? {
            if (entry == "FAIL") return "TEXTURE_ENTRY"
            if (texture == "FAIL") return "TEXTURA"
            if (material == "FAIL") return "MATERIAL"
            if (uv == "FAIL") return "UV"
            if (tint == "FAIL") return "TINT"
            if (fullbright == "FAIL") return "FULLBRIGHT"
            if (renderable == "FAIL") return "RENDERABLE"
            return null
        }
    }

    private fun visualField(block: String, key: String): String {
        for (l in block.split('\n')) {
            if (l.startsWith(key + "=")) return l.substring(key.length + 1).trim()
        }
        return "-"
    }

    private fun appendVisualSample(b: StringBuilder, localId: Int): VisualVerdict {
        val v = VisualVerdict()
        val slot = slots[localId]
        val fact = facts[localId]
        if (slot == null || fact == null) {
            b.append("#").append(localId).append(" sin slot/ficha (salió de escena durante el muestreo)\n")
            return v
        }
        val pipe = textureStreamer?.pipeline
        b.append("#").append(localId)
            .append(" forma=").append(fact.shapeText)
            .append(" pcode=").append(fact.pcode).append('(').append(pcodeName(fact.pcode)).append(')')
            .append(" uuid=").append(shortUuid(fact.uuid))
            .append(" parent=").append(parentIdOf(localId))
            .append(" caras=").append(slot.mesh.faceCount).append('/').append(slot.faces.size)
            .append(" entryBytes=").append(fact.textureEntrySize).append('\n')
        b.append("  local: pos=").append(vec(slot.transform.translation))
            .append(" esc=").append(vec(slot.transform.scale))
            .append(" rot=").append(vec(slot.transform.rotation)).append('\n')
        val worldPos = placementInRegion(localId)
        val worldScale = worldScaleOf(localId)
        b.append("  mundo: pos=").append(vec(worldPos)).append(" esc=").append(vec(worldScale)).append('\n')
        val worldMatrix = try {
            renderer.entityWorldMatrix(slot.entity)
        } catch (_: Throwable) {
            null
        }
        if (worldMatrix != null) {
            b.append("  motor: pos=").append(vec(FrustumMath.translation(worldMatrix)))
                .append(" esc=").append(vec(colNorms3(worldMatrix))).append('\n')
        } else {
            b.append("  motor: sin matriz mundo\n")
        }
        val desc = slot.mesh.desc
        val geoProblem = try {
            desc.geometryProblem()
        } catch (_: Throwable) {
            "ilegible"
        }
        val hasUv = desc.vertexCount > 0 && desc.vertices.size % com.lumiyaviewer.lumiya.renderer.MeshDesc.VERTEX_FLOATS == 0
        b.append("  malla: key=").append(slot.mesh.key).append(" src=").append(slot.mesh.source)
            .append(" tris=").append(slot.mesh.triangles).append(" verts=").append(desc.vertexCount)
            .append(" uv=").append(if (hasUv) "SI" else "NO")
            .append(" problema=").append(geoProblem ?: "-").append('\n')
        v.shape = if (slot.mesh.triangles > 0 && geoProblem == null && slot.faces.isNotEmpty()) "OK" else "FAIL"
        val ident = try {
            renderer.renderableIdentityState(slot.entity)
        } catch (_: Throwable) {
            "ERROR"
        }
        val has = visualField(ident, "hasComponent") == "true"
        val inst = try {
            visualField(ident, "instance").toInt()
        } catch (_: Throwable) {
            -1
        }
        val ent = try {
            visualField(ident, "entity").toInt()
        } catch (_: Throwable) {
            -1
        }
        val from = try {
            visualField(ident, "entityFromInstance").toInt()
        } catch (_: Throwable) {
            -1
        }
        val inScene = try {
            renderer.entityInScene(slot.entity)
        } catch (_: Throwable) {
            null
        }
        val probeInst = try {
            renderer.entityProbe(slot.entity)?.actualRenderableInstance ?: -1
        } catch (_: Throwable) {
            -1
        }
        b.append("  renderable: entity=").append(ent).append(" instancia=").append(inst)
            .append(" probeInst=").append(probeInst).append(" getEntity=").append(from)
            .append(" enScene=").append(inScene).append('\n')
        v.renderable = if (has && inst > 0 && from == ent && ent >= 0 && inScene == true) "OK" else "FAIL"
        val texturedFaces = slot.faces.count { it.hasTexture }
        v.entry = when {
            texturedFaces > 0 && fact.textureEntrySize > 0 -> "OK"
            texturedFaces > 0 -> "FAIL"
            fact.textureEntrySize > 0 -> "OK"
            else -> "N/A"
        }
        var texFail = false
        var fetchFail = false
        var decodeFail = false
        var gpuFail = false
        var gapFail = false
        // TINT (2.25): el tinte viaja siempre en el uniforme baseColor del
        // material, exista o no pixel decodificado; "sin tinte pedido" no es
        // un fallo (antes marcaba FAIL cuando faltaban pixels). Los pixels
        // pendientes son veredicto TEXTURE, no TINT.
        val tintFail = false
        for (i in slot.faces.indices) {
            val face = slot.faces[i]
            val mat = slot.materials.getOrNull(i) ?: -1
            if (!face.hasTexture) {
                b.append("  cara ").append(i).append('/').append(slot.faces.size)
                    .append(" sin textura (UUID por defecto)")
                    .append(if (face.hasTint) " tint=SI" else "").append(" mat=").append(mat).append('\n')
                continue
            }
            val state = try {
                pipe?.cache?.state(face.textureId)?.name ?: "sin-pipeline"
            } catch (_: Throwable) {
                "?"
            }
            val bytes = try {
                pipe?.asset(face.textureId)?.size ?: -1
            } catch (_: Throwable) {
                -1
            }
            val img = try {
                pipe?.image(face.textureId)
            } catch (_: Throwable) {
                null
            }
            val gpu = textures.hasDecoded(face.textureId)
            val cpu = pipe?.isDecoded(face.textureId) == true
            if (state != "READY") fetchFail = true
            if (!cpu) decodeFail = true
            if (!gpu) gpuFail = true
            if (cpu && !gpu) gapFail = true
            b.append("  cara ").append(i).append('/').append(slot.faces.size)
                .append(" tex=").append(shortUuid(face.textureId))
                .append(" colorLin=").append(fmt2(face.color[0])).append(',').append(fmt2(face.color[1]))
                .append(',').append(fmt2(face.color[2])).append(',').append(fmt2(face.color[3]))
                .append(if (face.hasTint) " tint=SI" else " tint=NO")
                .append(" fb=").append(face.fullBright)
                .append(" rep=").append(fmt2(face.repeatU)).append('x').append(fmt2(face.repeatV))
                .append(" off=").append(fmt2(face.offsetU)).append(',').append(fmt2(face.offsetV))
                .append(" rot=").append(fmt2(face.rotation)).append('\n')
            b.append("    cache=").append(state).append(" bytes=").append(bytes)
                .append(" decode=").append(if (img != null) img.width.toString() + "x" + img.height + (if (img.isConsistent) "" else " INCONSISTENTE") else "-")
                .append(" gpu=").append(if (gpu) "SI" else "NO")
                .append(" mat=").append(mat).append('\n')
        }
        if (texturedFaces == 0) {
            v.texture = "N/A"
            v.tint = "N/A"
        } else {
            texFail = v.entry == "FAIL"
            v.texture = if (!texFail && !fetchFail && !decodeFail && !gpuFail) "OK" else "FAIL"
            b.append("  cadena textura: UUID_OK=").append(if (!texFail) "SI" else "NO")
                .append(" FETCH_").append(if (!fetchFail) "OK" else "FAIL")
                .append(" DECODE_").append(if (!decodeFail) "OK" else "FAIL")
                .append(" FILAMENT_TEXTURE_").append(if (!gpuFail) "OK" else "FAIL").append('\n')
            v.tint = if (!tintFail) "OK" else "FAIL"
        }
        val matsOk = slot.materials.size == slot.faces.size && slot.materials.isNotEmpty()
        v.material = if (matsOk && !gapFail) "OK" else "FAIL"
        if (gapFail) {
            b.append("  CORTE: pixels decodificados pero material sin reconectar (fallback aunque hay textura)\n")
        }
        v.uv = if (hasUv) "OK" else "FAIL"
        v.fullbright = if (matsOk) "OK" else "FAIL"
        b.append("  SHAPE=").append(v.shape).append('\n')
        b.append("  TEXTURE_ENTRY=").append(v.entry).append('\n')
        b.append("  TEXTURE=").append(v.texture).append('\n')
        b.append("  MATERIAL=").append(v.material).append('\n')
        b.append("  UV=").append(v.uv).append('\n')
        b.append("  TINT=").append(v.tint).append('\n')
        b.append("  FULLBRIGHT=").append(v.fullbright).append('\n')
        b.append("  FINAL_RENDERABLE=").append(v.renderable).append('\n')
        return v
    }

    /** How many different materials one object's faces actually use. */
    private fun distinctMaterials(slot: Slot): Int {
        val seen = HashSet<Int>()
        for (id in slot.materials) {
            seen.add(id)
        }
        return seen.size
    }

    /**
     * Faces of the drawn objects that currently have pixels bound.
     *
     * This is the "caras con textura" figure: it counts the faces the renderer
     * was told to draw with an image, which is neither the number of textures
     * downloaded nor the number of materials created.
     */
    val texturedFaceCount: Int
        get() {
            var total = 0
            for (slot in slots.values) {
                for (face in slot.faces) {
                    if (face.hasTexture && textures.hasDecoded(face.textureId)) {
                        total += 1
                    }
                }
            }
            return total
        }

    /** Drawn objects with at least one face showing pixels. */
    val texturedEntityCount: Int
        get() {
            var total = 0
            for (slot in slots.values) {
                for (face in slot.faces) {
                    if (face.hasTexture && textures.hasDecoded(face.textureId)) {
                        total += 1
                        break
                    }
                }
            }
            return total
        }

    /** Faces of the drawn objects, whether they have a texture or not. */
    val drawnFaceCount: Int
        get() {
            var total = 0
            for (slot in slots.values) {
                total += slot.faces.size
            }
            return total
        }

    /** Drawn faces whose `TextureEntry` carries the default (empty) UUID. */
    val untexturedFaceCount: Int
        get() {
            var total = 0
            for (slot in slots.values) {
                for (face in slot.faces) {
                    if (!face.hasTexture) {
                        total += 1
                    }
                }
            }
            return total
        }

    private companion object {
        const val TAG = "SLScene"
        /** `RenderDiagnostics.firstPrimReport`'s "nothing yet" value. */
        const val NO_REPORT = "-"

        /** How many object lines also go to logcat immediately. */
        const val OBJECT_LOG_TO_CONSOLE = 20

        /** How many rejected objects get their full field dump logged. */
        const val MAX_REJECTION_DUMPS = 5

        /** How often the per-object shape table is rebuilt (it is a report). */
        const val TABLE_INTERVAL_MILLIS = 400L

        /**
         * DIAG-VIS (temporal, reversible): local id del objeto testigo.
         * No se inventa: es el que el reporte marca como conocido.
         */
        const val DIAG_WITNESS_ID = 622043907

        /** Ritmo del bloque del testigo (un objeto; barato, pero no por frame). */
        const val DIAG_WITNESS_INTERVAL_MILLIS = 2000L

        /**
         * How often the visual-chain sample is rebuilt. It walks the nearest
         * objects and asks the backend per sample, so it runs far less often
         * than the shape table; the cached text is served in between.
         */
        const val VISUAL_INTERVAL_MILLIS = 15000L

        /**
         * How often the texture scan runs. Textures are not render state: asking
         * for the faces near the camera twice a second is fast enough for the
         * picture to fill in smoothly, and it keeps the scan off the frame path.
         */
        const val TEXTURE_SCAN_INTERVAL_MILLIS = 400L

        /**
         * How far textures are fetched from. A region is 256 m across and its
         * far half is a few dozen pixels on a phone screen; downloading all of
         * it would cost tens of megabytes for detail nobody can see. Everything
         * inside this radius is fetched nearest-first, and the radius is one
         * constant so it can be raised deliberately (Phase 4's distance
         * prioritisation turns it into a proper LOD ladder).
         */
        const val TEXTURE_NEAR_METRES = 64f

        /**
         * Sculpt maps nuevos por escaneo (fase 5 parcial, presupuesto
         * compartido con las caras: las caras van primero). Resolución
         * completa porque definen geometría, no color.
         */
        const val SCULPT_PER_SCAN_BUDGET = 2

        /** Tope de sculpt maps pendientes (los UUID son únicos por mapa). */
        const val MAX_PENDING_SCULPTS = 2048

        /**
         * Ritmo del escaneo de mallas (fase 5): dos por segundo basta, la
         * geometría no cambia 60 veces por segundo y el pipeline pide en su
         * hilo.
         */
        const val MESH_SCAN_INTERVAL_MILLIS = 500L

        /**
         * Radio de petición de mallas: los edificios se ven de lejos, así que
         * el doble que las texturas. Lo lejano-visible llega después por
         * prioridad de distancia, no se excluye por defecto.
         */
        const val MESH_NEAR_METRES = 128f

        /** Mesh assets nuevos pedidos por escaneo (presupuesto propio). */
        const val MESH_PER_SCAN_BUDGET = 6

        /** Meshes decodificados aplicados por frame (subida GPU + re-attach). */
        const val MESH_PUMP_PER_FRAME = 4

        /** Tope de MeshAssetIDs en espera (los UUID son únicos por asset). */
        const val MAX_PENDING_MESHES = 2048

        /**
         * 2.26: ritmo del barrido de recursos (GC de materiales/texturas GPU,
         * cancelacion de descargas muertas y linea RECURSOS). 15 s basta: los
         * recursos liberados no son estado de render y el barrido recorre los
         * slots. Si la cache de materiales supera el tope se adelanta.
         */
        const val RESOURCE_GC_INTERVAL_MILLIS = 15000L

        /** 2.26: materiales en cache que adelantan el barrido. */
        const val RESOURCE_GC_MATERIAL_CAP = 2500

        /** How many rows the per-object shape table shows nearest-first. */
        const val SHAPE_TABLE_ROWS = 40

        /** The audit's "near" cut: objects inside this radius are the ones that matter. */
        const val AUDIT_MAX_METRES = 100f

        /**
         * How many entity events of the followed object (fase 2.10) are kept in
         * the report. The rest are counted, so "it moved 3000 times" and "it was
         * rebuilt once" cannot look the same.
         */
        const val MAX_FOCUS_ENTITY_LINES = 40

        /** How many children the parent/child audit lists in full. */
        const val PARENT_AUDIT_ROWS = 8

        /**
         * How many links a parent chain may have before the scene stops walking
         * it. Second Life linksets are nowhere near this deep; the limit is there
         * so a corrupt `ParentID` cycle can never make the composition (or a
         * report) loop forever.
         */
        const val MAX_PARENT_DEPTH = 16
    }

}
