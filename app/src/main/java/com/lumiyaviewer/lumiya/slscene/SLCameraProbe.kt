package com.lumiyaviewer.lumiya.slscene

import com.lumiyaviewer.lumiya.renderer.CameraDesc
import com.lumiyaviewer.lumiya.renderer.EntityDesc
import com.lumiyaviewer.lumiya.renderer.EntityHandle
import com.lumiyaviewer.lumiya.renderer.MaterialDesc
import com.lumiyaviewer.lumiya.renderer.MaterialHandle
import com.lumiyaviewer.lumiya.renderer.MeshHandle
import com.lumiyaviewer.lumiya.renderer.Renderer
import com.lumiyaviewer.lumiya.renderer.Transform
import java.util.Locale

/**
 * A camera probe: one bright cube, glued a fixed distance in front of wherever
 * the camera currently is.
 *
 * It is the control experiment for this phase. The region's own prims arrive
 * over the network, are turned into meshes by `slcore` and placed by transforms
 * that originate in a packet — every one of those steps can be wrong. This cube
 * has none of them: its geometry is six quads built here, its material is
 * fullbright (so no lighting term can hide it), and its transform is recomputed
 * from the camera every frame, so it is always dead centre of the picture.
 *
 * That gives the phase a clean split:
 *
 * * **probe visible, prims not** → surface, swap chain, engine, camera and
 *   projection all work; the fault is in the SL side (transforms, positions,
 *   distances), and the audit table says which.
 * * **probe not visible** → the fault is below the scene layer (camera,
 *   projection, render pass or surface), and no amount of fixing transforms
 *   would have helped.
 *
 * It is **not** Second Life geometry and it is never presented as such: nothing
 * in the region ever sees it, and [describe] says so in the report.
 */
class SLCameraProbe(private val renderer: Renderer) {

    private val mesh: MeshHandle = renderer.createMesh(SLDiagnosticProbe.cubeMesh(SIZE))
    private val material: MaterialHandle = renderer.createMaterial(
        MaterialDesc(
            baseColor = floatArrayOf(1.0f, 0.10f, 0.85f, 1f),
            fullBright = true,
            materialCode = 5
        )
    )
    private val entity: EntityHandle = renderer.createEntity(
        EntityDesc(
            mesh = mesh,
            materialOfFace = intArrayOf(material.id),
            transform = Transform(
                translation = FloatArray(3),
                rotation = floatArrayOf(0f, 0f, 0f, 1f),
                scale = floatArrayOf(1f, 1f, 1f)
            )
        )
    )

    /** Where the probe currently is, in region metres. */
    val position = FloatArray(3)

    /** False when the renderer refused the probe (the probe's own mesh failed). */
    val valid: Boolean get() = entity.isValid

    /** True once [place] has run at least once. */
    var placed: Boolean = false
        private set

    /**
     * Recomputes the probe's position from the camera that was just handed to
     * the backend: straight ahead, [DISTANCE] metres along the view direction.
     * Called every frame, on the render thread, before the frame is drawn.
     */
    fun place(camera: CameraDesc) {
        var fx = camera.target[0] - camera.eye[0]
        var fy = camera.target[1] - camera.eye[1]
        var fz = camera.target[2] - camera.eye[2]
        val length = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz)
        if (length > 1e-6f) {
            val inverse = 1f / length
            fx *= inverse
            fy *= inverse
            fz *= inverse
        } else {
            fx = 1f
            fy = 0f
            fz = 0f
        }
        position[0] = camera.eye[0] + fx * DISTANCE
        position[1] = camera.eye[1] + fy * DISTANCE
        position[2] = camera.eye[2] + fz * DISTANCE
        renderer.updateTransform(
            entity,
            Transform(
                translation = floatArrayOf(position[0], position[1], position[2]),
                rotation = floatArrayOf(0f, 0f, 0f, 1f),
                scale = floatArrayOf(1f, 1f, 1f)
            )
        )
        placed = true
    }

    /**
     * The CAMERA_TEST block. The frustum sample is computed with the same math
     * the audit uses, so "the probe should be at the centre of the picture" is a
     * number, not a claim — but whether it is *actually on screen* is only
     * decidable by looking, which is why the last line says so.
     */
    fun describe(camera: CameraDesc, aspect: Float): String {
        val builder = StringBuilder(320)
        builder.append("CAMERA_TEST (probe de camara, NO es geometria SL)\n")
        builder.append("  probe: entidad=").append(entity)
        builder.append(" valida=").append(if (valid) "SI" else "NO")
        builder.append(" cubo ").append(SIZE).append(" m\n")
        builder.append("  proposito: si esto se ve y los prims no, el fallo esta en las\n")
        builder.append("             transformaciones/posiciones SL, no en la camara ni en Filament.\n")
        if (!placed) {
            builder.append("  resultado: todavia no colocado (esperando un frame)")
            return builder.toString()
        }
        builder.append("  posicion=").append(fmt(position))
        builder.append(" (eye + ").append(DISTANCE).append(" m en la direccion de vista)\n")
        val sample = CameraFrustum.from(camera, aspect).sample(position)
        builder.append("  frustum(calculado)=").append(sample.text)
        builder.append("  depth=").append(fmt1(sample.depth)).append(" m")
        builder.append("  ndc=(").append(fmt3(sample.ndcX)).append(", ").append(fmt3(sample.ndcY)).append(")\n")
        builder.append("  posicion en pantalla: centro-x=").append(pct(sample.screenX))
        builder.append(" centro-y=").append(pct(sample.screenY))
        builder.append("  -> deberia estar en el CENTRO de la pantalla\n")
        builder.append("  visibleAJO=PENDIENTE: mira la pantalla y confirma si ves un cubo magenta grande en el centro")
        return builder.toString()
    }

    fun destroy() {
        renderer.destroyEntity(entity)
        renderer.destroyMaterial(material)
        renderer.destroyMesh(mesh)
    }

    private fun fmt(v: FloatArray): String =
        String.format(Locale.US, "%.2f, %.2f, %.2f", v[0], v[1], v[2])

    private fun fmt1(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun fmt3(value: Float): String = String.format(Locale.US, "%.3f", value)

    private fun pct(fraction: Float): String = String.format(Locale.US, "%.0f%%", fraction * 100f)

    companion object {
        /** Edge length of the probe cube, in metres. */
        const val SIZE = 0.8f

        /**
         * How far in front of the camera it sits. Close enough that it is
         * unmistakable, far enough that it is inside the near plane of every
         * camera the viewer uses, and small enough that the picture behind it is
         * still visible around it.
         */
        const val DISTANCE = 3f
    }
}
