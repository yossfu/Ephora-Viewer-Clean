package com.lumiyaviewer.lumiya.slproto.llsd

/**
 * A value that must be written as an LLSD `<uuid>` element. Plain Kotlin strings
 * become `<string>`, which the grid would reject for identifier fields.
 */
class LLSDUuid(val value: String)

/** A value that must be written as an LLSD `<integer>` element. */
class LLSDInteger(val value: Long)

/** A value that must be written as an LLSD `<real>` element. */
class LLSDReal(val value: Double)

/**
 * Serializes a Kotlin value tree to LLSD XML, the format the Second Life
 * login endpoint and every capability expects.
 */
object LLSDWriter {

    fun toXml(value: Any?): String {
        val builder = StringBuilder(2048)
        builder.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<llsd>\n")
        writeValue(builder, value, 0)
        builder.append("\n</llsd>\n")
        return builder.toString()
    }

    private fun writeValue(builder: StringBuilder, value: Any?, indent: Int) {
        val pad = " ".repeat(indent)
        when (value) {
            null -> builder.append(pad).append("<undef />")
            is LLSDUuid -> builder.append(pad).append("<uuid>").append(value.value).append("</uuid>")
            is LLSDInteger -> builder.append(pad).append("<integer>").append(value.value).append("</integer>")
            is LLSDReal -> builder.append(pad).append("<real>").append(value.value).append("</real>")
            is Boolean -> builder.append(pad).append("<boolean>").append(if (value) "true" else "false").append("</boolean>")
            is Int -> builder.append(pad).append("<integer>").append(value).append("</integer>")
            is Long -> builder.append(pad).append("<integer>").append(value).append("</integer>")
            is Float -> builder.append(pad).append("<real>").append(value).append("</real>")
            is Double -> builder.append(pad).append("<real>").append(value).append("</real>")
            is String -> builder.append(pad).append("<string>").append(escape(value)).append("</string>")
            is ByteArray -> builder.append(pad).append("<binary>").append(toBase64(value)).append("</binary>")
            is Map<*, *> -> {
                builder.append(pad).append("<map>\n")
                for ((key, item) in value) {
                    builder.append(pad).append("  <key>").append(escape(key.toString())).append("</key>\n")
                    writeValue(builder, item, indent + 2)
                    builder.append('\n')
                }
                builder.append(pad).append("</map>")
            }
            is Iterable<*> -> {
                builder.append(pad).append("<array>\n")
                for (item in value) {
                    writeValue(builder, item, indent + 2)
                    builder.append('\n')
                }
                builder.append(pad).append("</array>")
            }
            else -> builder.append(pad).append("<string>").append(escape(value.toString())).append("</string>")
        }
    }

    private fun escape(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun toBase64(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
}
