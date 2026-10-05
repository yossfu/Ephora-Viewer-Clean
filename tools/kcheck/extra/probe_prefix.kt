package com.lumiyaviewer.lumiya.kcheck

import com.lumiyaviewer.lumiya.slproto.base.LLUUIDUtil
import com.lumiyaviewer.lumiya.slproto.world.TextureEntry
import com.lumiyaviewer.lumiya.slproto.world.TextureEntryReference
import java.io.ByteArrayOutputStream

private fun writeLe(table: StringBuilder, out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value shr 8) and 0xFF)
    out.write((value shr 16) and 0xFF)
    out.write((value shr 24) and 0xFF)
}

private fun uuidOf(text: String): ByteArray {
    val hex = text.replace("-", "")
    val out = ByteArray(16)
    for (i in 0 until 16) {
        out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return out
}

private fun packFaceList(count: Int, size: Int, value: (Int) -> ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    for (i in 0 until count - 1) {
        out.write(value(i))
        out.write(0)
    }
    out.write(value(count - 1))
    out.write(0)
    return out.toByteArray()
}

private fun entry(count: Int, texture: (Int) -> String, material: String = LLUUIDUtil.ZERO): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(packFaceList(count, 16) { uuidOf(texture(it)) })
    out.write(packFaceList(count, 4) { byteArrayOf(0, 0, 0, 0) })
    out.write(packFaceList(count, 4) { byteArrayOf(0, 0, 0x80.toByte(), 0x3F) })
    out.write(packFaceList(count, 4) { byteArrayOf(0, 0, 0x80.toByte(), 0x3F) })
    out.write(packFaceList(count, 2) { byteArrayOf(0, 0) })
    out.write(packFaceList(count, 2) { byteArrayOf(0, 0) })
    out.write(packFaceList(count, 2) { byteArrayOf(0, 0) })
    out.write(packFaceList(count, 1) { byteArrayOf(0) })
    out.write(packFaceList(count, 1) { byteArrayOf(0) })
    out.write(packFaceList(count, 1) { byteArrayOf(0) })
    out.write(packFaceList(count, 16) { uuidOf(material) })
    return out.toByteArray()
}

private fun prefixed(te: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(4 + te.size)
    writeLe(StringBuilder(), out, te.size)
    out.write(te)
    return out.toByteArray()
}

private fun classify(blob: ByteArray): String {
    val parsed = TextureEntry.parse(blob)
    val reference = TextureEntryReference.read(blob).verdict
    if (parsed == null) return "null (corta), referencia=" + reference
    return "campos=" + parsed.parsedFields + (if (parsed.truncated) " TRUNCADA(" + parsed.stop + ")" else "") +
        " tex0=" + parsed.face(0).textureId.take(8) + " referencia=" + reference
}

fun main() {
    val a = "11111111-2222-3333-4444-555555555555"
    val b = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    val c = "01234567-89ab-cdef-0123-456789abcdef"
    val cases = listOf(
        "1 cara, uuid" to entry(1, { a }),
        "2 caras distintas" to entry(2, { if (it == 0) a else b }),
        "8 caras, 2 texturas" to entry(8, { if (it % 4 == 0) a else b }),
        "8 caras, material" to entry(8, { a }, c)
    )
    for ((name, te) in cases) {
        println("=== " + name + " (" + te.size + " B)")
        println("  sin prefijo:   " + classify(te))
        val p = prefixed(te)
        println("  con prefijo:   " + p.size + " B -> " + classify(p))
        val at4 = p.copyOfRange(4, p.size)
        println("  saltando 4:    " + classify(at4))
        println("  primero4=" + (p[0].toInt() and 0xFF) + "," + (p[1].toInt() and 0xFF) +
            " (longitud que queda = " + (p.size - 4) + ")")
    }
}
