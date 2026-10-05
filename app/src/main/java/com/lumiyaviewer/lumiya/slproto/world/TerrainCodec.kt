package com.lumiyaviewer.lumiya.slproto.world

/**
 * The simulator's terrain codec.
 *
 * `LayerData` patches are **not** raw heights: each one is a JPEG2000-style DCT
 * bitstream, bit-packed with no byte alignment. Getting this wrong is why the
 * world had no ground and no water — the old code read the blob as 16x16
 * little-endian 16-bit heights and rejected everything it saw.
 *
 * Layout of a `LayerData.Data` blob (all values bit-packed, MSB first inside
 * each byte):
 *
 * ```
 *   stride      : 16 bits   (264)
 *   patch size  :  8 bits   (16)
 *   layer type  :  8 bits   ('L' = land, 'W' = water, '7' = wind, '8' = cloud)
 *   repeated, until the type byte reads 97 (END_OF_PATCHES):
 *     quant wbits :  8 bits
 *     DC offset   : 32 bits   (float, little-endian byte order)
 *     range       : 16 bits
 *     patch ids   : 10 bits   (x = ids >> 5, y = ids & 0x1F)
 *     coefficients: 256 adaptive codes (see [decodeCoefficients])
 * ```
 *
 * Ported from libopenmetaverse's `TerrainCompressor` (the reference
 * implementation every third-party viewer uses) so the numbers are bit-for-bit
 * the same. The encoder is kept next to the decoder purely so the app can
 * round-trip a synthetic heightmap in the on-device self test.
 */
object TerrainCodec {

    const val PATCH_SIZE = 16
    const val PATCHES_PER_EDGE = 16
    const val END_OF_PATCHES = 97

    const val LAND = 0x4C
    const val WATER = 0x57
    const val WIND = 0x37
    const val CLOUD = 0x38

    /** One decoded 16x16 patch, in metres, with its position in the region. */
    class Patch(val x: Int, val y: Int, val heights: FloatArray)

    class Decoded(val layerType: Int, val stride: Int, val patchSize: Int, val patches: List<Patch>)

    class PatchInput(val x: Int, val y: Int, val heights: FloatArray)

    // ----------------------------------------------------------- the tables ---

    private const val OO_SQRT2 = 0.7071067811865475244008443621049f
    private const val STRIDE = 264
    private const val PREQUANT = 10

    private val dequantizeTable = FloatArray(256) {
        val i = it % 16
        val j = it / 16
        1f + 2f * (i + j)
    }

    private val quantizeTable = FloatArray(256) {
        val i = it % 16
        val j = it / 16
        1f / (1f + 2f * (i + j))
    }

    /** cosineTable[u * 16 + n] = cos((2n + 1) * u * pi / 2 / 16) */
    private val cosineTable = FloatArray(256) {
        val u = it / 16
        val n = it % 16
        kotlin.math.cos((2.0 * n + 1.0) * u * (Math.PI / 2.0 / 16.0)).toFloat()
    }

    /** [copyMatrix] maps a coefficient's natural index to its zig-zag position. */
    private val copyMatrix = IntArray(256)

    init {
        buildCopyMatrix()
    }

    private fun buildCopyMatrix() {
        var diag = false
        var right = true
        var i = 0
        var j = 0
        var count = 0
        while (i < 16 && j < 16) {
            copyMatrix[j * 16 + i] = count
            count += 1
            if (!diag) {
                if (right) {
                    if (i < 15) i += 1 else j += 1
                    right = false
                    diag = true
                } else {
                    if (j < 15) j += 1 else i += 1
                    right = true
                    diag = true
                }
            } else {
                if (right) {
                    i += 1
                    j -= 1
                    if (i == 15 || j == 0) diag = false
                } else {
                    i -= 1
                    j += 1
                    if (j == 15 || i == 0) diag = false
                }
            }
        }
    }

    // -------------------------------------------------------------- the bits ---

    /**
     * Reads the bitstream the way libopenmetaverse's `BitPack` does: bits are
     * consumed most-significant-first inside each byte, and a value longer than
     * eight bits is assembled from whole bytes taken little-endian.
     */
    private class BitReader(private val data: ByteArray) {

        private var bytePosition = 0
        private var bitPosition = 0

        var exhausted = false
            private set

        fun bits(count: Int): Int {
            var value = 0
            var remaining = count
            var byteIndex = 0
            while (remaining > 0) {
                val group = if (remaining > 8) 8 else remaining
                var current = 0
                var n = 0
                while (n < group) {
                    current = (current shl 1) or nextBit()
                    n += 1
                }
                value = value or (current shl (8 * byteIndex))
                byteIndex += 1
                remaining -= group
            }
            return value
        }

        fun float(): Float = Float.fromBits(bits(32))

        private fun nextBit(): Int {
            if (bytePosition >= data.size) {
                exhausted = true
                return 0
            }
            val bit = (data[bytePosition].toInt() shr (7 - bitPosition)) and 1
            bitPosition += 1
            if (bitPosition == 8) {
                bitPosition = 0
                bytePosition += 1
            }
            return bit
        }
    }

    private class BitWriter {
        private val out = ArrayList<Byte>(4096)
        private var current = 0
        private var bitPosition = 0

        fun bits(value: Int, count: Int) {
            var remaining = count
            var byteIndex = 0
            while (remaining > 0) {
                val group = if (remaining > 8) 8 else remaining
                val byteValue = (value ushr (8 * byteIndex)) and 0xFF
                var n = group - 1
                while (n >= 0) {
                    writeBit((byteValue ushr n) and 1)
                    n -= 1
                }
                byteIndex += 1
                remaining -= group
            }
        }

        fun float(value: Float) = bits(value.toRawBits(), 32)

        private fun writeBit(bit: Int) {
            current = (current shl 1) or bit
            bitPosition += 1
            if (bitPosition == 8) {
                out.add(current.toByte())
                current = 0
                bitPosition = 0
            }
        }

        fun toByteArray(): ByteArray {
            if (bitPosition != 0) {
                out.add(((current shl (8 - bitPosition)) and 0xFF).toByte())
                current = 0
                bitPosition = 0
            }
            val bytes = ByteArray(out.size)
            for (index in out.indices) {
                bytes[index] = out[index]
            }
            return bytes
        }
    }

    // ----------------------------------------------------------- decoding ---

    /**
     * Decodes a `LayerData.Data` blob. Returns null when nothing usable was
     * found, so the caller can log the raw shape instead of guessing.
     */
    fun decodeLayerData(data: ByteArray): Decoded? {
        if (data.size < 4) {
            return null
        }
        val reader = BitReader(data)
        val stride = reader.bits(16)
        val patchSize = reader.bits(8)
        val layerType = reader.bits(8)
        val patches = ArrayList<Patch>(PATCHES_PER_EDGE * PATCHES_PER_EDGE)
        while (true) {
            val quantWBits = reader.bits(8)
            if (quantWBits == END_OF_PATCHES || reader.exhausted) {
                break
            }
            val dcOffset = reader.float()
            val range = reader.bits(16)
            val patchIds = reader.bits(10)
            if (patchSize != PATCH_SIZE) {
                break
            }
            val coefficients = IntArray(PATCH_SIZE * PATCH_SIZE)
            decodeCoefficients(coefficients, reader, quantWBits)
            val heights = decompressPatch(coefficients, quantWBits, dcOffset, range)
            val x = patchIds ushr 5
            val y = patchIds and 0x1F
            if (x < PATCHES_PER_EDGE && y < PATCHES_PER_EDGE) {
                patches.add(Patch(x, y, heights))
            }
        }
        if (patches.isEmpty()) {
            return null
        }
        return Decoded(layerType, stride, patchSize, patches)
    }

    /**
     * Coefficients are coded adaptively: `0` = zero, `10` = end of block (the
     * rest are zero), `110` = positive, `111` = negative, each of the last two
     * followed by `wordBits` magnitude bits.
     */
    private fun decodeCoefficients(out: IntArray, reader: BitReader, quantWBits: Int) {
        val wordBits = (quantWBits and 0x0F) + 2
        for (n in out.indices) {
            if (reader.bits(1) == 0) {
                out[n] = 0
                continue
            }
            if (reader.bits(1) == 0) {
                for (o in n until out.size) {
                    out[o] = 0
                }
                return
            }
            val negative = reader.bits(1) != 0
            val magnitude = reader.bits(wordBits)
            out[n] = if (negative) -magnitude else magnitude
        }
    }

    /** Inverse DCT plus dequantisation: the coefficients back to metres. */
    private fun decompressPatch(
        coefficients: IntArray,
        quantWBits: Int,
        dcOffset: Float,
        range: Int
    ): FloatArray {
        val prequant = (quantWBits ushr 4) + 2
        val quantize = 1 shl prequant
        val reciprocal = 1f / quantize.toFloat()
        val mult = reciprocal * range.toFloat()
        val addValue = mult * (1 shl (prequant - 1)).toFloat() + dcOffset

        val block = FloatArray(256)
        for (n in 0 until 256) {
            block[n] = coefficients[copyMatrix[n]].toFloat() * dequantizeTable[n]
        }
        val temp = FloatArray(256)
        for (column in 0 until 16) {
            idctColumn(block, temp, column)
        }
        for (line in 0 until 16) {
            idctLine(temp, block, line)
        }
        val output = FloatArray(256)
        for (n in 0 until 256) {
            output[n] = block[n] * mult + addValue
        }
        return output
    }

    private fun idctColumn(input: FloatArray, output: FloatArray, column: Int) {
        for (n in 0 until 16) {
            var total = OO_SQRT2 * input[column]
            for (u in 1 until 16) {
                total += input[u * 16 + column] * cosineTable[u * 16 + n]
            }
            output[16 * n + column] = total
        }
    }

    private fun idctLine(input: FloatArray, output: FloatArray, line: Int) {
        val scale = 2f / 16f
        val lineSize = line * 16
        for (n in 0 until 16) {
            var total = OO_SQRT2 * input[lineSize]
            for (u in 1 until 16) {
                total += input[lineSize + u] * cosineTable[u * 16 + n]
            }
            output[lineSize + n] = total * scale
        }
    }

    // ----------------------------------------------------------- encoding ---

    /**
     * Encodes patches the same way the simulator does. Only used by the self
     * test: it lets the app prove on-device that the bit order, the DCT and the
     * zig-zag walk all agree with the decoder.
     */
    fun encodeLandPacket(inputs: List<PatchInput>): ByteArray {
        val writer = BitWriter()
        writer.bits(STRIDE, 16)
        writer.bits(PATCH_SIZE, 8)
        writer.bits(LAND, 8)
        for (input in inputs) {
            encodePatch(writer, input.heights, input.x, input.y)
        }
        writer.bits(END_OF_PATCHES, 8)
        return writer.toByteArray()
    }

    private fun encodePatch(writer: BitWriter, heights: FloatArray, x: Int, y: Int) {
        var minimum = Float.MAX_VALUE
        var maximum = -Float.MAX_VALUE
        for (height in heights) {
            if (height < minimum) minimum = height
            if (height > maximum) maximum = height
        }
        val range = ((maximum - minimum) + 1f).toInt()
        val coefficients = compressPatch(heights, minimum, range)
        val wordBits = chooseWordBits(coefficients)
        val quantWBits = (wordBits - 2) or ((PREQUANT - 2) shl 4)
        writer.bits(quantWBits and 0xFF, 8)
        writer.float(minimum)
        writer.bits(range, 16)
        writer.bits(((y and 0x1F) or (x shl 5)) and 0x3FF, 10)
        encodeCoefficients(writer, coefficients, wordBits)
    }

    /** Forward DCT plus quantisation: metres to coefficients. */
    private fun compressPatch(heights: FloatArray, dcOffset: Float, range: Int): IntArray {
        val rangeValue = (1 shl PREQUANT).toFloat()
        val reciprocal = if (range == 0) 0f else 1f / range.toFloat()
        val premult = reciprocal * rangeValue
        val subtract = (1 shl (PREQUANT - 1)).toFloat() + dcOffset * premult

        val block = FloatArray(256)
        for (n in 0 until 256) {
            block[n] = heights[n] * premult - subtract
        }
        val temp = FloatArray(256)
        val result = IntArray(256)
        for (line in 0 until 16) {
            dctLine(block, temp, line)
        }
        for (column in 0 until 16) {
            dctColumn(temp, result, column)
        }
        return result
    }

    private fun dctLine(input: FloatArray, output: FloatArray, line: Int) {
        val lineSize = line * 16
        var total = 0f
        for (n in 0 until 16) {
            total += input[lineSize + n]
        }
        output[lineSize] = OO_SQRT2 * total
        for (u in 1 until 16) {
            total = 0f
            for (n in 0 until 16) {
                total += input[lineSize + n] * cosineTable[u * 16 + n]
            }
            output[lineSize + u] = total
        }
    }

    private fun dctColumn(input: FloatArray, output: IntArray, column: Int) {
        val scale = 2f / 16f
        var total = 0f
        for (n in 0 until 16) {
            total += input[16 * n + column]
        }
        output[copyMatrix[column]] =
            (OO_SQRT2 * total * scale * quantizeTable[column]).toInt()
        for (u in 1 until 16) {
            total = 0f
            for (n in 0 until 16) {
                total += input[16 * n + column] * cosineTable[u * 16 + n]
            }
            output[copyMatrix[16 * u + column]] =
                (total * scale * quantizeTable[16 * u + column]).toInt()
        }
    }

    private fun chooseWordBits(coefficients: IntArray): Int {
        val minimum = PREQUANT shr 1
        val maximum = PREQUANT + 5
        var wordBits = minimum
        for (coefficient in coefficients) {
            var value = coefficient
            if (value == 0) {
                continue
            }
            if (value < 0) {
                value = -value
            }
            var bit = maximum
            while (bit > minimum) {
                if ((value and (1 shl bit)) != 0) {
                    if (bit > wordBits) {
                        wordBits = bit
                    }
                    break
                }
                bit -= 1
            }
        }
        return wordBits + 1
    }

    private fun encodeCoefficients(writer: BitWriter, coefficients: IntArray, wordBits: Int) {
        for (i in coefficients.indices) {
            var value = coefficients[i]
            if (value == 0) {
                var endOfBlock = true
                for (j in i until coefficients.size) {
                    if (coefficients[j] != 0) {
                        endOfBlock = false
                        break
                    }
                }
                if (endOfBlock) {
                    writer.bits(0x2, 2)
                    return
                }
                writer.bits(0x0, 1)
                continue
            }
            val limit = 1 shl wordBits
            if (value > limit) value = limit
            if (value < -limit) value = -limit
            if (value < 0) {
                writer.bits(0x7, 3)
                writer.bits(-value, wordBits)
            } else {
                writer.bits(0x6, 3)
                writer.bits(value, wordBits)
            }
        }
    }

    // ------------------------------------------------------------ self test ---

    /**
     * Encodes a synthetic region, decodes it again and reports the worst error.
     * A healthy codec comes back within a few centimetres; a broken bit order
     * or zig-zag walk returns metres of error (or garbage), which is exactly
     * what we want the Diagnostics screen to show.
     */
    fun roundTripSelfTest(): String {
        val side = PATCHES_PER_EDGE * PATCH_SIZE
        val heights = FloatArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) {
                heights[y * side + x] = 20f +
                    8f * kotlin.math.sin(x * 0.08f) * kotlin.math.cos(y * 0.06f) +
                    x * 0.05f
            }
        }
        val inputs = ArrayList<PatchInput>(PATCHES_PER_EDGE * PATCHES_PER_EDGE)
        for (py in 0 until PATCHES_PER_EDGE) {
            for (px in 0 until PATCHES_PER_EDGE) {
                val patch = FloatArray(PATCH_SIZE * PATCH_SIZE)
                for (row in 0 until PATCH_SIZE) {
                    for (column in 0 until PATCH_SIZE) {
                        patch[row * PATCH_SIZE + column] =
                            heights[(py * PATCH_SIZE + row) * side + px * PATCH_SIZE + column]
                    }
                }
                inputs.add(PatchInput(px, py, patch))
            }
        }
        val encoded = encodeLandPacket(inputs)
        val decoded = decodeLayerData(encoded)
            ?: return "FALLO: la capa codificada no se pudo decodificar"
        if (decoded.layerType != LAND) {
            return "FALLO: tipo de capa " + decoded.layerType
        }
        if (decoded.patches.size != inputs.size) {
            return "FALLO: " + decoded.patches.size + " de " + inputs.size + " parches"
        }
        var worst = 0f
        var samples = 0
        var sum = 0.0
        for (patch in decoded.patches) {
            val source = inputs[patch.y * PATCHES_PER_EDGE + patch.x].heights
            for (n in 0 until PATCH_SIZE * PATCH_SIZE) {
                val error = kotlin.math.abs(patch.heights[n] - source[n])
                if (error > worst) worst = error
                sum += error.toDouble()
                samples += 1
            }
        }
        val mean = (sum / samples).toFloat()
        val kilobytes = encoded.size / 1024
        if (worst > 1.0f) {
            return "FALLO: error maximo " + format(worst) + " m (" + format(mean) + " m de media)"
        }
        return "OK: " + decoded.patches.size + " parches, error maximo " + format(worst) +
            " m, medio " + format(mean) + " m, " + kilobytes + " KB"
    }

    private fun format(value: Float): String =
        String.format(java.util.Locale.US, "%.3f", value)
}
