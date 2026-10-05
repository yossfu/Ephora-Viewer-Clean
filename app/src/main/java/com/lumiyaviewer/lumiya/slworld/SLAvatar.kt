package com.lumiyaviewer.lumiya.slworld

/**
 * What the region has actually told us about one avatar.
 *
 * Avatars are **not** drawn in this phase, and they are not faked either: no
 * capsule, no billboard, no substitute mesh. The region's own `ObjectUpdate` for
 * an avatar carries its local ID, its UUID, its name and where it is, and that
 * is exactly what this class holds — nothing more. Unknown fields stay at their
 * "not sent" value (see [positionKnown]) instead of being guessed at.
 *
 * Phase 8 turns an [SLAvatar] into something visible, and the work happens in
 * this module. It needs the pieces Second Life actually sends for a body, and
 * each one is a separate job:
 *
 * * `ObjectUpdate` / `ObjectUpdateCompressed` with `PCode` 47 — identity and
 *   placement (what this class already holds).
 * * `AvatarAppearance` (`VisualParam` values, an `ObjectData.TextureEntry`, and
 *   the `AppearanceData.AppearanceVersion` / `CofVersion` / `Flags` block) —
 *   visual parameters and the wearable layers, decoded per `LLVOAvatar` and
 *   `LLVisualParam` rather than invented.
 * * The avatar skeleton — its bone names, their parents and the default
 *   positions come from `avatar_skeleton.xml` in the official viewer, not from
 *   this file.
 * * `Attachments` and `ObjectUpdate` with a non-zero `AttachmentPoint` — worn
 *   items, which only become placeable once the avatar they hang off has a
 *   transform.
 *
 * `SLAnimation` (Phase 9) handles [AgentAnimation]-style motion; this module is
 * about the body.
 */
class SLAvatar(
    val localId: Int,
    val uuid: String,
    val name: String,
    /** Position, rotation (xyzw) and scale, exactly as the simulator sent them. */
    val transform: SLTransform,
    /** False when the region has only told us the avatar exists. */
    val positionKnown: Boolean,
    val revision: Int
) {

    override fun toString(): String =
        "SLAvatar(#" + localId + " " + (if (name.isEmpty()) "sin nombre" else name) + ")"

    companion object {

        /**
         * Interprets an avatar-shaped snapshot. Returns null for anything that
         * is not an avatar, so callers can use it as a filter.
         */
        fun from(object_: SLObject): SLAvatar? {
            if (object_.kind != SLObjectKind.AVATAR) {
                return null
            }
            return SLAvatar(
                localId = object_.localId,
                uuid = object_.uuid,
                name = object_.name,
                transform = object_.transform,
                positionKnown = object_.positionKnown,
                revision = object_.revision
            )
        }
    }
}
