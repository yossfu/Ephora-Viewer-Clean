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
fun renderPipelineChecks(): String {
    val failures = ArrayList<String>()
    fun check(name: String, body: () -> String) {
        val result = try {
            body()
        } catch (error: Throwable) {
            "EXCEPCION " + error.javaClass.simpleName + ": " + error.message
        }
        if (result != "OK") {
            failures.add(name + " -> " + result)
        }
    }

    check("shapes", ::shapeClassificationCheck)
    check("pcodes", ::pcodeClassificationCheck)
    check("geomval", ::geometryValidationCheck)
    check("transform", ::transformCheck)
    check("color", ::colorCheck)
    check("primparams", ::primitiveParamsCheck)
    check("testscene", ::testSceneCheck)
    check("world", ::worldDiffCheck)
    check("camera", ::cameraCheck)
    check("framing", ::framingCheck)
    check("scene", ::scenePipelineCheck)
    check("reject", ::sceneRejectionCheck)
    check("objectlog", ::objectLogCheck)
    check("cache", ::meshCacheCheck)
    check("avatars", ::avatarCheck)
    check("probe", ::probeMeshCheck)
    check("terrain", ::terrainMeshCheck)
    check("diagnostics", ::diagnosticsCheck)
    check("bootstrap", ::bootstrapCheck)
    check("bytes", ::byteReaderCheck)
    check("compressed", ::compressedUpdateCheck)
    check("placeonly", ::placementOnlyCheck)
    check("incremental", ::incrementalShapeCheck)
    check("shapesource", ::shapeSourceCheck)
    check("census", ::shapeCensusCheck)
    check("samesource", ::shapeSingleSourceCheck)
    check("budget", ::entityBudgetCheck)
    check("frustum", ::frustumMathCheck)
    check("matrixfrustum", ::matrixFrustumCheck)
    check("camtrace", ::cameraTraceCheck)
    check("camprobe", ::cameraProbeCheck)
    check("primlock", ::primLockCheck)
    check("parentparser", ::parentParserCheck)
    check("parentchain", ::parentChainCheck)
    check("parentresolve", ::parentResolveCheck)
    check("focusreport", ::focusReportCheck)
    check("scalability", ::scalabilityCheck)
    check("childfrustum", ::childFrustumCheck)
    check("benchstate", ::benchmarkStateCheck)
    check("texentry", ::textureEntryParseCheck)
    check("texdecode", ::textureEntryDecoderCheck)
    check("texclass", ::textureEntryClassificationCheck)
    check("texdiag", ::textureEntryDiagnosticsCheck)
    check("texcache", ::textureAssetCacheCheck)
    check("texref", ::textureEntryReferenceCheck)
    check("texframing", ::textureFramingCheck)
    check("terseprefix", ::tersePrefixCheck)
    check("terseconsume", ::terseConsumeCheck)
    check("renderloop", ::renderLoopAuditCheck)
    check("moveaudit", ::movementAuditCheck)
    check("texasset", ::texturePipelineCheck)
    check("texbind", ::textureBindingCheck)
    check("capsfail", ::capabilityFailureCheck)

    return if (failures.isEmpty()) {
        "OK (" + TEST_COUNT + " comprobaciones)"
    } else {
        failures.joinToString(" | ")
    }
}

private const val TEST_COUNT = 53

private fun expect(condition: Boolean, message: String): String = if (condition) "OK" else message

/** `LL_PCODE_*`: line 0x10, circle 0x20, flexible 0x80; circle 0x00, square 0x01, tri 0x02..0x04, half 0x05. */
private fun shapeClassificationCheck(): String {
    val cases = listOf(
        Triple(0x10, 0x01, PrimShape.BOX),
        Triple(0x10, 0x00, PrimShape.CYLINDER),
        Triple(0x10, 0x02, PrimShape.PRISM),
        Triple(0x10, 0x03, PrimShape.PRISM),
        Triple(0x10, 0x04, PrimShape.PRISM),
        Triple(0x20, 0x05, PrimShape.SPHERE),
        Triple(0x20, 0x00, PrimShape.TORUS),
        Triple(0x20, 0x01, PrimShape.TUBE),
        Triple(0x30, 0x00, PrimShape.TORUS),
        Triple(0x80, 0x00, PrimShape.SPHERE),
        Triple(0x80, 0x01, PrimShape.BOX)
    )
    for (case in cases) {
        val actual = PrimShapeClassifier.classify(case.first, case.second)
        if (actual != case.third) {
            return "path=0x" + case.first.toString(16) + " profile=0x" + case.second.toString(16) +
                " esperado " + case.third + " obtenido " + actual
        }
    }
    if (PrimShapeClassifier.classify(0x10, 0x00, 40000) != PrimShape.TUBE) {
        return "el cilindro hueco deberia ser TUBE"
    }
    if (PrimShapeClassifier.classify(0x20, 0x00, 40000) != PrimShape.RING) {
        return "el toroide hueco deberia ser RING"
    }
    return "OK"
}

/**
 * The `PCode` values, and what they classify as.
 *
 * This is the check that would have caught the on-device bug directly: the
 * viewer used to declare `PCODE_PRIM = 6` and `PCODE_TREE = 9`, but `9` is
 * `LL_PCODE_VOLUME` — every ordinary prim — while the legacy tree is `255`. The
 * result was 522 real prims classified as trees and handed the legacy tree mesh
 * instead of the geometry their own path/profile parameters describe.
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
private fun geometryValidationCheck(): String {
    val good = fakeGeometry(IntArray(0))
    val goodProblem = good.geometryProblem()
    if (goodProblem != null) return "una malla valida se rechazo: " + goodProblem
    if (good.faceCount != 1) return "faceCount de la malla valida " + good.faceCount
    if (!good.summary().contains("caras=1")) return "el resumen no lleva las caras: " + good.summary()

    val noGroups = MeshDesc(good.vertices, good.indices, IntArray(0), good.boundsMin, good.boundsMax)
    if (noGroups.faceCount != 0) return "faceCount sin grupos " + noGroups.faceCount
    val groupsProblem = noGroups.geometryProblem()
        ?: return "no se detecto una malla sin grupos de caras"
    if (!groupsProblem.contains("grupos de caras")) return "motivo inesperado: " + groupsProblem

    fun problemOf(
        vertices: FloatArray = good.vertices,
        indices: IntArray = good.indices,
        groups: IntArray = good.faceGroups
    ): String = MeshDesc(vertices, indices, groups, good.boundsMin, good.boundsMax).geometryProblem()
        ?: "ninguno"

    if (!problemOf(vertices = FloatArray(0)).contains("sin vertices")) return "no detecto cero vertices"
    if (!problemOf(indices = IntArray(0)).contains("sin indices")) return "no detecto cero indices"
    if (!problemOf(indices = intArrayOf(0, 1)).contains("multiplo de 3")) return "no detecto indices sueltos"
    if (!problemOf(indices = intArrayOf(0, 1, 9)).contains("fuera de")) return "no detecto un indice fuera de rango"
    if (!problemOf(groups = intArrayOf(0, 0, 99)).contains("fuera de")) return "no detecto un rango de caras fuera del buffer"
    // Two triangles in the buffer, a group that only covers the first one: the
    // object still draws (the covered triangle), so this is a *warning*, not a
    // reason to hide a whole prim. Reporting it is how a generator bug stays
    // visible without turning into "no geometry".
    val twoTriangles = intArrayOf(0, 1, 2, 0, 2, 1)
    val partial = MeshDesc(good.vertices, twoTriangles, intArrayOf(0, 0, 3), good.boundsMin, good.boundsMax)
    if (partial.geometryProblem() != null) {
        return "un grupo que no cubre todo no deberia rechazar la malla: " + partial.geometryProblem()
    }
    val warning = partial.geometryWarning() ?: return "no se aviso de los indices sin grupo"
    if (!warning.contains("cubren")) return "aviso inesperado: " + warning
    if (good.geometryWarning() != null) return "se aviso de una malla completa: " + good.geometryWarning()
    if (!problemOf(groups = intArrayOf(0, 0, 2)).contains("multiplo de 3")) {
        return "no detecto un grupo con un numero de indices no multiplo de 3"
    }
    if (!problemOf(groups = intArrayOf(0, 0, 3, 0, 0)).contains("multiplo de 3")) {
        return "no detecto grupos mal formados (tamano no multiplo de 3)"
    }
    val nan = good.vertices.copyOf()
    nan[0] = Float.NaN
    if (!problemOf(vertices = nan).contains("no finito")) return "no detecto un vertice no finito"
    // A mesh with more face groups than triangles still has to be refused when a
    // group is empty, because an empty primitive draws nothing.
    if (!problemOf(groups = intArrayOf(0, 0, 0)).contains("indexCount")) return "no detecto una cara vacia"
    return "OK"
}

private fun transformCheck(): String {
    // 90 degrees about Z: the quaternion is (0, 0, sin45, cos45), i.e. +X goes to +Y.
    val half = kotlin.math.sqrt(0.5f)
    val transform = Transform(
        translation = floatArrayOf(10f, 20f, 30f),
        rotation = floatArrayOf(0f, 0f, half, half),
        scale = floatArrayOf(2f, 3f, 4f)
    )
    val matrix = transform.toMatrix16()
    // Column major: column 0 is the image of +X, which must be (0, 2, 0) after scaling by 2.
    if (kotlin.math.abs(matrix[0]) > 1e-5f) return "col0.x = " + matrix[0]
    if (kotlin.math.abs(matrix[1] - 2f) > 1e-4f) return "col0.y = " + matrix[1]
    if (kotlin.math.abs(matrix[4] + 3f) > 1e-4f) return "col1.x = " + matrix[4]
    if (kotlin.math.abs(matrix[5]) > 1e-5f) return "col1.y = " + matrix[5]
    if (kotlin.math.abs(matrix[10] - 4f) > 1e-4f) return "col2.z = " + matrix[10]
    if (matrix[12] != 10f || matrix[13] != 20f || matrix[14] != 30f || matrix[15] != 1f) {
        return "traslacion " + matrix[12] + "," + matrix[13] + "," + matrix[14] + "," + matrix[15]
    }
    // Identity rotation keeps the axes and applies the scale.
    val plain = Transform(
        translation = floatArrayOf(0f, 0f, 0f),
        rotation = floatArrayOf(0f, 0f, 0f, 1f),
        scale = floatArrayOf(1f, 1f, 1f)
    ).toMatrix16()
    if (plain[0] != 1f || plain[5] != 1f || plain[10] != 1f) {
        return "identidad mal formada"
    }
    return "OK"
}

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
private fun objectLogCheck(): String {
    val diagnostics = RenderDiagnostics()
    for (i in 1..25) {
        diagnostics.addObjectLog("objeto " + i)
    }
    if (diagnostics.objectLogCount != 25) return "objetos contados " + diagnostics.objectLogCount
    if (diagnostics.objectLogLines().size != RenderDiagnostics.OBJECT_LOG_LIMIT) {
        return "lineas guardadas " + diagnostics.objectLogLines().size
    }
    val hud = diagnostics.lines().joinToString("\n")
    if (!hud.contains("25 objetos recibidos")) return "el HUD no dice cuantos objetos hubo"
    if (!hud.contains("objeto 20")) return "el HUD no guarda los primeros objetos"

    diagnostics.rejectEntity("rechazo 1")
    diagnostics.rejectEntity("rechazo 2")
    if (diagnostics.entityRejections != 2) return "rechazos " + diagnostics.entityRejections
    if (!diagnostics.lines().any { it.contains("rechazo 1") }) return "el HUD no lista los rechazos"

    diagnostics.resetObjectLog()
    if (diagnostics.objectLogCount != 0 || diagnostics.entityRejections != 0 ||
        diagnostics.firstPrimReport != "-" || diagnostics.objectLogLines().isNotEmpty()
    ) {
        return "resetObjectLog no limpio el registro"
    }
    if (diagnostics.lines().any { it.contains("objetos recibidos") }) {
        return "el HUD sigue mostrando un registro vacio"
    }
    return "OK"
}

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
private fun diagnosticsCheck(): String {
    val d = RenderDiagnostics()
    d.mode = "region"
    d.modelObjects = 490
    d.regionObjects = 487
    d.sceneEntities = 487
    d.sceneVisible = 400
    d.framesAttempted = 120
    d.beginFrameOk = 118
    d.cameraEye = floatArrayOf(167.95f, 142.02f, 22.52f)
    d.viewportWidth = 1080
    d.viewportHeight = 2400
    d.terrainPatches = 22
    d.avatars = 1
    d.fail("frame", "boom")
    d.fail("beginFrame", "segundo error")
    if (d.errorStage != "frame" || d.errorMessage != "boom") {
        return "no conservo el primer error (" + d.errorStage + ": " + d.errorMessage + ")"
    }
    val text = d.lines().joinToString("\n")
    for (needle in listOf("490", "487", "400", "120", "167.95", "Viewport", "Camara", "boom", "frame")) {
        if (!text.contains(needle)) return "el informe no menciona " + needle
    }
    if (!text.contains("1080x2400")) return "el informe no muestra el viewport"
    if (d.center()[0] != 0f) return "el centro sin limites deberia ser cero"

    d.recordWorldBounds(floatArrayOf(-10f, -20f, 0f), floatArrayOf(30f, 40f, 50f))
    if (!d.boundsKnown) return "los limites no se registraron"
    val centre = d.center()
    if (centre[0] != 10f || centre[1] != 10f || centre[2] != 25f) {
        return "centro " + centre.joinToString(",")
    }
    val withBounds = d.lines().joinToString("\n")
    if (!withBounds.contains("Mundo min")) return "el informe no muestra los limites"
    if (!withBounds.contains("centro")) return "el informe no muestra el centro del mundo"

    // The spec's section 4 question, in one line: is the middle of the received
    // world in front of the camera? A camera can be otherwise perfect and still
    // see nothing because it is aimed the other way.
    d.cameraEye = floatArrayOf(0f, -80f, 10f)
    d.cameraForward = floatArrayOf(0f, 1f, 0f)
    if (!d.lines().joinToString("\n").contains("Centro del mundo delante de la camara: SI")) {
        return "el centro del mundo deberia salir delante de la camara"
    }
    d.cameraForward = floatArrayOf(0f, -1f, 0f)
    if (!d.lines().joinToString("\n").contains("Centro del mundo delante de la camara: NO")) {
        return "el centro del mundo deberia salir detras de la camara"
    }

    d.clearError()
    if (d.errorStage.isNotEmpty() || d.errorMessage.isNotEmpty()) return "clearError no limpio"

    d.probeEntities = 4
    d.probeTriangles = 506
    d.terrainTriangles = 32768
    val verdicts = d.verdicts(4, 487, 22)
    if (verdicts.size != 3) return "veredictos " + verdicts.size
    if (!verdicts[0].contains("PRUEBA A")) return "sin PRUEBA A"
    if (!verdicts[1].contains("PRUEBA B")) return "sin PRUEBA B"
    if (!verdicts[2].contains("PRUEBA C")) return "sin PRUEBA C"
    // A probe that never became an entity is a failure *before* the draw; the
    // report must say so instead of blaming Second Life.
    val broken = RenderDiagnostics()
    if (!broken.verdicts(4, 487, 22)[0].contains("FALLA ANTES DEL DIBUJO")) {
        return "un probe vacio no se reporta como fallo previo al dibujo"
    }
    return "OK"
}

/**
 * The start-up log is what turns "Filament no pudo iniciarse" into a fix.
 *
 * These checks pin down the contract the whole phase rests on: every step is
 * recorded as START / SUCCESS / FAIL; a failure keeps the **whole** throwable
 * (class, message, cause chain and stack trace), never just a sentence; the
 * first failure is preserved while the retry storm is still visible as the
 * last one; the environment block and the SurfaceHolder callbacks reach the
 * report; and the report can be written to disk so it survives leaving the
 * device.
 *
 * The failure used is the real one this phase was written for: a Kotlin class
 * initializer (`companion object`) building a `TextureSampler` — whose
 * constructor calls native `nCreateSampler` — before `Filament.init()` had
 * loaded `libfilament-jni.so`, which leaves the class erroneous and makes every
 * later attempt report only `NoClassDefFoundError: <class name>`.
 */
private fun bootstrapCheck(): String {
    val diagnostics = RenderDiagnostics()
    diagnostics.step("FILAMENT_INIT", "START")
    diagnostics.step("FILAMENT_INIT", "SUCCESS")
    diagnostics.step("ENGINE_CREATE", "START")
    diagnostics.step("ENGINE_CREATE", "SUCCESS")

    val link = UnsatisfiedLinkError(
        "No implementation found for long com.google.android.filament.TextureSampler.nCreateSampler(int, int, int, int, int)"
    )
    val failure = IllegalStateException("no se pudo crear el sampler", link)
    diagnostics.failDetailed("SAMPLER_MIPMAPPED", failure)

    val report = diagnostics.startUpReport().joinToString("\n")
    for (needle in listOf(
        "START  ENGINE_CREATE",
        "SUCCESS  ENGINE_CREATE",
        "FAIL SAMPLER_MIPMAPPED",
        "java.lang.IllegalStateException",
        "UnsatisfiedLinkError",
        "nCreateSampler",
        "Caused by",
        "at "
    )) {
        if (!report.contains(needle)) {
            return "el informe de arranque no contiene " + needle
        }
    }

    // A start-up that is retried keeps the first (informative) error as "the"
    // error, and the last one separately: a failed class initializer reports its
    // own name on every later attempt, which is the symptom, not the cause.
    diagnostics.fail("createRenderer", "java.lang.NoClassDefFoundError: FilamentRenderer")
    if (diagnostics.errorStage != "SAMPLER_MIPMAPPED") {
        return "se perdio el primer error (" + diagnostics.errorStage + ")"
    }
    if (diagnostics.lastErrorStage != "createRenderer") {
        return "no se guarda el ultimo error (" + diagnostics.lastErrorStage + ")"
    }
    val hud = diagnostics.lines().joinToString("\n")
    if (!hud.contains("ERROR en SAMPLER_MIPMAPPED")) return "el HUD no muestra el primer error"
    if (!hud.contains("ULTIMO error en createRenderer")) return "el HUD no muestra el ultimo error"
    if (!diagnostics.errorFull.contains("nCreateSampler")) {
        return "errorFull no conserva la traza completa"
    }

    // The native preflight is environment evidence, not a step: it must be in
    // the report under its own heading.
    diagnostics.environment(
        listOf(
            "Dispositivo: Google Pixel  ·  Android 14 (API 34)",
            "ABIs soportadas: arm64-v8a, armeabi-v7a",
            "System.loadLibrary(\"filament-jni\"): OK"
        )
    )
    val withEnvironment = diagnostics.startUpReport().joinToString("\n")
    if (!withEnvironment.contains("--- entorno nativo ---")) return "sin bloque de entorno"
    if (!withEnvironment.contains("libfilament-jni") && !withEnvironment.contains("filament-jni")) {
        return "el entorno no lista las librerias nativas"
    }

    // The whole throwable must survive the trip into the report text, which is
    // what a device writes to a file and what a user copies off the screen.
    val full = diagnostics.fullReport()
    for (needle in listOf("FILAMENT DEBUG", "FAIL SAMPLER_MIPMAPPED", "nCreateSampler")) {
        if (!full.contains(needle)) return "el informe completo no contiene " + needle
    }

    // The SurfaceHolder handshake (spec section 4) must be visible too.
    diagnostics.surfaceEvent("SURFACE CREATED  width = 1080  height = 2400")
    diagnostics.surfaceEvent("SURFACE NOT CREATED  valida=false  0x0")
    val surfaceHud = diagnostics.lines().joinToString("\n")
    if (!surfaceHud.contains("SurfaceCallback: SURFACE CREATED")) {
        return "el HUD no muestra las llamadas del SurfaceView"
    }
    if (!surfaceHud.contains("SURFACE NOT CREATED")) {
        return "el HUD no muestra la superficie ausente"
    }

    // And the throwable formatter the bootstrap uses must not lose the cause.
    val summary = com.lumiyaviewer.lumiya.renderer.filament.FilamentBootstrap.errorSummary(failure)
    if (!summary.contains("IllegalStateException") || !summary.contains("UnsatisfiedLinkError")) {
        return "errorSummary pierde la cadena de causas: " + summary
    }

    // On a device the report is also written to the app's files directory. The
    // sandbox that hosts these checks denies file creation, so a null return is
    // accepted here — but if a file *is* produced it must hold the same text.
    val file = java.io.File("ephora-diag-check.log")
    val written = diagnostics.writeTo(file)
    if (written != null) {
        val text = written.readText()
        if (!text.contains("FAIL SAMPLER_MIPMAPPED") || !text.contains("nCreateSampler")) {
            return "el informe en disco no conserva la traza completa"
        }
        written.delete()
    }
    return "OK"
}

// ------------------------------------------------------------------- helpers

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
private fun byteReaderCheck(): String {
    val blob = ByteArray(113)
    val reader = ByteReader(blob).at("prueba.Length")
    reader.skip(-872415225)
    if (reader.position != 0) return "skip negativo movio el cursor a " + reader.position
    if (reader.violations != 1) return "skip negativo no se conto (" + reader.violations + ")"
    if (!reader.firstViolation.contains("prueba.Length")) return "el aviso no nombra el campo: " + reader.firstViolation
    reader.seek(10)
    reader.skip(1000)
    if (reader.position != blob.size) return "skip mas alla del final quedo en " + reader.position
    val huge = reader.bytes(-872415225)
    if (huge.isNotEmpty()) return "bytes() con longitud negativa devolvio " + huge.size
    if (reader.seek(200)) return "seek fuera del bloque no fue rechazado"
    if (reader.violations < 4) return "no se registraron todos los avances rechazados (" + reader.violations + ")"
    // The guard must not be able to produce an index at all.
    val ok = try {
        reader.at("final")
        reader.u8()
        reader.u16()
        reader.u32()
        reader.string()
        true
    } catch (error: Throwable) {
        return "el lector lanzo " + error.javaClass.simpleName + ": " + error.message
    }
    return expect(ok && reader.violations >= 3, "no se registraron los avances rechazados (" + reader.violations + ")")
}

/**
 * The reported bug, reproduced with a real 113-byte block: the geometry block
 * *must* be read, the object must end up with a complete shape, and the parser
 * must not throw.
 */
private fun compressedUpdateCheck(): String {
    val blob = compressedBlob(7001, pathCurve = 0x10, profileCurve = 0x00)
    if (blob.size != 113) return "el bloque de prueba mide " + blob.size + " bytes, no 113"
    val model = WorldModel()
    beforeParser()
    val applied = ObjectUpdateDecoder.applyCompressed(compressedMessage(blob), model)
    if (applied != 1) return "bloques aplicados " + applied
    val object_ = model.get(7001) ?: return "el objeto no se creo"
    if (object_.uuid != "01020304-0506-0708-090a-0b0c0d0e0f10") return "uuid " + object_.uuid
    if (object_.pcode != SceneObject.PCODE_PRIM) return "pcode " + object_.pcode
    if (object_.position.x != 10f || object_.position.y != 20f || object_.position.z != 30f) {
        return "posicion " + object_.position
    }
    if (object_.scale.y != 2f) return "escala " + object_.scale
    if (!object_.paramsKnown) return "los parametros del bloque comprimido NO se leyeron (el bug original)"
    if (!object_.hasCompleteShape) return "el objeto no tiene forma completa"
    if (object_.pathCurve != 0x10 || object_.profileCurve != 0x00) {
        return "pathCurve=0x" + Integer.toHexString(object_.pathCurve) +
            " profileCurve=0x" + Integer.toHexString(object_.profileCurve)
    }
    if (object_.shape != PrimShape.CYLINDER) return "forma " + object_.shape
    if (object_.shapeSource != ShapeSource.RECEIVED_COMPRESSED) return "origen " + object_.shapeSource
    if (object_.shapeText != "CYLINDER") return "shapeText " + object_.shapeText
    if (object_.extraParamsSize != 1) return "ExtraParams consumio " + object_.extraParamsSize + " bytes (deberia ser 1)"
    if (object_.textureEntrySize != 1) return "TextureEntry " + object_.textureEntrySize
    if (ObjectUpdateDiagnostics.compressedWithParams != beforeWithParams + 1) {
        return "el bloque no se conto como 'con parametros'"
    }
    if (ObjectUpdateDiagnostics.compressedPlacementOnly != beforePlacementOnly) {
        return "el bloque se conto como 'solo colocacion'"
    }
    if (ObjectUpdateDiagnostics.compressedFailed != beforeFailures) {
        return "el bloque fallo: " + ObjectUpdateDiagnostics.lastFailure
    }
    if (ObjectUpdateDiagnostics.readerViolations != beforeViolations) {
        return "el lector registro avances rechazados: " + ObjectUpdateDiagnostics.readerFirstViolation
    }
    val trace = ObjectUpdateDiagnostics.fieldTraceText()
    if (!trace.contains("ProfileHollow")) return "la traza de offsets no incluye los campos: " + trace.take(120)
    return "OK"
}

/**
 * An `ExtraParams` section that cannot be walked (the length is 0xCC000007, the
 * value from the device report) must be *refused and reported*, must not throw,
 * must not be used to locate the geometry block — and the definition the object
 * already had must survive untouched.
 */
private fun placementOnlyCheck(): String {
    val model = WorldModel()
    // 1: a good block first, so the object has a definition to lose.
    ObjectUpdateDecoder.applyCompressed(
        compressedMessage(compressedBlob(7002, pathCurve = 0x10, profileCurve = 0x01)),
        model
    )
    val object_ = model.get(7002) ?: return "el objeto no se creo"
    if (object_.shape != PrimShape.BOX) return "forma inicial " + object_.shape
    val bogus = byteArrayOf(1, 0x10, 0x00, 7, 0x00, 0x00, 0xCC.toByte())
    beforeParser()
    var threw: String? = null
    try {
        // The same object, a block whose ExtraParams entry claims 0xCC000007 bytes.
        ObjectUpdateDecoder.applyCompressed(
            compressedMessage(
                compressedBlob(7002, extraParams = bogus, pathCurve = 0x20, profileCurve = 0x05)
            ),
            model
        )
    } catch (error: Throwable) {
        threw = error.javaClass.simpleName + ": " + error.message
    }
    if (threw != null) return "el parser lanzo " + threw
    if (ObjectUpdateDiagnostics.extraParamBogusLengths != beforeBogusLengths + 1) {
        return "la longitud imposible no se registro"
    }
    val after = model.get(7002) ?: return "el objeto desaparecio"
    if (!after.paramsKnown) return "la definicion anterior se perdio"
    if (after.pathCurve != 0x10 || after.profileCurve != 0x01) {
        return "la definicion se sustituyo por bytes ilegibles: pathCurve=0x" +
            Integer.toHexString(after.pathCurve) + " profileCurve=0x" + Integer.toHexString(after.profileCurve)
    }
    if (after.shape != PrimShape.BOX) return "la forma cambio a " + after.shape
    if (after.shapeSource != ShapeSource.PERSISTED) return "origen " + after.shapeSource
    if (beforeFailures == ObjectUpdateDiagnostics.compressedFailed) return "el bloque ilegible no se conto"
    return "OK"
}

/**
 * The sequence the spec asks for: an object that first arrives without
 * path/profile (movement only) and later receives a complete definition. Its
 * state must transition MISSING → received → PERSISTED, and nothing may erase
 * the definition in between.
 */
private fun incrementalShapeCheck(): String {
    val model = WorldModel()
    ObjectUpdateDecoder.applyTerse(terseMessage(terseBlob(7003, Vector3(1f, 1f, 1f))), model)
    val first = model.get(7003) ?: return "el update terse no creo el objeto"
    if (first.paramsKnown) return "un update terse dijo tener forma"
    if (first.shapeText != "UNKNOWN/MISSING_SHAPE") return "forma tras el terse: " + first.shapeText
    if (first.shape == PrimShape.CYLINDER) return "el terse produjo un cilindro por defecto (el bug de la lista)"
    // Update #2: the definition.
    ObjectUpdateDecoder.applyCompressed(
        compressedMessage(compressedBlob(7003, pathCurve = 0x20, profileCurve = 0x05)),
        model
    )
    val second = model.get(7003) ?: return "el objeto desaparecio tras el comprimido"
    if (!second.paramsKnown) return "la definicion no se aplico"
    if (second.shape != PrimShape.SPHERE) return "forma " + second.shape
    if (second.shapeSource != ShapeSource.RECEIVED_COMPRESSED) return "origen " + second.shapeSource
    // Update #3 and #4: movement only, which must not touch the definition.
    ObjectUpdateDecoder.applyTerse(terseMessage(terseBlob(7003, Vector3(9f, 9f, 9f))), model)
    ObjectUpdateDecoder.applyTerse(terseMessage(terseBlob(7003, Vector3(11f, 11f, 11f))), model)
    val third = model.get(7003) ?: return "el objeto desaparecio tras los terse"
    if (third.pathCurve != 0x20 || third.profileCurve != 0x05) {
        return "un update terse cambio la definicion: pathCurve=0x" + Integer.toHexString(third.pathCurve)
    }
    if (third.profileHollow != second.profileHollow) return "el terse cambio profileHollow"
    if (third.shape != PrimShape.SPHERE) return "el terse perdio la forma (" + third.shape + ")"
    if (third.shapeSource != ShapeSource.PERSISTED) return "origen tras el terse " + third.shapeSource
    if (third.position.x != 11f) return "el terse no actualizo la posicion (" + third.position.x + ")"
    if (third.updatesSeen != 4) return "updates vistos " + third.updatesSeen
    if (third.terseUpdatesSeen != 3) return "terse vistos " + third.terseUpdatesSeen
    val counts = model.shapeCounts()
    if (counts.withPersistedShape != 1) return "forma persistida contada " + counts.withPersistedShape
    if (counts.withCompleteShape != 1) return "con forma completa " + counts.withCompleteShape
    if (counts.withMissingShape != 0) return "sin forma " + counts.withMissingShape
    if (counts.usingShapeFallback != 0) return "usando fallback " + counts.usingShapeFallback
    return "OK"
}

/**
 * The placeholder path/profile of a freshly created `SceneObject` classifies as
 * a cylinder. It must never be *reported* as one: that is exactly why every
 * object in the object list said "(cylinder)".
 */
private fun shapeSourceCheck(): String {
    val object_ = SceneObject(localId = 7004)
    if (object_.paramsKnown) return "un objeto nuevo dice tener parametros"
    if (object_.shape != PrimShape.UNKNOWN) return "la forma por defecto es " + object_.shape
    if (object_.shapeText != "UNKNOWN/MISSING_SHAPE") return "shapeText " + object_.shapeText
    if (object_.hasCompleteShape) return "un objeto nuevo dice tener forma completa"
    if (object_.shapeSource != ShapeSource.MISSING) return "origen " + object_.shapeSource
    if (object_.shapeIsFallback) return "el objeto se declara en fallback"
    // The same placeholder values *are* a cylinder once an update really carried
    // them: the difference is the provenance, not the numbers.
    object_.noteShapeReceived(UpdateSource.COMPRESSED)
    if (object_.shape != PrimShape.CYLINDER) return "tras recibir los parametros la forma es " + object_.shape
    if (object_.shapeText != "CYLINDER") return "shapeText tras recibir " + object_.shapeText
    if (object_.shapeIsFallback) return "una forma recibida se declara en fallback"
    if (object_.shapeSource != ShapeSource.RECEIVED_COMPRESSED) return "origen " + object_.shapeSource
    // A prim whose class has no path/profile at all is never "missing".
    val tree = SceneObject(localId = 7005, pcode = SceneObject.PCODE_LEGACY_TREE)
    if (!tree.hasCompleteShape) return "un arbol no tiene forma completa"
    if (tree.shape != PrimShape.TREE) return "la forma del arbol es " + tree.shape
    val avatar = SceneObject(localId = 7006, pcode = SceneObject.PCODE_AVATAR)
    if (avatar.shape != PrimShape.AVATAR) return "la forma del avatar es " + avatar.shape
    return "OK"
}

/** The census separates received / complete / partial / missing / fallback. */
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

private var beforeWithParams = 0
private var beforePlacementOnly = 0
private var beforeFailures = 0
private var beforeViolations = 0
private var beforeBogusLengths = 0

private fun beforeParser() {
    beforeWithParams = ObjectUpdateDiagnostics.compressedWithParams
    beforePlacementOnly = ObjectUpdateDiagnostics.compressedPlacementOnly
    beforeFailures = ObjectUpdateDiagnostics.compressedFailed
    beforeViolations = ObjectUpdateDiagnostics.readerViolations
    beforeBogusLengths = ObjectUpdateDiagnostics.extraParamBogusLengths
}

// ---------------------------------------------------------------------------
// Phase 2.9: is a prim that is in the Scene actually inside the picture?
//
// The phase has to settle one question without a device: given the camera that
// was handed to Filament and the transform the entity was built from, is the
// object inside the frustum — and do two independent computations agree? These
// checks pin the frustum maths, the transform read-back, the audit text and the
// two reversible instruments (the probe and the camera lock).
// ---------------------------------------------------------------------------

/** The frustum maths, one failing condition at a time. */
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
private fun parentParserCheck(): String {
    val model = WorldModel()
    val beforeNonZero = ObjectUpdateDiagnostics.parentNonZeroValues
    val beforeKept = ObjectUpdateDiagnostics.parentKeptWithoutField
    val beforeNow = ObjectUpdateDiagnostics.parentNowCount()

    // 1: un comprimido que SI lleva ParentID lo entrega.
    ObjectUpdateDecoder.applyCompressed(
        compressedMessage(compressedBlob(localId = 4242, parentId = 700)),
        model
    )
    val first = model.get(4242) ?: return "el objeto no se creo"
    if (first.parentId != 700) return "el ParentID del comprimido no llego: " + first.parentId
    if (ObjectUpdateDiagnostics.parentNonZeroValues != beforeNonZero + 1) {
        return "no se conto el ParentID != 0"
    }
    if (ObjectUpdateDiagnostics.parentNowCount() != beforeNow + 1) {
        return "el objeto no quedo contado como 'con parent'"
    }

    // 2: un terse no lleva el campo y no debe tocarlo.
    ObjectUpdateDecoder.applyTerse(terseMessage(terseBlob(4242, Vector3(5f, 6f, 7f))), model)
    if (first.parentId != 700) return "el terse borro el parent: " + first.parentId

    // 3: un comprimido SIN el bloque no dice nada del parent: el parent que ya
    //    estaba se conserva (fase 2.11). Antes se escribia 0 aqui, y ese era el
    //    unico punto del decode donde un parent ya conocido podia desaparecer.
    ObjectUpdateDecoder.applyCompressed(
        compressedMessage(compressedBlob(localId = 4242, position = Vector3(1f, 2f, 3f))),
        model
    )
    if (first.parentId != 700) return "el comprimido sin bloque borro el parent: " + first.parentId
    if (ObjectUpdateDiagnostics.parentKeptWithoutField != beforeKept + 1) {
        return "no se conto el parent conservado sin el campo"
    }
    if (ObjectUpdateDiagnostics.parentNowCount() != beforeNow + 1) {
        return "el objeto dejo de estar contado como 'con parent'"
    }

    // 4: un comprimido que SI trae el campo con valor 0 desvincula: eso no es
    //    silencio, es la region diciendo que el objeto ya no tiene parent.
    ObjectUpdateDecoder.applyCompressed(
        compressedMessage(compressedBlob(localId = 4242, parentId = 0, carryParent = true)),
        model
    )
    if (first.parentId != 0) return "el comprimido con ParentID=0 no desvinculo: " + first.parentId
    if (ObjectUpdateDiagnostics.parentZeroValues < 1) return "no se conto el ParentID 0 explicito"
    if (ObjectUpdateDiagnostics.parentNowCount() != beforeNow) {
        return "el contador de 'con parent' no volvio a su valor"
    }
    val ledger = ObjectUpdateDiagnostics.parentLedgerLines().joinToString("\n")
    if (!ledger.contains("#4242")) return "el objeto no aparece en el ledger:\n" + ledger
    if (!ledger.contains("-> 700")) return "no registra la aparicion del parent:\n" + ledger
    if (!ledger.contains("parent 700 -> 0")) return "no registra el desvinculado:\n" + ledger
    return "OK"
}

/**
 * La cadena completa con dos hijos (fase 2.11): el `Transform` que la escena
 * construye tiene que llevar el `EntityHandle` de su parent, el motor tiene que
 * recibir la relacion, y el hijo tiene que conservar su TRS LOCAL. Se entregan en
 * orden inverso (nieto, hijo, raiz) para probar que el resultado no depende del
 * orden en que llegan los updates.
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
private fun benchmarkStateCheck(): String {
    val d = RenderDiagnostics()
    fun state(): String {
        val line = d.lines().firstOrNull { it.startsWith("Benchmark state ") }
            ?: return "SIN LINEA"
        return line.removePrefix("Benchmark state ").substringBefore(" ")
    }
    if (state() == "SIN LINEA") return "el informe no publica el estado del benchmark"

    // A: el estado por defecto.
    d.cullingEnabled = true
    d.distanceCullingEnabled = true
    d.shadowingEnabled = true
    d.shadowLodDistance = 0f
    if (state() != "A") return "por defecto deberia ser A y dice " + state()

    // B: culling OFF.
    d.cullingEnabled = false
    if (state() != "B") return "culling OFF deberia ser B y dice " + state()

    // C: distancia OFF.
    d.cullingEnabled = true
    d.distanceCullingEnabled = false
    if (state() != "C") return "distancia OFF deberia ser C y dice " + state()

    // D: sombras OFF.
    d.distanceCullingEnabled = true
    d.shadowingEnabled = false
    if (state() != "D") return "sombras OFF deberia ser D y dice " + state()

    // Fuera del benchmark: con el LOD encendido no es uno de los cuatro estados.
    d.shadowingEnabled = true
    d.shadowLodDistance = 40f
    if (state() != "-") return "con LOD ON no deberia etiquetarse como A/B/C/D y dice " + state()
    return "OK"
}

// ---------------------------------------------------------------------------
// Fase 2.13a: el parser completo de TextureEntry
// ---------------------------------------------------------------------------

private fun uuidBytes(uuid: String): ByteArray {
    val out = ByteArray(16)
    LLUUIDUtil.toBytes(uuid, out, 0)
    return out
}

/** `LLPrimitive::packTEField`: un campo = valor por defecto + grupos de excepcion. */
private fun packFaceList(
    out: java.io.ByteArrayOutputStream,
    size: Int,
    count: Int,
    value: (Int) -> ByteArray
) {
    if (count <= 0) {
        return
    }
    val values = (0 until count).map(value)
    val last = count - 1
    // El "default" que LL escribe es el valor de la ULTIMA cara.
    out.write(values[last], 0, size)
    for (face in last - 1 downTo 0) {
        var alreadySent = false
        for (i in face + 1..last) {
            if (values[face].contentEquals(values[i])) {
                alreadySent = true
                break
            }
        }
        if (alreadySent) {
            continue
        }
        var flags = 0
        for (i in face downTo 0) {
            if (values[face].contentEquals(values[i])) {
                flags = flags or (1 shl i)
            }
        }
        writeTeVarint(out, flags)
        out.write(values[face], 0, size)
    }
    out.write(0)
}

/** El bitfield de excepciones: trozos de 7 bits, el mas alto primero, MSB = continuo. */
private fun writeTeVarint(out: java.io.ByteArrayOutputStream, flags: Int) {
    val chunks = ArrayList<Int>()
    var value = flags
    chunks.add(value and 0x7F)
    value = value ushr 7
    while (value != 0) {
        chunks.add(value and 0x7F)
        value = value ushr 7
    }
    for (i in chunks.indices.reversed()) {
        out.write(chunks[i] or (if (i > 0) 0x80 else 0x00))
    }
}

private fun teColorBytes(argb: Int): ByteArray {
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    val a = (argb ushr 24) and 0xFF
    return byteArrayOf((255 - r).toByte(), (255 - g).toByte(), (255 - b).toByte(), (255 - a).toByte())
}

private fun teFloatBytes(value: Float): ByteArray {
    val bits = java.lang.Float.floatToIntBits(value)
    return byteArrayOf(
        (bits and 0xFF).toByte(),
        ((bits ushr 8) and 0xFF).toByte(),
        ((bits ushr 16) and 0xFF).toByte(),
        ((bits ushr 24) and 0xFF).toByte()
    )
}

private fun teOffsetBytes(value: Float): ByteArray {
    val packed = java.lang.Math.round(value.coerceIn(-1f, 1f) * 32767f)
    return byteArrayOf((packed and 0xFF).toByte(), ((packed ushr 8) and 0xFF).toByte())
}

private fun teRotationBytes(value: Float): ByteArray {
    val turns = value / (2f * Math.PI.toFloat())
    val packed = java.lang.Math.round(turns * 32768f)
    return byteArrayOf((packed and 0xFF).toByte(), ((packed ushr 8) and 0xFF).toByte())
}

/**
 * Un `TextureEntry` completo, empaquetado exactamente como `LLPrimitive::
 * packTEMessage`: once campos, cada uno un valor por defecto y sus excepciones.
 */
private fun buildTextureEntry(
    count: Int,
    texture: (Int) -> String,
    color: (Int) -> Int = { 0xFFFFFFFF.toInt() },
    scaleU: (Int) -> Float = { 1f },
    scaleV: (Int) -> Float = { 1f },
    offsetU: (Int) -> Float = { 0f },
    offsetV: (Int) -> Float = { 0f },
    rotation: (Int) -> Float = { 0f },
    bump: (Int) -> Int = { 0 },
    media: (Int) -> Int = { 0 },
    glow: (Int) -> Float = { 0f },
    material: (Int) -> String = { LLUUIDUtil.ZERO }
): ByteArray {
    val out = java.io.ByteArrayOutputStream(256)
    packFaceList(out, 16, count) { uuidBytes(texture(it)) }
    packFaceList(out, 4, count) { teColorBytes(color(it)) }
    packFaceList(out, 4, count) { teFloatBytes(scaleU(it)) }
    packFaceList(out, 4, count) { teFloatBytes(scaleV(it)) }
    packFaceList(out, 2, count) { teOffsetBytes(offsetU(it)) }
    packFaceList(out, 2, count) { teOffsetBytes(offsetV(it)) }
    packFaceList(out, 2, count) { teRotationBytes(rotation(it)) }
    packFaceList(out, 1, count) { byteArrayOf(bump(it).toByte()) }
    packFaceList(out, 1, count) { byteArrayOf(media(it).toByte()) }
    packFaceList(out, 1, count) { byteArrayOf(java.lang.Math.round(glow(it) * 255f).toByte()) }
    packFaceList(out, 16, count) { uuidBytes(material(it)) }
    return out.toByteArray()
}

/**
 * Fase 2.13a: el parser de `TextureEntry`.
 *
 * Los blobs se construyen con un empaquetador que reproduce `packTEField` de
 * Linden Lab (default = ultima cara, grupos de excepcion con bitfield de 7 bits
 * y terminador 0), asi que lo que se prueba es el formato real, no uno inventado
 * por el test.
 */
private fun textureEntryParseCheck(): String {
    val a = "11111111-2222-3333-4444-555555555555"
    val b = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    val mat = "01234567-89ab-cdef-0123-456789abcdef"

    // 1: un blob vacio o mas corto que el UUID por defecto no es una entrada.
    if (TextureEntry.parse(ByteArray(0)) != null) return "un blob vacio deberia dar null"
    if (TextureEntry.parse(ByteArray(15)) != null) return "15 bytes deberian dar null"

    // 2: solo el UUID por defecto (16 bytes): bien formado y con menos campos de
    //    los que escribe la region, pero no truncado.
    //    (La clasificacion de "corto" se comprueba en `texclass`.)
    val legacy = TextureEntry.parse(uuidBytes(a)) ?: return "16 bytes deberian parsearse"
    if (legacy.parsedFields != 1) return "16 bytes: campos " + legacy.parsedFields
    if (legacy.truncated) return "16 bytes no es un truncamiento: " + legacy
    if (!legacy.belowWriterMinimum) return "16 bytes deberia marcarse con campos de menos"
    if (legacy.face(0).textureId != a) return "16 bytes: textura " + legacy.face(0).textureId
    if (legacy.face(0).colorArgb != 0xFFFFFFFF.toInt()) return "16 bytes: tinte por defecto"
    if (legacy.face(0).scaleU != 1f) return "16 bytes: la escala por defecto deberia ser 1"

    // 3: 16 bytes a cero: el objeto existe pero no tiene textura.
    val zero = TextureEntry.parse(ByteArray(16)) ?: return "16 ceros deberian parsearse"
    if (zero.face(0).textureId != LLUUIDUtil.ZERO) return "16 ceros: " + zero.face(0).textureId
    if (zero.face(0).hasTint) return "16 ceros no deberia contar como tinte"

    // 4: una entrada completa de una sola cara (todo por defecto).
    val one = buildTextureEntry(1, texture = { a })
    val oneEntry = TextureEntry.parse(one) ?: return "la entrada de una cara no parsea"
    if (oneEntry.parsedFields != 11) return "campos " + oneEntry.parsedFields + " (esperado 11)"
    if (oneEntry.truncated) return "no deberia estar truncada: " + oneEntry
    val f0 = oneEntry.face(0)
    if (f0.textureId != a) return "textura " + f0.textureId
    if (f0.colorArgb != 0xFFFFFFFF.toInt()) return "tinte 0x" + Integer.toHexString(f0.colorArgb)
    if (f0.scaleU != 1f || f0.scaleV != 1f) return "escala " + f0.scaleU + "x" + f0.scaleV
    if (f0.offsetU != 0f || f0.offsetV != 0f) return "offset " + f0.offsetU + "/" + f0.offsetV
    if (f0.rotation != 0f) return "rotacion " + f0.rotation
    if (f0.fullBright || f0.glow != 0f || f0.bumpCode != 0) return "bump/glow inesperados: " + f0
    if (f0.materialId != LLUUIDUtil.ZERO) return "material " + f0.materialId

    // 5: ocho caras: la 0 con otra textura, la 2 tintada, la 3 con bump+glow,
    //    la 4 con UV y la 5 con material de render.
    val red = 0xFFFF0000.toInt()
    val mixed = buildTextureEntry(
        8,
        texture = { if (it == 0) a else b },
        color = { if (it == 2) red else 0xFFFFFFFF.toInt() },
        scaleU = { if (it == 4) 2f else 1f },
        offsetU = { if (it == 4) 0.25f else 0f },
        bump = { if (it == 3) (1 shl 5) else 0 },
        glow = { if (it == 3) 0.5f else 0f },
        material = { if (it == 5) mat else LLUUIDUtil.ZERO }
    )
    val mixedEntry = TextureEntry.parse(mixed) ?: return "la entrada mixta no parsea"
    if (mixedEntry.parsedFields != 11) return "mixta: campos " + mixedEntry.parsedFields
    if (mixedEntry.face(0).textureId != a) return "cara 0: " + mixedEntry.face(0).textureId
    for (face in 1 until 8) {
        if (mixedEntry.face(face).textureId != b) {
            return "cara " + face + ": " + mixedEntry.face(face).textureId
        }
    }
    if (mixedEntry.face(0).colorArgb != 0xFFFFFFFF.toInt()) return "cara 0 no deberia estar tintada"
    if (mixedEntry.face(2).colorArgb != red) {
        return "cara 2: 0x" + Integer.toHexString(mixedEntry.face(2).colorArgb)
    }
    if (mixedEntry.face(1).colorArgb != 0xFFFFFFFF.toInt()) return "cara 1 no deberia estar tintada"
    if (!mixedEntry.face(3).fullBright) return "cara 3 deberia ser fullbright"
    if (abs(mixedEntry.face(3).glow - 0.5f) > 0.02f) return "glow de la cara 3: " + mixedEntry.face(3).glow
    if (mixedEntry.face(3).bumpCode != 0) return "cara 3: bump " + mixedEntry.face(3).bumpCode
    if (mixedEntry.face(4).scaleU != 2f) return "cara 4 scaleU " + mixedEntry.face(4).scaleU
    if (abs(mixedEntry.face(4).offsetU - 0.25f) > 0.002f) return "cara 4 offsetU " + mixedEntry.face(4).offsetU
    if (mixedEntry.face(5).materialId != mat) return "cara 5 material " + mixedEntry.face(5).materialId
    if (mixedEntry.face(6).materialId != LLUUIDUtil.ZERO) return "cara 6 no deberia tener material"
    if (mixedEntry.texturedFaces != 1) return "caras con textura propia " + mixedEntry.texturedFaces
    if (mixedEntry.tintedFaces != 1) return "caras con tinte propio " + mixedEntry.tintedFaces
    if (mixedEntry.highestNamedFace != 5) return "cara mas alta nombrada " + mixedEntry.highestNamedFace

    // 6: bitfield de mas de 7 bits: la cara 9 sola obliga a dos bytes de varint.
    val wide = buildTextureEntry(12, texture = { if (it == 9) a else b })
    val wideEntry = TextureEntry.parse(wide) ?: return "12 caras no parsea"
    if (wideEntry.face(9).textureId != a) return "cara 9: " + wideEntry.face(9).textureId
    if (wideEntry.face(8).textureId != b) return "cara 8: " + wideEntry.face(8).textureId
    if (wideEntry.face(11).textureId != b) return "cara 11: " + wideEntry.face(11).textureId

    // 7: rotacion (π/2) y alpha.
    val rotated = TextureEntry.parse(
        buildTextureEntry(1, { a }, rotation = { (Math.PI / 2).toFloat() })
    ) ?: return "la entrada rotada no parsea"
    if (abs(rotated.face(0).rotation - (Math.PI / 2).toFloat()) > 0.001f) {
        return "rotacion " + rotated.face(0).rotation
    }
    val half = TextureEntry.parse(buildTextureEntry(1, { a }, color = { 0x80FFFFFF.toInt() }))
        ?: return "la entrada con alpha no parsea"
    if (((half.face(0).colorArgb ushr 24) and 0xFF) != 0x80) {
        return "alpha " + Integer.toHexString(half.face(0).colorArgb)
    }
    if (!half.face(0).hasTint) return "alpha 128 deberia contar como tinte"

    // 8: un blob cortado a mitad de campo se parsea hasta donde llega y lo dice.
    val cut = TextureEntry.parse(one.copyOfRange(0, 18)) ?: return "un blob cortado deberia parsearse"
    if (!cut.truncated) return "el blob cortado deberia marcarse truncado: " + cut
    if (cut.stoppedAt != "tinte") return "el corte deberia parar en el campo 'tinte', no en '" + cut.stoppedAt + "'"
    if (cut.parsedFields != 1) return "el blob cortado deberia conservar 1 campo, no " + cut.parsedFields
    if (cut.face(0).textureId != a) return "el campo leido antes del corte se perdio"
    // Un corte dentro del primer campo deja menos de un UUID: no hay entrada.
    if (TextureEntry.parse(one.copyOfRange(0, 10)) != null) return "un blob de 10 bytes deberia dar null"

    // Ejemplo trabajado que el informe de la fase cita (va al log, no es una
    // asercion): el blob de 8 caras del caso 5, campo a campo.
    val demo = StringBuilder()
    demo.append("TEXTUREENTRY EJEMPLO (8 caras, ").append(mixed.size).append(" bytes)")
    demo.append("\n  campos leidos=").append(mixedEntry.parsedFields).append("/11")
    demo.append(" truncada=").append(if (mixedEntry.truncated) "SI" else "no")
    demo.append(" caras nombradas=").append(mixedEntry.highestNamedFace + 1)
    demo.append(" texturas propias=").append(mixedEntry.texturedFaces)
    demo.append(" tintes propios=").append(mixedEntry.tintedFaces)
    demo.append(" bump propios=").append(mixedEntry.bumpedFaces)
    for (face in intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)) {
        val f = mixedEntry.face(face)
        demo.append("\n  cara ").append(face)
            .append(": tex=").append(f.textureId)
            .append(" color=#").append(Integer.toHexString(f.colorArgb).uppercase())
            .append(" uv=").append(f.scaleU).append("x").append(f.scaleV)
            .append("+").append(f.offsetU).append("+").append(f.offsetV)
            .append(" rot=").append(f.rotation)
            .append(" bump=0x").append(Integer.toHexString(f.bump))
            .append(" fullbright=").append(if (f.fullBright) "SI" else "no")
            .append(" glow=").append(f.glow)
            .append(" mat=").append(if (f.materialId == LLUUIDUtil.ZERO) "-" else f.materialId)
    }
    println(demo.toString())
    return "OK"
}

/** Un `ImprovedTerseObjectUpdate` cuyo bloque lleva ademas el `TextureEntry`. */
private fun terseMessageWithTexture(
    localId: Int,
    position: Vector3,
    textureEntry: ByteArray
): SLMessage {
    val def = MessageDef("ImprovedTerseObjectUpdate", Frequency.HIGH, 15, false, false, emptyList())
    val message = SLMessage(def)
    val block = message.addBlock("ObjectData")
    block.set("Data", terseBlob(localId, position))
    block.set("TextureEntry", textureEntry)
    return message
}

/** Un `ObjectUpdate` minimo con el `TextureEntry` como campo con nombre. */
private fun fullObjectUpdate(localId: Int, textureEntry: ByteArray): SLMessage {
    val def = MessageDef("ObjectUpdate", Frequency.HIGH, 12, false, false, emptyList())
    val message = SLMessage(def)
    val block = message.addBlock("ObjectData")
    block.set("ID", localId)
    block.set("FullID", LLUUIDUtil.ZERO)
    block.set("PCode", SceneObject.PCODE_PRIM)
    block.set("Material", 3)
    block.set("Scale", Vector3(1f, 1f, 1f))
    block.set("PathCurve", 0x10)
    block.set("ProfileCurve", 0x00)
    block.set("ObjectData", ByteArray(60))
    block.set("ExtraParams", byteArrayOf(0))
    block.set("TextureEntry", textureEntry)
    block.set("OwnerID", LLUUIDUtil.ZERO)
    block.set("Text", ByteArray(0))
    block.set("NameValue", ByteArray(0))
    return message
}

/**
 * Fase 2.13a: los TRES caminos del decodificador tienen que entregar el mismo
 * `TextureEntry` decodificado, conservar su tamaño, y no inventar textura cuando
 * el blob no la lleva.
 */
private fun textureEntryDecoderCheck(): String {
    val uuid = "12345678-9abc-def0-1234-56789abcdef0"
    val blob = buildTextureEntry(1, texture = { uuid })
    val expectedSize = blob.size

    // 1: ObjectUpdateCompressed (longitud de 4 bytes delante del TextureEntry).
    val compressedModel = WorldModel()
    ObjectUpdateDecoder.applyCompressed(
        compressedMessage(compressedBlob(localId = 4242, textureEntry = blob)),
        compressedModel
    )
    val compressed = compressedModel.get(4242) ?: return "el comprimido no creo el objeto"
    if (compressed.textureEntrySize != expectedSize) {
        return "comprimido: tamaño " + compressed.textureEntrySize + " (esperado " + expectedSize + ")"
    }
    val compressedEntry = compressed.textureEntry ?: return "comprimido: sin TextureEntry decodificado"
    if (compressedEntry.parsedFields != 11) return "comprimido: campos " + compressedEntry.parsedFields
    if (compressedEntry.face(0).textureId != uuid) return "comprimido: " + compressedEntry.face(0).textureId
    if (compressed.textureId != uuid) return "comprimido: textureId " + compressed.textureId

    // 2: ImprovedTerseObjectUpdate (el TextureEntry es un campo del bloque).
    val terseModel = WorldModel()
    ObjectUpdateDecoder.applyTerse(
        terseMessageWithTexture(5150, Vector3(1f, 2f, 3f), blob),
        terseModel
    )
    val terse = terseModel.get(5150) ?: return "el terse no creo el objeto"
    if (terse.textureEntrySize != expectedSize) return "terse: tamaño " + terse.textureEntrySize
    if (terse.textureEntry == null) return "terse: sin TextureEntry decodificado"
    if (terse.textureId != uuid) return "terse: textureId " + terse.textureId

    // 3: ObjectUpdate (campos con nombre).
    val fullModel = WorldModel()
    ObjectUpdateDecoder.applyFull(fullObjectUpdate(6001, blob), fullModel)
    val full = fullModel.get(6001) ?: return "el completo no creo el objeto"
    if (full.textureEntrySize != expectedSize) return "completo: tamaño " + full.textureEntrySize
    val fullEntry = full.textureEntry ?: return "completo: sin TextureEntry decodificado"
    if (fullEntry.face(0).textureId != uuid) return "completo: " + fullEntry.face(0).textureId

    // 4: un terse SIN TextureEntry deja el que ya tenia; un blob ilegible no
    //    inventa textura ni impide crear el objeto.
    val keepModel = WorldModel()
    ObjectUpdateDecoder.applyTerse(terseMessageWithTexture(5150, Vector3(1f, 2f, 3f), blob), keepModel)
    ObjectUpdateDecoder.applyTerse(
        terseMessageWithTexture(5150, Vector3(9f, 9f, 9f), byteArrayOf(0)),
        keepModel
    )
    val kept = keepModel.get(5150) ?: return "el objeto desaparecio"
    if (kept.textureId != uuid) return "un terse ilegible borro la textura: " + kept.textureId

    val emptyModel = WorldModel()
    ObjectUpdateDecoder.applyCompressed(
        compressedMessage(compressedBlob(localId = 7000, textureEntry = byteArrayOf(0))),
        emptyModel
    )
    val empty = emptyModel.get(7000) ?: return "sin TextureEntry no deberia impedir el objeto"
    if (empty.textureEntry != null) return "1 byte no deberia decodificar una entrada"
    if (empty.textureId.isNotEmpty()) return "1 byte no deberia dar textura"
    return "OK"
}

/**
 * Revision de 2.13a: clasificar un blob corto, en vez de contarlo.
 *
 * La pregunta que responde es la del informe: `truncado` solo puede significar
 * "el blob termino dentro de un campo". El empaquetador de la region
 * (`LLPrimitive::packTEMessage`) escribe siempre los once campos, con el material
 * al final y sin terminador; el lector (`parseTEMessage`) es mas tolerante:
 * acepta cualquier prefijo que acabe en el limite de un campo y, si el material
 * falta o esta a medias, sigue sin el. Asi que hay tres cosas distintas que un
 * contador unico mezclaba: entrada completa, prefijo bien formado con menos
 * campos de los que escribe la region (NUNCA truncada) y corte real dentro de un
 * campo. Cada caso tiene que decir ademas **donde** paro.
 */
private fun textureEntryClassificationCheck(): String {
    val a = "11111111-2222-3333-4444-555555555555"
    val b = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    if (TextureEntry.FULL_WIRE_SIZE != 63) return "FULL_WIRE_SIZE " + TextureEntry.FULL_WIRE_SIZE
    if (TextureEntry.WRITER_MIN_WIRE_SIZE != 63) {
        return "WRITER_MIN_WIRE_SIZE " + TextureEntry.WRITER_MIN_WIRE_SIZE
    }
    if (TextureEntry.WRITER_MIN_FIELDS != 11) return "WRITER_MIN_FIELDS " + TextureEntry.WRITER_MIN_FIELDS
    if (TextureEntry.OPTIONAL_FIELD_INDEX != 10) {
        return "OPTIONAL_FIELD_INDEX " + TextureEntry.OPTIONAL_FIELD_INDEX
    }
    if (TextureEntry.fieldName(10) != "material") return "fieldName(10) " + TextureEntry.fieldName(10)
    if (TextureEntry.fieldSize(10) != 16) return "fieldSize(10) " + TextureEntry.fieldSize(10)

    // 1: la entrada completa de once campos.
    val full = buildTextureEntry(1, texture = { a })
    val fullEntry = TextureEntry.parse(full) ?: return "la entrada completa no parsea"
    if (full.size < TextureEntry.FULL_WIRE_SIZE) {
        return "una entrada de once campos mide al menos " + TextureEntry.FULL_WIRE_SIZE + ", no " + full.size
    }
    if (fullEntry.size != full.size) return "completa: size " + fullEntry.size
    if (fullEntry.parsedFields != 11) return "completa: campos " + fullEntry.parsedFields
    if (fullEntry.truncated) return "completa: truncada en " + fullEntry.stoppedAt
    if (fullEntry.stop != TextureEntry.StopReason.COMPLETE) return "completa: parada " + fullEntry.stop
    if (fullEntry.stopFieldIndex != -1) return "completa: indice " + fullEntry.stopFieldIndex
    if (fullEntry.belowWriterMinimum) return "los once campos son los que escribe la region"
    if (fullEntry.fieldNamesRead.size != 11) return "completa: nombres " + fullEntry.fieldNamesRead.size

    // 2: diez campos, sin material: el lector lo acepta (el material es opcional),
    //    pero el emisor de la region escribe siempre los once, asi que la entrada
    //    es "campos de menos" y NO un truncamiento.
    val ten = full.copyOfRange(0, full.size - 17)
    if (ten.size != 47) return "diez campos + separadores deberia medir 47, mide " + ten.size
    val tenEntry = TextureEntry.parse(ten) ?: return "la entrada de diez campos no parsea"
    if (tenEntry.parsedFields != 10) return "diez campos: leidos " + tenEntry.parsedFields
    if (tenEntry.truncated) return "diez campos NO es truncado: paro en " + tenEntry.stoppedAt
    if (tenEntry.stop != TextureEntry.StopReason.COMPLETE) return "diez campos: parada " + tenEntry.stop
    if (!tenEntry.belowWriterMinimum) {
        return "diez campos deberia marcarse como campos de menos (la region escribe 11)"
    }
    if (tenEntry.face(0).textureId != a) return "diez campos: textura " + tenEntry.face(0).textureId

    //    Y sin el terminador de `glow` (el ultimo campo no esta terminado en el
    //    formato): tampoco puede cambiar la clasificacion.
    val tenNoTail = ten.copyOfRange(0, ten.size - 1)
    val tenTail = TextureEntry.parse(tenNoTail) ?: return "diez campos sin terminador final no parsea"
    if (tenTail.truncated) return "diez campos sin terminador final es truncado: " + tenTail.stoppedAt
    if (tenTail.parsedFields != 10) return "diez campos sin terminador final: " + tenTail.parsedFields

    // 3: solo el UUID por defecto (16) y su terminador (1): bien formado, pero
    //    muy por debajo de lo que escribe la region. NO es un truncamiento.
    val uuidOnly = TextureEntry.parse(uuidBytes(a) + byteArrayOf(0)) ?: return "17 bytes deberia parsearse"
    if (uuidOnly.size != 17) return "solo UUID: size " + uuidOnly.size
    if (uuidOnly.parsedFields != 1) return "solo UUID: campos " + uuidOnly.parsedFields
    if (uuidOnly.truncated) return "solo UUID no es truncado: " + uuidOnly.stoppedAt
    if (!uuidOnly.belowWriterMinimum) return "solo UUID deberia marcarse con campos de menos"
    if (uuidOnly.face(0).textureId != a) return "solo UUID: textura " + uuidOnly.face(0).textureId

    // 4: cortado en el valor por defecto del campo 2 (`tinte`).
    val cutTint = TextureEntry.parse(full.copyOfRange(0, 18)) ?: return "18 bytes deberia parsearse"
    if (!cutTint.truncated) return "18 bytes deberia estar truncado"
    if (cutTint.stop != TextureEntry.StopReason.DEFAULT_INCOMPLETE) return "18 bytes: parada " + cutTint.stop
    if (cutTint.stopFieldIndex != 1) return "18 bytes: campo " + cutTint.stopFieldIndex
    if (cutTint.stoppedAt != "tinte") return "18 bytes: paro en '" + cutTint.stoppedAt + "'"
    if (cutTint.stopExpectedSize != 4) return "18 bytes: esperaba " + cutTint.stopExpectedSize
    if (cutTint.stopRemaining != 1) return "18 bytes: quedaba " + cutTint.stopRemaining
    if (cutTint.parsedFields != 1) return "18 bytes: campos " + cutTint.parsedFields

    // 5: cortado dentro del ULTIMO campo (`material`): diez campos leidos y el
    //    material a medias. Esta es la firma de una entrada recortada por el
    //    final, que es como se ve una longitud declarada mayor que los bytes que
    //    quedan.
    val cutMaterial = TextureEntry.parse(full.copyOfRange(0, full.size - 3))
        ?: return "la entrada sin sus ultimos 3 bytes no parsea"
    if (!cutMaterial.truncated) return "cortar el material deberia truncar"
    if (cutMaterial.stopFieldIndex != 10) return "material: campo " + cutMaterial.stopFieldIndex
    if (cutMaterial.stoppedAt != "material") return "material: paro en '" + cutMaterial.stoppedAt + "'"
    if (cutMaterial.stopExpectedSize != 16) return "material: esperaba " + cutMaterial.stopExpectedSize
    if (cutMaterial.stopRemaining != 14) return "material: quedaban " + cutMaterial.stopRemaining
    if (cutMaterial.parsedFields != 10) return "material: campos " + cutMaterial.parsedFields

    // 6: cortado dentro del valor de una excepcion (cara 0 con otra textura).
    val twoFaces = buildTextureEntry(2, texture = { if (it == 0) a else b })
    val cutException = TextureEntry.parse(twoFaces.copyOfRange(0, 16 + 1 + 3))
        ?: return "el corte dentro de una excepcion deberia parsearse"
    if (!cutException.truncated) return "el corte dentro de una excepcion deberia truncar"
    if (cutException.stop != TextureEntry.StopReason.EXCEPTION_INCOMPLETE) {
        return "excepcion: parada " + cutException.stop
    }
    if (cutException.parsedFields != 0) return "excepcion: campos " + cutException.parsedFields
    if (cutException.stopRemaining != 3) return "excepcion: quedaban " + cutException.stopRemaining

    // 7: cortado dentro del bitfield que introduce una excepcion (dos bytes de
    //    varint, solo el primero presente).
    val twelve = buildTextureEntry(12, texture = { if (it == 9) a else b })
    val cutBitfield = TextureEntry.parse(twelve.copyOfRange(0, 16 + 1))
        ?: return "el corte dentro del bitfield deberia parsearse"
    if (!cutBitfield.truncated) return "el corte dentro del bitfield deberia truncar"
    if (cutBitfield.stop != TextureEntry.StopReason.BITFIELD_INCOMPLETE) {
        return "bitfield: parada " + cutBitfield.stop
    }
    if (cutBitfield.parsedFields != 0) return "bitfield: campos " + cutBitfield.parsedFields

    // 8: menos de 16 bytes no es una entrada.
    if (TextureEntry.parse(ByteArray(15)) != null) return "15 bytes deberia dar null"
    if (TextureEntry.parse(ByteArray(0)) != null) return "0 bytes deberia dar null"

    val demo = StringBuilder()
    demo.append("TEXTUREENTRY CLASIFICACION (revision 2.13a)")
    demo.append("\n  completo (11 campos): ").append(full.size).append(" bytes")
    demo.append("\n  bien formada con 10 campos (material opcional ausente): ").append(ten.size)
        .append(" bytes (el emisor de la region siempre escribe ")
        .append(TextureEntry.WRITER_MIN_FIELDS).append("; no es truncamiento)")
    demo.append("\n  solo UUID + terminador: ").append(uuidOnly.size).append(" bytes, ")
        .append(uuidOnly.parsedFields).append(" campo, campos de menos=")
        .append(uuidOnly.belowWriterMinimum)
    demo.append("\n  cortado en '").append(cutTint.stoppedAt).append("': ").append(cutTint.size)
        .append(" bytes, campos=").append(cutTint.parsedFields).append(", quedaban ")
        .append(cutTint.stopRemaining).append(" de ").append(cutTint.stopExpectedSize)
    demo.append("\n  cortado en '").append(cutMaterial.stoppedAt).append("' (campo ")
        .append(cutMaterial.stopFieldIndex).append(", ")
        .append(if (cutMaterial.stopFieldIndex == TextureEntry.OPTIONAL_FIELD_INDEX)
            "el OPCIONAL: el lector de referencia lo aceptaria sin material" else "obligatorio")
        .append("): ").append(cutMaterial.size)
        .append(" bytes, campos=").append(cutMaterial.parsedFields).append(", quedaban ")
        .append(cutMaterial.stopRemaining).append(" de ").append(cutMaterial.stopExpectedSize)
    demo.append("\n  cortado en una excepcion: campos=").append(cutException.parsedFields)
        .append(", quedaban ").append(cutException.stopRemaining)
        .append(", parada=").append(cutException.stop)
    demo.append("\n  cortado en el bitfield: campos=").append(cutBitfield.parsedFields)
        .append(", parada=").append(cutBitfield.stop)
    println(demo.toString())
    return "OK"
}

/**
 * Revision de 2.13a: el reparto del diagnostico.
 *
 * El mismo blob corto significa cosas distintas segun el camino que lo trajo, y
 * ni una ausencia legal (un update sin entrada) ni una entrada corta bien formada
 * pueden mover el contador de truncados. Se comprueba con los tres caminos
 * reales del decodificador, y con las muestras que el informe imprime.
 */
private fun textureEntryDiagnosticsCheck(): String {
    val uuid = "12345678-9abc-def0-1234-56789abcdef0"
    val full = buildTextureEntry(1, texture = { uuid })

    fun hexOf(bytes: ByteArray): String = bytes.joinToString(" ") { b ->
        val value = b.toInt() and 0xFF
        val digits = "0123456789abcdef"
        "" + digits[value shr 4] + digits[value and 0x0F]
    }

    ObjectUpdateDiagnostics.reset()

    // 1: un comprimido cuya seccion de textura declara mas bytes de los que
    //    quedan. Es el recorte real: el decodificador entrega los bytes que hay,
    //    la entrada se lee hasta donde llega (diez campos y el material a medias)
    //    y la longitud imposible queda reportada aparte.
    val cutBlob = compressedBlob(localId = 8001, textureEntry = full)
    val cut = cutBlob.copyOfRange(0, cutBlob.size - 3)
    val expectedSize = full.size - 3
    val cutModel = WorldModel()
    ObjectUpdateDecoder.applyCompressed(compressedMessage(cut), cutModel)
    if (ObjectUpdateDiagnostics.textureEntriesTruncated != 1) {
        return "comprimido recortado: truncados " + ObjectUpdateDiagnostics.textureEntriesTruncated
    }
    if (ObjectUpdateDiagnostics.textureStopsDefaultIncomplete != 1) {
        return "comprimido recortado: paradas por defecto " + ObjectUpdateDiagnostics.textureStopsDefaultIncomplete
    }
    //    La parada es en el campo 11, el UNICO opcional: el lector de referencia
    //    habria aceptado el blob sin material, asi que el informe tiene que decirlo.
    if (ObjectUpdateDiagnostics.textureStopsInOptionalMaterial != 1) {
        return "la parada en el material opcional no se conto (" +
            ObjectUpdateDiagnostics.textureStopsInOptionalMaterial + ")"
    }
    if (ObjectUpdateDiagnostics.textureStopsExceptionIncomplete != 0 ||
        ObjectUpdateDiagnostics.textureStopsBitfieldIncomplete != 0
    ) {
        return "comprimido recortado conto la parada en el sitio equivocado"
    }
    if (ObjectUpdateDiagnostics.textureEntriesBelowWriter != 0) {
        return "una entrada recortada no puede contarse ademas como campos de menos"
    }
    if (ObjectUpdateDiagnostics.bogusSectionLengths != 1) {
        return "el recorte deberia reportar la longitud imposible (" +
            ObjectUpdateDiagnostics.bogusSectionLengths + ")"
    }
    val cutObject = cutModel.get(8001) ?: return "el objeto recortado no se creo"
    if (cutObject.textureEntrySize != expectedSize) {
        return "tamano conservado " + cutObject.textureEntrySize + " (esperado " + expectedSize + ")"
    }
    val cutEntry = cutObject.textureEntry ?: return "la entrada recortada no se guardo"
    if (cutEntry.parsedFields != 10) return "la entrada recortada leyo " + cutEntry.parsedFields + " campos"
    val cutHex = cut.copyOfRange(cut.size - expectedSize, cut.size)

    // 2: un terse con una entrada CORTA pero LEGAL (17 bytes). No es un
    //    truncamiento: es una entrada bien formada con menos campos de los que
    //    escribe la region.
    val short = uuidBytes(uuid) + byteArrayOf(0)
    val shortModel = WorldModel()
    ObjectUpdateDecoder.applyTerse(
        terseMessageWithTexture(8002, Vector3(1f, 2f, 3f), short),
        shortModel
    )
    if (ObjectUpdateDiagnostics.textureEntriesTruncated != 1) {
        return "una entrada corta y legal movio los truncados (" +
            ObjectUpdateDiagnostics.textureEntriesTruncated + ")"
    }
    if (ObjectUpdateDiagnostics.textureEntriesBelowWriter != 1) {
        return "la entrada de 17 bytes no se clasifico como corta (" +
            ObjectUpdateDiagnostics.textureEntriesBelowWriter + ")"
    }
    val shortObject = shortModel.get(8002) ?: return "el objeto corto no se creo"
    if (shortObject.textureId != uuid) return "la entrada corta perdio la textura: " + shortObject.textureId

    // 3: un update SIN entrada (blob vacio): ausencia legal, contada aparte.
    val emptyModel = WorldModel()
    ObjectUpdateDecoder.applyFull(fullObjectUpdate(8003, ByteArray(0)), emptyModel)
    if (ObjectUpdateDiagnostics.textureEntriesEmpty != 1) {
        return "el blob vacio no se conto como ausencia (" + ObjectUpdateDiagnostics.textureEntriesEmpty + ")"
    }
    if (ObjectUpdateDiagnostics.textureEntriesTruncated != 1) {
        return "el blob vacio toco los truncados (" + ObjectUpdateDiagnostics.textureEntriesTruncated + ")"
    }

    // 3b: un terse SIN el campo TextureEntry: silencio legal. Se cuenta aparte y no
    //     entra ni en "demasiado cortos" ni en ninguna categoria de entrada.
    val silenceModel = WorldModel()
    ObjectUpdateDecoder.applyTerse(
        terseMessage(terseBlob(8007, Vector3(0f, 0f, 0f))),
        silenceModel
    )
    if (ObjectUpdateDiagnostics.terseWithoutTextureEntry != 1) {
        return "el terse sin TextureEntry no se conto como silencio (" +
            ObjectUpdateDiagnostics.terseWithoutTextureEntry + ")"
    }
    if (ObjectUpdateDiagnostics.textureEntriesTooShort != 1) {
        return "el terse sin campo toco los cortos (" + ObjectUpdateDiagnostics.textureEntriesTooShort + ")"
    }

    // 3c: categoria A ausente — diez campos (falta solo el material opcional). El
    //     lector de referencia la acepta; no es truncada.
    val ten = full.copyOfRange(0, full.size - 17)
    val tenModel = WorldModel()
    ObjectUpdateDecoder.applyCompressed(
        compressedMessage(compressedBlob(8004, textureEntry = ten)),
        tenModel
    )
    if (ObjectUpdateDiagnostics.textureCategoryMaterialAbsent != 1) {
        return "los diez campos no se contaron como material ausente (" +
            ObjectUpdateDiagnostics.textureCategoryMaterialAbsent + ")"
    }
    if (ObjectUpdateDiagnostics.textureEntriesTruncated != 1) {
        return "diez campos toco los truncados (" + ObjectUpdateDiagnostics.textureEntriesTruncated + ")"
    }

    // 3d: categoria B — entrada legacy bien formada con menos campos que los
    //     obligatorios (solo el UUID por defecto).
    val legacyModel = WorldModel()
    ObjectUpdateDecoder.applyCompressed(
        compressedMessage(compressedBlob(8005, textureEntry = uuidBytes(uuid) + byteArrayOf(0))),
        legacyModel
    )
    if (ObjectUpdateDiagnostics.textureCategoryLegacyFewerFields != 2) {
        return "la entrada legacy no se conto (" +
            ObjectUpdateDiagnostics.textureCategoryLegacyFewerFields + ", esperado 2 con la del terse)"
    }

    // 3e: categoria E — un blob de 8 bytes: ni el UUID cabe. Por el camino FULL,
    //     que no declara longitud, para no confundirlo con un caso de framing.
    val subModel = WorldModel()
    ObjectUpdateDecoder.applyFull(fullObjectUpdate(8006, ByteArray(8)), subModel)
    if (ObjectUpdateDiagnostics.textureCategorySubUuid != 1) {
        return "el blob de 8 bytes no se conto como menor del UUID (" +
            ObjectUpdateDiagnostics.textureCategorySubUuid + ")"
    }
    if (ObjectUpdateDiagnostics.textureEntriesTooShort != 2) {
        return "el blob de 8 bytes no se conto como corto (" +
            ObjectUpdateDiagnostics.textureEntriesTooShort + ")"
    }

    // 3f: categoria D — un comprimido cuya seccion DECLARA 20 bytes (por debajo de
    //     los 63 que escribe siempre la region). El blob de 20 bytes se lee hasta
    //     donde llega (campo obligatorio, categoria C) y la atribucion a framing
    //     queda reportada: no es evidencia sobre el formato de la entrada.
    val framingModel = WorldModel()
    ObjectUpdateDecoder.applyCompressed(
        compressedMessage(compressedBlob(8008, textureEntry = full, declaredTextureLength = 20)),
        framingModel
    )
    // Las otras dos entradas declaraban 47 y 17 bytes: por debajo del minimo del
    // emisor, asi que tambien cuentan como longitudes imposibles (3 en total),
    // mientras que la seccion recortada de 8001 declara mas bytes de los que hay.
    if (ObjectUpdateDiagnostics.impossibleTextureLengths != 3) {
        return "las longitudes imposibles no se detectaron (" +
            ObjectUpdateDiagnostics.impossibleTextureLengths + ")"
    }
    if (ObjectUpdateDiagnostics.bogusSectionLengths != 1) {
        return "el recorte de 8001 deberia seguir contando como longitud bogus (" +
            ObjectUpdateDiagnostics.bogusSectionLengths + ")"
    }
    if (ObjectUpdateDiagnostics.textureCategoryFramingSuspect != 4) {
        return "los blobs tras framing invalido no se atribuyeron (" +
            ObjectUpdateDiagnostics.textureCategoryFramingSuspect + ")"
    }
    if (ObjectUpdateDiagnostics.textureCategoryRequiredIncomplete != 1) {
        return "el corte en campo obligatorio no se conto (" +
            ObjectUpdateDiagnostics.textureCategoryRequiredIncomplete + ")"
    }
    if (ObjectUpdateDiagnostics.textureEntriesTruncated != 2) {
        return "el caso de framing no sumo un truncado (" +
            ObjectUpdateDiagnostics.textureEntriesTruncated + ")"
    }

    // 4: el reparto por origen, la clasificacion A-E y las muestras del informe.
    val lines = ObjectUpdateDiagnostics.lines()
    val report = lines.joinToString("\n")
    if (!report.contains("PARSER TextureEntry por origen:")) return "falta el reparto por origen"
    if (!report.contains("PARSER TextureEntry CLASIFICACION A-E")) {
        return "falta la clasificacion A-E:\n" + report.lines()
            .filter { it.contains("TextureEntry") }.joinToString("\n")
    }
    if (!report.contains("por origen, clasificacion A-E:")) {
        return "falta la clasificacion A-E por origen"
    }
    if (!report.contains("ObjectUpdateCompressed A 2 (ausente 1 + a medias 1)")) {
        return "el comprimido no reparte A como se espera:\n" + report.lines()
            .filter { it.contains("clasificacion A-E") }.joinToString("\n")
    }
    if (!report.contains("ImprovedTerseObjectUpdate A 0 (ausente 0 + a medias 0)")) {
        return "el terse no reparte A como se espera:\n" + report.lines()
            .filter { it.contains("clasificacion A-E") }.joinToString("\n")
    }
    if (!report.contains("B 2")) return "el reparto no dice B 2"
    if (!report.contains("framing (categoria D):")) return "falta la linea de framing"
    if (!report.contains("histograma de truncados: campos 1:1  10:1")) {
        return "el histograma de campos de los truncados no dice 1:1 y 10:1:\n" + report.lines()
            .filter { it.contains("histograma de truncados") }.joinToString("\n")
    }
    if (!report.contains("truncados por campo de parada: tinte:1  material:1")) {
        return "el histograma de campos de parada no dice tinte:1 y material:1:\n" + report.lines()
            .filter { it.contains("campo de parada") }.joinToString("\n")
    }
    if (!report.contains("en el material OPCIONAL")) {
        return "la linea del material opcional no esta en el informe"
    }
    if (!report.contains("menores del UUID (1-15 bytes, categoria E) 1")) {
        return "la linea de demasiado cortos por categoria no esta bien:\n" + report.lines()
            .filter { it.contains("demasiado cortos por categoria") }.joinToString("\n")
    }
    val sampleLines = lines.filter { it.startsWith("PARSER   ") }
    if (sampleLines.isEmpty()) return "no se guardo ninguna muestra"
    val cutSample = sampleLines.firstOrNull { it.contains("prim #8001") }
        ?: return "la muestra del comprimido recortado no esta:\n" + sampleLines.joinToString("\n")
    if (!cutSample.contains("campos=10")) return "la muestra no dice los campos: " + cutSample
    if (!cutSample.contains("hex=" + hexOf(cutHex))) {
        return "la muestra no lleva el volcado completo del blob: " + cutSample
    }
    // Cada muestra tiene que decir ademas que haria el VISOR DE REFERENCIA con
    // esos mismos bytes: 8001 paro en el material opcional, asi que lo aceptaria.
    if (!cutSample.contains("la referencia ACEPTA la entrada")) {
        return "la muestra no dice que haria el visor de referencia: " + cutSample
    }
    val emptySample = sampleLines.firstOrNull { it.contains("prim #8003") }
        ?: return "la muestra del blob vacio no esta:\n" + sampleLines.joinToString("\n")
    if (!emptySample.contains("la referencia no lee nada")) {
        return "la muestra del vacio no distingue la ausencia legal: " + emptySample
    }
    if (report.contains(" mas)")) return "alguna muestra quedo recortada en el informe"
    val contextLines = lines.filter { it.startsWith("PARSER     contexto=") }
    if (contextLines.none { it.contains("bloque Data de ObjectUpdateCompressed") }) {
        return "falta el contexto del bloque comprimido:\n" + contextLines.joinToString("\n")
    }
    val terseContext = contextLines.filter { it.contains("ImprovedTerseObjectUpdate") }
    if (terseContext.isEmpty()) {
        return "falta el contexto del mensaje terse:\n" + contextLines.joinToString("\n")
    }
    // On device the message is re-encoded and the blob located inside it; with a
    // synthetic definition (no blocks) the re-encode is empty and the captured
    // context is the neighbouring field instead. Either is a valid provenance.
    if (terseContext.none { it.contains("recomprimido") || it.contains("campo vecino") }) {
        return "el contexto terse no dice de donde salio:\n" + terseContext.joinToString("\n")
    }
    // Y el reparto tiene que cuadrar con los contadores: ninguna entrada se pierde
    // ni se duplica entre las categorias.
    val classified = ObjectUpdateDiagnostics.textureCategoryMaterialAbsent +
        ObjectUpdateDiagnostics.textureStopsInOptionalMaterial +
        ObjectUpdateDiagnostics.textureCategoryLegacyFewerFields +
        ObjectUpdateDiagnostics.textureCategoryRequiredIncomplete +
        ObjectUpdateDiagnostics.textureCategorySubUuid +
        ObjectUpdateDiagnostics.textureCategoryOther +
        ObjectUpdateDiagnostics.textureEntriesEmpty
    if (classified != ObjectUpdateDiagnostics.textureEntries +
        ObjectUpdateDiagnostics.textureEntriesTooShort
    ) {
        return "la clasificacion no cubre todos los blobs: " + classified + " de " +
            (ObjectUpdateDiagnostics.textureEntries + ObjectUpdateDiagnostics.textureEntriesTooShort)
    }
    println("TEXTUREENTRY REPARTO (revision 2.13a)")
    for (line in lines.filter {
        it.startsWith("PARSER TextureEntry") && !it.contains("hex=") && !it.startsWith("PARSER     contexto=")
    }) {
        println("  " + line)
    }
    return "OK"
}

private fun drainTextureResults(cache: TextureAssetCache, provider: FakeTextureAssetProvider) {
    for (result in provider.drainCompleted()) {
        when (result) {
            is TextureAssetResult.Ready -> cache.put(result.asset)
            is TextureAssetResult.Failed -> cache.fail(result.textureId, result.discardLevel, result.reason)
        }
    }
}

/**
 * Fase 2.13a: el cache y el proveedor falso. Un miss pide una vez; un hit no
 * vuelve a pedir (una textura compartida por varios prims se descarga una sola
 * vez); un fallo se recuerda y se puede reintentar; y el nivel de descarte forma
 * parte de la clave.
 */
private fun textureAssetCacheCheck(): String {
    val cache = TextureAssetCache()
    val provider = FakeTextureAssetProvider()
    val shared = "0f0f0f0f-1111-2222-3333-444444444444"
    val other = "99999999-8888-7777-6666-555555555555"

    if (cache.state(shared) != TextureAssetState.UNKNOWN) return "una textura nueva deberia estar UNKNOWN"
    if (!cache.markPending(shared)) return "la primera solicitud deberia ser un miss"
    if (cache.state(shared) != TextureAssetState.PENDING) return "tras pedirla deberia estar PENDING"
    if (cache.markPending(shared)) return "una segunda solicitud no deberia volver a descargar"
    if (cache.stats.cacheMisses != 1) return "misses " + cache.stats.cacheMisses
    if (cache.stats.cacheHits != 1) return "hits " + cache.stats.cacheHits

    provider.request(shared)
    if (provider.pendingCount != 1) return "el provider deberia tener 1 pendiente"
    if (!provider.deliver(shared, ByteArray(2048))) return "no se pudo entregar la textura"
    drainTextureResults(cache, provider)
    if (cache.state(shared) != TextureAssetState.READY) return "tras entregarla deberia estar READY"
    if (cache.stats.downloaded != 1) return "descargas " + cache.stats.downloaded
    if (cache.stats.bytesDownloaded != 2048L) return "bytes " + cache.stats.bytesDownloaded

    // Un segundo prim con la MISMA textura: hit, no se vuelve a pedir.
    if (cache.markPending(shared)) return "la textura compartida no deberia pedirse otra vez"
    if (provider.requests != 1) return "el provider volvio a recibir la peticion"
    if (cache.asset(shared)?.size != 2048) return "los bytes guardados no cuadran"
    if (cache.stats.cacheHits != 2) return "hits " + cache.stats.cacheHits

    // Fallo, y reintento que si descarga.
    if (!cache.markPending(other)) return "la segunda textura deberia ser un miss"
    provider.request(other)
    provider.fail(other, "HTTP 404")
    drainTextureResults(cache, provider)
    if (cache.state(other) != TextureAssetState.FAILED) return "tras fallar deberia estar FAILED"
    if (cache.failureOf(other) != "HTTP 404") return "no se recuerda el motivo: " + cache.failureOf(other)
    if (cache.markPending(other)) return "una textura fallida no deberia reintentarse sola"
    if (!cache.retry(other)) return "retry deberia aceptar una textura fallida"
    if (cache.state(other) != TextureAssetState.UNKNOWN) return "tras retry deberia estar UNKNOWN"
    if (!cache.markPending(other)) return "tras retry la peticion deberia volver a ser un miss"
    provider.request(other)
    provider.deliver(other, ByteArray(512))
    drainTextureResults(cache, provider)
    if (cache.state(other) != TextureAssetState.READY) return "tras el reintento deberia estar READY"
    if (cache.stats.retried != 1) return "reintentos " + cache.stats.retried
    if (cache.stats.downloaded != 2) return "descargas " + cache.stats.downloaded

    // El nivel de descarte forma parte de la clave.
    if (cache.state(shared, 1) != TextureAssetState.UNKNOWN) return "otro discard deberia ser UNKNOWN"
    if (!cache.markPending(shared, 1)) return "otro discard deberia ser un miss"

    // El provider falso no inventa nada: sin pedirlo, no entrega.
    if (provider.deliver("00000000-0000-0000-0000-0000000000ff", ByteArray(4))) {
        return "el provider no deberia entregar una textura que nadie pidio"
    }
    val summary = cache.stats.summary()
    if (!summary.contains("cache hit")) return "el resumen no lleva los contadores: " + summary
    return "OK"
}

// ---------------------------------------------------------------------------
// Revision de 2.13a: el lector de REFERENCIA, portado, y su equivalencia
// ---------------------------------------------------------------------------

/**
 * El visor de referencia, calculado en vez de citado.
 *
 * El informe ya no afirma "el visor de referencia lo acepta": lo calcula con
 * [TextureEntryReference], que reproduce `LLPrimitive::unpackTEMessage` byte a
 * byte (el terminador fantasma que anade al final del buffer y el +1 que exige
 * para cada campo). Este test fija el resultado de ese algoritmo y lo compara con
 * la clasificacion del parser propio, para que "prefijo valido" deje de ser una
 * impresion:
 *
 *  * 0 bytes        -> el update no lleva entrada: la referencia no lee nada;
 *  * 1..15 bytes    -> RECHAZA (no cabe ni el UUID por defecto);
 *  * 16..45 bytes   -> RECHAZA (no caben los diez campos obligatorios);
 *  * 46..62 bytes   -> ACEPTA (los diez caben; el material es opcional);
 *  * 63 bytes o mas -> ACEPTA (con material).
 *
 * Y la equivalencia, que es la conclusion que la revision tenia que demostrar:
 * el parser propio dice "no truncada y campos >= 10", o "truncada en el campo
 * 10 (el material opcional)", exactamente cuando la referencia acepta. Un blob
 * que acaba en el limite de un campo ANTES del decimo no es un truncamiento para
 * el parser, pero la referencia lo RECHAZA: por eso la categoria B no puede
 * llamarse "prefijo valido".
 *
 * La unica divergencia posible esta documentada: si un bitfield de excepciones
 * queda cortado justo despues de un byte con el bit de continuacion (y ese byte
 * no aporta bits), el parser propio dice BITFIELD_INCOMPLETE mientras la
 * referencia lee su byte fantasma, ve flags = 0 y da el campo por terminado. Es
 * un blob que el emisor de la region no puede producir (su primer trozo de
 * bitfield es siempre distinto de cero), asi que la divergencia se cuenta y se
 * comprueba que solo aparece por ese motivo.
 */
private fun textureEntryReferenceCheck(): String {
    val uuid = "12345678-9abc-def0-1234-56789abcdef0"
    val full = buildTextureEntry(1, texture = { uuid })

    // 1: tamano del minimo aceptado y limite del emisor.
    if (TextureEntryReference.MIN_ACCEPTED_SIZE != 46) {
        return "MIN_ACCEPTED_SIZE " + TextureEntryReference.MIN_ACCEPTED_SIZE
    }
    if (TextureEntry.WRITER_MIN_WIRE_SIZE != 63) {
        return "WRITER_MIN_WIRE_SIZE " + TextureEntry.WRITER_MIN_WIRE_SIZE
    }

    // 2: la frontera, tamano a tamano (blobs a cero: todos los terminadores
    //    presentes, que es el caso mas favorable para el lector).
    val accepted = ArrayList<Int>()
    for (size in 0..100) {
        val blob = ByteArray(size)
        if (TextureEntryReference.read(blob).verdict != TextureEntryReference.Verdict.REJECTED) {
            accepted.add(size)
        }
    }
    if (accepted != listOf(0) + (46..100).toList()) {
        return "la frontera del lector de referencia no es 46: " + accepted
    }
    if (TextureEntryReference.read(ByteArray(0)).verdict !=
        TextureEntryReference.Verdict.NO_ENTRY
    ) {
        return "un blob vacio deberia ser NO_ENTRY (ausencia legal)"
    }
    if (TextureEntryReference.read(ByteArray(15)).field != 0) {
        return "15 bytes deberia rechazarlos en el campo 0"
    }
    if (TextureEntryReference.read(ByteArray(45)).field != 9) {
        return "45 bytes deberia rechazarlos en el campo 9 (glow)"
    }

    // 3: los casos con contenido real.
    if (!TextureEntryReference.accepts(full)) {
        return "una entrada completa (11 campos) deberia aceptarse"
    }
    val ten = full.copyOfRange(0, full.size - 17)
    if (!TextureEntryReference.accepts(ten)) {
        return "una entrada de diez campos (material opcional ausente) deberia aceptarse"
    }
    val cutMaterial = full.copyOfRange(0, full.size - 3)
    if (!TextureEntryReference.accepts(cutMaterial)) {
        return "un material a medias deberia aceptarse (el material es opcional)"
    }
    val cutTint = full.copyOfRange(0, 18)
    if (TextureEntryReference.accepts(cutTint)) {
        return "un corte en el campo 'tinte' deberia rechazarse"
    }
    val uuidOnly = uuidBytes(uuid) + byteArrayOf(0)
    if (TextureEntryReference.accepts(uuidOnly)) {
        return "un prefijo de un solo campo deberia rechazarse (no es un prefijo valido)"
    }
    if (!TextureEntryReference.verdictText(uuidOnly).contains("RECHAZA")) {
        return "el veredicto de un prefijo corto no dice que lo rechaza: " +
            TextureEntryReference.verdictText(uuidOnly)
    }

    // 4: la equivalencia con el parser propio, sobre un corpus grande: blobs
    //    generados por el empaquetador real (1..3 caras, con y sin excepciones),
    //    TODOS sus prefijos de todos los tamanos, y blobs aleatorios.
    val corpus = ArrayList<ByteArray>()
    for (faces in 1..3) {
        val entry = buildTextureEntry(
            faces,
            texture = { if (it == 0) uuid else LLUUIDUtil.ZERO },
            color = { if (it % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFFF2030.toInt() },
            material = { if (it % 3 == 0) uuid else LLUUIDUtil.ZERO }
        )
        for (size in 0..entry.size) {
            corpus.add(entry.copyOfRange(0, size))
        }
    }
    var random = 987654321
    for (round in 0 until 4000) {
        random = random * 1103515245 + 12345
        val size = (random and 0x7FFFFFFF) % 90
        val blob = ByteArray(size)
        for (i in 0 until size) {
            random = random * 1103515245 + 12345
            blob[i] = ((random and 0x7FFFFFFF) % 256).toByte()
        }
        corpus.add(blob)
    }
    var divergences = 0
    var explained = 0
    for (blob in corpus) {
        val reference = TextureEntryReference.read(blob)
        val parsed = TextureEntry.parse(blob)
        val ourAccepted = parsed != null && (
            (!parsed.truncated && parsed.parsedFields >= TextureEntry.OPTIONAL_FIELD_INDEX) ||
                (parsed.truncated &&
                    parsed.stopFieldIndex == TextureEntry.OPTIONAL_FIELD_INDEX)
            )
        if (blob.isEmpty()) {
            // Ausencia legal: la referencia no lee nada y el parser propio
            // devuelve null (no hay nada que decodificar). El informe lo cuenta
            // aparte, como blob vacio, no como categoria.
            if (reference.verdict != TextureEntryReference.Verdict.NO_ENTRY) {
                return "un blob vacio deberia ser NO_ENTRY para la referencia"
            }
            continue
        }
        if (reference.accepted == ourAccepted) {
            continue
        }
        divergences += 1
        // La unica divergencia admisible: el parser propio paro en un bitfield
        // que la referencia completa con su byte fantasma.
        if (parsed != null && parsed.truncated &&
            parsed.stop == TextureEntry.StopReason.BITFIELD_INCOMPLETE
        ) {
            explained += 1
        }
    }
    if (divergences != explained) {
        return "hay " + divergences + " divergencias y solo " + explained +
            " son el bitfield incompleto documentado"
    }

    // 5: la divergencia documentada, construida a mano. Diez campos terminados y
    //    despues un 0x80: el parser propio dice BITFIELD_INCOMPLETE (el bit de
    //    continuacion promete otro byte que no llega), la referencia lee su 0x00
    //    fantasma, ve flags = 0 y acepta.
    //    Los bytes son los 46 primeros de la entrada completa (los diez campos con
    //    sus terminadores hasta el valor de 'glow'), mas un 0x80 suelto: el
    //    bitfield de excepciones de 'glow' promete otro byte que no llega.
    val cornerTail = full.copyOfRange(0, 46) + byteArrayOf(0x80.toByte())
    val cornerParsed = TextureEntry.parse(cornerTail)
    val cornerReference = TextureEntryReference.read(cornerTail)
    if (cornerParsed == null || !cornerParsed.truncated ||
        cornerParsed.stop != TextureEntry.StopReason.BITFIELD_INCOMPLETE
    ) {
        return "el caso esquina del bitfield no se comporta como se documenta: " + cornerParsed
    }
    if (cornerReference.verdict != TextureEntryReference.Verdict.ACCEPTED) {
        return "la referencia deberia aceptar el caso esquina del bitfield: " +
            cornerReference.verdict + " en el campo " + cornerReference.field
    }

    println("TEXTUREENTRY LECTOR DE REFERENCIA (revision 2.13a)")
    println("  frontera: acepta 0 y >= " + TextureEntryReference.MIN_ACCEPTED_SIZE +
        " bytes; rechaza 1.." + (TextureEntryReference.MIN_ACCEPTED_SIZE - 1))
    println("  corpus: " + corpus.size + " blobs, " + divergences +
        " divergencias (todas el bitfield incompleto documentado)")
    println("  prefijo de un campo  -> " + TextureEntryReference.verdictText(uuidOnly))
    println("  corte en 'tinte'     -> " + TextureEntryReference.verdictText(cutTint))
    println("  material a medias    -> " + TextureEntryReference.verdictText(cutMaterial))
    return "OK"
}

// ---------------------------------------------------------------------------
// Revision de 2.13a: el FRAMING del mensaje terse y los casos C
// ---------------------------------------------------------------------------

/**
 * De donde salen de verdad los bytes de un `TextureEntry`, medido sobre el
 * mensaje codificado con el codec real.
 *
 * La pregunta que cierra la revision es si un blob corto es un corte real en el
 * wire o un offset equivocado nuestro. Este test la responde sin dispositivo:
 * construye un `ImprovedTerseObjectUpdate` completo (mismo `MessageDef` que el
 * template: Data Variable 1, TextureEntry Variable 2), lo codifica, lo vuelve a
 * decodificar, y comprueba con las posiciones que el codec registra:
 *
 *  * la palabra de longitud del `TextureEntry` esta exactamente donde dice el
 *    formato (contador de bloques + longitud de Data + los datos del movimiento);
 *  * la longitud declarada coincide con los bytes entregados;
 *  * el blob termina EXACTAMENTE donde termina el cuerpo (fin_coincide=si), que
 *    es la prueba de que el corte, si lo hay, es real en el wire;
 *  * el cuerpo se leyo entero (cuerpo_completo=si).
 *
 * Y despues pasa esos mismos mensajes por el decodificador para ver la
 * clasificacion real de cada caso: B (prefijo por debajo de los obligatorios),
 * C (corte dentro de un campo obligatorio) y A (material opcional incompleto).
 */
private fun textureFramingCheck(): String {
    val uuid = "12345678-9abc-def0-1234-56789abcdef0"
    val full = buildTextureEntry(1, texture = { uuid })

    val def = MessageDef(
        "ImprovedTerseObjectUpdate", Frequency.HIGH, 15, false, true,
        listOf(
            BlockDef(
                "RegionData", BlockRepeat.SINGLE, 0,
                listOf(
                    FieldDef("RegionHandle", FieldType.U64, 0),
                    FieldDef("TimeDilation", FieldType.U16, 0)
                )
            ),
            BlockDef(
                "ObjectData", BlockRepeat.VARIABLE, 0,
                listOf(
                    FieldDef("Data", FieldType.VARIABLE, 1),
                    FieldDef("TextureEntry", FieldType.VARIABLE, 2)
                )
            )
        )
    )
    val movement = terseBlob(4242, Vector3(1f, 2f, 3f))

    fun decodeTerse(texture: ByteArray): SLMessage? {
        val message = SLMessage(def)
        val regionData = message.block("RegionData")
        regionData.set("RegionHandle", 1234L)
        regionData.set("TimeDilation", 1)
        val block = message.addBlock("ObjectData")
        block.set("Data", movement)
        block.set("TextureEntry", texture)
        val encoded = SLMessageCodec.encode(message)
        return SLMessageCodec.decode(def, encoded, 0, encoded.size, true)
    }

    // 1: mensaje bien formado. El codec tiene que registrar donde estaba la
    //    palabra de longitud y donde empezaron los datos, y el blob tiene que
    //    terminar el cuerpo.
    val wellFormed = decodeTerse(full) ?: return "el mensaje terse bien formado no decodifica"
    val wellBlock = wellFormed.blocks["ObjectData"]?.firstOrNull()
        ?: return "el mensaje terse no trae ObjectData"
    val wellSpan = wellBlock.span("TextureEntry")
        ?: return "el codec no registro la posicion del TextureEntry"
    if (!wellBlock.bytes("TextureEntry").contentEquals(full)) {
        return "los bytes del TextureEntry no son los que se enviaron"
    }
    if (wellSpan.declaredLength != full.size) {
        return "longitud declarada " + wellSpan.declaredLength + " y el blob mide " + full.size
    }
    // RegionData mide 8 + 2; despues el contador de bloques (1) y la longitud de
    // Data (1), y luego los datos del movimiento.
    val expectedLengthWord = 10 + 2 + movement.size
    if (wellSpan.lengthWordOffset != expectedLengthWord) {
        return "la palabra de longitud esta en " + wellSpan.lengthWordOffset +
            " y el formato dice " + expectedLengthWord
    }
    if (wellSpan.dataOffset != expectedLengthWord + 2) {
        return "los datos empiezan en " + wellSpan.dataOffset + " y deberian empezar en " +
            (expectedLengthWord + 2)
    }
    if (wellSpan.dataOffset + wellSpan.declaredLength != wellFormed.bodyLength) {
        return "el blob NO termina donde termina el cuerpo: " +
            (wellSpan.dataOffset + wellSpan.declaredLength) + " != " + wellFormed.bodyLength
    }
    if (!wellFormed.bodyComplete) return "el cuerpo del mensaje bien formado no se leyo entero"

    // 2: el mismo mensaje con entradas cortas. El framing se prueba igual: el blob
    //    termina el cuerpo, la longitud declarada es la que se envio y el cuerpo
    //    se leyo entero. Eso es "el corte es real en el wire", no un offset nuestro.
    ObjectUpdateDiagnostics.reset()
    val shortSizes = listOf(17, 20, 45, 47, full.size - 3)
    for (size in shortSizes) {
        val texture = full.copyOfRange(0, size)
        val message = decodeTerse(texture) ?: return "el terse de " + size + " bytes no decodifica"
        val block = message.blocks["ObjectData"]?.firstOrNull()
            ?: return "el terse de " + size + " bytes no trae ObjectData"
        val span = block.span("TextureEntry")
            ?: return "el terse de " + size + " bytes no registro el TextureEntry"
        if (span.declaredLength != size) {
            return "declarado " + span.declaredLength + " y enviado " + size
        }
        if (span.dataOffset + span.declaredLength != message.bodyLength) {
            return "el blob de " + size + " bytes no termina el cuerpo"
        }
        if (!message.bodyComplete) return "el cuerpo del terse de " + size + " no se leyo entero"
        ObjectUpdateDecoder.applyTerse(message, WorldModel())
    }
    val report = ObjectUpdateDiagnostics.lines().joinToString("\n")

    // 3: la clasificacion real de cada caso y lo que diria la referencia.
    val uuidOnly = uuidBytes(uuid) + byteArrayOf(0)
    if (TextureEntryReference.accepts(uuidOnly)) return "un prefijo de 17 bytes no es valido"
    val tenFields = full.copyOfRange(0, 47)
    if (!TextureEntryReference.accepts(tenFields)) {
        return "diez campos deberian ser aceptados por la referencia"
    }
    val cut = full.copyOfRange(0, 20)
    val cutParsed = TextureEntry.parse(cut) ?: return "el corte de 20 bytes no parsea"
    if (!cutParsed.truncated || cutParsed.stopFieldIndex >= TextureEntry.OPTIONAL_FIELD_INDEX) {
        return "20 bytes deberia ser un corte en un campo obligatorio: " + cutParsed
    }
    if (TextureEntryReference.accepts(cut)) {
        return "la referencia no deberia aceptar un corte en campo obligatorio"
    }

    // 4: el informe tiene que llevar las cifras de framing de las muestras.
    if (!report.contains("fin_coincide=")) return "el informe no dice si el blob termina el cuerpo"
    if (!report.contains("declarado=")) return "el informe no dice la longitud declarada"
    if (!report.contains("cuerpo_completo=")) {
        return "el informe no dice si el cuerpo se leyo entero"
    }
    if (!report.contains("veredicto del LECTOR DE REFERENCIA")) {
        return "el informe no lleva el veredicto calculado de la referencia"
    }
    //    Dos truncados de los cinco casos: el corte de 20 bytes (campo obligatorio,
    //    categoria C) y el de 61 bytes (dentro del material opcional, categoria A
    //    a medias). Los otros tres (17, 45 y 47) acaban en el limite de un campo.
    if (ObjectUpdateDiagnostics.textureEntriesTruncated != 2) {
        return "los casos de framing deberian sumar dos truncados (20 y 61 bytes): " +
            ObjectUpdateDiagnostics.textureEntriesTruncated
    }
    //    Dos prefijos acaban en el limite de un campo por debajo de los diez
    //    obligatorios: 17 bytes (un campo) y 45 (nueve). Los dos son categoria B,
    //    y los dos los RECHAZA el lector de referencia.
    if (ObjectUpdateDiagnostics.textureCategoryLegacyFewerFields != 2) {
        return "los prefijos de 17 y 45 bytes deberian ser la categoria B: " +
            ObjectUpdateDiagnostics.textureCategoryLegacyFewerFields
    }
    if (ObjectUpdateDiagnostics.textureCategoryMaterialAbsent != 1) {
        return "los diez campos deberian ser la unica A (ausente): " +
            ObjectUpdateDiagnostics.textureCategoryMaterialAbsent
    }
    if (ObjectUpdateDiagnostics.textureReferenceRejected != 3L) {
        return "la referencia deberia rechazar 3 de los 5 blobs (17, 20 y el " +
            "material a medias no: 47 y " + (full.size - 3) + " se aceptan): " +
            ObjectUpdateDiagnostics.textureReferenceRejected
    }

    println("TEXTUREENTRY FRAMING (revision 2.13a, mensaje terse codificado y decodificado)")
    println("  RegionData 10 B + [contador 1][Data " + movement.size + " B][palabra 2 B] -> " +
        "TextureEntry declarado en " + wellSpan.lengthWordOffset + ", datos en " + wellSpan.dataOffset)
    println("  blob completo " + full.size + " B: termina el cuerpo en " + wellFormed.bodyLength +
        " (fin_coincide=" + (wellSpan.dataOffset + wellSpan.declaredLength == wellFormed.bodyLength) + ")")
    println("  casos cortos probados: " + shortSizes.joinToString(", ") +
        " (todos terminan el cuerpo y el cuerpo se leyo entero)")
    return "OK"
}

/**
 * The render loop's audit, without a device.
 *
 * What the report showed: 251325 "frames attempted" against 7058 beginFrame
 * successes. The question is not "is Filament broken" but "who is calling
 * beginFrame that often, and what happens when it says no". That answer is a
 * source-level fact (no Choreographer, no frame callback, no sleep between
 * frames, no wait after a refusal), so it is asserted here: the report has to
 * name all of it, and the numbers it prints have to be the arithmetic of the
 * counters.
 *
 * The one piece that cannot live here is the consecutive-refusal counter: it is
 * incremented inside `FilamentRenderer.render()`, which needs the Filament AAR
 * to compile. This check covers everything on the diagnostics side of that line.
 */
private fun renderLoopAuditCheck(): String {
    val diagnostics = RenderDiagnostics()

    //    A loop that has never run must say so rather than invent a rate, and the
    //    policy line is the audit itself: no pacer until the thread sets one up,
    //    and no wait of its own after a refusal.
    val fresh = diagnostics.lines().joinToString("\n")
    if (!fresh.contains("Bucle de render (2.13a-rev4):")) {
        return "el informe no lleva la linea del bucle de render"
    }
    if (!fresh.contains("sin superficie") || !fresh.contains("renderer no listo")) {
        return "la linea del bucle no separa los motivos de salida"
    }
    if (!fresh.contains("Bucle de render, politicas: -")) {
        return "el informe no declara que el bucle aun no ha sido pautado"
    }
    if (!fresh.contains("tras un beginFrame=false no hay espera propia")) {
        return "el informe no declara que no hay espera propia tras un rechazo"
    }
    if (!fresh.contains("setTargetFrameRate llamado: NO")) {
        return "el informe no dice si se fijo una tasa de refresco"
    }
    if (diagnostics.frameRateHintCalls != 0) {
        return "nadie deberia haber fijado la tasa todavia"
    }
    if (diagnostics.captureSeconds != 0.0) {
        return "sin tiempo de hilo no hay duracion de captura"
    }

    //    The device's own numbers (8244 attempts, 7214 presented, 1030 refused) with
    //    the thread time that came with them. Every rate in the report has to be
    //    one of those counters divided by that duration, so the whole line can be
    //    checked by hand -- which is what the device asked for after seeing a
    //    window rate printed next to cumulative counters.
    diagnostics.framesAttempted = 8244
    diagnostics.beginFrameOk = 7214
    diagnostics.beginFrameFail = 1030
    diagnostics.presentedFrames = 7214
    diagnostics.renderCalls = 7214
    diagnostics.endFrameCalls = 7214
    diagnostics.drawNanos = 205_000_000_000L
    diagnostics.failedFrameNanos = 6_932_600_000L
    diagnostics.loopNanos = 210_575_700_000L
    //    The first call only opens the window: it must not invent a window rate yet.
    diagnostics.publishRenderRates(1000L)
    if (diagnostics.windowSeconds != 0.0) {
        return "la primera llamada ya publica una ventana"
    }
    if (kotlin.math.abs(diagnostics.captureSeconds - 210.5757) > 1e-9) {
        return "la duracion de la captura no es el tiempo del hilo: " + diagnostics.captureSeconds
    }
    //    The formulas themselves, not just their printout.
    if (kotlin.math.abs(diagnostics.attemptRatePerSecond - 8244.0 / 210.5757) > 1e-9) {
        return "intentos/s no es intentos / duracion: " + diagnostics.attemptRatePerSecond
    }
    if (kotlin.math.abs(diagnostics.presentedRatePerSecond - 7214.0 / 210.5757) > 1e-9) {
        return "presentados/s no es presentados / duracion: " + diagnostics.presentedRatePerSecond
    }
    if (kotlin.math.abs(diagnostics.failedRatePerSecond - 1030.0 / 210.5757) > 1e-9) {
        return "rechazados/s no es fallos / duracion: " + diagnostics.failedRatePerSecond
    }
    if (kotlin.math.abs(diagnostics.failedMillisPerSecond - 6932.6 / 210.5757) > 1e-9) {
        return "ms/s perdidos no es el tiempo perdido / duracion: " + diagnostics.failedMillisPerSecond
    }
    if (kotlin.math.abs(diagnostics.failedDutyPercent - 100.0 * 6932.6 / 210575.7) > 1e-9) {
        return "el porcentaje del hilo no es tiempo perdido / tiempo de hilo: " +
            diagnostics.failedDutyPercent
    }
    if (kotlin.math.abs(diagnostics.drawDutyPercent - 100.0 * 205000.0 / 210575.7) > 1e-9) {
        return "el porcentaje dentro de drawFrame no es ese tiempo / tiempo de hilo: " +
            diagnostics.drawDutyPercent
    }

    val report = diagnostics.lines().joinToString("\n")
    //    The printed arithmetic: counter / duration, printed next to the counter.
    if (!report.contains("ritmo de la captura (210.6 s de hilo de render)")) {
        return "el informe no nombra la duracion de la captura"
    }
    if (!report.contains("39.1 intentos/s = 8244 / 210.6 s")) {
        return "el informe no lleva intentos/s = intentos / duracion"
    }
    if (!report.contains("34.3 presentados/s = 7214 / 210.6 s")) {
        return "el informe no lleva presentados/s = presentados / duracion"
    }
    if (!report.contains("4.9 rechazados/s = 1030 / 210.6 s")) {
        return "el informe no lleva rechazados/s = fallos / duracion"
    }
    if (!report.contains("12.5% de los intentos")) {
        return "el porcentaje de beginFrame fallidos no es fallos / intentos"
    }
    if (!report.contains("dentro de drawFrame 205000.0 ms de 210575.7 ms de hilo (97.4%)")) {
        return "la linea de coste no lleva el tiempo dentro de drawFrame sobre el del hilo"
    }
    if (!report.contains("6932.6 ms (3.3% del hilo, 32.9 ms/s sobre 210.6 s)")) {
        return "la linea de coste no lleva el tiempo perdido, su porcentaje y sus ms/s"
    }
    if (!report.contains("coste medio por frame presentado 28.4 ms")) {
        return "el informe no lleva el coste medio por frame presentado"
    }
    if (!report.contains("por intento fallido 6.7 ms")) {
        return "el informe no lleva el coste medio por intento fallido"
    }
    if (!report.contains("Frames: 8244 intentados") ||
        !report.contains("beginFrame OK 7214 / fallo 1030") ||
        !report.contains("render 7214") || !report.contains("endFrame 7214")
    ) {
        return "la linea de frames no lleva los contadores"
    }

    //    The recent-rate window is separate, with its own duration printed, and it
    //    must not touch the capture numbers: 113 attempts and 99 presented frames
    //    over the 3 s that closed.
    diagnostics.framesAttempted = 8244 + 113
    diagnostics.presentedFrames = 7214 + 99
    diagnostics.beginFrameFail = 1030 + 14
    diagnostics.publishRenderRates(4000L)
    if (kotlin.math.abs(diagnostics.windowSeconds - 3.0) > 1e-9) {
        return "la ventana no dura lo que duro: " + diagnostics.windowSeconds
    }
    if (kotlin.math.abs(diagnostics.windowAttemptsPerSecond - 113.0 / 3.0) > 1e-9 ||
        kotlin.math.abs(diagnostics.windowPresentedPerSecond - 99.0 / 3.0) > 1e-9 ||
        kotlin.math.abs(diagnostics.windowFailedPerSecond - 14.0 / 3.0) > 1e-9
    ) {
        return "los ritmos de la ventana no son su crecimiento / su duracion"
    }
    if (kotlin.math.abs(diagnostics.failedRatePerSecond - 1044.0 / 210.5757) > 1e-9) {
        return "el ritmo de la captura debe seguir a los contadores, no a la ventana: " +
            diagnostics.failedRatePerSecond
    }
    if (diagnostics.windowSeconds == 0.0) {
        return "la ventana no se cerro"
    }

    //    The pacing correction has to be visible in the report: which thread draws,
    //    which one owns input, and how the loop is paced. The comparison of the two
    //    thread names is what answers "does the render loop share a thread with
    //    input/UI" without anyone having to infer it.
    diagnostics.renderThreadName = "EphoraRender"
    diagnostics.uiThreadName = "main"
    diagnostics.pacingMode = "Choreographer (VSYNC): un turno por frame del display"
    val threaded = diagnostics.lines().joinToString("\n")
    if (!threaded.contains("Hilos: render 'EphoraRender'  ·  UI 'main'")) {
        return "el informe no nombra los dos hilos"
    }
    if (!threaded.contains("el bucle de render NO comparte hilo con la UI")) {
        return "el informe no dice si el render comparte hilo con la UI"
    }
    if (!threaded.contains("Pacing: Choreographer (VSYNC)")) {
        return "el informe no dice como se pauta el bucle"
    }
    if (!threaded.contains("Bucle de render, politicas: Choreographer (VSYNC)")) {
        return "la linea de politicas no lleva el modo de pautado"
    }
    diagnostics.uiThreadName = "EphoraRender"
    val sameThread = diagnostics.lines().joinToString("\n")
    if (!sameThread.contains("ATENCION: el render comparte hilo con la UI")) {
        return "el informe no avisa cuando los dos hilos son el mismo"
    }

    println("BUCLE DE RENDER (revision 2.13a-rev4, metrica corregida)")
    println("  politica: un turno por VSYNC (Choreographer en el hilo de render); sin busy-spin")
    println("  captura 210.6 s: 39.1 intentos/s = 8244/210.6  ·  34.3 presentados/s = 7214/210.6" +
        "  ·  4.9 rechazados/s = 1030/210.6")
    println("  12.5% de beginFrame rechazados = 1030/8244  ·  6932.6 ms perdidos = 3.3% del hilo" +
        " = 32.9 ms/s")
    println("  coste medio: 28.4 ms por frame presentado, 6.7 ms por intento fallido")
    return "OK"
}

/**
 * The terse `TextureEntry`, as the region actually writes it.
 *
 * This is the causal closure of the device's B and C. The second device run left
 * no doubt about *where* they come from: **1004 refusals out of 1004 came from
 * `ImprovedTerseObjectUpdate`**, while `ObjectUpdate` (513 accepted) and
 * `ObjectUpdateCompressed` (639 accepted) were refused exactly zero times. The
 * two clean paths share one property the terse one does not have: inside their
 * `Data` blob the region writes a **four-byte length** ahead of the
 * `TextureEntry`, and this decoder consumes it (`reader.u32()` in the
 * compressed path). The terse message carries the entry as its own field, and the
 * region writes the same four bytes *inside that field*: OpenSim's sender says so
 * literally (`CreatePartImprovedTerseBlock`: the field's own length word is
 * `len + 4`, then `len` is written as a **u32**, then two zero bytes, then the
 * blob), and LibreMetaverse reads that field and skips the four bytes with a
 * FIXME — "Why are we ignoring the first four bytes here?".
 *
 * What is asserted here is the **measurement**, with the parser untouched and the
 * blobs parsed directly: the four bytes move every field, so a prefixed blob whose
 * shifted parse happens to complete is *accepted with the wrong texture*, which
 * is why the prefix has to be measured before anything is changed.
 */
private fun tersePrefixCheck(): String {
    val a = "11111111-2222-3333-4444-555555555555"
    val b = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    // One face — the blob that ends up in category C — and two faces, the one
    // the reference reader accepts while reading a different entry.
    val oneFace = buildTextureEntry(1, texture = { a })
    val twoFaces = buildTextureEntry(2, texture = { if (it == 0) a else b })
    val onePlain = TextureEntry.parse(oneFace) ?: return "la entrada de una cara no parsea"
    val twoPlain = TextureEntry.parse(twoFaces) ?: return "la entrada de dos caras no parsea"
    val cases = listOf(
        Triple("una cara", oneFace, onePlain),
        Triple("dos caras", twoFaces, twoPlain)
    )

    // (1) Without the prefix — what the full and the compressed paths hand over —
    //     both are complete and the reference reader accepts them. That is why
    //     those two paths were refused zero times on the device.
    for ((label, entry, parsed) in cases) {
        if (parsed.truncated || parsed.parsedFields != TextureEntry.WRITER_MIN_FIELDS) {
            return "la entrada de " + label + " sin prefijo deberia ser completa: campos=" + parsed.parsedFields
        }
        if (parsed.face(0).textureId != a) {
            return "la cara 0 de " + label + " sin prefijo es " + parsed.face(0).textureId
        }
        if (TextureEntryReference.read(entry).verdict != TextureEntryReference.Verdict.ACCEPTED) {
            return "la referencia deberia ACEPTAR la entrada de " + label + " sin prefijo"
        }
    }

    // (2) Category C. With the region's four bytes in front, the one-face blob is
    //     cut inside a mandatory field and the reference drops the whole entry —
    //     the signature the device reported 47 times, all of them in terse
    //     updates.
    val onePrefixed = withTerseLengthPrefix(oneFace)
    val oneShifted = TextureEntry.parse(onePrefixed)
        ?: return "el caso de una cara con prefijo ni siquiera parsea"
    if (!oneShifted.truncated || oneShifted.stop != TextureEntry.StopReason.DEFAULT_INCOMPLETE) {
        return "el caso de una cara con prefijo deberia cortarse en un valor obligatorio, para en " +
            oneShifted.stop
    }
    val oneReference = TextureEntryReference.read(onePrefixed).verdict
    if (oneReference != TextureEntryReference.Verdict.REJECTED) {
        return "la referencia deberia RECHAZAR el caso de una cara con prefijo, dice " + oneReference
    }

    // (3) The silent false positive. With two faces the shifted parse does complete
    //     (eleven fields) and the reference ACCEPTS it — but it is not the object's
    //     entry: the four bytes move every field. Face 0's texture coincides *by
    //     accident* here, which is why a single field proves nothing and the whole
    //     entry is compared.
    val twoPrefixed = withTerseLengthPrefix(twoFaces)
    val twoShifted = TextureEntry.parse(twoPrefixed)
        ?: return "el caso de dos caras con prefijo no parsea"
    val differing = differingFaces(twoPlain, twoShifted)
    if (differing == 0) {
        return "el prefijo de 4 bytes no cambio ninguna cara del caso de dos caras"
    }
    val twoReference = TextureEntryReference.read(twoPrefixed).verdict
    if (twoReference != TextureEntryReference.Verdict.ACCEPTED) {
        return "el caso de dos caras con prefijo deberia ser ACEPTADO por la referencia (falso positivo), dice " +
            twoReference
    }

    // (4) The four bytes are the entry's own length: skipping exactly them gives
    //     the original entry back, whole and accepted, in both cases.
    for ((label, entry, parsed) in cases) {
        val prefixed = withTerseLengthPrefix(entry)
        if (leadingU32(prefixed) != entry.size) {
            return "los cuatro primeros bytes de " + label + " no son la longitud que queda: " + leadingU32(prefixed)
        }
        val recovered = TextureEntry.parse(prefixed.copyOfRange(4, prefixed.size))
            ?: return "saltando 4 bytes (" + label + ") no parsea"
        if (differingFaces(parsed, recovered) != 0) {
            return "saltando 4 bytes (" + label + ") la entrada no vuelve igual: " +
                differingFaces(parsed, recovered) + " caras distintas"
        }
        if (TextureEntryReference.read(prefixed.copyOfRange(4, prefixed.size)).verdict !=
            TextureEntryReference.Verdict.ACCEPTED
        ) {
            return "la referencia deberia ACEPTAR la entrada de " + label + " saltando los 4 bytes"
        }
    }

    // (5) Through the real decoder, with the field exactly as the region writes it
    //     (the four bytes and all): the correction consumes them, so the parser is
    //     handed the *entry*. Before the correction this same blob stopped inside
    //     `offsetS` — it is the device's category C.
    ObjectUpdateDiagnostics.reset()
    val model = WorldModel()
    ObjectUpdateDecoder.applyTerse(
        terseMessageWithTexture(7300, Vector3(1f, 2f, 3f), onePrefixed),
        model
    )
    val decoded = model.get(7300) ?: return "el terse no creo el objeto"
    if (decoded.textureEntrySize != oneFace.size) {
        return "el terse deberia entregar la entrada (" + oneFace.size + " B), entrego " +
            decoded.textureEntrySize
    }
    val decodedEntry = decoded.textureEntry
        ?: return "el terse deberia haber dejado una entrada parseada"
    if (decodedEntry.truncated || decodedEntry.parsedFields != TextureEntry.WRITER_MIN_FIELDS) {
        return "la entrada del terse no es completa: campos=" + decodedEntry.parsedFields
    }
    if (decodedEntry.face(0).textureId != a) {
        return "la textura del terse es " + decodedEntry.face(0).textureId
    }
    if (ObjectUpdateDiagnostics.tersePrefixFields != 1) {
        return "el terse deberia contar un campo, conto " + ObjectUpdateDiagnostics.tersePrefixFields
    }
    if (ObjectUpdateDiagnostics.tersePrefixConsumed != 1 ||
        ObjectUpdateDiagnostics.tersePrefixMissing != 0
    ) {
        return "el prefijo del terse no se consumio: consumidos " +
            ObjectUpdateDiagnostics.tersePrefixConsumed + ", sin coincidir " +
            ObjectUpdateDiagnostics.tersePrefixMissing
    }
    // The four-byte measurement is still taken, on the field as it arrives.
    if (ObjectUpdateDiagnostics.textureTersePrefix4Seen != 1 ||
        ObjectUpdateDiagnostics.textureTersePrefix4Matches != 1
    ) {
        return "el terse deberia contar su prefijo: " +
            ObjectUpdateDiagnostics.textureTersePrefix4Matches + "/" +
            ObjectUpdateDiagnostics.textureTersePrefix4Seen
    }
    // (6) The before/after comparison, from the same pass: what the parser used to
    //     see (a C) against what it sees now (eleven fields, accepted).
    val before = ObjectUpdateDiagnostics.terseBeforeCounts()
    val after = ObjectUpdateDiagnostics.terseAfterCounts()
    if (before[4] != 1) return "el 'antes' del terse deberia ser una C, es " + before.toList()
    if (after[4] != 0 || after[0] != 1) {
        return "el 'despues' del terse deberia ser 11 campos y ninguna C, es " + after.toList()
    }
    if (after[6] != 1 || after[7] != 0) {
        return "el 'despues' del terse deberia ser aceptado por la referencia, es " + after.toList()
    }

    // (7) The report carries the aggregate, the split by origin, the samples and the
    //     before/after comparison.
    val report = ObjectUpdateDiagnostics.textureEntryLines().joinToString("\n")
    for (needed in listOf(
        "prefijo de 4 bytes",
        "ImprovedTerseObjectUpdate 1/1",
        "COINCIDE: es la longitud que queda",
        "saltando 4 bytes: campos=11",
        "terse, prefijo u32 (correccion 2.13a-rev4): campos vistos 1  ·  consumidos 1",
        "terse, ANTES (el campo tal cual llega)",
        "terse, DESPUES (consumido el u32)"
    )) {
        if (!report.contains(needed)) return "el informe no lleva: " + needed
    }

    println("TERSE PREFIJO (2.13a rev2/rev4): el campo del terse es [u32 longitud][TextureEntry], " +
        "igual que el bloque Data del comprimido")
    println("  una cara   sin prefijo " + oneFace.size + " B: 11 campos, completa, la referencia ACEPTA")
    println("  una cara   con prefijo " + onePrefixed.size + " B: el parser para en el campo " +
        oneShifted.stopFieldIndex + " (" + oneShifted.stoppedAt + "), la referencia RECHAZA  -> categoria C")
    println("  dos caras  sin prefijo " + twoFaces.size + " B: 11 campos, completa, la referencia ACEPTA")
    println("  dos caras  con prefijo " + twoPrefixed.size + " B: 11 campos y la referencia ACEPTA, pero " +
        differing + " de 8 caras no son las del objeto -> falso positivo silencioso")
    println("  por el decodificador: el campo de " + onePrefixed.size + " B se entrega como entrada de " +
        decoded.textureEntrySize + " B, 11 campos, cara 0 " + decodedEntry.face(0).textureId.take(8) +
        ", la referencia ACEPTA")
    return "OK"
}

/** Cuantas de las ocho caras difieren entre dos entradas (el parser no da equals). */
private fun differingFaces(x: TextureEntry, y: TextureEntry): Int {
    var count = 0
    for (index in 0 until 8) {
        val p = x.face(index)
        val q = y.face(index)
        if (p.textureId != q.textureId || p.colorArgb != q.colorArgb || p.scaleU != q.scaleU ||
            p.scaleV != q.scaleV || p.offsetU != q.offsetU || p.offsetV != q.offsetV ||
            p.rotation != q.rotation || p.bump != q.bump || p.mediaFlags != q.mediaFlags ||
            p.glow != q.glow || p.materialId != q.materialId
        ) {
            count += 1
        }
    }
    return count
}

/** Los cuatro primeros bytes como entero little-endian. */
private fun leadingU32(bytes: ByteArray): Int =
    (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or
        ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 24)

/** The region's terse field content: the entry's length as a u32, then the entry. */
private fun withTerseLengthPrefix(entry: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream(4 + entry.size)
    writeLe32(out, entry.size)
    out.write(entry)
    return out.toByteArray()
}

/**
 * 2.13a-rev4: **el unico cambio funcional** — el u32 que el campo del terse lleva
 * delante se consume en la entrega, antes de que el blob llegue al parser.
 *
 * El dispositivo lo habia medido antes: 1975 de 1975 campos de terse empezaban con
 * su propia longitud restante, y ninguno de los completos o comprimidos. Lo que
 * comprueba esta funcion es la consecuencia, con el decodificador real y con el
 * mismo parser y el mismo lector de referencia que el resto del informe:
 *
 *  * un terse de una cara, uno de varias caras, una entrada con material completo y
 *    una sin el material opcional entregan la entrada correcta (campos, caras y
 *    veredicto de la referencia);
 *  * el caso que antes terminaba en C y el que antes terminaba en B llegan ahora
 *    completos;
 *  * los cuatro bytes son la longitud que queda detras de ellos, y el reparto
 *    `antes -> despues` lo demuestra en la misma pasada.
 */
private fun terseConsumeCheck(): String {
    val a = "11111111-2222-3333-4444-555555555555"
    val b = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    val c = "cccccccc-dddd-eeee-ffff-000011112222"
    val material = "01234567-89ab-cdef-0123-456789abcdef"

    val oneFace = buildTextureEntry(1, texture = { a })
    val withMaterial = buildTextureEntry(1, texture = { a }, material = { material })
    // El material opcional dejado fuera: los diez campos obligatorios, 47 bytes, que
    // el lector de referencia acepta (categoria A, "ausente").
    val withoutMaterial = oneFace.copyOfRange(0, oneFace.size - 17)
    val severalFaces = buildTextureEntry(
        4,
        texture = { index -> if (index == 0) a else if (index == 1) b else c }
    )
    val cases = listOf(
        Triple("una cara", oneFace, 11),
        Triple("una cara con material", withMaterial, 11),
        Triple("sin material opcional", withoutMaterial, 10),
        Triple("cuatro caras", severalFaces, 11)
    )

    // El u32 tiene que ser exactamente la longitud que queda, y la entrada que el
    // decodificador entrega tiene que ser la original, cara por cara.
    for ((label, entry, fields) in cases) {
        val field = withTerseLengthPrefix(entry)
        if (leadingU32(field) != entry.size) {
            return label + ": el u32 del campo no es la longitud que queda: " +
                leadingU32(field) + " != " + entry.size
        }
        ObjectUpdateDiagnostics.reset()
        val model = WorldModel()
        ObjectUpdateDecoder.applyTerse(
            terseMessageWithTexture(7400, Vector3(1f, 2f, 3f), field),
            model
        )
        val decoded = model.get(7400) ?: return label + ": el terse no creo el objeto"
        if (decoded.textureEntrySize != entry.size) {
            return label + ": deberia entregar " + entry.size + " B, entrego " + decoded.textureEntrySize
        }
        val parsed = decoded.textureEntry ?: return label + ": el terse no dejo entrada"
        if (parsed.truncated || parsed.parsedFields != fields) {
            return label + ": campos=" + parsed.parsedFields + " (esperaba " + fields +
                "), truncada=" + parsed.truncated
        }
        val original = TextureEntry.parse(entry) ?: return label + ": la entrada original no parsea"
        val differing = differingFaces(original, parsed)
        if (differing != 0) {
            return label + ": la entrada entregada no es la original (" + differing + " caras distintas)"
        }
        if (TextureEntryReference.read(entry).verdict != TextureEntryReference.Verdict.ACCEPTED) {
            return label + ": la referencia deberia ACEPTAR la entrada"
        }
        if (ObjectUpdateDiagnostics.tersePrefixConsumed != 1 ||
            ObjectUpdateDiagnostics.tersePrefixMissing != 0
        ) {
            return label + ": el prefijo no se consumio (consumidos " +
                ObjectUpdateDiagnostics.tersePrefixConsumed + ", sin coincidir " +
                ObjectUpdateDiagnostics.tersePrefixMissing + ")"
        }
        if (ObjectUpdateDiagnostics.tersePrefixFields != 1) {
            return label + ": deberia contar un campo de terse"
        }
    }

    // Los dos casos que el dispositivo reporto, buscados en un corpus de entradas
    // validas: con el campo tal cual llega, estos mismos blobs terminaban en C (un
    // corte dentro de un campo obligatorio) o en B (bien formada, con menos de los
    // diez campos obligatorios que exige la referencia).
    val corpus = ArrayList<Pair<String, ByteArray>>()
    corpus.add("una cara" to oneFace)
    corpus.add("una cara con material" to withMaterial)
    corpus.add("sin material opcional" to withoutMaterial)
    corpus.add("cuatro caras" to severalFaces)
    for (count in 2..6) {
        corpus.add(
            count.toString() + " caras" to buildTextureEntry(
                count,
                texture = { index -> if (index == 0) a else if (index == 1) b else c }
            )
        )
    }
    for (seed in 1..16) {
        val uuid = java.lang.String.format("00000000-0000-0000-0000-%012x", seed)
        corpus.add("una cara uuid " + seed to buildTextureEntry(1, texture = { uuid }))
    }
    var cCase: Pair<String, ByteArray>? = null
    var bCase: Pair<String, ByteArray>? = null
    for (candidate in corpus) {
        val shifted = TextureEntry.parse(withTerseLengthPrefix(candidate.second)) ?: continue
        if (shifted.truncated && shifted.stopFieldIndex != TextureEntry.OPTIONAL_FIELD_INDEX) {
            if (cCase == null) cCase = candidate
        } else if (!shifted.truncated && shifted.belowWriterMinimum &&
            shifted.parsedFields < TextureEntry.OPTIONAL_FIELD_INDEX
        ) {
            if (bCase == null) bCase = candidate
        }
    }
    if (cCase == null) {
        return "el corpus no reproduce la categoria C: revisar los blobs de prueba"
    }
    if (bCase == null) {
        return "el corpus no reproduce la categoria B: revisar los blobs de prueba"
    }

    // Una sola pasada con los dos casos: el reparto antes/despues tiene que moverse
    // exactamente donde la correccion dice.
    val both = listOf(cCase, bCase)
    ObjectUpdateDiagnostics.reset()
    val model = WorldModel()
    var localId = 7500
    for (case in both) {
        ObjectUpdateDecoder.applyTerse(
            terseMessageWithTexture(localId, Vector3(1f, 2f, 3f), withTerseLengthPrefix(case.second)),
            model
        )
        val decoded = model.get(localId) ?: return "el terse de " + case.first + " no creo el objeto"
        val parsed = decoded.textureEntry ?: return "el terse de " + case.first + " no dejo entrada"
        val original = TextureEntry.parse(case.second)
            ?: return "la entrada de " + case.first + " no parsea"
        if (parsed.truncated || differingFaces(original, parsed) != 0) {
            return "el caso " + case.first + " no llega completo tras consumir el prefijo"
        }
        if (TextureEntryReference.read(case.second).verdict != TextureEntryReference.Verdict.ACCEPTED) {
            return "la referencia deberia ACEPTAR la entrada de " + case.first
        }
        localId += 1
    }
    val before = ObjectUpdateDiagnostics.terseBeforeCounts()
    val after = ObjectUpdateDiagnostics.terseAfterCounts()
    if (before[3] != 1 || before[4] != 1) {
        return "el 'antes' deberia contar una B y una C, es " + before.toList()
    }
    if (before[6] != 0 || before[7] != 2) {
        return "el 'antes' deberia ser RECHAZADO por la referencia en los dos casos, es " + before.toList()
    }
    if (after[3] != 0 || after[4] != 0) {
        return "el 'despues' no deberia tener B ni C, es " + after.toList()
    }
    if (after[0] != 2 || after[6] != 2 || after[7] != 0) {
        return "el 'despues' deberian ser dos entradas completas y aceptadas, es " + after.toList()
    }
    if (ObjectUpdateDiagnostics.tersePrefixConsumed != 2) {
        return "los dos campos deberian haberse consumido, consumidos " +
            ObjectUpdateDiagnostics.tersePrefixConsumed
    }

    println("TERSE PREFIJO CONSUMIDO (2.13a rev4, la unica correccion funcional)")
    println("  " + cases.size + " entradas entregadas completas (1 cara, con material, sin material, 4 caras)" +
        "; en todas el u32 es la longitud que queda")
    println("  corpus " + corpus.size + " · caso C tras el prefijo: " + cCase.first +
        " · caso B tras el prefijo: " + bCase.first)
    println("  antes -> despues: B " + before[3] + " -> " + after[3] +
        "  ·  C " + before[4] + " -> " + after[4] +
        "  ·  la referencia acepta " + before[6] + " -> " + after[6] +
        " / rechaza " + before[7] + " -> " + after[7])
    return "OK"
}

/**
 * The movement audit (phase 2.13a-rev5, MOVEMENT-AUDIT.md).
 *
 * It answers off the device every part of "the controls appear but the avatar
 * does not advance" that *can* be answered off the device, so that the one
 * device run left answers the rest:
 *
 *  * **link 2** — the control bits are the ones the reference viewer declares,
 *    and each [MoveAction] maps to the right one. A pressed FORWARD is a real
 *    `AT_POS`, or this check fails.
 *  * **link 3** — the `AgentUpdate` the app builds is well formed on the wire:
 *    122 bytes of body in the template's own order, the body rotation for the
 *    heading, the flags, the draw distance, the id and the frequency. The
 *    message definition is transcribed here from
 *    `app/src/main/assets/message_template.msg` (`AgentUpdate High 4 NotTrusted
 *    Zerocoded`) because the harness has no assets; the constants the app
 *    *reports* are asserted against it, so they cannot drift.
 *  * **link 3, content** — the camera basis the app sends is **not** a frame
 *    (finding M1), while the reference basis for the same heading is; both are
 *    printed here so the difference is on the record and cannot go quiet.
 *  * **A..E** — the audit's own classifier names the right link for each of the
 *    five ways the chain can break. That is what makes one device run enough.
 */
private fun movementAuditCheck(): String {
    fun u32le(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 4) {
            value = value or ((bytes[at + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }
    fun f32(bytes: ByteArray, at: Int): Float = Float.fromBits(u32le(bytes, at).toInt())

    // --- link 2: the control table and the action mapping --------------------

    val referenceTable = listOf(
        "AT_POS" to 0x1L, "AT_NEG" to 0x2L, "LEFT_POS" to 0x4L, "LEFT_NEG" to 0x8L,
        "UP_POS" to 0x10L, "UP_NEG" to 0x20L, "YAW_POS" to 0x100L, "YAW_NEG" to 0x200L,
        "FAST_AT" to 0x400L, "FAST_LEFT" to 0x800L, "FAST_UP" to 0x1000L,
        "FLY" to 0x2000L, "STOP" to 0x4000L,
        "NUDGE_AT_POS" to 0x80000L, "NUDGE_AT_NEG" to 0x100000L,
        "NUDGE_LEFT_POS" to 0x200000L, "NUDGE_LEFT_NEG" to 0x400000L,
        "NUDGE_UP_POS" to 0x800000L, "NUDGE_UP_NEG" to 0x1000000L
    )
    if (AgentControlFlags.TABLE != referenceTable) {
        return "la tabla de control flags no es la de la referencia (EControlFlags): " +
            AgentControlFlags.TABLE
    }
    if (!AgentControlFlags.hasNudge(0x80000L) || AgentControlFlags.hasNudge(0x1L) ||
        AgentControlFlags.NUDGE_MASK != 0x1F80000L) {
        return "la mascara de NUDGE no es la de la referencia: 0x" + AgentControlFlags.NUDGE_MASK.toString(16)
    }
    if (AgentControlFlags.describe(0x80001L) != "0x00080001 AT_POS|NUDGE_AT_POS") {
        return "describe(0x80001) = " + AgentControlFlags.describe(0x80001L)
    }
    val referenceActions = listOf(
        MoveAction.FORWARD to 0x1L, MoveAction.BACKWARD to 0x2L,
        MoveAction.STRAFE_LEFT to 0x4L, MoveAction.STRAFE_RIGHT to 0x8L,
        MoveAction.UP to 0x10L, MoveAction.DOWN to 0x20L,
        MoveAction.TURN_LEFT to 0x100L, MoveAction.TURN_RIGHT to 0x200L,
        MoveAction.RUN to 0x400L, MoveAction.FLY to 0x2000L
    )
    for ((action, bit) in referenceActions) {
        if (AgentControlFlags.bitFor(action) != bit) {
            return action.name + " deberia ser 0x" + bit.toString(16) +
                " y es 0x" + AgentControlFlags.bitFor(action).toString(16)
        }
    }
    if (AgentControlFlags.describe(0x401L) != "0x00000401 AT_POS|FAST_AT") {
        return "describe(0x401) = " + AgentControlFlags.describe(0x401L)
    }
    if (AgentControlFlags.describe(0L) != "0x00000000 (ninguno)") {
        return "describe(0) = " + AgentControlFlags.describe(0L)
    }

    // --- link 3: the command the app builds, byte by byte --------------------

    val def = MessageDef(
        AgentUpdateBuilder.MESSAGE_NAME,
        Frequency.HIGH,
        AgentUpdateBuilder.HIGH_ID,
        true,
        false,
        listOf(
            BlockDef(
                "AgentData",
                BlockRepeat.SINGLE,
                0,
                listOf(
                    FieldDef("AgentID", FieldType.LLUUID, 0),
                    FieldDef("SessionID", FieldType.LLUUID, 0),
                    FieldDef("BodyRotation", FieldType.QUATERNION, 0),
                    FieldDef("HeadRotation", FieldType.QUATERNION, 0),
                    FieldDef("State", FieldType.U8, 0),
                    FieldDef("CameraCenter", FieldType.VECTOR3, 0),
                    FieldDef("CameraAtAxis", FieldType.VECTOR3, 0),
                    FieldDef("CameraLeftAxis", FieldType.VECTOR3, 0),
                    FieldDef("CameraUpAxis", FieldType.VECTOR3, 0),
                    FieldDef("Far", FieldType.F32, 0),
                    FieldDef("ControlFlags", FieldType.U32, 0),
                    FieldDef("Flags", FieldType.U8, 0)
                )
            )
        )
    )
    val heading = 0.7f
    val body = AgentUpdateBuilder.build(
        def = def,
        agentId = "11111111-2222-3333-4444-555555555555",
        sessionId = "66666666-7777-8888-9999-aaaaaaaaaaaa",
        bodyYawRadians = heading,
        cameraCenter = Vector3(10f, 20f, 30f),
        basis = AgentUpdateBuilder.referenceBasis(heading),
        far = 128f,
        state = 0,
        controlFlags = AgentControlFlags.AT_POS,
        flags = 0
    )
    val bytes = SLMessageCodec.encode(body)
    if (bytes.size != AgentUpdateBuilder.BODY_BYTES) {
        return "el cuerpo del AgentUpdate mide " + bytes.size + " bytes y la constante dice " +
            AgentUpdateBuilder.BODY_BYTES
    }
    if (bytes.size != 122) {
        return "el cuerpo del AgentUpdate mide " + bytes.size + " y el template declara 122"
    }
    // Offsets, from the template's own field order: AgentID 0, SessionID 16,
    // BodyRotation 32, HeadRotation 48, State 64, CameraCenter 65, CameraAtAxis
    // 77, CameraLeftAxis 89, CameraUpAxis 101, Far 113, ControlFlags 117, Flags 121.
    val half = heading / 2f
    if (kotlin.math.abs(f32(bytes, 40) - kotlin.math.sin(half)) > 1e-6f ||
        kotlin.math.abs(f32(bytes, 44) - kotlin.math.cos(half)) > 1e-6f ||
        f32(bytes, 32) != 0f || f32(bytes, 36) != 0f) {
        return "BodyRotation no es (0, 0, sin, cos) del rumbo: " +
            f32(bytes, 32) + "," + f32(bytes, 36) + "," + f32(bytes, 40) + "," + f32(bytes, 44)
    }
    if (u32le(bytes, 117) != 0x1L) {
        return "ControlFlags en el cuerpo = 0x" + u32le(bytes, 117).toString(16) + " y se pidio AT_POS"
    }
    if (f32(bytes, 113) != 128f) {
        return "Far en el cuerpo = " + f32(bytes, 113)
    }
    if (f32(bytes, 65) != 10f || f32(bytes, 69) != 20f || f32(bytes, 73) != 30f) {
        return "CameraCenter en el cuerpo = " + f32(bytes, 65) + "," + f32(bytes, 69) + "," + f32(bytes, 73)
    }
    if (bytes[121].toInt() != 0) {
        return "Flags deberia ser 0 y es " + bytes[121]
    }
    if (def.zerocoded != true || def.trusted || def.frequency != Frequency.HIGH ||
        def.id != 4 || def.id != AgentUpdateBuilder.HIGH_ID) {
        return "la definicion de AgentUpdate no es High 4 NotTrusted Zerocoded"
    }

    // --- link 3, content: the basis actually serialized -----------------------
    // Since the 2.13b movement correction the send path uses the reference frame,
    // so what has to be asserted is (a) that the packet carries the reference
    // frame for its own heading, byte for byte, and (b) that the degenerate M1
    // triple is still the degenerate M1 triple (so the finding stays checkable
    // and the report's before/after line cannot silently become a lie).

    val sent = AgentUpdateBuilder.referenceBasis(heading)
    if (!sent.isOrthonormal || sent.volume <= 0f) {
        return "la base que se envia no es una base derecha: " + sent.describe()
    }
    if (kotlin.math.abs(f32(bytes, 77) - (kotlin.math.cos(heading))) > 1e-6f ||
        kotlin.math.abs(f32(bytes, 81) - (kotlin.math.sin(heading))) > 1e-6f ||
        f32(bytes, 85) != 0f) {
        return "el CameraAtAxis serializado no es el de la base: " +
            f32(bytes, 77) + "," + f32(bytes, 81) + "," + f32(bytes, 85)
    }
    if (kotlin.math.abs(f32(bytes, 89) + (kotlin.math.sin(heading))) > 1e-6f ||
        kotlin.math.abs(f32(bytes, 93) - (kotlin.math.cos(heading))) > 1e-6f ||
        f32(bytes, 97) != 0f) {
        return "el CameraLeftAxis serializado no es el de la base: " +
            f32(bytes, 89) + "," + f32(bytes, 93) + "," + f32(bytes, 97)
    }
    if (f32(bytes, 101) != 0f || f32(bytes, 105) != 0f || f32(bytes, 109) != 1f) {
        return "el CameraUpAxis serializado no apunta al cielo: " +
            f32(bytes, 101) + "," + f32(bytes, 105) + "," + f32(bytes, 109)
    }
    val readBack = AgentUpdateBuilder.serializedBasis(body)
    if (!readBack.isOrthonormal || kotlin.math.abs(readBack.volume - 1f) > 1e-6f) {
        return "leer la base del mensaje construido no devuelve una base: " + readBack.describe()
    }
    if (AgentUpdateBuilder.pretty(readBack.at) != AgentUpdateBuilder.pretty(sent.at) ||
        AgentUpdateBuilder.pretty(readBack.left) != AgentUpdateBuilder.pretty(sent.left) ||
        AgentUpdateBuilder.pretty(readBack.up) != AgentUpdateBuilder.pretty(sent.up)) {
        return "la base leida del mensaje no es la que se le paso al constructor: " + readBack.describe()
    }
    if (AgentUpdateBuilder.serializedControlFlags(body) != AgentControlFlags.AT_POS) {
        return "los flags leidos del mensaje son 0x" +
            AgentUpdateBuilder.serializedControlFlags(body).toString(16)
    }
    val sentHeading = AgentUpdateBuilder.headingOf(AgentUpdateBuilder.serializedBodyRotation(body))
    if (kotlin.math.abs(sentHeading - heading) > 1e-5f) {
        return "el rumbo leido del BodyRotation serializado es " + sentHeading + " y se pidio " + heading
    }

    // The historical M1 value, kept so the deviation stays reproducible.
    val historic = AgentUpdateBuilder.currentBasis()
    if (historic.isOrthonormal) {
        return "la base de la revision anterior deberia ser degenerada (hallazgo M1) y es una base"
    }
    if (kotlin.math.abs(historic.volume) > 1e-6f) {
        return "el volumen de la base de M1 es " + historic.volume + " y deberia ser 0"
    }
    if (kotlin.math.abs(historic.at.x) > 1e-6f || kotlin.math.abs(historic.at.y) > 1e-6f || historic.at.z != -1f) {
        return "el at de la base de M1 deberia apuntar hacia abajo: " + AgentUpdateBuilder.pretty(historic.at)
    }
    val referenceZero = AgentUpdateBuilder.referenceBasis(0f)
    if (!referenceZero.isOrthonormal || referenceZero.volume <= 0f) {
        return "la base de referencia en rumbo 0 no es una base derecha: " + referenceZero.describe()
    }
    if (AgentUpdateBuilder.pretty(referenceZero.at) != "(1.00, 0.00, 0.00)") {
        return "el at de referencia en rumbo 0 deberia mirar al este: " + AgentUpdateBuilder.pretty(referenceZero.at)
    }
    if (kotlin.math.abs(referenceZero.left.x) > 1e-6f ||
        kotlin.math.abs(referenceZero.left.y - 1f) > 1e-6f ||
        kotlin.math.abs(referenceZero.left.z) > 1e-6f) {
        return "la izquierda de referencia en rumbo 0 deberia ser el norte: " + AgentUpdateBuilder.pretty(referenceZero.left)
    }
    val referenceQuarter = AgentUpdateBuilder.referenceBasis((Math.PI / 2).toFloat())
    if (!referenceQuarter.isOrthonormal) {
        return "la base de referencia en rumbo pi/2 no es una base: " + referenceQuarter.describe()
    }

    // --- the classifier: one scenario per break, A..E ------------------------

    MovementAudit.reset()
    if (MovementAudit.verdict() != MovementBreak.A_INPUT) {
        return "sin entrada el veredicto deberia ser A y es " + MovementAudit.verdict()
    }
    MovementAudit.padPresses = 3
    MovementAudit.handlerCalls = 3
    if (MovementAudit.verdict() != MovementBreak.B_ENGINE) {
        return "con pulsaciones y sin comando el veredicto deberia ser B y es " + MovementAudit.verdict()
    }
    MovementAudit.updateBuilt = 2
    if (MovementAudit.verdict() != MovementBreak.C_SEND) {
        return "con comando construido y sin enviar el veredicto deberia ser C y es " + MovementAudit.verdict()
    }
    MovementAudit.updateSent = 2
    if (MovementAudit.verdict() != MovementBreak.D_SIM) {
        return "con comandos enviados y sin respuesta el veredicto deberia ser D y es " + MovementAudit.verdict()
    }
    MovementAudit.ownAdoptSamples = 5
    if (MovementAudit.verdict() != MovementBreak.D_SIM) {
        return "con el objeto propio visto y sin moverse el veredicto deberia ser D y es " + MovementAudit.verdict()
    }
    MovementAudit.ownPositionChanges = 3
    if (MovementAudit.verdict() != MovementBreak.E_LOCAL) {
        return "con la posicion cambiando y sin reflejo el veredicto deberia ser E y es " + MovementAudit.verdict()
    }
    MovementAudit.focusChanges = 9
    if (MovementAudit.verdict() != MovementBreak.NONE) {
        return "con los cinco enlaces vivos el veredicto deberia ser 'ninguno' y es " + MovementAudit.verdict()
    }

    // --- the counters themselves --------------------------------------------

    MovementAudit.reset()
    MovementAudit.notePad("FORWARD", true)
    MovementAudit.notePad("FORWARD", false)
    MovementAudit.noteHandler(true, AgentControlFlags.AT_POS, 0.5f, 0.5f)
    if (MovementAudit.padPresses != 1 || MovementAudit.padReleases != 1 || MovementAudit.handlerCalls != 1) {
        return "los contadores de entrada no cuadran: " + MovementAudit.hudLine()
    }
    if (MovementAudit.flagsNow != AgentControlFlags.AT_POS) {
        return "el estado tras el handler no guarda los flags: " + MovementAudit.flagsNow
    }
    MovementAudit.noteCommandBuilt()
    MovementAudit.noteCommandSent(37, AgentControlFlags.AT_POS, Vector3(1f, 2f, 3f), sent, 122, false, heading, heading)
    if (MovementAudit.updateBuilt != 1 || MovementAudit.updateSent != 1 || MovementAudit.sentBeforeMovementComplete != 1) {
        return "los contadores de comando no cuadran: " + MovementAudit.hudLine()
    }
    if (MovementAudit.lastSequence != 37 || MovementAudit.lastBasis.contains("NO es una base")) {
        return "el comando enviado no se registro: secuencia " + MovementAudit.lastSequence
    }
    if (kotlin.math.abs(MovementAudit.lastBodyYawSent - heading) > 1e-6f) {
        return "el rumbo serializado no se registro: " + MovementAudit.lastBodyYawSent
    }
    if (kotlin.math.abs(MovementAudit.lastBasisYaw - heading) > 1e-6f) {
        return "el rumbo usado por la base no se registro: " + MovementAudit.lastBasisYaw
    }
    if (!MovementAudit.sentBasisMatchesReference()) {
        return "la base registrada no coincide con la de referencia de su rumbo: " + MovementAudit.lastBasis
    }
    // The body faces where the viewer walks, the basis faces where the camera
    // looks, and turning with the yaw buttons makes them differ. The comparison
    // must follow the yaw the basis was built from, or the report would print a
    // false "NO COINCIDE" the first time the user turns.
    MovementAudit.noteCommandSent(38, AgentControlFlags.YAW_POS, Vector3(1f, 2f, 3f), sent, 122, true,
        heading + 0.9f, heading)
    if (kotlin.math.abs(MovementAudit.lastBodyYawSent - (heading + 0.9f)) > 1e-6f) {
        return "el rumbo del cuerpo se confundio con el de la base: " + MovementAudit.lastBodyYawSent
    }
    if (!MovementAudit.sentBasisMatchesReference()) {
        return "con el cuerpo girado y la base en su rumbo, la comparacion fallo: " + MovementAudit.lastBasis
    }
    MovementAudit.noteMovementComplete(Vector3(100f, 100f, 20f))
    MovementAudit.noteOwnObject(545575791, Vector3(100f, 100f, 20f))
    if (MovementAudit.ownAdoptSamples != 1 || MovementAudit.ownPositionChanges != 0) {
        return "la primera muestra del objeto propio no deberia contar como movimiento"
    }
    MovementAudit.noteOwnObject(545575791, Vector3(103f, 100f, 20f))
    if (MovementAudit.ownPositionChanges != 1 || MovementAudit.ownFirstDelta != "(3.00, 0.00, 0.00)") {
        return "el primer desplazamiento propio no se midio: " + MovementAudit.ownFirstDelta
    }
    if (MovementAudit.ownPreviousPosition != "(100.00, 100.00, 20.00)" ||
        MovementAudit.ownLastPosition != "(103.00, 100.00, 20.00)") {
        return "el par antes/despues de la posicion propia no se registro: " +
            MovementAudit.ownPreviousPosition + " -> " + MovementAudit.ownLastPosition
    }
    if (kotlin.math.abs(MovementAudit.ownDistanceFromStart - 3f) > 1e-4f) {
        return "la distancia desde el inicio no se midio: " + MovementAudit.ownDistanceFromStart
    }
    MovementAudit.noteFocus(103f, 100f, 21.5f)
    MovementAudit.noteFocus(103f, 100f, 21.5f)
    MovementAudit.noteFocus(106f, 100f, 21.5f)
    if (MovementAudit.focusUpdates != 3 || MovementAudit.focusChanges != 2) {
        return "los contadores de foco no cuadran: " + MovementAudit.focusUpdates + " / " + MovementAudit.focusChanges
    }

    // --- la ventana de FORWARD (revision 2.13b-rev2) -------------------------
    // El ultimo paquete de una carrera es el de soltar (flags 0x0), asi que el
    // informe no puede concluir nada del ultimo ControlFlags: estas cuentas son
    // las que dicen si FORWARD=0x00000001 viajo *mientras* el boton estaba abajo.
    MovementAudit.reset()
    MovementAudit.noteMovementComplete(Vector3(100f, 100f, 20f))
    MovementAudit.noteOwnObject(545575791, Vector3(100f, 100f, 20f))
    MovementAudit.notePad("FORWARD", true)
    // Lo mismo que hace la app: la accion se aplica justo despues de la pulsacion,
    // asi que el estado interno con la ventana abierta queda muestreado.
    MovementAudit.noteHandler(true, AgentControlFlags.AT_POS, heading, heading)
    if (MovementAudit.forwardWindows != 1 || !MovementAudit.forwardWindowOpen) {
        return "pulsar FORWARD deberia abrir una ventana: " + MovementAudit.forwardWindows
    }
    if (MovementAudit.forwardStateOpenSamples < 1 || MovementAudit.forwardStateOpenWithFlag < 1) {
        return "el estado interno con la ventana abierta no registro AT_POS: " +
            MovementAudit.forwardStateOpenSamples + " / " + MovementAudit.forwardStateOpenWithFlag
    }
    if (MovementAudit.forwardWindowPositionBefore != "(100.00, 100.00, 20.00)") {
        return "la posicion antes de pulsar no se guardo: " + MovementAudit.forwardWindowPositionBefore
    }
    MovementAudit.noteCommandBuilt()
    MovementAudit.noteCommandSent(1, AgentControlFlags.AT_POS, Vector3(100f, 100f, 21.5f), sent, 122, true, heading, heading)
    MovementAudit.noteCommandSent(
        2, AgentControlFlags.AT_POS or AgentControlFlags.FAST_AT, Vector3(100f, 100f, 21.5f), sent, 122, true, heading, heading
    )
    MovementAudit.noteCommandSent(3, 0L, Vector3(100f, 100f, 21.5f), sent, 122, true, heading, heading)
    if (MovementAudit.forwardWindowUpdates != 3 || MovementAudit.forwardWindowFlagged != 2 ||
        MovementAudit.forwardWindowUnflagged != 1
    ) {
        return "la ventana no cuenta los paquetes con AT_POS: " + MovementAudit.forwardWindowUpdates +
            " / " + MovementAudit.forwardWindowFlagged + " / " + MovementAudit.forwardWindowUnflagged
    }
    if (MovementAudit.forwardWindowFirstSequence != 1 || MovementAudit.forwardWindowLastSequence != 2) {
        return "la ventana no guarda el primero y el ultimo con AT_POS: " +
            MovementAudit.forwardWindowFirstSequence + " .. " + MovementAudit.forwardWindowLastSequence
    }
    if (MovementAudit.forwardWindowFirstFlags != AgentControlFlags.AT_POS ||
        MovementAudit.forwardWindowLastFlags != (AgentControlFlags.AT_POS or AgentControlFlags.FAST_AT)
    ) {
        return "la ventana no guarda los flags serializados: " + MovementAudit.forwardWindowFlagsSeen
    }
    if (MovementAudit.forwardWindowFlagsSeen != "0x00000001, 0x00000401") {
        return "los flags vistos en la ventana no son los enviados: " + MovementAudit.forwardWindowFlagsSeen
    }
    MovementAudit.noteOwnObject(545575791, Vector3(100f, 103f, 20f))
    MovementAudit.noteOwnObject(545575791, Vector3(100f, 106f, 20f))
    if (MovementAudit.forwardWindowDeltas != 2 || MovementAudit.forwardWindowFirstDelta != "(0.00, 3.00, 0.00)" ||
        MovementAudit.forwardWindowLastDelta != "(0.00, 3.00, 0.00)"
    ) {
        return "los deltas de la ventana no se midieron: " + MovementAudit.forwardWindowDeltaList
    }
    if (kotlin.math.abs(MovementAudit.forwardWindowNetMetres - 6f) > 1e-4f) {
        return "el neto de la ventana no es la distancia a la pulsacion: " + MovementAudit.forwardWindowNetMetres
    }
    MovementAudit.notePad("FORWARD", false)
    if (MovementAudit.forwardWindowOpen) {
        return "soltar FORWARD deberia cerrar la ventana"
    }
    MovementAudit.noteOwnObject(545575791, Vector3(100f, 106.5f, 20f))
    if (MovementAudit.forwardWindowPositionAfter != "(100.00, 106.50, 20.00)") {
        return "la posicion despues de soltar no es la del primer sample posterior: " +
            MovementAudit.forwardWindowPositionAfter
    }
    if (MovementAudit.lastFlagsSent != 0L) {
        return "el ultimo paquete deberia ser el de soltar (0x0)"
    }
    if (MovementAudit.forwardWindowFlagged == 0) {
        return "la ventana deberia recordar el AT_POS aunque el ultimo paquete lleve 0x0"
    }
    // --- la traza temporal (revision 2.13b-rev3) -----------------------------
    // Lo que la ventana de 2.13b-rev2 no medía: los totales, la duracion, los
    // tiempos, y la linea de tiempo que entrelaza pulsaciones, estado interno,
    // envios y movimientos. Es lo que responde "50 pulsaciones, dos paquetes con
    // AT_POS" y "cero movimiento dentro de la ventana".
    if (MovementAudit.forwardTotalUpdates != 3 || MovementAudit.forwardTotalFlagged != 2 ||
        MovementAudit.forwardTotalUnflagged != 1
    ) {
        return "los totales acumulados de la ventana no cuadran: " + MovementAudit.forwardTotalUpdates +
            " / " + MovementAudit.forwardTotalFlagged + " / " + MovementAudit.forwardTotalUnflagged
    }
    if (MovementAudit.forwardWindowsWithoutFlag != 0) {
        return "la ventana SI tenia un AT_POS y no deberia contar como vacia"
    }
    if (!MovementAudit.forwardWindowLog.contains("#1")) {
        return "el resumen por ventana no registro la primera: " + MovementAudit.forwardWindowLog
    }
    if (MovementAudit.forwardFlaggedFirstMs == 0L || MovementAudit.forwardFlaggedLastMs == 0L) {
        return "los tiempos del primer y ultimo AT_POS no se registraron"
    }
    if (!MovementAudit.forwardTrace.contains("P pulsado") ||
        !MovementAudit.forwardTrace.contains("E+ AT_POS") ||
        !MovementAudit.forwardTrace.contains("M ")
    ) {
        return "la traza no entrelaza pulsacion, envio y movimiento: " + MovementAudit.forwardTrace
    }
    MovementAudit.noteCommandSent(4, AgentControlFlags.NUDGE_AT_POS, Vector3(1f, 1f, 1f), sent, 122, true, heading, heading)
    if (MovementAudit.nudgeUpdates != 1) {
        return "los paquetes con NUDGE no se cuentan: " + MovementAudit.nudgeUpdates
    }
    if (MovementAudit.forwardLateSends < 1) {
        return "un envio despues de soltar deberia contarse como tardio: " + MovementAudit.forwardLateSends
    }
    // El caso que el dispositivo mostro (50 pulsaciones, dos paquetes con AT_POS):
    // un toque cuyo envio llega DESPUES de soltar. Aqui es determinista, y es la
    // hipotesis que la medida tiene que poder confirmar o descartar.
    MovementAudit.notePad("FORWARD", true)
    MovementAudit.notePad("FORWARD", false)
    MovementAudit.noteCommandSent(5, AgentControlFlags.AT_POS, Vector3(1f, 1f, 1f), sent, 122, true, heading, heading)
    if (MovementAudit.forwardLateFlagged < 1) {
        return "un AT_POS serializado despues de soltar deberia contarse como tardio con flag: " +
            MovementAudit.forwardLateFlagged
    }
    if (!MovementAudit.forwardLateSendList.contains("AT_POS")) {
        return "la lista de envios tardios no lo registro: " + MovementAudit.forwardLateSendList
    }
    if (MovementAudit.forwardWindowsWithoutFlag != 1) {
        return "la ventana sin ningun AT_POS deberia contarse como vacia: " +
            MovementAudit.forwardWindowsWithoutFlag
    }
    // The window test drove links 1, 3 and 4; the verdict assertions below expect
    // the whole chain alive, so links 2 and 5 are left as a real run leaves them.
    MovementAudit.noteHandler(true, AgentControlFlags.AT_POS, heading, heading)
    MovementAudit.focusChanges = 1
    if (MovementAudit.forwardStateSamples < 1 || MovementAudit.forwardStateWithFlag < 1) {
        return "las muestras del estado interno no registraron AT_POS: " +
            MovementAudit.forwardStateSamples + " / " + MovementAudit.forwardStateWithFlag
    }
    if (MovementAudit.verdict() != MovementBreak.NONE) {
        return "con el escenario completo el veredicto deberia ser 'ninguno': " + MovementAudit.verdict()
    }

    // --- the report the device copies off ------------------------------------

    val report = MovementAudit.reportText()
    for (marker in listOf(
        "enlace 1-entrada", "enlace 2-motor", "enlace 3-comando", "enlace 4-simulador",
        "enlace 5-reflejo local", "Movimiento, veredicto: ninguno",
        "ventana FORWARD (temporal 2.13b-rev3), totales",
        "ventana FORWARD (temporal), tiempos",
        "estado interno del motor",
        "envios TARDIOS",
        "posiciones y secuencias",
        "ultimas ventanas",
        "traza (t=ms"
    )) {
        if (!report.contains(marker)) {
            return "el informe del movimiento no imprime '" + marker + "'"
        }
    }
    if (!MovementAudit.hudLine().startsWith("Movimiento: pulsaciones")) {
        return "la linea del HUD no es la esperada: " + MovementAudit.hudLine()
    }

    MovementAudit.reset()
    println("  MOVIMIENTO: base serializada (correccion M1 aplicada) " + AgentUpdateBuilder.serializedBasis(body).describe())
    println("  MOVIMIENTO: base de referencia (rumbo 0) " + referenceZero.describe())
    println("  MOVIMIENTO: base de la revision anterior (M1, ya no se envia) " + historic.describe())
    println("  MOVIMIENTO: veredictos A/B/C/D/E/ninguno comprobados; cuerpo del AgentUpdate 122 bytes, ControlFlags en 117")
    return "OK"
}

// ---------------------------------------------------------------------------
// Fase 2.13b: el pipeline de texturas (peticion -> cache -> descarga -> decode)
// ---------------------------------------------------------------------------

/**
 * Llama a `pump()` (o a cualquier otra cosa) hasta que la condicion se cumpla.
 *
 * Las descargas y los decodes ocurren en hilos propios, asi que una prueba que
 * los mire tiene que esperar: con una condicion con limite de tiempo, el fallo
 * se ve como un mensaje con el valor que se alcanzo, no como un cuelgue.
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
private class RefusingTextureDecoder : TextureDecoder {
    override val name: String = "rechaza (prueba)"
    override val isAvailable: Boolean = true
    override fun decode(asset: TextureAsset): DecodedTexture? = null
}

/** Llama a pump() hasta que devuelva algo, o se agote el tiempo. */
private fun pumpUntilEvent(pipeline: TexturePipeline, timeoutMillis: Long = 5000): List<TexturePipelineEvent> {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        val events = pipeline.pump()
        if (events.isNotEmpty()) {
            return events
        }
        Thread.sleep(5)
    }
    return emptyList()
}

/**
 * El pipeline de 2.13b, etapa a etapa y sin red.
 *
 * Lo que se comprueba es la cadena entera del apartado 1-2 de la fase: una
 * peticion nueva es un miss y sale al transporte; la repetida no sale; lo que
 * llega se guarda como asset; sin decoder los bytes **se esperan** en vez de
 * contarse como fallo; al enlazar el decoder se decodifican los bytes ya
 * descargados **sin volver a pedirlos**; un codestream rechazado no deja imagen;
 * un error del transporte se recuerda con su motivo; y el tope de peticiones
 * corta sin romper nada.
 */
private fun texturePipelineCheck(): String {
    val uuidA = "11111111-2222-3333-4444-555555555555"
    val uuidB = "22222222-3333-4444-5555-666666666666"
    val uuidC = "33333333-4444-5555-6666-777777777777"
    val uuidD = "44444444-5555-6666-7777-888888888888"

    // --- 1: el proveedor real, sobre un transporte falso ----------------------
    val transport = RecordingTextureTransport()
    val provider = GetTextureAssetProvider(transport, workers = 1)
    if (!provider.isReady) return "el proveedor deberia estar listo"
    if (provider.inFlight != 0) return "en vuelo al empezar: " + provider.inFlight
    // Las dos peticiones tienen que estar *las dos* en vuelo para que la segunda
    // sea el duplicado que esta comprobacion mide: el worker es lo bastante rapido
    // (mas aun despues de 23 comprobaciones que calientan la JVM) para terminar la
    // primera descarga antes de que llegue la segunda llamada, y entonces la
    // segunda seria una peticion nueva y la asercion dependeria del calendario. El
    // transporte se mantiene bloqueado entre las dos llamadas: el worker se para
    // dentro de fetch, la clave sigue en el mapa del proveedor, y la segunda
    // llamada es el duplicado, de forma determinista.
    transport.block = true
    provider.request(uuidA)
    provider.request(uuidA)
    transport.block = false
    if (!awaitCondition(5000) { provider.transportStats.responses >= 1 }) {
        return "no llego la respuesta (respuestas " + provider.transportStats.responses + ")"
    }
    if (transport.fetchCount != 1) return "se pidio " + transport.fetchCount + " veces la misma textura"
    if (provider.received != 2) return "el proveedor conto " + provider.received + " peticiones"
    if (provider.duplicates != 1) return "duplicadas " + provider.duplicates
    if (provider.transportStats.requests != 1) return "peticiones emitidas " + provider.transportStats.requests
    if (provider.transportStats.responses != 1) return "respuestas " + provider.transportStats.responses
    if (provider.transportStats.inFlight != 0) return "en vuelo " + provider.transportStats.inFlight
    if (provider.transportStats.bytes != 128L) return "bytes " + provider.transportStats.bytes
    val drained = provider.drainCompleted()
    if (drained.size != 1) return "resultados drenados " + drained.size
    val ready = drained[0] as? TextureAssetResult.Ready ?: return "el resultado no traia bytes"
    if (ready.asset.textureId != uuidA) return "uuid del asset " + ready.asset.textureId
    if (ready.asset.size != 128) return "tamano del asset " + ready.asset.size
    if (provider.drainCompleted().isNotEmpty()) return "el segundo drenaje devolvio resultados"
    if (provider.pendingCount != 0) return "quedan peticiones pendientes"

    // --- 2: cancelar una peticion en el aire ---------------------------------
    transport.block = true
    provider.request(uuidB)
    if (!awaitCondition(5000) { transport.fetchCount >= 2 }) return "el worker no llego a la peticion"
    if (provider.inFlight != 1) return "en vuelo con una peticion bloqueada: " + provider.inFlight
    provider.cancel(uuidB)
    transport.block = false
    if (!awaitCondition(5000) { provider.transportStats.cancelled == 1 && provider.inFlight == 0 }) {
        return "tras cancelar: canceladas " + provider.transportStats.cancelled +
            ", en vuelo " + provider.inFlight
    }
    if (provider.transportStats.responses != 1) return "una cancelada conto como respuesta"
    if (provider.drainCompleted().isNotEmpty()) return "una cancelada produjo un resultado"

    // --- 3: el pipeline, con el decoder de 2.13b (ausente) -------------------
    val provider2 = GetTextureAssetProvider(transport, workers = 1)
    val pipeline = TexturePipeline(provider2, UnavailableTextureDecoder(), decodeWorkers = 1)
    if (!pipeline.request(uuidA, 0)) return "la primera peticion deberia ser un miss"
    if (pipeline.request(uuidA, 0)) return "la segunda no deberia volver a pedir"
    if (pipeline.request("", 0)) return "un uuid vacio no es una peticion"
    if (pipeline.stats.requested != 1) return "solicitadas " + pipeline.stats.requested
    if (pipeline.cache.stats.cacheHits != 1) return "cache hits " + pipeline.cache.stats.cacheHits
    if (pipeline.cache.stats.cacheMisses != 1) return "cache misses " + pipeline.cache.stats.cacheMisses
    if (!awaitCondition(5000) { provider2.transportStats.responses >= 1 }) {
        return "no llego la descarga del pipeline"
    }
    if (pipeline.pump().isNotEmpty()) return "sin decoder no deberia haber eventos de decode"
    if (pipeline.stats.received != 1) return "recibidas " + pipeline.stats.received
    if (pipeline.stats.ready != 1) return "assets listos " + pipeline.stats.ready
    if (pipeline.cache.count != 1) return "blobs en cache " + pipeline.cache.count
    if (pipeline.cache.cachedBytes != 128L) return "bytes en cache " + pipeline.cache.cachedBytes
    if (pipeline.waitingForDecode != 1) return "esperando decoder " + pipeline.waitingForDecode
    if (pipeline.stats.decodeFailed != 0) return "sin decoder no es un fallo de decode"
    if (pipeline.decodedCount != 0) return "no deberia haber imagenes todavia"
    if (pipeline.asset(uuidA)?.discardLevel != 0) return "el asset no conserva el discard level"

    // --- 4: llega el decoder (2.13c): se decodifica sin volver a descargar ---
    val fetchesBefore = transport.fetchCount
    pipeline.decoder = SyntheticTextureDecoder(24)
    val decodedEvents = pumpUntilEvent(pipeline)
    val decoded = decodedEvents.filterIsInstance<TexturePipelineEvent.Decoded>()
    if (decoded.size != 1) return "deberia decodificarse 1 imagen y fueron " + decoded.size
    if (transport.fetchCount != fetchesBefore) return "se volvio a descargar al enlazar el decoder"
    val image = decoded[0].texture
    if (image.width != 24 || image.height != 24) return "la imagen no es 24x24: " + image
    if (!image.isConsistent) return "la imagen es inconsistente: " + image
    if (!pipeline.isDecoded(uuidA)) return "el pipeline no recuerda la imagen"
    if (pipeline.decodedBytes != 24L * 24L * 4L) return "bytes decodificados " + pipeline.decodedBytes
    if (pipeline.stats.decodeOk != 1) return "decodificadas " + pipeline.stats.decodeOk
    pipeline.noteUploaded(uuidA, 0, image.size, 7L)
    if (pipeline.stats.uploadOk != 1) return "subidas " + pipeline.stats.uploadOk
    if (pipeline.stats.uploadBytes != image.size.toLong()) return "bytes subidos " + pipeline.stats.uploadBytes
    if (pipeline.stats.lastUploadMillis != 7L) return "tiempo de subida " + pipeline.stats.lastUploadMillis

    // --- 5: un codestream que el decoder rechaza -----------------------------
    pipeline.decoder = RefusingTextureDecoder()
    if (!pipeline.request(uuidB, 0)) return "uuidB deberia ser un miss"
    if (!awaitCondition(5000) { provider2.transportStats.responses >= 2 }) return "no llego uuidB"
    val refusedEvents = pumpUntilEvent(pipeline)
    val refused = refusedEvents.filterIsInstance<TexturePipelineEvent.DecodeFailed>()
    if (refused.size != 1) return "se esperaba 1 fallo de decode y hubo " + refused.size
    if (refused[0].textureId != uuidB) return "el fallo de decode es de " + refused[0].textureId
    if (pipeline.stats.decodeFailed != 1) return "fallos de decode " + pipeline.stats.decodeFailed
    if (pipeline.stats.decodeOk != 1) return "un fallo no deberia sumar decodes OK"
    if (pipeline.isDecoded(uuidB)) return "un codestream rechazado no deberia dejar imagen"
    if (pipeline.waitingForDecode != 0) return "un rechazado no se queda en la cola"

    // --- 6: un error del transporte ------------------------------------------
    transport.answer = { _, _ -> TextureTransportReply.Error("HTTP 404") }
    if (!pipeline.request(uuidC, 0)) return "uuidC deberia ser un miss"
    if (!awaitCondition(5000) { provider2.transportStats.failures >= 1 }) return "no llego el error"
    if (pipeline.pump().isNotEmpty()) return "un asset fallido no produce eventos de decode"
    if (pipeline.stats.failed != 1) return "assets fallidos " + pipeline.stats.failed
    if (pipeline.cache.failureOf(uuidC) != "HTTP 404") return "motivo guardado: '" + pipeline.cache.failureOf(uuidC) + "'"
    if (pipeline.cache.state(uuidC) != TextureAssetState.FAILED) return "estado " + pipeline.cache.state(uuidC)
    transport.answer = { _, _ -> TextureTransportReply.Bytes(ByteArray(64), "transporte falso") }

    // --- 7: diferir cuando no hay capability (no es un fallo) ----------------
    transport.ready = false
    val deferredPipeline = TexturePipeline(GetTextureAssetProvider(transport, workers = 1))
    if (deferredPipeline.request(uuidD, 0)) return "no deberia pedir sin capability"
    if (deferredPipeline.stats.deferred != 1) return "diferidas " + deferredPipeline.stats.deferred
    if (deferredPipeline.cache.stats.requested != 0) return "una diferida no es un miss de cache"
    transport.ready = true

    // --- 8: el tope de peticiones (proteccion de la primera ejecucion) -------
    val cappedProvider = GetTextureAssetProvider(transport, workers = 1)
    val cappedPipeline = TexturePipeline(cappedProvider, SyntheticTextureDecoder(8), maxRequests = 1)
    val fetchesBeforeCap = transport.fetchCount
    if (!cappedPipeline.request(uuidA, 0)) return "la primera deberia pasar el tope"
    if (cappedPipeline.request(uuidD, 0)) return "el tope deberia cortar la segunda"
    if (cappedPipeline.stats.capped != 1) return "cortadas " + cappedPipeline.stats.capped
    if (cappedPipeline.cache.stats.requested != 1) return "una cortada no deberia ser un miss"
    // La descarga del pipeline con tope ocurre en su hilo, pero sus contadores
    // solo se mueven en pump(): primero se espera al proveedor (que es quien
    // sabe que la descarga termino) y despues se drena, en ese orden. Esperar
    // `stats.ready` antes de drenar seria esperar un numero que nadie mueve.
    if (!awaitCondition(5000) { cappedProvider.transportStats.responses >= 1 }) {
        return "el pipeline con tope no recibio su unica descarga"
    }
    if (cappedPipeline.stats.ready != 0) return "el pipeline movio su contador sin drenar"
    cappedPipeline.pump()
    if (cappedPipeline.stats.ready != 1) return "el pipeline con tope no dreno su unica descarga"
    // Y el tope corto de verdad: una sola peticion llego al transporte.
    if (transport.fetchCount != fetchesBeforeCap + 1) {
        return "el tope dejo salir " + (transport.fetchCount - fetchesBeforeCap) + " peticiones"
    }

    // --- 9: rearmar el decode (la escena tiro su lado GPU) -------------------
    val fetchesAfterDecode = transport.fetchCount
    pipeline.decoder = SyntheticTextureDecoder(8)
    pipeline.rearmDecodes()
    if (pipeline.decodedCount != 0) return "rearm no olvido las imagenes"
    if (pipeline.waitingForDecode != 2) return "esperando tras rearm " + pipeline.waitingForDecode
    var redecode = 0
    val redecodeDeadline = System.currentTimeMillis() + 5000
    while (redecode < 2 && System.currentTimeMillis() < redecodeDeadline) {
        redecode += pipeline.pump().count { event -> event is TexturePipelineEvent.Decoded }
        Thread.sleep(5)
    }
    if (redecode != 2) return "tras rearm se re-decodificaron " + redecode
    if (transport.fetchCount != fetchesAfterDecode) {
        return "rearm volvio a descargar: " + (transport.fetchCount - fetchesAfterDecode) + " peticiones de mas"
    }

    // --- 10: las lineas del informe que el usuario pidio leer ----------------
    val hud = pipeline.hudLine()
    if (!hud.startsWith("texturas:")) return "la linea del HUD no es la esperada: " + hud
    if (!hud.contains("en vuelo")) return "el HUD no dice cuantas van en vuelo: " + hud
    if (!hud.contains("decode")) return "el HUD no dice como va el decode: " + hud
    val summary = pipeline.summary()
    for (marker in listOf("solicitadas", "recibidas", "listas", "fallidas", "decodificadas", "subidas")) {
        if (!summary.contains(marker)) return "el resumen del pipeline no dice '" + marker + "': " + summary
    }

    println("  TEXTURAS: transporte " + transport.requests.size + " peticiones, " +
        provider2.transportStats.summary())
    println("  TEXTURAS: pipeline " + pipeline.summary() + "  ·  " + pipeline.hudLine())

    pipeline.shutdown()
    cappedPipeline.shutdown()
    deferredPipeline.shutdown()
    provider.shutdown()
    provider2.shutdown()
    cappedProvider.shutdown()
    return "OK"
}

// ---------------------------------------------------------------------------
// Fase 2.13b: la textura llega a la cara (asset -> upload -> material)
// ---------------------------------------------------------------------------

/**
 * La cadena completa desde una cara hasta el material, con el escenario de
 * prueba y el renderer falso: el escenario tiene diez formas y diez texturas
 * (cada fila repite las cinco primeras), asi que prueba de paso la parte mas
 * importante del cache — que veinte caras pidan diez descargas.
 *
 * Y prueba el **fallback**: mientras no hay pixeles, las caras se dibujan con su
 * tinte (aqui, el de depuracion, porque el escenario de prueba no trae tinte por
 * el wire), y las caras sin textura no cuentan como "con textura" en ningun
 * momento.
 */
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
private fun capabilityFailureCheck(): String {
    val uuid = "11111111-2222-3333-4444-555555555555"

    // --- 1: el saneado --------------------------------------------------------
    val secretUrl = "http://simhost.example:9000/CAPS/8f2a-secret-capability-token?x=1"
    // Con saltos de linea, que es como llega un cuerpo de error de verdad: las
    // reglas de cookies y de autorizacion terminan en el fin de linea, asi que una
    // prueba en una sola linea mediria cual de las dos gana, no que las dos actuan.
    val rawText = "Proxy Error reaching " + secretUrl +
        "\nsession_id=abcdef123456\nCookie: session=deadbeef\nAuthorization: Bearer zzz"
    val clean = HttpText.sanitize(rawText)
    if (clean.contains("secret-capability-token") || clean.contains("abcdef123456") ||
        clean.contains("deadbeef") || clean.contains("zzz") || clean.contains("simhost.example:9000/CAPS")) {
        return "el saneado dejo pasar un secreto: " + clean
    }
    if (!clean.contains("[url]")) return "el saneado no sustituyo la URL: " + clean
    if (!clean.contains("session_id=[redacted]")) return "el saneado no tacho el session_id: " + clean
    if (!clean.contains("[cookie]")) return "el saneado no tacho la cookie: " + clean
    if (!clean.contains("[auth]")) return "el saneado no tacho la cabecera de autorizacion: " + clean
    if (clean.contains('\n')) return "el saneado no dejo una sola linea"
    if (HttpText.sanitize("y".repeat(400)).length > 200) return "el saneado no recorta el texto largo"
    if (HttpText.hostOf(secretUrl) != "simhost.example:9000") {
        return "el endpoint deberia ser solo host:puerto y es " + HttpText.hostOf(secretUrl)
    }
    if (HttpText.hostOf("no es una url") != "<url invalida>") {
        return "una url invalida deberia decirse: " + HttpText.hostOf("no es una url")
    }

    // --- 2: los hechos de un 502 con firma de proxy ---------------------------
    val proxiedRecord = CapabilityCallFailure.response(
        capability = "GetTexture",
        endpoint = "simhost.example:9000",
        status = 502,
        contentType = "text/html",
        size = 1234,
        server = "squid/5.7",
        via = "1.1 proxy.local:3128",
        detail = "Proxy Error: cannot reach the upstream server"
    )
    val proxied = textureFailureOf(proxiedRecord, "GetTexture")
    if (proxied.kind != TextureFailureKind.HTTP_ERROR) {
        return "un 502 deberia ser error HTTP y es " + proxied.kind
    }
    if (!proxied.answered || proxied.status != 502 || proxied.size != 1234 ||
        proxied.contentType != "text/html") {
        return "el 502 no se registro con sus hechos: " + proxied.describe()
    }
    if (!proxied.looksProxied) {
        return "un 502 con Via y Server de proxy deberia marcarse como proxy"
    }
    val proxiedText = proxied.describe()
    for (marker in listOf(
        "error HTTP del servidor", "GetTexture POST", "simhost.example:9000", "HTTP 502",
        "text/html", "1234 B", "Server squid/5.7", "Via 1.1 proxy.local:3128",
        "PARECE UN PROXY"
    )) {
        if (!proxiedText.contains(marker)) {
            return "la linea del fallo no dice '" + marker + "': " + proxiedText
        }
    }

    // --- 3: cada forma de fallar, y lo que la distingue -----------------------
    val missing = textureFailureOf(CapabilityCallFailure.notOffered("GetTexture"), "GetTexture")
    if (missing.kind != TextureFailureKind.CAPABILITY_MISSING) {
        return "una capability ausente es " + missing.kind
    }
    if (!missing.describe().contains("capability ausente")) {
        return "la capability ausente no se nombra: " + missing.describe()
    }
    val refused = textureFailureOf(
        CapabilityCallFailure.exception(
            "GetTexture", "simhost.example:9000", java.net.ConnectException("connect timed out")
        ),
        "GetTexture"
    )
    if (refused.kind != TextureFailureKind.TRANSPORT_EXCEPTION) {
        return "una excepcion de conexion es " + refused.kind
    }
    if (refused.answered || refused.status != 0) {
        return "una excepcion no deberia tener respuesta: " + refused.describe()
    }
    if (!refused.describe().contains("ConnectException")) {
        return "la excepcion no se nombra: " + refused.describe()
    }
    val notReady = textureFailureOf(
        CapabilityCallFailure(
            capability = "GetTexture", notOffered = false, answered = false, status = 0,
            contentType = "", size = 0, exception = "", exceptionMessage = "", endpoint = "",
            server = "", via = "", detail = "la capability no llego a estar lista",
            looksProxied = false
        ),
        "GetTexture"
    )
    if (notReady.kind != TextureFailureKind.NOT_READY) {
        return "una espera agotada es " + notReady.kind
    }
    val empty = textureFailureOf(
        CapabilityCallFailure.response(
            "GetTexture", "simhost.example:9000", 200, "application/octet-stream", 0, "", "", ""
        ),
        "GetTexture"
    )
    if (empty.kind != TextureFailureKind.EMPTY_BODY) {
        return "una respuesta valida de 0 bytes es " + empty.kind
    }
    if (!empty.answered || empty.status != 200) {
        return "la respuesta vacia deberia contar como respuesta: " + empty.describe()
    }
    val local = textureFailureOf(
        CapabilityCallFailure.unreadable(
            "GetTexture", "simhost.example:9000", 200, "application/octet-stream", 4096,
            IllegalStateException("no es un codestream")
        ),
        "GetTexture"
    )
    if (local.kind != TextureFailureKind.UNKNOWN) {
        return "una respuesta que no supimos leer es " + local.kind
    }

    // --- 4: los contadores y la particion A-F --------------------------------
    val stats = TextureTransportStats()
    fun note(failure: TextureFetchFailure?, textureId: String) {
        stats.noteRequest()
        stats.noteFailure(failure, textureId, 5L)
    }
    val serverOnly = textureFailureOf(
        CapabilityCallFailure.response("GetTexture", "simhost.example:9000", 500, "text/plain", 12, "", "", ""),
        "GetTexture"
    )
    note(proxied, uuid)
    note(serverOnly, uuid)
    note(refused, uuid)
    note(empty, uuid)
    note(missing, uuid)
    note(local, uuid)
    stats.noteRequest()
    stats.noteResponse(128, 7L)
    if (stats.responses != 7 || stats.failures != 6 || stats.successfulResponses != 1) {
        return "respuestas " + stats.responses + ", fallos " + stats.failures +
            ", con bytes " + stats.successfulResponses
    }
    if (stats.httpErrors != 2 || stats.transportExceptions != 1 || stats.emptyResponses != 1 ||
        stats.capabilityMissing != 1 || stats.unclassified != 1 || stats.notReady != 0 ||
        stats.proxiedFailures != 1) {
        return "los contadores por tipo no cuadran: " + stats.failureSummary()
    }
    if (stats.firstFailure !== proxied) return "el primer fallo no es el que llego primero"
    if (stats.lastFailure !== local) return "el ultimo fallo no es el que llego ultimo"
    if (stats.firstFailureTextureId != uuid || stats.lastFailureTextureId != uuid) {
        return "el fallo no recuerda la textura: " + stats.firstFailureTextureId
    }
    val summary = stats.failureSummary()
    for (marker in listOf(
        "error HTTP 2", "excepcion de transporte 1", "respuesta vacia 1",
        "capability ausente 1", "sin clasificar 1", "total 6"
    )) {
        if (!summary.contains(marker)) return "el resumen de fallos no dice '" + marker + "': " + summary
    }
    val reading = failureReading(stats)
    for (marker in listOf(
        "A capability ausente 1", "B endpoint no disponible 0", "C error HTTP del servidor/grid 1",
        "D proxy/transporte 2", "E respuesta valida de 0 bytes 1", "F lectura local 1", "suma 6"
    )) {
        if (!reading.contains(marker)) return "la lectura A-F no dice '" + marker + "': " + reading
    }
    // Sin fallos la particion tambien tiene que cuadrar (todo a cero).
    if (failureReading(TextureTransportStats()) != "lectura A-F: A capability ausente 0" +
        "  ·  B endpoint no disponible 0  ·  C error HTTP del servidor/grid 0" +
        "  ·  D proxy/transporte 0  ·  E respuesta valida de 0 bytes 0" +
        "  ·  F lectura local 0  ·  suma 0") {
        return "la lectura A-F sin fallos no es la esperada: " + failureReading(TextureTransportStats())
    }

    // --- 5: la forma de la peticion de textura (revision 2.13b-rev2) ----------
    // El dispositivo pidio 755 texturas y recibio 755 HTTP 403 de
    // `asset-cdn.glb.agni.lindenlab.com` con 0 bytes. El endpoint era el correcto;
    // la *forma* no: un CDN de assets sirve `GET <base>/?texture_id=<uuid>`, y un
    // POST con cuerpo a su raiz se rechaza antes de mirarlo. El visor oficial
    // construye exactamente `http_url + "/?texture_id=" + id`.
    val cdnBase = "https://asset-cdn.glb.agni.lindenlab.com"
    val cdn = assetRequestFor(cdnBase, uuid, 0)
    if (cdn.method != "GET" || cdn.isLegacy) {
        return "una URL sin ruta deberia pedirse con GET y sin cuerpo: " + cdn.method
    }
    if (cdn.url != cdnBase + "/?texture_id=" + uuid) {
        return "la URL del CDN no es la que construye el visor oficial: " + cdn.url
    }
    if (assetUrlFor("https://host/", uuid) != "https://host/?texture_id=" + uuid) {
        return "una base con barra final duplica la barra: " + assetUrlFor("https://host/", uuid)
    }
    if (assetUrlFor("https://host?x=1", uuid) != "https://host?x=1&texture_id=" + uuid) {
        return "una base con query ya presente deberia anadir con &: " + assetUrlFor("https://host?x=1", uuid)
    }
    val legacyRequest = assetRequestFor("https://simhost.example:9000/cap/abc", uuid, 3)
    if (legacyRequest.method != "POST" || !legacyRequest.isLegacy) {
        return "una URL con ruta es una capability y deberia pedirse con POST: " + legacyRequest.method
    }
    if (legacyRequest.url != "https://simhost.example:9000/cap/abc") {
        return "la URL de una capability no se modifica: " + legacyRequest.url
    }
    val legacyBody = legacyRequest.legacyBody as? Map<*, *>
    if (legacyBody == null || !legacyBody.containsKey("texture_id") || !legacyBody.containsKey("discard_level")) {
        return "el cuerpo LLSD de la capability no lleva texture_id/discard_level: " + legacyRequest.legacyBody
    }
    val forbidden = textureFailureOf(
        CapabilityCallFailure.response(
            capability = "GetTexture",
            endpoint = "asset-cdn.glb.agni.lindenlab.com",
            status = 403,
            contentType = "text/html",
            size = 398,
            server = "",
            via = "",
            detail = "Access Denied",
            method = "GET"
        ),
        "GetTexture"
    )
    if (forbidden.kind != TextureFailureKind.HTTP_ERROR || forbidden.status != 403) {
        return "un 403 deberia ser error HTTP 403: " + forbidden.describe()
    }
    if (forbidden.method != "GET" || !forbidden.describe().contains("GetTexture GET")) {
        return "el fallo no conserva el metodo con el que se pidio: " + forbidden.describe()
    }
    if (forbidden.looksProxied) {
        return "un 403 del CDN no es un fallo de proxy: " + forbidden.describe()
    }
    // Y las dos categorias siguen separadas: el 403 del CDN y el 502 del proxy
    // caen los dos en C (error HTTP) pero solo el proxy suma en D.
    val mixed = TextureTransportStats()
    mixed.noteRequest()
    mixed.noteFailure(forbidden, uuid, 3L)
    mixed.noteRequest()
    mixed.noteFailure(proxied, uuid, 4L)
    if (mixed.httpErrors != 2 || mixed.proxiedFailures != 1) {
        return "el 403 y el 502 no se cuentan por separado: " + mixed.failureSummary()
    }
    val mixedReading = failureReading(mixed)
    // C is "an HTTP error that is *not* a proxy in the path" and D is "a proxy or a
    // transport failure", so the plain 403 counts in C and the proxied 502 in D:
    // the two categories the user asked to keep apart, on the same counters.
    for (marker in listOf("C error HTTP del servidor/grid 1", "D proxy/transporte 1", "suma 2")) {
        if (!mixedReading.contains(marker)) {
            return "la lectura A-F del 403+502 no dice '" + marker + "': " + mixedReading
        }
    }

    println("  TEXTURAS: fallos por tipo -> " + summary)
    println("  TEXTURAS: " + reading)
    println("  TEXTURAS: primer error -> " + (stats.firstFailure?.describe() ?: "-"))
    println("  TEXTURAS: ultimo error -> " + (stats.lastFailure?.describe() ?: "-"))
    return "OK"
}
