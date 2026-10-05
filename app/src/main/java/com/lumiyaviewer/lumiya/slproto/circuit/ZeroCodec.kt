package com.lumiyaviewer.lumiya.slproto.circuit

import java.io.ByteArrayOutputStream

/**
 * Second Life zero-run compression. The first six bytes of a packet (flags,
 * sequence and the header pad byte) are copied verbatim; runs of 0x00 are
 * written as 0x00 followed by the run length. Appended ACKs are never
 * compressed, which is why the encoder stops at `bodyLen` and copies the rest.
 */
object ZeroCodec {

    fun decode(src: ByteArray, srcLen: Int): ByteArray {
        val out = ByteArrayOutputStream(srcLen + 32)
        val head = if (srcLen < 6) srcLen else 6
        out.write(src, 0, head)
        var i = 6
        while (i < srcLen) {
            val value = src[i].toInt() and 0xFF
            if (value == 0) {
                if (i + 1 >= srcLen) {
                    break
                }
                val runLength = src[i + 1].toInt() and 0xFF
                for (j in 0 until runLength) {
                    out.write(0)
                }
                i += 2
            } else {
                out.write(value)
                i += 1
            }
        }
        return out.toByteArray()
    }

    fun encode(src: ByteArray, srcLen: Int): ByteArray {
        val out = ByteArrayOutputStream(srcLen + 32)
        val head = if (srcLen < 6) srcLen else 6
        out.write(src, 0, head)

        var bodyLen = srcLen
        if (srcLen >= 1 && (src[0].toInt() and PacketFlags.APPENDED_ACKS) != 0 && srcLen >= 1) {
            val ackCount = src[srcLen - 1].toInt() and 0xFF
            bodyLen = srcLen - ackCount * 4 - 1
        }

        var zeroCount = 0
        var i = 6
        while (i < bodyLen) {
            val value = src[i].toInt() and 0xFF
            if (value == 0) {
                zeroCount += 1
                if (zeroCount == 255) {
                    out.write(0)
                    out.write(255)
                    zeroCount = 0
                }
            } else {
                if (zeroCount > 0) {
                    out.write(0)
                    out.write(zeroCount)
                    zeroCount = 0
                }
                out.write(value)
            }
            i += 1
        }
        if (zeroCount > 0) {
            out.write(0)
            out.write(zeroCount)
        }

        var j = bodyLen
        while (j < srcLen) {
            out.write(src[j].toInt() and 0xFF)
            j += 1
        }
        return out.toByteArray()
    }
}
