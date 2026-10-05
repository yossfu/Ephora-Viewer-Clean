package com.lumiyaviewer.lumiya.slproto.world

import android.util.Log
import com.lumiyaviewer.lumiya.slproto.base.LLUUIDUtil
import com.lumiyaviewer.lumiya.slproto.base.Quaternion
import com.lumiyaviewer.lumiya.slproto.base.Vector3
import com.lumiyaviewer.lumiya.slproto.messages.Block
import com.lumiyaviewer.lumiya.slproto.messages.SLMessage
import com.lumiyaviewer.lumiya.slproto.messages.SLMessageCodec

/**
 * Turns the three object-update messages the simulator sends into scene objects.
 *
 * * `ObjectUpdate` (High 12) — the full record, with all path/profile parameters.
 *   Its `ObjectData` field is the packed placement block (position, velocity,
 *   acceleration, rotation, angular velocity).
 * * `ObjectUpdateCompressed` (High 13) — the same information compressed, with
 *   optional trailing sections selected by a flag word.
 * * `ImprovedTerseObjectUpdate` (High 15) — the per-frame movement update, with
 *   quantised velocity/rotation. This is what makes avatars move smoothly.
 *
 * ## The incremental model, and why it is written this way
 *
 * A region never sends an object's full description twice. One `ObjectUpdate`
 * defines the object's geometry; from then on the simulator sends
 * `ObjectUpdateCompressed` (placement plus, usually, the same parameter block
 * again) and `ImprovedTerseObjectUpdate` (movement only) messages. So the
 * decoder **merges**: it looks the object up by `LocalID` in the [WorldModel],
 * writes only the fields the message actually carries, and leaves the geometric
 * definition in place. Nothing here ever calls a `clearShape()`, resets
 * path/profile to a default, or builds a second, empty record for an object it
 * has already seen — the object's geometry is defined by whichever update last
 * carried a real path/profile block (see [SceneObject.noteShapeReceived] and
 * [SceneObject.noteUpdateWithoutShape]).
 *
 * ## What breaks if a field is read at the wrong offset
 *
 * These blobs are length-delimited and self-describing only through their flag
 * word, so one mis-read makes every following value garbage. The old version of
 * this file read the `ExtraParams` section as "the whole rest of the blob",
 * which (a) meant the path/profile block that always follows it was never read,
 * so *no* compressed update ever produced a shape, and (b) fed the parameter
 * bytes to the extra-parameter parser as a *length*, which is where
 * `length=113; index=-872415225` came from: an unsigned length of 0xCC000007
 * used as an array index. The section is now walked entry by entry, bounded, and
 * a bogus length is *reported* (field, offsets, stack trace) instead of being
 * turned into a cursor position.
 */
object ObjectUpdateDecoder {

    private const val TAG = "ObjectUpdateDecoder"

    /** Size of the packed placement block of a full `ObjectUpdate`. */
    private const val PACKED_FULL_SIZE = 60

    /**
     * Size of the fixed header of an `ObjectUpdateCompressed` blob:
     * UUID(16) LocalID(4) PCode(1) State(1) CRC(4) Material(1) ClickAction(1)
     * Scale(12) Position(12) Rotation(12) Flags(4) OwnerID(16).
     */
    private const val COMPRESSED_HEADER_SIZE = 84

    /**
     * Path/profile values plus the TextureEntry length word.
     *
     * `PathCurve(1) PathBegin(2) PathEnd(2) PathScaleX(1) PathScaleY(1)
     * PathShearX(1) PathShearY(1) PathTwist(1) PathTwistBegin(1)
     * PathRadiusOffset(1) PathTaperX(1) PathTaperY(1) PathRevolutions(1)
     * PathSkew(1) ProfileCurve(1) ProfileBegin(2) ProfileEnd(2)
     * ProfileHollow(2)` = 23 bytes, plus the 4-byte TextureEntry length. The
     * earlier value here was 26, one byte short: a block with exactly 26 bytes
     * left passed the test and then read the TextureEntry length past the end.
     */
    private const val COMPRESSED_PARAMS_SIZE = 27

    /** `CompressedFlags`, straight from LL's `llviewerobject.cpp`. */
    private const val FLAG_SCRATCH_PAD = 0x01L
    private const val FLAG_TREE = 0x02L
    private const val FLAG_HAS_TEXT = 0x04L
    private const val FLAG_HAS_PARTICLES = 0x08L
    private const val FLAG_HAS_SOUND = 0x10L
    private const val FLAG_HAS_PARENT = 0x20L
    private const val FLAG_TEXTURE_ANIM = 0x40L
    private const val FLAG_HAS_ANGULAR_VELOCITY = 0x80L
    private const val FLAG_HAS_NAME_VALUES = 0x100L
    private const val FLAG_MEDIA_URL = 0x200L

    /** Fixed size of the packed particle system the compressed update may carry. */
    private const val PARTICLE_SYSTEM_SIZE = 86

    /** `ExtraParamType` values (`indra/llprimitive/llprimitive.h`). */
    private const val EXTRA_PARAM_FLEXIBLE = 0x10
    private const val EXTRA_PARAM_LIGHT = 0x20
    private const val EXTRA_PARAM_SCULPT = 0x30
    private const val EXTRA_PARAM_LIGHT_IMAGE = 0x40
    private const val EXTRA_PARAM_MESH = 0x60

    /** `SculptData`: the asset UUID plus the sculpt type byte. */
    private const val SCULPT_ENTRY_SIZE = 17

    // ------------------------------------------------------------ full update

    /**
     * `ObjectUpdate` (High 12): the complete record. Every block carries the
     * path/profile definition, so this is the message that *defines* a prim's
     * geometry; later messages merge into it.
     */
    fun applyFull(message: SLMessage, model: WorldModel): Int {
        val blocks = message.blocks["ObjectData"] ?: return 0
        var applied = 0
        for (block in blocks) {
            val localId = block.u32("ID").toInt()
            if (localId == 0) {
                continue
            }
            ObjectUpdateDiagnostics.noteFullBlock()
            try {
                val sceneObject = model.getOrCreate(localId)
                val before = describeShape(sceneObject)
                sceneObject.uuid = block.uuid("FullID")
                sceneObject.pcode = block.u8("PCode")
                sceneObject.attachmentPoint = block.u8("State")
                sceneObject.material = block.u8("Material")
                sceneObject.clickAction = block.u8("ClickAction")
                sceneObject.scale = block.vector3("Scale")
                sceneObject.parentId = block.u32("ParentID").toInt()
                ObjectUpdateDiagnostics.noteParent(
                    localId,
                    sceneObject.updatesSeen + 1,
                    UpdateSource.FULL.label,
                    sceneObject.parentId,
                    carried = true
                )
                sceneObject.updateFlags = block.u32("UpdateFlags")
                readPathProfile(sceneObject, block)
                // The definition is now known: this is the first update type that
                // gives a prim its shape, and it must never be undone by a later
                // terse or truncated update.
                sceneObject.noteShapeReceived(UpdateSource.FULL)
                ObjectUpdateDiagnostics.noteShapeFromFull()
                readPlacement(sceneObject, block.bytes("ObjectData"))
                val extraParams = block.bytes("ExtraParams")
                val consumed = readExtraParams(
                    sceneObject,
                    ByteReader(extraParams).at("ExtraParams"),
                    blobLength = extraParams.size
                ).consumed
                sceneObject.extraParamsSize = consumed
                val textureEntry = block.bytes("TextureEntry")
                applyTextureEntry(
                    sceneObject, textureEntry, overwriteTextureId = true, source = UpdateSource.FULL
                ) { textureContext(message, block, textureEntry, "ObjectData") }
                sceneObject.ownerId = block.uuid("OwnerID")
                sceneObject.text = decodeText(block.bytes("Text"))
                sceneObject.name = nameOf(sceneObject, block.bytes("NameValue"))
                sceneObject.waitingForFullUpdate = false
                model.put(sceneObject)
                applied += 1
                focusUpdate(
                    localId,
                    sceneObject.updatesSeen,
                    UpdateSource.FULL,
                    sceneObject.parentId,
                    parentCarried = true,
                    position = sceneObject.position,
                    params = true
                )
                ObjectUpdateDiagnostics.traceUpdate(
                    localId,
                    "update #" + sceneObject.updatesSeen + " " + UpdateSource.FULL.label +
                        ": campos=colocacion+escala+material+pcode+path/profile+TextureEntry(" +
                        textureEntry.size + "B)+ExtraParams(" + consumed + "B)" +
                        "; forma " + before + " -> " + describeShape(sceneObject)
                )
            } catch (error: Throwable) {
                model.noteParseFailure()
                val report = "FALLO ObjectUpdate bloque #" + localId + "\n" +
                    "  ultimo campo del codec: " + SLMessageCodec.lastDecodeError +
                    "\n  traza completa:\n  " + stackTrace(error)
                ObjectUpdateDiagnostics.recordFailure(report)
                Log.e(TAG, report)
            }
        }
        return applied
    }

    // ------------------------------------------------------ compressed update

    /**
     * `ObjectUpdateCompressed` (High 13).
     *
     * The blob's layout is fixed by LL's `processObjectUpdateCompressed` (and
     * mirrored by LibreMetaverse's `ObjectManager`), and it is **not** the same
     * order as the full update: local id, pcode, state, a 4-byte CRC, material,
     * click action, scale, position, rotation, the flag word and the owner id,
     * then the flag-selected optional blocks, then the extra parameters, then
     * the path/profile values, then the TextureEntry. Every value is
     * little-endian and the path/profile values are exactly the wire values — no
     * unpacking happens here; `slcore` does that (see `sl_prim_geometry.cpp`).
     *
     * Every field is traced with the offset it occupied, so the report can show
     * the layout the region actually sent. A block that throws is recorded in
     * full (field, offsets, pcode, local id, flags, stack trace) — never skipped
     * silently — and the remaining blocks of the same message are still decoded.
     */
    fun applyCompressed(message: SLMessage, model: WorldModel): Int {
        val blocks = message.blocks["ObjectData"] ?: return 0
        var applied = 0
        for (block in blocks) {
            val blob = block.bytes("Data")
            ObjectUpdateDiagnostics.noteCompressedBlock(blob.size)
            if (blob.size < COMPRESSED_HEADER_SIZE) {
                model.noteCompressedWithoutHeader()
                ObjectUpdateDiagnostics.noteCompressedTooShort(blob.size)
                continue
            }
            applied += decodeCompressedBlock(blob, model)
        }
        return applied
    }

    private fun decodeCompressedBlock(blob: ByteArray, model: WorldModel): Int {
        val reader = ByteReader(blob)
        val trace = FieldTrace(blob.size)
        var localId = 0
        var uuid = "-"
        var pcode = 0
        var flags = 0L
        var sceneObject: SceneObject? = null
        var fields: MutableList<String>? = null
        var before = "-"
        var paramsRead = false
        try {
            val uuidBytes = traceField(trace, "UUID", reader) { reader.bytes(16) }
            uuid = LLUUIDUtil.fromBytes(uuidBytes, 0)
            localId = traceField(trace, "LocalID", reader) { reader.u32().toInt() }
            if (localId == 0) {
                return 0
            }
            val watched = ObjectUpdateDiagnostics.isTraced(localId)
            fields = if (watched) ArrayList(8) else null
            val mine = model.getOrCreate(localId)
            sceneObject = mine
            before = describeShape(mine)
            pcode = traceField(trace, "PCode", reader) { reader.u8() }
            val state = traceField(trace, "State (attachment)", reader) { reader.u8() }
            traceField(trace, "CRC (ignorado)", reader) { reader.u32() }
            val material = traceField(trace, "Material", reader) { reader.u8() }
            val clickAction = traceField(trace, "ClickAction", reader) { reader.u8() }
            val scale = traceField(trace, "Scale", reader) { reader.vector3() }
            val position = traceField(trace, "Position", reader) { reader.vector3() }
            val rotation = traceField(trace, "Rotation (xyz)", reader) { reader.quaternionFromXyz() }
            flags = traceField(trace, "CompressedFlags", reader) { reader.u32() }
            val owner = traceField(trace, "OwnerID", reader) { reader.bytes(16) }

            // --- merge: only the fields this update actually carries ----------
            mine.uuid = uuid
            mine.pcode = pcode
            mine.attachmentPoint = state
            mine.material = material
            mine.clickAction = clickAction
            mine.scale = scale
            mine.position = position
            mine.rotation = rotation
            mine.positionKnown = true
            mine.ownerId = LLUUIDUtil.fromBytes(owner, 0)
            fields?.add("colocacion+escala+material+pcode")

            // --- optional blocks, in the order the viewer reads them ---------
            if ((flags and FLAG_HAS_ANGULAR_VELOCITY) != 0L) {
                traceField(trace, "AngularVelocity", reader) { reader.skip(12) }
            }
            if ((flags and FLAG_HAS_PARENT) != 0L) {
                mine.parentId = traceField(trace, "ParentID", reader) { reader.u32().toInt() }
                fields?.add("ParentID")
            }
            // No `else`: an update that does not carry the field says *nothing*
            // about the parent, so the parent already stored stays. This mirrors
            // the reference viewer, which seeds `parent_id` with the object's
            // current parent before reading the block (`llviewerobject.cpp`), so a
            // partial update can never silently unlink a linkset child.
            // Diagnostic only (fase 2.10/2.11): the compressed path is the one
            // place where an update that says nothing about the parent used to
            // write 0, and the counters say how often that silence happened.
            ObjectUpdateDiagnostics.noteParent(
                localId,
                mine.updatesSeen + 1,
                UpdateSource.COMPRESSED.label,
                mine.parentId,
                carried = (flags and FLAG_HAS_PARENT) != 0L
            )
            if ((flags and FLAG_TREE) != 0L) {
                mine.treeSpecies = traceField(trace, "TreeSpecies", reader) { reader.u8() }
                fields?.add("TreeSpecies")
            } else if ((flags and FLAG_SCRATCH_PAD) != 0L) {
                val size = traceField(trace, "ScratchPad.Length", reader) { reader.u8() }
                traceField(trace, "ScratchPad", reader) { reader.skip(size) }
            }
            if ((flags and FLAG_HAS_TEXT) != 0L) {
                mine.text = traceField(trace, "Text", reader) { reader.string() }
                traceField(trace, "TextColor", reader) { reader.skip(4) }
                fields?.add("Text")
            } else {
                // A missing text block means "no floating text", which is what
                // the field already holds. It is not part of the geometry, so
                // clearing it is safe and mirrors what the region is saying.
                mine.text = ""
            }
            if ((flags and FLAG_MEDIA_URL) != 0L) {
                traceField(trace, "MediaURL", reader) { reader.string() }
            }
            if ((flags and FLAG_HAS_PARTICLES) != 0L) {
                traceField(trace, "ParticleSystem", reader) { reader.skip(PARTICLE_SYSTEM_SIZE) }
                fields?.add("ParticleSystem")
            }
            val extra = readExtraParams(
                mine,
                reader.at("ExtraParams"),
                blobLength = blob.size
            )
            mine.extraParamsSize = extra.consumed
            if (extra.consumed > 0) {
                fields?.add("ExtraParams(" + extra.consumed + "B)")
            }
            if ((flags and FLAG_HAS_SOUND) != 0L) {
                traceField(trace, "Sound", reader) { reader.skip(16 + 4 + 1 + 4) }
            }
            if ((flags and FLAG_HAS_NAME_VALUES) != 0L) {
                mine.name = traceField(trace, "NameValues", reader) { nameOf(mine, reader.string()) }
                mine.hasNameValues = true
                fields?.add("NameValues")
            }

            // --- path/profile, TextureEntry, animation ------------------------
            if (!extra.trusted) {
                // The extra-parameter section could not be walked to its end, so
                // where the next section *starts* is unknown. Reading the
                // path/profile block from a guessed offset is exactly how a
                // parser produces a shape out of unrelated bytes, so it is not
                // attempted: the stored definition (if any) is kept untouched and
                // the block is reported as unreadable.
                noteShapeAbsent(mine, UpdateSource.COMPRESSED)
                model.noteCompressedWithoutParams()
                ObjectUpdateDiagnostics.noteCompressedFailure()
                model.noteParseFailure()
                ObjectUpdateDiagnostics.noteCompressedPlacementOnly(
                    "bloque de " + blob.size + " bytes: la seccion ExtraParams no se pudo recorrer" +
                        " completa, asi que no se sabe donde empieza path/profile"
                )
                ObjectUpdateDiagnostics.recordFailure(
                    "FALLO ObjectUpdateCompressed: seccion ExtraParams ilegible\n" +
                        "  bloque=" + blob.size + " bytes, localID=" + localId + ", uuid=" + uuid +
                        ", pcode=" + pcode + ", flags=0x" + java.lang.Long.toHexString(flags) +
                        ", offset=" + reader.position + "/" + blob.size +
                        "\n  forma conservada: " + describeShape(mine) +
                        "\n  " + ObjectUpdateDiagnostics.extraParamFirstBogus +
                        "\n  offsets por campo: " + trace.text()
                )
                fields?.add("SIN path/profile (ExtraParams ilegible)")
            } else if (reader.remaining < COMPRESSED_PARAMS_SIZE) {
                // No parameter section: this update says nothing about the
                // object's shape. It must not be read as "the prim has no
                // shape" — any definition received earlier stays.
                noteShapeAbsent(mine, UpdateSource.COMPRESSED)
                model.noteCompressedWithoutParams()
                ObjectUpdateDiagnostics.noteCompressedPlacementOnly(
                    "bloque de " + blob.size + " bytes: quedan " + reader.remaining +
                        " bytes tras la cabecera y las secciones opcionales; se necesitan " +
                        COMPRESSED_PARAMS_SIZE + " para path/profile + TextureEntry"
                )
                fields?.add("SIN path/profile (solo colocacion)")
            } else {
                readPathProfileCompressed(mine, reader, trace)
                mine.noteShapeReceived(UpdateSource.COMPRESSED)
                ObjectUpdateDiagnostics.noteShapeFromCompressed()
                ObjectUpdateDiagnostics.noteCompressedWithParams()
                paramsRead = true
                fields?.add("path/profile")
                val textureEntryLengthStart = reader.position
                val textureEntryLength = traceField(trace, "TextureEntry.Length", reader) { reader.u32() }
                val bogusLength = textureEntryLength > reader.remaining
                if (bogusLength) {
                    ObjectUpdateDiagnostics.noteBogusSectionLength(
                        "TextureEntry",
                        textureEntryLength,
                        reader.remaining,
                        blob.size
                    )
                    ObjectUpdateDiagnostics.recordFailure(
                        "ObjectUpdateCompressed: TextureEntry declara " + textureEntryLength +
                            " bytes y solo quedan " + reader.remaining + " (bloque de " + blob.size +
                            ")" + "\n  traza completa:\n  " + stackTrace(IllegalStateException("longitud de TextureEntry"))
                    )
                }
                // Where the declared entry starts, for the temporary capture: a
                // bogus length is invisible in the slice alone, but obvious in the
                // window around it.
                //
                // A length that fits but is *below* what the writer emits (1 ..
                // FULL_WIRE_SIZE-1) is just as impossible as one that overflows:
                // the region always writes all eleven fields, so no legitimate
                // section is that small. It is recorded (category D) without being
                // "corrected", because the blob that comes out of it is evidence
                // about the framing, not about the entry's own format.
                val impossibleLength =
                    textureEntryLength in 1 until TextureEntry.FULL_WIRE_SIZE.toLong()
                if (impossibleLength) {
                    ObjectUpdateDiagnostics.noteImpossibleTextureLength(
                        textureEntryLength, reader.remaining, blob.size, localId
                    )
                }
                val framingSuspect = impossibleLength || bogusLength
                val textureEntryStart = reader.position
                val textureEntry = traceField(trace, "TextureEntry", reader) {
                    // Never convert an implausible length to an Int: it is what
                    // turns a bad length into a negative index. A length that
                    // does not fit has already been reported.
                    reader.bytes(textureEntryLength.coerceIn(0L, reader.remaining.toLong()).toInt())
                }
                applyTextureEntry(
                    mine, textureEntry, overwriteTextureId = true,
                    source = UpdateSource.COMPRESSED, framingSuspect = framingSuspect
                ) {
                    ObjectUpdateDiagnostics.window(
                        blob, textureEntryStart, textureEntry.size,
                        "bloque Data de ObjectUpdateCompressed",
                        declaredLength =
                            if (textureEntryLength in 0..Int.MAX_VALUE.toLong()) {
                                textureEntryLength.toInt()
                            } else {
                                -1
                            },
                        declaredLengthOffset = textureEntryLengthStart,
                        bodySize = blob.size
                    )
                }
                if (textureEntry.isNotEmpty()) {
                    fields?.add("TextureEntry(" + textureEntry.size + "B)")
                }
                if ((flags and FLAG_TEXTURE_ANIM) != 0L) {
                    traceField(trace, "TextureAnimation", reader) { reader.skip(4) }
                }
                mine.waitingForFullUpdate = false
            }

            model.put(mine)
            focusUpdate(
                localId,
                mine.updatesSeen,
                UpdateSource.COMPRESSED,
                mine.parentId,
                parentCarried = (flags and FLAG_HAS_PARENT) != 0L,
                position = mine.position,
                params = paramsRead
            )
            if (fields != null) {
                ObjectUpdateDiagnostics.traceUpdate(
                    localId,
                    "update #" + mine.updatesSeen + " " + UpdateSource.COMPRESSED.label +
                        " (flags=0x" + java.lang.Long.toHexString(flags) + ", pcode=" + pcode +
                        ", " + blob.size + "B): campos=" + fields.joinToString("+") +
                        "; forma " + before + " -> " + describeShape(mine)
                )
            }
            ObjectUpdateDiagnostics.recordFieldTrace(
                "bloque de " + blob.size + " bytes, localID " + localId + ", pcode " + pcode +
                    ", flags 0x" + java.lang.Long.toHexString(flags),
                trace.text(),
                complete = paramsRead
            )
            ObjectUpdateDiagnostics.noteReaderViolations(reader)
            return 1
        } catch (error: Throwable) {
            ObjectUpdateDiagnostics.noteReaderViolations(reader)
            ObjectUpdateDiagnostics.noteCompressedFailure()
            model.noteParseFailure()
            val context = "bloque=" + blob.size + " bytes, localID=" + localId + ", uuid=" + uuid +
                ", pcode=" + pcode + ", flags=0x" + java.lang.Long.toHexString(flags) +
                (sceneObject?.let { ", forma antes=" + before + ", updates=" + it.updatesSeen } ?: "")
            val report = ObjectUpdateDiagnostics.compressedFailureReport(
                blob,
                reader,
                trace.text(),
                context,
                error
            )
            ObjectUpdateDiagnostics.recordFailure(report)
            Log.e(TAG, report)
            // The fields decoded so far are still merged (usually placement), so
            // the object does not teleport back; the geometry was untouched.
            sceneObject?.let { model.put(it) }
            return 0
        }
    }

    /**
     * One line per update for the object the user is following (fase 2.10).
     *
     * It records what the update wrote — type, parent, position, whether a
     * geometric definition came with it — so "was this object created once, or
     * created and then updated and then recreated?" can be read off the parser
     * side instead of inferred. It is a notification: nothing about the decode
     * changes, and when no object is being followed it costs one comparison.
     */
    private fun focusUpdate(
        localId: Int,
        updateNumber: Int,
        source: UpdateSource,
        parentId: Int,
        parentCarried: Boolean,
        position: Vector3,
        params: Boolean
    ) {
        if (!ObjectUpdateDiagnostics.isFocused(localId)) {
            return
        }
        ObjectUpdateDiagnostics.focusLine(
            localId,
            "update #" + updateNumber + " " + source.label +
                ": parent=" + parentId + (if (parentCarried) "" else " (el update no llevaba ParentID)") +
                " pos=" + fmtVec(position) +
                (if (params) " con path/profile" else " SIN path/profile")
        )
    }

    private fun fmtVec(v: Vector3): String =
        String.format(java.util.Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z)

    /**
     * Records that an update arrived without a geometric definition.
     *
     * If the object already had one, that definition is now *persisted* and the
     * fact is counted: this counter is the evidence that partial updates do not
     * erase shapes (the old behaviour this phase exists to fix).
     */
    private fun noteShapeAbsent(sceneObject: SceneObject, source: UpdateSource) {
        val hadShape = sceneObject.paramsKnown
        sceneObject.noteUpdateWithoutShape(source)
        if (hadShape) {
            ObjectUpdateDiagnostics.noteShapeKept()
            if (source == UpdateSource.TERSE) {
                ObjectUpdateDiagnostics.noteTersePreserved()
            }
        } else {
            ObjectUpdateDiagnostics.noteUpdateWithoutShape(source.label)
        }
    }

    // ---------------------------------------------------------- terse update

    /**
     * `ImprovedTerseObjectUpdate` (High 15): movement only.
     *
     * Layout: `LocalID(4) State(1) Avatar(1) [CollisionPlane(16) if avatar]
     * Position(12) Velocity(6) Acceleration(6) Rotation(4×u16) Omega(6)`, with an
     * optional `TextureEntry` field outside the blob.
     *
     * A terse update carries **no** path/profile, and this method therefore
     * never writes any: the object keeps whatever definition it received. It is
     * this message, arriving 1973 times for objects whose defining update had
     * been mis-parsed, that used to leave every prim in the region looking like
     * an unshaped object.
     */
    fun applyTerse(message: SLMessage, model: WorldModel): Int {
        val blocks = message.blocks["ObjectData"] ?: return 0
        var applied = 0
        for (block in blocks) {
            val blob = block.bytes("Data")
            if (blob.size < 24) {
                continue
            }
            ObjectUpdateDiagnostics.noteTerseBlock()
            val reader = ByteReader(blob).at("Terse.LocalID")
            val localId = reader.u32().toInt()
            if (localId == 0) {
                continue
            }
            val sceneObject = model.getOrCreate(localId)
            val before = describeShape(sceneObject)
            reader.at("Terse.State")
            sceneObject.attachmentPoint = reader.u8()
            reader.at("Terse.Avatar")
            val isAvatar = reader.u8() != 0
            if (isAvatar) {
                reader.at("Terse.CollisionPlane")
                reader.skip(16)
                sceneObject.pcode = SceneObject.PCODE_AVATAR
            }
            reader.at("Terse.Position")
            sceneObject.position = reader.vector3()
            sceneObject.positionKnown = true
            reader.at("Terse.Velocity")
            reader.skip(6)
            reader.at("Terse.Acceleration")
            reader.skip(6)
            reader.at("Terse.Rotation")
            sceneObject.rotation = reader.quantisedQuaternion()
            val terseField = block.bytes("TextureEntry")
            // The entry the parser is given: the field minus the four bytes the
            // region writes in front of it (see `consumeTersePrefix`). An empty
            // field stays empty, so the "the update said nothing" branch below is
            // exactly the one it always was.
            val textureEntry = if (terseField.isEmpty()) {
                terseField
            } else {
                ObjectUpdateDiagnostics.consumeTersePrefix(terseField)
            }
            if (textureEntry.isNotEmpty()) {
                applyTextureEntry(
                    sceneObject, textureEntry, overwriteTextureId = false, source = UpdateSource.TERSE,
                    received = terseField
                ) { textureContext(message, block, terseField, "Data") }
            } else {
                // A terse update without the field says nothing about the texture,
                // and that silence is not one of the "too short" blobs. It is
                // counted separately so the revision can state the difference.
                ObjectUpdateDiagnostics.noteTerseWithoutTextureEntry()
            }
            sceneObject.waitingForFullUpdate = false
            // Movement only: the geometric definition is not part of this
            // message, so it is not touched. Not cleared, not defaulted.
            noteShapeAbsent(sceneObject, UpdateSource.TERSE)
            model.put(sceneObject)
            applied += 1
            focusUpdate(
                localId,
                sceneObject.updatesSeen,
                UpdateSource.TERSE,
                sceneObject.parentId,
                parentCarried = false,
                position = sceneObject.position,
                params = false
            )
            ObjectUpdateDiagnostics.noteReaderViolations(reader)
            if (ObjectUpdateDiagnostics.isTraced(localId)) {
                ObjectUpdateDiagnostics.traceUpdate(
                    localId,
                    "update #" + sceneObject.updatesSeen + " " + UpdateSource.TERSE.label +
                        ": campos=solo colocacion (State, avatar=" + isAvatar + ", posicion, rotacion" +
                        (if (terseField.isNotEmpty()) {
                            ", TextureEntry(" + textureEntry.size + "B de entrada, campo de " +
                                terseField.size + "B)" 
                        } else {
                            ""
                        }) +
                        "); forma " + before + " -> " + describeShape(sceneObject)
                )
            }
        }
        return applied
    }

    // --------------------------------------------------------- extra params

    /**
     * The `ExtraParams` section: a one-byte count, then for each entry a 16-bit
     * type, a 32-bit length and that many bytes.
     *
     * Two things matter here and both used to be wrong:
     *
     * * **The section is walked, not swallowed.** The caller continues reading
     *   whatever follows it (the path/profile block, in a compressed update).
     *   Treating "the rest of the blob" as the section is what made every
     *   compressed update report "no parameters".
     * * **The length is checked before it is used.** It is a `U32`: 0xCC000007 is
     *   3 422 552 071 bytes, not an index. A length that does not fit is
     *   refused, counted, and named — and the walk stops, because after a
     *   malformed entry there is no way to know where the next one starts.
     *
     * Returns the number of bytes the section occupied (1 when it was empty).
     */
    private fun readExtraParams(sceneObject: SceneObject, reader: ByteReader, blobLength: Int = 0): ExtraParamsResult {
        if (reader.remaining < 1) {
            ObjectUpdateDiagnostics.noteExtraParamTruncated(0, 0, reader.remaining)
            return ExtraParamsResult(0, false)
        }
        val start = reader.position
        val count = reader.u8()
        for (index in 0 until count) {
            if (reader.remaining < 6) {
                ObjectUpdateDiagnostics.noteExtraParamTruncated(count, index, reader.remaining)
                return ExtraParamsResult(reader.position - start, false)
            }
            reader.at("ExtraParams[$index].Type")
            val type = reader.u16()
            reader.at("ExtraParams[$index].Length")
            val length = reader.u32()
            if (length > reader.remaining.toLong()) {
                // Refused before it can become a cursor position or an index:
                // 0xCC000007 is 3 422 552 071 bytes, not -872415225.
                ObjectUpdateDiagnostics.noteExtraParamBogusLength(type, length, reader.remaining, blobLength)
                return ExtraParamsResult(reader.position - start, false)
            }
            val size = length.toInt()
            val entryStart = reader.position
            ObjectUpdateDiagnostics.noteExtraParamEntry()
            reader.at("ExtraParams[$index].Datos")
            when (type) {
                EXTRA_PARAM_SCULPT, EXTRA_PARAM_MESH -> if (size >= SCULPT_ENTRY_SIZE) {
                    sceneObject.sculptId = LLUUIDUtil.fromBytes(reader.bytes(16), 0)
                    sceneObject.sculptType = reader.u8()
                    sceneObject.sculptTypeCode = type
                }
                EXTRA_PARAM_FLEXIBLE -> sceneObject.isFlexible = true
                EXTRA_PARAM_LIGHT -> sceneObject.isLight = true
                EXTRA_PARAM_LIGHT_IMAGE -> Unit
                else -> Unit
            }
            // Always land exactly at the end of the entry, whatever was read
            // from it: an entry that is longer than the fields we know about
            // must not shift every following byte.
            reader.seek(entryStart + size)
        }
        return ExtraParamsResult(reader.position - start, true)
    }

    /**
     * One `ExtraParams` walk: how many bytes it occupied, and whether it reached
     * the end of the section. `trusted == false` means the cursor must not be
     * used to locate the sections that follow (the geometry generator would then
     * be fed unrelated bytes).
     */
    private class ExtraParamsResult(val consumed: Int, val trusted: Boolean)

    // ------------------------------------------------------------ path/profile

    /** Reads the same path/profile block from a full update's named fields. */
    private fun readPathProfile(sceneObject: SceneObject, block: Block) {
        sceneObject.pathCurve = block.u8("PathCurve")
        sceneObject.profileCurve = block.u8("ProfileCurve")
        sceneObject.pathBegin = block.u16("PathBegin")
        sceneObject.pathEnd = block.u16("PathEnd")
        sceneObject.pathScaleX = block.u8("PathScaleX")
        sceneObject.pathScaleY = block.u8("PathScaleY")
        sceneObject.pathShearX = block.u8("PathShearX")
        sceneObject.pathShearY = block.u8("PathShearY")
        sceneObject.pathTwist = block.u8("PathTwist")
        sceneObject.pathTwistBegin = block.u8("PathTwistBegin")
        sceneObject.pathRadiusOffset = block.u8("PathRadiusOffset")
        sceneObject.pathTaperX = block.u8("PathTaperX")
        sceneObject.pathTaperY = block.u8("PathTaperY")
        sceneObject.pathRevolutions = block.u8("PathRevolutions")
        sceneObject.pathSkew = block.u8("PathSkew")
        sceneObject.profileBegin = block.u16("ProfileBegin")
        sceneObject.profileEnd = block.u16("ProfileEnd")
        sceneObject.profileHollow = block.u16("ProfileHollow")
    }

    /**
     * The path/profile block of a compressed update, in LL's order: 15 one-byte
     * values, then profileBegin/End (u16 each) and profileHollow (u16).
     *
     * The signed fields (`PathShearX/Y`, `PathTwist`, `PathTwistBegin`,
     * `PathRadiusOffset`, `PathTaperX/Y`, `PathSkew`) are read as bytes and kept
     * as their raw 8-bit pattern: `slcore` reinterprets the low byte as signed
     * (see `asSigned` in `sl_prim_geometry.cpp`), which is how Linden Lab's own
     * `(S8)` casts work, so the geometry generator sees the same value the
     * viewer would.
     */
    private fun readPathProfileCompressed(sceneObject: SceneObject, reader: ByteReader, trace: FieldTrace) {
        sceneObject.pathCurve = traceField(trace, "PathCurve", reader) { reader.u8() }
        sceneObject.pathBegin = traceField(trace, "PathBegin", reader) { reader.u16() }
        sceneObject.pathEnd = traceField(trace, "PathEnd", reader) { reader.u16() }
        sceneObject.pathScaleX = traceField(trace, "PathScaleX", reader) { reader.u8() }
        sceneObject.pathScaleY = traceField(trace, "PathScaleY", reader) { reader.u8() }
        sceneObject.pathShearX = traceField(trace, "PathShearX", reader) { reader.u8() }
        sceneObject.pathShearY = traceField(trace, "PathShearY", reader) { reader.u8() }
        sceneObject.pathTwist = traceField(trace, "PathTwist", reader) { reader.u8() }
        sceneObject.pathTwistBegin = traceField(trace, "PathTwistBegin", reader) { reader.u8() }
        sceneObject.pathRadiusOffset = traceField(trace, "PathRadiusOffset", reader) { reader.u8() }
        sceneObject.pathTaperX = traceField(trace, "PathTaperX", reader) { reader.u8() }
        sceneObject.pathTaperY = traceField(trace, "PathTaperY", reader) { reader.u8() }
        sceneObject.pathRevolutions = traceField(trace, "PathRevolutions", reader) { reader.u8() }
        sceneObject.pathSkew = traceField(trace, "PathSkew", reader) { reader.u8() }
        sceneObject.profileCurve = traceField(trace, "ProfileCurve", reader) { reader.u8() }
        sceneObject.profileBegin = traceField(trace, "ProfileBegin", reader) { reader.u16() }
        sceneObject.profileEnd = traceField(trace, "ProfileEnd", reader) { reader.u16() }
        sceneObject.profileHollow = traceField(trace, "ProfileHollow", reader) { reader.u16() }
    }

    // ------------------------------------------------------------- placement

    /**
     * The packed placement block of a full update:
     * `Position(12) Velocity(12) Acceleration(12) Rotation(12) AngularVelocity(12)`.
     * A shorter block means a legacy tree and carries no placement at all.
     */
    private fun readPlacement(sceneObject: SceneObject, packed: ByteArray) {
        if (packed.size < PACKED_FULL_SIZE) {
            return
        }
        val reader = ByteReader(packed).at("ObjectData.Position")
        sceneObject.position = reader.vector3()
        reader.at("ObjectData.Velocity+Acceleration")
        reader.skip(24)
        reader.at("ObjectData.Rotation")
        sceneObject.rotation = reader.quaternionFromXyz()
        sceneObject.positionKnown = true
        ObjectUpdateDiagnostics.noteReaderViolations(reader)
    }

    // ------------------------------------------------------------- utilities

    /**
     * Applies a `TextureEntry` blob: decodes the eleven packed per-face fields
     * (`TextureEntry.parse`) and stores the result on the object.
     *
     * `textureId`, `textureColor` and `fullBright` keep mirroring the **first
     * face**, because the material path builds one material per face today and
     * reads those fields. They are now taken from the decoded entry instead of
     * from "the first 16 bytes" — the tint and the fullbright flag were never
     * read off the wire before this phase, so they were always white and false.
     *
     * [overwriteTextureId] mirrors each message's own semantics: a full or
     * compressed update states the object's texture (an all-zero default means
     * "no texture"), while a terse update only carries one when it has something
     * to say, so silence leaves the stored texture alone.
     *
     * @param source which of the three messages this blob came from, so the
     *   report can split its counters instead of blaming "the parser".
     * @param context builds the window of bytes around the blob inside the
     *   buffer it was cut from. It is *lazy* because on the field-based paths it
     *   costs a re-encode, and it is only ever called for a kept sample.
     * @param framingSuspect true when the section this blob came out of already
     *   declared an impossible length, so the blob is a framing artefact and says
     *   nothing about the entry's own format (category D of the revision).
     */
    private fun applyTextureEntry(
        target: SceneObject,
        bytes: ByteArray,
        overwriteTextureId: Boolean,
        source: UpdateSource,
        framingSuspect: Boolean = false,
        received: ByteArray? = null,
        context: (() -> ObjectUpdateDiagnostics.TextureContext?)? = null
    ) {
        target.textureEntrySize = bytes.size
        val parsed = TextureEntry.parse(bytes)
        // Temporary (fase 2.13a, revision): keep the real blobs so the report can
        // classify the odd ones against the bytes the region actually sent.
        ObjectUpdateDiagnostics.noteTextureEntry(
            target.localId, source, bytes, parsed, framingSuspect, context, received
        )
        if (parsed == null) {
            if (overwriteTextureId) {
                target.textureId = ""
            }
            return
        }
        target.textureEntry = parsed
        val first = parsed.face(0)
        val resolved = if (first.textureId == LLUUIDUtil.ZERO) "" else first.textureId
        if (overwriteTextureId || resolved.isNotEmpty()) {
            target.textureId = resolved
        }
        // The tint and the fullbright flag are decoded and kept in [parsed], but
        // they are deliberately *not* copied into `textureColor`/`fullBright`
        // yet: those two feed the material, and this sub-phase must not change
        // what is on screen. Wiring them in belongs to the material sub-phase.
    }

    /**
     * Diagnostic-only (fase 2.13a, revision): the window of bytes around a
     * `TextureEntry` inside its own message, plus the facts that say whether the
     * section's declared length is what the region really sent.
     *
     * The **declared length, the length word's position and the bytes left after
     * the blob** come from [Block.span], which the codec records as it reads — they
     * are properties of the received bytes, not of a local re-encode. The hex
     * window around the blob is still built by re-encoding the decoded message
     * (read-only: it builds a byte array from what was already decoded), because
     * that is where the neighbouring bytes can be looked at; when the re-encode
     * cannot place the blob the neighbouring field of the same block is captured
     * instead, and the span numbers are reported either way.
     *
     * For `ImprovedTerseObjectUpdate` the `TextureEntry` is the message's last
     * field, so `fin_coincide=si` (the blob's end is the body's end) is the proof
     * that the short length is what the region declared, while `fin_coincide=no`
     * (bytes left over) means the length word — or the `Data` length before it —
     * is not where this decoder thinks it is.
     */
    private fun textureContext(
        message: SLMessage,
        block: Block,
        blob: ByteArray,
        neighbour: String
    ): ObjectUpdateDiagnostics.TextureContext? {
        if (blob.isEmpty()) {
            return null
        }
        val span = block.span("TextureEntry")
        val body = message.bodyLength
        val declared = span?.declaredLength ?: blob.size
        val word = span?.lengthWordOffset ?: -1
        val dataOffset = span?.dataOffset ?: -1
        val trailing = if (span != null) body - (dataOffset + declared) else -1
        val endsBody = trailing == 0
        try {
            val encoded = SLMessageCodec.encode(message)
            val at = indexOf(encoded, blob)
            if (at >= 0) {
                return ObjectUpdateDiagnostics.window(
                    encoded, at, blob.size,
                    "mensaje " + message.name() + " recomprimido",
                    declaredLength = declared,
                    declaredLengthOffset = word,
                    bodySize = body,
                    endsBuffer = endsBody,
                    bodyComplete = message.bodyComplete,
                    reencodedLength = encoded.size,
                    bodyOffset = message.bodyOffset
                )
            }
        } catch (e: Exception) {
            // fall through to the neighbouring-field capture
        }
        for ((_, list) in message.blocks) {
            for (carried in list) {
                val candidate = carried.bytes("TextureEntry")
                if (candidate.isEmpty() || !candidate.contentEquals(blob)) {
                    continue
                }
                val side = carried.bytes(neighbour)
                return ObjectUpdateDiagnostics.TextureContext(
                    bufferSize = body,
                    offset = dataOffset,
                    before = ObjectUpdateDiagnostics.hexPreview(side, 64),
                    after = "-",
                    note = "mensaje " + message.name() + ": recompresion no disponible, campo vecino " +
                        neighbour + " (" + side.size + " bytes)",
                    declaredLength = declared,
                    declaredLengthOffset = word,
                    trailing = trailing,
                    endsBuffer = endsBody,
                    bodyComplete = message.bodyComplete
                )
            }
        }
        return null
    }

    /** First occurrence of [needle] in [haystack], or -1. */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) {
            return -1
        }
        val head = needle[0]
        for (start in 0..(haystack.size - needle.size)) {
            if (haystack[start] != head) {
                continue
            }
            var match = true
            for (i in 1 until needle.size) {
                if (haystack[start + i] != needle[i]) {
                    match = false
                    break
                }
            }
            if (match) {
                return start
            }
        }
        return -1
    }

    /** What the report shows as "the shape before/after this merge". */
    private fun describeShape(sceneObject: SceneObject): String =
        sceneObject.shapeText + " [" + sceneObject.shapeSource.label + "]"

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            return ""
        }
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000').trim()
    }

    /**
     * `NameValue` blobs are lines of `Name Type Class SendTo Value` (the value
     * may contain spaces). Avatars carry `FirstName`, `LastName` and `Title`.
     */
    private fun nameOf(sceneObject: SceneObject, bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            return sceneObject.name
        }
        return nameOf(sceneObject, String(bytes, Charsets.UTF_8).trimEnd('\u0000'))
    }

    /** The same parser, for callers that already have the decoded string. */
    private fun nameOf(sceneObject: SceneObject, text: String): String {
        if (text.isEmpty()) {
            return sceneObject.name
        }
        val values = HashMap<String, String>()
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                continue
            }
            val tokens = trimmed.split(Regex("[ \t]+"), limit = 5)
            when {
                tokens.size >= 5 -> values[tokens[0]] = tokens[4]
                tokens.size == 2 -> values[tokens[0]] = tokens[1]
            }
        }
        val first = values["FirstName"].orEmpty()
        val last = values["LastName"].orEmpty()
        val full = (first + " " + last).trim()
        if (full.isNotEmpty()) {
            return full
        }
        val title = values["Title"].orEmpty()
        if (title.isNotEmpty()) {
            return title
        }
        val name = values["Name"].orEmpty()
        return if (name.isNotEmpty()) name else sceneObject.name
    }

    private fun stackTrace(error: Throwable): String {
        val writer = java.io.StringWriter(256)
        error.printStackTrace(java.io.PrintWriter(writer))
        return writer.toString().trim().replace("\n", "\n  ")
    }

    // ------------------------------------------------------ message plumbing

    /** Local ids the simulator asked us to re-request (we have no object cache). */
    fun cachedIds(message: SLMessage): List<Int> {
        val blocks = message.blocks["ObjectData"] ?: return emptyList()
        val out = ArrayList<Int>(blocks.size)
        for (block in blocks) {
            val localId = block.u32("ID").toInt()
            if (localId != 0) {
                out.add(localId)
            }
        }
        return out
    }

    fun removedIds(message: SLMessage): List<Int> {
        val blocks = message.blocks["ObjectData"] ?: return emptyList()
        val out = ArrayList<Int>(blocks.size)
        for (block in blocks) {
            val localId = block.u32("ID").toInt()
            if (localId != 0) {
                out.add(localId)
            }
        }
        return out
    }

    /**
     * The offset walk of one block: `campo [inicio..fin] = valor`, one entry per
     * field, so a failure names the field instead of an array index.
     */
    private class FieldTrace(private val blobLength: Int) {

        private val builder = StringBuilder(512)

        private var field: String = "-"

        private var start: Int = 0

        fun begin(name: String, reader: ByteReader) {
            field = name
            start = reader.position
        }

        fun end(reader: ByteReader, value: Any?) {
            builder.append(field)
            builder.append(" [").append(start).append("..").append(reader.position - 1).append(']')
            builder.append(" = ").append(describe(value))
            builder.append('\n')
        }

        /** The walk as `campo [a..b] = valor` lines (one per line in the report). */
        fun text(): String = if (builder.isEmpty()) {
            "sin campos decodificados (bloque de " + blobLength + " bytes)"
        } else {
            builder.toString().trimEnd('\n').replace("\n", "\n    ")
        }

        private fun describe(value: Any?): String = when (value) {
            null -> "null"
            is ByteArray -> value.size.toString() + " bytes"
            is Float -> String.format(java.util.Locale.US, "%.3f", value)
            is Vector3 -> String.format(java.util.Locale.US, "%.2f,%.2f,%.2f", value.x, value.y, value.z)
            is Quaternion -> String.format(java.util.Locale.US, "%.2f,%.2f,%.2f,%.2f", value.x, value.y, value.z, value.w)
            is Long -> value.toString() + " (0x" + java.lang.Long.toHexString(value) + ")"
            else -> value.toString()
        }
    }

    private inline fun <T> traceField(trace: FieldTrace, name: String, reader: ByteReader, read: () -> T): T {
        trace.begin(name, reader)
        val value = read()
        trace.end(reader, value)
        return value
    }
}

/**
 * Quantised rotation: four 16-bit values mapped onto [-1, 1], which is how the
 * terse update squeezes a quaternion into eight bytes.
 */
fun ByteReader.quantisedQuaternion(): com.lumiyaviewer.lumiya.slproto.base.Quaternion {
    val x = quantised(-1f, 1f)
    val y = quantised(-1f, 1f)
    val z = quantised(-1f, 1f)
    val w = quantised(-1f, 1f)
    val length = kotlin.math.sqrt(x * x + y * y + z * z + w * w)
    if (length < 1e-6f) {
        return com.lumiyaviewer.lumiya.slproto.base.Quaternion.IDENTITY
    }
    return com.lumiyaviewer.lumiya.slproto.base.Quaternion(x / length, y / length, z / length, w / length)
}
