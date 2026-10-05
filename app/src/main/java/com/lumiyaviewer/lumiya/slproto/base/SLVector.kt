package com.lumiyaviewer.lumiya.slproto.base

class Vector3(val x: Float, val y: Float, val z: Float) {

    override fun toString(): String {
        return "(" + x + ", " + y + ", " + z + ")"
    }

    fun distanceTo(other: Vector3): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun distanceTo(x: Float, y: Float, z: Float): Float {
        val dx = this.x - x
        val dy = this.y - y
        val dz = this.z - z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        val ZERO = Vector3(0f, 0f, 0f)
    }
}

class Vector3d(val x: Double, val y: Double, val z: Double) {

    override fun toString(): String {
        return "(" + x + ", " + y + ", " + z + ")"
    }

    companion object {
        val ZERO = Vector3d(0.0, 0.0, 0.0)
    }
}

class Vector4(val x: Float, val y: Float, val z: Float, val w: Float) {

    override fun toString(): String {
        return "(" + x + ", " + y + ", " + z + ", " + w + ")"
    }

    companion object {
        val ZERO = Vector4(0f, 0f, 0f, 0f)
    }
}

class Quaternion(val x: Float, val y: Float, val z: Float, val w: Float) {

    override fun toString(): String {
        return "(" + x + ", " + y + ", " + z + ", " + w + ")"
    }

    companion object {
        val IDENTITY = Quaternion(0f, 0f, 0f, 1f)

        /** Rotation about the world Z axis (which is "up" in Second Life). */
        fun fromYaw(yawRadians: Float): Quaternion {
            val half = yawRadians / 2f
            return Quaternion(0f, 0f, kotlin.math.sin(half), kotlin.math.cos(half))
        }

        /**
         * Second Life sends rotations as a normalised xyz triple for primitives
         * (the wire format squeezes the fourth component out); the w component
         * is rebuilt from the unit-length constraint.
         */
        fun fromXyz(x: Float, y: Float, z: Float): Quaternion {
            val squared = x * x + y * y + z * z
            val w = kotlin.math.sqrt(kotlin.math.max(0f, 1f - squared))
            val length = kotlin.math.sqrt(squared + w * w)
            if (length < 1e-6f) {
                return IDENTITY
            }
            return Quaternion(x / length, y / length, z / length, w / length)
        }
    }
}
