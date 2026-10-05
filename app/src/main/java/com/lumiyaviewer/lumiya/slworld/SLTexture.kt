package com.lumiyaviewer.lumiya.slworld

import com.lumiyaviewer.lumiya.renderer.MeshDesc
import com.lumiyaviewer.lumiya.slproto.world.SceneObject
import com.lumiyaviewer.lumiya.slproto.world.TextureEntry
import com.lumiyaviewer.lumiya.slproto.world.TextureEntryFace
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Base de proyeccion planar en espacio objeto: binormal (eje U) y tangente
 * (eje V), vectores unitarios alineados a ejes. Regla exacta de
 * `planarProjection` (llface.cpp) aplicada a la normal media del grupo de
 * caras: exacta en caras planas (muros, suelos), aproximacion en curvas.
 */
class PlanarBasis(val u: FloatArray, val v: FloatArray)

/**
 * A face's texture information, in Second Life's own terms.
 *
 * Second Life's `TextureEntry` is per face, and every face can carry its own
 * texture, tint, alpha, UV repeat/offset and rotation (see `LLTextureEntry` in
 * the official viewer). Since fase 2.13a the whole entry is decoded
 * (`slproto.world.TextureEntry`, eleven packed fields) and this class is the
 * *scene-side* record of one face: the same data with the tint already in linear
 * space, which is what Filament wants.
 *
 * Colours are stored linear (Filament multiplies `baseColor` by an sRGB-decoded
 * texture sample, so the tint has to be linear to match). The wire order of the
 * four colour bytes is RGBA, each one stored as `255 - value`.
 */
class SLTextureFace(
    val textureId: String,
    /** Linear RGBA. */
    val color: FloatArray,
    val repeatU: Float = 1f,
    val repeatV: Float = 1f,
    val offsetU: Float = 0f,
    val offsetV: Float = 0f,
    /** Rotation in radians, counter-clockwise. */
    val rotation: Float = 0f,
    val fullBright: Boolean = false,
    /** Legacy bump-map code (0 = none). */
    val bump: Int = 0,
    /** 0 = none, 1 = low, 2 = medium, 3 = high (`SSFBBBBB` bits 6..7). */
    val shiny: Int = 0,
    /** `TEM_*` media bits; 0 = no media. */
    val mediaFlags: Int = 0,
    /** 0..1. */
    val glow: Float = 0f,
    /** Render-material UUID, all-zero when the face has none. */
    val materialId: String = DEFAULT_UUID,
    /**
     * Modo de mapping SL (lltextureentry.h, bits 1..2 de mediaFlags):
     * 0 = default (UV de la geometria), 1 = planar, 2 = esferico,
     * 3 = cilindrico. El renderer actual implementa de forma exacta el camino
     * DEFAULT y PLANAR; los otros dos conservan su modo wire pero no se fuerzan
     * a PLANAR porque eso seria una proyeccion incorrecta.
     */
    val texGen: Int = 0,
    /** Valor `texGen` tal cual llego del wire (0..3); solo diagnostico. */
    val texGenWire: Int = 0
) {

    val hasTint: Boolean
        get() = color[0] < 0.999f || color[1] < 0.999f || color[2] < 0.999f || color[3] < 0.999f

    val alpha: Float get() = color[3]

    val hasUvTransform: Boolean
        get() = repeatU != 1f || repeatV != 1f || offsetU != 0f || offsetV != 0f || rotation != 0f

    val hasTexture: Boolean get() = textureId.isNotEmpty() && textureId != DEFAULT_UUID

    /** True solo para TEX_GEN_PLANAR; spherical/cylindrical no se aproximan a planar. */
    val isPlanar: Boolean get() = texGen == 1

    companion object {
        const val DEFAULT_UUID = "00000000-0000-0000-0000-000000000000"

        val WHITE = floatArrayOf(1f, 1f, 1f, 1f)

        /**
         * The face a material falls back to when the mesh reports no face groups
         * at all (`faceCount` 0). It is the "no texture, no tint" face: white
         * with the default UUID, so the material path cannot be handed a
         * half-built face.
         */
        val DEFAULT_FACE = SLTextureFace(DEFAULT_UUID, WHITE)

        /**
         * The object's face 0: the decoded `TextureEntry` when the region sent
         * one, otherwise the scalar fields the decoder kept for compatibility.
         */
        fun fromSceneObject(object_: SceneObject): SLTextureFace {
            val entry = object_.textureEntry
            if (entry != null) {
                return fromFace(entry.face(0))
            }
            return SLTextureFace(
                textureId = object_.textureId.ifEmpty { DEFAULT_UUID },
                color = argbToLinear(object_.textureColor),
                fullBright = object_.fullBright
            )
        }

        /** One decoded wire face, converted to the scene's terms. */
        fun fromFace(face: TextureEntryFace): SLTextureFace = SLTextureFace(
            textureId = face.textureId.ifEmpty { DEFAULT_UUID },
            color = argbToLinear(face.colorArgb),
            repeatU = face.scaleU,
            repeatV = face.scaleV,
            offsetU = face.offsetU,
            offsetV = face.offsetV,
            rotation = face.rotation,
            fullBright = face.fullBright,
            bump = face.bumpCode,
            shiny = face.shiny,
            mediaFlags = face.mediaFlags,
            glow = face.glow,
            materialId = face.materialId,
            // Preserve the actual wire mode. Only 1 (planar) maps to the
            // planar shader path; 2/3 are left as their own modes so they can
            // be implemented later without having silently rendered as planar.
            texGen = face.texGen,
            texGenWire = face.texGen
        )

        /** Every face of an entry the mesh actually draws, in face-index order. */
        fun facesOf(entry: TextureEntry?, faceCount: Int): List<SLTextureFace> {
            if (entry == null) {
                return emptyList()
            }
            val count = if (faceCount > 0) faceCount else 1
            return (0 until count).map { fromFace(entry.face(it)) }
        }

        /** `0xAARRGGBB` in sRGB, as the decoder stores it, to linear RGBA. */
        fun argbToLinear(argb: Int): FloatArray {
            val a = ((argb ushr 24) and 0xFF).toFloat() / 255f
            val r = ((argb ushr 16) and 0xFF).toFloat() / 255f
            val g = ((argb ushr 8) and 0xFF).toFloat() / 255f
            val b = (argb and 0xFF).toFloat() / 255f
            return floatArrayOf(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b), a)
        }

        fun srgbToLinear(value: Float): Float {
            return if (value <= 0.04045f) {
                value / 12.92f
            } else {
                ((value + 0.055f) / 1.055f).pow(2.4f)
            }
        }

        /**
         * Base planar de un grupo de caras (llface.cpp `planarProjection`):
         * promedia las normales del grupo, normaliza y deriva binormal y
         * tangente con la misma regla del visor oficial. Null cuando el grupo
         * no da una normal util (la cara cae a mapeado default).
         */
        fun planarBasisFor(desc: MeshDesc, group: Int): PlanarBasis? {
            if (group < 0 || group >= desc.faceCount) return null
            val stride = MeshDesc.VERTEX_FLOATS
            val first = desc.faceFirstIndexAt(group)
            val count = desc.faceIndexCountAt(group)
            if (first < 0 || count <= 0 || first + count > desc.indices.size) return null
            var nx = 0f
            var ny = 0f
            var nz = 0f
            var n = 0
            for (k in 0 until count) {
                val vi = desc.indices[first + k]
                val o = vi * stride + 3
                if (vi < 0 || o + 2 >= desc.vertices.size) return null
                nx += desc.vertices[o]
                ny += desc.vertices[o + 1]
                nz += desc.vertices[o + 2]
                n += 1
            }
            if (n == 0) return null
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-6f) return null
            nx /= len
            ny /= len
            nz /= len
            val bu: FloatArray
            val bv: FloatArray
            if (nx >= 0.5f || nx <= -0.5f) {
                bu = if (nx < 0f) floatArrayOf(0f, -1f, 0f) else floatArrayOf(0f, 1f, 0f)
            } else {
                bu = if (ny > 0f) floatArrayOf(-1f, 0f, 0f) else floatArrayOf(1f, 0f, 0f)
            }
            bv = floatArrayOf(
                bu[1] * nz - bu[2] * ny,
                bu[2] * nx - bu[0] * nz,
                bu[0] * ny - bu[1] * nx
            )
            return PlanarBasis(bu, bv)
        }

        /**
         * 2.27: la base planar en espacio objeto ESCALADO. El shader proyecta
         * con la posicion pre-transform (`vObjPos`: la escala vive en el
         * transform del renderable, no en los vertices), asi que
         * dot(B, P) == dot(B*S, P_escalado) solo si la base absorbe la escala
         * del objeto por componentes. Sin esto un muro de 10 m mapeaba igual
         * que un cubo de 1 m. El espejo CPU (`planarSampleUv`) ya escalaba la
         * posicion; ahora ambos coinciden. `uvKeyOf` ya incluye los floats de
         * la base, asi que escalas distintas siguen generando materiales
         * distintos sin tocar el shader ni reconstruir la malla.
         */
        fun scaledPlanarBasis(basis: PlanarBasis, scale: FloatArray): PlanarBasis {
            val sx = scale.getOrElse(0) { 1f }
            val sy = scale.getOrElse(1) { 1f }
            val sz = scale.getOrElse(2) { 1f }
            return PlanarBasis(
                floatArrayOf(basis.u[0] * sx, basis.u[1] * sy, basis.u[2] * sz),
                floatArrayOf(basis.v[0] * sx, basis.v[1] * sy, basis.v[2] * sz)
            )
        }

        /**
         * Espejo CPU del `xform` oficial (llface.cpp) y del shader: centro
         * 0.5, rotacion antihoraria, escala (repeat, con signo = flip) y
         * offset. Solo para la muestra del informe; el render usa el shader.
         * 2.26: devuelve el UV en el convenio de muestreo de Filament
         * (V volteada respecto al `xform` oficial), igual que el shader.
         */
        fun xformUv(u: Float, v: Float, face: SLTextureFace): Pair<Float, Float> {
            var s = u - 0.5f
            var t = v - 0.5f
            val c = cos(face.rotation)
            val si = sin(face.rotation)
            val rs = s * c + t * si
            val rt = -s * si + t * c
            val slU = rs * face.repeatU + face.offsetU + 0.5f
            val slV = rt * face.repeatV + face.offsetV + 0.5f
            return Pair(slU, 1f - slV)
        }

        /**
         * Espejo CPU de `planarProjection` (llface.cpp) + `xform`, para la
         * muestra del informe. `pos` = posicion en espacio objeto (metros).
         */
        fun planarSampleUv(basis: PlanarBasis, pos: FloatArray, face: SLTextureFace): Pair<Float, Float> {
            val pu = 2f * (basis.u[0] * pos[0] + basis.u[1] * pos[1] + basis.u[2] * pos[2]) + 0.5f
            val pv = 0.5f - 2f * (basis.v[0] * pos[0] + basis.v[1] * pos[1] + basis.v[2] * pos[2])
            return xformUv(pu, pv, face)
        }

        fun rot00(u: Float, v: Float, rot: Float): Pair<Float, Float> {
            val c = cos(rot)
            val si = sin(rot)
            return Pair(u * c + v * si, -u * si + v * c)
        }

        fun rot05(u: Float, v: Float, rot: Float): Pair<Float, Float> {
            val c = cos(rot)
            val si = sin(rot)
            val s = u - 0.5f
            val t = v - 0.5f
            return Pair(s * c + t * si + 0.5f, -s * si + t * c + 0.5f)
        }

        fun vFlip(uv: Pair<Float, Float>): Pair<Float, Float> = Pair(uv.first, 1f - uv.second)

        fun variantA(u: Float, v: Float, face: SLTextureFace): Pair<Float, Float> {
            val r = Pair(u * face.repeatU + face.offsetU, v * face.repeatV + face.offsetV)
            return rot00(r.first, r.second, face.rotation)
        }

        fun variantB(u: Float, v: Float, face: SLTextureFace): Pair<Float, Float> {
            val r = rot00(u * face.repeatU, v * face.repeatV, face.rotation)
            return Pair(r.first + face.offsetU, r.second + face.offsetV)
        }

        fun variantC(u: Float, v: Float, face: SLTextureFace): Pair<Float, Float> {
            val r = rot00(u, v, face.rotation)
            return Pair(r.first * face.repeatU + face.offsetU, r.second * face.repeatV + face.offsetV)
        }

        fun variantD(u: Float, v: Float, face: SLTextureFace): Pair<Float, Float> {
            val r = rot05(u, v, face.rotation)
            return Pair(r.first * face.repeatU + face.offsetU, r.second * face.repeatV + face.offsetV)
        }

        fun variantE(u: Float, v: Float, face: SLTextureFace): Pair<Float, Float> {
            val r = Pair(u * face.repeatU + face.offsetU, v * face.repeatV + face.offsetV)
            return rot05(r.first, r.second, face.rotation)
        }

        fun slReferenceUv(u: Float, v: Float, face: SLTextureFace): Pair<Float, Float> {
            val tx = u - 0.5f
            val ty = v - 0.5f
            val c = cos(face.rotation)
            val si = sin(face.rotation)
            val x = (tx * c + ty * si) * face.repeatU + face.offsetU + 0.5f
            val y = (-tx * si + ty * c) * face.repeatV + face.offsetV + 0.5f
            return Pair(x, y)
        }
    }
}
