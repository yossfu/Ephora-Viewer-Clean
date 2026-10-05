package com.lumiyaviewer.lumiya.slproto.world

import com.lumiyaviewer.lumiya.slproto.base.LLUUIDUtil
import com.lumiyaviewer.lumiya.slproto.base.Quaternion
import com.lumiyaviewer.lumiya.slproto.base.Vector3

/** The shape family a prim's path/profile parameters put it into. */
enum class PrimShape { BOX, CYLINDER, PRISM, SPHERE, TORUS, TUBE, RING, TREE, GRASS, AVATAR, UNKNOWN }

/**
 * Where the geometric definition an object is currently carrying came from.
 *
 * The distinction is the whole point of the incremental object model: an update
 * that only carries movement (`ImprovedTerseObjectUpdate`) or only placement (a
 * truncated compressed block) must **never** be read as "this prim has no
 * shape". It may only mean "this update did not say". [PERSISTED] is the state
 * that proves an earlier definition survived a later partial update.
 */
enum class ShapeSource {
    /** Nothing usable arrived yet: the object is known, its shape is not. */
    MISSING,

    /** A full `ObjectUpdate` carried the path/profile block. */
    RECEIVED_FULL,

    /** An `ObjectUpdateCompressed` carried the path/profile block. */
    RECEIVED_COMPRESSED,

    /** An earlier update carried it, and later partial updates kept it. */
    PERSISTED,

    /** Set locally (the built-in test rows), not by the region. */
    LOCAL,

    /** Present, but no update was recorded as having carried it. */
    UNRECORDED;

    /** The text the report shows for this provenance. */
    val label: String
        get() = when (this) {
            MISSING -> "AUSENTE"
            RECEIVED_FULL -> "recibida (ObjectUpdate)"
            RECEIVED_COMPRESSED -> "recibida (ObjectUpdateCompressed)"
            PERSISTED -> "persistida (un update parcial no la borro)"
            LOCAL -> "local (escena de prueba)"
            UNRECORDED -> "presente (origen no registrado)"
        }
}

/** The update message an object was last touched by. */
enum class UpdateSource {
    NONE,
    FULL,
    COMPRESSED,
    TERSE,
    LOCAL;

    val label: String
        get() = when (this) {
            NONE -> "ninguno"
            FULL -> "ObjectUpdate"
            COMPRESSED -> "ObjectUpdateCompressed"
            TERSE -> "ImprovedTerseObjectUpdate"
            LOCAL -> "local"
        }
}

/**
 * One object of the region as the simulator describes it: prims, trees and
 * avatars share this record. Everything the renderer needs (placement, shape,
 * tint and label) lives here, filled in from `ObjectUpdate`,
 * `ObjectUpdateCompressed` and `ImprovedTerseObjectUpdate`.
 *
 * ## Accumulative state, keyed by local id
 *
 * This record *is* the per-object state the incremental model needs, and
 * `WorldModel` keeps exactly one of them per `LocalID` (see
 * `WorldModel.getOrCreate`). Every update merges into the existing record: an
 * update overwrites the fields it actually carries and leaves the rest alone.
 * In particular the geometry (`pathCurve` … `profileHollow`) is only ever
 * written by an update that carried a path/profile block, and
 * [noteUpdateWithoutShape] records that a partial update did not touch it.
 *
 * The constructor's path/profile defaults are *placeholder values*, not a
 * shape: they are what SL uses for a default cylinder, which is precisely why
 * they must never be classified. [paramsKnown] (and [hasCompleteShape]) is what
 * says whether a definition actually arrived, and [shapeText] reports
 * `UNKNOWN/MISSING_SHAPE` when it did not.
 */
class SceneObject(
    val localId: Int,
    var uuid: String = LLUUIDUtil.ZERO,
    var pcode: Int = PCODE_PRIM,
    var position: Vector3 = Vector3.ZERO,
    var rotation: Quaternion = Quaternion.IDENTITY,
    var scale: Vector3 = Vector3(0.5f, 0.5f, 0.5f),
    var pathCurve: Int = 16,
    var profileCurve: Int = 0,
    var pathBegin: Int = 0,
    var pathEnd: Int = 0,
    var pathScaleX: Int = 100,
    var pathScaleY: Int = 100,
    var pathShearX: Int = 0,
    var pathShearY: Int = 0,
    var pathTwist: Int = 0,
    var pathTwistBegin: Int = 0,
    var pathRadiusOffset: Int = 0,
    var pathTaperX: Int = 0,
    var pathTaperY: Int = 0,
    var pathRevolutions: Int = 0,
    var pathSkew: Int = 0,
    var profileBegin: Int = 0,
    var profileEnd: Int = 0,
    var profileHollow: Int = 0,
    var material: Int = 3,
    var clickAction: Int = 0,
    var attachmentPoint: Int = 0,
    var parentId: Int = 0,
    var textureId: String = LLUUIDUtil.ZERO,
    var textureColor: Int = 0xFFFFFFFF.toInt(),
    var fullBright: Boolean = false,
    var ownerId: String = LLUUIDUtil.ZERO,
    var name: String = "",
    var text: String = "",
    var updateFlags: Long = 0L,
    /**
     * Size of the `TextureEntry` blob exactly as received. The blob is not kept
     * (only its first face is decoded so far), but its length is real evidence:
     * an object that arrived with no TextureEntry at all is a different case
     * from one whose texture we simply have not turned into pixels yet.
     */
    var textureEntrySize: Int = 0,
    /**
     * The `TextureEntry` blob decoded per face (fase 2.13a). Null when the region
     * sent no entry at all; a blob that was too short to hold even the default
     * texture decodes to null and leaves [textureId] as it was. `textureId`,
     * `textureColor` and `fullBright` below are the *first* face's values, kept
     * because the material path uses one face today.
     */
    var textureEntry: TextureEntry? = null,
    /**
     * True once the path/profile parameters actually arrived. A prim whose
     * parameters are unknown must never be given geometry from the defaults in
     * this class — that would be drawing an invented shape.
     */
    var paramsKnown: Boolean = false,
    /** `LL_PCODE_TREE_*` species byte, when the update carried one. */
    var treeSpecies: Int = 0,
    /** Bytes of the `ExtraParams` section as received (1 when it was empty). */
    var extraParamsSize: Int = 0,
    /** Extra `Sculpt`/`Mesh` geometry the region sent, if any (Phase 5 loads it). */
    var sculptType: Int = 0,
    var sculptId: String = LLUUIDUtil.ZERO,
    /** Raw `ExtraParamType` of the sculpt entry (0x30 sculpt, 0x60 mesh). */
    var sculptTypeCode: Int = 0,
    var isFlexible: Boolean = false,
    var isLight: Boolean = false,
    var hasNameValues: Boolean = false,
    var lastUpdateMillis: Long = System.currentTimeMillis()
) {

    /** True once the simulator has told us where this object actually is. */
    var positionKnown: Boolean = false

    /** True for objects we only know by local id (cached updates). */
    var waitingForFullUpdate: Boolean = false

    /**
     * Which update carried the geometric definition now stored, recorded by
     * [noteShapeReceived]. Only ever read through [shapeSource], which folds in
     * [paramsKnown], so a definition set without going through that method can
     * never be reported as "received".
     */
    private var recordedShapeSource: ShapeSource = ShapeSource.MISSING

    /** The update that last touched this object, whatever it carried. */
    var lastUpdateSource: UpdateSource = UpdateSource.NONE
        private set

    /** Updates this object has seen, and how many of them were terse. */
    var updatesSeen: Int = 0
        private set
    var terseUpdatesSeen: Int = 0
        private set

    /**
     * Records that an update carried the prim's path/profile definition. From
     * here on the object [hasCompleteShape], and a later partial update cannot
     * take that away — see [noteUpdateWithoutShape].
     */
    fun noteShapeReceived(source: UpdateSource) {
        paramsKnown = true
        recordedShapeSource = when (source) {
            UpdateSource.FULL -> ShapeSource.RECEIVED_FULL
            UpdateSource.COMPRESSED -> ShapeSource.RECEIVED_COMPRESSED
            UpdateSource.LOCAL -> ShapeSource.LOCAL
            else -> ShapeSource.RECEIVED_FULL
        }
        lastUpdateSource = source
        updatesSeen += 1
    }

    /**
     * Records an update that did **not** carry a geometric definition (a terse
     * movement update, or a compressed block whose parameter section was
     * truncated). Nothing here clears, resets or substitutes a shape: if a
     * definition was already stored it stays, and it is marked as persisted.
     */
    fun noteUpdateWithoutShape(source: UpdateSource) {
        lastUpdateSource = source
        updatesSeen += 1
        if (source == UpdateSource.TERSE) {
            terseUpdatesSeen += 1
        }
        if (paramsKnown) {
            recordedShapeSource = ShapeSource.PERSISTED
        }
    }

    /**
     * True when this object's geometry is fully described: a prim needs its
     * path/profile block, while trees, grass and avatars are drawn from their
     * class alone.
     */
    val hasCompleteShape: Boolean
        get() = when {
            isPrim -> paramsKnown
            isAvatar || isTree || isGrass -> true
            else -> false
        }

    /** Where the definition now stored came from; see [ShapeSource]. */
    val shapeSource: ShapeSource
        get() = when {
            !paramsKnown -> ShapeSource.MISSING
            recordedShapeSource == ShapeSource.MISSING -> ShapeSource.UNRECORDED
            else -> recordedShapeSource
        }

    /**
     * The invariant that used to be violated: a prim with no received geometry
     * must never be *labelled* with the default path/profile values in this
     * class (`pathCurve = 16`, `profileCurve = 0`, which classify as a
     * cylinder). It is reported as `UNKNOWN/MISSING_SHAPE` instead.
     */
    val shapeIsFallback: Boolean
        get() = isPrim && !paramsKnown && shape != PrimShape.UNKNOWN

    /**
     * Bumped by [WorldModel.put] on every update that reaches the model, so a
     * consumer can tell "changed" from "unchanged" without comparing every
     * field (and without being fooled by two updates in the same millisecond).
     */
    var revision: Int = 0

    val isAvatar: Boolean
        get() = pcode == PCODE_AVATAR

    /** Deprecated single-billboard tree (`LL_PCODE_LEGACY_TREE`, 255). */
    val isLegacyTree: Boolean
        get() = pcode == PCODE_LEGACY_TREE

    /** The tree generations the simulator still sends (255 and 111). */
    val isTree: Boolean
        get() = pcode == PCODE_LEGACY_TREE || pcode == PCODE_NEW_TREE

    /** Grass, a pcode of its own (95) — never a prim and never a tree. */
    val isGrass: Boolean
        get() = pcode == PCODE_GRASS

    /** The ordinary object class: `LL_PCODE_VOLUME`. */
    val isPrim: Boolean
        get() = pcode == PCODE_PRIM

    /** Anything whose geometry we do not generate at all yet. */
    val isOtherPcode: Boolean
        get() = !isAvatar && !isTree && !isGrass && !isPrim

    /** A worn attachment is parented to an avatar, not to the region. */
    val isAttachment: Boolean
        get() = attachmentPoint > 0 && parentId != 0

    val shape: PrimShape
        get() {
            if (isAvatar) {
                return PrimShape.AVATAR
            }
            if (isGrass) {
                return PrimShape.GRASS
            }
            if (isTree) {
                return PrimShape.TREE
            }
            if (!isPrim) {
                return PrimShape.UNKNOWN
            }
            // No received parameters means no shape. The defaults in this class
            // (pathCurve = 16, profileCurve = 0) classify as a *cylinder*, so
            // consulting the classifier here is exactly how every object that
            // had only ever sent a terse update ended up labelled "(cylinder)"
            // in the UI. The definition must have arrived; otherwise the honest
            // answer is UNKNOWN.
            if (!paramsKnown) {
                return PrimShape.UNKNOWN
            }
            return PrimShapeClassifier.classify(pathCurve, profileCurve, profileHollow)
        }

    /**
     * The shape as text for the UI and the logs: a real shape name when the
     * definition arrived, `UNKNOWN/MISSING_SHAPE` when it did not. Never a
     * silent default.
     */
    val shapeText: String
        get() = when {
            !hasCompleteShape -> "UNKNOWN/MISSING_SHAPE"
            else -> shape.name
        }

    /**
     * Stable pseudo-colour derived from the texture UUID, used until the real
     * JPEG2000 texture pipeline exists. Identical objects always get the same
     * tint, and the palette is tuned to look like SL surface materials.
     */
    val fallbackColor: FloatArray
        get() = PrimShapeClassifier.fallbackColor(textureId, fullBright)

    companion object {
        /**
         * The `PCode` byte is the object's *class*, not its shape, and the wire
         * values are Linden Lab's (`indra/llmath/llvolume.h`, cross-checked
         * against LibreMetaverse's `PCode` enum):
         *
         * * `LL_PCODE_VOLUME` = 9 — **every ordinary prim**, whatever its path
         *   and profile parameters say. 9 is *not* a tree value.
         * * `LL_PCODE_LEGACY_AVATAR` = 0x20 | 0x0F = 47.
         * * `LL_PCODE_LEGACY_GRASS` = 0x50 | 0x0F = 95.
         * * `LL_PCODE_TREE_NEW` = 0x60 | 0x0F = 111.
         * * `LL_PCODE_LEGACY_PART_SYS` = 0x80 | 0x0F = 143.
         * * `LL_PCODE_LEGACY_ROCK` = 0x90 | 0x0F = 159.
         * * `LL_PCODE_LEGACY_TREE` = 0xF0 | 0x0F = 255.
         */
        const val PCODE_PRIM = 9

        const val PCODE_AVATAR = 47
        const val PCODE_GRASS = 95
        const val PCODE_NEW_TREE = 111
        const val PCODE_PARTICLE_SYSTEM = 143
        const val PCODE_LEGACY_ROCK = 159

        /** The deprecated single-billboard tree. */
        const val PCODE_LEGACY_TREE = 255

        /** Older code (and some grids) call the legacy tree simply "the tree". */
        const val PCODE_TREE = PCODE_LEGACY_TREE
    }
}

/**
 * Names the shape a (pathCurve, profileCurve) pair produces.
 *
 * This is the same classification the viewer uses for its UI labels
 * (`LLVolume::isSphere()`, `isTorus()` in indra/llmath/llvolume.cpp). The mesh
 * itself is always built from the raw parameters in [SceneObject] — never from
 * this name — so a mislabelled exotic prim still renders with the correct
 * geometry.
 */
object PrimShapeClassifier {

    /** `LL_PCODE_PROFILE_*`, low nibble of profileCurve. */
    const val PROFILE_CIRCLE = 0x00
    const val PROFILE_SQUARE = 0x01
    const val PROFILE_ISOTRI = 0x02
    const val PROFILE_EQUALTRI = 0x03
    const val PROFILE_RIGHTTRI = 0x04
    const val PROFILE_CIRCLE_HALF = 0x05

    /** `LL_PCODE_PATH_*`, high nibble of pathCurve. */
    const val PATH_LINE = 0x10
    const val PATH_CIRCLE = 0x20
    const val PATH_CIRCLE2 = 0x30
    const val PATH_FLEXIBLE = 0x80

    fun classify(pathCurve: Int, profileCurve: Int): PrimShape = classify(pathCurve, profileCurve, 0)

    fun classify(pathCurve: Int, profileCurve: Int, profileHollow: Int): PrimShape {
        val path = pathCurve and 0xF0
        val profile = profileCurve and 0x0F
        val hollow = profileHollow > 0
        return when (path) {
            PATH_LINE -> when (profile) {
                PROFILE_CIRCLE -> if (hollow) PrimShape.TUBE else PrimShape.CYLINDER
                PROFILE_CIRCLE_HALF -> if (hollow) PrimShape.TUBE else PrimShape.CYLINDER
                PROFILE_SQUARE -> PrimShape.BOX
                PROFILE_ISOTRI, PROFILE_EQUALTRI, PROFILE_RIGHTTRI -> PrimShape.PRISM
                else -> PrimShape.BOX
            }
            // A circular path with a half profile is a sphere; anything else
            // swept around a circle closes back on itself.
            PATH_CIRCLE, PATH_CIRCLE2 -> when (profile) {
                PROFILE_CIRCLE_HALF -> PrimShape.SPHERE
                PROFILE_CIRCLE -> if (hollow) PrimShape.RING else PrimShape.TORUS
                PROFILE_SQUARE -> PrimShape.TUBE
                else -> PrimShape.RING
            }
            // A flexible path is a sphere/box depending on the profile.
            PATH_FLEXIBLE -> when (profile) {
                PROFILE_CIRCLE, PROFILE_CIRCLE_HALF -> PrimShape.SPHERE
                PROFILE_SQUARE -> PrimShape.BOX
                else -> PrimShape.PRISM
            }
            else -> when (profile) {
                PROFILE_CIRCLE, PROFILE_CIRCLE_HALF -> PrimShape.CYLINDER
                PROFILE_SQUARE -> PrimShape.BOX
                else -> PrimShape.PRISM
            }
        }
    }

    /** Deterministic, stable colour for a texture UUID. */
    fun fallbackColor(textureId: String, fullBright: Boolean): FloatArray {
        var hash = 2166136261u
        val text = if (textureId.isEmpty()) "00000000-0000-0000-0000-000000000000" else textureId
        for (character in text) {
            hash = (hash xor character.code.toUInt()) * 16777619u
        }
        if (hash == 0u) {
            hash = 0x9E3779B9u
        }
        val hue = (hash and 0xFFFFu).toFloat() / 65535f
        val saturation = 0.25f + ((hash shr 16) and 0xFFu).toFloat() / 255f * 0.30f
        var value = 0.55f + ((hash shr 24) and 0x7Fu).toFloat() / 127f * 0.30f
        if (fullBright) {
            value = (value + 0.25f).coerceAtMost(1f)
        }
        return hsv(hue, saturation, value)
    }

    private fun hsv(hue: Float, saturation: Float, value: Float): FloatArray {
        val h = (hue - kotlin.math.floor(hue)) * 6f
        val sector = kotlin.math.floor(h).toInt()
        val fraction = h - sector
        val p = value * (1f - saturation)
        val q = value * (1f - saturation * fraction)
        val t = value * (1f - saturation * (1f - fraction))
        return when (sector % 6) {
            0 -> floatArrayOf(value, t, p)
            1 -> floatArrayOf(q, value, p)
            2 -> floatArrayOf(p, value, t)
            3 -> floatArrayOf(p, q, value)
            4 -> floatArrayOf(t, p, value)
            else -> floatArrayOf(value, p, q)
        }
    }
}
