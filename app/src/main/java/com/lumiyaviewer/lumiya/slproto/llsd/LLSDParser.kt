package com.lumiyaviewer.lumiya.slproto.llsd

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Parses LLSD in both XML and notation form. The login endpoint normally
 * answers with XML, but notation is used for nested values such as `home`.
 */
object LLSDParser {

    fun parse(text: String): Any? {
        val trimmed = text.trim()
        return if (trimmed.startsWith("<")) parseXml(trimmed) else parseNotation(trimmed)
    }

    /**
     * Parses an LLSD body whose notation is not known in advance. Capabilities
     * are free to answer in XML, notation or binary — the simulator even
     * switches between them for the same endpoint — so sniff the bytes instead
     * of trusting the caller.
     */
    fun parseBytes(bytes: ByteArray, contentType: String = ""): Any? {
        if (bytes.isEmpty()) {
            return null
        }
        val type = contentType.lowercase()
        if (type.contains("binary")) {
            return LLSDBinary.decode(bytes)
        }
        var text = String(bytes, Charsets.UTF_8)
        if (text.isNotEmpty() && text[0] == '\uFEFF') {
            text = text.substring(1)
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        if (trimmed.startsWith("<")) {
            return parseXml(trimmed)
        }
        if (type.contains("json")) {
            return parseNotation(trimmed)
        }
        if (!looksLikeText(bytes)) {
            return LLSDBinary.decode(bytes)
        }
        return try {
            parseNotation(trimmed)
        } catch (t: Throwable) {
            LLSDBinary.decode(bytes)
        }
    }

    /** True when the body is printable text, and therefore not binary LLSD. */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        val limit = if (bytes.size < 64) bytes.size else 64
        if (limit == 0) {
            return false
        }
        for (i in 0 until limit) {
            val value = bytes[i].toInt() and 0xFF
            if (value == 0 || value < 0x09 || (value in 0x0E..0x1F) || value == 0x7F) {
                return false
            }
        }
        return true
    }

    fun asBoolean(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            is Number -> value.toInt() != 0
            else -> false
        }
    }

    fun asString(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> value
            is Double -> if (value == Math.floor(value)) value.toLong().toString() else value.toString()
            else -> value.toString()
        }
    }

    fun asInt(value: Any?, fallback: Int = 0): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: fallback
            else -> fallback
        }
    }

    fun asReal(value: Any?, fallback: Double = 0.0): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull() ?: fallback
            else -> fallback
        }
    }

    /** 64-bit integers (e.g. an LLSD `region_handle`) do not fit in an Int. */
    fun asLong(value: Any?, fallback: Long = 0L): Long {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: fallback
            else -> fallback
        }
    }

    @Suppress("unchecked")
    fun asMap(value: Any?): Map<String, Any?> {
        return value as? Map<String, Any?> ?: emptyMap()
    }

    // ---------------------------------------------------------------- XML ---

    private fun parseXml(text: String): Any? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(text))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name != "llsd") {
                return parseXmlValue(parser)
            }
            event = parser.next()
        }
        return null
    }

    private fun parseXmlValue(parser: XmlPullParser): Any? {
        return when (parser.name) {
            "map" -> parseXmlMap(parser)
            "array" -> parseXmlArray(parser)
            "string" -> readText(parser)
            "integer" -> {
                val raw = readText(parser).trim()
                val parsed = raw.toLongOrNull()
                when {
                    parsed == null -> 0
                    parsed in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> parsed.toInt()
                    else -> parsed
                }
            }
            "real" -> readText(parser).trim().toDoubleOrNull() ?: 0.0
            "boolean" -> {
                val raw = readText(parser).trim()
                raw == "1" || raw.equals("true", ignoreCase = true)
            }
            "uuid" -> readText(parser)
            "binary" -> readText(parser)
            "undef" -> {
                consumeEmpty(parser)
                null
            }
            else -> readText(parser)
        }
    }

    private fun parseXmlMap(parser: XmlPullParser): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        var key: String? = null
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) {
                break
            }
            if (event == XmlPullParser.END_TAG && parser.name == "map") {
                break
            }
            if (event == XmlPullParser.START_TAG) {
                if (parser.name == "key") {
                    key = readText(parser)
                } else {
                    val value = parseXmlValue(parser)
                    val currentKey = key
                    if (currentKey != null) {
                        result[currentKey] = value
                    }
                    key = null
                }
            }
        }
        return result
    }

    private fun parseXmlArray(parser: XmlPullParser): List<Any?> {
        val result = ArrayList<Any?>()
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) {
                break
            }
            if (event == XmlPullParser.END_TAG && parser.name == "array") {
                break
            }
            if (event == XmlPullParser.START_TAG) {
                result.add(parseXmlValue(parser))
            }
        }
        return result
    }

    private fun readText(parser: XmlPullParser): String {
        val builder = StringBuilder()
        var depth = 1
        while (depth > 0) {
            val event = parser.next()
            when (event) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> builder.append(parser.text)
                XmlPullParser.START_TAG -> depth += 1
                XmlPullParser.END_TAG -> depth -= 1
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
        return builder.toString()
    }

    private fun consumeEmpty(parser: XmlPullParser) {
        // Self closing tags already end here; nothing to consume.
    }

    // ----------------------------------------------------------- Notation ---

    private fun parseNotation(text: String): Any? {
        val cursor = NotationCursor(text)
        val value = cursor.value()
        return value
    }

    private class NotationCursor(private val text: String) {

        private var pos = 0

        fun value(): Any? {
            skipSpace()
            if (pos >= text.length) {
                return null
            }
            return when (text[pos]) {
                '{' -> map()
                '[' -> array()
                '\'', '"' -> string()
                '!' -> {
                    pos += 1
                    null
                }
                'i' -> {
                    pos += 1
                    number().toInt()
                }
                'r' -> {
                    pos += 1
                    number()
                }
                'u' -> {
                    pos += 1
                    readWhile { it.isLetterOrDigit() || it == '-' }
                }
                'd' -> {
                    pos += 1
                    string()
                }
                'b' -> {
                    pos += 1
                    string()
                }
                else -> literal()
            }
        }

        private fun map(): Map<String, Any?> {
            pos += 1
            val result = LinkedHashMap<String, Any?>()
            while (true) {
                skipSpace()
                if (pos >= text.length) {
                    break
                }
                if (text[pos] == '}') {
                    pos += 1
                    break
                }
                if (text[pos] == ',') {
                    pos += 1
                    continue
                }
                val key = string()
                skipSpace()
                if (pos < text.length && text[pos] == ':') {
                    pos += 1
                }
                result[key] = value()
            }
            return result
        }

        private fun array(): List<Any?> {
            pos += 1
            val result = ArrayList<Any?>()
            while (true) {
                skipSpace()
                if (pos >= text.length) {
                    break
                }
                if (text[pos] == ']') {
                    pos += 1
                    break
                }
                if (text[pos] == ',') {
                    pos += 1
                    continue
                }
                result.add(value())
            }
            return result
        }

        private fun string(): String {
            skipSpace()
            if (pos >= text.length) {
                return ""
            }
            val quote = text[pos]
            if (quote != '\'' && quote != '"') {
                return literal().toString()
            }
            pos += 1
            val builder = StringBuilder()
            while (pos < text.length && text[pos] != quote) {
                if (text[pos] == '\\' && pos + 1 < text.length) {
                    pos += 1
                    builder.append(
                        when (text[pos]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> text[pos]
                        }
                    )
                } else {
                    builder.append(text[pos])
                }
                pos += 1
            }
            pos += 1
            return builder.toString()
        }

        private fun number(): Double {
            val raw = readWhile {
                it.isDigit() || it == '.' || it == '-' || it == '+' || it == 'e' || it == 'E'
            }
            return raw.toDoubleOrNull() ?: 0.0
        }

        private fun literal(): Any? {
            val raw = readWhile { it.isLetterOrDigit() || it == '.' || it == '-' || it == '+' }
            return when {
                raw.isEmpty() -> {
                    pos += 1
                    null
                }
                raw.equals("true", ignoreCase = true) -> true
                raw.equals("false", ignoreCase = true) -> false
                raw.contains('.') -> raw.toDoubleOrNull() ?: 0.0
                else -> raw.toIntOrNull() as Any? ?: raw
            }
        }

        private fun readWhile(predicate: (Char) -> Boolean): String {
            val start = pos
            while (pos < text.length && predicate(text[pos])) {
                pos += 1
            }
            return text.substring(start, pos)
        }

        private fun skipSpace() {
            while (pos < text.length && text[pos].isWhitespace()) {
                pos += 1
            }
        }
    }
}
