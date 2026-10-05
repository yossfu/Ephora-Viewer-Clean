package com.lumiyaviewer.lumiya.slscene

import com.lumiyaviewer.lumiya.slproto.base.Quaternion
import com.lumiyaviewer.lumiya.slproto.base.Vector3
import com.lumiyaviewer.lumiya.slproto.world.SceneObject
import com.lumiyaviewer.lumiya.slproto.world.UpdateSource
import com.lumiyaviewer.lumiya.slworld.SLObject
import com.lumiyaviewer.lumiya.slworld.SLRegion
import com.lumiyaviewer.lumiya.slworld.SLWorldDelta

/**
 * A fixed set of prims for the Phase 1 test screen.
 *
 * This is a *debug* scene, and it is the one place in the viewer where geometry
 * is not something a simulator sent. It exists so the whole chain can be
 * verified on a device without logging in: Second Life prim parameters →
 * `slcore` geometry → `SLMesh` → `SLScene` → `FilamentRenderer` → Filament.
 *
 * Every parameter below is a real Second Life parameter encoding — the same
 * values a simulator would put in an object update for that shape — so what the
 * test screen exercises is exactly the production path. Nothing here is ever
 * mixed into the real region's objects: the test screen replaces the world, it
 * does not add to it.
 */
object SLTestScene {

    /** The shape list, in the order they are displayed. */
    private class Sample(
        val label: String,
        val pcode: Int,
        val pathCurve: Int,
        val profileCurve: Int,
        val scale: Vector3,
        val profileBegin: Int = 0,
        val profileEnd: Int = 0,
        val profileHollow: Int = 0,
        val pathBegin: Int = 0,
        val pathEnd: Int = 0,
        val pathScaleX: Int = 100,
        val pathScaleY: Int = 100,
        val pathTaperX: Int = 0,
        val pathTaperY: Int = 0,
        val pathTwist: Int = 0,
        val pathShearX: Int = 0,
        val pathShearY: Int = 0,
        val pathRevolutions: Int = 0
    )

    /**
     * Path/profile codes are `LL_PCODE_*` (indra/llprimitive/llprimitive.h):
     * paths 0x10 line, 0x20 circle, 0x80 flexible; profiles 0x00 circle,
     * 0x01 square, 0x02 iso triangle, 0x03 equal triangle, 0x04 right triangle,
     * 0x05 half circle. A cut runs the path from 1/8 to 3/4 of its length.
     */
    private const val PATH_LINE = 0x10
    private const val PATH_CIRCLE = 0x20
    private const val PATH_FLEXIBLE = 0x80
    private const val PROFILE_CIRCLE = 0x00
    private const val PROFILE_SQUARE = 0x01
    private const val PROFILE_EQUALTRI = 0x03
    private const val PROFILE_CIRCLE_HALF = 0x05
    private const val CUT_BEGIN = 0x2000
    private const val CUT_END = 0xC000

    private val samples = listOf(
        Sample(
            label = "Cubo",
            pcode = SceneObject.PCODE_PRIM,
            pathCurve = PATH_LINE, profileCurve = PROFILE_SQUARE,
            scale = Vector3(1.4f, 1.4f, 1.4f)
        ),
        Sample(
            label = "Cilindro",
            pcode = SceneObject.PCODE_PRIM,
            pathCurve = PATH_LINE, profileCurve = PROFILE_CIRCLE,
            scale = Vector3(1.2f, 1.2f, 1.8f)
        ),
        Sample(
            label = "Prisma",
            pcode = SceneObject.PCODE_PRIM,
            pathCurve = PATH_LINE, profileCurve = PROFILE_EQUALTRI,
            scale = Vector3(1.5f, 1.5f, 1.5f)
        ),
        Sample(
            label = "Esfera",
            pcode = SceneObject.PCODE_PRIM,
            pathCurve = PATH_CIRCLE, profileCurve = PROFILE_CIRCLE_HALF,
            scale = Vector3(1.6f, 1.6f, 1.6f)
        ),
        Sample(
            label = "Toroide",
            pcode = SceneObject.PCODE_PRIM,
            pathCurve = PATH_CIRCLE, profileCurve = PROFILE_CIRCLE,
            pathScaleX = 50, pathScaleY = 50,
            scale = Vector3(1.6f, 1.6f, 1.6f)
        ),
        Sample(
            label = "Tubo",
            pcode = SceneObject.PCODE_PRIM,
            pathCurve = PATH_CIRCLE, profileCurve = PROFILE_SQUARE,
            pathScaleX = 50, pathScaleY = 50,
            scale = Vector3(1.6f, 1.6f, 1.2f)
        ),
        Sample(
            label = "Anillo",
            pcode = SceneObject.PCODE_PRIM,
            pathCurve = PATH_LINE, profileCurve = PROFILE_CIRCLE,
            profileHollow = 35000,
            scale = Vector3(1.8f, 1.8f, 1.0f)
        ),
        Sample(
            label = "Cubo cortado",
            pcode = SceneObject.PCODE_PRIM,
            pathCurve = PATH_LINE, profileCurve = PROFILE_SQUARE,
            pathBegin = CUT_BEGIN, pathEnd = CUT_END,
            pathTaperX = 40, pathTaperY = -40,
            scale = Vector3(1.5f, 1.5f, 1.5f)
        ),
        Sample(
            label = "Cilindro hueco",
            pcode = SceneObject.PCODE_PRIM,
            pathCurve = PATH_LINE, profileCurve = PROFILE_CIRCLE,
            profileHollow = 40000,
            scale = Vector3(1.6f, 1.6f, 1.6f)
        ),
        Sample(
            label = "Flexible",
            pcode = SceneObject.PCODE_PRIM,
            pathCurve = PATH_FLEXIBLE, profileCurve = PROFILE_CIRCLE,
            scale = Vector3(1.6f, 1.6f, 1.6f)
        )
    )

    val labels: List<String> get() = samples.map { it.label }

    /**
     * Builds the test objects: one upright and one tilted copy of every shape,
     * laid out in two rows so a single look at the screen shows whether shape
     * generation *and* rotation are right.
     */
    fun build(detail: Int = PrimGeometryNative.DETAIL_STANDARD): List<SLObject> {
        val objects = ArrayList<SLObject>(samples.size * 2)
        var localId = FIRST_LOCAL_ID
        for (row in 0..1) {
            var index = 0
            for (sample in samples) {
                val x = (index - (samples.size - 1) / 2f) * SPACING
                val y = row * ROW_SPACING
                val rotation = if (row == 0) {
                    Quaternion.IDENTITY
                } else {
                    // 60 degrees about X: half the angle is 30 degrees, so the
                    // quaternion is (sin 30, 0, 0, cos 30). A tilt this big makes
                    // a wrongly-wired rotation matrix obvious at a glance.
                    Quaternion(0.5f, 0f, 0f, 0.8660254f)
                }
                objects.add(objectOf(localId++, index, sample, Vector3(x, y, 0f), rotation, detail))
                index++
            }
        }
        return objects
    }

    /** The same objects wrapped as a world change set, for the scene to apply. */
    fun buildDelta(): SLWorldDelta {
        val objects = build()
        val region = SLRegion(
            handle = TEST_REGION_HANDLE,
            gridX = 0,
            gridY = 0,
            name = "Patron de prueba (Fase 1)",
            id = "",
            waterHeight = 20f
        )
        return SLWorldDelta(region, objects, emptyList(), emptyList(), 0, true)
    }

    private fun objectOf(
        localId: Int,
        sampleIndex: Int,
        sample: Sample,
        position: Vector3,
        rotation: Quaternion,
        detail: Int
    ): SLObject {
        val sceneObject = SceneObject(
            localId = localId,
            uuid = uuidFor(sampleIndex),
            pcode = sample.pcode,
            position = position,
            rotation = rotation,
            scale = sample.scale,
            pathCurve = sample.pathCurve,
            profileCurve = sample.profileCurve,
            pathBegin = sample.pathBegin,
            pathEnd = sample.pathEnd,
            pathScaleX = sample.pathScaleX,
            pathScaleY = sample.pathScaleY,
            pathShearX = sample.pathShearX,
            pathShearY = sample.pathShearY,
            pathTwist = sample.pathTwist,
            pathRadiusOffset = 0,
            pathTaperX = sample.pathTaperX,
            pathTaperY = sample.pathTaperY,
            pathRevolutions = sample.pathRevolutions,
            pathSkew = 0,
            profileBegin = sample.profileBegin,
            profileEnd = sample.profileEnd,
            profileHollow = sample.profileHollow,
            material = 3,
            textureId = uuidFor(sampleIndex),
            name = sample.label
        )
        sceneObject.positionKnown = true
        // These rows are built locally, not received from a region, so the
        // provenance says so instead of claiming an update carried them.
        sceneObject.noteShapeReceived(UpdateSource.LOCAL)
        sceneObject.revision = 1
        return SLObject.from(sceneObject, detail)
    }

    /**
     * An obviously-not-real UUID per *shape*, used as the prim's `TextureEntry`
     * default texture id, so the two rows of the same shape share one material
     * the way two real prims with the same texture would (and so a per-object
     * UUID does not accidentally hide a caching bug). It is only ever used as a
     * cache identity and a debug label.
     */
    private fun uuidFor(sampleIndex: Int): String =
        "00000000-0000-0000-0000-0000000000" + String.format("%02x", sampleIndex + 1)

    const val FIRST_LOCAL_ID = 900001
    const val TEST_REGION_HANDLE = -1L
    const val SPACING = 3.4f
    const val ROW_SPACING = 3.6f

    /** Where the test camera should look. */
    val CAMERA_FOCUS = floatArrayOf(0f, ROW_SPACING / 2f, 1.2f)
}
