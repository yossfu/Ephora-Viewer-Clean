package com.lumiyaviewer.lumiya.slworld

import com.lumiyaviewer.lumiya.slproto.base.Quaternion
import com.lumiyaviewer.lumiya.slproto.base.Vector3
import com.lumiyaviewer.lumiya.slproto.world.SceneObject
import com.lumiyaviewer.lumiya.slproto.world.ShapeSource
import com.lumiyaviewer.lumiya.slproto.world.TextureEntry
import com.lumiyaviewer.lumiya.slproto.world.UpdateSource

/** What kind of thing the simulator says this object is (its `PCode`). */
enum class SLObjectKind {
    PRIM,
    TREE,
    GRASS,
    AVATAR,
    /** Any other pcode: we know where it is, but not yet what to draw. */
    UNKNOWN;

    companion object {
        fun of(sceneObject: SceneObject): SLObjectKind = when {
            sceneObject.isAvatar -> AVATAR
            sceneObject.isGrass -> GRASS
            sceneObject.isTree -> TREE
            sceneObject.isPrim -> PRIM
            else -> UNKNOWN
        }
    }
}

/**
 * A placement in the region, in Second Life's own frame: Z is up, X is east and
 * Y is north. The renderer layer converts this into its own transform type — the
 * SL side never touches a renderer type, and the renderer never sees an SL type.
 */
class SLTransform(
    val translation: FloatArray,
    /** Quaternion, xyzw. */
    val rotation: FloatArray,
    val scale: FloatArray
) {
    companion object {
        fun of(position: Vector3, rotation: Quaternion, scale: Vector3): SLTransform = SLTransform(
            floatArrayOf(position.x, position.y, position.z),
            floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w),
            floatArrayOf(scale.x, scale.y, scale.z)
        )
    }
}

/**
 * An object of the region, interpreted: the immutable snapshot of what Second
 * Life told us about one local ID.
 *
 * Everything here is optional-shaped on purpose — an object update that only
 * carries movement (`ImprovedTerseObjectUpdate`) produces an object with no
 * prim parameters, and an avatar produces one with no geometry at all. Nothing
 * is filled in with a plausible-looking guess.
 */
class SLObject(
    val localId: Int,
    val uuid: String,
    val kind: SLObjectKind,
    /** The raw `PCode` byte, exactly as the simulator sent it. */
    val pcode: Int,
    /** Null for avatars and unknown pcodes, and for movement-only updates. */
    val prim: SLPrimitive?,
    val transform: SLTransform,
    val texture: SLTextureFace,
    /**
     * The decoded `TextureEntry` (fase 2.13a), per face. Null when the region
     * never sent one. [texture] is its face 0, which is what the material path
     * consumes today; the rest of the faces are already decoded and available for
     * the per-face material work.
     */
    val textureEntry: TextureEntry?,
    /** The prim's `Material` byte: 0 stone … 7 light. */
    val materialCode: Int,
    val attachmentPoint: Int,
    /** Non-zero when this object is a child of a linkset, or worn by an avatar. */
    val parentLocalId: Int,
    val name: String,
    val positionKnown: Boolean,
    val lastUpdateMillis: Long,
    /** The protocol revision this snapshot was built from; see [SceneObject.revision]. */
    val revision: Int,
    /** Bytes of `TextureEntry` the region sent (0 when it sent none). */
    val textureEntrySize: Int = 0,
    /** Bytes of `ExtraParams` the region sent (1 when the section was empty). */
    val extraParamsSize: Int = 0,
    /** `Sculpt`/`Mesh` extra geometry: its type byte and asset UUID, when sent. */
    val sculptType: Int = 0,
    val sculptId: String = "00000000-0000-0000-0000-000000000000",
    /**
     * True when the object's geometry is fully described: a prim needs its
     * path/profile block, trees, grass and avatars are described by their class.
     */
    val hasCompleteShape: Boolean = false,
    /** Where the stored geometric definition came from; see [ShapeSource]. */
    val shapeSource: ShapeSource = ShapeSource.MISSING,
    /** The shape as text: a real name, or `UNKNOWN/MISSING_SHAPE`. */
    val shapeText: String = "UNKNOWN/MISSING_SHAPE",
    /** The update message that last touched this object. */
    val updateSource: UpdateSource = UpdateSource.NONE,
    /**
     * How many updates this object has seen, and how many of them were terse
     * movement-only ones. They are the same counters [SceneObject] keeps, carried
     * through so the fase 2.10 focus report can say whether an object was defined
     * once and then only moved, or defined again and again.
     */
    val updatesSeen: Int = 0,
    val terseUpdatesSeen: Int = 0
) {

    val isRenderable: Boolean
        get() = positionKnown && prim != null &&
            (kind == SLObjectKind.PRIM || kind == SLObjectKind.TREE || kind == SLObjectKind.GRASS)

    val isLinkedChild: Boolean
        get() = parentLocalId != 0

    override fun toString(): String = "SLObject(#" + localId + " " + kind + " " + name + ")"

    companion object {

        /**
         * Interprets a decoded [SceneObject]. [detail] is the tessellation level
         * for generated prim geometry.
         */
        fun from(sceneObject: SceneObject, detail: Int): SLObject {
            val kind = SLObjectKind.of(sceneObject)
            // No geometry without parameters: an object whose path/profile block
            // never arrived (a placement-only compressed update, or a state that
            // only ever sent terse updates) is carried for its transform, not
            // given a shape it was never told to have. `paramsKnown` is what the
            // incremental merge maintains: it is set by the first update that
            // carries a path/profile block and is never cleared afterwards.
            val hasShape = kind == SLObjectKind.PRIM || kind == SLObjectKind.TREE || kind == SLObjectKind.GRASS
            val prim = if (sceneObject.paramsKnown && hasShape) {
                SLPrimitiveFactory.from(sceneObject, detail)
            } else {
                null
            }
            return SLObject(
                localId = sceneObject.localId,
                uuid = sceneObject.uuid,
                kind = kind,
                pcode = sceneObject.pcode,
                prim = prim,
                transform = SLTransform.of(sceneObject.position, sceneObject.rotation, sceneObject.scale),
                texture = SLTextureFace.fromSceneObject(sceneObject),
                textureEntry = sceneObject.textureEntry,
                materialCode = sceneObject.material,
                attachmentPoint = sceneObject.attachmentPoint,
                parentLocalId = sceneObject.parentId,
                name = sceneObject.name,
                positionKnown = sceneObject.positionKnown,
                lastUpdateMillis = sceneObject.lastUpdateMillis,
                revision = sceneObject.revision,
                textureEntrySize = sceneObject.textureEntrySize,
                extraParamsSize = sceneObject.extraParamsSize,
                sculptType = sceneObject.sculptType,
                sculptId = sceneObject.sculptId,
                hasCompleteShape = sceneObject.hasCompleteShape,
                shapeSource = sceneObject.shapeSource,
                shapeText = sceneObject.shapeText,
                updateSource = sceneObject.lastUpdateSource,
                updatesSeen = sceneObject.updatesSeen,
                terseUpdatesSeen = sceneObject.terseUpdatesSeen
            )
        }
    }
}
