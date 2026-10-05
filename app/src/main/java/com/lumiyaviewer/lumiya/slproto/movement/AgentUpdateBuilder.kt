package com.lumiyaviewer.lumiya.slproto.movement

import com.lumiyaviewer.lumiya.slproto.base.Quaternion
import com.lumiyaviewer.lumiya.slproto.base.Vector3
import com.lumiyaviewer.lumiya.slproto.messages.MessageDef
import com.lumiyaviewer.lumiya.slproto.messages.MessageTemplate
import com.lumiyaviewer.lumiya.slproto.messages.SLMessage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds the `AgentUpdate` the viewer sends, as a pure function.
 *
 * It exists for the movement audit: the same call that fills the packet on the
 * device can be asserted field by field and byte by byte in the check harness,
 * with no socket, no simulator and no session — so "the engine does not generate
 * the command" becomes a measurement instead of a suspicion. `SLConnection`
 * builds every `AgentUpdate` through [build], so there is exactly one place that
 * decides what goes on the wire.
 *
 * The **camera basis** is a parameter, not a constant. The audit needed to say
 * what the app sends *and* what a reference client sends for the same heading,
 * and phase 2.13b's movement correction is a different argument here, not an edit
 * inside the encoder.
 */
object AgentUpdateBuilder {

    /** Name of the message in `message_template.msg`. */
    const val MESSAGE_NAME = "AgentUpdate"

    /** Frequency-`High` id of `AgentUpdate` (`AgentUpdate High 4 ...`). */
    const val HIGH_ID = 4

    /** The message has exactly one block, and it is this one. */
    const val AGENT_DATA = "AgentData"

    /**
     * Bytes of the one block, from the template: 16 (AgentID) + 16 (SessionID) +
     * 16 (BodyRotation) + 16 (HeadRotation) + 1 (State) + 12 (CameraCenter) +
     * 12 + 12 + 12 (the three axes) + 4 (Far) + 4 (ControlFlags) + 1 (Flags).
     */
    const val BODY_BYTES = 122

    /**
     * The camera's three axes, in Second Life's Z-up frame (X east, Y north,
     * Z up): `at` is where the camera looks, `left` is the camera's left, `up` is
     * the camera's up. For a frame in that frame of reference, `left` is the
     * cross product of `up` and `at`.
     */
    class CameraBasis(val at: Vector3, val left: Vector3, val up: Vector3) {

        /** `at . (left x up)`: +1 for a right-handed orthonormal frame, 0 if coplanar. */
        val volume: Float
            get() {
                val crossX = left.y * up.z - left.z * up.y
                val crossY = left.z * up.x - left.x * up.z
                val crossZ = left.x * up.y - left.y * up.x
                return at.x * crossX + at.y * crossY + at.z * crossZ
            }

        /** True when the three axes are unit length and mutually perpendicular. */
        val isOrthonormal: Boolean
            get() = nearly(length(at), 1f) && nearly(length(left), 1f) && nearly(length(up), 1f) &&
                nearly(dot(at, left), 0f) && nearly(dot(at, up), 0f) && nearly(dot(left, up), 0f)

        override fun toString(): String =
            "at " + pretty(at) + "  ·  izquierda " + pretty(left) + "  ·  arriba " + pretty(up)

        /** The one-line description the report prints, with the verdict on the frame. */
        fun describe(): String {
            val builder = StringBuilder(160)
            builder.append(toString())
            if (!isOrthonormal) {
                builder.append("  (NO es una base: volumen ")
                builder.append(fmt(volume))
                builder.append(", at.arriba=")
                builder.append(fmt(dot(at, up)))
                builder.append(")")
            }
            return builder.toString()
        }

        private fun nearly(a: Float, b: Float): Boolean = abs(a - b) <= 1e-3f

        private fun dot(a: Vector3, b: Vector3): Float = a.x * b.x + a.y * b.y + a.z * b.z

        private fun length(v: Vector3): Float = sqrt(dot(v, v))
    }

    /**
     * What the app sent **before** the movement correction, named so it cannot be
     * mistaken for a basis: the at-axis points straight **down** (which is also
     * the world's up), and the three vectors are coplanar. See
     * `MOVEMENT-AUDIT.md`, finding M1.
     *
     * Nothing on the send path uses this any more — [referenceBasis] is what goes
     * on the wire. It is kept for two reasons, both about evidence: the `moveaudit`
     * check asserts that this is the degenerate triple M1 described (so the
     * finding stays reproducible), and the report prints it next to the basis
     * actually sent, which is how one device log shows the correction took effect.
     */
    fun currentBasis(): CameraBasis = CameraBasis(
        at = Vector3(0f, 0f, -1f),
        left = Vector3(0f, 1f, 0f),
        up = Vector3(0f, 0f, 1f)
    )

    /**
     * The frame a reference client sends for a heading, in SL's Z-up frame:
     * `at = (cos h, sin h, 0)`, `up = (0, 0, 1)`, `left = up x at =
     * (-sin h, cos h, 0)`. Heading 0 is the identity body rotation, which faces
     * east (+X) — so `at` is (1, 0, 0) and `left` is (0, 1, 0) (north).
     *
     * Reference: `OpenMetaverse/CoordinateFrame.cs` (`LookDirection(Vector3 at,
     * Vector3 upDirection)`: `left = up x at`, and X is "Forward/At in grid
     * terms") driven from `AgentManagerMovement.SendUpdate`, which is what a
     * working client puts in these three fields.
     */
    fun referenceBasis(headingRadians: Float): CameraBasis = CameraBasis(
        at = Vector3(cos(headingRadians), sin(headingRadians), 0f),
        left = Vector3(-sin(headingRadians), cos(headingRadians), 0f),
        up = Vector3(0f, 0f, 1f)
    )

    /** The body rotation for a heading: a rotation about Z, `(0, 0, sin, cos)`. */
    fun bodyRotation(bodyYawRadians: Float): Quaternion = Quaternion.fromYaw(bodyYawRadians)

    /**
     * The heading a body rotation encodes, back out of the quaternion.
     *
     * Used on the *serialized* value: the report says which yaw the packet
     * actually carries, instead of repeating the number that was passed in, so a
     * mismatch between "what we meant to send" and "what is in the packet"
     * cannot hide.
     */
    fun headingOf(rotation: Quaternion): Float {
        val angle = kotlin.math.atan2(rotation.z, rotation.w)
        val heading = 2f * angle
        // Wrap to (-pi, pi], the range the rest of the movement code works in.
        return if (heading > Math.PI) heading - 2f * Math.PI.toFloat()
        else if (heading <= -(Math.PI.toFloat())) heading + 2f * Math.PI.toFloat()
        else heading
    }

    /**
     * The camera basis **as it sits in the built message**, read back out of the
     * block rather than echoed from the argument.
     *
     * The audit has to report what is *serialized*: a value could be dropped,
     * renamed or overwritten between [build] and the encoder, and a report that
     * prints its own input would never show it. This reads the three fields the
     * codec will write.
     */
    fun serializedBasis(message: SLMessage): CameraBasis {
        val block = message.block(AGENT_DATA)
        return CameraBasis(
            at = block.vector3("CameraAtAxis"),
            left = block.vector3("CameraLeftAxis"),
            up = block.vector3("CameraUpAxis")
        )
    }

    /** The control flags as they sit in the built message, read back. */
    fun serializedControlFlags(message: SLMessage): Long = message.block(AGENT_DATA).u32("ControlFlags")

    /** The body rotation as it sits in the built message, read back. */
    fun serializedBodyRotation(message: SLMessage): Quaternion =
        message.block(AGENT_DATA).quaternion("BodyRotation")

    /** The camera centre as it sits in the built message, read back. */
    fun serializedCameraCenter(message: SLMessage): Vector3 = message.block(AGENT_DATA).vector3("CameraCenter")

    /** The message definition, or null when the template has not been loaded. */
    fun messageDef(): MessageDef? = MessageTemplate.byName(MESSAGE_NAME)

    /**
     * Fills one `AgentUpdate`. Every value is the caller's: nothing here reads
     * the session, the world or the clock.
     */
    fun build(
        def: MessageDef,
        agentId: String,
        sessionId: String,
        bodyYawRadians: Float,
        cameraCenter: Vector3,
        basis: CameraBasis,
        far: Float,
        state: Int,
        controlFlags: Long,
        flags: Int
    ): SLMessage {
        val message = SLMessage(def)
        val block = message.block(AGENT_DATA)
        block.set("AgentID", agentId)
        block.set("SessionID", sessionId)
        val rotation = bodyRotation(bodyYawRadians)
        block.set("BodyRotation", rotation)
        block.set("HeadRotation", rotation)
        block.set("State", state)
        block.set("CameraCenter", cameraCenter)
        block.set("CameraAtAxis", basis.at)
        block.set("CameraLeftAxis", basis.left)
        block.set("CameraUpAxis", basis.up)
        block.set("Far", far)
        block.set("ControlFlags", controlFlags and 0xFFFFFFFFL)
        block.set("Flags", flags)
        return message
    }

    /** `(1.00, 0.00, 0.00)`, the form the report and the audit use. */
    fun pretty(v: Vector3): String =
        "(" + fmt(v.x) + ", " + fmt(v.y) + ", " + fmt(v.z) + ")"

    /** Two decimals, locale-independent: the report is compared by eye and by test. */
    fun fmt(value: Float): String =
        String.format(java.util.Locale.US, "%.2f", if (value == 0f) 0f else value)
}
