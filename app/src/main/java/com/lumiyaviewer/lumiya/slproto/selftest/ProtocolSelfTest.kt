package com.lumiyaviewer.lumiya.slproto.selftest

import android.content.Context
import com.lumiyaviewer.lumiya.slproto.base.LLUUIDUtil
import com.lumiyaviewer.lumiya.slproto.circuit.PacketFlags
import com.lumiyaviewer.lumiya.slproto.circuit.SLPacket
import com.lumiyaviewer.lumiya.slproto.circuit.SLPacketWriter
import com.lumiyaviewer.lumiya.slproto.circuit.ZeroCodec
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDBinary
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDParser
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDUuid
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDWriter
import com.lumiyaviewer.lumiya.slproto.messages.LLSDMessageDecoder
import com.lumiyaviewer.lumiya.slproto.messages.Frequency
import com.lumiyaviewer.lumiya.slproto.messages.MessageDef
import com.lumiyaviewer.lumiya.slproto.messages.MessageTemplate
import com.lumiyaviewer.lumiya.slproto.messages.MessageTemplateLoader
import com.lumiyaviewer.lumiya.slproto.messages.SLMessage
import com.lumiyaviewer.lumiya.slproto.messages.SLMessageCodec
import com.lumiyaviewer.lumiya.slproto.world.ObjectUpdateDecoder
import com.lumiyaviewer.lumiya.slproto.world.ObjectUpdateDiagnostics
import com.lumiyaviewer.lumiya.slproto.world.PrimShape
import com.lumiyaviewer.lumiya.slproto.world.SceneObject
import com.lumiyaviewer.lumiya.slproto.world.ShapeSource
import com.lumiyaviewer.lumiya.slproto.world.WorldModel
import com.lumiyaviewer.lumiya.slproto.world.TerrainCodec

class SelfTestReport(val lines: List<String>, val passed: Int, val failed: Int) {

    val ok: Boolean get() = failed == 0

    fun toText(): String {
        val builder = StringBuilder()
        for (line in lines) {
            builder.append(line).append('\n')
        }
        builder.append('\n')
        if (ok) {
            builder.append("RESULTADO: ").append(passed).append(" pruebas correctas")
        } else {
            builder.append("RESULTADO: ").append(passed).append(" correctas, ")
                .append(failed).append(" fallidas")
        }
        return builder.toString()
    }
}

/**
 * Exercises the whole wire layer (template parsing, message codec, zerocoding,
 * packet framing, appended ACKs) plus LLSD login parsing, without touching the
 * network. If this passes, a failure to connect is a network/credentials issue,
 * not a protocol bug.
 */
object ProtocolSelfTest {

    fun run(context: Context): SelfTestReport {
        val lines = ArrayList<String>()
        var passed = 0
        var failed = 0

        fun check(name: String, body: () -> String) {
            try {
                val detail = body()
                passed += 1
                lines.add("[ OK ] " + name + (if (detail.isEmpty()) "" else "  -> " + detail))
            } catch (t: Throwable) {
                failed += 1
                lines.add("[FALLO] " + name + "  -> " + (t.message ?: t.javaClass.simpleName))
            }
        }

        fun expect(condition: Boolean, message: String) {
            if (!condition) {
                throw IllegalStateException(message)
            }
        }

        check("Plantillas de mensajes (message_template.msg)") {
            if (MessageTemplate.loadedCount == 0) {
                MessageTemplateLoader.load(context)
            }
            expect(MessageTemplate.loadedCount > 400, "solo " + MessageTemplate.loadedCount + " mensajes")
            "cargados " + MessageTemplate.loadedCount + " mensajes"
        }

        check("Identificadores de los mensajes clave") {
            val expected = arrayOf(
                arrayOf("StartPingCheck", "HIGH", "1"),
                arrayOf("CompletePingCheck", "HIGH", "2"),
                arrayOf("UseCircuitCode", "LOW", "3"),
                arrayOf("AgentUpdate", "HIGH", "4"),
                arrayOf("ChatFromViewer", "LOW", "80"),
                arrayOf("AgentThrottle", "LOW", "81"),
                arrayOf("ChatFromSimulator", "LOW", "139"),
                arrayOf("RegionHandshake", "LOW", "148"),
                arrayOf("RegionHandshakeReply", "LOW", "149"),
                arrayOf("CompleteAgentMovement", "LOW", "249"),
                arrayOf("AgentMovementComplete", "LOW", "250"),
                arrayOf("LogoutRequest", "LOW", "252"),
                arrayOf("LogoutReply", "LOW", "253"),
                arrayOf("AgentDataUpdateRequest", "LOW", "386"),
                arrayOf("AgentDataUpdate", "LOW", "387"),
                arrayOf("PacketAck", "LOW", "65531")
            )
            for (row in expected) {
                val def = MessageTemplate.byName(row[0])
                expect(def != null, "falta " + row[0])
                val wanted = Frequency.valueOf(row[1])
                expect(def!!.frequency == wanted, row[0] + " frecuencia " + def.frequency)
                expect(def.id == row[2].toInt(), row[0] + " id " + def.id)
            }
            "" + expected.size + " mensajes verificados"
        }

        check("Zero-coding (compresion de ceros)") {
            val source = ByteArray(6 + 300)
            source[0] = PacketFlags.ZEROCODED.toByte()
            for (i in 1 until 6) {
                source[i] = 0xAB.toByte()
            }
            for (i in 6 until source.size) {
                source[i] = 0
            }
            val encoded = ZeroCodec.encode(source, source.size)
            expect(encoded.size < source.size / 4, "no comprimio: " + encoded.size)
            val decoded = ZeroCodec.decode(encoded, encoded.size)
            expect(decoded.size == source.size, "tamano " + decoded.size)
            for (i in source.indices) {
                expect(decoded[i] == source[i], "byte " + i)
            }
            "300 ceros -> " + encoded.size + " bytes"
        }

        check("Codec de ChatFromViewer con acentos") {
            val def = MessageTemplate.byName("ChatFromViewer")
                ?: throw IllegalStateException("sin plantilla")
            val agentId = LLUUIDUtil.random()
            val sessionId = LLUUIDUtil.random()
            val text = "Hola \u00F1and\u00FA, \u00BFqu\u00E9 tal? \u4F60\u597D"
            val message = SLMessage(def)
            message.block("AgentData").set("AgentID", agentId)
            message.block("AgentData").set("SessionID", sessionId)
            message.block("ChatData").setString("Message", text)
            message.block("ChatData").set("Type", 1)
            message.block("ChatData").set("Channel", 0)

            val body = SLMessageCodec.encode(message)
            expect(body.size > 32, "cuerpo demasiado corto")
            val decoded = SLMessageCodec.decode(def, body, 0, body.size)
                ?: throw IllegalStateException("no se pudo decodificar")
            val back = decoded.block("ChatData").string("Message")
            expect(back == text, "texto '" + back + "'")
            expect(decoded.block("AgentData").uuid("AgentID") == agentId, "agent id")
            expect(decoded.block("ChatData").u8("Type") == 1, "tipo")
            "cuerpo de " + body.size + " bytes reconstruido"
        }

        check("Trama de paquete UDP (cabecera + zerocoding)") {
            val def = MessageTemplate.byName("ChatFromViewer")
                ?: throw IllegalStateException("sin plantilla")
            val message = SLMessage(def)
            message.block("AgentData").set("AgentID", LLUUIDUtil.random())
            message.block("AgentData").set("SessionID", LLUUIDUtil.random())
            message.block("ChatData").setString("Message", "prueba de trama")
            message.block("ChatData").set("Type", 1)
            message.block("ChatData").set("Channel", 0)

            val body = SLMessageCodec.encode(message)
            val packet = SLPacketWriter.build(4242, def, body, true)
            val onWire = ZeroCodec.encode(packet, packet.size)
            val parsed = SLPacket.parse(onWire, onWire.size)
                ?: throw IllegalStateException("paquete rechazado")
            expect(parsed.header.sequence == 4242, "secuencia " + parsed.header.sequence)
            expect(parsed.header.messageId == 80, "id " + parsed.header.messageId)
            expect(parsed.header.frequency == Frequency.LOW, "frecuencia")
            expect(parsed.header.reliable, "no marcado fiable")
            expect(parsed.def?.name == "ChatFromViewer", "plantilla resuelta mal")
            val decoded = SLMessageCodec.decode(
                parsed.def!!, parsed.data, parsed.bodyOffset, parsed.bodyLength
            ) ?: throw IllegalStateException("cuerpo ilegible")
            val text = decoded.block("ChatData").string("Message")
            expect(text == "prueba de trama", "texto '" + text + "'")
            "paquete de " + onWire.size + " bytes en el cable"
        }

        check("ACKs adjuntos al final del paquete") {
            val def = MessageTemplate.byName("PacketAck")
                ?: throw IllegalStateException("sin plantilla")
            val message = SLMessage(def)
            message.addBlock("Packets").set("ID", 7L)
            message.addBlock("Packets").set("ID", 8L)
            val body = SLMessageCodec.encode(message)
            val packet = SLPacketWriter.build(99, def, body, false)
            val merged = ByteArray(packet.size + 9)
            System.arraycopy(packet, 0, merged, 0, packet.size)
            merged[0] = (merged[0].toInt() or PacketFlags.APPENDED_ACKS).toByte()
            var offset = packet.size
            writeU32Be(merged, offset, 11)
            offset += 4
            writeU32Be(merged, offset, 12)
            offset += 4
            merged[offset] = 2

            val parsed = SLPacket.parse(merged, merged.size)
                ?: throw IllegalStateException("paquete rechazado")
            expect(parsed.acks.size == 2, "acks " + parsed.acks.size)
            expect(parsed.acks[0] == 11, "ack0 " + parsed.acks[0])
            expect(parsed.acks[1] == 12, "ack1 " + parsed.acks[1])
            val decoded = SLMessageCodec.decode(
                parsed.def!!, parsed.data, parsed.bodyOffset, parsed.bodyLength
            ) ?: throw IllegalStateException("cuerpo ilegible")
            val ids = decoded.blocks["Packets"] ?: throw IllegalStateException("sin bloque Packets")
            expect(ids.size == 2, "bloques " + ids.size)
            expect(ids[0].u32i("ID") == 7, "id0")
            expect(ids[1].u32i("ID") == 8, "id1")
            "ACKs 11 y 12 leidos"
        }

        check("UUID ida y vuelta") {
            val text = LLUUIDUtil.random()
            val bytes = LLUUIDUtil.toBytes(text)
            expect(bytes.size == 16, "tamano")
            val back = LLUUIDUtil.fromBytes(bytes, 0)
            expect(back == text, back)
            val undashed = text.replace("-", "")
            expect(LLUUIDUtil.fromBytes(LLUUIDUtil.toBytes(undashed), 0) == text, "sin guiones")
            "16 bytes"
        }

        check("LLSD del login (XML)") {
            val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><llsd><map>" +
                "<key>login</key><boolean>1</boolean>" +
                "<key>message</key><string>Bienvenido</string>" +
                "<key>agent_id</key><uuid>11111111-2222-3333-4444-555555555555</uuid>" +
                "<key>circuit_code</key><integer>1234567</integer>" +
                "<key>sim_ip</key><string>203.0.113.7</string>" +
                "<key>sim_port</key><integer>13000</integer>" +
                "<key>home</key><map><key>region_handle</key><integer>1099511628032</integer></map>" +
                "<key>inventory-root</key><array><map>" +
                "<key>folder_id</key><uuid>aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</uuid>" +
                "</map></array>" +
                "<key>inventory-skeleton</key><array><map>" +
                "<key>folder_id</key><uuid>aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</uuid>" +
                "<key>parent_id</key><uuid>00000000-0000-0000-0000-000000000000</uuid>" +
                "<key>name</key><string>Contenido</string>" +
                "<key>type_default</key><integer>8</integer>" +
                "</map><map>" +
                "<key>folder_id</key><uuid>bbbbbbbb-1111-2222-3333-444444444444</uuid>" +
                "<key>parent_id</key><uuid>aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</uuid>" +
                "<key>name</key><string>Notas</string>" +
                "<key>type_default</key><integer>7</integer>" +
                "</map></array>" +
                "</map></llsd>"
            val parsed = LLSDParser.parse(xml)
            val map = LLSDParser.asMap(parsed)
            expect(LLSDParser.asBoolean(map["login"]), "login")
            expect(LLSDParser.asString(map["message"]) == "Bienvenido", "message")
            expect(LLSDParser.asInt(map["circuit_code"]) == 1234567, "circuit_code")
            expect(LLSDParser.asString(map["sim_ip"]) == "203.0.113.7", "sim_ip")
            expect(LLSDParser.asInt(map["sim_port"]) == 13000, "sim_port")
            val home = LLSDParser.asMap(map["home"])
            expect(LLSDParser.asLong(home["region_handle"]) == 1099511628032L, "home")
            val root = map["inventory-root"] as? List<*> ?: throw IllegalStateException("inventory-root")
            expect(root.size == 1, "raiz")
            expect(
                LLSDParser.asString(LLSDParser.asMap(root[0])["folder_id"]) ==
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "folder_id de la raiz"
            )
            val skeleton = map["inventory-skeleton"] as? List<*> ?: throw IllegalStateException("skeleton")
            expect(skeleton.size == 2, "esqueleto " + skeleton.size)
            val second = LLSDParser.asMap(skeleton[1])
            expect(LLSDParser.asString(second["name"]) == "Notas", "nombre de carpeta")
            expect(LLSDParser.asInt(second["type_default"]) == 7, "type_default")
            "login, agente, simulador, region anidada e inventario"
        }

        check("LLSD de la peticion de login (escritura)") {
            val payload = LinkedHashMap<String, Any?>()
            payload["first"] = "Ana"
            payload["last"] = "Perez"
            payload["passwd"] = "secreto&raro"
            payload["start"] = "last"
            payload["agree_to_tos"] = true
            payload["options"] = listOf("inventory-root", "buddy-list")
            val xml = LLSDWriter.toXml(payload)
            expect(xml.contains("<key>first</key>"), "sin clave first")
            expect(xml.contains("<string>Ana</string>"), "sin valor Ana")
            expect(xml.contains("&amp;"), "sin escapar el &")
            expect(xml.contains("<array>"), "sin array de opciones")
            val roundTrip = LLSDParser.asMap(LLSDParser.parse(xml))
            expect(LLSDParser.asString(roundTrip["passwd"]) == "secreto&raro", "passwd")
            expect(LLSDParser.asString(roundTrip["first"]) == "Ana", "first")
            expect(LLSDParser.asBoolean(roundTrip["agree_to_tos"]), "tos")
            "XML generado y releido"
        }

        check("Codec de terreno (LayerData DCT)") {
            val report = TerrainCodec.roundTripSelfTest()
            expect(report.startsWith("OK"), report)
            report
        }

        check("LLSD binario (respuestas de texturas y assets)") {
            val source = LinkedHashMap<String, Any?>()
            source["name"] = "prueba \u00F1andu"
            source["count"] = 7
            source["ratio"] = 1.5
            source["flag"] = true
            source["id"] = LLSDUuid("11111111-2222-3333-4444-555555555555")
            source["blob"] = byteArrayOf(1, 2, 3, 4)
            source["list"] = listOf(1, 2, 3)
            val bytes = LLSDBinary.encode(source)
            expect(bytes[0].toInt() == '{'.code, "no empieza con '{'")
            expect(bytes[1].toInt() == 0 && bytes[4].toInt() == 7, "el contador no es big endian")
            val parsed = LLSDParser.asMap(LLSDParser.parseBytes(bytes, "application/llsd+binary"))
            expect(LLSDParser.asString(parsed["name"]) == "prueba \u00F1andu", "cadena")
            expect(LLSDParser.asInt(parsed["count"]) == 7, "entero")
            expect(Math.abs(LLSDParser.asReal(parsed["ratio"]) - 1.5) < 1e-9, "real")
            expect(LLSDParser.asBoolean(parsed["flag"]), "booleano")
            expect(
                LLSDParser.asString(parsed["id"]) == "11111111-2222-3333-4444-555555555555",
                "uuid"
            )
            val blob = parsed["blob"] as? ByteArray ?: throw IllegalStateException("sin binario")
            expect(blob.size == 4 && blob[3].toInt() == 4, "binario")
            val list = parsed["list"] as? List<*> ?: throw IllegalStateException("sin lista")
            expect(list.size == 3 && LLSDParser.asInt(list[2]) == 3, "lista")
            // The same bytes, this time without the content type telling us.
            val sniffed = LLSDParser.asMap(LLSDParser.parseBytes(bytes))
            expect(LLSDParser.asInt(sniffed["count"]) == 7, "deteccion automatica")
            bytes.size.toString() + " bytes ida y vuelta"
        }

        check("Mensaje instantaneo de la cola de eventos (LLSD)") {
            val body = LinkedHashMap<String, Any?>()
            val agentData = LinkedHashMap<String, Any?>()
            agentData["AgentID"] = "6729528d-147f-4eed-a10e-cbf835a6e07d"
            agentData["SessionID"] = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            body["AgentData"] = agentData
            val block = LinkedHashMap<String, Any?>()
            block["FromGroup"] = false
            block["ToAgentID"] = "11111111-2222-3333-4444-555555555555"
            block["ParentEstateID"] = 0
            block["RegionID"] = "22222222-3333-4444-5555-666666666666"
            block["Position"] = listOf(10.0, 20.0, 30.0)
            block["Offline"] = 0
            block["Dialog"] = 0
            block["ID"] = "33333333-4444-5555-6666-777777777777"
            block["Timestamp"] = 0
            block["FromAgentName"] = "ExeQiel Resident"
            block["Message"] = "Hola \u00BFqu\u00E9 tal?"
            block["BinaryBucket"] = ""
            body["MessageBlock"] = block

            val message = LLSDMessageDecoder.decode("ImprovedInstantMessage", body)
                ?: throw IllegalStateException("no se decodifico")
            expect(
                message.firstBlock("AgentData")?.uuid("AgentID") ==
                    "6729528d-147f-4eed-a10e-cbf835a6e07d",
                "remitente"
            )
            val messageBlock = message.firstBlock("MessageBlock")
                ?: throw IllegalStateException("sin bloque de mensaje")
            expect(messageBlock.string("FromAgentName") == "ExeQiel Resident", "nombre")
            expect(messageBlock.string("Message") == "Hola \u00BFqu\u00E9 tal?", "texto")
            expect(messageBlock.u8("Dialog") == 0, "dialogo")
            expect(messageBlock.uuid("ID") == "33333333-4444-5555-6666-777777777777", "sesion")
            expect(messageBlock.vector3("Position").z == 30f, "posicion")
            "remitente, texto, sesion y posicion"
        }

        // ------------------------------------------------------------------
        // Object-update decoding and the incremental shape model. These run on
        // the device from this same screen, with no region and no network, so
        // the decoder can be verified on the exact build that is on screen.
        // ------------------------------------------------------------------

        check("ObjectUpdateCompressed de 113 bytes: path/profile se leen") {
            val blob = buildCompressedBlock(
                localId = 9001,
                pathCurve = 0x10,
                profileCurve = 0x00
            )
            expect(blob.size == 113, "el bloque de prueba mide " + blob.size + " bytes")
            val model = WorldModel()
            val applied = ObjectUpdateDecoder.applyCompressed(compressedMessage(blob), model)
            expect(applied == 1, "bloques aplicados " + applied)
            val object_ = model.get(9001) ?: throw IllegalStateException("objeto no creado")
            expect(object_.paramsKnown, "los parametros no se leyeron (el fallo original)")
            expect(object_.hasCompleteShape, "sin forma completa")
            expect(object_.pathCurve == 0x10, "pathCurve 0x" + Integer.toHexString(object_.pathCurve))
            expect(object_.profileCurve == 0x00, "profileCurve 0x" + Integer.toHexString(object_.profileCurve))
            expect(object_.shape == PrimShape.CYLINDER, "forma " + object_.shape)
            expect(object_.extraParamsSize == 1, "ExtraParams " + object_.extraParamsSize + " bytes")
            expect(object_.position.z == 30f, "posicion z " + object_.position.z)
            "parametros leidos, forma " + object_.shapeText + ", ExtraParams 1 byte"
        }

        check("Modelo incremental: los updates parciales no borran la forma") {
            val model = WorldModel()
            ObjectUpdateDecoder.applyCompressed(
                compressedMessage(
                    buildCompressedBlock(localId = 9002, pathCurve = 0x20, profileCurve = 0x05)
                ),
                model
            )
            val object_ = model.get(9002) ?: throw IllegalStateException("objeto no creado")
            expect(object_.shape == PrimShape.SPHERE, "forma inicial " + object_.shape)
            ObjectUpdateDecoder.applyTerse(terseMessage(buildTerseBlock(9002)), model)
            ObjectUpdateDecoder.applyTerse(terseMessage(buildTerseBlock(9002, x = 9f)), model)
            val after = model.get(9002) ?: throw IllegalStateException("objeto perdido")
            expect(after.paramsKnown, "un update terse borro los parametros")
            expect(after.pathCurve == 0x20 && after.profileCurve == 0x05, "la definicion cambio")
            expect(after.shape == PrimShape.SPHERE, "la forma cambio a " + after.shape)
            expect(after.shapeSource == ShapeSource.PERSISTED, "origen " + after.shapeSource)
            expect(after.position.x == 9f, "el terse no movio el objeto")
            "forma conservada tras 2 updates terse (" + after.shapeSource.label + ")"
        }

        check("Sin definicion recibida la forma es UNKNOWN, no CYLINDER") {
            val model = WorldModel()
            ObjectUpdateDecoder.applyTerse(terseMessage(buildTerseBlock(9003, avatar = false)), model)
            val object_ = model.get(9003) ?: throw IllegalStateException("objeto no creado")
            expect(!object_.paramsKnown, "un terse dijo tener parametros")
            expect(object_.shapeText == "UNKNOWN/MISSING_SHAPE", "shapeText " + object_.shapeText)
            expect(!object_.shapeIsFallback, "el objeto se declara en fallback")
            val counts = model.shapeCounts()
            expect(counts.withMissingShape == 1, "sin forma " + counts.withMissingShape)
            expect(counts.usingShapeFallback == 0, "usando fallback " + counts.usingShapeFallback)
            "un terse produce UNKNOWN/MISSING_SHAPE, nunca un cilindro por defecto"
        }

        check("ExtraParams con longitud imposible (0xCC000007) no rompe el parser") {
            val model = WorldModel()
            // count=1, tipo flexible (0x10), longitud 0xCC000007: el valor exacto
            // del informe del dispositivo.
            val bogus = byteArrayOf(1, 0x10, 0x00, 7, 0x00, 0x00, 0xCC.toByte())
            val before = ObjectUpdateDiagnostics.extraParamBogusLengths
            var thrown: String? = null
            try {
                ObjectUpdateDecoder.applyCompressed(
                    compressedMessage(
                        buildCompressedBlock(localId = 9004, extraParams = bogus, pathCurve = 0x10, profileCurve = 0x01)
                    ),
                    model
                )
            } catch (error: Throwable) {
                thrown = error.javaClass.name + ": " + error.message
            }
            expect(thrown == null, "el parser lanzo " + thrown)
            expect(
                ObjectUpdateDiagnostics.extraParamBogusLengths == before + 1,
                "la longitud imposible no se registro"
            )
            val object_ = model.get(9004)
            expect(object_ != null && !object_.paramsKnown, "se leyeron parametros de bytes ilegibles")
            "longitud 0xCC000007 rechazada y registrada, sin excepcion"
        }

        return SelfTestReport(lines, passed, failed)
    }

    /** The same 113-byte block the headless checks and the device report use. */
    private fun buildCompressedBlock(
        localId: Int,
        extraParams: ByteArray = byteArrayOf(0),
        pathCurve: Int = 0x10,
        profileCurve: Int = 0x00,
        textureEntrySize: Int = 1
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream(128)
        out.write(ByteArray(16) { (it + 1).toByte() })
        writeLe32(out, localId)
        out.write(SceneObject.PCODE_PRIM)
        out.write(0) // state
        writeLe32(out, 0) // CRC
        out.write(3) // material
        out.write(0) // click action
        writeVec(out, 1f, 2f, 3f) // scale
        writeVec(out, 10f, 20f, 30f) // position
        writeVec(out, 0f, 0f, 0f) // rotation xyz
        writeLe32(out, 0) // flags
        out.write(ByteArray(16)) // owner
        out.write(extraParams, 0, extraParams.size)
        out.write(pathCurve)
        writeLe16(out, 0)
        writeLe16(out, 0)
        out.write(100)
        out.write(100)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(profileCurve)
        writeLe16(out, 0)
        writeLe16(out, 0)
        writeLe16(out, 0)
        writeLe32(out, textureEntrySize)
        out.write(ByteArray(textureEntrySize))
        return out.toByteArray()
    }

    /** `LocalID State Avatar Position …`, movement only. */
    private fun buildTerseBlock(localId: Int, x: Float = 1f, avatar: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream(64)
        writeLe32(out, localId)
        out.write(0)
        out.write(if (avatar) 1 else 0)
        if (avatar) {
            out.write(ByteArray(16))
        }
        writeVec(out, x, 2f, 3f)
        out.write(ByteArray(6))
        out.write(ByteArray(6))
        writeLe16(out, 32768)
        writeLe16(out, 0)
        writeLe16(out, 0)
        writeLe16(out, 0)
        return out.toByteArray()
    }

    private fun compressedMessage(blob: ByteArray): SLMessage =
        objectMessage("ObjectUpdateCompressed", 13, blob)

    private fun terseMessage(blob: ByteArray): SLMessage =
        objectMessage("ImprovedTerseObjectUpdate", 15, blob)

    private fun objectMessage(name: String, id: Int, blob: ByteArray): SLMessage {
        val message = SLMessage(MessageDef(name, Frequency.HIGH, id, false, false, emptyList()))
        message.addBlock("ObjectData").set("Data", blob)
        return message
    }

    private fun writeLe16(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeLe32(out: java.io.ByteArrayOutputStream, value: Int) {
        for (i in 0 until 4) {
            out.write((value ushr (i * 8)) and 0xFF)
        }
    }

    private fun writeVec(out: java.io.ByteArrayOutputStream, x: Float, y: Float, z: Float) {
        writeLe32(out, java.lang.Float.floatToIntBits(x))
        writeLe32(out, java.lang.Float.floatToIntBits(y))
        writeLe32(out, java.lang.Float.floatToIntBits(z))
    }

    private fun writeU32Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}
