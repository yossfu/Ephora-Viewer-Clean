package com.lumiyaviewer.lumiya.slproto.world

import java.util.Locale

/**
 * Everything the object-update **decoder** learned about the packets it read.
 *
 * This is deliberately separate from [com.lumiyaviewer.lumiya.renderer.RenderDiagnostics]:
 * that record answers "why is the screen black" (world → scene → GPU), while
 * this one answers "what exactly did the region's bytes say, and where did the
 * parser stop understanding them". The renderer's report copies these numbers
 * and texts into itself, so the on-screen DIAG panel and the saved log carry
 * both halves.
 *
 * The rules it exists to enforce:
 *
 * * A parse failure is **never** swallowed. It names the field that was being
 *   decoded, the offsets before and after each field of the block, the pcode,
 *   the local id and UUID when they had already been read, the compressed flag
 *   word, and the full stack trace.
 * * A length that comes off the wire is checked before it is used. `0xCC000007`
 *   is 3 422 552 071, not a negative index.
 * * An update that does not carry a shape **keeps** whatever shape the object
 *   already had, and that is counted ([shapeKeptFromPersisted]) so it is
 *   provable rather than claimed.
 *
 * Written by the network thread, read by the UI thread; every mutation happens
 * under [lock] and the counters the report reads are `@Volatile`.
 */
object ObjectUpdateDiagnostics {

    private val lock = Any()

    /** How many failures keep their full text in the report. */
    private const val MAX_FAILURES = 4

    /** How many distinct objects get a state-transition trace. */
    private const val MAX_TRANSITION_OBJECTS = 5

    /** Lines per traced object (one per update). */
    private const val MAX_TRANSITION_LINES = 8

    // ------------------------------------------------------------ counters

    /** Blocks the decoder applied, by update type. */
    @Volatile
    var fullBlocks = 0
        private set

    @Volatile
    var compressedBlocks = 0
        private set

    @Volatile
    var terseBlocks = 0
        private set

    /** Compressed blocks whose path/profile/TextureEntry section was read. */
    @Volatile
    var compressedWithParams = 0
        private set

    /** Compressed blocks that carried placement only (truncated or junk). */
    @Volatile
    var compressedPlacementOnly = 0
        private set

    /** Compressed blocks that threw; each one is in [failureLines]. */
    @Volatile
    var compressedFailed = 0
        private set

    /** Compressed blobs shorter than the fixed header. */
    @Volatile
    var compressedTooShort = 0
        private set

    /** Terse updates that arrived for an object that already had a shape. */
    @Volatile
    var terseWithPersistedShape = 0
        private set

    /** Terse updates for an object that still has no shape definition. */
    @Volatile
    var terseWithoutShape = 0
        private set

    /** Shapes that arrived in a full `ObjectUpdate`. */
    @Volatile
    var shapeFromFull = 0
        private set

    /** Shapes that arrived in an `ObjectUpdateCompressed`. */
    @Volatile
    var shapeFromCompressed = 0
        private set

    /**
     * Updates that arrived **without** geometry for an object that already had a
     * valid definition. If this number is large and the objects still have their
     * shape, the incremental model works: partial updates did not erase it.
     */
    @Volatile
    var shapeKeptFromPersisted = 0
        private set

    /** Updates that arrived without geometry for an object that has none yet. */
    @Volatile
    var shapeUpdatesWithoutShape = 0
        private set

    /** `ExtraParams` entries read, and the ones refused for a bogus length. */
    @Volatile
    var extraParamEntries = 0
        private set

    @Volatile
    var extraParamBogusLengths = 0
        private set

    @Volatile
    var extraParamTruncated = 0
        private set

    /** Every advance [ByteReader] had to refuse (a wire value that made no sense). */
    @Volatile
    var readerViolations = 0
        private set

    /** The first refused advance, with the field that was being decoded. */
    @Volatile
    var readerFirstViolation = "-"
        private set

    /** The first `ExtraParams` length that had to be refused. */
    @Volatile
    var extraParamFirstBogus = "-"
        private set

    // ---------------------------------------------- TextureEntry (fase 2.13a)
    //
    // La revision de 2.13a separa dos cosas que el primer informe mezclaba:
    //
    //  * **"truncado" significa una sola cosa**: el blob terminó *dentro* de un
    //    campo. Un blob que termina justo en el límite de un campo es una
    //    entrada bien formada (aunque lleve menos campos de los que el visor de
    //    referencia exige), y eso se cuenta aparte ([textureEntriesBelowWriter]),
    //    nunca dentro de los truncados. El contador de truncados no se oculta ni
    //    se renombra: se reparte.
    //  * **una entrada corta y legal no debe confundirse con una lectura
    //    incompleta**, así que el reparto es por origen (completo / comprimido /
    //    terse), con histograma de campos leídos y de tamaños, y con muestras
    //    verbatim: un "TextureEntry" de 16 bytes y uno de 60 que paró en
    //    `material` son problemas distintos y sólo los bytes reales dicen cuál
    //    envió la región.
    //
    // El material del duodécimo campo es opcional en el lector de referencia
    // (`LLPrimitive::unpackTEMessage`), así que `parsedFields < 10` es "entrada
    // bien formada por debajo del mínimo del visor", no "fallo del parser".

    /** `TextureEntry` blobs the decoder accepted (truncated ones included). */
    @Volatile
    var textureEntries = 0
        private set

    /** Blobs that ended *inside* a field (the parser stops and says where). */
    @Volatile
    var textureEntriesTruncated = 0
        private set

    /** Blobs shorter than the default texture UUID: nothing can be decoded. */
    @Volatile
    var textureEntriesTooShort = 0
        private set

    /** Of the short ones, the blobs the update sent empty (a legal "no entry"). */
    @Volatile
    var textureEntriesEmpty = 0
        private set

    /** Complete blobs carrying fewer fields than the reference reader requires. */
    @Volatile
    var textureEntriesBelowWriter = 0
        private set

    /** Sum of the fields every blob decoded (11 per full entry). */
    @Volatile
    var textureEntryFieldsRead = 0L
        private set

    /** Truncations that ended inside a field's default value. */
    @Volatile
    var textureStopsDefaultIncomplete = 0
        private set

    /** Truncations that ended inside an exception group's value. */
    @Volatile
    var textureStopsExceptionIncomplete = 0
        private set

    /** Truncations that ended inside a 7-bit exception bitfield. */
    @Volatile
    var textureStopsBitfieldIncomplete = 0
        private set

    /**
     * Truncations that ended inside the *optional* last field (the material id).
     *
     * This is the one place where the reference reader is more forgiving than
     * this parser: `LLPrimitive::parseTEMessage` accepts a blob whose optional
     * material field is short or missing and simply leaves every face without a
     * material, while this parser reports the ending as a truncation. The
     * counter exists so the revision can tell, from a real run, whether the
     * reported "truncados" are this tolerated case or genuine incomplete reads.
     */
    @Volatile
    var textureStopsInOptionalMaterial = 0
        private set

    // ---------------------------------------------------- classification A-E
    //
    // One blob, one category. Reported both as totals and per message path, which
    // is what the revision of 2.13a has to answer with. None of these replaces
    // `textureEntriesTruncated`: they split it, they do not move it.

    /** **A** (absent): well formed, exactly one field short — the optional material. */
    @Volatile
    var textureCategoryMaterialAbsent = 0
        private set

    /** **B**: well formed with fewer fields than the writer's ten mandatory ones. */
    @Volatile
    var textureCategoryLegacyFewerFields = 0
        private set

    /** **C**: truncated inside a mandatory field (0..9). */
    @Volatile
    var textureCategoryRequiredIncomplete = 0
        private set

    /** **E**: 1..15 bytes — not even a UUID fits. */
    @Volatile
    var textureCategorySubUuid = 0
        private set

    /** **D** (attribution): fed from a section whose declared length is impossible. */
    @Volatile
    var textureCategoryFramingSuspect = 0
        private set

    /** **Otro**: covered by no category above (expected 0). */
    @Volatile
    var textureCategoryOther = 0
        private set

    /**
     * Texture sections in the compressed path that declared an *impossible* length
     * (1..[TextureEntry.FULL_WIRE_SIZE]-1 bytes): the writer never produces one, so
     * a blob that size means the length word — or the offset it was read from — is
     * wrong. The proof of framing trouble, and the reason a "20-byte entry" cannot
     * be read as data.
     */
    @Volatile
    var impossibleTextureLengths = 0
        private set

    /** The first impossible declared length, with the numbers that show why. */
    @Volatile
    var impossibleTextureFirst = "-"
        private set

    /** Terse updates whose `TextureEntry` field was absent (not one of the 9). */
    @Volatile
    var terseWithoutTextureEntry = 0
        private set

    /** Faces that carried their own texture UUID (not the default). */
    @Volatile
    var textureFacesWithOwnTexture = 0
        private set

    /** Faces that carried their own tint. */
    @Volatile
    var textureFacesWithOwnTint = 0
        private set

    /** The last blob's parse summary, for the report. */
    @Volatile
    var lastTextureEntrySummary = "-"
        private set

    /** Per-source breakdown, so a broken path cannot hide behind the totals. */
    private val textureBySource = HashMap<UpdateSource, TextureSourceCounters>()

    /**
     * What the **reference viewer's own reader** does with every blob
     * ([TextureEntryReference] — the port of `LLPrimitive::unpackTEMessage`).
     *
     * This is the counter that keeps the revision honest: "our parser called it a
     * short prefix" and "the reference viewer would keep the entry" are two
     * different claims, and until now only the first one was computed. Every blob
     * — not just the kept samples — is now run through the reference algorithm, so
     * the report states how many entries the reference accepts and how many it
     * drops, and the field that made it drop them.
     */
    @Volatile
    var textureReferenceAccepted = 0L
        private set

    @Volatile
    var textureReferenceRejected = 0L
        private set

    @Volatile
    var textureReferenceNoEntry = 0L
        private set

    /** Mandatory field that made the reference drop the entry. */
    private val textureReferenceRejectFields = HashMap<Int, Int>()

    /** Blob size of every entry the reference dropped. */
    private val textureReferenceRejectSizes = HashMap<Int, Int>()

    /** How many fields each size of entry decoded, and how big the odd ones are. */
    private val textureCompleteFields = HashMap<Int, Int>()
    private val textureTruncatedFields = HashMap<Int, Int>()
    private val textureTruncatedStopFields = HashMap<Int, Int>()
    private val textureTruncatedSizes = HashMap<Int, Int>()
    private val textureShortSizes = HashMap<Int, Int>()

    /**
     * Sizes of the blobs that parsed to the end: the histogram that says whether
     * a "well formed but below the writer minimum" entry is a 17-byte UUID-only
     * prefix, a 46..62-byte ten-field one, or something else entirely. Without it
     * the B counter is a number with no shape (revision of 2.13a).
     */
    private val textureCompleteSizes = HashMap<Int, Int>()

    /** Same split for the below-the-writer-minimum prefixes: size and field count. */
    private val textureBelowWriterSizes = HashMap<Int, Int>()
    private val textureBelowWriterFields = HashMap<Int, Int>()

    /** Verbatim samples, keyed so the report keeps several of each interesting shape. */
    private val textureSamples = LinkedHashMap<String, ArrayList<TextureSample>>()

    /** How many bytes of a kept sample the report prints before saying it cut. */
    private const val TEXTURE_SAMPLE_LIMIT = 1024

    /**
     * Samples kept per class. The revision has to answer "which message carried
     * the 50 C entries, with which declared length, at which offset" from **real
     * samples**, not from one of them, so a class keeps a small battery instead of
     * a single blob: slot 0 is the most informative one (largest, or smallest for
     * the smallest-cut class) and the rest are the first seen. The report is read
     * by a human, hence the cap.
     */
    private const val TEXTURE_SAMPLE_PER_KEY = 4

    private const val HEX = "0123456789abcdef"

    /**
     * Counters for one message path — and the A–E classification of that path.
     *
     * The categories are the ones the revision of 2.13a has to report: each blob
     * lands in exactly one of them, so they partition the path's blobs (the
     * `materialAbsent` + `legacyFewerFields` pair is the writer-minimum split of
     * [belowWriter], and `materialIncomplete` + `requiredIncomplete` is the split
     * of [truncated]). `framingSuspect` is *not* a bucket: it is an attribution,
     * because a blob fed from a section whose declared length is impossible can
     * still stop anywhere.
     */
    class TextureSourceCounters {
        var complete = 0
        var truncated = 0
        var tooShort = 0
        var empty = 0
        var belowWriter = 0
        var fieldsRead = 0L

        /** **A (absent)**: well formed exactly one field short: the optional material. */
        var materialAbsent = 0

        /** **A (incomplete)**: truncated *inside* the optional material. */
        var materialIncomplete = 0

        /** **B**: well formed with fewer fields than the writer's ten mandatory ones. */
        var legacyFewerFields = 0

        /** **C**: truncated inside a mandatory field (0..9). */
        var requiredIncomplete = 0

        /** **E**: blobs that are neither empty nor able to carry a UUID (1..15 bytes). */
        var subUuid = 0

        /** **D (attribution)**: fed from a section whose declared length is impossible. */
        var framingSuspect = 0

        /** **Otro**: anything the categories above do not cover (expected 0). */
        var other = 0

        /** What the reference reader does with this path's blobs, computed per blob. */
        var referenceAccepted = 0L
        var referenceRejected = 0L
        var referenceNoEntry = 0L

        /** The material opinion of the reference reader: absent, partial or whole. */
        val optionalMaterialTolerated: Int get() = materialAbsent + materialIncomplete

        fun text(): String = "completos $complete  ·  truncados $truncated  ·  cortos $tooShort" +
            (if (empty > 0 || belowWriter > 0) {
                " (" + (if (empty > 0) "vacios " + empty else "") +
                    (if (empty > 0 && belowWriter > 0) ", " else "") +
                    (if (belowWriter > 0) "campos de menos " + belowWriter else "") + ")"
            } else "") +
            "  ·  campos $fieldsRead"

        /** One path's A–E line, so a single path cannot hide behind the totals. */
        fun categoryText(): String = "A " + optionalMaterialTolerated +
            " (ausente " + materialAbsent + " + a medias " + materialIncomplete + ")" +
            "  ·  B " + legacyFewerFields +
            "  ·  C " + requiredIncomplete +
            "  ·  D tras framing " + framingSuspect +
            "  ·  vacios " + empty + "  ·  menores del UUID " + subUuid +
            "  ·  otro " + other

        /**
         * What the reference viewer's reader does with this path's blobs. A path
         * can be "full of short entries" and still be nothing but tolerated
         * absences, so the verdict is printed per path next to the categories.
         */
        fun referenceText(): String = "acepta " + referenceAccepted +
            "  ·  rechaza " + referenceRejected +
            "  ·  sin entrada " + referenceNoEntry
    }

    /**
     * The bytes around a `TextureEntry` inside the buffer it came from, so a
     * 16-byte "entry" can be checked against its own framing (a length word read
     * one byte off is invisible in the entry alone but obvious in the window).
     *
     * The revision of 2.13a extended it with the facts that make the difference
     * between a real cut on the wire and a wrong offset of ours: the length the
     * section **declared** and where its length word sat, how many bytes of the
     * buffer follow the blob, whether the blob's end coincides with the buffer's
     * end (for the terse message, whose `TextureEntry` is the last field, that
     * identity *is* the proof), whether the whole body was read to the end, and
     * how many bytes the message re-encoded to.
     */
    class TextureContext(
        /** Size of the buffer the entry was cut out of. */
        val bufferSize: Int,
        /** Where the entry starts inside that buffer (-1 when it was not found). */
        val offset: Int,
        /** The up-to-8 bytes that precede the entry, in hex. */
        val before: String,
        /** The up-to-8 bytes that follow the entry, in hex. */
        val after: String,
        /** What the buffer is ("bloque Data", "mensaje recomprimido"...). */
        val note: String,
        /** Bytes the section declared for this blob (-1 when unknown). */
        val declaredLength: Int = -1,
        /** Offset of that declared-length word inside the buffer (-1 when none). */
        val declaredLengthOffset: Int = -1,
        /** Bytes of the buffer that follow the blob. */
        val trailing: Int = -1,
        /** True when the blob ends exactly where the buffer ends. */
        val endsBuffer: Boolean = false,
        /** True when every field of the body was read to the end. */
        val bodyComplete: Boolean = true,
        /** Bytes the message re-encoded to (-1 when it was not re-encoded). */
        val reencodedLength: Int = -1,
        /** Where the message body starts inside the received packet (-1 unknown). */
        val bodyOffset: Int = -1
    ) {
        fun text(): String = "contexto=[" + note + " buffer=" + bufferSize +
            " offset=" + offset + " declarado=" + declaredLength +
            (if (declaredLengthOffset >= 0) " palabra=" + declaredLengthOffset else "") +
            " antes=" + before + " despues=" + after +
            (if (bodyOffset >= 0) " bodyOffset=" + bodyOffset + " bodyLength=" + bufferSize else "") +
            " restantes=" + trailing + " fin_coincide=" + (if (endsBuffer) "si" else "no") +
            " cuerpo_completo=" + (if (bodyComplete) "si" else "no") +
            (if (reencodedLength >= 0) " recomprimido=" + reencodedLength else "") +
            "]"
    }

    /** One kept blob, verbatim, with what the parser made of it. */
    class TextureSample(
        val key: String,
        val localId: Int,
        val source: String,
        val size: Int,
        /** Fields the parser read out of it (-1 when it could not parse at all). */
        val fields: Int,
        /** One line saying why the parse stopped where it did. */
        val stop: String,
        /** The full blob in hex (cut only past [TEXTURE_SAMPLE_LIMIT] bytes). */
        val hex: String,
        val truncated: Boolean,
        /** What the *reference* reader would do with these same bytes. */
        val reference: String,
        /** The little-endian u32 in the blob's first four bytes (-1 if fewer than four). */
        val prefix4: Long = -1L,
        /**
         * What the entry would be if the four-byte length the region writes ahead
         * of it were skipped. Computed for the sample only, so the cost is bounded.
         */
        val at4: String = "-",
        /**
         * True when those four bytes are the length of what follows them. Measured
         * on the bytes **as they arrived** — the terse field, for a terse sample —
         * so it stays true after the prefix has been consumed on the way in.
         */
        val prefix4Exact: Boolean = false
    ) {
        /** The window of the buffer the blob was cut from, when the caller had it. */
        var context: TextureContext? = null
    }

    /**
     * What the *reference* reader would do with the same bytes this parser read.
     *
     * Computed, not asserted: the verdict comes from [TextureEntryReference], a
     * port of the reference's own `unpackTEMessage`, so "the reference accepts
     * this" is a result of running that algorithm over the blob. It is the arbiter
     * of the revision, because it is the only statement that says whether a short
     * entry would have been *used* or *dropped* by the viewer the format comes
     * from — and the two are very different: a blob that stops on a field boundary
     * before the tenth field is a "well formed prefix" for this parser and a
     * dropped update for the reference.
     */
    private fun referenceVerdict(bytes: ByteArray): String =
        TextureEntryReference.verdictText(bytes)

    /**
     * Per message path: `[blobs whose four leading bytes are their own remaining
     * length, blobs seen]`.
     *
     * Why this exists: the device showed that **all** of the B and C blobs come
     * from `ImprovedTerseObjectUpdate` (1004 refusals out of 1004 refusals), while
     * `ObjectUpdate` and `ObjectUpdateCompressed` were refused exactly zero times.
     * The two clean paths differ from the terse one in one concrete way: inside
     * their `Data` blob the region writes a **four-byte length** before the
     * `TextureEntry`, and this decoder consumes it. In the terse message the entry
     * travels as its own field, and the region writes the same four bytes inside
     * that field — which nobody consumes. `count/4 == size` for the terse path and
     * not for the others is the measurement that turns that into a fact.
     */
    private val texturePrefix4 = HashMap<UpdateSource, IntArray>()

    /** The first blob per path whose four leading bytes were not its own length. */
    private val texturePrefix4FirstMismatch = HashMap<UpdateSource, String>()

    /**
     * The same measurement, for the terse path alone, as plain numbers the checks
     * can assert on (the report prints the whole per-path table).
     */
    var textureTersePrefix4Seen = 0
    var textureTersePrefix4Matches = 0

    // ---------------------------------------- el prefijo del terse (correccion)
    //
    // Lo que la medicion de arriba probo en el dispositivo (1975/1975, el 100%) se
    // usa aqui: el campo `TextureEntry` del terse lleva delante un u32 con la
    // longitud de la entrada, y ese u32 se consume **una sola vez**, en la entrega,
    // antes de que el blob llegue al parser. El parser no cambia; los caminos
    // completo y comprimido no se tocan (el comprimido ya consume su propio u32
    // dentro de `Data`).
    //
    // La comparacion antes/despues es lo que cierra el caso B/C sin cambiar de
    // captura: "antes" es el contenido del campo tal cual llega (lo que el parser
    // veia), "despues" es lo que se le entrega ahora, y los dos se clasifican con
    // el mismo parser y el mismo lector de referencia.

    /** Campos `TextureEntry` de terse vistos (los no vacios). */
    @Volatile
    var tersePrefixFields = 0
        private set

    /** De esos, los que llevaban el u32 de su propia longitud y se han consumido. */
    @Volatile
    var tersePrefixConsumed = 0
        private set

    /** De esos, los que NO lo llevaban: se entregan tal cual, y el informe lo dice. */
    @Volatile
    var tersePrefixMissing = 0
        private set

    /** Clasificacion de esos campos antes y despues de consumir el prefijo. */
    private val terseBefore = TerseClasses()
    private val terseAfter = TerseClasses()

    /**
     * How the terse field's content classifies, with the same parser and the same
     * reference reader the report uses everywhere else. Counted twice — before and
     * after consuming the four bytes — so the correction is measured, not claimed.
     */
    private class TerseClasses {
        var complete = 0
        var materialAbsent = 0
        var materialPartial = 0
        var b = 0
        var c = 0
        var other = 0
        var referenceAccepted = 0
        var referenceRejected = 0

        fun text(): String =
            "11 campos " + complete + "  ·  A ausente " + materialAbsent +
                "  ·  A a medias " + materialPartial + "  ·  B " + b + "  ·  C " + c +
                "  ·  otros " + other +
                "  ·  la referencia acepta " + referenceAccepted + " / rechaza " + referenceRejected

        fun clear() {
            complete = 0
            materialAbsent = 0
            materialPartial = 0
            b = 0
            c = 0
            other = 0
            referenceAccepted = 0
            referenceRejected = 0
        }

        /**
         * `[11 campos, A ausente, A a medias, B, C, otros, la referencia acepta,
         * rechaza]` — the same order the report prints, for the checks.
         */
        fun counts(): IntArray = intArrayOf(
            complete, materialAbsent, materialPartial, b, c, other,
            referenceAccepted, referenceRejected
        )
    }

    /** The before/after terse classification as numbers; see [TerseClasses.counts]. */
    fun terseBeforeCounts(): IntArray = synchronized(lock) { terseBefore.counts() }

    fun terseAfterCounts(): IntArray = synchronized(lock) { terseAfter.counts() }

    /**
     * The terse field's content, with the four bytes the region writes in front of
     * the entry removed — the one functional correction of 2.13a-rev4.
     *
     * The four bytes are the entry's own length, little-endian and unsigned
     * (`OpenSim`'s terse writer: `totlen = len + 4` into the field's length word,
     * then `len` as a u32; LibreMetaverse skips exactly those four bytes when it
     * reads Second Life itself). The device measured them on 1975 of 1975 terse
     * fields, while the full and compressed paths had zero.
     *
     * They are consumed **only when they are consistent** — that is, when the u32
     * equals the number of bytes left after it. A field that does not carry them
     * is handed over untouched and counted as [tersePrefixMissing], so a grid that
     * does not write them stays correct *and* visible instead of being silently
     * mis-parsed. A non-positive payload is never touched.
     *
     * @return the bytes to hand to `TextureEntry.parse`.
     */
    fun consumeTersePrefix(raw: ByteArray): ByteArray {
        synchronized(lock) {
            tersePrefixFields += 1
            // The measurement is taken on the field as it arrives: the parser only
            // gets the entry from now on, so this is the last place it can be seen.
            noteTexturePrefixLocked(UpdateSource.TERSE, raw)
            val consumable = raw.size > 4 && leadingU32(raw) == (raw.size - 4).toLong()
            val entry = if (consumable) {
                tersePrefixConsumed += 1
                raw.copyOfRange(4, raw.size)
            } else {
                tersePrefixMissing += 1
                raw
            }
            classifyTerse(raw, terseBefore)
            classifyTerse(entry, terseAfter)
            return entry
        }
    }

    /** Classifies one blob into a [TerseClasses] set. Call under [lock]. */
    private fun classifyTerse(bytes: ByteArray, into: TerseClasses) {
        when (TextureEntryReference.read(bytes).verdict) {
            TextureEntryReference.Verdict.ACCEPTED -> into.referenceAccepted += 1
            TextureEntryReference.Verdict.REJECTED -> into.referenceRejected += 1
            else -> Unit
        }
        val parsed = TextureEntry.parse(bytes)
        if (parsed == null) {
            into.other += 1
            return
        }
        when {
            parsed.truncated -> if (parsed.stopFieldIndex == TextureEntry.OPTIONAL_FIELD_INDEX) {
                into.materialPartial += 1
            } else {
                into.c += 1
            }

            parsed.belowWriterMinimum -> if (parsed.parsedFields == TextureEntry.OPTIONAL_FIELD_INDEX) {
                into.materialAbsent += 1
            } else {
                into.b += 1
            }

            parsed.parsedFields == TextureEntry.FIELD_COUNT -> into.complete += 1
            else -> into.other += 1
        }
    }

    /** Records the four-byte-prefix measurement for one blob. Call under [lock]. */
    private fun noteTexturePrefixLocked(source: UpdateSource, bytes: ByteArray) {
        val counts = texturePrefix4.getOrPut(source) { IntArray(2) }
        counts[1] += 1
        val matches = leadingU32(bytes) == (bytes.size - 4).toLong()
        if (matches) {
            counts[0] += 1
        } else if (!texturePrefix4FirstMismatch.containsKey(source)) {
            texturePrefix4FirstMismatch[source] = "#? " + bytes.size + " bytes, primeros4=" +
                leadingU32(bytes) + " (su propia longitud seria " + (bytes.size - 4) + ")"
        }
        if (source == UpdateSource.TERSE) {
            textureTersePrefix4Seen += 1
            if (matches) {
                textureTersePrefix4Matches += 1
            }
        }
    }

    /** The little-endian u32 in a blob's first four bytes, or -1 when there are fewer. */
    private fun leadingU32(bytes: ByteArray): Long {
        if (bytes.size < 4) return -1L
        return (bytes[0].toLong() and 0xFF) or
            ((bytes[1].toLong() and 0xFF) shl 8) or
            ((bytes[2].toLong() and 0xFF) shl 16) or
            ((bytes[3].toLong() and 0xFF) shl 24)
    }

    /**
     * What the blob would parse as if the region's four-byte length ahead of it
     * were skipped. Sample-only: it re-parses the blob, so it is bounded by the
     * number of kept samples.
     */
    private fun entryAfterPrefix(bytes: ByteArray, skip: Int): String {
        if (bytes.size <= skip) {
            return "saltando " + skip + " bytes: no queda nada"
        }
        val rest = bytes.copyOfRange(skip, bytes.size)
        val parsed = TextureEntry.parse(rest)
        val fields = parsed?.parsedFields ?: -1
        val truncated = parsed?.truncated ?: false
        val complete = parsed != null && !parsed.truncated &&
            parsed.parsedFields >= TextureEntry.WRITER_MIN_FIELDS
        return "saltando " + skip + " bytes: campos=" + fields +
            (if (parsed == null) " NO PARSEA" else "") +
            (if (truncated) " TRUNCADO" else "") +
            (if (complete) " COMPLETA" else "") +
            "  ·  veredicto de la referencia: " + referenceVerdict(rest)
    }

    /**
     * Builds the window around `[offset, offset+length)` of `buffer`, with the
     * framing facts the 2.13a revision needs.
     *
     * @param bodySize the size of the buffer the offsets belong to, when it is not
     *   `buffer.size`: for a message body the caller passes the **received** body
     *   length, so `trailing` and `endsBuffer` are statements about the wire and
     *   not about the local re-encode (which `reencodedLength` reports separately).
     * @param endsBuffer when true, the blob's last byte is the buffer's last byte.
     *   For a terse update — whose `TextureEntry` is the message's last field —
     *   this is the difference between a length the region really declared and a
     *   length we read from the wrong place.
     */
    fun window(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        note: String,
        declaredLength: Int = -1,
        declaredLengthOffset: Int = -1,
        bodySize: Int = buffer.size,
        endsBuffer: Boolean = false,
        bodyComplete: Boolean = true,
        reencodedLength: Int = -1,
        bodyOffset: Int = -1
    ): TextureContext {
        val start = offset - 8
        val end = offset + length
        val safeOffset = offset.coerceIn(0, buffer.size)
        val safeEnd = end.coerceIn(0, buffer.size)
        return TextureContext(
            bufferSize = bodySize,
            offset = safeOffset,
            before = hexOf(buffer, maxOf(0, start), minOf(buffer.size, maxOf(0, safeOffset))),
            after = hexOf(buffer, safeEnd, minOf(buffer.size, safeEnd + 8)),
            note = note,
            declaredLength = declaredLength,
            declaredLengthOffset = declaredLengthOffset,
            trailing = if (endsBuffer) 0 else bodySize - safeEnd,
            endsBuffer = endsBuffer,
            bodyComplete = bodyComplete,
            reencodedLength = reencodedLength,
            bodyOffset = bodyOffset
        )
    }

    private fun hexOf(bytes: ByteArray, from: Int, to: Int): String {
        if (to <= from) {
            return "-"
        }
        val builder = StringBuilder((to - from) * 3)
        for (i in from until to) {
            if (i > from) {
                builder.append(' ')
            }
            val value = bytes[i].toInt() and 0xFF
            builder.append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    private fun hexOf(bytes: ByteArray, limit: Int): Pair<String, Int> {
        val kept = minOf(bytes.size, limit)
        val builder = StringBuilder(kept * 3 + 24)
        for (i in 0 until kept) {
            if (i > 0) {
                builder.append(' ')
            }
            val value = bytes[i].toInt() and 0xFF
            builder.append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
        if (kept < bytes.size) {
            builder.append(" ... (").append(bytes.size - kept).append(" mas)")
        }
        return Pair(builder.toString(), kept)
    }

    private fun bump(histogram: HashMap<Int, Int>, key: Int) {
        histogram[key] = (histogram[key] ?: 0) + 1
    }

    private fun histogramText(histogram: Map<Int, Int>): String {
        if (histogram.isEmpty()) {
            return "-"
        }
        return histogram.entries
            .sortedBy { it.key }
            .joinToString("  ") { it.key.toString() + ":" + it.value }
    }

    /**
     * Temporary (fase 2.13a, revision): records one `TextureEntry` blob so the
     * report can show the **real bytes** the parser ran against, and so the 453
     * truncations can be classified instead of counted.
     *
     * @param source which of the three decoder paths produced the blob.
     * @param framingSuspect true when the decoder already knows the blob came out
     *   of a section whose declared length is impossible, so the blob is a
     *   framing artefact and not evidence about the entry's own format.
     * @param context built lazily, and only when a sample is actually kept (the
     *   buffer window costs a re-encode in the field-based paths).
     */
    fun noteTextureEntry(
        localId: Int,
        source: UpdateSource,
        bytes: ByteArray,
        parsed: TextureEntry?,
        framingSuspect: Boolean = false,
        context: (() -> TextureContext?)? = null,
        received: ByteArray? = null
    ) {
        synchronized(lock) {
            val counters = textureBySource.getOrPut(source) { TextureSourceCounters() }
            // The terse field's four bytes are measured where they can still be
            // seen, in `consumeTersePrefix`, over the field as it arrives. The
            // parser only gets the entry now, so measuring here would answer a
            // different question (the entry's own first four bytes).
            if (source != UpdateSource.TERSE) {
                noteTexturePrefixLocked(source, bytes)
            }
            // The reference reader's verdict is computed for EVERY blob, not only
            // for the kept samples: it is the counter that decides whether a short
            // entry would have been used or dropped. It is a linear scan of a
            // ~63-byte blob, so it costs nothing next to the parse that just ran.
            val reference = TextureEntryReference.read(bytes)
            when (reference.verdict) {
                TextureEntryReference.Verdict.NO_ENTRY -> {
                    textureReferenceNoEntry += 1
                    counters.referenceNoEntry += 1
                }
                TextureEntryReference.Verdict.ACCEPTED -> {
                    textureReferenceAccepted += 1
                    counters.referenceAccepted += 1
                }
                TextureEntryReference.Verdict.REJECTED -> {
                    textureReferenceRejected += 1
                    counters.referenceRejected += 1
                    bump(textureReferenceRejectFields, reference.field)
                    bump(textureReferenceRejectSizes, bytes.size)
                }
            }
            if (framingSuspect) {
                textureCategoryFramingSuspect += 1
                counters.framingSuspect += 1
            }
            if (parsed == null) {
                textureEntriesTooShort += 1
                counters.tooShort += 1
                if (bytes.isEmpty()) {
                    textureEntriesEmpty += 1
                    counters.empty += 1
                } else {
                    textureCategorySubUuid += 1
                    counters.subUuid += 1
                }
                bump(textureShortSizes, bytes.size)
                val stopText = if (bytes.isEmpty()) {
                    "blob vacio: el update no lleva entrada (legal)"
                } else {
                    "menos de 16 bytes: no cabe ni el UUID por defecto"
                }
                lastTextureEntrySummary = "#" + localId + ": " + bytes.size + " bytes, " + stopText
                keepTextureSample(
                    if (bytes.isEmpty()) "corta-vacia" else "corta-corta",
                    keepLargest = bytes.isNotEmpty(),
                    localId, source, bytes, fields = -1, truncated = false,
                    stopText = stopText,
                    reference = referenceVerdict(bytes),
                    context = context,
                    received = received
                )
            } else {
                textureEntries += 1
                textureEntryFieldsRead += parsed.parsedFields.toLong()
                textureFacesWithOwnTexture += parsed.texturedFaces
                textureFacesWithOwnTint += parsed.tintedFaces
                counters.fieldsRead += parsed.parsedFields.toLong()
                var stopText = "sin terminador (fin de blob)"
                if (parsed.truncated) {
                    textureEntriesTruncated += 1
                    counters.truncated += 1
                    bump(textureTruncatedFields, parsed.parsedFields)
                    bump(textureTruncatedStopFields, parsed.stopFieldIndex)
                    if (parsed.stopFieldIndex == TextureEntry.OPTIONAL_FIELD_INDEX) {
                        // Cat A (a medias): el lector de referencia lo aceptaria.
                        textureStopsInOptionalMaterial += 1
                        counters.materialIncomplete += 1
                    } else {
                        // Cat C: un campo obligatorio.
                        textureCategoryRequiredIncomplete += 1
                        counters.requiredIncomplete += 1
                    }
                    bump(textureTruncatedSizes, bytes.size)
                    stopText = when (parsed.stop) {
                        TextureEntry.StopReason.DEFAULT_INCOMPLETE -> {
                            textureStopsDefaultIncomplete += 1
                            "valor por defecto incompleto en '" + parsed.stoppedAt + "' " +
                                "(quedaban " + parsed.stopRemaining + " de " + parsed.stopExpectedSize + ")"
                        }
                        TextureEntry.StopReason.EXCEPTION_INCOMPLETE -> {
                            textureStopsExceptionIncomplete += 1
                            "valor de excepcion incompleto en '" + parsed.stoppedAt + "' " +
                                "(quedaban " + parsed.stopRemaining + " de " + parsed.stopExpectedSize + ")"
                        }
                        TextureEntry.StopReason.BITFIELD_INCOMPLETE -> {
                            textureStopsBitfieldIncomplete += 1
                            "bitfield de excepciones incompleto en '" + parsed.stoppedAt + "'"
                        }
                        else -> "sin terminador (fin de blob)"
                    }
                } else {
                    counters.complete += 1
                    bump(textureCompleteFields, parsed.parsedFields)
                    bump(textureCompleteSizes, bytes.size)
                    if (parsed.belowWriterMinimum) {
                        textureEntriesBelowWriter += 1
                        counters.belowWriter += 1
                        bump(textureBelowWriterSizes, bytes.size)
                        bump(textureBelowWriterFields, parsed.parsedFields)
                        if (parsed.parsedFields == TextureEntry.OPTIONAL_FIELD_INDEX) {
                            // Cat A (ausente): exactamente los diez obligatorios.
                            textureCategoryMaterialAbsent += 1
                            counters.materialAbsent += 1
                        } else {
                            // Cat B: menos campos que los diez obligatorios.
                            textureCategoryLegacyFewerFields += 1
                            counters.legacyFewerFields += 1
                        }
                        // The wording is the point of the revision. A prefix that
                        // ends on a field boundary is *not* a truncation — our
                        // parser says where it stopped and nothing was half-read —
                        // but it is not a valid entry either: the reference reader
                        // needs all ten mandatory fields and drops the update when
                        // one of them does not fit. Only the material may be
                        // missing (46 bytes upwards, which is the A/absent case).
                        stopText = "bien formada, pero con menos campos de los que escribe la" +
                            " region (" + parsed.parsedFields + " de " + TextureEntry.WRITER_MIN_FIELDS +
                            " campos)" +
                            (if (parsed.parsedFields == TextureEntry.OPTIONAL_FIELD_INDEX) {
                                ": falta solo el material opcional, y el lector de referencia" +
                                    " lo acepta asi"
                            } else {
                                ": le faltan campos OBLIGATORIOS, y el lector de referencia" +
                                    " RECHAZA la entrada entera (no es un prefijo valido)"
                            })
                    }
                }
                lastTextureEntrySummary = "#" + localId + ": " + bytes.size + " bytes, " +
                    parsed.toString()
                val key = when {
                    parsed.truncated -> when (parsed.stop) {
                        TextureEntry.StopReason.DEFAULT_INCOMPLETE -> "parada-default"
                        TextureEntry.StopReason.EXCEPTION_INCOMPLETE -> "parada-excepcion"
                        TextureEntry.StopReason.BITFIELD_INCOMPLETE -> "parada-bitfield"
                        else -> "truncada"
                    }
                    parsed.parsedFields == TextureEntry.OPTIONAL_FIELD_INDEX -> "sin-material"
                    parsed.belowWriterMinimum -> "pocos-campos"
                    else -> "completa"
                }
                keepTextureSample(
                    key, keepLargest = true, localId, source, bytes, fields = parsed.parsedFields,
                    truncated = parsed.truncated, stopText = stopText,
                    reference = referenceVerdict(bytes), context = context, received = received
                )
                if (parsed.truncated) {
                    // The smallest cut reads very differently from the largest:
                    // one says "the entry was truncated", the other says "the
                    // last field was a few bytes short".
                    keepTextureSample(
                        "truncada-menor", keepLargest = false, localId, source, bytes,
                        fields = parsed.parsedFields, truncated = true,
                        stopText = stopText, reference = referenceVerdict(bytes), context = context,
                        received = received
                    )
                }
            }
        }
    }

    /**
     * Keeps one sample per interesting shape, up to [TEXTURE_SAMPLE_PER_KEY] per
     * shape. Slot 0 is the most informative blob of the class ([keepLargest]
     * decides whether that means the largest or the smallest one); the remaining
     * slots are the first blobs of the class seen, so a class shows a *battery* of
     * real messages — the revision has to answer "which message carried the C
     * entries" from several of them, not from one.
     */
    private fun keepTextureSample(
        key: String,
        keepLargest: Boolean,
        localId: Int,
        source: UpdateSource,
        bytes: ByteArray,
        fields: Int,
        stopText: String,
        truncated: Boolean,
        reference: String,
        context: (() -> TextureContext?)? = null,
        received: ByteArray? = null
    ) {
        val classSamples = textureSamples.getOrPut(key) { ArrayList(TEXTURE_SAMPLE_PER_KEY) }
        if (classSamples.isNotEmpty()) {
            val first = classSamples[0]
            val better = if (keepLargest) bytes.size > first.size else bytes.size < first.size
            if (classSamples.size >= TEXTURE_SAMPLE_PER_KEY && !better) {
                return
            }
            if (better) {
                // Replace slot 0 in place, keeping the class's other blobs.
                classSamples[0] = buildTextureSample(
                    key, localId, source, bytes, fields, stopText, truncated, reference, context,
                    received
                )
                return
            }
        }
        classSamples.add(
            buildTextureSample(
                key, localId, source, bytes, fields, stopText, truncated, reference, context, received
            )
        )
    }

    private fun buildTextureSample(
        key: String,
        localId: Int,
        source: UpdateSource,
        bytes: ByteArray,
        fields: Int,
        stopText: String,
        truncated: Boolean,
        reference: String,
        context: (() -> TextureContext?)?,
        received: ByteArray? = null
    ): TextureSample {
        val (hex, _) = hexOf(bytes, TEXTURE_SAMPLE_LIMIT)
        // The four-byte measurement is taken on the bytes **as they arrived** when the
        // caller can still see them — the terse field, whose four leading bytes are
        // consumed before the parser runs — and on the blob otherwise. The sample's
        // size stays the entry's, so `prefix4 == size - 4` still means "the field
        // carried its own remaining length".
        val prefixSource = received ?: bytes
        val prefix4 = leadingU32(prefixSource)
        val sample = TextureSample(
            key = key,
            localId = localId,
            source = source.label,
            size = bytes.size,
            fields = fields,
            stop = stopText,
            hex = hex,
            truncated = truncated,
            reference = reference,
            prefix4 = prefix4,
            at4 = if (prefix4 >= 0L) entryAfterPrefix(prefixSource, 4) else "-",
            prefix4Exact = prefix4 >= 0L && prefix4 == (prefixSource.size - 4).toLong()
        )
        sample.context = try {
            context?.invoke()
        } catch (e: Exception) {
            null
        }
        return sample
    }

    /** The kept samples as report lines, verbatim and without cutting the blob. */
    fun textureSampleLines(): List<String> = synchronized(lock) {
        val out = ArrayList<String>(16)
        if (textureSamples.isEmpty()) {
            return emptyList()
        }
        var count = 0
        for (list in textureSamples.values) {
            count += list.size
        }
        out.add(
            "PARSER TextureEntry muestras ($count blobs reales, hasta " +
                TEXTURE_SAMPLE_PER_KEY + " de cada clase; volcado COMPLETO, temporal):"
        )
        for (list in textureSamples.values) {
            for (sample in list) {
                out.add(
                    "PARSER   " + sample.key + " prim #" + sample.localId + " [" + sample.source + "] " +
                        sample.size + " bytes, campos=" + sample.fields + ", parada=" + sample.stop +
                        ", " + sample.reference + ", hex=" + sample.hex
                )
                val context = sample.context
                if (context != null) {
                    out.add("PARSER     " + context.text())
                }
                out.add(
                    "PARSER     prefijo4=" + sample.prefix4 +
                        (if (sample.prefix4Exact) " (COINCIDE: es la longitud que queda)" else "") +
                        "  ·  " + sample.at4
                )
            }
        }
        out
    }

    /**
     * The whole `TextureEntry` block of the report: the totals 2.13a printed, the
     * classification that says *why* the odd ones are odd, the per-path split, the
     * histograms, and the verbatim samples.
     */
    fun textureEntryLines(): List<String> = synchronized(lock) {
        val out = ArrayList<String>(12)
        out.add(
            "PARSER TextureEntry (fase 2.13a): blobs decodificados $textureEntries" +
                "  ·  truncados $textureEntriesTruncated  ·  demasiado cortos $textureEntriesTooShort" +
                "  ·  campos leidos $textureEntryFieldsRead (11 por entrada completa)"
        )
        out.add(
            "PARSER TextureEntry reparto: de los decodificados, bien formados pero con menos" +
                " campos de los que escribe la region (" + TextureEntry.WRITER_MIN_FIELDS + ") " +
                textureEntriesBelowWriter +
                "  ·  de los demasiado cortos, blobs vacios (el update no lleva entrada) " +
                textureEntriesEmpty
        )
        out.add(
            "PARSER TextureEntry paradas dentro de un campo: valor por defecto incompleto " +
                textureStopsDefaultIncomplete + "  ·  valor de excepcion incompleto " +
                textureStopsExceptionIncomplete + "  ·  bitfield incompleto " +
                textureStopsBitfieldIncomplete
        )
        out.add(
            "PARSER TextureEntry truncados por campo de parada: " +
                textureStopFieldText() +
                "  ·  de los $textureEntriesTruncated truncados: cat A en el material OPCIONAL" +
                " (el visor de referencia los acepta sin material) $textureStopsInOptionalMaterial" +
                "  ·  cat C en un campo OBLIGATORIO $textureCategoryRequiredIncomplete" +
                "  ·  sin clasificar " +
                (textureEntriesTruncated - textureStopsInOptionalMaterial -
                    textureCategoryRequiredIncomplete)
        )
        out.add(
            "PARSER TextureEntry CLASIFICACION A-E (" +
                (textureEntries + textureEntriesTooShort) + " blobs en total):" +
                "  A " + (textureCategoryMaterialAbsent + textureStopsInOptionalMaterial) +
                " (material opcional: ausente " + textureCategoryMaterialAbsent + " + a medias " +
                textureStopsInOptionalMaterial + ", el visor de referencia lo acepta)" +
                "  ·  B " + textureCategoryLegacyFewerFields +
                " (bien formada pero por debajo de los campos OBLIGATORIOS:" +
                " el visor de referencia la RECHAZA)" +
                "  ·  C " + textureCategoryRequiredIncomplete + " (corte en campo obligatorio)" +
                "  ·  D " + textureCategoryFramingSuspect +
                " (atribucion a framing invalido, se solapa con A/B/C)" +
                "  ·  E/otro " + (textureCategorySubUuid + textureCategoryOther)
        )
        out.add(
            "PARSER TextureEntry demasiado cortos por categoria: vacios legales (el update no" +
                " lleva entrada) " + textureEntriesEmpty +
                "  ·  menores del UUID (1-15 bytes, categoria E) " + textureCategorySubUuid +
                "  ·  terse sin campo TextureEntry (contexto, no cuenta) " + terseWithoutTextureEntry
        )
        out.add(
            "PARSER TextureEntry por origen: ObjectUpdate " +
                sourceText(UpdateSource.FULL) + "  ·  ObjectUpdateCompressed " +
                sourceText(UpdateSource.COMPRESSED) + "  ·  ImprovedTerseObjectUpdate " +
                sourceText(UpdateSource.TERSE)
        )
        out.add(
            "PARSER TextureEntry por origen, clasificacion A-E: ObjectUpdate " +
                categoryText(UpdateSource.FULL) + "  ·  ObjectUpdateCompressed " +
                categoryText(UpdateSource.COMPRESSED) + "  ·  ImprovedTerseObjectUpdate " +
                categoryText(UpdateSource.TERSE)
        )
        out.add(
            "PARSER TextureEntry veredicto del LECTOR DE REFERENCIA (calculado blob a blob" +
                " portando `LLPrimitive::unpackTEMessage`): acepta " + textureReferenceAccepted +
                "  ·  RECHAZA " + textureReferenceRejected +
                "  ·  sin entrada " + textureReferenceNoEntry +
                "  (minimo que acepta: " + TextureEntryReference.MIN_ACCEPTED_SIZE +
                " bytes = los diez campos obligatorios; el emisor de la region escribe 0 o >= " +
                TextureEntry.WRITER_MIN_WIRE_SIZE + ", nunca 1.." +
                (TextureEntry.WRITER_MIN_WIRE_SIZE - 1) + ")" +
                (if (textureReferenceRejected > 0) {
                    "  ·  rechazados por campo obligatorio: " + histogramText(textureReferenceRejectFields) +
                        "  ·  tamaños de los rechazados: " + histogramText(textureReferenceRejectSizes)
                } else {
                    ""
                })
        )
        out.add(
            "PARSER TextureEntry por origen, veredicto de la referencia: ObjectUpdate " +
                referenceText(UpdateSource.FULL) + "  ·  ObjectUpdateCompressed " +
                referenceText(UpdateSource.COMPRESSED) + "  ·  ImprovedTerseObjectUpdate " +
                referenceText(UpdateSource.TERSE)
        )
        out.add(
            "PARSER TextureEntry histograma de COMPLETOS (tamaños de todos los que parsearon al" +
                " final, " + histogramText(textureCompleteSizes) + ")" +
                "  ·  por campos: " + histogramText(textureCompleteFields)
        )
        if (textureEntriesBelowWriter > 0) {
            out.add(
                "PARSER TextureEntry histogramas de bien formados por debajo del minimo del emisor" +
                    " (" + textureEntriesBelowWriter + "): tamaños " +
                    histogramText(textureBelowWriterSizes) +
                    "  ·  campos " + histogramText(textureBelowWriterFields) +
                    "  (mira si son de 46.." + (TextureEntry.WRITER_MIN_WIRE_SIZE - 1) +
                    " bytes = diez campos, A ausente, la referencia los ACEPTA; 17..45 =" +
                    " faltan campos obligatorios, la referencia los RECHAZA)"
            )
        }
        if (impossibleTextureLengths > 0 || bogusSectionLengths > 0) {
            out.add(
                "PARSER TextureEntry framing (categoria D): longitudes declaradas imposibles" +
                    " (< " + TextureEntry.FULL_WIRE_SIZE + ", que es lo minimo que escribe la region)" +
                    " " + impossibleTextureLengths +
                    "  ·  longitudes mayores que los bytes que quedan " + bogusSectionLengths +
                    "  ·  primera: " + impossibleTextureFirst
            )
        }
        out.add(
            "PARSER TextureEntry histograma de campos leidos (completos): " +
                histogramText(textureCompleteFields)
        )
        out.add(
            "PARSER TextureEntry histograma de truncados: campos " +
                histogramText(textureTruncatedFields) + "  ·  tamaños " +
                histogramText(textureTruncatedSizes)
        )
        if (textureShortSizes.isNotEmpty()) {
            out.add(
                "PARSER TextureEntry histograma de demasiado cortos (tamaños): " +
                    histogramText(textureShortSizes)
            )
        }
        out.add(
            "PARSER TextureEntry prefijo de 4 bytes (el campo del terse es [u32 longitud][blob], igual" +
                " que el bloque Data del comprimido; en el terse se mide sobre el campo tal cual" +
                " llega, antes de consumirlo): " + prefix4Text()
        )
        val terseMismatch = texturePrefix4FirstMismatch[UpdateSource.TERSE]
        if (terseMismatch != null) {
            out.add("PARSER TextureEntry primer terse cuyo prefijo4 NO coincide: " + terseMismatch)
        }
        out.add(
            "PARSER TextureEntry terse, prefijo u32 (correccion 2.13a-rev4): campos vistos " +
                tersePrefixFields + "  ·  consumidos " + tersePrefixConsumed +
                "  ·  sin prefijo que coincida (se entregan tal cual) " + tersePrefixMissing
        )
        if (tersePrefixFields > 0) {
            out.add("PARSER TextureEntry terse, ANTES (el campo tal cual llega): " + terseBefore.text())
            out.add("PARSER TextureEntry terse, DESPUES (consumido el u32): " + terseAfter.text())
        }
        out.addAll(textureSampleLines())
        out
    }

    private fun sourceText(source: UpdateSource): String {
        val counters = textureBySource[source] ?: return "0"
        return counters.text()
    }

    /** One path's A–E classification, or a dash when that path has seen nothing. */
    private fun categoryText(source: UpdateSource): String {
        val counters = textureBySource[source] ?: return "-"
        return counters.categoryText()
    }

    /** One path's reference-reader verdict, or a dash when it has seen nothing. */
    private fun referenceText(source: UpdateSource): String {
        val counters = textureBySource[source] ?: return "-"
        return counters.referenceText()
    }

    /** The four-byte-prefix measurement, per message path. Call under [lock]. */
    private fun prefix4Text(): String {
        if (texturePrefix4.isEmpty()) {
            return "sin blobs"
        }
        return texturePrefix4.entries
            .sortedBy { it.key.label }
            .joinToString("  ·  ") { entry ->
                val matches = entry.value[0]
                val total = entry.value[1]
                entry.key.label + " " + matches + "/" + total +
                    (if (total > 0) " (" + (100 * matches / total) + "%)" else "")
            }
    }

    /**
     * The truncations by the *field* they stopped at, named rather than numbered
     * (`texture 3 · material 450`), because it is the direct answer to "is this a
     * real cut or the optional tail?".
     */
    private fun textureStopFieldText(): String {
        if (textureTruncatedStopFields.isEmpty()) {
            return "-"
        }
        return textureTruncatedStopFields.entries
            .sortedBy { it.key }
            .joinToString("  ") { TextureEntry.fieldName(it.key) + ":" + it.value }
    }

    // ------------------------------------------------------------- records

    private val failures = ArrayList<String>()

    private val transitions = LinkedHashMap<Int, StringBuilder>()

    private var fieldTrace: String = "-"

    private var fieldTraceOwner: String = "-"

    /** True when the stored walk reached the path/profile block. */
    private var fieldTraceComplete: Boolean = false

    // ------------------------------------------------------------- writes

    fun noteFullBlock() {
        fullBlocks += 1
    }

    fun noteTerseBlock() {
        terseBlocks += 1
    }

    /** A terse update that carried no `TextureEntry` field at all (legal silence). */
    fun noteTerseWithoutTextureEntry() {
        terseWithoutTextureEntry += 1
    }

    fun noteCompressedBlock(blobLength: Int) {
        compressedBlocks += 1
        lastBlobLength = blobLength
    }

    @Volatile
    var lastBlobLength: Int = 0
        private set

    fun noteCompressedWithParams() {
        compressedWithParams += 1
    }

    fun noteCompressedPlacementOnly(reason: String) {
        compressedPlacementOnly += 1
        lastPlacementOnlyReason = reason
    }

    @Volatile
    var lastPlacementOnlyReason: String = "-"
        private set

    fun noteCompressedTooShort(blobLength: Int) {
        compressedTooShort += 1
        recordFailure(
            "ObjectUpdateCompressed: bloque de " + blobLength + " bytes, mas corto que la cabecera de " +
                COMPRESSED_HEADER + " bytes; no se puede decodificar ningun campo"
        )
    }

    fun noteShapeFromFull() {
        shapeFromFull += 1
    }

    fun noteShapeFromCompressed() {
        shapeFromCompressed += 1
    }

    fun noteShapeKept() {
        shapeKeptFromPersisted += 1
    }

    fun noteUpdateWithoutShape(source: String) {
        shapeUpdatesWithoutShape += 1
        if (source == "terse") {
            terseWithoutShape += 1
        }
    }

    fun noteTersePreserved() {
        terseWithPersistedShape += 1
    }

    fun noteExtraParamEntry() {
        extraParamEntries += 1
    }

    fun noteExtraParamBogusLength(type: Int, length: Long, available: Int, blobLength: Int) {
        extraParamBogusLengths += 1
        val text = "ExtraParams tipo 0x" + Integer.toHexString(type) +
            ": longitud " + length + " (0x" + java.lang.Long.toHexString(length) + ")" +
            " pero solo quedan " + available + " bytes en un bloque de " + blobLength
        if (extraParamFirstBogus == "-") {
            extraParamFirstBogus = text
        }
    }

    fun noteExtraParamTruncated(count: Int, index: Int, remaining: Int) {
        extraParamTruncated += 1
        if (extraParamFirstBogus == "-") {
            extraParamFirstBogus = "ExtraParams: " + count + " entradas declaradas, la " + (index + 1) +
                " corta el bloque (quedan " + remaining + " bytes)"
        }
    }

    /**
     * A length-delimited section (TextureEntry, ExtraParams entry) whose declared
     * length does not fit in what is left of the block. The length is a `U32`:
     * it is checked as an unsigned value *before* it is used as a count, which is
     * exactly the check whose absence produced `index=-872415225`.
     */
    fun noteBogusSectionLength(section: String, length: Long, available: Int, blobLength: Int) {
        bogusSectionLengths += 1
        val text = section + ": longitud " + length + " (0x" + java.lang.Long.toHexString(length) +
            ") pero solo quedan " + available + " bytes en un bloque de " + blobLength
        if (bogusSectionFirst == "-") {
            bogusSectionFirst = text
        }
    }

    @Volatile
    var bogusSectionLengths = 0
        private set

    @Volatile
    var bogusSectionFirst = "-"
        private set

    /**
     * A `TextureEntry` section whose declared length is *impossible*: not zero
     * (which the writer does emit for a prim with no faces) and smaller than
     * [TextureEntry.FULL_WIRE_SIZE], the all-default entry the writer always
     * produces. Such a blob cannot be region data, so it is a framing artefact —
     * this is the evidence that turns category D from a hypothesis into a fact.
     */
    fun noteImpossibleTextureLength(length: Long, available: Int, blobLength: Int, localId: Int) {
        impossibleTextureLengths += 1
        val text = "localID " + localId + ": TextureEntry declara " + length +
            " bytes (por debajo de " + TextureEntry.FULL_WIRE_SIZE +
            ", el minimo que escribe la region) y quedan " + available +
            " en un bloque de " + blobLength
        if (impossibleTextureFirst == "-") {
            impossibleTextureFirst = text
        }
    }

    /**
     * True while this object's update history is worth recording: the first
     * [MAX_TRANSITION_OBJECTS] objects that carry a geometry block, and any
     * object already being traced. Movement-only updates ask this before
     * building a trace line, because they arrive every frame.
     */
    fun isTraced(localId: Int): Boolean = synchronized(lock) {
        transitions.containsKey(localId) || transitions.size < MAX_TRANSITION_OBJECTS
    }

    fun noteReaderViolations(reader: ByteReader) {
        if (reader.violations == 0) {
            return
        }
        readerViolations += reader.violations
        if (readerFirstViolation == "-") {
            readerFirstViolation = reader.firstViolation
        }
    }

    /**
     * Remembers the field-by-field offset walk of the first compressed block
     * that decoded cleanly. That walk *is* the layout evidence: one line per
     * field with the offsets it occupied, so the next log says whether the
     * region's bytes match the layout this decoder assumes.
     */
    fun recordFieldTrace(owner: String, text: String, complete: Boolean = true) {
        synchronized(lock) {
            // Keep the first walk, but prefer one that reached the
            // path/profile block: that is the layout evidence the report needs.
            if (fieldTrace != "-" && (fieldTraceComplete || !complete)) {
                return
            }
            fieldTrace = text
            fieldTraceOwner = owner
            fieldTraceComplete = complete
        }
    }

    fun fieldTraceText(): String {
        synchronized(lock) {
            return if (fieldTrace == "-") {
                "-"
            } else {
                fieldTraceOwner + "\n  " + fieldTrace
            }
        }
    }

    /** Records a parse failure, in full, once per occurrence (capped in the report). */
    fun recordFailure(text: String) {
        synchronized(lock) {
            if (failures.size < MAX_FAILURES) {
                failures.add(text)
            }
            lastFailure = text
        }
    }

    /** Marks that a compressed block failed; the text comes from [recordFailure]. */
    fun noteCompressedFailure() {
        compressedFailed += 1
    }

    @Volatile
    var lastFailure: String = "-"
        private set

    /**
     * One line per update for the first few objects, so the *transition* is
     * visible: which update arrived, which shape fields it carried, and what the
     * object's definition was before and after the merge.
     */
    fun traceUpdate(localId: Int, line: String) {
        synchronized(lock) {
            val known = transitions[localId]
            if (known == null) {
                if (transitions.size >= MAX_TRANSITION_OBJECTS) {
                    skippedTransitions += 1
                    return
                }
                val builder = StringBuilder(320)
                builder.append('#').append(localId)
                builder.append('\n').append("  ").append(line)
                transitions[localId] = builder
                return
            }
            val lines = known.count { it == '\n' }
            if (lines >= MAX_TRANSITION_LINES) {
                skippedTransitions += 1
                return
            }
            known.append('\n').append("  ").append(line)
        }
    }

    @Volatile
    var skippedTransitions = 0
        private set

    fun transitionLines(): List<String> = synchronized(lock) {
        transitions.values.map { it.toString() }
    }

    // ------------------------------------------- parent/child (fase 2.10, diagnostico)
    //
    // This is the parser half of the parent/child audit, and it exists so the
    // *first* place a parent can be lost is visible **before** the scene layer
    // ever sees the object. Three update types touch `parentId`:
    //
    //  * `ObjectUpdate` (full) — always carries the field.
    //  * `ObjectUpdateCompressed` — carries it only when `FLAG_HAS_PARENT` is
    //    set; when the flag is absent the decoder writes 0, exactly as
    //    LibreMetaverse's `ObjectManager` does, so a parent learned earlier can
    //    be erased by a later compressed update.
    //  * `ImprovedTerseObjectUpdate` — never carries it, never writes it.
    //
    // Nothing here changes what the decoder does: every method is a
    // notification, called after the field was written.

    /** How many objects get a line-by-line parent ledger. */
    private const val MAX_PARENT_OBJECTS = 8

    /** Lines per ledged object. */
    private const val MAX_PARENT_LINES = 10

    /** Lines kept for the focused object's update history. */
    private const val MAX_FOCUS_LINES = 48

    /** The object whose whole update history is being recorded (0 = none). */
    @Volatile
    var focusLocalId: Int = 0
        private set

    /** Starts (or stops, with 0) recording one object's whole update history. */
    fun setFocus(localId: Int) {
        synchronized(lock) {
            focusLocalId = localId
            focusHistory.setLength(0)
            focusUpdates = 0
            if (localId != 0) {
                focusHistory.append('#').append(localId)
                    .append("  (historial de updates desde que se activo el foco)")
            }
        }
    }

    fun isFocused(localId: Int): Boolean = localId != 0 && localId == focusLocalId

    /** Blocks whose decoded update carried the `ParentID` field. */
    @Volatile
    var parentFieldCarried = 0
        private set

    /** Blocks whose `ParentID` was not 0. */
    @Volatile
    var parentNonZeroValues = 0
        private set

    /** Blocks that carried `ParentID` and said 0 explicitly. */
    @Volatile
    var parentZeroValues = 0
        private set

    /**
     * Blocks that did **not** carry the field while the object already had a
     * parent: the compressed update without `FLAG_HAS_PARENT`. Before fase 2.11
     * this is where a parent that had already arrived disappeared (the decoder
     * wrote 0); now the stored parent is kept, exactly as the reference viewer
     * does, and the counter says how often that silence happened.
     */
    @Volatile
    var parentKeptWithoutField = 0
        private set

    @Volatile
    var parentObjectsEverNonZero = 0
        private set

    /** The last `parentId` seen per object. */
    private val lastParentById = HashMap<Int, Int>()

    /** Objects whose *current* parent is not 0. */
    private val parentNowIds = HashSet<Int>()

    /** Objects that ever had a parent. */
    private val parentEverIds = HashSet<Int>()

    private val parentLedger = LinkedHashMap<Int, StringBuilder>()

    private var skippedParentLines = 0

    private val focusHistory = StringBuilder(600)

    private var focusUpdates = 0

    /**
     * Records what one update wrote into `parentId`. [carried] says whether the
     * block actually contained the field (`ObjectUpdate` always does, a
     * compressed one only with `FLAG_HAS_PARENT`).
     */
    fun noteParent(localId: Int, updateNumber: Int, source: String, parentId: Int, carried: Boolean) {
        synchronized(lock) {
            val previous = lastParentById[localId]
            if (carried) {
                parentFieldCarried += 1
                if (parentId != 0) {
                    parentNonZeroValues += 1
                } else {
                    parentZeroValues += 1
                }
            } else if (parentId != 0 && previous != null && previous != 0) {
                // The update said nothing about the parent and the object already
                // had one, so the decoder kept it (fase 2.11).
                parentKeptWithoutField += 1
            }
            if (parentId != 0) {
                parentEverIds.add(localId)
                parentNowIds.add(localId)
            } else {
                parentNowIds.remove(localId)
            }
            parentObjectsEverNonZero = parentEverIds.size
            lastParentById[localId] = parentId
            // Only transitions worth reading are kept: the appearance of a parent
            // and its disappearance. "0 -> 0" for every compressed update of
            // every object would fill the ledger with noise.
            val interesting = parentId != 0 || (previous != null && previous != 0)
            if (interesting && previous != parentId) {
                val text = "#" + localId + " update #" + updateNumber + " " + source +
                    ": parent " + parentName(previous) + " -> " + parentId +
                    (if (!carried) " (el update NO llevaba el campo ParentID: se conserva el parent anterior)" else "")
                rememberParentLine(localId, text)
            }
        }
    }

    /** Objects whose current `parentId` is not 0, as the parser last saw it. */
    fun parentNowCount(): Int = synchronized(lock) { parentNowIds.size }

    /** The last `parentId` the parser saw for one object, or null if never seen. */
    fun lastParent(localId: Int): Int? = synchronized(lock) { lastParentById[localId] }

    private fun rememberParentLine(localId: Int, line: String) {
        var known = parentLedger[localId]
        if (known == null) {
            if (parentLedger.size >= MAX_PARENT_OBJECTS) {
                skippedParentLines += 1
                return
            }
            known = StringBuilder(240)
            known.append('#').append(localId)
            parentLedger[localId] = known
        }
        if (known.count { it == '\n' } >= MAX_PARENT_LINES) {
            skippedParentLines += 1
            return
        }
        known.append('\n').append("  ").append(line)
    }

    /** Appends one line to the focused object's history. */
    fun focusLine(localId: Int, line: String) {
        synchronized(lock) {
            if (!isFocused(localId)) {
                return
            }
            if (focusUpdates >= MAX_FOCUS_LINES) {
                return
            }
            focusUpdates += 1
            focusHistory.append('\n').append("  ").append(line)
        }
    }

    private fun parentName(value: Int?): String = if (value == null) "?" else value.toString()

    fun parentLedgerLines(): List<String> = synchronized(lock) { parentLedger.values.map { it.toString() } }

    fun parentSkippedLines(): Int = synchronized(lock) { skippedParentLines }

    fun parentFocusText(): String = synchronized(lock) { focusHistory.toString() }

    fun failureLines(): List<String> = synchronized(lock) { ArrayList(failures) }

    fun reset() {
        synchronized(lock) {
            fullBlocks = 0
            compressedBlocks = 0
            terseBlocks = 0
            compressedWithParams = 0
            compressedPlacementOnly = 0
            compressedFailed = 0
            compressedTooShort = 0
            terseWithPersistedShape = 0
            terseWithoutShape = 0
            shapeFromFull = 0
            shapeFromCompressed = 0
            shapeKeptFromPersisted = 0
            shapeUpdatesWithoutShape = 0
            extraParamEntries = 0
            extraParamBogusLengths = 0
            extraParamTruncated = 0
            bogusSectionLengths = 0
            bogusSectionFirst = "-"
            impossibleTextureLengths = 0
            impossibleTextureFirst = "-"
            readerViolations = 0
            readerFirstViolation = "-"
            extraParamFirstBogus = "-"
            lastPlacementOnlyReason = "-"
            lastFailure = "-"
            lastBlobLength = 0
            textureEntries = 0
            textureEntriesTruncated = 0
            textureEntriesTooShort = 0
            textureEntriesEmpty = 0
            textureEntriesBelowWriter = 0
            textureEntryFieldsRead = 0L
            textureStopsDefaultIncomplete = 0
            textureStopsExceptionIncomplete = 0
            textureStopsBitfieldIncomplete = 0
            textureStopsInOptionalMaterial = 0
            textureCategoryMaterialAbsent = 0
            textureCategoryLegacyFewerFields = 0
            textureCategoryRequiredIncomplete = 0
            textureCategorySubUuid = 0
            textureCategoryFramingSuspect = 0
            textureCategoryOther = 0
            textureReferenceAccepted = 0L
            textureReferenceRejected = 0L
            textureReferenceNoEntry = 0L
            terseWithoutTextureEntry = 0
            textureFacesWithOwnTexture = 0
            textureFacesWithOwnTint = 0
            lastTextureEntrySummary = "-"
            textureBySource.clear()
            textureCompleteFields.clear()
            textureTruncatedFields.clear()
            textureTruncatedStopFields.clear()
            textureTruncatedSizes.clear()
            textureShortSizes.clear()
            textureCompleteSizes.clear()
            textureBelowWriterSizes.clear()
            textureBelowWriterFields.clear()
            textureReferenceRejectFields.clear()
            textureReferenceRejectSizes.clear()
            texturePrefix4.clear()
            texturePrefix4FirstMismatch.clear()
            textureTersePrefix4Seen = 0
            textureTersePrefix4Matches = 0
            tersePrefixFields = 0
            tersePrefixConsumed = 0
            tersePrefixMissing = 0
            terseBefore.clear()
            terseAfter.clear()
            textureTersePrefix4Matches = 0
            textureSamples.clear()
            skippedTransitions = 0
            failures.clear()
            transitions.clear()
            fieldTrace = "-"
            fieldTraceOwner = "-"
            fieldTraceComplete = false
            // The parser's parent ledger starts clean for the new session. The
            // focus (the object being followed) is deliberately kept: it is a
            // choice the user made, not state of the previous region.
            parentFieldCarried = 0
            parentNonZeroValues = 0
            parentZeroValues = 0
            parentKeptWithoutField = 0
            parentObjectsEverNonZero = 0
            lastParentById.clear()
            parentNowIds.clear()
            parentEverIds.clear()
            parentLedger.clear()
            skippedParentLines = 0
            focusHistory.setLength(0)
            focusUpdates = 0
            if (focusLocalId != 0) {
                focusHistory.append('#').append(focusLocalId)
                    .append("  (historial de updates desde que se activo el foco)")
            }
        }
    }

    /** The whole parser section of the report. */
    fun lines(): List<String> {
        val out = ArrayList<String>(12)
        out.add(
            "PARSER updates: completos $fullBlocks bloques, comprimidos $compressedBlocks, terse $terseBlocks" +
                "  ·  ultimo bloque comprimido " + lastBlobLength + " bytes"
        )
        out.add(
            "PARSER comprimidos: con parametros $compressedWithParams  ·  solo colocacion $compressedPlacementOnly" +
                "  ·  demasiado cortos $compressedTooShort  ·  FALLOS $compressedFailed"
        )
        out.add(
            "PARSER formas: de ObjectUpdate $shapeFromFull  ·  de comprimido $shapeFromCompressed" +
                "  ·  conservadas al llegar un update parcial $shapeKeptFromPersisted" +
                "  ·  updates sin forma para objetos sin definicion $shapeUpdatesWithoutShape"
        )
        out.add(
            "PARSER terse: $terseWithPersistedShape sobre objetos que ya tenian forma" +
                "  ·  $terseWithoutShape sobre objetos que aun no la tienen (no la borran, no la inventan)"
        )
        out.add(
            "PARSER ExtraParams: entradas leidas $extraParamEntries" +
                "  ·  longitudes imposibles $extraParamBogusLengths  ·  truncados $extraParamTruncated"
        )
        out.add(
            "PARSER TextureEntry caras: con textura propia $textureFacesWithOwnTexture" +
                "  ·  con tinte propio $textureFacesWithOwnTint  ·  ultima: $lastTextureEntrySummary"
        )
        out.addAll(textureEntryLines())
        if (bogusSectionLengths > 0) {
            out.add(
                "PARSER secciones con longitud imposible: $bogusSectionLengths  ·  primera: " +
                    bogusSectionFirst
            )
        }
        out.add(
            "PARSER ByteReader: avances rechazados $readerViolations" +
                (if (readerFirstViolation != "-") "  ·  primero: " + readerFirstViolation else "")
        )
        if (extraParamFirstBogus != "-") {
            out.add("PARSER primera longitud de ExtraParams rechazada: " + extraParamFirstBogus)
        }
        if (compressedPlacementOnly > 0) {
            out.add("PARSER ultimo comprimido sin parametros: " + lastPlacementOnlyReason)
        }
        out.add("PARSER offsets del primer bloque comprimido decodificado: " + fieldTraceText())
        val failureList = failureLines()
        if (failureList.isEmpty()) {
            out.add("PARSER fallos: ninguno")
        } else {
            out.add("PARSER ultimos fallos (" + failureList.size + " de $compressedFailed):")
            out.addAll(failureList)
        }
        val traceList = transitionLines()
        if (traceList.isNotEmpty()) {
            out.add("PARSER transiciones de estado por objeto" +
                (if (skippedTransitions > 0) " (" + skippedTransitions + " updates mas, truncado)" else "") + ":")
            out.addAll(traceList)
        }
        out.add(
            "PARSER parents (fase 2.10/2.11): bloques con el campo ParentID $parentFieldCarried" +
                "  ·  con valor != 0 $parentNonZeroValues  ·  con valor 0 explicito $parentZeroValues"
        )
        out.add(
            "PARSER parents: objetos vistos alguna vez con parent != 0 $parentObjectsEverNonZero" +
                "  ·  con parent != 0 en el ultimo update " + parentNowCount() +
                "  ·  parent CONSERVADO por un comprimido SIN el bloque ParentID $parentKeptWithoutField"
        )
        val parentLines = parentLedgerLines()
        if (parentLines.isNotEmpty()) {
            out.add("PARSER cambios de parent por objeto (el update que los escribio, en orden):")
            out.addAll(parentLines)
            if (skippedParentLines > 0) {
                out.add("  ... " + skippedParentLines + " cambios de parent mas, no listados")
            }
        }
        if (focusLocalId != 0) {
            out.add("PARSER objeto en FOCO #" + focusLocalId + " (historial de updates):")
            out.add(parentFocusText())
        }
        return out
    }

    /** Builds the report text of one compressed block failure. */
    fun compressedFailureReport(
        blob: ByteArray,
        reader: ByteReader,
        trace: String,
        context: String,
        error: Throwable
    ): String {
        val builder = StringBuilder(700)
        builder.append("FALLO ObjectUpdateCompressed: ").append(error.javaClass.name)
        val message = error.message
        if (!message.isNullOrEmpty()) {
            builder.append(": ").append(message)
        }
        builder.append('\n').append("  ").append(context)
        builder.append('\n').append("  campo en curso: ").append(reader.field)
        builder.append("  ·  offset ").append(reader.position).append(" de ").append(blob.size)
        builder.append('\n').append("  bytes: ").append(hexPreview(blob))
        builder.append('\n').append("  offsets por campo: ").append(trace)
        builder.append('\n').append("  traza completa:\n").append(stackTrace(error))
        return builder.toString()
    }

    /**
     * Diagnostic-only (fase 2.13a, revision): the first [limit] bytes of [blob]
     * as space-separated hex. Used by the parser-error dump and by the decoder
     * when it captures a sample's neighbouring field.
     */
    fun hexPreview(blob: ByteArray, limit: Int = 48): String {
        val builder = StringBuilder(180)
        for (i in blob.indices) {
            if (i >= limit) {
                builder.append("...")
                break
            }
            builder.append(String.format(Locale.US, "%02x", blob[i]))
            if (i % 8 == 7) {
                builder.append(' ')
            }
        }
        return builder.toString()
    }

    private fun stackTrace(error: Throwable): String {
        val writer = java.io.StringWriter(512)
        error.printStackTrace(java.io.PrintWriter(writer))
        return "    " + writer.toString().trim().replace("\n", "\n    ")
    }

    private const val COMPRESSED_HEADER = 84
}
