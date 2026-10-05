package com.lumiyaviewer.lumiya.slproto.world

import com.lumiyaviewer.lumiya.slproto.base.LLUUIDUtil
import kotlin.math.PI

/**
 * One face of a prim's `TextureEntry`, in the wire's own terms.
 *
 * Eleven values describe a face in Second Life: the texture UUID, the tint
 * (with alpha), the UV repeat/offset/rotation, the packed bump/shiny/fullbright
 * byte, the media/tex-gen flags, the glow and the render-material UUID. This
 * class is the *decoded wire* record — no scene, no renderer, no colour-space
 * conversion. `SLTextureFace` (in `slworld`) is the scene-side record built from
 * it, and that is where the tint becomes linear for Filament.
 */
class TextureEntryFace(
    val textureId: String,
    /**
     * Tint + alpha in sRGB, as `0xAARRGGBB`. `0xFFFFFFFF` is white (and it is
     * what the wire sends as four zero bytes, the region's "common colour"
     * optimisation), so a face with no tint keeps the texture's own colour.
     */
    val colorArgb: Int,
    val scaleU: Float,
    val scaleV: Float,
    val offsetU: Float,
    val offsetV: Float,
    /** Radians, counter-clockwise. */
    val rotation: Float,
    /**
     * The packed `SSFBBBBB` byte exactly as received: bump = bits 0..4,
     * fullbright = bit 5, shiny = bits 6..7 (`lltextureentry.h`).
     */
    val bump: Int,
    val mediaFlags: Int,
    /** 0..1. */
    val glow: Float,
    /** Render-material UUID; all-zero when the face has none. */
    val materialId: String
) {

    val bumpCode: Int get() = bump and 0x1F

    val fullBright: Boolean get() = ((bump shr 5) and 0x1) != 0

    val shiny: Int get() = (bump shr 6) and 0x3

    /**
     * `TEM_TEX_GEN_*` (lltextureentry.h): bits 1..2 de mediaFlags.
     * 0 = default, 1 = planar, 2 = esferico, 3 = cilindrico.
     */
    val texGen: Int get() = (mediaFlags shr 1) and 0x3

    val hasTint: Boolean
        get() = (colorArgb and 0x00FFFFFF) != 0x00FFFFFF || ((colorArgb ushr 24) and 0xFF) != 0xFF

    val hasUvTransform: Boolean
        get() = scaleU != 1f || scaleV != 1f || offsetU != 0f || offsetV != 0f || rotation != 0f

    /** True solo cuando la cara pide TEX_GEN_PLANAR. */
    val wantsPlanar: Boolean get() = texGen == 1

    override fun toString(): String = "face(tex=" + textureId + " color=0x" +
        Integer.toHexString(colorArgb) + " uv=" + scaleU + "x" + scaleV +
        "+" + offsetU + "+" + offsetV + " rot=" + rotation +
        " bump=0x" + Integer.toHexString(bump) + " glow=" + glow + " mat=" + materialId + ")"
}

/**
 * A decoded `TextureEntry` blob, per face.
 *
 * ## The wire format this parses
 *
 * `LLPrimitive::packTEMessage` — the *same* source the simulator links, so it is
 * what the region actually sends — writes **eleven fields in a fixed order**, and
 * every field is a small face list encoded the same way:
 *
 * ```
 * [default value]              (one value, the field's element size)
 * [ (index flags, value) ... ] (zero or more exception groups)
 * [0x00]                       (terminator: the reader stops here)
 * ```
 *
 * The default fills every face; an exception group is a variable-length 7-bit
 * bitfield (`bit i` = "face i takes this value", MSB = continuation) followed by
 * the value. Order and sizes:
 *
 * | # | field         | size | notes                                       |
 * |---|---------------|------|---------------------------------------------|
 * | 1 | image ids     | 16   | UUID per face                               |
 * | 2 | colours       | 4    | RGB**A**, each byte stored as `255 - value` |
 * | 3 | scale S       | 4    | F32                                         |
 * | 4 | scale T       | 4    | F32                                         |
 * | 5 | offset S      | 2    | S16 / 0x7FFF                                |
 * | 6 | offset T      | 2    | S16 / 0x7FFF                                |
 * | 7 | rotation      | 2    | S16 / 0x8000 * 2π                           |
 * | 8 | bump/shiny/fb | 1    | `SSFBBBBB`                                  |
 * | 9 | media flags   | 1    | `TEM_*` bits                                |
 * |10 | glow          | 1    | 0..255 → 0..1                               |
 * |11 | material id   | 16   | render-material UUID                        |
 *
 * Nothing here knows how many faces a prim has: the format is self-delimiting, so
 * it is parsed without that number and the caller resolves whichever face indices
 * its geometry uses. A blob that ends early is *reported* ([truncated],
 * [stoppedAt]) and every field not reached keeps its safe default — never a guess
 * built from unrelated bytes.
 *
 * ## What "short" can legitimately mean (revision of 2.13a)
 *
 * `LLPrimitive::packTEMessage` — the writer the region links — and
 * `LLPrimitive::parseTEMessage`/`unpackTEMessage` — its reader — do not have the
 * same standards, and the difference is exactly the one this revision had to
 * pin down (`llprimitive.cpp`):
 *
 * * **The writer always emits all eleven fields.** The ten first values are
 *   followed by a `0x00` separator each, and then the material id (field 11)
 *   follows, *unterminated*. A single-prim, all-default entry is therefore
 *   [FULL_WIRE_SIZE] bytes and eleven fields, [WRITER_MIN_WIRE_SIZE] — there
 *   is no such thing as a region-sent ten-field blob.
 * * **The reader appends one phantom `0x00` to the buffer** before parsing
 *   ("The last field is not zero terminated. Rather than special case the unpack
 *   functions, just make it 0x00 terminated"), and each field's value must be
 *   followed by at least one byte (`source + size + 1 > source_end` rejects it).
 *   With the phantom byte, that check reduces to "the value must fit in the bytes
 *   received" — which is exactly the rule [parse] applies.
 * * **The reader tolerates a missing or short material.** The material is read
 *   only `if (cur_ptr < buffer_end)`, and a failure there is swallowed
 *   (`memset(material_data, 0, ...)`). So a blob that stops after the tenth
 *   field — or in the middle of the material — is *accepted* by the reference,
 *   with every face left without a material.
 *
 * The consequence is the classification this class exposes:
 *
 * * [parsedFields] == [FIELD_COUNT]: what the region writes.
 * * Fewer fields, but the blob ended on a field boundary: well formed and
 *   accepted by the reader, yet not something the region's writer produces —
 *   [belowWriterMinimum], never a truncation.
 * * The blob ended *inside* a field: [truncated]. If it ended inside the
 *   optional material ([OPTIONAL_FIELD_INDEX]) the reference would have accepted
 *   it anyway; reporting the stop field separately
 *   (`ObjectUpdateDiagnostics.textureStopsInOptionalMaterial`) is what keeps that
 *   tolerated case from being read as a broken one.
 */
class TextureEntry private constructor(
    private val textureIds: Packed<String>,
    private val colors: Packed<Int>,
    private val scaleS: Packed<Float>,
    private val scaleT: Packed<Float>,
    private val offsetS: Packed<Float>,
    private val offsetT: Packed<Float>,
    private val rotations: Packed<Float>,
    private val bumps: Packed<Int>,
    private val mediaFlags: Packed<Int>,
    private val glows: Packed<Float>,
    private val materialIds: Packed<String>,
    /** Fields 1..11 read to their terminator, in order. */
    val parsedFields: Int,
    /** The blob's size in bytes, exactly as received. */
    val size: Int,
    /** Why the parse stopped: [StopReason.COMPLETE] or the way it ended early. */
    val stop: StopReason,
    /** Index of the field the parse stopped at, or -1 when it read them all. */
    val stopFieldIndex: Int,
    /** Bytes still unread when the parse stopped (`0` for a clean end). */
    val stopRemaining: Int,
    /** Element size of the field the parse stopped at (`0` when it read them all). */
    val stopExpectedSize: Int
) {

    /** True when the blob ended *inside* a field. Never means "the blob was short". */
    val truncated: Boolean get() = stop != StopReason.COMPLETE

    /** The field the parser stopped at when the blob ended early, or `-`. */
    val stoppedAt: String
        get() = if (stopFieldIndex < 0) "-" else FIELD_NAMES[stopFieldIndex]

    /**
     * True when the entry is *well formed* but carries fewer fields than the
     * region's own writer ever emits ([WRITER_MIN_FIELDS] = all eleven, material
     * included). The reader is more tolerant than the writer, so such a blob
     * parses fine — which is exactly why it is worth reporting: it did not come
     * out of `packTEMessage`, so it is either a cut landing on a field boundary,
     * a legacy/foreign encoder, or a mis-sliced section. A blob that ended *inside*
     * a field is [truncated] and is never also "short of fields"; the two are
     * different answers and are kept apart.
     */
    val belowWriterMinimum: Boolean get() = !truncated && parsedFields < WRITER_MIN_FIELDS

    /** Fields whose terminator the blob contained, in wire order. */
    val fieldNamesRead: List<String>
        get() = (0 until parsedFields).map { FIELD_NAMES[it] }

    /**
     * Why a parse stopped. Only [COMPLETE] means the blob was not cut short; the
     * other three say *where* it was cut, which is the difference between an
     * ending inside a value, inside an exception value, and inside the bitfield
     * that introduces one.
     */
    enum class StopReason {
        /** The blob ended on a field boundary; nothing was left half-read. */
        COMPLETE,

        /** The blob ended 1..fieldSize-1 bytes into a field's default value. */
        DEFAULT_INCOMPLETE,

        /** The blob ended 1..fieldSize-1 bytes into an exception group's value. */
        EXCEPTION_INCOMPLETE,

        /** The blob ended inside the 7-bit exception bitfield (continuation set). */
        BITFIELD_INCOMPLETE
    }


    /** Faces whose texture UUID differs from the default. */
    val texturedFaces: Int get() = textureIds.exceptions.size

    /** Faces with their own tint. */
    val tintedFaces: Int get() = colors.exceptions.size

    /** Faces with their own bump/shiny/fullbright byte. */
    val bumpedFaces: Int get() = bumps.exceptions.size

    /** One face, resolved: its own value where the wire carried one, else the default. */
    fun face(index: Int): TextureEntryFace = TextureEntryFace(
        textureId = textureIds.resolve(index),
        colorArgb = colors.resolve(index),
        scaleU = scaleS.resolve(index),
        scaleV = scaleT.resolve(index),
        offsetU = offsetS.resolve(index),
        offsetV = offsetT.resolve(index),
        rotation = rotations.resolve(index),
        bump = bumps.resolve(index),
        mediaFlags = mediaFlags.resolve(index),
        glow = glows.resolve(index),
        materialId = materialIds.resolve(index)
    )

    /** The face the region treats as the object's own: index 0. */
    val defaultFace: TextureEntryFace get() = face(0)

    /**
     * The highest face index the wire named, or 0 when every face shared the
     * default (then the blob itself does not say how many faces exist).
     */
    val highestNamedFace: Int get() = maxOf(
        textureIds.highest, colors.highest, scaleS.highest, scaleT.highest,
        offsetS.highest, offsetT.highest, rotations.highest, bumps.highest,
        mediaFlags.highest, glows.highest, materialIds.highest
    )

    override fun toString(): String = "TextureEntry(campos=" + parsedFields + "/" + FIELD_COUNT +
        (if (truncated) ", TRUNCADO en " + stoppedAt else "") +
        (if (belowWriterMinimum) ", CORTO (campos de menos: la region escribe " +
            FIELD_COUNT + ")" else "") +
        ", caras nombradas=" + (highestNamedFace + 1) +
        ", texturas propias=" + texturedFaces + ")"

    /** A field's default plus the faces that override it. */
    private class Packed<T>(var defaultValue: T, val exceptions: HashMap<Int, T>) {
        val highest: Int get() = exceptions.keys.maxOrNull() ?: 0
        fun resolve(index: Int): T = exceptions[index] ?: defaultValue
    }

    companion object {

        /** `packTEMessage` writes exactly this many fields. */
        const val FIELD_COUNT = 11

        /**
         * Fields the region's own writer (`LLPrimitive::packTEMessage`) always
         * emits: all eleven, the material id included and never left out. A blob
         * that parses to fewer fields was therefore *not* written by the region,
         * whatever else is true of it — it is reported through
         * [belowWriterMinimum], never as a truncation.
         */
        const val WRITER_MIN_FIELDS = 11

        /** Sum of the eleven field element sizes (no separators): 53 bytes. */
        const val FIELD_BYTES_TOTAL = 53

        /** Index of the optional field (the material id), the only one the reader tolerates short. */
        const val OPTIONAL_FIELD_INDEX = 10

        /**
         * Smallest blob the region itself writes: eleven fields, each followed by
         * its `0x00` separator except the material id (= 53 + 10).
         */
        const val FULL_WIRE_SIZE = 63

        /**
         * Same as [FULL_WIRE_SIZE]: the writer always emits all eleven fields, so
         * the smallest blob it produces is the all-default one.
         */
        const val WRITER_MIN_WIRE_SIZE = FULL_WIRE_SIZE

        private const val UUID_BYTES = 16
        private const val COLOR_BYTES = 4
        private const val FLOAT_BYTES = 4

        /** `TEXTURE_ROTATION_PACK_FACTOR` (`llprimitive.cpp`): `0x8000`. */
        private const val ROTATION_PACK_FACTOR = 32768f

        /** `S16` full scale the offsets are packed against: `0x7FFF`. */
        private const val OFFSET_PACK_FACTOR = 32767f

        /** Field names, in wire order; used for the truncation report. */
        private val FIELD_NAMES = arrayOf(
            "texture", "tinte", "scaleS", "scaleT", "offsetS", "offsetT",
            "rotacion", "bump", "media", "glow", "material"
        )

        /** Field element sizes, in wire order. */
        private val FIELD_SIZES = intArrayOf(
            UUID_BYTES, COLOR_BYTES, FLOAT_BYTES, FLOAT_BYTES, 2, 2, 2, 1, 1, 1, UUID_BYTES
        )

        /** Name of the field at [index], for reports. */
        fun fieldName(index: Int): String =
            if (index in FIELD_NAMES.indices) FIELD_NAMES[index] else "-"

        /** Element size of the field at [index], in bytes. */
        fun fieldSize(index: Int): Int =
            if (index in FIELD_SIZES.indices) FIELD_SIZES[index] else 0

        /**
         * Parses one `TextureEntry` blob exactly as received. Returns null when
         * the blob is shorter than the default texture UUID — the smallest thing
         * the region can send meaningfully — so the caller keeps what it had.
         */
        fun parse(bytes: ByteArray): TextureEntry? {
            if (bytes.size < UUID_BYTES) {
                return null
            }
            val reader = ByteReader(bytes)
            val textureIds = Packed(LLUUIDUtil.ZERO, HashMap())
            val colors = Packed(0xFFFFFFFF.toInt(), HashMap())
            val scaleS = Packed(1f, HashMap())
            val scaleT = Packed(1f, HashMap())
            val offsetS = Packed(0f, HashMap())
            val offsetT = Packed(0f, HashMap())
            val rotations = Packed(0f, HashMap())
            val bumps = Packed(0, HashMap())
            val mediaFlags = Packed(0, HashMap())
            val glows = Packed(0f, HashMap())
            val materialIds = Packed(LLUUIDUtil.ZERO, HashMap())

            var parsed = 0
            var stop = StopReason.COMPLETE
            var stopIndex = -1
            var stopRemaining = 0
            var stopExpected = 0
            for (index in 0 until FIELD_COUNT) {
                // A blob ending exactly on a field boundary is not a
                // truncation: it is a well-formed prefix of the format (the
                // reference reader accepts it, helped by the phantom terminator it
                // appends — see [belowWriterMinimum] for why it still gets
                // reported). Ending *inside* a field is what [StopReason] names.
                if (reader.remaining == 0) {
                    break
                }
                val outcome = readField(reader, FIELD_SIZES[index])
                val field = outcome.field
                if (field == null) {
                    stop = outcome.reason
                    stopIndex = index
                    stopRemaining = outcome.remaining
                    stopExpected = FIELD_SIZES[index]
                    break
                }
                when (index) {
                    0 -> fill(textureIds, field) { LLUUIDUtil.fromBytes(it, 0) }
                    1 -> fill(colors, field) { decodeColor(it) }
                    2 -> fill(scaleS, field) { decodeFloat(it) }
                    3 -> fill(scaleT, field) { decodeFloat(it) }
                    4 -> fill(offsetS, field) { decodeOffset(it) }
                    5 -> fill(offsetT, field) { decodeOffset(it) }
                    6 -> fill(rotations, field) { decodeRotation(it) }
                    7 -> fill(bumps, field) { it[0].toInt() and 0xFF }
                    8 -> fill(mediaFlags, field) { it[0].toInt() and 0xFF }
                    9 -> fill(glows, field) { (it[0].toInt() and 0xFF) / 255f }
                    else -> fill(materialIds, field) { LLUUIDUtil.fromBytes(it, 0) }
                }
                parsed += 1
            }
            return TextureEntry(
                textureIds, colors, scaleS, scaleT, offsetS, offsetT, rotations, bumps,
                mediaFlags, glows, materialIds, parsed, bytes.size, stop, stopIndex,
                stopRemaining, stopExpected
            )
        }

        /**
         * Reads one field: its default value plus its exception groups.
         *
         * The outcome names *why* a field could not be completed, which is what
         * turns "truncado" into a cause: an ending inside a default value, inside
         * an exception value, or inside the bitfield that introduces one.
         */
        private fun readField(reader: ByteReader, size: Int): FieldOutcome {
            if (reader.remaining < size) {
                return FieldOutcome(null, StopReason.DEFAULT_INCOMPLETE, reader.remaining)
            }
            val default = reader.bytes(size)
            val groups = ArrayList<Pair<Int, ByteArray>>(4)
            while (reader.remaining > 0) {
                var flags = 0L
                var byte: Int
                do {
                    if (reader.remaining == 0) {
                        // The continuation bit said another bitfield byte follows,
                        // and the blob ended first.
                        return FieldOutcome(null, StopReason.BITFIELD_INCOMPLETE, 0)
                    }
                    byte = reader.u8()
                    flags = (flags shl 7) or (byte and 0x7F).toLong()
                } while ((byte and 0x80) != 0)
                if (flags == 0L) {
                    return FieldOutcome(ReadField(default, groups), StopReason.COMPLETE, reader.remaining)
                }
                if (reader.remaining < size) {
                    return FieldOutcome(null, StopReason.EXCEPTION_INCOMPLETE, reader.remaining)
                }
                groups.add(Pair(flags.toInt(), reader.bytes(size)))
            }
            return FieldOutcome(ReadField(default, groups), StopReason.COMPLETE, 0)
        }

        private class ReadField(val default: ByteArray, val groups: List<Pair<Int, ByteArray>>)

        /** A field read, or the reason it could not be finished. */
        private class FieldOutcome(
            val field: ReadField?,
            val reason: StopReason,
            /** Bytes left when the reader gave up (the field's size is the demand). */
            val remaining: Int
        )

        /** Applies a field's default and exceptions to its [Packed] store. */
        private fun <T> fill(target: Packed<T>, field: ReadField, decode: (ByteArray) -> T) {
            target.defaultValue = decode(field.default)
            target.exceptions.clear()
            for ((flags, raw) in field.groups) {
                val value = decode(raw)
                for (face in expand(flags)) {
                    target.exceptions[face] = value
                }
            }
        }

        /** The faces named by a packed bitfield (`bit i` = face i). */
        private fun expand(flags: Int): List<Int> {
            if (flags == 0) {
                return emptyList()
            }
            val faces = ArrayList<Int>(8)
            for (bit in 0 until 32) {
                if ((flags ushr bit) and 1 != 0) {
                    faces.add(bit)
                }
            }
            return faces
        }

        /** RGB**A** on the wire, each byte the `255 - value` of the real channel. */
        private fun decodeColor(raw: ByteArray): Int {
            val r = 255 - (raw[0].toInt() and 0xFF)
            val g = 255 - (raw[1].toInt() and 0xFF)
            val b = 255 - (raw[2].toInt() and 0xFF)
            val a = 255 - (raw[3].toInt() and 0xFF)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        private fun decodeFloat(raw: ByteArray): Float {
            var bits = 0
            for (i in 0 until 4) {
                bits = bits or ((raw[i].toInt() and 0xFF) shl (8 * i))
            }
            return Float.fromBits(bits)
        }

        private fun decodeOffset(raw: ByteArray): Float {
            val value = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
            val signed = if (value > 32767) value - 65536 else value
            return signed / OFFSET_PACK_FACTOR
        }

        private fun decodeRotation(raw: ByteArray): Float {
            val value = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
            val signed = if (value > 32767) value - 65536 else value
            return signed / ROTATION_PACK_FACTOR * (2f * PI.toFloat())
        }
    }
}
