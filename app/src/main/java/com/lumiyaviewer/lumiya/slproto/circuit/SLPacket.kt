package com.lumiyaviewer.lumiya.slproto.circuit

import com.lumiyaviewer.lumiya.slproto.messages.Frequency
import com.lumiyaviewer.lumiya.slproto.messages.MessageDef
import com.lumiyaviewer.lumiya.slproto.messages.MessageTemplate

class PacketHeader(
    val sequence: Int,
    val messageId: Int,
    val frequency: Frequency,
    val reliable: Boolean,
    val resent: Boolean,
    val zerocoded: Boolean,
    val appendedAcks: Boolean
)

class ParsedPacket(
    val header: PacketHeader,
    val def: MessageDef?,
    val data: ByteArray,
    val bodyOffset: Int,
    val bodyLength: Int,
    val acks: IntArray
)

object SLPacket {

    fun parse(raw: ByteArray, length: Int): ParsedPacket? {
        if (length < 7) {
            return null
        }

        val flags = raw[0].toInt() and 0xFF
        var bodyEnd = length
        var acks = IntArray(0)

        if ((flags and PacketFlags.APPENDED_ACKS) != 0) {
            val count = raw[length - 1].toInt() and 0xFF
            if (count > 0) {
                bodyEnd = length - 1 - count * 4
                if (bodyEnd < 6) {
                    return null
                }
                acks = IntArray(count)
                for (i in 0 until count) {
                    val offset = length - 1 - (i + 1) * 4
                    acks[i] = Bytes.readU32Be(raw, offset)
                }
            } else {
                bodyEnd = length - 1
            }
        }

        val data: ByteArray
        val dataLength: Int
        if ((flags and PacketFlags.ZEROCODED) != 0) {
            data = ZeroCodec.decode(raw, bodyEnd)
            dataLength = data.size
        } else {
            data = raw
            dataLength = bodyEnd
        }
        if (dataLength < 7) {
            return null
        }

        val sequence = Bytes.readU32Be(data, 1)
        val idByte = data[6].toInt() and 0xFF
        val frequency: Frequency
        val messageId: Int
        val headerLength: Int

        if (idByte == 0xFF) {
            if (dataLength > 7 && (data[7].toInt() and 0xFF) == 0xFF) {
                if (dataLength < 10) {
                    return null
                }
                frequency = Frequency.LOW
                messageId = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
                headerLength = 10
            } else {
                if (dataLength < 8) {
                    return null
                }
                frequency = Frequency.MEDIUM
                messageId = data[7].toInt() and 0xFF
                headerLength = 8
            }
        } else {
            frequency = Frequency.HIGH
            messageId = idByte
            headerLength = 7
        }

        val header = PacketHeader(
            sequence = sequence,
            messageId = messageId,
            frequency = frequency,
            reliable = (flags and PacketFlags.RELIABLE) != 0,
            resent = (flags and PacketFlags.RESENT) != 0,
            zerocoded = (flags and PacketFlags.ZEROCODED) != 0,
            appendedAcks = (flags and PacketFlags.APPENDED_ACKS) != 0
        )

        return ParsedPacket(
            header = header,
            def = MessageTemplate.byId(frequency, messageId),
            data = data,
            bodyOffset = headerLength,
            bodyLength = dataLength - headerLength,
            acks = acks
        )
    }
}
