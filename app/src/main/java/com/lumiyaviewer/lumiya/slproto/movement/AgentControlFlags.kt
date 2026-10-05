package com.lumiyaviewer.lumiya.slproto.movement

/**
 * Movement controls understood by the simulator's `AgentUpdate` message.
 *
 * It lives here, in the movement package, and not next to the session, because
 * the audit of phase 2.13a's movement investigation has to be able to assert the
 * action-to-bit mapping without dragging the whole session (and with it the
 * network, the world and the renderer) into the check harness.
 */
enum class MoveAction { FORWARD, BACKWARD, STRAFE_LEFT, STRAFE_RIGHT, TURN_LEFT, TURN_RIGHT, UP, DOWN, RUN, FLY }

/**
 * The viewer's control bits, spelled out as `EControlFlags` in the official
 * viewer and re-declared by every working client. The values are the ones on the
 * wire, and they are asserted against the reference table by the `moveaudit`
 * check — a bit moved by accident here is a control that silently stops working.
 *
 * Sources: `indra/llmessage/llcontrolflags.h` (official viewer),
 * `OpenMetaverse/AgentManagerMovement.cs` (LibreMetaverse, the client the
 * movement audit compares against).
 */
object AgentControlFlags {

    /** Walk/run forward along the camera's at-axis. */
    const val AT_POS = 0x00000001L

    /** Walk backwards. */
    const val AT_NEG = 0x00000002L

    /** Strafe left. */
    const val LEFT_POS = 0x00000004L

    /** Strafe right. */
    const val LEFT_NEG = 0x00000008L

    /** Rise (fly up, or jump while on the ground). */
    const val UP_POS = 0x00000010L

    /** Sink. */
    const val UP_NEG = 0x00000020L

    /** Turn left in place. */
    const val YAW_POS = 0x00000100L

    /** Turn right in place. */
    const val YAW_NEG = 0x00000200L

    /** Run instead of walk, when paired with [AT_POS]. */
    const val FAST_AT = 0x00000400L

    /** Run while strafing. */
    const val FAST_LEFT = 0x00000800L

    /** Fly faster upward. */
    const val FAST_UP = 0x00001000L

    /** Fly mode. */
    const val FLY = 0x00002000L

    /** Stop, and hold position. */
    const val STOP = 0x00004000L

    /**
     * The NUDGE controls. LibreMetaverse documents them as *"Legacy, used if a key
     * was pressed for less than a certain amount of time"*, and this viewer has no
     * nudge input at all, so it never sets them — but they share the `AgentUpdate`
     * `ControlFlags` word the movement audit reads back, so they belong in the
     * table: a report that printed a mystery hex value for a flag it does not know
     * would be worse than one that names it.
     */
    const val NUDGE_AT_POS = 0x00080000L
    const val NUDGE_AT_NEG = 0x00100000L
    const val NUDGE_LEFT_POS = 0x00200000L
    const val NUDGE_LEFT_NEG = 0x00400000L
    const val NUDGE_UP_POS = 0x00800000L
    const val NUDGE_UP_NEG = 0x01000000L

    /** Every NUDGE bit at once, so "any nudge" is one `and`. */
    const val NUDGE_MASK = 0x01F80000L

    /** True when the packet carries a nudge control. */
    fun hasNudge(flags: Long): Boolean = (flags and NUDGE_MASK) != 0L

    /** No control at all. */
    const val NONE = 0L

    /** The bit one movement control sets. */
    fun bitFor(action: MoveAction): Long = when (action) {
        MoveAction.FORWARD -> AT_POS
        MoveAction.BACKWARD -> AT_NEG
        MoveAction.STRAFE_LEFT -> LEFT_POS
        MoveAction.STRAFE_RIGHT -> LEFT_NEG
        MoveAction.TURN_LEFT -> YAW_POS
        MoveAction.TURN_RIGHT -> YAW_NEG
        MoveAction.UP -> UP_POS
        MoveAction.DOWN -> UP_NEG
        MoveAction.RUN -> FAST_AT
        MoveAction.FLY -> FLY
    }

    /**
     * El impulso discreto (NUDGE) equivalente a una pulsación corta de esta
     * acción, o 0 cuando la acción no tiene impulso (giros, correr, volar).
     * Bits legacy del protocolo que el viewer funcional emite para toques
     * cortos; viajan en el mismo campo ControlFlags del AgentUpdate.
     */
    fun nudgeBitFor(action: MoveAction): Long = when (action) {
        MoveAction.FORWARD -> NUDGE_AT_POS
        MoveAction.BACKWARD -> NUDGE_AT_NEG
        MoveAction.STRAFE_LEFT -> NUDGE_LEFT_POS
        MoveAction.STRAFE_RIGHT -> NUDGE_LEFT_NEG
        MoveAction.UP -> NUDGE_UP_POS
        MoveAction.DOWN -> NUDGE_UP_NEG
        else -> 0L
    }

    /** The official bit mask of one control, in the order the table lists them. */
    val TABLE: List<Pair<String, Long>> = listOf(
        "AT_POS" to AT_POS,
        "AT_NEG" to AT_NEG,
        "LEFT_POS" to LEFT_POS,
        "LEFT_NEG" to LEFT_NEG,
        "UP_POS" to UP_POS,
        "UP_NEG" to UP_NEG,
        "YAW_POS" to YAW_POS,
        "YAW_NEG" to YAW_NEG,
        "FAST_AT" to FAST_AT,
        "FAST_LEFT" to FAST_LEFT,
        "FAST_UP" to FAST_UP,
        "FLY" to FLY,
        "STOP" to STOP,
        "NUDGE_AT_POS" to NUDGE_AT_POS,
        "NUDGE_AT_NEG" to NUDGE_AT_NEG,
        "NUDGE_LEFT_POS" to NUDGE_LEFT_POS,
        "NUDGE_LEFT_NEG" to NUDGE_LEFT_NEG,
        "NUDGE_UP_POS" to NUDGE_UP_POS,
        "NUDGE_UP_NEG" to NUDGE_UP_NEG
    )

    /** `0x00000011 AT_POS|UP_POS`, or `0x00000000 (ninguno)`. */
    fun describe(flags: Long): String {
        if (flags == 0L) {
            return "0x00000000 (ninguno)"
        }
        val names = StringBuilder()
        for ((name, bit) in TABLE) {
            if ((flags and bit) != 0L) {
                if (names.isNotEmpty()) {
                    names.append('|')
                }
                names.append(name)
            }
        }
        val known = TABLE.fold(0L) { acc, entry -> acc or entry.second }
        val unknown = flags and known.inv()
        if (unknown != 0L) {
            if (names.isNotEmpty()) {
                names.append('|')
            }
            names.append("desconocido=0x").append(java.lang.Long.toHexString(unknown))
        }
        return "0x" + String.format(java.util.Locale.US, "%08x", flags) + " " + names
    }

    /** The horizontal direction the avatar faces at a heading, in SL's Z-up frame. */
    fun headingAtAxis(headingRadians: Float): FloatArray {
        return floatArrayOf(
            kotlin.math.cos(headingRadians),
            kotlin.math.sin(headingRadians),
            0f
        )
    }
}
