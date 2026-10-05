package com.lumiyaviewer.lumiya.slproto.asset

import android.util.Log

sealed class J2KDecodeOutcome {
    class Ok(val texture: DecodedTexture) : J2KDecodeOutcome()
    class Fail(val stage: Int) : J2KDecodeOutcome()
}

object J2KDecoderNative {

    private const val TAG = "J2KDecoderNative"

    const val MAX_EDGE = 2048

    /** 1..9 nativo, 10 rechazo previo, 11 contrato/inesperado. */
    fun stageName(stage: Int): String = when (stage) {
        1 -> "codec-null"
        2 -> "setup"
        3 -> "stream-null"
        4 -> "header"
        5 -> "decode"
        6 -> "end-decompress"
        7 -> "dims"
        8 -> "comps"
        9 -> "pixels-alloc"
        10 -> "previo-nativo"
        11 -> "contrato"
        else -> "etapa" + stage
    }

    @Volatile
    var isAvailable: Boolean = false
        private set

    @Volatile
    var unavailableReason: String? = "not loaded"
        private set

    init {
        try {
            System.loadLibrary("slcore")
            val probe = try {
                nativeJ2KAvailable()
            } catch (error: UnsatisfiedLinkError) {
                null
            } catch (error: Throwable) {
                null
            }
            if (probe == true) {
                isAvailable = true
                unavailableReason = null
            } else {
                isAvailable = false
                unavailableReason = if (probe == null) "nativeJ2KAvailable sin enlazar" else "probe false"
            }
        } catch (error: Throwable) {
            isAvailable = false
            unavailableReason = error.message ?: error.javaClass.simpleName
            Log.w(TAG, "libslcore.so sin OpenJPEG: " + unavailableReason)
        }
    }

    private external fun nativeJ2KAvailable(): Boolean

    private external fun nativeDecodeJ2K(data: ByteArray, reduce: Int): Array<Any>?

    fun decodeDetailed(data: ByteArray, discardLevel: Int): J2KDecodeOutcome {
        if (!isAvailable || data.isEmpty() || data.size > (32 shl 20)) {
            return J2KDecodeOutcome.Fail(10)
        }
        val reduce = if (discardLevel < 0) 0 else if (discardLevel > 3) 3 else discardLevel
        val result = try {
            nativeDecodeJ2K(data, reduce)
        } catch (error: Throwable) {
            Log.w(TAG, "decode nativo fallo: " + (error.message ?: error.javaClass.simpleName))
            null
        } ?: return J2KDecodeOutcome.Fail(11)
        if (result.size == 1) {
            return J2KDecodeOutcome.Fail((result[0] as? Int) ?: 11)
        }
        if (result.size < 4) {
            return J2KDecodeOutcome.Fail(11)
        }
        val width = result[0] as? Int ?: return J2KDecodeOutcome.Fail(11)
        val height = result[1] as? Int ?: return J2KDecodeOutcome.Fail(11)
        val pixels = result[2] as? ByteArray ?: return J2KDecodeOutcome.Fail(11)
        val hasAlpha = result[3] as? Boolean ?: false
        if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE) {
            return J2KDecodeOutcome.Fail(7)
        }
        if (pixels.size != width * height * 4) {
            return J2KDecodeOutcome.Fail(9)
        }
        return J2KDecodeOutcome.Ok(DecodedTexture(width, height, pixels, hasAlpha = hasAlpha,
            discardLevel = discardLevel, format = DecodedTexture.FORMAT_RGBA8))
    }

    fun decodeToPixels(data: ByteArray, discardLevel: Int): DecodedTexture? {
        return when (val outcome = decodeDetailed(data, discardLevel)) {
            is J2KDecodeOutcome.Ok -> outcome.texture
            is J2KDecodeOutcome.Fail -> null
        }
    }
}

class OpenJpegTextureDecoder : TextureDecoder {

    override val name: String = "OpenJPEG J2K (fase 2.13c-recovery2)"

    override val isAvailable: Boolean get() = J2KDecoderNative.isAvailable

    private val okCount = java.util.concurrent.atomic.AtomicInteger()
    private val stageCounts = java.util.concurrent.atomic.AtomicIntegerArray(12)
    private val firstFailLock = Any()

    @Volatile
    private var firstFailText: String = ""

    override fun decode(asset: TextureAsset): DecodedTexture? {
        if (!J2KDecoderNative.isAvailable || asset.bytes.isEmpty()) {
            stageCounts.incrementAndGet(10)
            return null
        }
        val outcome = try {
            J2KDecoderNative.decodeDetailed(asset.bytes, asset.discardLevel)
        } catch (error: Throwable) {
            J2KDecodeOutcome.Fail(11)
        }
        return when (outcome) {
            is J2KDecodeOutcome.Ok -> {
                okCount.incrementAndGet()
                outcome.texture
            }
            is J2KDecodeOutcome.Fail -> {
                recordFail(asset, outcome.stage)
                null
            }
        }
    }

    private fun recordFail(asset: TextureAsset, stage: Int) {
        if (stage in 0..11) {
            stageCounts.incrementAndGet(stage)
        }
        if (firstFailText.isEmpty()) {
            synchronized(firstFailLock) {
                if (firstFailText.isEmpty()) {
                    val magic = asset.bytes.take(4).joinToString("") { bytes ->
                        "0123456789abcdef"[(bytes.toInt() ushr 4) and 0xF].toString() +
                            "0123456789abcdef"[bytes.toInt() and 0xF]
                    }
                    firstFailText = asset.textureId.take(8) + " " + asset.bytes.size +
                        "B magia=" + (if (magic.isEmpty()) "-" else magic) +
                        " etapa=" + stage + "(" + J2KDecoderNative.stageName(stage) + ")"
                }
            }
        }
    }

    fun diagnosticLine(): String {
        val parts = StringBuilder()
        for (stage in 1..11) {
            val count = stageCounts.get(stage)
            if (count > 0) {
                if (parts.isNotEmpty()) {
                    parts.append(" ")
                }
                parts.append(J2KDecoderNative.stageName(stage)).append(":").append(count)
            }
        }
        return "j2k etapas: ok " + okCount.get() +
            (if (parts.isEmpty()) "" else " · fallo(" + parts + ")") +
            (if (firstFailText.isEmpty()) "" else " · primera: " + firstFailText)
    }

    override fun toString(): String =
        "OpenJpegTextureDecoder(disponible=" + isAvailable + ")"
}
