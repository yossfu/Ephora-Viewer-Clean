package com.lumiyaviewer.lumiya.slproto.world

/**
 * The **reference viewer's own** `TextureEntry` reader, ported line by line so
 * its verdict on a blob is *computed* from the bytes instead of asserted in
 * prose (`llprimitive.cpp`, `LLPrimitive::unpackTEMessage` / `unpack_TEField`).
 *
 * Why a second reader exists at all: this viewer's parser ([TextureEntry]) is
 * deliberately more talkative than the reference — it says *where* a blob ended
 * (`truncated`, `stoppedAt`) and it treats "the blob ended exactly on a field
 * boundary" as a well-formed prefix rather than as a cut. Those two answers are
 * not the same as "the reference would accept it", and mixing them up is how a
 * short blob gets read as corruption. This class removes the guesswork: it runs
 * the reference algorithm, including its two peculiarities, and reports what the
 * reference would actually do:
 *
 * * **The phantom terminator.** The reference appends one `0x00` byte to the
 *   buffer before parsing ("The last field is not zero terminated. Rather than
 *   special case the unpack functions, just make it 0x00 terminated"), and every
 *   field must fit *plus one following byte* (`source + size + 1 > source_end`).
 *   With the phantom byte present those two together mean exactly "the value must
 *   fit in the bytes received" — which is the rule [TextureEntry] applies too.
 * * **The optional material.** The material is read only `if (cur_ptr <
 *   buffer_end)` and a failure there is swallowed (`memset(material_data, 0,
 *   ...)`), so a blob that stops after the tenth field, or inside the material,
 *   is *accepted* with every face left without a material.
 *
 * The consequence, and the reason this class is the arbiter of the revision:
 * a blob is accepted **only** if all ten mandatory fields fit. A blob that ends
 * on a field boundary *before* the tenth field is **rejected** — the reference
 * returns 0 and logs "Failure parsing Texture Entry Message due to malformed TE
 * Field! Dropping changes on the floor." A "prefix" is therefore only valid as
 * far as the material: [MIN_ACCEPTED_SIZE] is 46 bytes, and the region's own
 * writer never emits anything between 1 and 62 bytes at all.
 */
object TextureEntryReference {

    /**
     * Element sizes of the ten mandatory fields, in wire order (uuid, colour,
     * scale S, scale T, offset S, offset T, rotation, bump, media, glow).
     */
    private val MANDATORY_FIELD_SIZES = intArrayOf(16, 4, 4, 4, 2, 2, 2, 1, 1, 1)

    /** The material is the eleventh field and the only optional one. */
    private const val MATERIAL_FIELD_SIZE = 16

    /**
     * Smallest blob the reference reader accepts: the ten mandatory fields with
     * the material absent (45 bytes of them plus the phantom terminator make the
     * last field's `+1` check pass at exactly this size).
     */
    const val MIN_ACCEPTED_SIZE = 46

    /** What the reference reader would do with a blob. */
    enum class Verdict {
        /** Size 0: the reference reads nothing at all (`if (size == 0) return`). */
        NO_ENTRY,

        /** The reference parses the entry; a missing material only leaves it unset. */
        ACCEPTED,

        /** The reference drops the entry. [Result.field] names the field that did not fit. */
        REJECTED
    }

    /**
     * @param field index of the mandatory field that did not fit (-1 when the
     *   entry was accepted or there was nothing to read).
     */
    class Result(val verdict: Verdict, val field: Int) {

        val accepted: Boolean get() = verdict != Verdict.REJECTED
    }

    /** Runs the reference reader's acceptance test over the blob's bytes. */
    fun read(blob: ByteArray): Result {
        if (blob.isEmpty()) {
            // `unpackTEMessage`: `if (size == 0) { return retval; }` — no entry.
            return Result(Verdict.NO_ENTRY, -1)
        }
        // The phantom terminator the reference appends before parsing.
        val buffer = ByteArray(blob.size + 1)
        System.arraycopy(blob, 0, buffer, 0, blob.size)
        buffer[blob.size] = 0
        var source = 0
        for (index in MANDATORY_FIELD_SIZES.indices) {
            val next = readField(buffer, source, MANDATORY_FIELD_SIZES[index])
            if (next < 0) {
                return Result(Verdict.REJECTED, index)
            }
            source = next
        }
        // `if (cur_ptr >= buffer_end || !unpack_TEField(...)) memset(material...)`.
        // Accepted either way: a short or absent material is not an error.
        return Result(Verdict.ACCEPTED, -1)
    }

    /** True when the reference reader would keep the entry (a legal absence counts). */
    fun accepts(blob: ByteArray): Boolean = read(blob).accepted

    /** One line saying what the reference reader does and why. */
    fun verdictText(blob: ByteArray): String {
        val result = read(blob)
        return when (result.verdict) {
            Verdict.NO_ENTRY ->
                "la referencia no lee nada (el update no lleva entrada)"
            Verdict.ACCEPTED ->
                "la referencia ACEPTA la entrada (los diez campos obligatorios caben;" +
                    " el material es opcional y puede faltar)"
            Verdict.REJECTED ->
                "la referencia RECHAZA la entrada entera (el campo obligatorio " +
                    result.field + " '" + TextureEntry.fieldName(result.field) +
                    "' no cabe; el visor imprime 'Dropping changes on the floor')"
        }
    }

    /**
     * One field of `unpack_TEField`: its default value plus its exception groups.
     *
     * Returns the new source position, or -1 when the field could not be read —
     * exactly like the reference, which returns `false` and makes the whole
     * `&&` chain fail.
     */
    private fun readField(buffer: ByteArray, source: Int, size: Int): Int {
        var cursor = source
        // `(source + size + 1) > source_end` → refusals, with the +1 standing for
        // the byte that must follow the value (the phantom byte provides it at
        // the end of the buffer).
        if (cursor + size + 1 > buffer.size) {
            return -1
        }
        cursor += size
        while (cursor < buffer.size) {
            var flags = 0L
            var byte: Int
            do {
                if (cursor >= buffer.size) {
                    return -1
                }
                byte = buffer[cursor++].toInt() and 0xFF
                flags = (flags shl 7) or (byte and 0x7F).toLong()
            } while (byte and 0x80 != 0)
            if (flags == 0L) {
                // The terminating 0 byte: the field ends here.
                break
            }
            if (cursor + size + 1 > buffer.size) {
                return -1
            }
            cursor += size
        }
        return cursor
    }
}
