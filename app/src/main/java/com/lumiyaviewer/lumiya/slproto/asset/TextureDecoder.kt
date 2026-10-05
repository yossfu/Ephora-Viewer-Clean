package com.lumiyaviewer.lumiya.slproto.asset

/**
 * A texture turned into pixels: what the renderer can actually upload.
 *
 * The pixel format is deliberately the one every backend can take without a
 * conversion layer — 8-bit RGBA, first row first, tightly packed — and it is
 * the format OpenJPEG is asked for in fase 2.13c. [format] names it so a report
 * can never claim a layout the bytes do not have.
 *
 * Nothing here knows about Filament: turning this into a [com.lumiyaviewer.lumiya.renderer.TextureDesc]
 * is the scene's job, which is what keeps the asset layer free of renderer
 * types.
 */
class DecodedTexture(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val hasAlpha: Boolean = false,
    /** The discard level this image was decoded from; part of its identity. */
    val discardLevel: Int = 0,
    val format: String = FORMAT_RGBA8
) {

    val size: Int get() = pixels.size

    /** 4 for RGBA8, 0 when the dimensions do not describe the buffer. */
    val bytesPerPixel: Int
        get() = if (width <= 0 || height <= 0) 0 else pixels.size / (width * height)

    /** True when [pixels] holds exactly `width * height * 4` bytes. */
    val isConsistent: Boolean
        get() = width > 0 && height > 0 && pixels.size == width * height * 4

    val megapixels: Double get() = (width.toDouble() * height.toDouble()) / 1_000_000.0

    override fun toString(): String = "DecodedTexture(" + width + "x" + height + ", " +
        format + ", " + pixels.size + " bytes" + (if (hasAlpha) ", alfa" else "") + ")"

    companion object {
        const val FORMAT_RGBA8 = "RGBA8"
    }
}

/**
 * The JPEG2000 seam.
 *
 * Every other stage of the texture pipeline is plain Kotlin and is already in
 * place: the bytes arrive through [TextureAsset], the cache keys them, the
 * streamer uploads the pixels. The decoder is the one stage that needs native
 * code (OpenJPEG through the NDK in fase 2.13c), so it is behind this interface
 * with exactly two operations: whether it can decode at all, and decode.
 *
 * [decode] runs on the pipeline's own decode threads — never on the render
 * thread and never on a network thread — and returns `null` on any failure,
 * including "this is not a codestream". It must not throw: a texture that
 * cannot be decoded is a texture the viewer draws with its fallback colour, not
 * a reason to lose a frame.
 */
interface TextureDecoder {

    /** Human readable name, for the report. */
    val name: String

    /**
     * False when no decoder is linked in yet. The pipeline reads this to decide
     * between *queueing* the asset for a later decode and *failing* it, which is
     * the difference between "the grid has not answered" and "we cannot read
     * this".
     */
    val isAvailable: Boolean

    /** Decodes one asset, or returns null. Blocking, off the render thread. */
    fun decode(asset: TextureAsset): DecodedTexture?
}

/**
 * The decoder that ships in fase 2.13b: none.
 *
 * It says so instead of pretending: [isAvailable] is false, so every asset that
 * arrives is kept in the cache and *waited for* rather than counted as a
 * decode failure. That is what makes "bytes arrived" (which 2.13b proves) and
 * "pixels exist" (which 2.13c proves) two separately visible facts, and it is
 * why turning OpenJPEG on later re-decodes everything already downloaded
 * instead of re-downloading it.
 */
class UnavailableTextureDecoder : TextureDecoder {

    override val name: String = "ausente (fase 2.13c: OpenJPEG)"

    override val isAvailable: Boolean = false

    override fun decode(asset: TextureAsset): DecodedTexture? = null
}

/**
 * A decoder that invents pixels instead of decoding them.
 *
 * It is **not** a JPEG2000 decoder and the report labels it as synthetic: it
 * builds a deterministic RGBA image from the texture's UUID so the *rest* of the
 * pipeline — decode queue, decode timing, GPU upload, material rebind, per-face
 * binding — can be driven and measured end to end on the device before OpenJPEG
 * exists. Its output is obviously not Second Life data (a uuid-derived
 * checkerboard with a diagonal), which is the point: it can never be mistaken
 * for a real texture in a screenshot.
 *
 * [size] is the edge of the square image in pixels; the default keeps a full
 * region's worth of these cheap enough to be a smoke test.
 */
class SyntheticTextureDecoder(val size: Int = 64) : TextureDecoder {

    override val name: String = "sintetico " + size + "x" + size + " (NO es JPEG2000)"

    override val isAvailable: Boolean = true

    override fun decode(asset: TextureAsset): DecodedTexture {
        val edge = if (size < 1) 1 else size
        val pixels = ByteArray(edge * edge * 4)
        // A stable hash of the UUID: two faces sharing a texture get identical
        // pixels (as they would with a real decoder) and different textures do
        // not, which is what makes it usable to check material reuse.
        var hash = 0x811C9DC5.toInt()
        for (character in asset.textureId) {
            hash = (hash xor character.code) * 16777619
        }
        val red = (hash ushr 16) and 0xFF
        val green = (hash ushr 8) and 0xFF
        val blue = hash and 0xFF
        for (y in 0 until edge) {
            for (x in 0 until edge) {
                val at = (y * edge + x) * 4
                // Checkerboard plus a diagonal, so scale, repeat and rotation
                // applied by the material are visible in a screenshot.
                val tile = ((x / 8) + (y / 8)) and 1
                val diagonal = if (x / 8 == y / 8) 40 else 0
                pixels[at] = clamp(red / 2 + tile * 90 + diagonal).toByte()
                pixels[at + 1] = clamp(green / 2 + (1 - tile) * 90 + diagonal).toByte()
                pixels[at + 2] = clamp(blue / 2 + tile * 60).toByte()
                pixels[at + 3] = 0xFF.toByte()
            }
        }
        return DecodedTexture(edge, edge, pixels, hasAlpha = false, discardLevel = asset.discardLevel, format = "RGBA8 sintetico")
    }

    private fun clamp(value: Int): Int = if (value < 0) 0 else if (value > 255) 255 else value

    override fun toString(): String = "SyntheticTextureDecoder(" + size + "x" + size + ")"
}
