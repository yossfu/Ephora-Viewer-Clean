package com.lumiyaviewer.lumiya.slscene

import com.lumiyaviewer.lumiya.renderer.CameraDesc
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The first condition a point failed when it was tested against the frustum.
 *
 * These are not decoration: "the object is not on screen" has at least six
 * different causes (it is behind the camera, nearer than the near plane, past the
 * far plane, or off one of the four sides), and each one implies a different fix.
 */
object FrustumReason {
    const val INSIDE = "DENTRO"
    const val BEHIND = "DETRAS_DE_LA_CAMARA"
    const val TOO_CLOSE = "MAS_CERCA_QUE_NEAR"
    const val TOO_FAR = "MAS_LEJOS_QUE_FAR"
    const val OFF_LEFT = "FUERA_POR_IZQUIERDA"
    const val OFF_RIGHT = "FUERA_POR_DERECHA"
    const val OFF_BOTTOM = "FUERA_POR_ABAJO"
    const val OFF_TOP = "FUERA_POR_ARRIBA"
    const val DEGENERATE = "CAMARA_DEGENERADA"
}

/**
 * One camera-space measurement of a world point.
 *
 * [depth] is metres along the camera's forward axis (negative means behind the
 * camera), [horizontal]/[vertical] are metres along the camera's own right/up
 * axes, and [ndcX]/[ndcY] are normalised device coordinates: -1..1 is exactly
 * the visible rectangle, so `0.5, 0.5` is the middle of the picture. Every one
 * of those is reported, not just the boolean, because "outside by 0.02" and
 * "outside by 40" are different bugs.
 */
class FrustumSample(
    val depth: Float,
    val horizontal: Float,
    val vertical: Float,
    val ndcX: Float,
    val ndcY: Float,
    val inside: Boolean,
    val reason: String
) {
    /** Where on the picture the point falls: 0..1 from left/top, centre is 0.5. */
    val screenX: Float get() = (ndcX + 1f) * 0.5f
    val screenY: Float get() = (1f - ndcY) * 0.5f

    /** `DENTRO`, or `FUERA (motivo)`. */
    val text: String get() = if (inside) FrustumReason.INSIDE else "FUERA (" + reason + ")"

    override fun toString(): String = String.format(
        Locale.US,
        "%s depth=%.2f h=%.2f v=%.2f ndc=(%.3f, %.3f)",
        text, depth, horizontal, vertical, ndcX, ndcY
    )
}

/**
 * The camera's frustum, built from the same numbers the backend's camera was
 * given (eye, target, up, vertical fov, aspect, near, far).
 *
 * The whole point of this class is to answer "is this object inside the picture"
 * *without guessing*: it uses the identical eye/target/up/fov that were handed
 * to Filament's `Camera.lookAt`/`setProjection`, in the identical convention
 * (view space: +x right, +y up, the camera looking down -z), so a disagreement
 * between this test and the picture can only come from the object's transform —
 * which is exactly the question this phase has to settle.
 *
 * [MatrixFrustum] below does the same computation a second way, from the view
 * and projection matrices the live camera hands back. The two are independent;
 * [agreement] reports how far apart they are.
 */
class CameraFrustum(
    val eye: FloatArray,
    val forward: FloatArray,
    val right: FloatArray,
    val up: FloatArray,
    val verticalFovDegrees: Float,
    val aspect: Float,
    val near: Float,
    val far: Float
) {

    private val tanHalfVertical: Float =
        tan(Math.toRadians(verticalFovDegrees.toDouble() * 0.5)).toFloat()

    private val safeAspect: Float = if (aspect > 0.0001f) aspect else DEFAULT_ASPECT

    /** A point test with the failing condition named. */
    fun sample(point: FloatArray): FrustumSample {
        val dx = point[0] - eye[0]
        val dy = point[1] - eye[1]
        val dz = point[2] - eye[2]
        return sampleOffsets(
            dx * forward[0] + dy * forward[1] + dz * forward[2],
            dx * right[0] + dy * right[1] + dz * right[2],
            dx * up[0] + dy * up[1] + dz * up[2]
        )
    }

    private fun sampleOffsets(depth: Float, horizontal: Float, vertical: Float): FrustumSample {
        if (!depth.isFinite() || depth <= DEGENERATE_DEPTH) {
            return FrustumSample(depth, horizontal, vertical, 0f, 0f, false, FrustumReason.BEHIND)
        }
        val halfHeight = depth * tanHalfVertical
        val halfWidth = halfHeight * safeAspect
        val ndcX = horizontal / halfWidth
        val ndcY = vertical / halfHeight
        val reason = when {
            depth < near -> FrustumReason.TOO_CLOSE
            depth > far -> FrustumReason.TOO_FAR
            ndcX < -1f -> FrustumReason.OFF_LEFT
            ndcX > 1f -> FrustumReason.OFF_RIGHT
            ndcY < -1f -> FrustumReason.OFF_BOTTOM
            ndcY > 1f -> FrustumReason.OFF_TOP
            else -> FrustumReason.INSIDE
        }
        return FrustumSample(
            depth, horizontal, vertical, ndcX, ndcY,
            reason == FrustumReason.INSIDE, reason
        )
    }

    /**
     * The conservative test for a whole bounding sphere: true when *any* part of
     * the sphere is inside the frustum, even if its centre is not. A 20 m prim
     * whose centre is 12 m off-axis is still half on screen, and reporting it as
     * "not visible" would be wrong.
     *
     * Each of the six frustum planes is built explicitly (four sides through the
     * eye, plus near and far) and the sphere is tested against all six.
     */
    fun intersectsSphere(centre: FloatArray, radius: Float): Boolean {
        if (radius <= 0f) {
            return sample(centre).inside
        }
        val dx = centre[0] - eye[0]
        val dy = centre[1] - eye[1]
        val dz = centre[2] - eye[2]
        val planes = spherePlanes()
        for (index in planes.indices step PLANE_STRIDE) {
            val nx = planes[index]
            val ny = planes[index + 1]
            val nz = planes[index + 2]
            val offset = planes[index + 3]
            val signed = dx * nx + dy * ny + dz * nz - offset
            if (signed > radius) {
                return false
            }
        }
        return true
    }

    /**
     * Six planes as `(nx, ny, nz, offset)`, normals unit length and pointing into
     * the frustum: a point is inside a plane when `dot(p - eye, n) <= offset`.
     */
    private fun spherePlanes(): FloatArray {
        val out = FloatArray(6 * PLANE_STRIDE)
        val tanH = tanHalfVertical * safeAspect
        var at = 0
        // Right and left sides: |x| <= tanH * depth  ->  dot(d, ±right - tanH*forward) <= 0.
        at = writePlane(out, at, right[0] - tanH * forward[0], right[1] - tanH * forward[1], right[2] - tanH * forward[2], 0f, true)
        at = writePlane(out, at, -right[0] - tanH * forward[0], -right[1] - tanH * forward[1], -right[2] - tanH * forward[2], 0f, true)
        // Top and bottom: |y| <= tanV * depth.
        at = writePlane(out, at, up[0] - tanHalfVertical * forward[0], up[1] - tanHalfVertical * forward[1], up[2] - tanHalfVertical * forward[2], 0f, true)
        at = writePlane(out, at, -up[0] - tanHalfVertical * forward[0], -up[1] - tanHalfVertical * forward[1], -up[2] - tanHalfVertical * forward[2], 0f, true)
        // Near and far: depth in [near, far]  ->  dot(d, -forward) <= -near, dot(d, forward) <= far.
        at = writePlane(out, at, -forward[0], -forward[1], -forward[2], -near, false)
        writePlane(out, at, forward[0], forward[1], forward[2], far, false)
        return out
    }

    private fun writePlane(
        out: FloatArray,
        at: Int,
        nx: Float,
        ny: Float,
        nz: Float,
        offset: Float,
        normalise: Boolean
    ): Int {
        var x = nx
        var y = ny
        var z = nz
        var a = offset
        if (normalise) {
            val length = sqrt(x * x + y * y + z * z)
            if (length > 1e-6f) {
                val inverse = 1f / length
                x *= inverse
                y *= inverse
                z *= inverse
                a *= inverse
            }
        }
        out[at] = x
        out[at + 1] = y
        out[at + 2] = z
        out[at + 3] = a
        return at + PLANE_STRIDE
    }

    /**
     * The radius of the bounding sphere of a mesh with the given local bounds,
     * after the object's scale is applied. This is the radius that matters: the
     * mesh bounds are in the prim's own units, and a prim scaled to 10 m is not
     * a 0.5 m object.
     */
    fun describe(): String = String.format(
        Locale.US,
        "eye=(%.2f, %.2f, %.2f) dir=(%.3f, %.3f, %.3f) up=(%.3f, %.3f, %.3f) fov=%.1f near=%.3f far=%.1f aspect=%.3f",
        eye[0], eye[1], eye[2],
        forward[0], forward[1], forward[2],
        up[0], up[1], up[2],
        verticalFovDegrees, near, far, safeAspect
    )

    companion object {
        const val DEFAULT_ASPECT = 16f / 9f
        private const val DEGENERATE_DEPTH = 1e-4f
        private const val PLANE_STRIDE = 4

        /** The aspect ratio of a viewport, with a sane fallback before layout. */
        fun aspectOf(width: Int, height: Int): Float =
            if (width > 0 && height > 0) width.toFloat() / height.toFloat() else DEFAULT_ASPECT

        /**
         * Builds the frustum from the very numbers that are handed to the
         * backend's camera. The basis is derived here (right = forward x up, then
         * up = right x forward) so that it is right-handed with the camera
         * looking down -z, which is Filament's convention.
         */
        fun from(camera: CameraDesc, aspect: Float): CameraFrustum = build(
            eye = camera.eye,
            forward = floatArrayOf(
                camera.target[0] - camera.eye[0],
                camera.target[1] - camera.eye[1],
                camera.target[2] - camera.eye[2]
            ),
            up = camera.up,
            verticalFovDegrees = camera.verticalFovDegrees,
            aspect = aspect,
            near = camera.near,
            far = camera.far
        )

        /**
         * The same frustum, built from the vectors the **engine** reports for its
         * own camera (`CameraSnapshot.eye/forward/up`), not from the `CameraDesc`
         * the scene sent.
         *
         * This exists because the basis test has to use the engine's own camera to
         * be comparable with the matrix test: if the scene's camera and the
         * engine's ever came apart — the audit prints them side by side — a basis
         * built from the scene's numbers would be testing a camera that is not the
         * one drawing, and the disagreement would be blamed on the geometry
         * instead of on the camera. When the two agree, which is the normal case,
         * this factory removes the doubt completely.
         */
        fun fromEngine(
            eye: FloatArray,
            forward: FloatArray,
            up: FloatArray,
            verticalFovDegrees: Float,
            aspect: Float,
            near: Float,
            far: Float
        ): CameraFrustum = build(eye, forward, up, verticalFovDegrees, aspect, near, far)

        /** The one implementation both factories use: `right = forward x up`. */
        private fun build(
            eye: FloatArray,
            forward: FloatArray,
            up: FloatArray,
            verticalFovDegrees: Float,
            aspect: Float,
            near: Float,
            far: Float
        ): CameraFrustum {
            var fx = forward[0]
            var fy = forward[1]
            var fz = forward[2]
            var length = sqrt(fx * fx + fy * fy + fz * fz)
            if (length < 1e-6f) {
                fx = 1f
                fy = 0f
                fz = 0f
                length = 1f
            }
            val inverse = 1f / length
            fx *= inverse
            fy *= inverse
            fz *= inverse

            val ux = up[0]
            val uy = up[1]
            val uz = up[2]

            // right = normalize(forward x up)
            var rx = fy * uz - fz * uy
            var ry = fz * ux - fx * uz
            var rz = fx * uy - fy * ux
            var rightLength = sqrt(rx * rx + ry * ry + rz * rz)
            if (rightLength < 1e-6f) {
                // forward and up are parallel: pick any perpendicular axis.
                rx = 1f
                ry = 0f
                rz = 0f
                rightLength = 1f
            }
            val rightInverse = 1f / rightLength
            rx *= rightInverse
            ry *= rightInverse
            rz *= rightInverse

            // up = right x forward (re-orthogonalised, so a non-perpendicular
            // "up" input cannot tilt the frustum test).
            val upX = ry * fz - rz * fy
            val upY = rz * fx - rx * fz
            val upZ = rx * fy - ry * fx

            return CameraFrustum(
                eye = floatArrayOf(eye[0], eye[1], eye[2]),
                forward = floatArrayOf(fx, fy, fz),
                right = floatArrayOf(rx, ry, rz),
                up = floatArrayOf(upX, upY, upZ),
                verticalFovDegrees = verticalFovDegrees,
                aspect = aspect,
                near = near,
                far = far
            )
        }
    }
}

/**
 * One world point taken through the engine's own matrices, with every
 * intermediate step kept.
 *
 * The frustum test only needs the boolean and the NDC, but "why does the
 * diagnostic disagree" needs more: a wrong sign of the view axis shows up in
 * [viewZ], a wrong projection shows up in [clipZ]/[clipW], and the perspective
 * divide hides both. Printing the view-space and clip-space positions is what
 * turns \"the matrices say inside and the basis says behind\" into a specific
 * step.
 *
 * All of this is a **diagnostic**: the engine's own per-renderable decision is
 * not exposed by Filament's Java API, so this is the closest available reading of
 * the inputs the engine culls with (its own view and projection matrices and its
 * own world transform).
 */
class MatrixTrace(
    /** Position in the engine's view space (its camera looks down -z). */
    val viewX: Float,
    val viewY: Float,
    val viewZ: Float,
    /** Position in clip space, before the perspective divide. */
    val clipX: Float,
    val clipY: Float,
    val clipZ: Float,
    val clipW: Float,
    /** Normalised device coordinates: -1..1 is exactly the visible rectangle. */
    val ndcX: Float,
    val ndcY: Float,
    /** Metres along the camera's forward axis, taken from the view matrix. */
    val depth: Float
)

/**
 * The same test, computed from the matrices the live camera hands back.
 *
 * This is deliberately a second, independent implementation: it multiplies the
 * world point by the camera's own view matrix and then by its own projection
 * matrix, which is literally the transform the driver applies. If the
 * basis-based [CameraFrustum] and this one disagree, the camera the backend is
 * using is not the camera the scene thinks it set — a failure mode that no
 * counter would ever show.
 *
 * It is a diagnostic either way: the real culling is the engine's
 * (`View.setFrustumCullingEnabled` plus the per-renderable flag), which tests the
 * renderable's own bounding box and does not expose a per-object verdict.
 */
class MatrixFrustum(
    private val view: FloatArray,
    private val projection: DoubleArray,
    private val near: Float,
    private val far: Float
) {

    /**
     * View space → clip space → NDC, step by step, for one world point given in
     * camera space inputs. One implementation, used by both [sample] and the
     * phase-2.12 trace, so the diagnosis can never disagree with the verdict.
     */
    fun trace(point: FloatArray): MatrixTrace {
        val x = point[0]
        val y = point[1]
        val z = point[2]
        // Column-major view matrix: column 0 is view[0..3], column 3 is view[12..15].
        val vx = view[0] * x + view[4] * y + view[8] * z + view[12]
        val vy = view[1] * x + view[5] * y + view[9] * z + view[13]
        val vz = view[2] * x + view[6] * y + view[10] * z + view[14]
        // Filament's camera looks down its own -z, so depth is -vz.
        val depth = -vz
        val clipX = projection[0] * vx + projection[4] * vy + projection[8] * vz + projection[12]
        val clipY = projection[1] * vx + projection[5] * vy + projection[9] * vz + projection[13]
        val clipZ = projection[2] * vx + projection[6] * vy + projection[10] * vz + projection[14]
        val clipW = projection[3] * vx + projection[7] * vy + projection[11] * vz + projection[15]
        val safeW = if (abs(clipW) < 1e-6) 1.0 else clipW
        return MatrixTrace(
            viewX = vx,
            viewY = vy,
            viewZ = vz,
            clipX = clipX.toFloat(),
            clipY = clipY.toFloat(),
            clipZ = clipZ.toFloat(),
            clipW = clipW.toFloat(),
            ndcX = (clipX / safeW).toFloat(),
            ndcY = (clipY / safeW).toFloat(),
            depth = depth
        )
    }

    fun sample(point: FloatArray): FrustumSample {
        val t = trace(point)
        if (!t.depth.isFinite() || t.depth <= 0f || abs(t.clipW) < 1e-6) {
            return FrustumSample(t.depth, t.viewX, t.viewY, 0f, 0f, false, FrustumReason.BEHIND)
        }
        val ndcX = t.ndcX
        val ndcY = t.ndcY
        val reason = when {
            t.depth < near -> FrustumReason.TOO_CLOSE
            t.depth > far -> FrustumReason.TOO_FAR
            ndcX < -1f -> FrustumReason.OFF_LEFT
            ndcX > 1f -> FrustumReason.OFF_RIGHT
            ndcY < -1f -> FrustumReason.OFF_BOTTOM
            ndcY > 1f -> FrustumReason.OFF_TOP
            else -> FrustumReason.INSIDE
        }
        return FrustumSample(
            t.depth, t.viewX, t.viewY, ndcX, ndcY, reason == FrustumReason.INSIDE, reason
        )
    }
}

/** Matrix helpers, so the two paths can be compared field by field. */
object FrustumMath {

    /** Column-major 4x4 as four rows of four, for a human to read. */
    fun rows(matrix: FloatArray): String {
        if (matrix.size < 16) {
            return "-"
        }
        val builder = StringBuilder(96)
        for (row in 0 until 4) {
            builder.append('[')
            for (column in 0 until 4) {
                // Column-major: element (row, column) is at column * 4 + row.
                builder.append(String.format(Locale.US, "% 8.3f", matrix[column * 4 + row]))
                if (column < 3) {
                    builder.append(' ')
                }
            }
            builder.append(']')
            if (row < 3) {
                builder.append(' ')
            }
        }
        return builder.toString()
    }

    /** The diagonal of a column-major projection matrix, which is what matters. */
    fun diagonal(matrix: DoubleArray): String {
        if (matrix.size < 16) {
            return "-"
        }
        return String.format(
            Locale.US,
            "[% .4f % .4f % .4f % .4f]  col3=[% .4f % .4f % .4f % .4f]",
            matrix[0], matrix[5], matrix[10], matrix[15],
            matrix[12], matrix[13], matrix[14], matrix[15]
        )
    }

    /** The largest absolute difference between two matrices (same length). */
    fun maxDifference(a: FloatArray, b: FloatArray): Float {
        val count = minOf(a.size, b.size)
        var worst = 0f
        for (index in 0 until count) {
            val difference = abs(a[index] - b[index])
            if (difference > worst) {
                worst = difference
            }
        }
        return worst
    }

    /** Translation column of a column-major matrix. */
    fun translation(matrix: FloatArray): FloatArray =
        floatArrayOf(matrix[12], matrix[13], matrix[14])

    /** The largest side of the difference between two points. */
    fun pointDifference(a: FloatArray, b: FloatArray): Float {
        var worst = 0f
        for (index in 0 until minOf(a.size, b.size)) {
            val difference = abs(a[index] - b[index])
            if (difference > worst) {
                worst = difference
            }
        }
        return worst
    }
}
