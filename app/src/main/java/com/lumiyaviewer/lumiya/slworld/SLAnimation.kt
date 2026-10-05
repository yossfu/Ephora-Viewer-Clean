package com.lumiyaviewer.lumiya.slworld

/**
 * Avatar animation: the phase-9 seam, so the protocol side has a place to decode
 * into and the avatar module has a dependency it can name.
 *
 * Nothing here plays anything yet. What it does do is pin the wire shape, taken
 * from the message table the viewer already ships
 * (`app/src/main/assets/message_template.msg`, which is the official viewer's own
 * template), so that when Phase 9 starts there is no guessing about what the
 * simulator sends:
 *
 * ```
 * AvatarAnimation                      // simulator --> viewer
 *   Sender.ID            LLUUID
 *   AnimationList        { AnimID LLUUID, AnimSequenceID S32 }        (variable)
 *   AnimationSourceList  { ObjectID LLUUID }                          (variable)
 *   PhysicalAvatarEventList { TypeData Variable 1 }                   (variable)
 *
 * AgentAnimation                       // viewer --> simulator
 *   AgentData  { AgentID LLUUID, SessionID LLUUID }
 *   AnimationList { AnimID LLUUID, StartAnim BOOL }                   (variable)
 *   PhysicalAvatarEventList { TypeData Variable 1 }                   (variable)
 * ```
 *
 * [SLAnimationPlay] is one entry of the incoming `AnimationList`: the animation
 * asset's UUID, the simulator's sequence number for that play, and which object
 * requested it. The `AnimSequenceID` is what makes stop/start of the same
 * animation unambiguous, and how it interacts with priority and blending is
 * exactly what Phase 9 has to get right — that behaviour will be read off
 * `LLVOAvatar`/`LLAnimationObject`/the animation asset parser in the official
 * viewer, not assumed here.
 */
class SLAnimationPlay(
    /** The animation asset's UUID, as sent. */
    val animationId: String,
    /** The simulator's sequence number for this play of that animation. */
    val sequenceId: Int,
    /** The object that caused the play, when the region names one. */
    val sourceId: String
) {

    override fun toString(): String = "SLAnimationPlay(" + animationId + " #" + sequenceId + ")"
}
