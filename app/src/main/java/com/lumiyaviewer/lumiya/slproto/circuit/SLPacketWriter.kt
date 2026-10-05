package com.lumiyaviewer.lumiya.slproto.circuit

import com.lumiyaviewer.lumiya.slproto.messages.Frequency
import com.lumiyaviewer.lumiya.slproto.messages.MessageDef

/**
 * Assembles a wire packet: 6 byte header prefix (flags, sequence, pad), the
 * message id (1, 2 or 4 bytes depending on frequency) and the encoded body.
 */
object SLPacketWriter {

    fun build(sequence: Int, def: MessageDef, body: ByteArray, reliable: Boolean): ByteArray {
        val idLength = when (def.frequency) {
            Frequency.HIGH -> 1
            Frequency.MEDIUM -> 2
            Frequency.LOW -> 4
        }
        val headerLength = 6 + idLength
        val packet = ByteArray(headerLength + body.size)

        var flags = 0
        if (reliable) {
            flags = flags or PacketFlags.RELIABLE
        }
        if (def.zerocoded) {
            flags = flags or PacketFlags.ZEROCODED
        }
        packet[0] = flags.toByte()
        Bytes.writeU32Be(packet, 1, sequence)
        packet[5] = 0
        when (def.frequency) {
            Frequency.HIGH -> packet[6] = def.id.toByte()
            Frequency.MEDIUM -> {
                packet[6] = 0xFF.toByte()
                packet[7] = def.id.toByte()
            }
            Frequency.LOW -> {
                packet[6] = 0xFF.toByte()
                packet[7] = 0xFF.toByte()
                packet[8] = ((def.id shr 8) and 0xFF).toByte()
                packet[9] = (def.id and 0xFF).toByte()
            }
        }
        System.arraycopy(body, 0, packet, headerLength, body.size)
        return packet
    }
}
