package com.lumiyaviewer.lumiya.slproto.messages

enum class Frequency { HIGH, MEDIUM, LOW }

class FieldDef(val name: String, val type: FieldType, val extra: Int)

enum class BlockRepeat { SINGLE, MULTIPLE, VARIABLE }

class BlockDef(
    val name: String,
    val repeat: BlockRepeat,
    val count: Int,
    val fields: List<FieldDef>
)

class MessageDef(
    val name: String,
    val frequency: Frequency,
    val id: Int,
    val zerocoded: Boolean,
    val trusted: Boolean,
    val blocks: List<BlockDef>
)

/**
 * Parser for Linden Lab's `message_template.msg` (version 2.0), the same file the
 * official viewer and Lumiya use to describe every UDP message on the wire.
 */
object MessageTemplate {

    private val byFrequency = HashMap<Frequency, HashMap<Int, MessageDef>>()
    private val byName = HashMap<String, MessageDef>()

    var loadedCount: Int = 0
        private set

    fun byId(frequency: Frequency, id: Int): MessageDef? {
        val map = byFrequency[frequency] ?: return null
        return map[id]
    }

    fun byName(name: String): MessageDef? = byName[name]

    @Synchronized
    fun parse(text: String) {
        if (loadedCount > 0) {
            return
        }

        var depth = 0
        var messageName: String? = null
        var frequency = Frequency.LOW
        var messageId = 0
        var zerocoded = false
        var trusted = false
        var blocks: MutableList<BlockDef>? = null

        var blockName: String? = null
        var blockRepeat = BlockRepeat.SINGLE
        var blockCount = 0
        var fields: MutableList<FieldDef>? = null

        for (rawLine in text.split('\n')) {
            var line = rawLine
            val comment = line.indexOf("//")
            if (comment >= 0) {
                line = line.substring(0, comment)
            }
            line = line.trim()
            if (line.isEmpty()) {
                continue
            }

            if (line == "{") {
                depth += 1
                continue
            }
            if (line == "}") {
                depth -= 1
                if (depth == 1) {
                    val name = blockName
                    if (name != null && fields != null) {
                        val list = blocks
                        if (list != null) {
                            list.add(BlockDef(name, blockRepeat, blockCount, fields))
                        }
                    }
                    blockName = null
                    fields = null
                } else if (depth == 0) {
                    val name = messageName
                    val list = blocks
                    if (name != null && list != null) {
                        val def = MessageDef(name, frequency, messageId, zerocoded, trusted, list)
                        byName[name] = def
                        byFrequency.getOrPut(frequency) { HashMap() }[messageId] = def
                        loadedCount += 1
                    }
                    messageName = null
                    blocks = null
                }
                continue
            }

            if (line.startsWith("{")) {
                parseField(stripBraces(line), fields)
                continue
            }

            when (depth) {
                1 -> {
                    val tokens = line.split(Regex("\\s+"))
                    if (tokens.size >= 3) {
                        messageName = tokens[0]
                        val freqToken = tokens[1]
                        frequency = when (freqToken) {
                            "High" -> Frequency.HIGH
                            "Medium" -> Frequency.MEDIUM
                            else -> Frequency.LOW
                        }
                        messageId = parseNumber(tokens[2])
                        if (freqToken == "Fixed") {
                            frequency = Frequency.LOW
                            messageId = messageId and 0xFFFF
                        }
                        for (i in 3 until tokens.size) {
                            when (tokens[i]) {
                                "Zerocoded" -> zerocoded = true
                                "Unencoded" -> zerocoded = false
                                "Trusted" -> trusted = true
                                "NotTrusted" -> trusted = false
                            }
                        }
                        blocks = ArrayList()
                    }
                }
                2 -> {
                    val tokens = line.split(Regex("\\s+"))
                    if (tokens.size >= 2) {
                        blockName = tokens[0]
                        blockCount = if (tokens.size >= 3) tokens[2].toIntOrNull() ?: 1 else 1
                        blockRepeat = when (tokens[1]) {
                            "Multiple" -> BlockRepeat.MULTIPLE
                            "Variable" -> BlockRepeat.VARIABLE
                            else -> BlockRepeat.SINGLE
                        }
                        if (blockRepeat != BlockRepeat.MULTIPLE) {
                            blockCount = 0
                        }
                        fields = ArrayList()
                    }
                }
            }
        }
    }

    private fun stripBraces(line: String): String {
        var s = line
        if (s.startsWith("{")) {
            s = s.substring(1)
        }
        if (s.endsWith("}")) {
            s = s.substring(0, s.length - 1)
        }
        return s.trim()
    }

    private fun parseField(content: String, fields: MutableList<FieldDef>?) {
        if (fields == null || content.isEmpty()) {
            return
        }
        val tokens = content.split(Regex("\\s+"))
        if (tokens.size < 2) {
            return
        }
        val name = tokens[0]
        val extra = if (tokens.size >= 3) tokens[2].toIntOrNull() ?: 0 else 0
        val type = mapType(tokens[1], extra) ?: return
        fields.add(FieldDef(name, type, extra))
    }

    private fun mapType(token: String, extra: Int): FieldType? {
        return when (token) {
            "U8" -> FieldType.U8
            "U16" -> FieldType.U16
            "U32" -> FieldType.U32
            "U64" -> FieldType.U64
            "S8" -> FieldType.S8
            "S16" -> FieldType.S16
            "S32" -> FieldType.S32
            "S64" -> FieldType.S64
            "F32" -> FieldType.F32
            "F64" -> FieldType.F64
            "BOOL" -> FieldType.BOOL
            "LLUUID" -> FieldType.LLUUID
            "IPADDR" -> FieldType.IPADDR
            "IPPORT" -> FieldType.IPPORT
            "LLVector3" -> FieldType.VECTOR3
            "LLVector3d" -> FieldType.VECTOR3D
            "LLVector4" -> FieldType.VECTOR4
            "LLQuaternion" -> FieldType.QUATERNION
            "Variable" -> FieldType.VARIABLE
            "Fixed" -> FieldType.FIXED
            else -> null
        }
    }

    private fun parseNumber(token: String): Int {
        return try {
            if (token.startsWith("0x") || token.startsWith("0X")) {
                token.substring(2).toLong(16).toInt()
            } else {
                token.toInt()
            }
        } catch (e: NumberFormatException) {
            0
        }
    }
}
