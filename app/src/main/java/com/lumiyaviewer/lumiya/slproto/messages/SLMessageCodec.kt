package com.lumiyaviewer.lumiya.slproto.messages

import com.lumiyaviewer.lumiya.slproto.base.LLUUIDUtil
import com.lumiyaviewer.lumiya.slproto.base.Quaternion
import com.lumiyaviewer.lumiya.slproto.base.Vector3
import com.lumiyaviewer.lumiya.slproto.base.Vector3d
import com.lumiyaviewer.lumiya.slproto.base.Vector4
import java.io.ByteArrayOutputStream

/**
 * Generic binary codec driven by [MessageTemplate]. The same code path handles
 * every one of the ~475 UDP messages in the protocol.
 */
object SLMessageCodec {

    private const val MAX_VARIABLE_BLOCKS = 512

    /**
     * Where the last decode stopped, as `Block.field (offset/limit)`. A
     * simulator update that the viewer cannot parse used to disappear without a
     * trace; the log now names the field that broke.
     */
    var lastDecodeError: String = ""
        private set

    fun encode(message: SLMessage, out: ByteArrayOutputStream) {
        for (blockDef in message.def.blocks) {
            val list = message.blocks[blockDef.name]
            when (blockDef.repeat) {
                BlockRepeat.SINGLE -> {
                    writeBlock(blockDef, list?.firstOrNull() ?: Block(), out)
                }
                BlockRepeat.MULTIPLE -> {
                    for (i in 0 until blockDef.count) {
                        writeBlock(blockDef, list?.getOrNull(i) ?: Block(), out)
                    }
                }
                BlockRepeat.VARIABLE -> {
                    val count = list?.size ?: 0
                    out.write(count and 0xFF)
                    if (list != null) {
                        for (block in list) {
                            writeBlock(blockDef, block, out)
                        }
                    }
                }
            }
        }
    }

    fun encode(message: SLMessage): ByteArray {
        val out = ByteArrayOutputStream(256)
        encode(message, out)
        return out.toByteArray()
    }

    fun decode(def: MessageDef, data: ByteArray, offset: Int, length: Int): SLMessage? {
        return decode(def, data, offset, length, false)
    }

    /**
     * Decodes a message body. When [lenient] is true, a truncated or extended
     * body (older/newer simulator) still yields the blocks decoded so far
     * instead of being thrown away.
     */
    fun decode(def: MessageDef, data: ByteArray, offset: Int, length: Int, lenient: Boolean): SLMessage? {
        lastDecodeError = ""
        val message = SLMessage(def)
        message.bodyOffset = offset
        message.bodyLength = length
        val cursor = Cursor(data, offset, offset + length)
        for (blockDef in def.blocks) {
            val list = message.blocks.getOrPut(blockDef.name) { ArrayList() }
            val complete = when (blockDef.repeat) {
                BlockRepeat.SINGLE -> readLoosely(blockDef, cursor, list)
                BlockRepeat.MULTIPLE -> {
                    var ok = true
                    for (i in 0 until blockDef.count) {
                        if (!readLoosely(blockDef, cursor, list)) {
                            ok = false
                            break
                        }
                    }
                    ok
                }
                BlockRepeat.VARIABLE -> {
                    val count = cursor.u8()
                    if (count > MAX_VARIABLE_BLOCKS) {
                        lastDecodeError = blockDef.name + ": " + count + " bloques"
                        return null
                    }
                    var ok = true
                    for (i in 0 until count) {
                        if (!readLoosely(blockDef, cursor, list)) {
                            ok = false
                            break
                        }
                    }
                    ok
                }
            }
            if (!complete) {
                // The body ran out or a field was named wrongly. Keep what was
                // decoded so far when the caller accepts a partial message
                // (older simulators send fewer fields), otherwise reject it.
                message.bodyComplete = false
                if (!lenient) {
                    return null
                }
                break
            }
        }
        return message
    }

    /**
     * Reads one block, keeping it even when a field fails: half an object
     * record is worth more than none, and the next packet usually carries the
     * rest.
     */
    private fun readLoosely(blockDef: BlockDef, cursor: Cursor, list: MutableList<Block>): Boolean {
        val block = Block()
        try {
            readBlock(blockDef, block, cursor)
        } catch (e: Exception) {
            lastDecodeError = e.message ?: (blockDef.name + ": " + e.javaClass.simpleName)
            if (!block.isEmpty()) {
                list.add(block)
            }
            return false
        }
        list.add(block)
        return true
    }

    private fun writeBlock(blockDef: BlockDef, block: Block, out: ByteArrayOutputStream) {
        for (field in blockDef.fields) {
            writeField(field, block, out)
        }
    }

    private fun readBlock(blockDef: BlockDef, block: Block, cursor: Cursor) {
        for (field in blockDef.fields) {
            try {
                readField(field, block, cursor)
            } catch (e: Exception) {
                throw FieldDecodeException(
                    blockDef.name + "." + field.name + " (" + cursor.position() + "/" + cursor.limit() + ")",
                    e
                )
            }
        }
    }

    private class FieldDecodeException(message: String, cause: Throwable) : Exception(message, cause)

    private fun writeField(field: FieldDef, block: Block, out: ByteArrayOutputStream) {
        when (field.type) {
            FieldType.U8, FieldType.S8 -> out.write(block.u8(field.name) and 0xFF)
            FieldType.U16, FieldType.S16 -> writeU16Le(out, block.u16(field.name))
            FieldType.U32, FieldType.S32 -> writeU32Le(out, block.u32i(field.name))
            FieldType.U64, FieldType.S64 -> writeU64Le(out, block.u64(field.name))
            FieldType.F32 -> writeU32Le(out, java.lang.Float.floatToIntBits(block.f32(field.name)))
            FieldType.F64 -> writeU64Le(out, java.lang.Double.doubleToLongBits(block.f64(field.name)))
            FieldType.BOOL -> out.write(if (block.bool(field.name)) 1 else 0)
            FieldType.LLUUID -> {
                val tmp = ByteArray(16)
                LLUUIDUtil.toBytes(block.uuid(field.name), tmp, 0)
                out.write(tmp, 0, 16)
            }
            FieldType.IPADDR -> writeU32Be(out, block.u32i(field.name))
            FieldType.IPPORT -> writeU16Be(out, block.u16(field.name))
            FieldType.VECTOR3 -> {
                val v = block.vector3(field.name)
                writeU32Le(out, java.lang.Float.floatToIntBits(v.x))
                writeU32Le(out, java.lang.Float.floatToIntBits(v.y))
                writeU32Le(out, java.lang.Float.floatToIntBits(v.z))
            }
            FieldType.VECTOR3D -> {
                val v = block.vector3d(field.name)
                writeU64Le(out, java.lang.Double.doubleToLongBits(v.x))
                writeU64Le(out, java.lang.Double.doubleToLongBits(v.y))
                writeU64Le(out, java.lang.Double.doubleToLongBits(v.z))
            }
            FieldType.VECTOR4 -> {
                val v = block.vector4(field.name)
                writeU32Le(out, java.lang.Float.floatToIntBits(v.x))
                writeU32Le(out, java.lang.Float.floatToIntBits(v.y))
                writeU32Le(out, java.lang.Float.floatToIntBits(v.z))
                writeU32Le(out, java.lang.Float.floatToIntBits(v.w))
            }
            FieldType.QUATERNION -> {
                val q = block.quaternion(field.name)
                writeU32Le(out, java.lang.Float.floatToIntBits(q.x))
                writeU32Le(out, java.lang.Float.floatToIntBits(q.y))
                writeU32Le(out, java.lang.Float.floatToIntBits(q.z))
                writeU32Le(out, java.lang.Float.floatToIntBits(q.w))
            }
            FieldType.VARIABLE -> {
                val bytes = block.bytes(field.name)
                if (field.extra == 2) {
                    writeU16Le(out, bytes.size)
                } else {
                    out.write(bytes.size and 0xFF)
                }
                out.write(bytes, 0, bytes.size)
            }
            FieldType.FIXED -> {
                val bytes = block.bytes(field.name)
                val size = field.extra
                if (bytes.size >= size) {
                    out.write(bytes, 0, size)
                } else {
                    out.write(bytes, 0, bytes.size)
                    for (i in bytes.size until size) {
                        out.write(0)
                    }
                }
            }
        }
    }

    private fun readField(field: FieldDef, block: Block, cursor: Cursor) {
        when (field.type) {
            FieldType.U8, FieldType.S8 -> block.set(field.name, cursor.u8())
            FieldType.U16, FieldType.S16 -> block.set(field.name, cursor.u16Le())
            FieldType.U32, FieldType.S32 -> block.set(field.name, cursor.u32Le())
            FieldType.U64, FieldType.S64 -> block.set(field.name, cursor.u64Le())
            FieldType.F32 -> block.set(field.name, Float.fromBits(cursor.u32Le().toInt()))
            FieldType.F64 -> block.set(field.name, Double.fromBits(cursor.u64Le()))
            FieldType.BOOL -> block.set(field.name, cursor.u8() != 0)
            FieldType.LLUUID -> block.set(field.name, LLUUIDUtil.fromBytes(cursor.bytes(16), 0))
            FieldType.IPADDR -> block.set(field.name, cursor.u32Be())
            FieldType.IPPORT -> block.set(field.name, cursor.u16Be())
            FieldType.VECTOR3 -> block.set(
                field.name,
                Vector3(
                    Float.fromBits(cursor.u32Le().toInt()),
                    Float.fromBits(cursor.u32Le().toInt()),
                    Float.fromBits(cursor.u32Le().toInt())
                )
            )
            FieldType.VECTOR3D -> block.set(
                field.name,
                Vector3d(
                    Double.fromBits(cursor.u64Le()),
                    Double.fromBits(cursor.u64Le()),
                    Double.fromBits(cursor.u64Le())
                )
            )
            FieldType.VECTOR4 -> block.set(
                field.name,
                Vector4(
                    Float.fromBits(cursor.u32Le().toInt()),
                    Float.fromBits(cursor.u32Le().toInt()),
                    Float.fromBits(cursor.u32Le().toInt()),
                    Float.fromBits(cursor.u32Le().toInt())
                )
            )
            FieldType.QUATERNION -> block.set(
                field.name,
                Quaternion(
                    Float.fromBits(cursor.u32Le().toInt()),
                    Float.fromBits(cursor.u32Le().toInt()),
                    Float.fromBits(cursor.u32Le().toInt()),
                    Float.fromBits(cursor.u32Le().toInt())
                )
            )
            FieldType.VARIABLE -> {
                // The length word's own position is recorded as well as the
                // data's: a diagnostic that says "declared 45 at 113, data at
                // 115" can prove where the slice came from (fase 2.13a revision).
                val lengthWordOffset = cursor.bodyPosition()
                val size = if (field.extra == 2) cursor.u16Le() else cursor.u8()
                val dataOffset = cursor.bodyPosition()
                block.set(field.name, cursor.bytes(size))
                block.setSpan(field.name, FieldSpan(lengthWordOffset, dataOffset, size))
            }
            FieldType.FIXED -> {
                val dataOffset = cursor.bodyPosition()
                block.set(field.name, cursor.bytes(field.extra))
                block.setSpan(field.name, FieldSpan(-1, dataOffset, field.extra))
            }
        }
    }

    private fun writeU16Le(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeU16Be(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32Le(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeU32Be(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU64Le(out: ByteArrayOutputStream, value: Long) {
        for (i in 0 until 8) {
            out.write(((value ushr (i * 8)) and 0xFF).toInt())
        }
    }

    private class Cursor(val data: ByteArray, var pos: Int, val end: Int) {

        /** Where the body begins, so positions can be reported body-relative. */
        private val start: Int = pos

        fun position(): Int = pos

        fun limit(): Int = end

        /** Position relative to the body start; what the report quotes. */
        fun bodyPosition(): Int = pos - start

        fun u8(): Int {
            if (pos + 1 > end) {
                throw IndexOutOfBoundsException("u8")
            }
            return data[pos++].toInt() and 0xFF
        }

        fun u16Le(): Int {
            val a = u8()
            val b = u8()
            return a or (b shl 8)
        }

        fun u16Be(): Int {
            val a = u8()
            val b = u8()
            return (a shl 8) or b
        }

        fun u32Le(): Long {
            val a = u8().toLong()
            val b = u8().toLong()
            val c = u8().toLong()
            val d = u8().toLong()
            return a or (b shl 8) or (c shl 16) or (d shl 24)
        }

        fun u32Be(): Long {
            val a = u8().toLong()
            val b = u8().toLong()
            val c = u8().toLong()
            val d = u8().toLong()
            return (a shl 24) or (b shl 16) or (c shl 8) or d
        }

        fun u64Le(): Long {
            var result = 0L
            for (i in 0 until 8) {
                result = result or (u8().toLong() shl (i * 8))
            }
            return result
        }

        fun bytes(count: Int): ByteArray {
            if (pos + count > end) {
                throw IndexOutOfBoundsException("bytes")
            }
            val out = ByteArray(count)
            System.arraycopy(data, pos, out, 0, count)
            pos += count
            return out
        }
    }
}
