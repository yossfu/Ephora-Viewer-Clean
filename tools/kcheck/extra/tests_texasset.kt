// GENERADO desde tests_render.kt por tools/kcheck/make_group.js. NO EDITAR.
// Grupo minimo: solo texturePipelineCheck.
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

private const val TEST_COUNT = 1

fun renderPipelineChecks(): String {
    val failures = ArrayList<String>()
    fun check(name: String, body: () -> String) {
        val result = try { body() } catch (error: Throwable) { "EXCEPCION " + error.javaClass.simpleName + ": " + error.message }
        if (result != "OK") failures.add(name + " -> " + result)
    }
    check("texasset", ::texturePipelineCheck)
    return if (failures.isEmpty()) "OK (" + TEST_COUNT + " comprobaciones)" else failures.joinToString(" | ")
}
