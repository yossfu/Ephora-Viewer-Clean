package com.lumiyaviewer.lumiya.slproto.messages

import com.lumiyaviewer.lumiya.slproto.base.Quaternion
import com.lumiyaviewer.lumiya.slproto.base.Vector3
import com.lumiyaviewer.lumiya.slproto.base.Vector3d
import com.lumiyaviewer.lumiya.slproto.base.Vector4
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDParser

/**
 * Decodes the LLSD map form of a message.
 *
 * The simulator delivers everything that is not a movement or chat packet as an
 * *event* over the `EventQueueGet` capability: instant messages, offline
 * messages, teleport requests, script dialogs, group notices. Each event is an
 * LLSD map named after the UDP message it mirrors, so the same
 * `message_template.msg` that drives the binary codec drives this decoder too —
 * one generic path instead of a hand-written parser per event.
 */
object LLSDMessageDecoder {

    fun decode(name: String, body: Any?): SLMessage? {
        val def = MessageTemplate.byName(name) ?: return null
        val map = LLSDParser.asMap(body)
        if (map.isEmpty()) {
            return null
        }
        val message = SLMessage(def)
        for (blockDef in def.blocks) {
            when (blockDef.repeat) {
                BlockRepeat.SINGLE -> fill(blockDef, message.block(blockDef.name), map[blockDef.name])
                BlockRepeat.MULTIPLE -> {
                    for (i in 0 until blockDef.count) {
                        fill(blockDef, message.block(blockDef.name, i), at(map[blockDef.name], i))
                    }
                }
                BlockRepeat.VARIABLE -> {
                    val list = map[blockDef.name] as? List<*> ?: emptyList<Any?>()
                    for (entry in list) {
                        fill(blockDef, message.addBlock(blockDef.name), entry)
                    }
                }
            }
        }
        return message
    }

    private fun at(value: Any?, index: Int): Any? {
        val list = value as? List<*> ?: return value
        return if (index < list.size) list[index] else null
    }

    private fun fill(blockDef: BlockDef, block: Block, value: Any?) {
        val map = LLSDParser.asMap(value)
        if (map.isEmpty()) {
            return
        }
        for (field in blockDef.fields) {
            val raw = map[field.name] ?: continue
            block.set(field.name, convert(field, raw))
        }
    }

    private fun convert(field: FieldDef, raw: Any): Any {
        return when (field.type) {
            FieldType.BOOL -> LLSDParser.asBoolean(raw)
            FieldType.U8, FieldType.S8,
            FieldType.U16, FieldType.S16,
            FieldType.U32, FieldType.S32,
            FieldType.IPADDR, FieldType.IPPORT -> LLSDParser.asInt(raw)
            FieldType.U64, FieldType.S64 -> LLSDParser.asLong(raw)
            FieldType.F32 -> LLSDParser.asReal(raw).toFloat()
            FieldType.F64 -> LLSDParser.asReal(raw)
            FieldType.LLUUID -> LLSDParser.asString(raw)
            FieldType.VECTOR3 -> vector3(raw)
            FieldType.VECTOR3D -> vector3d(raw)
            FieldType.VECTOR4 -> vector4(raw)
            FieldType.QUATERNION -> quaternion(raw)
            FieldType.VARIABLE -> variable(raw)
            FieldType.FIXED -> fixed(raw, field.extra)
        }
    }

    private fun variable(raw: Any): ByteArray {
        return when (raw) {
            is ByteArray -> raw
            is String -> {
                val body = raw.toByteArray(Charsets.UTF_8)
                val out = ByteArray(body.size + 1)
                System.arraycopy(body, 0, out, 0, body.size)
                out
            }
            else -> raw.toString().toByteArray(Charsets.UTF_8)
        }
    }

    private fun fixed(raw: Any, size: Int): ByteArray {
        val source = when (raw) {
            is ByteArray -> raw
            else -> raw.toString().toByteArray(Charsets.UTF_8)
        }
        val out = ByteArray(size)
        System.arraycopy(source, 0, out, 0, minOf(size, source.size))
        return out
    }

    private fun numbers(raw: Any): DoubleArray {
        val list = raw as? List<*> ?: return DoubleArray(0)
        val out = DoubleArray(list.size)
        for (i in list.indices) {
            out[i] = LLSDParser.asReal(list[i])
        }
        return out
    }

    private fun vector3(raw: Any): Vector3 {
        val values = numbers(raw)
        if (values.size < 3) {
            return Vector3.ZERO
        }
        return Vector3(values[0].toFloat(), values[1].toFloat(), values[2].toFloat())
    }

    private fun vector3d(raw: Any): Vector3d {
        val values = numbers(raw)
        if (values.size < 3) {
            return Vector3d.ZERO
        }
        return Vector3d(values[0], values[1], values[2])
    }

    private fun vector4(raw: Any): Vector4 {
        val values = numbers(raw)
        if (values.size < 4) {
            return Vector4.ZERO
        }
        return Vector4(values[0].toFloat(), values[1].toFloat(), values[2].toFloat(), values[3].toFloat())
    }

    private fun quaternion(raw: Any): Quaternion {
        val values = numbers(raw)
        if (values.size < 4) {
            return Quaternion.IDENTITY
        }
        return Quaternion(values[0].toFloat(), values[1].toFloat(), values[2].toFloat(), values[3].toFloat())
    }
}
