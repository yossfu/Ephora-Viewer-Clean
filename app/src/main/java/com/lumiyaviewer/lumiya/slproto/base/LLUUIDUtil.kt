package com.lumiyaviewer.lumiya.slproto.base

import java.util.UUID

object LLUUIDUtil {

    private const val HEX = "0123456789abcdef"

    const val ZERO = "00000000-0000-0000-0000-000000000000"

    fun random(): String = UUID.randomUUID().toString()

    fun fromBytes(bytes: ByteArray, offset: Int = 0): String {
        val builder = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) {
                builder.append('-')
            }
            val value = bytes[offset + i].toInt() and 0xFF
            builder.append(HEX[value shr 4])
            builder.append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    fun toBytes(uuid: String, out: ByteArray, offset: Int = 0) {
        var index = 0
        var i = 0
        while (i + 1 < uuid.length && index < 16) {
            val c = uuid[i]
            if (c == '-') {
                i += 1
                continue
            }
            val hi = digit(c)
            val lo = digit(uuid[i + 1])
            out[offset + index] = ((hi shl 4) or lo).toByte()
            index += 1
            i += 2
        }
        while (index < 16) {
            out[offset + index] = 0
            index += 1
        }
    }

    fun toBytes(uuid: String): ByteArray {
        val out = ByteArray(16)
        toBytes(uuid, out, 0)
        return out
    }

    private fun digit(c: Char): Int {
        return when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> 0
        }
    }
}
