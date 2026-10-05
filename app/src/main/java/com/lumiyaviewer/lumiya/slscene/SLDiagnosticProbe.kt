package com.lumiyaviewer.lumiya.slscene

import com.lumiyaviewer.lumiya.renderer.EntityDesc
import com.lumiyaviewer.lumiya.renderer.EntityHandle
import com.lumiyaviewer.lumiya.renderer.MaterialDesc
import com.lumiyaviewer.lumiya.renderer.MaterialHandle
import com.lumiyaviewer.lumiya.renderer.MeshDesc
import com.lumiyaviewer.lumiya.renderer.MeshHandle
import com.lumiyaviewer.lumiya.renderer.Renderer
import com.lumiyaviewer.lumiya.renderer.Transform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The "pure Filament" probe: geometry that comes from nothing but this file.
 *
 * It exists to answer one question without any doubt: *if this does not show up
 * on the screen, nothing about Second Life can be blamed* — not the protocol,
 * not the world model, not the primitive tessellator, not the coordinate
 * conversion. Every object here is built as plain vertex data and pushed through
 * exactly the same [Renderer] the region objects use (mesh → material → entity →
 * scene), so a failure isolates the graphics path: SurfaceView → SwapChain →
 * Renderer → View → Scene → Camera → present.
 *
 * The probe deliberately contains three shapes whose failure modes are
 * different, so one look at the screen says *which* part is broken:
 *
 * * a **lit** red cube — needs the sun and the ambient term to be right,
 * * a **fullbright** green cube — ignores lighting completely (its colour is
 *   emissive), so if only the red one is missing, the lighting is the problem,
 * * a blue **sphere** — a high-vertex-count mesh, so if only it is missing, the
 *   vertex/index buffer upload is the problem,
 * * a grey ground plane — a large surface, so "the scene renders but everything
 *   is off-screen" cannot hide this one.
 *
 * All coordinates are in the viewer's own frame (Z up, X east, Y north), the
 * same one [SLCamera] and the region objects use.
 */
class SLDiagnosticProbe(private val renderer: Renderer) {

    private val meshes = ArrayList<MeshHandle>()
    private val materials = ArrayList<MaterialHandle>()
    private val entities = ArrayList<EntityHandle>()

    var vertices: Int = 0
        private set
    var triangles: Int = 0
        private set

    init {
        val cubeMesh = cubeMesh(CUBE_SIZE)
        val sphereMesh = sphereMesh(SPHERE_RADIUS)
        val planeMesh = planeMesh()

        val litCube = renderer.createMaterial(
            MaterialDesc(baseColor = floatArrayOf(0.90f, 0.13f, 0.10f, 1f), materialCode = 3)
        )
        val brightCube = renderer.createMaterial(
            MaterialDesc(
                baseColor = floatArrayOf(0.20f, 1.00f, 0.25f, 1f),
                fullBright = true,
                materialCode = 5
            )
        )
        val sphereMaterial = renderer.createMaterial(
            MaterialDesc(baseColor = floatArrayOf(0.22f, 0.45f, 1.00f, 1f), materialCode = 5)
        )
        val groundMaterial = renderer.createMaterial(
            MaterialDesc(baseColor = floatArrayOf(0.42f, 0.42f, 0.46f, 1f), materialCode = 0)
        )

        val cubeHandle = renderer.createMesh(cubeMesh)
        val sphereHandle = renderer.createMesh(sphereMesh)
        val planeHandle = renderer.createMesh(planeMesh)

        meshes.add(cubeHandle)
        meshes.add(sphereHandle)
        meshes.add(planeHandle)
        materials.add(litCube)
        materials.add(brightCube)
        materials.add(sphereMaterial)
        materials.add(groundMaterial)

        entities.add(
            renderer.createEntity(
                EntityDesc(
                    mesh = cubeHandle,
                    materialOfFace = intArrayOf(litCube.id),
                    transform = transform(LIT_CUBE_AT, floatArrayOf(1f, 1f, 1f))
                )
            )
        )
        entities.add(
            renderer.createEntity(
                EntityDesc(
                    mesh = cubeHandle,
                    materialOfFace = intArrayOf(brightCube.id),
                    transform = transform(BRIGHT_CUBE_AT, floatArrayOf(1f, 1f, 1f))
                )
            )
        )
        entities.add(
            renderer.createEntity(
                EntityDesc(
                    mesh = sphereHandle,
                    materialOfFace = intArrayOf(sphereMaterial.id),
                    transform = transform(SPHERE_AT, floatArrayOf(1f, 1f, 1f))
                )
            )
        )
        entities.add(
            renderer.createEntity(
                EntityDesc(
                    mesh = planeHandle,
                    materialOfFace = intArrayOf(groundMaterial.id),
                    transform = transform(floatArrayOf(0f, 0f, 0f), floatArrayOf(60f, 60f, 1f))
                )
            )
        )

        vertices = cubeMesh.vertexCount * 2 + sphereMesh.vertexCount + planeMesh.vertexCount
        triangles = (cubeMesh.indices.size * 2 + sphereMesh.indices.size + planeMesh.indices.size) / 3
    }

    val entityCount: Int get() = entities.size

    fun destroy() {
        for (entity in entities) {
            renderer.destroyEntity(entity)
        }
        entities.clear()
        for (material in materials) {
            renderer.destroyMaterial(material)
        }
        materials.clear()
        for (mesh in meshes) {
            renderer.destroyMesh(mesh)
        }
        meshes.clear()
    }

    private fun transform(translation: FloatArray, scale: FloatArray): Transform = Transform(
        translation = translation,
        rotation = floatArrayOf(0f, 0f, 0f, 1f),
        scale = scale
    )

    companion object {

        const val CUBE_SIZE = 2.0f
        const val SPHERE_RADIUS = 1.2f

        /** Where the probe's camera should look, and from how far. */
        val FOCUS = floatArrayOf(0f, 1.8f, 1.1f)
        const val DISTANCE = 10f
        const val YAW = (-PI / 2).toFloat() + 0.30f
        const val PITCH = 0.22f

        private val LIT_CUBE_AT = floatArrayOf(-2.2f, 0f, 1.0f)
        private val BRIGHT_CUBE_AT = floatArrayOf(2.2f, 0f, 1.0f)
        private val SPHERE_AT = floatArrayOf(0f, 4.2f, 1.2f)

        /** Builds the probe and returns it, ready to be looked at. */
        fun build(renderer: Renderer): SLDiagnosticProbe = SLDiagnosticProbe(renderer)

        /**
         * A cube centred on the origin, one material, 24 vertices.
         *
         * Winding is counter-clockwise seen from *outside* the cube, which is
         * Filament's front face, so the shape is not accidentally back-face
         * culled — a classic way to make a "missing" object.
         */
        fun cubeMesh(size: Float): MeshDesc {
            val h = size * 0.5f
            val positions = ArrayList<Float>(24 * 8)
            val indices = IntArray(36)

            val faces = arrayOf(
                // +X
                floatArrayOf(h, -h, -h, h, h, -h, h, h, h, h, -h, h),
                // -X
                floatArrayOf(-h, -h, h, -h, h, h, -h, h, -h, -h, -h, -h),
                // +Y
                floatArrayOf(h, h, -h, -h, h, -h, -h, h, h, h, h, h),
                // -Y
                floatArrayOf(-h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h),
                // +Z
                floatArrayOf(-h, -h, h, h, -h, h, h, h, h, -h, h, h),
                // -Z
                floatArrayOf(-h, h, -h, h, h, -h, h, -h, -h, -h, -h, -h)
            )
            val normals = arrayOf(
                floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
                floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
                floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f)
            )
            val uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

            for (face in faces.indices) {
                for (corner in 0 until 4) {
                    positions.add(faces[face][corner * 3])
                    positions.add(faces[face][corner * 3 + 1])
                    positions.add(faces[face][corner * 3 + 2])
                    positions.add(normals[face][0])
                    positions.add(normals[face][1])
                    positions.add(normals[face][2])
                    positions.add(uvs[corner * 2])
                    positions.add(uvs[corner * 2 + 1])
                }
                val base = face * 4
                val index = face * 6
                indices[index] = base
                indices[index + 1] = base + 1
                indices[index + 2] = base + 2
                indices[index + 3] = base
                indices[index + 4] = base + 2
                indices[index + 5] = base + 3
            }
            return meshOf(positions, indices, floatArrayOf(-h, -h, -h), floatArrayOf(h, h, h))
        }

        /**
         * A UV sphere around the origin: poles on Z, `segments` around, `rings`
         * from pole to pole. Winding is outward (see [cubeMesh]).
         */
        fun sphereMesh(radius: Float, segments: Int = 20, rings: Int = 12): MeshDesc {
            val positions = ArrayList<Float>((rings + 1) * (segments + 1) * 8)
            for (ring in 0..rings) {
                val phi = PI * ring / rings
                val z = radius * cos(phi)
                val ringRadius = radius * sin(phi)
                for (segment in 0..segments) {
                    val theta = 2.0 * PI * segment / segments
                    val x = ringRadius * cos(theta)
                    val y = ringRadius * sin(theta)
                    val length = kotlin.math.sqrt(x * x + y * y + z * z).toFloat()
                    val inverse = if (length > 0f) 1f / length else 0f
                    positions.add(x.toFloat())
                    positions.add(y.toFloat())
                    positions.add(z.toFloat())
                    positions.add(x.toFloat() * inverse)
                    positions.add(y.toFloat() * inverse)
                    positions.add(z.toFloat() * inverse)
                    positions.add(segment.toFloat() / segments)
                    positions.add(1f - ring.toFloat() / rings)
                }
            }
            val indices = ArrayList<Int>(rings * segments * 6)
            val stride = segments + 1
            for (ring in 0 until rings) {
                for (segment in 0 until segments) {
                    val a = ring * stride + segment
                    val b = a + stride
                    indices.add(a)
                    indices.add(b)
                    indices.add(a + 1)
                    indices.add(a + 1)
                    indices.add(b)
                    indices.add(b + 1)
                }
            }
            return meshOf(
                positions,
                indices.toIntArray(),
                floatArrayOf(-radius, -radius, -radius),
                floatArrayOf(radius, radius, radius)
            )
        }

        /** A 1x1 quad in the XY plane, facing +Z (scale it through the transform). */
        fun planeMesh(): MeshDesc {
            val positions = floatArrayOf(
                -0.5f, -0.5f, 0f, 0f, 0f, 1f, 0f, 1f,
                0.5f, -0.5f, 0f, 0f, 0f, 1f, 1f, 1f,
                0.5f, 0.5f, 0f, 0f, 0f, 1f, 1f, 0f,
                -0.5f, 0.5f, 0f, 0f, 0f, 1f, 0f, 0f
            )
            // The V coordinate points down (-Y) so a texture applied to it later
            // has the same orientation secondlife gives a face.
            var index = 0
            while (index < positions.size) {
                positions[index + 7] = 1f - positions[index + 7]
                index += 8
            }
            return meshOf(
                toList(positions),
                intArrayOf(0, 1, 2, 0, 2, 3),
                floatArrayOf(-0.5f, -0.5f, 0f),
                floatArrayOf(0.5f, 0.5f, 0f)
            )
        }

        private fun toList(values: FloatArray): MutableList<Float> {
            val list = ArrayList<Float>(values.size)
            for (value in values) {
                list.add(value)
            }
            return list
        }

        private fun meshOf(
            vertices: MutableList<Float>,
            indices: IntArray,
            boundsMin: FloatArray,
            boundsMax: FloatArray
        ): MeshDesc {
            val array = FloatArray(vertices.size)
            for (index in vertices.indices) {
                array[index] = vertices[index]
            }
            return MeshDesc(
                vertices = array,
                indices = indices,
                // One face group: the whole mesh, one material.
                faceGroups = intArrayOf(0, 0, indices.size),
                boundsMin = boundsMin,
                boundsMax = boundsMax
            )
        }
    }
}
