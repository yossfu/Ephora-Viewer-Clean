// GENERADO desde tests_render.kt por tools/kcheck/make_group.js. NO EDITAR.
// Grupo de diagnostico: los fallos de GetTexture, sin escena ni red.
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


private const val TEST_COUNT = 1

fun renderPipelineChecks(): String {
    val failures = ArrayList<String>()
    fun check(name: String, body: () -> String) {
        val result = try { body() } catch (error: Throwable) { "EXCEPCION " + error.javaClass.simpleName + ": " + error.message }
        if (result != "OK") failures.add(name + " -> " + result)
    }
    check("capsfail", ::capabilityFailureCheck)
    return if (failures.isEmpty()) "OK (" + TEST_COUNT + " comprobaciones)" else failures.joinToString(" | ")
}
