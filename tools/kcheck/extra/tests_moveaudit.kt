// GENERADO desde tests_render.kt por tools/kcheck/make_group.js. NO EDITAR.
// Grupo minimo: solo movementAuditCheck.
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

private fun expect(condition: Boolean, message: String): String = if (condition) "OK" else message

/** `LL_PCODE_*`: line 0x10, circle 0x20, flexible 0x80; circle 0x00, square 0x01, tri 0x02..0x04, half 0x05. */

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

private const val TEST_COUNT = 1

fun renderPipelineChecks(): String {
    val failures = ArrayList<String>()
    fun check(name: String, body: () -> String) {
        val result = try { body() } catch (error: Throwable) { "EXCEPCION " + error.javaClass.simpleName + ": " + error.message }
        if (result != "OK") failures.add(name + " -> " + result)
    }
    check("moveaudit", ::movementAuditCheck)
    return if (failures.isEmpty()) "OK (" + TEST_COUNT + " comprobaciones)" else failures.joinToString(" | ")
}
