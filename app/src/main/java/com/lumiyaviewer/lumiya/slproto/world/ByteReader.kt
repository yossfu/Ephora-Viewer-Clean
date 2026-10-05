package com.lumiyaviewer.lumiya.slproto.world

import com.lumiyaviewer.lumiya.slproto.base.Quaternion
import com.lumiyaviewer.lumiya.slproto.base.Vector3
import com.lumiyaviewer.lumiya.slproto.base.Vector4

/**
 * Little-endian reader for the packed blobs that object updates carry inside
 * their `Variable` fields. Every value on the Second Life wire is little-endian
 * unless the field name says otherwise (IPADDR).
 *
 * ## Why this class refuses to walk off the end
 *
 * The object-update blobs are variable-length sections whose lengths come from
 * the packet itself. A single mis-read offset (a length read at the wrong
 * position, a section that a newer simulator made longer) makes every following
 * field garbage — and a *length* read as garbage can be huge or negative. The
 * original reader clamped forward reads but let a negative `skip` move the
 * cursor *before* the start of the array, which then produced an
 * `ArrayIndexOutOfBoundsException` whose index was a wire value
 * (`length=113; index=-872415225`) instead of a field name.
 *
 * So: every advance is bounded, a negative or oversized `skip` is *refused and
 * counted* rather than applied, and [at] labels the field being decoded so the
 * failure can name it. Nothing here is used to "fix up" a bad packet — the
 * reader only makes sure that a bad packet reports *where* it went bad.
 */
class ByteReader(private val data: ByteArray, start: Int = 0) {

    private var pos: Int = start

    /** The field last announced with [at], for failure reports. */
    var field: String = "inicio"
        private set

    /** Advances that were refused because they would leave the buffer. */
    var violations: Int = 0
        private set

    /** Description of the first refused advance, `campo (offset de N)`. */
    var firstViolation: String = ""
        private set

    val remaining: Int
        get() = data.size - pos

    val position: Int
        get() = pos

    val length: Int
        get() = data.size

    /** Names the field that is about to be decoded, for the failure report. */
    fun at(name: String): ByteReader {
        field = name
        return this
    }

    fun has(count: Int): Boolean = count >= 0 && pos + count <= data.size

    private fun violate(detail: String, requested: Int) {
        if (violations == 0) {
            firstViolation = field + " (offset " + pos + " de " + data.size + "): " + detail
        }
        violations += 1
    }

    /**
     * True when the caller asked for [count] bytes past the end. Callers that
     * need to tell "the region sent zeroes" from "the blob ended" use the
     * reader's [violations] counter instead of guessing.
     */
    fun check(count: Int): Boolean {
        val ok = count >= 0 && pos + count <= data.size
        if (!ok) {
            violate("se pidieron " + count + " bytes y quedan " + remaining, count)
        }
        return ok
    }

    fun u8(): Int {
        if (!has(1)) {
            violate("u8 al final del bloque", 1)
            return 0
        }
        return data[pos++].toInt() and 0xFF
    }

    /** Signed byte: `PathShearX/Y`, `PathTwist`, `PathTwistBegin`, `PathSkew`… */
    fun s8(): Int {
        val raw = u8()
        return if (raw > 127) raw - 256 else raw
    }

    fun u16(): Int {
        if (!has(2)) {
            violate("u16 al final del bloque", 2)
            pos = data.size
            return 0
        }
        val value = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return value
    }

    fun s16(): Int {
        val raw = u16()
        return if (raw > 32767) raw - 65536 else raw
    }

    fun u32(): Long {
        if (!has(4)) {
            violate("u32 al final del bloque", 4)
            pos = data.size
            return 0L
        }
        var value = 0L
        for (i in 0 until 4) {
            value = value or ((data[pos + i].toLong() and 0xFFL) shl (8 * i))
        }
        pos += 4
        return value
    }

    fun u64(): Long {
        if (!has(8)) {
            violate("u64 al final del bloque", 8)
            pos = data.size
            return 0L
        }
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((data[pos + i].toLong() and 0xFFL) shl (8 * i))
        }
        pos += 8
        return value
    }

    fun f32(): Float = Float.fromBits(u32().toInt())

    fun vector3(): Vector3 = Vector3(f32(), f32(), f32())

    fun vector4(): Vector4 = Vector4(f32(), f32(), f32(), f32())

    /** Quantised value used by velocity/acceleration/rotation fields. */
    fun quantised(lower: Float, upper: Float): Float {
        val raw = u16().toFloat()
        return (raw / 65535f) * (upper - lower) + lower
    }

    /** Rotation stored as a normalised xyz triple with w reconstructed. */
    fun quaternionFromXyz(): Quaternion {
        val x = f32()
        val y = f32()
        val z = f32()
        return Quaternion.fromXyz(x, y, z)
    }

    fun bytes(count: Int): ByteArray {
        val safeCount = count.coerceIn(0, remaining)
        if (safeCount != count) {
            violate("se pidieron " + count + " bytes y quedan " + remaining, count)
        }
        val out = ByteArray(safeCount)
        System.arraycopy(data, pos, out, 0, safeCount)
        pos += safeCount
        return out
    }

    /** Reads a zero-terminated UTF-8 string (the format used by Text/NameValue). */
    fun string(): String {
        var end = pos
        while (end < data.size && data[end].toInt() != 0) {
            end += 1
        }
        val text = if (pos >= 0 && end >= pos) {
            String(data, pos, end - pos, Charsets.UTF_8)
        } else {
            violate("string en offset " + pos, 1)
            ""
        }
        pos = if (end < data.size) end + 1 else data.size
        return text
    }

    /**
     * Moves the cursor forward by [count].
     *
     * A negative [count] would move it *before* the buffer, which is how a
     * corrupted length used to turn into an array-index crash far from the field
     * that was mis-read. It is refused, counted, and reported instead: the
     * cursor stays where it is and the caller can see which field produced the
     * bogus length.
     */
    fun skip(count: Int) {
        if (count < 0) {
            violate("skip negativo de " + count, count)
            return
        }
        val target = pos + count
        if (target > data.size) {
            violate("skip de " + count + " bytes (quedan " + remaining + ")", count)
            pos = data.size
            return
        }
        pos = target
    }

    /**
     * Moves the cursor to an absolute offset inside the buffer. Returns false
     * (and reports a violation) when the target is outside it — used to align to
     * the end of a length-delimited section without ever running past it.
     */
    fun seek(offset: Int): Boolean {
        if (offset < 0 || offset > data.size) {
            violate("seek a " + offset, 0)
            return false
        }
        pos = offset
        return true
    }
}
