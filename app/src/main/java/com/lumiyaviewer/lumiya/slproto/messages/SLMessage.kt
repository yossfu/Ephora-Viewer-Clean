package com.lumiyaviewer.lumiya.slproto.messages

import com.lumiyaviewer.lumiya.slproto.base.LLUUIDUtil
import com.lumiyaviewer.lumiya.slproto.base.Quaternion
import com.lumiyaviewer.lumiya.slproto.base.Vector3
import com.lumiyaviewer.lumiya.slproto.base.Vector3d
import com.lumiyaviewer.lumiya.slproto.base.Vector4

/**
 * A decoded message payload. Field values use a small set of Kotlin types:
 * Int, Long, Float, Double, Boolean, String (UUID), Vector3/Vector3d/Vector4/
 * Quaternion and ByteArray (variable + fixed fields).
 */
/**
 * Where a variable (or fixed) field sat in the message body it was decoded from.
 *
 * Recorded by [SLMessageCodec] as it reads, so the diagnostics can say where a
 * field's **length word** was, where its **data** began and how many bytes it
 * *declared* — as opposed to how many bytes came out of it. A short blob is then
 * a checkable statement about the wire ("the region declared 45 bytes and the
 * message ended right there") instead of a suspicion about our own framing.
 *
 * @param lengthWordOffset offset of the length word inside the body, or -1 for a
 *   fixed-size field (which has no length word at all).
 * @param dataOffset offset of the first data byte inside the body.
 * @param declaredLength bytes the field declared (variable) or occupies (fixed).
 */
class FieldSpan(
    val lengthWordOffset: Int,
    val dataOffset: Int,
    val declaredLength: Int
)

class Block {

    private val values = LinkedHashMap<String, Any>()

    private val spans = LinkedHashMap<String, FieldSpan>()

    fun set(name: String, value: Any) {
        values[name] = value
    }

    /** Where the field's bytes sat in the body, when the codec recorded it. */
    fun span(name: String): FieldSpan? = spans[name]

    fun setSpan(name: String, span: FieldSpan) {
        spans[name] = span
    }

    fun has(name: String): Boolean = values.containsKey(name)

    fun raw(name: String): Any? = values[name]

    fun isEmpty(): Boolean = values.isEmpty()

    fun u8(name: String): Int = (values[name] as? Number)?.toInt() ?: 0

    fun u16(name: String): Int = (values[name] as? Number)?.toInt() ?: 0

    fun u32(name: String): Long = (values[name] as? Number)?.toLong() ?: 0L

    fun u32i(name: String): Int = u32(name).toInt()

    fun u64(name: String): Long = (values[name] as? Number)?.toLong() ?: 0L

    fun s32(name: String): Int = (values[name] as? Number)?.toInt() ?: 0

    fun f32(name: String): Float = (values[name] as? Number)?.toFloat() ?: 0f

    fun f64(name: String): Double = (values[name] as? Number)?.toDouble() ?: 0.0

    fun bool(name: String): Boolean = (values[name] as? Boolean) ?: false

    fun uuid(name: String): String = (values[name] as? String) ?: LLUUIDUtil.ZERO

    fun vector3(name: String): Vector3 = (values[name] as? Vector3) ?: Vector3.ZERO

    fun vector3d(name: String): Vector3d = (values[name] as? Vector3d) ?: Vector3d.ZERO

    fun vector4(name: String): Vector4 = (values[name] as? Vector4) ?: Vector4.ZERO

    fun quaternion(name: String): Quaternion = (values[name] as? Quaternion) ?: Quaternion.IDENTITY

    fun bytes(name: String): ByteArray = (values[name] as? ByteArray) ?: EMPTY

    fun string(name: String): String {
        val bytes = values[name] as? ByteArray ?: return ""
        var end = bytes.size
        while (end > 0 && bytes[end - 1].toInt() == 0) {
            end -= 1
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    fun setString(name: String, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(body.size + 1)
        System.arraycopy(body, 0, out, 0, body.size)
        out[body.size] = 0
        values[name] = out
    }

    fun summary(): String {
        if (values.isEmpty()) {
            return "{}"
        }
        val builder = StringBuilder("{")
        var first = true
        for ((key, value) in values) {
            if (!first) {
                builder.append(", ")
            }
            first = false
            builder.append(key).append('=')
            when (value) {
                is ByteArray -> {
                    val text = String(value, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                    if (text.isNotEmpty() && text.all { it.code in 32..126 }) {
                        builder.append('"').append(text.take(80)).append('"')
                    } else {
                        builder.append(value.size).append(" bytes")
                    }
                }
                else -> builder.append(value.toString().take(80))
            }
        }
        builder.append('}')
        return builder.toString()
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}

class SLMessage(val def: MessageDef) {

    val blocks = LinkedHashMap<String, MutableList<Block>>()

    /**
     * Offset of the body inside the datagram it was decoded from. The codec sets
     * it, so a diagnostic can compare a field's absolute position against the
     * body's own limits instead of guessing.
     */
    var bodyOffset: Int = 0

    /** Bytes the decoded body occupied. */
    var bodyLength: Int = 0

    /**
     * True when every field of the body was read to the end. A body that ran out
     * (or a field a newer simulator named differently) reads as `false`, which is
     * the difference between "the region sent a short section" and "we stopped
     * reading early".
     */
    var bodyComplete: Boolean = true

    fun block(name: String, index: Int = 0): Block {
        val list = blocks.getOrPut(name) { ArrayList() }
        while (list.size <= index) {
            list.add(Block())
        }
        return list[index]
    }

    fun addBlock(name: String): Block {
        val list = blocks.getOrPut(name) { ArrayList() }
        val block = Block()
        list.add(block)
        return block
    }

    fun blockCount(name: String): Int = blocks[name]?.size ?: 0

    fun firstBlock(name: String): Block? = blocks[name]?.firstOrNull()

    fun name(): String = def.name

    fun describe(): String {
        val builder = StringBuilder(def.name)
        for ((blockName, list) in blocks) {
            for (index in list.indices) {
                builder.append(' ')
                builder.append(blockName)
                if (list.size > 1) {
                    builder.append('[').append(index).append(']')
                }
                builder.append(list[index].summary())
            }
        }
        return builder.toString()
    }
}
