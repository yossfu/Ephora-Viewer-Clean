package com.lumiyaviewer.lumiya.slproto.llsd

import com.lumiyaviewer.lumiya.slproto.base.LLUUIDUtil
import java.io.ByteArrayOutputStream

/**
 * LLSD binary notation (`application/llsd+binary`), byte for byte what Linden
 * Lab's `LLSDBinaryFormatter` writes and `LLSDBinaryParser` reads:
 *
 * ```
 * !                      undefined
 * 1 / 0                  true / false
 * i + 4 bytes BE         integer
 * r + 8 bytes BE         real (IEEE double)
 * d + 8 bytes            date (seconds since the epoch)
 * u + 16 bytes           UUID
 * s + 4 bytes BE + data  string (UTF-8)
 * l + 4 bytes BE + data  URI (decoded as a plain string here)
 * b + 4 bytes BE + data  binary
 * [ + 4 bytes BE + values... + ]
 * { + 4 bytes BE + ('k' + key + value)... + }
 * ```
 *
 * All multi-byte values are big endian ("network byte order"). The seed
 * capability may answer in this notation, and the texture/asset capabilities
 * always do, so the viewer needs to read it either way.
 */
object LLSDBinary {

    const val CONTENT_TYPE = "application/llsd+binary"

    private const val HEADER_PROBE = 16
    private const val MAX_ELEMENTS = 1 shl 20

    fun decode(bytes: ByteArray): Any? {
        return decodeWithLength(bytes, 0).first
    }

    fun decodeWithLength(bytes: ByteArray, startAt: Int = 0): Pair<Any?, Int> {
        var start = startAt
        if (bytes.size >= start + HEADER_PROBE) {
            val probe = String(bytes, start, HEADER_PROBE, Charsets.US_ASCII)
            if (probe.startsWith("<? LLSD/Binary") || probe.startsWith("<?LLSD/Binary")) {
                start = start + HEADER_PROBE
                while (start < bytes.size && bytes[start].toInt().toChar().isWhitespace()) {
                    start += 1
                }
            }
        }
        val cursor = Cursor(bytes, start, bytes.size)
        val value = cursor.value()
        return value to (cursor.pos - start)
    }

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream(256)
        write(out, value)
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(UNDEF)
            is Boolean -> out.write(if (value) TRUE else FALSE)
            is LLSDUuid -> {
                out.write(UUID)
                out.write(LLUUIDUtil.toBytes(value.value), 0, 16)
            }
            is LLSDInteger -> {
                out.write(INT)
                writeU32(out, value.value.toInt())
            }
            is LLSDReal -> {
                out.write(REAL)
                writeF64(out, value.value)
            }
            is Int -> {
                out.write(INT)
                writeU32(out, value)
            }
            is Long -> {
                out.write(INT)
                writeU32(out, value.toInt())
            }
            is Float -> {
                out.write(REAL)
                writeF64(out, value.toDouble())
            }
            is Double -> {
                out.write(REAL)
                writeF64(out, value)
            }
            is String -> {
                out.write(STRING)
                writeString(out, value)
            }
            is ByteArray -> {
                out.write(BINARY)
                writeU32(out, value.size)
                out.write(value, 0, value.size)
            }
            is Map<*, *> -> {
                out.write(MAP)
                writeU32(out, value.size)
                for ((key, item) in value) {
                    out.write(KEY)
                    writeString(out, key.toString())
                    write(out, item)
                }
                out.write(END_MAP)
            }
            is Iterable<*> -> {
                val items = value.toList()
                out.write(ARRAY)
                writeU32(out, items.size)
                for (item in items) {
                    write(out, item)
                }
                out.write(END_ARRAY)
            }
            else -> {
                out.write(STRING)
                writeString(out, value.toString())
            }
        }
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeF64(out: ByteArrayOutputStream, value: Double) {
        val bits = java.lang.Double.doubleToLongBits(value)
        for (i in 7 downTo 0) {
            out.write(((bits ushr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun writeString(out: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeU32(out, bytes.size)
        if (bytes.isNotEmpty()) {
            out.write(bytes, 0, bytes.size)
        }
    }

    private class Cursor(private val data: ByteArray, var pos: Int, private val end: Int) {

        fun value(): Any? {
            if (pos >= end) {
                return null
            }
            return when (read()) {
                UNDEF -> null
                TRUE -> true
                FALSE -> false
                INT -> i32()
                REAL, DATE -> f64()
                UUID -> LLUUIDUtil.fromBytes(raw(16), 0)
                STRING, URI -> text()
                BINARY -> raw(count())
                ARRAY -> array()
                MAP -> map()
                else -> null
            }
        }

        private fun array(): List<Any?> {
            val size = count()
            val out = ArrayList<Any?>(minOf(size, 4096))
            var index = 0
            while (index < size && pos < end && !atEnd(END_ARRAY)) {
                out.add(value())
                index += 1
            }
            if (pos < end && atEnd(END_ARRAY)) {
                pos += 1
            }
            return out
        }

        private fun map(): Map<String, Any?> {
            val size = count()
            val out = LinkedHashMap<String, Any?>(minOf(size, 4096))
            var index = 0
            while (index < size && pos < end && !atEnd(END_MAP)) {
                val marker = read()
                val key = if (marker == KEY) text() else ""
                val item = value()
                if (key.isNotEmpty()) {
                    out[key] = item
                }
                index += 1
            }
            if (pos < end && atEnd(END_MAP)) {
                pos += 1
            }
            return out
        }

        private fun atEnd(marker: Int): Boolean = (data[pos].toInt() and 0xFF) == marker

        private fun text(): String {
            val size = count()
            return String(raw(size), Charsets.UTF_8)
        }

        private fun i32(): Int {
            val bytes = raw(4)
            return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        }

        private fun f64(): Double {
            val bytes = raw(8)
            var bits = 0L
            for (i in 0 until 8) {
                bits = (bits shl 8) or (bytes[i].toLong() and 0xFF)
            }
            return java.lang.Double.longBitsToDouble(bits)
        }

        private fun count(): Int {
            val size = i32()
            if (size < 0 || size > MAX_ELEMENTS) {
                throw IllegalStateException("longitud LLSD binaria fuera de rango: " + size)
            }
            return size
        }

        private fun read(): Int {
            if (pos >= end) {
                throw IllegalStateException("LLSD binario truncado")
            }
            return data[pos++].toInt() and 0xFF
        }

        private fun raw(size: Int): ByteArray {
            if (size < 0 || pos + size > end) {
                throw IllegalStateException("LLSD binario truncado (" + size + " bytes)")
            }
            val out = ByteArray(size)
            System.arraycopy(data, pos, out, 0, size)
            pos += size
            return out
        }
    }

    private const val UNDEF = '!'.code
    private const val TRUE = '1'.code
    private const val FALSE = '0'.code
    private const val INT = 'i'.code
    private const val REAL = 'r'.code
    private const val DATE = 'd'.code
    private const val UUID = 'u'.code
    private const val STRING = 's'.code
    private const val URI = 'l'.code
    private const val BINARY = 'b'.code
    private const val ARRAY = '['.code
    private const val MAP = '{'.code
    private const val KEY = 'k'.code
    private const val END_ARRAY = ']'.code
    private const val END_MAP = '}'.code
}
