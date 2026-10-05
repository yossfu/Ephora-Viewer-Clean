package com.lumiyaviewer.lumiya.slproto.llsd

object LLSDSerializer {

    fun toXml(value: LLSD, indent: Int = 0): String {
        val pad = " ".repeat(indent)
        return when (value) {
            is LLSD.Undef -> pad + "<undef />"
            is LLSD.Bool -> pad + "<boolean>" + value.value + "</boolean>"
            is LLSD.Int32 -> pad + "<integer>" + value.value + "</integer>"
            is LLSD.Real -> pad + "<real>" + value.value + "</real>"
            is LLSD.Str -> pad + "<string>" + escape(value.value) + "</string>"
            is LLSD.Bin -> pad + "<binary>" + toHex(value.value) + "</binary>"
            is LLSD.Arr -> pad + "<array>\n" +
                value.value.joinToString("\n") { item -> toXml(item, indent + 2) } +
                "\n" + pad + "</array>"
            is LLSD.Map -> pad + "<map>\n" +
                value.value.entries.joinToString("\n") { entry ->
                    pad + "  <key>" + escape(entry.key) + "</key>\n" + toXml(entry.value, indent + 4)
                } +
                "\n" + pad + "</map>"
        }
    }

    private fun escape(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun toHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val unsigned = b.toInt() and 0xFF
            builder.append(HEX[unsigned shr 4])
            builder.append(HEX[unsigned and 0x0F])
        }
        return builder.toString()
    }

    private const val HEX = "0123456789abcdef"
}
