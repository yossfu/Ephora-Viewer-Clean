package com.lumiyaviewer.lumiya.slproto.circuit

object PacketFlags {
    const val APPENDED_ACKS = 0x10
    const val RESENT = 0x20
    const val RELIABLE = 0x40
    const val ZEROCODED = 0x80
}

internal object Bytes {

    fun readU32Be(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    fun writeU32Be(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }
}
