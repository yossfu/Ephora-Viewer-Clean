// GENERADO desde tests_render.kt por tools/kcheck/make_group.js. NO EDITAR.
// Grupo pesado: depende de slscene/slworld.
package com.lumiyaviewer.lumiya.kcheck

import com.lumiyaviewer.lumiya.renderer.AlphaMode
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
import com.lumiyaviewer.lumiya.slproto.base.LLUUIDUtil
import com.lumiyaviewer.lumiya.slproto.base.Quaternion
import com.lumiyaviewer.lumiya.slproto.base.Vector3
import com.lumiyaviewer.lumiya.slproto.asset.DecodedTexture
import com.lumiyaviewer.lumiya.slproto.asset.FakeTextureAssetProvider
import com.lumiyaviewer.lumiya.slproto.asset.GetTextureAssetProvider
import com.lumiyaviewer.lumiya.slproto.asset.SyntheticTextureDecoder
import com.lumiyaviewer.lumiya.slproto.asset.TextureAsset
import com.lumiyaviewer.lumiya.slproto.asset.TextureAssetCache
import com.lumiyaviewer.lumiya.slproto.asset.TextureAssetProvider
import com.lumiyaviewer.lumiya.slproto.asset.TextureAssetResult
import com.lumiyaviewer.lumiya.slproto.asset.TextureAssetState
import com.lumiyaviewer.lumiya.slproto.asset.TextureDecoder
import com.lumiyaviewer.lumiya.slproto.asset.TexturePipeline
import com.lumiyaviewer.lumiya.slproto.asset.TexturePipelineEvent
import com.lumiyaviewer.lumiya.slproto.asset.TextureTransport
import com.lumiyaviewer.lumiya.slproto.asset.TextureTransportReply
import com.lumiyaviewer.lumiya.slproto.asset.UnavailableTextureDecoder
import com.lumiyaviewer.lumiya.slproto.asset.TextureFailureKind
import com.lumiyaviewer.lumiya.slproto.asset.TextureFetchFailure
import com.lumiyaviewer.lumiya.slproto.asset.TextureTransportStats
import com.lumiyaviewer.lumiya.slproto.asset.failureReading
import com.lumiyaviewer.lumiya.slproto.caps.CapabilityCallFailure
import com.lumiyaviewer.lumiya.slproto.caps.HttpText
import com.lumiyaviewer.lumiya.slproto.caps.assetRequestFor
import com.lumiyaviewer.lumiya.slproto.caps.assetUrlFor
import com.lumiyaviewer.lumiya.slproto.caps.textureFailureOf
import com.lumiyaviewer.lumiya.slproto.messages.BlockDef
import com.lumiyaviewer.lumiya.slproto.messages.BlockRepeat
import com.lumiyaviewer.lumiya.slproto.messages.FieldDef
import com.lumiyaviewer.lumiya.slproto.messages.FieldType
import com.lumiyaviewer.lumiya.slproto.messages.Frequency
import com.lumiyaviewer.lumiya.slproto.messages.MessageDef
import com.lumiyaviewer.lumiya.slproto.messages.SLMessage
import com.lumiyaviewer.lumiya.slproto.messages.SLMessageCodec
import com.lumiyaviewer.lumiya.slproto.movement.AgentControlFlags
import com.lumiyaviewer.lumiya.slproto.movement.AgentUpdateBuilder
import com.lumiyaviewer.lumiya.slproto.movement.MoveAction
import com.lumiyaviewer.lumiya.slproto.movement.MovementAudit
import com.lumiyaviewer.lumiya.slproto.movement.MovementBreak
import com.lumiyaviewer.lumiya.slproto.world.ByteReader
import com.lumiyaviewer.lumiya.slproto.world.ObjectUpdateDecoder
import com.lumiyaviewer.lumiya.slproto.world.ObjectUpdateDiagnostics
import com.lumiyaviewer.lumiya.slproto.world.PrimShape
import com.lumiyaviewer.lumiya.slproto.world.PrimShapeClassifier
import com.lumiyaviewer.lumiya.slproto.world.SceneObject
import com.lumiyaviewer.lumiya.slproto.world.ShapeSource
import com.lumiyaviewer.lumiya.slproto.world.TextureEntry
import com.lumiyaviewer.lumiya.slproto.world.TextureEntryReference
import com.lumiyaviewer.lumiya.slproto.world.UpdateSource
import com.lumiyaviewer.lumiya.slproto.world.WorldModel
import com.lumiyaviewer.lumiya.slscene.CameraFrustum
import com.lumiyaviewer.lumiya.slscene.FrustumReason
import com.lumiyaviewer.lumiya.slscene.MatrixFrustum
import com.lumiyaviewer.lumiya.slscene.PrimGeometryNative
import com.lumiyaviewer.lumiya.slscene.SLCamera
import com.lumiyaviewer.lumiya.slscene.SLCameraProbe
import com.lumiyaviewer.lumiya.slscene.SLDiagnosticProbe
import com.lumiyaviewer.lumiya.slscene.SLScene
import com.lumiyaviewer.lumiya.slscene.SLTerrain
import com.lumiyaviewer.lumiya.slscene.SLTestScene
import com.lumiyaviewer.lumiya.slworld.SLMeshLibrary
import com.lumiyaviewer.lumiya.slworld.SLObject
import com.lumiyaviewer.lumiya.slworld.SLObjectKind
import com.lumiyaviewer.lumiya.slworld.SLTerrainSnapshot
import com.lumiyaviewer.lumiya.slworld.SLTextureFace
import com.lumiyaviewer.lumiya.slworld.SLWorld
import com.lumiyaviewer.lumiya.slworld.SLRegion
import com.lumiyaviewer.lumiya.slworld.SLWorldDelta
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Headless checks for the Second Life → scene → renderer pipeline.
 *
 * The graphics backend is replaced by a recording [FakeRenderer], so everything
 * between the protocol's world model and the renderer's calls is exercised for
 * real: shape classification, transform maths, snaphot diffing, the mesh cache,
 * material reuse and distance culling.
 */

private fun pcodeClassificationCheck(): String {
    val expected = mapOf(
        "PCODE_PRIM" to 9,
        "PCODE_AVATAR" to 47,
        "PCODE_GRASS" to 95,
        "PCODE_NEW_TREE" to 111,
        "PCODE_PARTICLE_SYSTEM" to 143,
        "PCODE_LEGACY_ROCK" to 159,
        "PCODE_LEGACY_TREE" to 255
    )
    val actual = mapOf(
        "PCODE_PRIM" to SceneObject.PCODE_PRIM,
        "PCODE_AVATAR" to SceneObject.PCODE_AVATAR,
        "PCODE_GRASS" to SceneObject.PCODE_GRASS,
        "PCODE_NEW_TREE" to SceneObject.PCODE_NEW_TREE,
        "PCODE_PARTICLE_SYSTEM" to SceneObject.PCODE_PARTICLE_SYSTEM,
        "PCODE_LEGACY_ROCK" to SceneObject.PCODE_LEGACY_ROCK,
        "PCODE_LEGACY_TREE" to SceneObject.PCODE_LEGACY_TREE
    )
    for ((name, value) in expected) {
        if (actual[name] != value) return name + " = " + actual[name] + " (esperado " + value + ")"
    }

    fun kindOf(pcode: Int): SLObjectKind = kindOfObject(pcode).kind

    val table = listOf(
        9 to SLObjectKind.PRIM,
        47 to SLObjectKind.AVATAR,
        95 to SLObjectKind.GRASS,
        111 to SLObjectKind.TREE,
        255 to SLObjectKind.TREE,
        143 to SLObjectKind.UNKNOWN,
        159 to SLObjectKind.UNKNOWN,
        0 to SLObjectKind.UNKNOWN
    )
    for ((pcode, want) in table) {
        val got = kindOf(pcode)
        if (got != want) return "pcode " + pcode + " -> " + got + " (esperado " + want + ")"
    }

    // A square-profile prim is a BOX, not a tree: the shape comes from the
    // path/profile pair, never from the pcode.
    if (kindOfObject(9).prim == null) return "un prim con parametros no genero primitiva"
    if (kindOfObject(9).prim?.shape != PrimShape.BOX) {
        return "un prim cuadrado no es BOX: " + kindOfObject(9).prim?.shape
    }

    // An object whose path/profile block never arrived must be carried for its
    // transform only: no shape may be invented for it.
    val noParams = SceneObject(localId = 2, pcode = 9)
    noParams.positionKnown = true
    noParams.revision = 1
    val withoutParams = SLObject.from(noParams, PrimGeometryNative.DETAIL_STANDARD)
    if (withoutParams.kind != SLObjectKind.PRIM) return "sin parametros: tipo " + withoutParams.kind
    if (withoutParams.prim != null) return "sin parametros: se invento una primitiva"
    if (withoutParams.isRenderable) return "sin parametros: se marcaria como dibujable"
    return "OK"
}

/** A well-formed prim object of the given pcode, with parameters present. */

private fun kindOfObject(pcode: Int): SLObject {
    val object_ = SceneObject(localId = 1, pcode = pcode)
    object_.pathCurve = 0x10
    object_.profileCurve = 0x01
    object_.paramsKnown = true
    object_.positionKnown = true
    object_.revision = 1
    return SLObject.from(object_, PrimGeometryNative.DETAIL_STANDARD)
}

/**
 * `MeshDesc.geometryProblem()` — the one gate every mesh passes before a backend
 * sees it.
 *
 * The first case is the exact failure reported on the device: a mesh with no
 * face groups. `faceCount` is then zero, and the old code turned that into
 * `faceGroups[-2]` with `faceGroups.length == 0` — `ArrayIndexOutOfBoundsException
 * length=0; index=-2` in `buildRenderable`, once per object, so a region of 523
 * objects produced a scene with no renderables at all.
 */

private fun colorCheck(): String {
    val white = SLTextureFace.argbToLinear(0xFFFFFFFF.toInt())
    for (channel in 0..2) {
        if (kotlin.math.abs(white[channel] - 1f) > 1e-4f) return "blanco canal " + channel + " = " + white[channel]
    }
    if (kotlin.math.abs(white[3] - 1f) > 1e-4f) return "alfa blanco = " + white[3]
    // 0x80 green is sRGB 0.502, which is ~0.2158 linear.
    // sRGB 0x80 is 0.502, which is ~0.2158 in linear space.
    val grey = SLTextureFace.argbToLinear(0xFF808080.toInt())
    for (channel in 0..2) {
        if (kotlin.math.abs(grey[channel] - 0.2158f) > 2e-3f) {
            return "gris lineal canal " + channel + " = " + grey[channel]
        }
    }
    // Pure blue keeps the other channels at zero.
    val blue = SLTextureFace.argbToLinear(0xFF0000FF.toInt())
    if (blue[0] > 1e-4f || blue[1] > 1e-4f) return "los canales ajenos al azul deberian ser 0"
    if (kotlin.math.abs(blue[2] - 1f) > 1e-4f) return "azul = " + blue[2]
    // Half alpha must survive.
    val half = SLTextureFace.argbToLinear(0x80FFFFFF.toInt())
    if (kotlin.math.abs(half[3] - 0.50196f) > 2e-3f) return "alfa = " + half[3]
    return "OK"
}


private fun primitiveParamsCheck(): String {
    val sceneObject = SceneObject(localId = 7)
    sceneObject.pathCurve = 0x20
    sceneObject.profileCurve = 0x05
    sceneObject.profileHollow = 12345
    sceneObject.pathScaleX = 42
    val packed = PrimGeometryNative.packParams(sceneObject)
    if (packed.size != PrimGeometryNative.PARAM_COUNT) {
        return "tamano " + packed.size
    }
    if (packed[0] != 0x20 || packed[1] != 0x05 || packed[17] != 12345 || packed[4] != 42) {
        return "contenido " + packed.joinToString(",")
    }
    if (packed[18] != PrimGeometryNative.DETAIL_STANDARD) {
        return "detalle " + packed[18]
    }
    val a = PrimGeometryNative.packParams(sceneObject)
    val b = PrimGeometryNative.packParams(sceneObject)
    if (PrimGeometryNative.meshKey(a) != PrimGeometryNative.meshKey(b)) {
        return "la clave de malla no es estable"
    }
    // Scale and position are not part of the primitive parameters, so they must
    // not change the cache key: they live in the entity transform.
    sceneObject.scale = Vector3(9f, 9f, 9f)
    sceneObject.position = Vector3(100f, 100f, 100f)
    val moved = PrimGeometryNative.packParams(sceneObject)
    if (PrimGeometryNative.meshKey(moved) != PrimGeometryNative.meshKey(a)) {
        return "la clave de malla depende de la escala/posicion"
    }
    sceneObject.pathRevolutions = 3
    if (PrimGeometryNative.meshKey(PrimGeometryNative.packParams(sceneObject)) == PrimGeometryNative.meshKey(a)) {
        return "la clave de malla ignora las revoluciones"
    }
    return "OK"
}


private fun testSceneCheck(): String {
    val objects = SLTestScene.build()
    if (objects.size != 20) return "objetos " + objects.size
    val keys = HashSet<Long>()
    var prims = 0
    for (object_ in objects) {
        if (object_.kind != SLObjectKind.PRIM) return "tipo " + object_.kind
        if (!object_.isRenderable) return "no dibujable"
        val prim = object_.prim ?: return "sin primitiva"
        keys.add(prim.meshKey)
        prims++
    }
    // Two rows of the same ten shapes: the tilted copies must share mesh keys.
    if (keys.size != 10) return "claves distintas " + keys.size
    if (prims != 20) return "prims " + prims
    val delta = SLTestScene.buildDelta()
    if (delta.added.size != 20) return "delta " + delta.added.size
    if (delta.region.name.isEmpty()) return "region sin nombre"
    return "OK"
}


private fun worldDiffCheck(): String {
    val world = SLWorld()
    val model = WorldModel()
    if (world.sync(model) == null) return "sin region inicial"

    model.put(objectOf(1, Vector3(1f, 2f, 3f)))
    model.put(objectOf(2, Vector3(4f, 5f, 6f)))
    val first = world.sync(model) ?: return "sin primer delta"
    if (first.added.size != 2 || first.updated.isNotEmpty() || first.removed.isNotEmpty()) {
        return "primero +" + first.added.size + " ~" + first.updated.size + " -" + first.removed.size
    }
    if (world.sync(model) != null) return "segundo sync deberia ser nulo"
    if (world.activeRegion?.objectCount != 2) return "objetos en la region " + world.activeRegion?.objectCount

    model.put(objectOf(1, Vector3(9f, 9f, 9f)))
    val second = world.sync(model) ?: return "sin segundo delta"
    if (second.updated.size != 1 || second.added.isNotEmpty()) {
        return "segundo +" + second.added.size + " ~" + second.updated.size
    }

    model.remove(2)
    val third = world.sync(model) ?: return "sin tercer delta"
    if (third.removed.size != 1 || third.removed[0] != 2) return "eliminados " + third.removed
    if (world.activeRegion?.objectCount != 1) return "quedan " + world.activeRegion?.objectCount
    return "OK"
}


private fun cameraCheck(): String {
    val camera = SLCamera()
    camera.setFocus(128f, 128f, 25f)
    camera.yaw = 0f
    camera.pitch = 0.5f
    camera.distance = 10f
    val desc = camera.desc()
    if (desc.target[0] != 128f || desc.target[2] != 25f) return "objetivo mal"
    // Looking along +X, so the camera sits at a smaller X and above the focus.
    if (!(desc.eye[0] < 128f)) return "eye.x = " + desc.eye[0]
    if (!(desc.eye[2] > 25f)) return "eye.z = " + desc.eye[2]
    val dx = desc.eye[0] - 128f
    val dy = desc.eye[1] - 128f
    val dz = desc.eye[2] - 25f
    val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    if (kotlin.math.abs(distance - 10f) > 1e-3f) return "distancia " + distance
    if (desc.up[2] != 1f) return "arriba no es Z"
    // A second orbit moves the eye but keeps the distance.
    camera.orbit(100f, 0f)
    val moved = camera.desc()
    val mdx = moved.eye[0] - 128f
    val mdy = moved.eye[1] - 128f
    val mdz = moved.eye[2] - 25f
    val movedDistance = kotlin.math.sqrt(mdx * mdx + mdy * mdy + mdz * mdz)
    if (kotlin.math.abs(movedDistance - 10f) > 1e-3f) {
        return "la orbita cambio la distancia (" + movedDistance + ")"
    }
    if (moved.eye[1] == desc.eye[1]) return "la orbita no movio la camara"
    return "OK"
}


private fun scenePipelineCheck(): String {
    val renderer = FakeRenderer()
    val scene = SLScene(renderer, SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed))

    scene.apply(SLTestScene.buildDelta())
    if (renderer.meshCount != 10) return "mallas creadas " + renderer.meshCount
    if (renderer.entityCount != 20) return "entidades creadas " + renderer.entityCount
    if (scene.entityCount != 20) return "entidades en escena " + scene.entityCount
    // Ten shapes, each with its own texture: the second row must reuse them.
    if (renderer.materialsCreated != 10) return "materiales creados " + renderer.materialsCreated
    if (scene.textures.reused != 10) return "materiales reutilizados " + scene.textures.reused
    if (renderer.materialsUpdated != 0) return "materiales actualizados de mas"

    // Nothing changed: moving the camera again must not touch the renderer.
    val transformCalls = renderer.transformsUpdated
    scene.prune(cameraDescAt(0f, 0f, 0f))
    if (scene.visibleEntities != 20) return "visibles con la camara cerca " + scene.visibleEntities
    // Everything is already visible, so culling must not issue a single call.
    if (renderer.visibilityChanges != 0) return "visibilidad redundante al acercar"
    scene.prune(cameraDescAt(5000f, 0f, 0f))
    if (scene.visibleEntities != 0) return "visibles con la camara lejos " + scene.visibleEntities
    if (renderer.visibilityChanges != 20) return "cambios de visibilidad " + renderer.visibilityChanges
    scene.prune(cameraDescAt(5000f, 0f, 0f))
    if (renderer.visibilityChanges != 20) return "visibilidad redundante al alejar"

    // A transform-only update must reuse the entity and the mesh.
    val update = mutableListOf<SLObject>()
    val source = SLTestScene.build()[0]
    val target = SceneObject(
        localId = source.localId,
        uuid = source.uuid,
        pcode = SceneObject.PCODE_PRIM,
        position = Vector3(1f, 1f, 1f),
        rotation = Quaternion.IDENTITY,
        scale = Vector3(1.4f, 1.4f, 1.4f),
        pathCurve = 0x10,
        profileCurve = 0x01
    )
    target.positionKnown = true
    target.paramsKnown = true
    target.revision = 2
    update.add(SLObject.from(target, PrimGeometryNative.DETAIL_STANDARD))
    val region = SLRegion(0L, 0, 0, "prueba", "", 20f)
    scene.apply(SLWorldDelta(region, emptyList(), update, emptyList(), 0, false))
    if (renderer.meshCount != 10) return "se creo una malla de mas"
    if (renderer.entityCount != 20) return "se creo una entidad de mas"
    if (renderer.transformsUpdated <= transformCalls) return "sin actualizacion de transformacion"

    // Removing objects destroys exactly those entities.
    scene.apply(SLWorldDelta(region, emptyList(), emptyList(), listOf(source.localId, 900002), 0, false))
    if (renderer.entityCount != 18) return "entidades tras borrar " + renderer.entityCount
    if (renderer.destroyedEntities != 2) return "destruidas " + renderer.destroyedEntities

    scene.destroy()
    if (renderer.destroyedMeshes != 10) return "mallas destruidas " + renderer.destroyedMeshes
    return "OK"
}

/**
 * The rule that keeps one bad object from taking the whole scene with it: when
 * the backend refuses an entity, the scene stores no slot for it, counts the
 * refusal, names it in the report — and builds every other object by itself.
 *
 * This is the behaviour the device report needed: "510 objetos, solo 1
 * Renderable". A scene that keeps a dead slot is a scene whose counters lie.
 */

private fun sceneRejectionCheck(): String {
    val renderer = FakeRenderer()
    renderer.rejectEntities = true
    val diagnostics = RenderDiagnostics()
    val scene = SLScene(renderer, SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed), diagnostics)
    val region = SLRegion(0L, 0, 0, "prueba", "", 20f)
    val objects = SLTestScene.build()

    scene.apply(SLWorldDelta(region, objects, emptyList(), emptyList(), 0, false))
    if (scene.entityCount != 0) {
        return "se guardaron slots para entidades invalidas (" + scene.entityCount + ")"
    }
    if (scene.rejectedEntities != objects.size) {
        return "rechazados " + scene.rejectedEntities + " de " + objects.size
    }
    // The backend records the refusal in the shared debug record (the fake
    // writes into its own, exactly like FilamentRenderer writes into the one it
    // was given); the count must match what the scene counted.
    if (renderer.diagnostics.entityRejections != objects.size) {
        return "rechazos en el informe " + renderer.diagnostics.entityRejections
    }
    if (renderer.diagnostics.rejectionLines().isEmpty()) return "sin motivos de rechazo en el informe"
    if (!renderer.diagnostics.lines().any { it.contains("Entidades rechazadas") }) {
        return "el HUD no menciona las entidades rechazadas"
    }
    if (diagnostics.firstPrimReport != "-") {
        return "se anuncio un prim renderizable cuando ninguno llego a entidad"
    }
    // "The region sent no prims" and "the first prim was refused" must not look
    // the same: the first failure carries the object and the reason.
    if (diagnostics.firstPrimFailure == "-") {
        return "sin motivo para el primer prim rechazado"
    }
    if (!diagnostics.firstPrimFailure.contains("geometria") &&
        !diagnostics.firstPrimFailure.contains("rechazo")
    ) {
        return "el motivo del primer prim no explica nada: " + diagnostics.firstPrimFailure
    }
    if (!diagnostics.lines().any { it.contains("Primer prim SL real RECHAZADO") }) {
        return "el HUD no muestra el prim rechazado"
    }

    // The backend now accepts: the next pass has to build all of them, which is
    // what "continue with the rest of the scene" means. No slot was left behind,
    // so nothing is stale and nothing is skipped.
    renderer.rejectEntities = false
    scene.apply(SLWorldDelta(region, objects, emptyList(), emptyList(), 0, false))
    if (scene.entityCount != objects.size) {
        return "no se construyeron el resto (" + scene.entityCount + " de " + objects.size + ")"
    }
    if (scene.primsWithRenderable == 0) return "sin prims con renderable tras aceptar"
    if (diagnostics.firstPrimReport == "-") return "sin informe del primer prim renderizable"
    if (!diagnostics.firstPrimReport.contains("ObjectUpdate -> Renderable") &&
        !diagnostics.lines().any { it.contains("Primer prim SL real") }
    ) {
        return "el informe del primer prim no llega al HUD"
    }
    return "OK"
}

/** The per-object log (spec §2) and the report's caps, which must not hide data. */

private fun meshCacheCheck(): String {
    val renderer = FakeRenderer()
    val library = SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed)
    // Two prims with identical parameters but different sizes and positions.
    val a = sceneObjectOf(1, 0x10, 0x01, Vector3(1f, 1f, 1f), Vector3(0f, 0f, 0f))
    val b = sceneObjectOf(2, 0x10, 0x01, Vector3(4f, 4f, 4f), Vector3(20f, 0f, 0f))
    val primA = com.lumiyaviewer.lumiya.slworld.SLPrimitiveFactory.from(a)
    val primB = com.lumiyaviewer.lumiya.slworld.SLPrimitiveFactory.from(b)
    library.meshFor(renderer, primA, SceneObject.PCODE_PRIM)
    library.meshFor(renderer, primB, SceneObject.PCODE_PRIM)
    if (renderer.meshCount != 1) return "mallas " + renderer.meshCount
    if (library.count != 1) return "mallas en cache " + library.count
    if (library.cacheHits != 1) return "aciertos de cache " + library.cacheHits
    // A different shape is a different mesh.
    val c = sceneObjectOf(3, 0x10, 0x00, Vector3(1f, 1f, 1f), Vector3(40f, 0f, 0f))
    library.meshFor(renderer, com.lumiyaviewer.lumiya.slworld.SLPrimitiveFactory.from(c), SceneObject.PCODE_PRIM)
    if (renderer.meshCount != 2) return "mallas tras cambiar de forma " + renderer.meshCount
    if (library.triangles != 2) return "triangulos " + library.triangles
    return "OK"
}

/**
 * The camera must be somewhere that can actually see the world. These assertions
 * are the spec's section 4 ("comprobar camara"), as far as they can be checked
 * without a screen: the framing camera is outside the box, looking at its centre
 * with the far corner inside the field of view, and a point (nothing to frame)
 * is refused instead of producing a camera at the origin.
 */

private fun framingCheck(): String {
    val aspect = 1.5f
    val camera = SLCamera()
    if (!camera.frameBounds(floatArrayOf(-12f, -8f, 0f), floatArrayOf(20f, 30f, 18f), aspect)) {
        return "no encuadro una caja con volumen"
    }
    if (camera.framing != SLCamera.FRAMING_WORLD) return "encuadre " + camera.framing
    if (camera.focusX != 4f || camera.focusY != 11f || camera.focusZ != 9f) {
        return "centro " + camera.focusX + "," + camera.focusY + "," + camera.focusZ
    }
    if (camera.followAgent) return "el encuadre del mundo deberia dejar de seguir al agente"
    val eye = camera.desc().eye
    // The eye must be outside the box: looking at its middle from inside is a
    // classic way to see nothing and blame the renderer.
    val inside = eye[0] > -12f && eye[0] < 20f &&
        eye[1] > -8f && eye[1] < 30f &&
        eye[2] > 0f && eye[2] < 18f
    if (inside) return "la camara del encuadre quedo dentro de la caja"
    // The forward vector is unit length and points at the focus, so the whole
    // box (including its far corner) has to be in front of the camera.
    val forward = camera.forward
    val length = kotlin.math.sqrt(forward[0] * forward[0] + forward[1] * forward[1] + forward[2] * forward[2])
    if (kotlin.math.abs(length - 1f) > 1e-4f) return "forward no es unitario (" + length + ")"
    val corner = floatArrayOf(20f - eye[0], 30f - eye[1], 18f - eye[2])
    val toCorner = normalise(corner)
    val dot = forward[0] * toCorner[0] + forward[1] * toCorner[1] + forward[2] * toCorner[2]
    if (dot < 0.2f) return "la esquina lejana queda fuera del campo de vision (dot " + dot + ")"

    // A point has nothing to frame: the caller gets false, not a camera at zero.
    if (SLCamera().frameBounds(floatArrayOf(5f, 5f, 5f), floatArrayOf(5f, 5f, 5f), aspect)) {
        return "encuadro una caja sin volumen"
    }

    // lookAt places the eye at a known distance, aimed at a known point.
    val look = SLCamera()
    look.lookAt(floatArrayOf(50f, 60f, 7f), 12f, 0f, 0.3f)
    if (look.focusX != 50f || look.focusY != 60f || look.focusZ != 7f) return "lookAt no fijo el foco"
    val lookEye = look.desc().eye
    val dx = lookEye[0] - 50f
    val dy = lookEye[1] - 60f
    val dz = lookEye[2] - 7f
    val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    if (kotlin.math.abs(distance - 12f) > 1e-3f) return "lookAt distancia " + distance
    val toPoint = normalise(floatArrayOf(50f - lookEye[0], 60f - lookEye[1], 7f - lookEye[2]))
    val lookForward = look.forward
    val alignment = lookForward[0] * toPoint[0] + lookForward[1] * toPoint[1] + lookForward[2] * toPoint[2]
    if (alignment < 0.999f) return "lookAt no apunta al objetivo (" + alignment + ")"
    return "OK"
}

/**
 * PRUEBA A, checked as far as a machine without a GPU can: the probe's meshes
 * must be well formed. Outward winding matters most — a cube wound the wrong way
 * is exactly the kind of "created but invisible" object that makes a black
 * screen look like a protocol bug.
 */

private fun probeMeshCheck(): String {
    val cube = SLDiagnosticProbe.cubeMesh(2f)
    if (cube.vertexCount != 24) return "cubo vertices " + cube.vertexCount
    if (cube.indices.size != 36) return "cubo indices " + cube.indices.size
    if (cube.faceCount != 1) return "cubo caras " + cube.faceCount
    if (cube.boundsMin[0] != -1f || cube.boundsMin[1] != -1f || cube.boundsMin[2] != -1f) {
        return "cubo min " + cube.boundsMin.joinToString(",")
    }
    if (cube.boundsMax[0] != 1f || cube.boundsMax[1] != 1f || cube.boundsMax[2] != 1f) {
        return "cubo max " + cube.boundsMax.joinToString(",")
    }
    val cubeWinding = windingOutward(cube)
    if (cubeWinding.isNotEmpty()) return "cubo: " + cubeWinding

    val sphere = SLDiagnosticProbe.sphereMesh(1.2f)
    if (sphere.vertexCount != 273) return "esfera vertices " + sphere.vertexCount
    if (sphere.indices.size != 1440) return "esfera indices " + sphere.indices.size
    for (vertex in 0 until sphere.vertexCount) {
        val offset = vertex * MeshDesc.VERTEX_FLOATS
        val x = sphere.vertices[offset]
        val y = sphere.vertices[offset + 1]
        val z = sphere.vertices[offset + 2]
        val radius = kotlin.math.sqrt(x * x + y * y + z * z)
        if (kotlin.math.abs(radius - 1.2f) > 1e-3f) return "esfera vertice " + vertex + " radio " + radius
        if (kotlin.math.abs(sphere.vertices[offset + 3] - x / 1.2f) > 2e-3f ||
            kotlin.math.abs(sphere.vertices[offset + 4] - y / 1.2f) > 2e-3f ||
            kotlin.math.abs(sphere.vertices[offset + 5] - z / 1.2f) > 2e-3f
        ) {
            return "esfera vertice " + vertex + " normal incorrecta"
        }
    }
    val sphereWinding = windingOutward(sphere)
    if (sphereWinding.isNotEmpty()) return "esfera: " + sphereWinding

    val plane = SLDiagnosticProbe.planeMesh()
    if (plane.vertexCount != 4) return "suelo vertices " + plane.vertexCount
    if (plane.indices.size != 6) return "suelo indices " + plane.indices.size
    if (plane.vertices[5] != 1f) return "el suelo no mira hacia arriba"

    // The probe's own camera must have its focus dead ahead of it, or the
    // "pure Filament" test could fail for the same reason the region does.
    val probeCamera = SLCamera()
    probeCamera.lookAt(SLDiagnosticProbe.FOCUS, SLDiagnosticProbe.DISTANCE, SLDiagnosticProbe.YAW, SLDiagnosticProbe.PITCH)
    val probeEye = probeCamera.desc().eye
    val originDirection = normalise(floatArrayOf(-probeEye[0], -probeEye[1], -probeEye[2]))
    val probeForward = probeCamera.forward
    val originAlignment = probeForward[0] * originDirection[0] +
        probeForward[1] * originDirection[1] + probeForward[2] * originDirection[2]
    if (originAlignment < 0.9f) return "el suelo del probe no queda delante de la camara (" + originAlignment + ")"

    // Through the renderer interface: three meshes, four materials, four
    // entities — and none of them left behind after destroy().
    val renderer = FakeRenderer()
    val probe = SLDiagnosticProbe.build(renderer)
    if (probe.entityCount != 4) return "entidades del probe " + probe.entityCount
    if (renderer.meshCount != 3) return "mallas del probe " + renderer.meshCount
    if (renderer.materialsCreated != 4) return "materiales del probe " + renderer.materialsCreated
    if (renderer.entityCount != 4) return "entidades creadas del probe " + renderer.entityCount
    val expectedVertices = cube.vertexCount * 2 + sphere.vertexCount + plane.vertexCount
    if (probe.vertices != expectedVertices) return "vertices del probe " + probe.vertices
    val expectedTriangles = (cube.indices.size * 2 + sphere.indices.size + plane.indices.size) / 3
    if (probe.triangles != expectedTriangles) return "triangulos del probe " + probe.triangles
    probe.destroy()
    if (renderer.destroyedEntities != 4) return "entidades destruidas " + renderer.destroyedEntities
    if (renderer.destroyedMeshes != 3) return "mallas destruidas " + renderer.destroyedMeshes
    return "OK"
}

/**
 * PRUEBA C, checked without a GPU: the height field the region sends becomes a
 * mesh whose vertices are at the right places, pointing up, with the region's
 * own coordinates (no rescaling), and whose entity survives a rebuild.
 */

private fun terrainMeshCheck(): String {
    if (SLTerrain.spanSamples(256, 1) != 256) return "muestreo a paso 1: " + SLTerrain.spanSamples(256, 1)
    if (SLTerrain.spanSamples(256, 2) != 129) return "muestreo a paso 2: " + SLTerrain.spanSamples(256, 2)
    if (SLTerrain.spanSamples(256, 4) != 65) return "muestreo a paso 4: " + SLTerrain.spanSamples(256, 4)

    // A tilted plane: height = 0.1 * (x + y) metres, the region's own units.
    val snapshot = terrainSnapshot(256, 0) { x, y -> (x + y) * 0.1f }
    if (snapshot.patchesExpected != 256) return "parches esperados " + snapshot.patchesExpected

    val desc = SLTerrain.buildMesh(snapshot, 2) ?: return "sin malla a paso 2"
    if (desc.vertexCount != 16641) return "vertices " + desc.vertexCount
    if (desc.indices.size != 98304) return "indices " + desc.indices.size
    if (desc.faceCount != 1) return "caras " + desc.faceCount
    // (8, 4) is the fifth column of the third row: 0.1 * (8 + 4) = 1.2 m.
    val vertex = (2 * 129 + 4) * MeshDesc.VERTEX_FLOATS
    if (desc.vertices[vertex] != 8f || desc.vertices[vertex + 1] != 4f) {
        return "posicion del vertice " + desc.vertices[vertex] + "," + desc.vertices[vertex + 1]
    }
    if (kotlin.math.abs(desc.vertices[vertex + 2] - 1.2f) > 1e-4f) {
        return "altura del vertice " + desc.vertices[vertex + 2]
    }
    if (desc.vertices[vertex + 5] < 0.98f) return "la normal no apunta hacia arriba " + desc.vertices[vertex + 5]
    if (kotlin.math.abs(desc.boundsMin[0]) > 1e-4f || kotlin.math.abs(desc.boundsMin[1]) > 1e-4f) {
        return "min " + desc.boundsMin.joinToString(",")
    }
    if (kotlin.math.abs(desc.boundsMax[0] - 255f) > 1e-4f || kotlin.math.abs(desc.boundsMax[1] - 255f) > 1e-4f) {
        return "max " + desc.boundsMax.joinToString(",")
    }
    if (kotlin.math.abs(desc.boundsMax[2] - 51f) > 1e-3f) return "altura maxima " + desc.boundsMax[2]
    val winding = windingUp(desc)
    if (winding.isNotEmpty()) return "terreno: " + winding

    val coarse = SLTerrain.buildMesh(snapshot, 4) ?: return "sin malla a paso 4"
    if (coarse.vertexCount != 4225) return "vertices a paso 4 " + coarse.vertexCount
    if (SLTerrain.buildMesh(terrainSnapshot(1, 0) { _, _ -> 0f }, 2) != null) {
        return "una rejilla de 1 muestra deberia dar null"
    }

    // The entity side: one material, one mesh, and coalesced rebuilds.
    val renderer = FakeRenderer()
    val terrain = SLTerrain(renderer)
    if (!terrain.update(snapshot, 0L)) return "el primer snapshot no construyo la malla"
    if (!terrain.hasMesh) return "sin entidad de terreno"
    if (terrain.rebuilds != 1) return "reconstrucciones " + terrain.rebuilds
    if (terrain.triangles != 32768) return "triangulos " + terrain.triangles
    if (terrain.vertices != 16641) return "vertices en la entidad " + terrain.vertices
    if (terrain.spanMetres != 255f) return "extension " + terrain.spanMetres
    if (renderer.materialsCreated != 1) return "materiales del terreno " + renderer.materialsCreated
    if (terrain.update(snapshot, 16L)) return "reconstruyo sin cambios"
    val newer = terrainSnapshot(256, 1) { x, y -> (x + y) * 0.1f + 1f }
    if (terrain.update(newer, 100L)) return "reconstruyo antes del tiempo minimo"
    if (!terrain.update(newer, 500L)) return "no reconstruyo el terreno nuevo"
    if (terrain.rebuilds != 2) return "reconstrucciones tras cambiar " + terrain.rebuilds
    if (renderer.meshCount != 1) return "mallas vivas del terreno " + renderer.meshCount
    if (renderer.materialCount != 1) return "materiales vivos del terreno " + renderer.materialCount
    terrain.destroy()
    if (terrain.hasMesh) return "destroy dejo la entidad"
    if (renderer.entityCount != 0) return "destroy dejo entidades " + renderer.entityCount
    return "OK"
}

/**
 * The debug record itself: the HUD has to carry the real numbers, keep the
 * first error instead of the last, and report the three acceptance tests with
 * the honest wording (counters are evidence, a screen is a verdict).
 */

private fun cameraDescAt(x: Float, y: Float, z: Float): CameraDesc = CameraDesc(
    eye = floatArrayOf(x, y, z),
    target = floatArrayOf(x, y, z - 1f),
    up = floatArrayOf(0f, 0f, 1f),
    verticalFovDegrees = 60f
)


private fun normalise(v: FloatArray): FloatArray {
    val length = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    val inverse = if (length > 1e-6f) 1f / length else 0f
    return floatArrayOf(v[0] * inverse, v[1] * inverse, v[2] * inverse)
}

/** A height grid where `height(x, y)` gives metres above the region's zero plane. */

private fun terrainSnapshot(size: Int, version: Int, height: (Int, Int) -> Float): SLTerrainSnapshot {
    val heights = FloatArray(size * size)
    var min = Float.MAX_VALUE
    var max = -Float.MAX_VALUE
    for (y in 0 until size) {
        for (x in 0 until size) {
            val value = height(x, y)
            heights[y * size + x] = value
            if (value < min) min = value
            if (value > max) max = value
        }
    }
    return SLTerrainSnapshot(0L, version, 22, size, min, max, heights)
}

/**
 * Every triangle must face away from the origin (the shape's centre). A
 * degenerate triangle at a sphere's pole has no normal and is skipped.
 */

private fun windingOutward(mesh: MeshDesc): String {
    var index = 0
    while (index + 2 < mesh.indices.size) {
        val a = mesh.indices[index]
        val b = mesh.indices[index + 1]
        val c = mesh.indices[index + 2]
        val ax = mesh.vertices[a * MeshDesc.VERTEX_FLOATS]
        val ay = mesh.vertices[a * MeshDesc.VERTEX_FLOATS + 1]
        val az = mesh.vertices[a * MeshDesc.VERTEX_FLOATS + 2]
        val bx = mesh.vertices[b * MeshDesc.VERTEX_FLOATS]
        val by = mesh.vertices[b * MeshDesc.VERTEX_FLOATS + 1]
        val bz = mesh.vertices[b * MeshDesc.VERTEX_FLOATS + 2]
        val cx = mesh.vertices[c * MeshDesc.VERTEX_FLOATS]
        val cy = mesh.vertices[c * MeshDesc.VERTEX_FLOATS + 1]
        val cz = mesh.vertices[c * MeshDesc.VERTEX_FLOATS + 2]
        val ux = bx - ax
        val uy = by - ay
        val uz = bz - az
        val vx = cx - ax
        val vy = cy - ay
        val vz = cz - az
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        if (length > 1e-6f) {
            val centreX = (ax + bx + cx) / 3f
            val centreY = (ay + by + cy) / 3f
            val centreZ = (az + bz + cz) / 3f
            val dot = (nx * centreX + ny * centreY + nz * centreZ) / length
            if (dot <= 0f) return "cara " + (index / 3) + " no mira hacia fuera"
        }
        index += 3
    }
    return ""
}

/** Every triangle of the terrain must face up (+Z), which is its front face. */

private fun windingUp(mesh: MeshDesc): String {
    var index = 0
    while (index + 2 < mesh.indices.size) {
        val a = mesh.indices[index]
        val b = mesh.indices[index + 1]
        val c = mesh.indices[index + 2]
        val ax = mesh.vertices[a * MeshDesc.VERTEX_FLOATS]
        val ay = mesh.vertices[a * MeshDesc.VERTEX_FLOATS + 1]
        val az = mesh.vertices[a * MeshDesc.VERTEX_FLOATS + 2]
        val ux = mesh.vertices[b * MeshDesc.VERTEX_FLOATS] - ax
        val uy = mesh.vertices[b * MeshDesc.VERTEX_FLOATS + 1] - ay
        val uz = mesh.vertices[b * MeshDesc.VERTEX_FLOATS + 2] - az
        val vx = mesh.vertices[c * MeshDesc.VERTEX_FLOATS] - ax
        val vy = mesh.vertices[c * MeshDesc.VERTEX_FLOATS + 1] - ay
        val vz = mesh.vertices[c * MeshDesc.VERTEX_FLOATS + 2] - az
        val nz = ux * vy - uy * vx
        if (nz <= 0f) return "cara " + (index / 3) + " no mira hacia arriba (nz " + nz + ")"
        index += 3
    }
    return ""
}

/**
 * Avatars must be *recorded*, never substituted with stand-in geometry: the
 * scene has to report them (Phase 8 draws them) while drawing nothing for them.
 */

private fun avatarCheck(): String {
    val renderer = FakeRenderer()
    val diagnostics = RenderDiagnostics()
    val scene = SLScene(renderer, SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed), diagnostics)
    val region = SLRegion(0L, 0, 0, "prueba", "", 20f)
    scene.apply(SLWorldDelta(region, listOf(avatarObject()), emptyList(), emptyList(), 1, false))
    if (renderer.entityCount != 0) return "el avatar se dibujo como geometria (" + renderer.entityCount + ")"
    if (renderer.meshCount != 0) return "el avatar creo una malla (" + renderer.meshCount + ")"
    if (renderer.materialsCreated != 0) return "el avatar creo un material (" + renderer.materialsCreated + ")"
    if (scene.entityCount != 0) return "el avatar ocupa un slot (" + scene.entityCount + ")"
    if (scene.avatarCount != 1) return "avatares " + scene.avatarCount
    val avatar = scene.avatars.first()
    if (avatar.name != AVATAR_NAME) return "nombre " + avatar.name
    if (!avatar.positionKnown) return "la posicion del avatar se perdio"
    if (avatar.transform.translation[0] != 7f || avatar.transform.translation[1] != 8f) {
        return "posicion " + avatar.transform.translation[0] + "," + avatar.transform.translation[1]
    }
    if (!scene.stats().contains("avatares")) return "el resumen no menciona los avatares"
    // The report carries the avatar field by field (spec §9), so "1 avatar
    // detected" is evidence rather than a number taken on trust.
    if (!diagnostics.avatarDump.contains(AVATAR_NAME)) return "el volcado del avatar no lleva el nombre"
    if (!diagnostics.avatarDump.contains("11111111-2222-3333-4444-555555555555")) {
        return "el volcado del avatar no lleva el uuid"
    }
    if (!diagnostics.avatarDump.contains("apariencia")) return "el volcado no dice el estado de apariencia"
    if (!diagnostics.lines().any { it.contains("Avatar") }) return "el informe no muestra el avatar"
    scene.apply(SLWorldDelta(region, emptyList(), emptyList(), listOf(AVATAR_LOCAL_ID), 0, false))
    if (scene.avatarCount != 0) return "el avatar no se retiro"
    return "OK"
}


private const val AVATAR_LOCAL_ID = 1

private const val AVATAR_NAME = "Residente Prueba"


private fun avatarObject(): SLObject {
    val object_ = SceneObject(
        localId = AVATAR_LOCAL_ID,
        uuid = "11111111-2222-3333-4444-555555555555",
        pcode = SceneObject.PCODE_AVATAR,
        position = Vector3(7f, 8f, 9f),
        name = AVATAR_NAME
    )
    object_.positionKnown = true
    object_.revision = 1
    return SLObject.from(object_, PrimGeometryNative.DETAIL_STANDARD)
}


private fun sceneObjectOf(
    localId: Int,
    pathCurve: Int,
    profileCurve: Int,
    scale: Vector3,
    position: Vector3,
    pcode: Int = SceneObject.PCODE_PRIM
): SceneObject {
    val object_ = SceneObject(localId = localId, pcode = pcode, position = position, scale = scale)
    object_.pathCurve = pathCurve
    object_.profileCurve = profileCurve
    object_.positionKnown = true
    object_.paramsKnown = true
    object_.revision = 1
    return object_
}


private fun objectOf(localId: Int, position: Vector3): SceneObject =
    sceneObjectOf(localId, 0x10, 0x01, Vector3(1f, 1f, 1f), position)

/**
 * A unit-triangle mesh. It stands in for the native prim generator so the scene
 * layer can be tested on a machine without the shared library — and it counts
 * every call, which is how the tests know whether meshes and materials are being
 * reused rather than rebuilt.
 */

private fun fakeGeometry(params: IntArray): MeshDesc = MeshDesc(
    vertices = floatArrayOf(
        0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
        1f, 0f, 0f, 0f, 0f, 1f, 1f, 0f,
        0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f
    ),
    indices = intArrayOf(0, 1, 2),
    faceGroups = intArrayOf(0, 0, 3),
    boundsMin = floatArrayOf(0f, 0f, 0f),
    boundsMax = floatArrayOf(1f, 1f, 0f)
)


private fun fakeGeometryFixed(kind: Int): MeshDesc = fakeGeometry(IntArray(0))

/** Records every call the scene makes, so the tests can assert on the diffing. */

private class FakeRenderer : Renderer {

    override val name: String = "fake"
    override val isReady: Boolean = true

    var meshCount = 0
        private set
    var destroyedMeshes = 0
        private set
    var entityCount = 0
        private set
    var destroyedEntities = 0
        private set
    var materialsCreated = 0
        private set
    var materialCount = 0
        private set
    var materialsUpdated = 0
        private set
    var texturesCreated = 0
        private set
    var texturesDestroyed = 0
        private set
    var transformsUpdated = 0
        private set
    var visibilityChanges = 0
        private set

    /**
     * When true, [createTexture] throws, like a backend that ran out of GPU
     * memory. The streamer must survive it: the material keeps its fallback.
     */
    var rejectTextures = false

    /**
     * Fase 2.12: what the scene last asked the backend for, per entity — the
     * distance-culling decision and the shadow-LOD decision — so the A/B work
     * can be checked end to end without touching renderer internals.
     */
    val visibility = HashMap<Int, Boolean>()
    val shadowCasters = HashMap<Int, Boolean>()

    /**
     * When true, [createEntity] refuses every entity (like a backend whose
     * geometry validation rejected them), which is how the scene's rejection
     * handling is tested.
     */
    var rejectEntities = false

    private var nextMesh = 1
    private var nextTexture = 1
    private var nextMaterial = 1
    private var nextEntity = 1

    override fun createMesh(mesh: MeshDesc): MeshHandle {
        meshCount += 1
        return MeshHandle(nextMesh++)
    }

    override fun destroyMesh(handle: MeshHandle) {
        destroyedMeshes += 1
        meshCount -= 1
    }

    override fun createTexture(texture: TextureDesc): TextureHandle {
        if (rejectTextures) {
            throw IllegalStateException("falso: sin memoria de GPU")
        }
        texturesCreated += 1
        return TextureHandle(nextTexture++)
    }

    override fun updateTexture(handle: TextureHandle, texture: TextureDesc) {}

    override fun destroyTexture(handle: TextureHandle) {
        texturesDestroyed += 1
    }

    override fun createMaterial(material: MaterialDesc): MaterialHandle {
        materialsCreated += 1
        materialCount += 1
        return MaterialHandle(nextMaterial++)
    }

    override fun updateMaterial(handle: MaterialHandle, material: MaterialDesc) {
        materialsUpdated += 1
    }

    override fun destroyMaterial(handle: MaterialHandle) {
        materialCount -= 1
    }

    override fun createEntity(entity: EntityDesc): EntityHandle {
        if (rejectEntities) {
            // The real backend records the refusal in the shared diagnostics
            // record; the fake does the same so the scene's reaction is tested
            // against the same evidence the device produces.
            diagnostics.rejectEntity("fake: geometria invalida")
            return EntityHandle.INVALID
        }
        entityCount += 1
        val handle = EntityHandle(nextEntity++)
        entityTransforms[handle.id] = entity.transform
        // The component instance the caller must remember, and the instance the
        // entity actually owns. They start equal; [simulateComponentMove] makes
        // them diverge the way Filament's swap-and-pop compaction does.
        val instance = nextInstance++
        cachedInstance[handle.id] = instance
        actualInstance[handle.id] = instance
        instanceTransforms[instance] = entity.transform
        return handle
    }

    override fun destroyEntity(handle: EntityHandle) {
        destroyedEntities += 1
        entityCount -= 1
        entityTransforms.remove(handle.id)
        cachedInstance.remove(handle.id)?.let { instanceTransforms.remove(it) }
        actualInstance.remove(handle.id)
    }

    override fun updateTransform(handle: EntityHandle, transform: Transform) {
        transformsUpdated += 1
        entityTransforms[handle.id] = transform
        // Written through the instance the entity owns *now*, exactly like the
        // real backend since fase 2.11 (it asks the component manager for the
        // entity's current instance instead of trusting the one it remembered).
        // The read-back ([entityLocalMatrix]) still goes through the remembered
        // instance, which is what keeps a stale instance a real, visible bug.
        val instance = actualInstance[handle.id] ?: cachedInstance[handle.id] ?: return
        instanceTransforms[instance] = transform
    }

    /** The parent the "engine" holds for an entity, or null. */
    fun parentOf(handle: EntityHandle): EntityHandle? = entityTransforms[handle.id]?.parent

    /** The transform an entity was last created or updated with. */
    fun transformOf(handle: EntityHandle): Transform? = entityTransforms[handle.id]

    /**
     * Makes one entity's component move to a new slot, as Filament's component
     * manager does when another component is removed (it swaps the last one into
     * the freed slot). [swappedIn] is what the old slot now holds: another
     * entity's transform. The caller's remembered instance is not updated — that
     * is the bug the fase 2.10 audit has to be able to see.
     */
    fun simulateComponentMove(handle: EntityHandle, swappedIn: Transform) {
        val from = actualInstance[handle.id] ?: return
        val to = nextInstance++
        actualInstance[handle.id] = to
        instanceTransforms[to] = instanceTransforms[from] ?: return
        instanceTransforms[from] = swappedIn
    }

    override fun setVisible(handle: EntityHandle, visible: Boolean) {
        visibilityChanges += 1
        visibility[handle.id] = visible
    }

    override fun setShadowCaster(handle: EntityHandle, castsShadows: Boolean) {
        shadowCasters[handle.id] = castsShadows
    }

    override fun updateMaterials(handle: EntityHandle, materialOfFace: IntArray) {}

    override fun setCamera(camera: CameraDesc) {
        cameraDesc = camera
    }

    // The same read-backs the real backend offers, so the phase 2.9 trace is
    // exercised end to end here: what the scene built, what the "engine" holds,
    // and whether the two camera descriptions agree.

    override fun entityLocalMatrix(handle: EntityHandle): FloatArray? =
        cachedInstance[handle.id]?.let { instanceTransforms[it] }?.toMatrix16()
            ?: entityTransforms[handle.id]?.toMatrix16()

    override fun entityWorldMatrix(handle: EntityHandle): FloatArray? =
        entityLocalMatrix(handle)

    override fun entityExists(handle: EntityHandle): Boolean = entityTransforms.containsKey(handle.id)

    override fun entityProbe(handle: EntityHandle): EntityProbe? {
        val cached = cachedInstance[handle.id] ?: return null
        val actual = actualInstance[handle.id] ?: cached
        val parentTransform = entityTransforms[handle.id]?.parent
        var parentEntity = 0
        var parentHandle = 0
        if (parentTransform != null) {
            parentEntity = parentTransform.id
            parentHandle = parentTransform.id
        }
        return EntityProbe(
            handle = handle.id,
            filamentEntity = handle.id,
            cachedTransformInstance = cached,
            actualTransformInstance = actual,
            cachedRenderableInstance = handle.id,
            actualRenderableInstance = handle.id,
            parentEntity = parentEntity,
            parentEntityFromCached = parentEntity,
            parentHandle = parentHandle,
            childCount = 0,
            mesh = "Mesh#1",
            triangles = 1,
            visible = true,
            cachedLocal = instanceTransforms[cached]?.toMatrix16(),
            actualLocal = instanceTransforms[actual]?.toMatrix16(),
            cachedWorld = instanceTransforms[cached]?.toMatrix16(),
            actualWorld = instanceTransforms[actual]?.toMatrix16()
        )
    }

    override fun cameraSnapshot(): CameraSnapshot? {
        val desc = cameraDesc ?: return null
        val forward = normalise(
            floatArrayOf(
                desc.target[0] - desc.eye[0],
                desc.target[1] - desc.eye[1],
                desc.target[2] - desc.eye[2]
            )
        )
        val right = normalise(
            floatArrayOf(
                forward[1] * desc.up[2] - forward[2] * desc.up[1],
                forward[2] * desc.up[0] - forward[0] * desc.up[2],
                forward[0] * desc.up[1] - forward[1] * desc.up[0]
            )
        )
        val up = floatArrayOf(
            right[1] * forward[2] - right[2] * forward[1],
            right[2] * forward[0] - right[0] * forward[2],
            right[0] * forward[1] - right[1] * forward[0]
        )
        return CameraSnapshot(
            viewMatrix = viewMatrixOf(desc, right, up, forward),
            projectionMatrix = projectionMatrixOf(desc, cameraAspect),
            eye = floatArrayOf(desc.eye[0], desc.eye[1], desc.eye[2]),
            forward = forward,
            left = floatArrayOf(-right[0], -right[1], -right[2]),
            up = up,
            near = desc.near,
            far = desc.far
        )
    }

    override fun setSun(light: LightDesc) {}

    override fun setBackgroundColor(color: FloatArray) {}

    override fun render(frameTimeNanos: Long): Boolean = true

    override fun stats(): RenderStats = RenderStats()

    override fun diagnostics(): RenderDiagnostics = diagnostics

    override fun destroy() {}

    /** The same record the real backend writes, so the scene's copy can be checked. */
    val diagnostics = RenderDiagnostics()

    /** The transform each entity was created/updated with, keyed by handle id. */
    private val entityTransforms = HashMap<Int, Transform>()

    /** The component instance the scene must remember for each handle. */
    private val cachedInstance = HashMap<Int, Int>()

    /** The instance each handle's component actually lives at (see compaction). */
    private val actualInstance = HashMap<Int, Int>()

    /** What each component instance holds. */
    private val instanceTransforms = HashMap<Int, Transform>()

    private var nextInstance = 1

    /** The camera the last [setCamera] handed over, for the read-back. */
    private var cameraDesc: CameraDesc? = null

    /** The aspect ratio used to build the fake projection matrix. */
    var cameraAspect: Float = 16f / 9f
}

/** Column-major world → view matrix for the fake's camera read-back. */

private fun viewMatrixOf(
    camera: CameraDesc,
    right: FloatArray,
    up: FloatArray,
    forward: FloatArray
): FloatArray {
    val eye = camera.eye
    val matrix = FloatArray(16)
    matrix[0] = right[0]
    matrix[1] = up[0]
    matrix[2] = -forward[0]
    matrix[3] = 0f
    matrix[4] = right[1]
    matrix[5] = up[1]
    matrix[6] = -forward[1]
    matrix[7] = 0f
    matrix[8] = right[2]
    matrix[9] = up[2]
    matrix[10] = -forward[2]
    matrix[11] = 0f
    matrix[12] = -(right[0] * eye[0] + right[1] * eye[1] + right[2] * eye[2])
    matrix[13] = -(up[0] * eye[0] + up[1] * eye[1] + up[2] * eye[2])
    matrix[14] = forward[0] * eye[0] + forward[1] * eye[1] + forward[2] * eye[2]
    matrix[15] = 1f
    return matrix
}

/** Column-major perspective projection, the same shape any GL-style camera uses. */

private fun projectionMatrixOf(camera: CameraDesc, aspect: Float): DoubleArray {
    val tangent = kotlin.math.tan(Math.toRadians(camera.verticalFovDegrees.toDouble() * 0.5))
    val near = camera.near.toDouble()
    val far = camera.far.toDouble()
    val matrix = DoubleArray(16)
    matrix[0] = 1.0 / (aspect * tangent)
    matrix[5] = 1.0 / tangent
    matrix[10] = -(far + near) / (far - near)
    matrix[11] = -1.0
    matrix[14] = -(2.0 * far * near) / (far - near)
    return matrix
}

// ---------------------------------------------------------------------------
// Object-update parsing and the incremental shape model (phase 2.8)
//
// The reported failure was `Error procesando ObjectUpdateCompressed:
// length=113; index=-872415225`: an `ExtraParams` length read at the wrong
// offset, used as an index. The checks below drive the decoder with *synthetic
// 113-byte blocks* built to the layout the simulator uses, so the fix is
// verified here as well as on the device.
// ---------------------------------------------------------------------------

/** Every value of `ObjectUpdateCompressed` is little-endian. */

private fun writeLe16(out: java.io.ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value ushr 8) and 0xFF)
}


private fun writeLe32(out: java.io.ByteArrayOutputStream, value: Int) {
    for (i in 0 until 4) {
        out.write((value ushr (i * 8)) and 0xFF)
    }
}


private fun writeLe32(out: java.io.ByteArrayOutputStream, value: Long) {
    for (i in 0 until 4) {
        out.write(((value ushr (i * 8)) and 0xFF).toInt())
    }
}


private fun writeVec(out: java.io.ByteArrayOutputStream, v: Vector3) {
    writeLe32(out, java.lang.Float.floatToIntBits(v.x))
    writeLe32(out, java.lang.Float.floatToIntBits(v.y))
    writeLe32(out, java.lang.Float.floatToIntBits(v.z))
}

/**
 * Builds an `ObjectUpdateCompressed` blob in LL's order. With the default
 * arguments it is exactly **113 bytes** — the length the device reported — since
 * 84 (header) + 1 (empty ExtraParams) + 23 (path/profile) + 4 (TextureEntry
 * length) + 1 (the smallest possible TextureEntry) = 113.
 */

private fun compressedBlob(
    localId: Int,
    pcode: Int = SceneObject.PCODE_PRIM,
    extraParams: ByteArray = byteArrayOf(0),
    pathCurve: Int = 0x10,
    profileCurve: Int = 0x00,
    profileHollow: Int = 0,
    textureEntry: ByteArray = byteArrayOf(0),
    /**
     * The length the block *declares* for the texture section. `-1` (the default)
     * declares the real size; anything else is how the framing cases (category D
     * of the revision) are built: the bytes written are still [textureEntry]'s, so
     * a declared length that differs from the size is exactly the "impossible
     * length" the decoder has to detect and attribute.
     */
    declaredTextureLength: Int = -1,
    position: Vector3 = Vector3(10f, 20f, 30f),
    scale: Vector3 = Vector3(1f, 2f, 3f),
    includeParams: Boolean = true,
    /**
     * The `ParentID` the block carries, and whether the field is present at all.
     * With `carryParent = false` (the default when the id is 0) the block says
     * *nothing* about the parent — which since fase 2.11 leaves the stored parent
     * alone instead of clearing it. `carryParent = true` with `parentId = 0` is
     * the region actually unlinking the object.
     */
    parentId: Int = 0,
    carryParent: Boolean = parentId != 0
): ByteArray {
    val out = java.io.ByteArrayOutputStream(128)
    out.write(ByteArray(16) { (it + 1).toByte() })
    writeLe32(out, localId)
    out.write(pcode)
    out.write(0)
    writeLe32(out, 0) // CRC
    out.write(3) // material
    out.write(0) // click action
    writeVec(out, scale)
    writeVec(out, position)
    writeVec(out, Vector3(0f, 0f, 0f)) // rotation xyz
    writeLe32(out, if (carryParent) 0x20L else 0L) // compressed flags
    out.write(ByteArray(16)) // owner
    if (carryParent) {
        writeLe32(out, parentId)
    }
    out.write(extraParams, 0, extraParams.size)
    if (includeParams) {
        out.write(pathCurve)
        writeLe16(out, 0) // pathBegin
        writeLe16(out, 0) // pathEnd
        out.write(100) // pathScaleX
        out.write(100) // pathScaleY
        out.write(0) // pathShearX
        out.write(0) // pathShearY
        out.write(0) // pathTwist
        out.write(0) // pathTwistBegin
        out.write(0) // pathRadiusOffset
        out.write(0) // pathTaperX
        out.write(0) // pathTaperY
        out.write(0) // pathRevolutions
        out.write(0) // pathSkew
        out.write(profileCurve)
        writeLe16(out, 0) // profileBegin
        writeLe16(out, 0) // profileEnd
        writeLe16(out, profileHollow)
        writeLe32(
            out,
            if (declaredTextureLength >= 0) declaredTextureLength.toLong() else textureEntry.size.toLong()
        )
        out.write(textureEntry, 0, textureEntry.size)
    }
    return out.toByteArray()
}

/** An `ImprovedTerseObjectUpdate` blob: movement only, no shape at all. */

private fun terseBlob(localId: Int, position: Vector3, avatar: Boolean = false): ByteArray {
    val out = java.io.ByteArrayOutputStream(64)
    writeLe32(out, localId)
    out.write(0) // state
    out.write(if (avatar) 1 else 0)
    if (avatar) {
        out.write(ByteArray(16))
    }
    writeVec(out, position)
    out.write(ByteArray(6)) // velocity
    out.write(ByteArray(6)) // acceleration
    writeLe16(out, 32768) // rotation x
    writeLe16(out, 0)
    writeLe16(out, 0)
    writeLe16(out, 0)
    return out.toByteArray()
}


private fun messageOf(name: String, id: Int, blocks: List<ByteArray>): SLMessage {
    val def = MessageDef(name, Frequency.HIGH, id, false, false, emptyList())
    val message = SLMessage(def)
    for (blob in blocks) {
        message.addBlock("ObjectData").set("Data", blob)
    }
    return message
}


private fun compressedMessage(vararg blobs: ByteArray): SLMessage =
    messageOf("ObjectUpdateCompressed", 13, blobs.toList())


private fun terseMessage(vararg blobs: ByteArray): SLMessage =
    messageOf("ImprovedTerseObjectUpdate", 15, blobs.toList())

/**
 * A negative or oversized advance must be refused and counted, never applied:
 * a cursor before the start of the array is what turned a corrupted length into
 * `ArrayIndexOutOfBoundsException: length=113; index=-872415225`.
 */

private fun shapeCensusCheck(): String {
    val model = WorldModel()
    ObjectUpdateDecoder.applyCompressed(
        compressedMessage(compressedBlob(7101), compressedBlob(7102, pathCurve = 0x10, profileCurve = 0x01)),
        model
    )
    ObjectUpdateDecoder.applyTerse(
        terseMessage(terseBlob(7103, Vector3(3f, 3f, 3f)), terseBlob(7201, Vector3(1f, 1f, 1f), avatar = true)),
        model
    )
    val counts = model.shapeCounts()
    if (counts.received != 4) return "recibidos " + counts.received
    if (counts.withCompleteShape != 3) return "con forma completa " + counts.withCompleteShape
    if (counts.withMissingShape != 1) return "sin forma " + counts.withMissingShape
    if (counts.withPartialState != 1) return "estado parcial " + counts.withPartialState
    if (counts.usingShapeFallback != 0) return "usando fallback " + counts.usingShapeFallback
    if (counts.shapeFromCompressed != 2) return "formas de comprimido " + counts.shapeFromCompressed
    if (counts.avatars != 1) return "avatares " + counts.avatars
    return "OK"
}

/**
 * The object list and the renderer must read the *same* shape. The list uses
 * `SceneObject.shapeText`; the scene gets it through `SLObject.from`. This check
 * fails if those ever diverge — which is what let the list say "cylinder" while
 * the renderer said "no shape".
 */

private fun shapeSingleSourceCheck(): String {
    val withoutShape = SceneObject(localId = 7301)
    withoutShape.positionKnown = true
    val withShape = SceneObject(localId = 7302, pathCurve = 0x10, profileCurve = 0x01)
    withShape.positionKnown = true
    withShape.noteShapeReceived(UpdateSource.FULL)
    for (source in listOf(withoutShape, withShape)) {
        val snapshot = SLObject.from(source, PrimGeometryNative.DETAIL_STANDARD)
        if (snapshot.shapeText != source.shapeText) {
            return "#" + source.localId + " la lista dice " + source.shapeText +
                " y el snapshot dice " + snapshot.shapeText
        }
        if (snapshot.hasCompleteShape != source.hasCompleteShape) return "forma completa distinta"
        if (snapshot.shapeSource != source.shapeSource) return "origen distinto"
    }
    val missing = SLObject.from(withoutShape, PrimGeometryNative.DETAIL_STANDARD)
    if (missing.prim != null) return "se construyo un prim sin definicion"
    if (missing.shapeText != "UNKNOWN/MISSING_SHAPE") return "shapeText " + missing.shapeText
    val box = SLObject.from(withShape, PrimGeometryNative.DETAIL_STANDARD)
    if (box.prim == null) return "un prim con definicion no produjo SLPrimitive"
    if (box.prim?.shape != PrimShape.BOX) return "el snapshot clasifico mal: " + box.prim?.shape
    if (box.shapeText != box.prim?.shape?.name) return "shapeText y prim.shape discrepan"
    // Same UUID, two readers, one text.
    if (box.uuid != withShape.uuid) return "uuid distinto"
    return "OK"
}

/** The ramp switch (spec §10) draws only the N nearest objects when set. */

private fun entityBudgetCheck(): String {
    val renderer = FakeRenderer()
    val diagnostics = RenderDiagnostics()
    val scene = SLScene(renderer, SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed), diagnostics)
    val region = SLRegion(0L, 0, 0, "prueba", "", 20f)
    val objects = ArrayList<SLObject>()
    for (index in 0 until 12) {
        // Straight down -Z, which is where cameraDescAt points.
        objects.add(
            SLObject.from(
                objectOf(7400 + index, Vector3(0f, 0f, -(index + 1) * 2f)),
                PrimGeometryNative.DETAIL_STANDARD
            )
        )
    }
    scene.apply(SLWorldDelta(region, objects, emptyList(), emptyList(), 0, false))
    val camera = cameraDescAt(0f, 0f, 0f)
    scene.prune(camera, 1000L)
    if (scene.visibleEntities != 12) return "sin limite se ven " + scene.visibleEntities + " de 12"
    scene.maxPrimEntities = 1
    scene.prune(camera, 1500L)
    if (scene.visibleEntities != 1) return "con limite 1 se ven " + scene.visibleEntities
    if (scene.budgetHidden != 11) return "ocultos por el limite " + scene.budgetHidden
    if (diagnostics.visibleInFrustum != 1) return "frustum " + diagnostics.visibleInFrustum
    scene.maxPrimEntities = 10
    scene.prune(camera, 2000L)
    if (scene.visibleEntities != 10) return "con limite 10 se ven " + scene.visibleEntities
    scene.maxPrimEntities = 0
    scene.prune(camera, 2500L)
    if (scene.visibleEntities != 12) return "al quitar el limite se ven " + scene.visibleEntities
    return "OK"
}


private fun frustumMathCheck(): String {
    val camera = CameraDesc(
        eye = floatArrayOf(0f, 0f, 0f),
        // Looking along +X. In an SL frame (Z up) that makes the camera's right
        // the -Y direction, which is what the test below relies on.
        target = floatArrayOf(1f, 0f, 0f),
        up = floatArrayOf(0f, 0f, 1f),
        verticalFovDegrees = 90f,
        near = 0.1f,
        far = 100f
    )
    val aspect = 16f / 9f
    val frustum = CameraFrustum.from(camera, aspect)

    if (kotlin.math.abs(frustum.forward[0] - 1f) > 1e-4f) return "forward " + frustum.forward.joinToString(",")
    if (kotlin.math.abs(frustum.right[1] + 1f) > 1e-4f) return "right " + frustum.right.joinToString(",")
    if (kotlin.math.abs(frustum.up[2] - 1f) > 1e-4f) return "up " + frustum.up.joinToString(",")

    val ahead = frustum.sample(floatArrayOf(10f, 0f, 0f))
    if (!ahead.inside || ahead.reason != FrustumReason.INSIDE) return "centro delante: " + ahead.text
    if (kotlin.math.abs(ahead.depth - 10f) > 1e-3f) return "depth " + ahead.depth
    if (kotlin.math.abs(ahead.ndcX) > 1e-4f || kotlin.math.abs(ahead.ndcY) > 1e-4f) return "ndc " + ahead

    val behind = frustum.sample(floatArrayOf(-10f, 0f, 0f))
    if (behind.inside || behind.reason != FrustumReason.BEHIND) return "detras: " + behind.text

    val close = frustum.sample(floatArrayOf(0.05f, 0f, 0f))
    if (close.inside || close.reason != FrustumReason.TOO_CLOSE) return "cerca: " + close.text

    val far = frustum.sample(floatArrayOf(200f, 0f, 0f))
    if (far.inside || far.reason != FrustumReason.TOO_FAR) return "lejos: " + far.text

    val right = frustum.sample(floatArrayOf(10f, -100f, 0f))
    if (right.inside || right.reason != FrustumReason.OFF_RIGHT) return "lado: " + right.text

    val left = frustum.sample(floatArrayOf(10f, 100f, 0f))
    if (left.inside || left.reason != FrustumReason.OFF_LEFT) return "otro lado: " + left.text

    val top = frustum.sample(floatArrayOf(10f, 0f, 100f))
    if (top.inside || top.reason != FrustumReason.OFF_TOP) return "arriba: " + top.text

    val bottom = frustum.sample(floatArrayOf(10f, 0f, -100f))
    if (bottom.inside || bottom.reason != FrustumReason.OFF_BOTTOM) return "abajo: " + bottom.text

    // The screen position of a point at the top of the picture must be near 0%.
    val high = frustum.sample(floatArrayOf(10f, 0f, 9.99f))
    if (!high.inside) return "casi arriba deberia entrar: " + high.text
    if (high.screenY > 0.1f) return "pantalla-y " + high.screenY
    if (kotlin.math.abs(high.screenX - 0.5f) > 1e-3f) return "pantalla-x " + high.screenX

    // A big sphere whose centre is off the side still intersects the frustum.
    if (!frustum.intersectsSphere(floatArrayOf(10f, 30f, 0f), 25f)) return "esfera grande deberia cortar el frustum"
    if (frustum.intersectsSphere(floatArrayOf(10f, 300f, 0f), 5f)) return "esfera lejana no deberia cortar el frustum"
    return "OK"
}

/** The matrix-based test must agree with the basis-based one, point by point. */

private fun matrixFrustumCheck(): String {
    val camera = CameraDesc(
        eye = floatArrayOf(0f, 0f, 0f),
        target = floatArrayOf(1f, 0f, 0f),
        up = floatArrayOf(0f, 0f, 1f),
        verticalFovDegrees = 60f,
        near = 0.1f,
        far = 100f
    )
    val aspect = 16f / 9f
    val basis = CameraFrustum.from(camera, aspect)
    val matrix = MatrixFrustum(
        viewMatrixOf(camera, basis.right, basis.up, basis.forward),
        projectionMatrixOf(camera, aspect),
        camera.near,
        camera.far
    )
    val points = listOf(
        floatArrayOf(10f, 0f, 0f),
        floatArrayOf(10f, 5f, 2f),
        floatArrayOf(10f, -30f, 0f),
        floatArrayOf(10f, 0f, 9f),
        floatArrayOf(50f, 20f, -10f)
    )
    for (point in points) {
        val a = basis.sample(point)
        val b = matrix.sample(point)
        if (a.inside != b.inside) {
            return "desacuerdo en " + point.joinToString(",") + ": " + a.text + " vs " + b.text
        }
        if (kotlin.math.abs(a.depth - b.depth) > 0.01f) {
            return "depth distinto en " + point.joinToString(",") + ": " + a.depth + " vs " + b.depth
        }
        if (kotlin.math.abs(a.ndcX - b.ndcX) > 0.01f || kotlin.math.abs(a.ndcY - b.ndcY) > 0.01f) {
            return "ndc distinto en " + point.joinToString(",") + ": " + a.ndcX + "," + a.ndcY +
                " vs " + b.ndcX + "," + b.ndcY
        }
    }
    val behind = matrix.sample(floatArrayOf(-10f, 0f, 0f))
    if (behind.inside || behind.reason != FrustumReason.BEHIND) return "detras: " + behind.text
    return "OK"
}

/**
 * The whole trace for the test scene: 20 entities, a camera aimed at them, and
 * every line of the audit present and consistent.
 */

private fun cameraTraceCheck(): String {
    val renderer = FakeRenderer()
    val diagnostics = RenderDiagnostics()
    val scene = SLScene(renderer, SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed), diagnostics)
    scene.apply(SLTestScene.buildDelta())
    val aspect = 16f / 9f
    renderer.cameraAspect = aspect
    scene.cameraAspect = aspect
    val camera = CameraDesc(
        eye = floatArrayOf(0f, -25f, 6f),
        target = floatArrayOf(0f, 1.8f, 1.2f),
        up = floatArrayOf(0f, 0f, 1f),
        verticalFovDegrees = 60f,
        near = 0.05f,
        far = 1024f
    )
    renderer.setCamera(camera)
    scene.prune(camera, 1000L)
    val audit = scene.cameraAudit(renderer, 5)
    if (!audit.contains("--- TRAZA SL -> entidad -> camara -> frustum")) return "sin cabecera"
    if (!audit.contains("entidades en la Scene: 20")) return "no ve 20 entidades"
    if (!audit.contains("TransformManager local:")) return "sin lectura del TransformManager"
    if (!audit.contains("aplicadaVsEscena=IGUAL")) return "la matriz del motor no coincide con la de la escena"
    if (!audit.contains("acuerdo=SI")) return "las dos rutas de frustum no coinciden"
    //    La fila se imprime como `frustum(base, DIAGNOSTICO)=...`: el sufijo se
    //    anadio cuando la traza paso a aclarar que es diagnostico y no culling.
    //    La asercion se quedo en el formato viejo y llevaba tiempo fallando por
    //    eso, no por la matematica del frustum.
    if (!audit.contains("frustum(base, DIAGNOSTICO)=DENTRO")) return "nada dentro del frustum mirando a los prims"
    if (!audit.contains("DESACUERDO escena<->motor: eye=0.0000")) {
        return "la camara leida del motor no coincide con la de la escena"
    }
    val rows = audit.split('\n').count { it.startsWith("#") }
    if (rows != 5) return "filas trazadas " + rows + " en vez de 5"
    // Every traced object must be the same object the scene holds an entity for.
    if (!audit.contains("entidad=Entity#")) return "sin identificador de entidad"
    return "OK"
}

/** The probe: exactly one cube, always at the centre of the picture, removable. */

private fun cameraProbeCheck(): String {
    val renderer = FakeRenderer()
    val probe = SLCameraProbe(renderer)
    if (!probe.valid) return "el renderer rechazo el probe"
    if (renderer.meshCount != 1) return "mallas " + renderer.meshCount
    if (renderer.materialCount != 1) return "materiales " + renderer.materialCount
    if (renderer.entityCount != 1) return "entidades " + renderer.entityCount
    val camera = CameraDesc(
        eye = floatArrayOf(0f, 0f, 0f),
        target = floatArrayOf(1f, 0f, 0f),
        up = floatArrayOf(0f, 0f, 1f),
        verticalFovDegrees = 60f
    )
    probe.place(camera)
    if (kotlin.math.abs(probe.position[0] - SLCameraProbe.DISTANCE) > 1e-4f) {
        return "posicion " + probe.position.joinToString(",")
    }
    if (kotlin.math.abs(probe.position[1]) > 1e-4f || kotlin.math.abs(probe.position[2]) > 1e-4f) {
        return "el probe no esta sobre el eje de la camara"
    }
    val text = probe.describe(camera, 16f / 9f)
    if (!text.contains("CAMERA_TEST")) return "sin cabecera"
    if (!text.contains("NO es geometria SL")) return "no dice que no es geometria SL"
    if (!text.contains("frustum(calculado)=DENTRO")) return "el probe no esta dentro del frustum"
    if (!text.contains("centro-x=50%") || !text.contains("centro-y=50%")) return "no esta en el centro:\n" + text
    if (!text.contains("visibleAJO=PENDIENTE")) return "no pide confirmacion visual"
    probe.destroy()
    if (renderer.meshCount != 0 || renderer.materialCount != 0 || renderer.entityCount != 0) {
        return "el probe no libero sus recursos"
    }
    return "OK"
}

/**
 * The reversible camera lock: the orbit is untouched, the override wins while on,
 * and the REAL_PRIM_TEST block reports the object as out of the picture before
 * the camera moved and inside it afterwards.
 */

private fun primLockCheck(): String {
    val camera = SLCamera()
    camera.setFocus(128f, 128f, 25f)
    camera.yaw = 0f
    camera.pitch = 0.2f
    camera.distance = 8f
    val free = camera.desc()
    camera.lockTo(floatArrayOf(10f, 20f, 30f), floatArrayOf(11f, 20f, 30f))
    if (!camera.isLocked) return "no se bloqueo"
    val locked = camera.desc()
    if (locked.eye[0] != 10f || locked.eye[2] != 30f) return "eye no es el bloqueado"
    if (locked.target[0] != 11f || locked.target[2] != 30f) return "target no es el bloqueado"
    if (camera.framing != SLCamera.FRAMING_PRIM) return "framing " + camera.framing
    camera.unlock()
    if (camera.isLocked) return "sigue bloqueada"
    val restored = camera.desc()
    if (kotlin.math.abs(restored.eye[0] - free.eye[0]) > 1e-4f) return "no se restauro el eye"
    if (kotlin.math.abs(restored.eye[2] - free.eye[2]) > 1e-4f) return "no se restauro la altura"
    if (camera.framing != SLCamera.FRAMING_AGENT) return "framing restaurado " + camera.framing

    // Now the real thing: one prim, behind the camera, and the camera locked
    // onto it. Nothing about the object changes.
    val renderer = FakeRenderer()
    val scene = SLScene(renderer, SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed), RenderDiagnostics())
    val region = SLRegion(0L, 0, 0, "prueba", "", 20f)
    val prim = SLObject.from(
        objectOf(500, Vector3(0f, 0f, 0f)),
        PrimGeometryNative.DETAIL_STANDARD
    )
    scene.apply(SLWorldDelta(region, listOf(prim), emptyList(), emptyList(), 0, false))
    val aspect = 16f / 9f
    scene.cameraAspect = aspect
    val before = CameraDesc(
        eye = floatArrayOf(0f, 20f, 2f),
        target = floatArrayOf(0f, 40f, 2f),
        up = floatArrayOf(0f, 0f, 1f),
        verticalFovDegrees = 60f,
        near = 0.05f,
        far = 1024f
    )
    renderer.cameraAspect = aspect
    renderer.setCamera(before)
    scene.prune(before, 1000L)
    val localId = scene.nearestPrimId(before.eye, 50f)
    if (localId != 500) return "prim elegido " + localId
    if (scene.entityOf(localId) == null) return "el prim no tiene entidad"

    // The camera goes to `prim + offset`, looking at the prim (the object stays).
    val offsetDirection = normalise(
        floatArrayOf(
            before.eye[0] - 0f,
            before.eye[1] - 0f,
            before.eye[2] - 0f
        )
    )
    val eye = floatArrayOf(
        offsetDirection[0] * 6f,
        offsetDirection[1] * 6f,
        offsetDirection[2] * 6f + 1.5f
    )
    val target = floatArrayOf(0f, 0f, 0f)
    val after = CameraDesc(
        eye = eye,
        target = target,
        up = floatArrayOf(0f, 0f, 1f),
        verticalFovDegrees = 60f,
        near = 0.05f,
        far = 1024f
    )
    val report = scene.realPrimTestReport(localId, before, after, renderer, eye, target)
    if (!report.contains("REAL_PRIM_TEST")) return "sin cabecera"
    if (!report.contains("visible ANTES del ajuste de camara=FUERA")) return "antes deberia estar fuera:\n" + report
    if (!report.contains("visible DESPUES del ajuste=DENTRO")) return "despues deberia estar dentro:\n" + report
    if (!report.contains("posicion transformada")) return "sin posicion transformada"
    if (!report.contains("el objeto NO se mueve")) return "no dice que el objeto no se mueve"
    return "OK"
}

// ---------------------------------------------------------------------------
// Fase 2.12: escalabilidad del render (culling por distancia y LOD de sombras)
//
// Los dos interruptores tienen que demostrar tres cosas aqui:
//
//  1. Culling por distancia — un objeto lejano sale del conjunto que se dibuja,
//     pero NO se destruye ni se descarga: la entidad, la malla y el material
//     siguen existiendo. Apagarlo lo devuelve todo.
//  2. LOD de sombras — un prim lejano deja de proyectar sombra y, al apagarlo,
//     vuelve a proyectarla. La geometria y los materiales no cambian nunca (no
//     hay una segunda malla ni un material nuevo).
//  3. Nada de esto toca la relacion parent/child, que se verifica aparte.
//
// El culling de frustum no se comprueba aqui porque no lo hace esta capa: es el
// test del propio motor (`View`), y el informe publica su medicion
// (`renderablesInScene - visibleRenderables`); el arnes no puede inventarse un
// view de Filament, y fingirlo volveria verde una afirmacion sin evidencia.
// ---------------------------------------------------------------------------

/**
 * Fase 2.12: the distance-culling switch and the far-prim shadow LOD, driven
 * through the scene's public API (never through renderer internals).
 */

private fun scalabilityCheck(): String {
    val diagnostics = RenderDiagnostics()
    val renderer = FakeRenderer()
    val scene = SLScene(renderer, SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed), diagnostics)
    scene.apply(SLTestScene.buildDelta())
    if (scene.entityCount != 20) return "entidades " + scene.entityCount

    // The default 300 m draw distance keeps the whole test scene (it is ~31 m
    // across), so the baseline is "nothing culled".
    scene.prune(cameraDescAt(0f, 0f, 0f))
    if (scene.visibleEntities != 20) return "visibles con la distancia por defecto " + scene.visibleEntities
    if (scene.distanceCulled != 0) return "culled con la distancia por defecto " + scene.distanceCulled

    // A 2 m draw distance: only the two objects at x = +-1.7 on the first row
    // are inside it (the second row is 3.6 m away in y).
    scene.maxDrawDistance = 2f
    scene.prune(cameraDescAt(0f, 0f, 0f))
    if (scene.visibleEntities != 2) return "visibles a 2 m " + scene.visibleEntities
    if (scene.distanceCulled != 18) return "culled por distancia " + scene.distanceCulled
    val hidden = renderer.visibility.values.count { !it }
    if (hidden != 18) return "el renderer no recibio los 18 ocultos (" + hidden + ")"
    // Distance culling removes objects from the draw set — it must not destroy,
    // unload or rebuild anything.
    if (renderer.entityCount != 20) return "la distancia destruyo entidades (" + renderer.entityCount + ")"
    if (renderer.destroyedEntities != 0) return "la distancia destruyo " + renderer.destroyedEntities
    if (renderer.meshCount != 10) return "la distancia toco las mallas (" + renderer.meshCount + ")"
    if (renderer.materialsCreated != 10) return "la distancia creo materiales (" + renderer.materialsCreated + ")"
    if (diagnostics.culledByDistance != 18) return "el informe no cuenta el culling (" + diagnostics.culledByDistance + ")"

    // Switch it off: every object comes back and the backend is told so. (The
    // two that were never hidden have no entry, which is why the check is on the
    // hidden ones.)
    scene.distanceCullingEnabled = false
    scene.prune(cameraDescAt(0f, 0f, 0f))
    if (scene.visibleEntities != 20) return "al apagar la distancia volvieron " + scene.visibleEntities
    if (scene.distanceCulled != 0) return "culled tras apagar la distancia " + scene.distanceCulled
    if (renderer.visibility.values.any { !it }) return "quedaron objetos ocultos tras apagar"
    if (diagnostics.distanceCullingEnabled) return "el informe dice que la distancia sigue ON"

    // Shadow LOD: at 2 m only the two nearest prims keep casting shadows.
    scene.shadowLodDistance = 2f
    scene.prune(cameraDescAt(0f, 0f, 0f))
    if (scene.shadowLodReduced != 18) return "el LOD redujo " + scene.shadowLodReduced
    val nonCasters = renderer.shadowCasters.values.count { !it }
    if (nonCasters != 18) return "el renderer no recibio el LOD (" + nonCasters + ")"
    if (diagnostics.shadowLodDistance != 2f) return "el informe no publica el LOD"
    // The LOD reduced cost, not content.
    if (renderer.entityCount != 20) return "el LOD destruyo entidades"
    if (renderer.materialsCreated != 10) return "el LOD creo materiales de mas (" + renderer.materialsCreated + ")"

    // And switching it off restores every caster.
    scene.shadowLodDistance = 0f
    scene.prune(cameraDescAt(0f, 0f, 0f))
    if (scene.shadowLodReduced != 0) return "el LOD sigue reduciendo tras apagarlo"
    if (renderer.shadowCasters.values.any { !it }) return "no se restauro quien proyecta sombra"
    return "OK"
}

// ---------------------------------------------------------------------------
// Fase 2.12b: la CORRECCION del diagnostico de frustum
//
// El fallo que aparecio en el dispositivo: para un HIJO de linkset al que la
// camara se movia para mirarlo directamente, el diagnostico decia
// "FUERA (DETRAS_DE_LA_CAMARA)" mientras la lectura por matrices decia "DENTRO".
//
// La causa era que la prueba usaba la `translation` LOCAL del hijo (relativa al
// padre) en un sitio y la posicion de REGION en otro: dos puntos distintos, asi
// que los dos veredictos no podian coincidir. Aqui se reproduce esa geometria.
// ---------------------------------------------------------------------------

/**
 * Fase 2.12b: el diagnostico de frustum de un hijo de linkset.
 *
 * Se construye un hijo cuya posicion LOCAL (0, 0, -8) y de REGION
 * (100, 0, -8) son distintas, se apunta la camara a su posicion de region desde
 * -x (que es exactamente la geometria que producia un depth negativo al probar
 * el punto local) y se exige que:
 *
 *  1. el hijo conserve su posicion local (nada se convierte ni se mueve);
 *  2. la prueba de frustum diga DENTRO mirando al hijo;
 *  3. `base` y `matrices del motor` prueben el MISMO punto y coincidan;
 *  4. el bloque compacto `PRUEBA DE FRUSTUM` lleve la posicion de region, la de
 *     mundo del motor, view-space, clip-space y NDC;
 *  5. quede claro en el texto que el culling real es el del motor.
 */

private fun childFrustumCheck(): String {
    val renderer = FakeRenderer()
    val diagnostics = RenderDiagnostics()
    val scene = SLScene(renderer, SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed), diagnostics)
    val region = SLRegion(0L, 0, 0, "prueba", "", 20f)
    val root = objectOf(500, Vector3(100f, 0f, 0f))
    val child = objectOf(501, Vector3(0f, 0f, -8f))
    child.parentId = 500
    scene.apply(
        SLWorldDelta(
            region,
            arrayListOf(
                SLObject.from(child, PrimGeometryNative.DETAIL_STANDARD),
                SLObject.from(root, PrimGeometryNative.DETAIL_STANDARD)
            ),
            emptyList(),
            emptyList(),
            0,
            false
        )
    )
    val aspect = 16f / 9f
    scene.cameraAspect = aspect
    renderer.cameraAspect = aspect

    val childEntity = scene.entityOf(501) ?: return "sin entidad para el hijo"
    val childTransform = renderer.transformOf(childEntity) ?: return "sin transformacion del hijo"
    if (childTransform.translation[0] != 0f || childTransform.translation[2] != -8f) {
        return "el hijo no conserva su posicion local: " + childTransform.translation.joinToString()
    }
    val placement = scene.positionOf(501) ?: return "sin posicion de region para el hijo"
    if (kotlin.math.abs(placement[0] - 100f) > 0.01f || kotlin.math.abs(placement[2] + 8f) > 0.01f) {
        return "la posicion de region del hijo no compone al padre: " + placement.joinToString()
    }
    // Local y region tienen que ser distintos, o esta prueba no probaria nada.
    if (kotlin.math.abs(placement[0] - childTransform.translation[0]) < 1f) {
        return "local y region coinciden: la prueba no reproduciria el fallo"
    }

    val camera = CameraDesc(
        eye = floatArrayOf(92f, 0f, -8f),
        target = floatArrayOf(100f, 0f, -8f),
        up = floatArrayOf(0f, 0f, 1f),
        verticalFovDegrees = 60f,
        near = 0.05f,
        far = 1024f
    )
    renderer.setCamera(camera)
    scene.prune(camera, 1000L)

    // El punto de region esta 8 m por delante; el punto LOCAL (0, 0, -8) esta
    // 92 m DETRAS de la camara. Si el diagnostico volviera a probar el local,
    // esta comprobacion diria DETRAS.
    val sample = scene.samplePrim(501, camera, aspect) ?: return "sin muestra de frustum"
    if (!sample.inside) {
        return "mirando al hijo deberia estar DENTRO y dice: " + sample.text + " depth=" + sample.depth
    }

    val before = CameraDesc(
        eye = floatArrayOf(0f, -25f, 6f),
        target = floatArrayOf(0f, 0f, 0f),
        up = floatArrayOf(0f, 0f, 1f),
        verticalFovDegrees = 60f
    )
    val report = scene.realPrimTestReport(501, before, camera, renderer, camera.eye, camera.target)
    if (!report.contains("visible DESPUES del ajuste=DENTRO")) {
        return "con la camara mirando al hijo deberia ser DENTRO:\n" + report
    }
    if (!report.contains("region (parentWorld x local) = 100.00, 0.00, -8.00")) {
        return "el informe compacto no prueba el punto de region:\n" + report
    }
    if (!report.contains("--- PRUEBA DE FRUSTUM #501")) return "sin bloque compacto:\n" + report
    if (!report.contains("view-space  (motor)")) return "sin view-space:\n" + report
    if (!report.contains("clip-space  (motor)")) return "sin clip-space:\n" + report
    if (!report.contains("NDC         (motor)")) return "sin NDC:\n" + report
    if (!report.contains("visible aux (matrices del motor)  = DENTRO")) {
        return "las matrices del motor deberian decir DENTRO:\n" + report
    }
    if (!report.contains("culling real del motor")) return "no dice cual es el culling real:\n" + report

    // La auditoria usa un solo punto (la region) en las dos rutas.
    val audit = scene.cameraAudit(renderer, 5)
    if (!audit.contains("posicion de REGION (parentWorld x local)=100.00, 0.00, -8.00")) {
        return "la auditoria no marca el punto de region:\n" + audit
    }
    if (audit.contains("acuerdo=NO")) return "base y matrices siguen discrepando:\n" + audit
    if (!audit.contains("frustum(base, DIAGNOSTICO)=DENTRO")) {
        return "la auditoria no ve al hijo dentro:\n" + audit
    }
    if (audit.contains("depth=-")) return "queda un depth negativo en la auditoria:\n" + audit
    return "OK"
}

// ---------------------------------------------------------------------------
// Fase 2.10: parent/child, y la cadena completa de un objeto concreto
//
// Dos problemas distintos, y cada uno tiene que poder demostrarse aqui:
//
//  1. Parent/Child — `SLObject.parentLocalId` llega del protocolo, pero el
//     `Transform` que la escena construye lo deja fuera, asi que el renderer no
//     recibe ninguna relacion. El informe tiene que decir *donde* se pierde.
//  2. SL → mundo de un objeto concreto — la matriz que la escena entrega y la
//     que el motor guarda pueden dejar de coincidir si la instancia de
//     componente que el renderer apunta ya no es la de esa entidad (el gestor
//     de componentes de Filament compacta con swap-and-pop al destruir). El
//     diagnostico tiene que distinguir "los datos estan mal" de "estoy leyendo
//     la ficha de otra entidad".
// ---------------------------------------------------------------------------

/**
 * El salto del protocolo (fase 2.10, corregido en 2.11): un comprimido CON el
 * bloque ParentID lo entrega, un terse no lo toca, y un comprimido SIN el bloque
 * NO dice nada — asi que el parent que ya estaba se conserva. Un comprimido que
 * SI trae el campo con valor 0 es la region desvinculando el objeto, y eso se
 * aplica. Los tres casos quedan contados y en el ledger.
 */

private fun parentChainCheck(): String {
    val renderer = FakeRenderer()
    val diagnostics = RenderDiagnostics()
    val scene = SLScene(renderer, SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed), diagnostics)
    val region = SLRegion(0L, 0, 0, "prueba", "", 20f)
    val root = objectOf(900, Vector3(100f, 20f, 30f))
    val child = objectOf(901, Vector3(0.05f, 0.21f, -0.9f))
    child.parentId = 900
    val grandChild = objectOf(902, Vector3(1f, 0f, 0f))
    grandChild.parentId = 901
    scene.apply(
        SLWorldDelta(
            region,
            arrayListOf(
                // Nada de orden padre-primero: el nieto llega antes que su padre y
                // que su abuelo.
                SLObject.from(grandChild, PrimGeometryNative.DETAIL_STANDARD),
                SLObject.from(child, PrimGeometryNative.DETAIL_STANDARD),
                SLObject.from(root, PrimGeometryNative.DETAIL_STANDARD)
            ),
            emptyList(),
            emptyList(),
            0,
            false
        )
    )
    scene.prune(cameraDescAt(0f, 0f, 0f), 1000L)

    if (scene.childrenWithParent != 2) return "hijos resueltos " + scene.childrenWithParent
    if (scene.childrenWithoutParent != 1) return "objetos sin parent " + scene.childrenWithoutParent
    if (scene.parentsResolved != 2) return "parents resueltos " + scene.parentsResolved
    if (scene.parentsPending != 0) return "parents pendientes " + scene.parentsPending
    if (scene.parentsUnresolved != 0) return "parents sin resolver " + scene.parentsUnresolved

    val rootEntity = scene.entityOf(900) ?: return "sin entidad para la raiz"
    val childEntity = scene.entityOf(901) ?: return "sin entidad para el hijo"
    val grandChildEntity = scene.entityOf(902) ?: return "sin entidad para el nieto"
    if (renderer.parentOf(childEntity) != rootEntity) {
        return "el hijo no apunta a la raiz: " + renderer.parentOf(childEntity)
    }
    if (renderer.parentOf(grandChildEntity) != childEntity) {
        return "el nieto no apunta al hijo: " + renderer.parentOf(grandChildEntity)
    }
    // El hijo conserva su posicion LOCAL: no se ha convertido ni movido.
    val childTransform = renderer.transformOf(childEntity) ?: return "sin transformacion del hijo"
    if (childTransform.translation[0] != 0.05f || childTransform.translation[2] != -0.9f) {
        return "el hijo se movio: " + childTransform.translation.joinToString()
    }
    // Su posicion DE REGION si compone al padre (la usa el test de distancia).
    val placement = scene.positionOf(901) ?: return "sin posicion de region para el hijo"
    if (kotlin.math.abs(placement[0] - 100.05f) > 0.001f ||
        kotlin.math.abs(placement[1] - 20.21f) > 0.001f ||
        kotlin.math.abs(placement[2] - 29.1f) > 0.001f
    ) {
        return "la posicion de region del hijo no compone al padre: " + placement.joinToString()
    }

    val report = scene.parentChainReport(renderer, 8)
    if (!report.contains("con parentLocalId != 0: 2")) return "no cuenta los hijos:\n" + report
    if (!report.contains("sin parent: 1")) return "no cuenta el objeto sin parent:\n" + report
    if (!report.contains("el Transform SI lleva parent: Entity#")) {
        return "el Transform no lleva el parent:\n" + report
    }
    if (!report.contains("(relacion parent/child viva)")) {
        return "el motor no reporta la relacion viva:\n" + report
    }
    if (!report.contains("de 2 hijos, 0 se entregaron con parent=null")) {
        return "el resumen del renderer no cuadra:\n" + report
    }
    if (!report.contains("childrenWithParent=2")) return "sin contadores de fase 2.11:\n" + report

    val links = scene.parentLinkReport(renderer, 10)
    if (!links.contains("Child LocalID=901  Parent LocalID=900")) return "sin fila del hijo:\n" + links
    if (!links.contains("Filament parentEntity=" + rootEntity.id)) {
        return "el motor no tiene el parentEntity de la raiz:\n" + links
    }
    val trace = "parentLocalId=900 -> Transform.parent=EntityHandle(" + rootEntity.id +
        ") -> Filament parentEntity=" + rootEntity.id + "  OK"
    if (!links.contains(trace)) return "la traza completa del hijo no cuadra:\n" + links
    // El objeto sin parent no debe aparecer como hijo auditado.
    val childRows = report.split('\n').count { it.startsWith("#901") || it.startsWith("#902") }
    if (childRows != 2) return "filas de hijos " + childRows
    return "OK"
}

/**
 * La resolucion en dos pasos y la destruccion (fase 2.11): un hijo que llega
 * antes que su padre queda pendiente SIN moverse, se resuelve cuando el padre
 * aparece, y si el padre desaparece despues el hijo vuelve a quedar pendiente con
 * un `Transform` sin handle — nunca con un handle muerto.
 */

private fun parentResolveCheck(): String {
    val renderer = FakeRenderer()
    val scene = SLScene(renderer, SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed), RenderDiagnostics())
    val region = SLRegion(0L, 0, 0, "prueba", "", 20f)
    val child = objectOf(700, Vector3(2f, 0f, 0f))
    child.parentId = 500

    // 1: solo el hijo: su padre no existe todavia.
    scene.apply(
        SLWorldDelta(
            region,
            arrayListOf(SLObject.from(child, PrimGeometryNative.DETAIL_STANDARD)),
            emptyList(),
            emptyList(),
            0,
            false
        )
    )
    if (scene.childrenWithParent != 0) return "el hijo se resolvio sin padre"
    if (scene.parentsPending != 1) return "no queda pendiente: " + scene.parentsPending
    if (scene.parentsUnresolved != 0) return "no deberia estar sin resolver: " + scene.parentsUnresolved
    val childEntity = scene.entityOf(700) ?: return "sin entidad para el hijo"
    if (renderer.parentOf(childEntity) != null) return "el hijo tiene un parent inventado"
    val before = renderer.transformOf(childEntity) ?: return "sin transformacion del hijo"
    if (before.translation[0] != 2f) return "el hijo se movio al quedar pendiente"

    // 2: llega el padre: el hijo se resuelve y nadie lo mueve.
    val parent = objectOf(500, Vector3(50f, 0f, 0f))
    scene.apply(
        SLWorldDelta(
            region,
            arrayListOf(SLObject.from(parent, PrimGeometryNative.DETAIL_STANDARD)),
            emptyList(),
            emptyList(),
            0,
            false
        )
    )
    if (scene.childrenWithParent != 1) return "el hijo no se resolvio al llegar el padre"
    if (scene.parentsPending != 0) return "sigue pendiente: " + scene.parentsPending
    if (scene.parentsResolved != 1) return "parentsResueltos " + scene.parentsResolved
    val parentEntity = scene.entityOf(500) ?: return "sin entidad para el padre"
    if (renderer.parentOf(childEntity) != parentEntity) return "el hijo no apunta al padre"
    val after = renderer.transformOf(childEntity) ?: return "sin transformacion del hijo"
    if (after.translation[0] != 2f) return "el hijo se movio al resolver el parent"

    // 3: el padre desaparece: el hijo vuelve a pendiente, sin handle muerto.
    scene.apply(SLWorldDelta(region, emptyList(), emptyList(), arrayListOf(500), 0, false))
    if (scene.parentsPending != 0) return "se confunde pendiente con perdido: " + scene.parentsPending
    if (scene.parentsUnresolved != 1) return "no se cuenta el parent desaparecido: " + scene.parentsUnresolved
    if (scene.childrenWithParent != 0) return "el hijo sigue con relacion"
    if (renderer.parentOf(childEntity) != null) return "el hijo conserva un handle muerto"
    if (!renderer.entityExists(childEntity)) return "el hijo perdio su entidad"
    return "OK"
}

/**
 * El objeto en foco: la matriz que la escena entrega, la instancia del motor que
 * la guarda, y la diferencia entre "los datos estan mal" y "la instancia
 * apuntada ya no es la de esta entidad".
 */

private fun focusReportCheck(): String {
    val renderer = FakeRenderer()
    val diagnostics = RenderDiagnostics()
    val scene = SLScene(renderer, SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed), diagnostics)
    val region = SLRegion(0L, 0, 0, "prueba", "", 20f)
    val healthy = objectOf(810, Vector3(0f, 0f, -5f))
    val stale = objectOf(830138250, Vector3(134.91f, 87.15f, 38.05f))
    scene.apply(
        SLWorldDelta(
            region,
            arrayListOf(
                SLObject.from(healthy, PrimGeometryNative.DETAIL_STANDARD),
                SLObject.from(stale, PrimGeometryNative.DETAIL_STANDARD)
            ),
            emptyList(),
            emptyList(),
            0,
            false
        )
    )
    scene.focusLocalId = 830138250
    scene.prune(cameraDescAt(0f, 0f, 0f), 1000L)

    // 1: sin tocar nada, la matriz de la escena y la del motor coinciden.
    val clean = scene.focusReport(renderer, 830138250)
    if (!clean.contains("B) objeto en foco: #830138250")) return "sin cabecera del foco:\n" + clean
    if (!clean.contains("MATRIZ_ESCENA")) return "sin la matriz de la escena"
    if (!clean.contains("C) objeto sano: #810")) return "no encuentra un objeto sano con el que comparar:\n" + clean
    if (!clean.contains("traslacion=134.91, 87.15, 38.05")) {
        return "la matriz de la escena no lleva la posicion SL:\n" + clean
    }
    if (!clean.contains("COINCIDE=SI")) {
        return "sin tocar nada, la instancia cacheada deberia coincidir:\n" + clean
    }

    // 2: el gestor de componentes mueve la ficha de esta entidad a otro indice
    //    (lo que hace Filament al destruir otra), y el hueco viejo queda con la
    //    transformacion de otra entidad: una posicion pequena.
    val entity = scene.entityOf(830138250) ?: return "sin entidad para el objeto en foco"
    renderer.simulateComponentMove(
        entity,
        Transform(
            translation = floatArrayOf(0.05f, 0.21f, -0.90f),
            rotation = floatArrayOf(0f, 0f, 0f, 1f),
            scale = floatArrayOf(1f, 1f, 1f)
        )
    )
    val staleReport = scene.focusReport(renderer, 830138250)
    if (!staleReport.contains("COINCIDE=NO")) {
        return "no detecta que la instancia cacheada ya no es la de la entidad:\n" + staleReport
    }
    if (!staleReport.contains("YA NO ES la de esta entidad")) {
        return "no explica la discrepancia:\n" + staleReport
    }
    if (!staleReport.contains("0.05, 0.21, -0.90")) {
        return "no imprime lo que hay en la ficha equivocada:\n" + staleReport
    }
    if (!staleReport.contains("la discrepancia aparece al LEER la ficha equivocada")) {
        return "no concluye donde aparece la discrepancia:\n" + staleReport
    }
    // 3: el objeto sano tiene que seguir siendo sano (no se elige por nombre).
    if (!staleReport.contains("C) objeto sano: #810")) {
        return "el objeto de comparacion cambio:\n" + staleReport
    }
    scene.focusLocalId = 0
    return "OK"
}

// ---------------------------------------------------------------------------
// Fase 2.12c: la etiqueta del estado del benchmark (A/B/C/D)
// ---------------------------------------------------------------------------

/**
 * Fase 2.12c: el informe tiene que decir sin ambiguedad cual de los cuatro
 * estados del benchmark esta midiendo, leyendo los flags que el motor ya tiene
 * APLICADOS (no los que la UI ha pedido). "A" es el estado por defecto:
 * culling ON, distancia ON, sombras ON, LOD OFF. Cualquier otra combinacion
 * fuera de A/B/C/D (por ejemplo con el LOD encendido) se etiqueta "-", para que
 * no se confunda con una de las cuatro medidas.
 */

private fun awaitCondition(timeoutMillis: Long = 5000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) {
            return true
        }
        Thread.sleep(5)
    }
    return false
}

/**
 * Un transporte falso para las pruebas del pipeline.
 *
 * Existe para que la politica de descarga —cola, deduplicacion, cancelacion,
 * contadores, tiempos— se pueda ejercer sin red y con resultados exactos: lo que
 * tarda la grid es lo unico que no se puede imitar, y por eso [block] permite
 * dejar una peticion "en el aire" el tiempo que haga falta para provocar una
 * cancelacion sin depender de una carrera.
 */

private class RecordingTextureTransport(
    var answer: (String, Int) -> TextureTransportReply = { _, _ ->
        TextureTransportReply.Bytes(ByteArray(128) { index -> (index and 0x7F).toByte() }, "transporte falso")
    }
) : TextureTransport {

    override val name: String = "falso"

    var ready = true

    /** Cuando es true, [fetch] no contesta hasta que se ponga a false. */
    @Volatile
    var block = false

    val requests = ArrayList<String>()

    @Volatile
    var fetchCount = 0
        private set

    override val isReady: Boolean get() = ready

    override fun fetch(textureId: String, discardLevel: Int): TextureTransportReply {
        synchronized(requests) {
            requests.add(textureId + "@" + discardLevel)
        }
        fetchCount += 1
        while (block) {
            try {
                Thread.sleep(2)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return answer(textureId, discardLevel)
    }
}

/** Un decodificador disponible que rechaza todo: el codestream que no se lee. */

private fun textureBindingCheck(): String {
    val renderer = FakeRenderer()
    val transport = RecordingTextureTransport()
    val provider = GetTextureAssetProvider(transport, workers = 1)
    val pipeline = TexturePipeline(provider, SyntheticTextureDecoder(16), decodeWorkers = 1)
    val diagnostics = RenderDiagnostics()
    val scene = SLScene(
        renderer,
        SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed),
        diagnostics,
        pipeline
    )
    val streamer = scene.textureStreamer ?: return "la escena no construyo el streamer"

    scene.apply(SLTestScene.buildDelta())
    if (renderer.materialsCreated != 10) return "materiales " + renderer.materialsCreated
    if (scene.drawnFaceCount != 20) return "caras dibujadas " + scene.drawnFaceCount
    if (scene.untexturedFaceCount != 0) return "el escenario de prueba lleva textura en todas las caras"
    if (scene.texturedFaceCount != 0) return "sin descargas no deberia haber caras con textura"
    if (scene.texturedEntityCount != 0) return "sin descargas no deberia haber entidades con textura"
    if (scene.textures.materialsWithTexture != 0) return "sin descargas no deberia haber materiales con textura"

    // El alfa de la cara decide el modo de mezcla: opaco es el caso comun y el
    // camino rapido, y todo lo que no sea opaco se dibuja translucido (la regla
    // del visor de referencia). MASKED queda para cuando haya pixeles que mirar.
    if (AlphaMode.forAlpha(1f) != AlphaMode.OPAQUE) return "alfa 1 deberia ser OPAQUE"
    if (AlphaMode.forAlpha(0.999f) != AlphaMode.OPAQUE) return "alfa 0.999 deberia ser OPAQUE"
    if (AlphaMode.forAlpha(0.5f) != AlphaMode.BLEND) return "alfa 0.5 deberia ser BLEND"
    if (AlphaMode.forAlpha(0f) != AlphaMode.BLEND) return "alfa 0 deberia ser BLEND"

    val camera = cameraDescAt(0f, 0f, 0f)
    scene.prune(camera)
    if (!awaitCondition(5000) { transport.fetchCount >= 10 }) {
        return "solo se pidieron " + transport.fetchCount + " texturas de 10"
    }
    if (!awaitCondition(1000) { transport.fetchCount >= 10 && transport.requests.size >= 10 }) {
        return "el transporte no registro las 10 peticiones"
    }
    if (transport.requests.size != 10) {
        return "20 caras pidieron " + transport.requests.size + " texturas; deberian ser 10 (compartidas)"
    }
    // Las diez son UUID distintos del escenario, pedidos una sola vez cada uno.
    if (transport.requests.toSet().size != 10) {
        return "se pidio la misma textura mas de una vez: " + transport.requests
    }

    // Los bytes llegan y se decodifican; el upload ocurre en el hilo de render
    // (aqui, el de la prueba), asi que se bombea hasta que las caras tengan pixeles.
    val deadline = System.currentTimeMillis() + 15000
    while (scene.texturedFaceCount < 20 && System.currentTimeMillis() < deadline) {
        scene.prune(camera)
        Thread.sleep(5)
    }
    if (scene.texturedFaceCount != 20) {
        return "caras con textura " + scene.texturedFaceCount + " de 20 (" + streamer.statusLine() + ")"
    }
    if (scene.texturedEntityCount != 20) return "entidades con textura " + scene.texturedEntityCount
    if (scene.textures.materialsWithTexture != 10) {
        return "materiales con textura " + scene.textures.materialsWithTexture + " de 10"
    }
    if (renderer.texturesCreated != 10) return "texturas subidas " + renderer.texturesCreated
    if (pipeline.stats.uploadOk != 10) return "uploads contados " + pipeline.stats.uploadOk
    if (pipeline.stats.decodeOk != 10) return "decodes contados " + pipeline.stats.decodeOk
    if (renderer.materialsUpdated == 0) return "ningun material se reconstruyo con la textura"
    if (scene.textureStreamer?.hudLine()?.contains("caras") != true) {
        return "la linea del HUD no lleva las caras: " + streamer.hudLine()
    }

    // El bloque del informe que el usuario copia del dispositivo.
    val report = scene.textureReport()
    for (marker in listOf(
        "UUIDs solicitados", "cache hit", "peticiones emitidas", "en vuelo",
        "respuestas recibidas", "assets fallidos", "decode: OK", "upload: OK",
        "materiales con textura", "caras con textura", "tiempo de descarga",
        "tiempo de decode", "memoria: cache", "decoder:",
        // Lo que la revision 2.13b-rev1 añade para poder diagnosticar los 649
        // errores del dispositivo: el reparto por tipo, la lectura A-F, y los
        // fallos primero y ultimo con sus hechos.
        "errores por tipo", "lectura A-F", "primer error", "ultimo error"
    )) {
        if (!report.contains(marker)) return "el informe de texturas no imprime '" + marker + "'"
    }
    if (!report.contains("con textura aplicada: 20")) {
        return "el informe no dice que 20 caras llevan textura: " + report.replace("\n", " | ")
    }

    // Alejarse no quita los materiales ni las texturas: solo la visibilidad.
    scene.prune(cameraDescAt(5000f, 0f, 0f))
    if (scene.visibleEntities != 0) return "lejos de todo no deberia quedar nada visible"
    if (scene.textures.materialsWithTexture != 10) {
        return "alejarse perdio los materiales con textura: " + scene.textures.materialsWithTexture
    }

    // Un backend que rechaza la textura no puede romper nada: la cara se queda
    // con su tinte, el contador de subidas fallidas lo dice y el motivo queda
    // escrito con el UUID, para poder buscarlo.
    val rejectingRenderer = FakeRenderer()
    rejectingRenderer.rejectTextures = true
    val rejectingProvider = GetTextureAssetProvider(RecordingTextureTransport(), workers = 1)
    val rejectingPipeline = TexturePipeline(rejectingProvider, SyntheticTextureDecoder(8), decodeWorkers = 1)
    val rejectingScene = SLScene(
        rejectingRenderer,
        SLMeshLibrary(::fakeGeometry, ::fakeGeometryFixed),
        RenderDiagnostics(),
        rejectingPipeline
    )
    rejectingScene.apply(SLTestScene.buildDelta())
    val rejectCamera = cameraDescAt(0f, 0f, 0f)
    val rejectDeadline = System.currentTimeMillis() + 15000
    while (rejectingPipeline.stats.uploadFailed < 10 && System.currentTimeMillis() < rejectDeadline) {
        rejectingScene.prune(rejectCamera)
        Thread.sleep(5)
    }
    if (rejectingPipeline.stats.uploadFailed != 10) {
        return "subidas fallidas " + rejectingPipeline.stats.uploadFailed + " de 10"
    }
    if (rejectingPipeline.stats.uploadOk != 0) return "no deberia haber subido ninguna textura"
    if (rejectingRenderer.texturesCreated != 0) {
        return "el renderer conto " + rejectingRenderer.texturesCreated + " texturas que rechazo"
    }
    if (rejectingScene.texturedFaceCount != 0) return "sin pixeles no deberia haber caras con textura"
    if (rejectingScene.textures.materialCount != 10) {
        return "el fallback deberia dejar los 10 materiales: " + rejectingScene.textures.materialCount
    }
    if (!rejectingScene.textureReport().contains("ultimo fallo de textura")) {
        return "el informe no nombra el ultimo fallo de textura"
    }
    rejectingScene.destroy()
    rejectingProvider.shutdown()

    println("  TEXTURAS: 20 caras, " + renderer.texturesCreated + " texturas subidas, " +
        renderer.materialsUpdated + " materiales reconstruidos, " +
        transport.requests.size + " descargas")

    scene.destroy()
    provider.shutdown()
    return "OK"
}

// ---------------------------------------------------------------------------
// Fase 2.13b-rev1: por que fallan las descargas (GetTexture: 649 errores)
// ---------------------------------------------------------------------------

/**
 * El diagnostico de fallos, probado sin red.
 *
 * El dispositivo dijo `UUIDs solicitados 649 · respuestas recibidas 649 · errores
 * de red 649 · bytes recibidos 0`, que es cierto y no sirve: no dice si el
 * servidor contesto, ni con que, ni si alguien leyo algo. Este check fija las
 * cuatro cosas que hacen que la proxima ejecucion si sirva:
 *
 * 1. **el saneado**: un informe que se copia y se pega no puede llevar una URL de
 *    capability (la credencial va en el camino), un `session_id=` ni una cabecera
 *    `Cookie`/`Authorization`, y aun asi tiene que decir *que* paso;
 * 2. **los hechos**: un 502 con `Via` y `Server` de proxy se registra con su
 *    status, su `Content-Type`, su tamaño y su extracto, y se marca como proxy;
 * 3. **la clasificacion**: cada forma de fallar (capability ausente, sin
 *    respuesta, error HTTP, respuesta vacia, lectura local) cae en su clase, y las
 *    cinco se distinguen entre si;
 * 4. **la particion A-F**: los contadores por tipo suman exactamente el total, y
 *    `failureReading` reparte ese total entre las seis preguntas sin contar nada
 *    dos veces ni dejar nada fuera — que es lo que convierte el bloque del
 *    informe en una respuesta y no en un resumen.
 */

private const val TEST_COUNT = 27

fun renderPipelineChecks(): String {
    val failures = ArrayList<String>()
    fun check(name: String, body: () -> String) {
        val result = try { body() } catch (error: Throwable) { "EXCEPCION " + error.javaClass.simpleName + ": " + error.message }
        if (result != "OK") failures.add(name + " -> " + result)
    }
    check("pcodes", ::pcodeClassificationCheck)
    check("color", ::colorCheck)
    check("primparams", ::primitiveParamsCheck)
    check("testscene", ::testSceneCheck)
    check("world", ::worldDiffCheck)
    check("camera", ::cameraCheck)
    check("framing", ::framingCheck)
    check("scene", ::scenePipelineCheck)
    check("reject", ::sceneRejectionCheck)
    check("cache", ::meshCacheCheck)
    check("avatars", ::avatarCheck)
    check("probe", ::probeMeshCheck)
    check("terrain", ::terrainMeshCheck)
    check("census", ::shapeCensusCheck)
    check("samesource", ::shapeSingleSourceCheck)
    check("budget", ::entityBudgetCheck)
    check("frustum", ::frustumMathCheck)
    check("matrixfrustum", ::matrixFrustumCheck)
    check("camtrace", ::cameraTraceCheck)
    check("camprobe", ::cameraProbeCheck)
    check("primlock", ::primLockCheck)
    check("parentchain", ::parentChainCheck)
    check("parentresolve", ::parentResolveCheck)
    check("focusreport", ::focusReportCheck)
    check("scalability", ::scalabilityCheck)
    check("childfrustum", ::childFrustumCheck)
    check("texbind", ::textureBindingCheck)
    return if (failures.isEmpty()) "OK (" + TEST_COUNT + " comprobaciones)" else failures.joinToString(" | ")
}
