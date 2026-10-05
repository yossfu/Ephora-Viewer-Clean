package com.lumiyaviewer.lumiya.slworld

import com.lumiyaviewer.lumiya.slproto.world.PrimShape
import com.lumiyaviewer.lumiya.slproto.world.PrimShapeClassifier
import com.lumiyaviewer.lumiya.slproto.world.SceneObject
import com.lumiyaviewer.lumiya.slscene.PrimGeometryNative

/**
 * One Second Life primitive, as the simulator described it.
 *
 * The values are kept exactly as they came off the wire — no clamping, no
 * "helpful" correction, no invented defaults — because the geometry generator
 * has to see what the region actually sent. The only derived things here are
 * the packed parameter array, which is what the native generator takes, and the
 * shape name, which is used for display and cache keys.
 *
 * This is an immutable snapshot: [SLWorld] creates a new one whenever an object
 * update arrives, so the render thread never sees an object change underneath
 * it.
 */
class SLPrimitive(
    val pathCurve: Int,
    val profileCurve: Int,
    val pathBegin: Int,
    val pathEnd: Int,
    val pathScaleX: Int,
    val pathScaleY: Int,
    val pathShearX: Int,
    val pathShearY: Int,
    val pathTwist: Int,
    val pathTwistBegin: Int,
    val pathRadiusOffset: Int,
    val pathTaperX: Int,
    val pathTaperY: Int,
    val pathRevolutions: Int,
    val pathSkew: Int,
    val profileBegin: Int,
    val profileEnd: Int,
    val profileHollow: Int,
    /** Tessellation level handed to the generator: 1 low, 3 the viewer's default. */
    val detail: Int
) {

    /** The `slcore::PrimParams` layout, packed once. */
    val params: IntArray = intArrayOf(
        pathCurve,
        profileCurve,
        pathBegin,
        pathEnd,
        pathScaleX,
        pathScaleY,
        pathShearX,
        pathShearY,
        pathTwist,
        pathTwistBegin,
        pathRadiusOffset,
        pathTaperX,
        pathTaperY,
        pathRevolutions,
        pathSkew,
        profileBegin,
        profileEnd,
        profileHollow,
        detail
    )

    /** Name of the shape this parameter pair produces; see [PrimShapeClassifier]. */
    val shape: PrimShape = PrimShapeClassifier.classify(pathCurve, profileCurve, profileHollow)

    /**
     * Identity of this *shape*. Scale is deliberately not part of it: a prim's
     * size lives in its transform, so every differently sized box shares one
     * mesh in the GPU cache.
     */
    val meshKey: Long = PrimGeometryNative.meshKey(params)

    /** Optional extra geometry for the legacy non-prim pcodes. */
    val isLegacyFixedShape: Boolean
        get() = shape == PrimShape.TREE || shape == PrimShape.GRASS

    override fun toString(): String = "SLPrimitive(" + shape + " detail=" + detail + ")"
}

/** Builds the snapshot from what the protocol layer decoded. */
object SLPrimitiveFactory {

    fun from(object_: SceneObject, detail: Int = PrimGeometryNative.DETAIL_STANDARD): SLPrimitive {
        return SLPrimitive(
            pathCurve = object_.pathCurve,
            profileCurve = object_.profileCurve,
            pathBegin = object_.pathBegin,
            pathEnd = object_.pathEnd,
            pathScaleX = object_.pathScaleX,
            pathScaleY = object_.pathScaleY,
            pathShearX = object_.pathShearX,
            pathShearY = object_.pathShearY,
            pathTwist = object_.pathTwist,
            pathTwistBegin = object_.pathTwistBegin,
            pathRadiusOffset = object_.pathRadiusOffset,
            pathTaperX = object_.pathTaperX,
            pathTaperY = object_.pathTaperY,
            pathRevolutions = object_.pathRevolutions,
            pathSkew = object_.pathSkew,
            profileBegin = object_.profileBegin,
            profileEnd = object_.profileEnd,
            profileHollow = object_.profileHollow,
            detail = detail
        )
    }
}
