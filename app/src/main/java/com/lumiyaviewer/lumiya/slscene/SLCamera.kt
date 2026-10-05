package com.lumiyaviewer.lumiya.slscene

import com.lumiyaviewer.lumiya.renderer.CameraDesc
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The viewer's camera, in terms of the scene rather than any graphics API.
 *
 * Second Life's world is Z-up (X east, Y north, Z up), and so is this class: it
 * produces a plain [CameraDesc] and the renderer backend converts that into
 * whatever its own camera wants.
 *
 * The camera orbits a focus point, which normally follows the agent — one finger
 * drags to orbit, two fingers pinch to zoom, exactly like Lumiya and the
 * official viewer. The drag direction is pushed back into the session so that
 * "walk forward" goes where you are looking.
 */
class SLCamera {

    /** Direction the camera looks, in radians, measured in the XY plane. */
    var yaw: Float = 0f

    /**
     * Elevation of the camera above the focus point, in radians: 0 puts the
     * camera level with the focus, positive lifts it up and looks down.
     */
    var pitch: Float = 0.28f

    /** Distance from the focus point, in metres. */
    var distance: Float = 6.0f

    var verticalFovDegrees: Float = 60f
    var near: Float = 0.05f
    var far: Float = 1024f

    /** When true the focus point tracks the agent's own position. */
    var followAgent: Boolean = true

    /** How the camera decided where to sit; shown in the debug HUD. */
    var framing: String = FRAMING_AGENT

    /**
     * A temporary, reversible camera override used by the diagnostic phase: a
     * plain eye/target pair that ignores the orbit rig completely.
     *
     * It exists so the camera can be *made* to look straight at one known real
     * prim (`eye = prim + offset`, `target = prim`) without the orbit's focus,
     * distance or yaw being able to move it somewhere else — and so that
     * clearing it restores the normal behaviour exactly. Nothing else in the
     * scene is touched by it.
     */
    private var lockedEye: FloatArray? = null
    private var lockedTarget: FloatArray? = null

    /** True while [lockTo] is in effect. */
    val isLocked: Boolean get() = lockedEye != null

    private val focus = floatArrayOf(DEFAULT_FOCUS[0], DEFAULT_FOCUS[1], DEFAULT_FOCUS[2])

    val focusX: Float get() = focus[0]
    val focusY: Float get() = focus[1]
    val focusZ: Float get() = focus[2]

    fun setFocus(x: Float, y: Float, z: Float) {
        focus[0] = x
        focus[1] = y
        focus[2] = z
    }

    /** Points the camera the way the avatar is facing. */
    fun lookAlong(headingRadians: Float) {
        yaw = headingRadians
    }

    fun orbit(deltaX: Float, deltaY: Float) {
        yaw -= deltaX * ROTATE_SPEED
        pitch = (pitch + deltaY * PITCH_SPEED).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    /** [factor] > 1 zooms out. */
    fun zoom(factor: Float) {
        distance = (distance * factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    fun desc(): CameraDesc {
        val locked = lockedEye
        val targetLocked = lockedTarget
        if (locked != null && targetLocked != null) {
            return CameraDesc(
                eye = floatArrayOf(locked[0], locked[1], locked[2]),
                target = floatArrayOf(targetLocked[0], targetLocked[1], targetLocked[2]),
                up = floatArrayOf(0f, 0f, 1f),
                verticalFovDegrees = verticalFovDegrees,
                near = near,
                far = far
            )
        }
        val horizontal = cos(pitch) * distance
        val eye = floatArrayOf(
            focus[0] - horizontal * cos(yaw),
            focus[1] - horizontal * sin(yaw),
            focus[2] + sin(pitch) * distance
        )
        return CameraDesc(
            eye = eye,
            target = floatArrayOf(focus[0], focus[1], focus[2]),
            up = floatArrayOf(0f, 0f, 1f),
            verticalFovDegrees = verticalFovDegrees,
            near = near,
            far = far
        )
    }

    /**
     * Pins the camera to one eye/target pair, bypassing the orbit entirely, and
     * marks the framing so the HUD says the picture is not the normal view.
     */
    fun lockTo(eye: FloatArray, target: FloatArray) {
        lockedEye = floatArrayOf(eye[0], eye[1], eye[2])
        lockedTarget = floatArrayOf(target[0], target[1], target[2])
        followAgent = false
        framing = FRAMING_PRIM
    }

    /** Undoes [lockTo]; the orbit rig (focus, yaw, pitch, distance) is untouched. */
    fun unlock() {
        lockedEye = null
        lockedTarget = null
        framing = FRAMING_AGENT
    }

    /** The agent's position, in metres, where the camera currently is. */
    val currentEye: FloatArray get() = desc().eye

    /** Unit vector the camera looks along (Z-up frame). */
    val forward: FloatArray
        get() {
            val eye = currentEye
            val dx = focus[0] - eye[0]
            val dy = focus[1] - eye[1]
            val dz = focus[2] - eye[2]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            val inverse = if (length > 0.0001f) 1f / length else 0f
            return floatArrayOf(dx * inverse, dy * inverse, dz * inverse)
        }

    /**
     * Points the camera at an explicit place from an explicit distance. Used by
     * the diagnostic modes, which need the camera to be somewhere known rather
     * than wherever the agent happens to be.
     */
    fun lookAt(point: FloatArray, from: Float, yawRadians: Float = yaw, pitchRadians: Float = pitch) {
        setFocus(point[0], point[1], point[2])
        distance = from
        yaw = yawRadians
        pitch = pitchRadians
    }

    /**
     * Frames a bounding box: the whole world, or whatever the region has sent.
     *
     * This is what makes "the camera is somewhere with nothing in front of it"
     * impossible to mistake for "nothing was drawn": the camera is placed
     * outside the box, looking at its centre, far enough that the box fills the
     * view. Returns false when the box is a point (nothing to frame).
     */
    fun frameBounds(min: FloatArray, max: FloatArray, aspect: Float): Boolean {
        val dx = max[0] - min[0]
        val dy = max[1] - min[1]
        val dz = max[2] - min[2]
        val radius = 0.5f * sqrt(dx * dx + dy * dy + dz * dz)
        if (radius <= 0.05f) {
            return false
        }
        setFocus((min[0] + max[0]) * 0.5f, (min[1] + max[1]) * 0.5f, (min[2] + max[2]) * 0.5f)
        val vertical = Math.toRadians(verticalFovDegrees.toDouble())
        val horizontal = 2.0 * atan(tan(vertical * 0.5) * aspect.coerceAtLeast(0.2f).toDouble())
        val narrowest = min(vertical, horizontal)
        val fitted = radius / sin(narrowest * 0.5).toFloat()
        distance = (fitted * FRAME_MARGIN).coerceIn(MIN_DISTANCE, MAX_FRAME_DISTANCE)
        pitch = FRAME_PITCH
        yaw = FRAME_YAW
        followAgent = false
        framing = FRAMING_WORLD
        return true
    }

    companion object {
        /** Middle of a second life region, a couple of metres above the ground. */
        val DEFAULT_FOCUS = floatArrayOf(128f, 128f, 25f)

        const val FRAMING_AGENT = "agente"
        const val FRAMING_WORLD = "mundo"
        const val FRAMING_TEST = "prueba"
        /** The temporary camera lock of the visibility phase (`lockTo`). */
        const val FRAMING_PRIM = "prim"

        const val ROTATE_SPEED = 0.0085f
        const val PITCH_SPEED = 0.0055f
        const val MIN_PITCH = -0.45f
        const val MAX_PITCH = 1.35f
        const val MIN_DISTANCE = 1.2f
        const val MAX_DISTANCE = 96f

        /** Framing a whole region needs to be able to back off much further. */
        const val MAX_FRAME_DISTANCE = 640f
        const val FRAME_MARGIN = 1.2f
        const val FRAME_PITCH = 0.42f
        const val FRAME_YAW = 0.6f
    }
}
