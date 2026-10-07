package com.ephora.sl
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class UdpDecodeTest {
  @Before fun clean() {
    try { PrimDecoder.reset() } catch (_: Throwable) {}
  }
  private fun hex(s: String): ByteArray {
    val t = s.replace(" ", "")
    return ByteArray(t.length / 2) { i -> t.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
  }
  private fun u16le(a: MutableList<Byte>, v: Int) { a.add((v and 0xFF).toByte()); a.add(((v shr 8) and 0xFF).toByte()) }
  private fun u32le(a: MutableList<Byte>, v: Long) { for (k in 0 until 4) a.add(((v shr (8 * k)) and 0xFF).toByte()) }
  private fun f32le(a: MutableList<Byte>, v: Float) {
    val b = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(v).array()
    for (x in b) a.add(x)
  }
  // R3 terse 44B real (node-metaverse ImprovedTerseObjectUpdateMessageL.json).
  // CITA layout R1 = OMV ImprovedTerseObjectUpdateHandler: LocalID U32@0 LE, State@4, Avatar@5, pos F32@6 LE.
  @Test fun terseR3_vectorReal_44B() {
    val d = hex("50B1002600009AC062438D1862435A1EFF42FF7FFF7FFF7FFF7FFF7FFF7FFF7FFF7FBE09FBB0FF7FFF7FFF7F")
    assertEquals(44, d.size)
    val p = mutableListOf<Byte>()
    for (i in 0 until 10) p.add(0)
    p.add(1)
    p.add(44)
    for (x in d) p.add(x)
    u16le(p, 0)
    PrimDecoder.ingest(15, p.toByteArray())
    val objs = PrimDecoder.publish(128.0, 128.0, 25.0)
    assertEquals(1, objs.size)
    assertEquals(637579600L, objs[0].id)
    assertEquals(226.75, objs[0].x, 0.5)
    assertEquals(226.10, objs[0].y, 0.5)
    assertEquals(127.56, objs[0].z, 0.5)
    assertEquals(1f, objs[0].sx)
  }
  @Test fun terse64Quantized_seDecodifica() {
    val p = mutableListOf<Byte>()
    for (i in 0 until 10) p.add(0)
    p.add(1)
    p.add(64)
    u32le(p, 1234)
    p.add(0); p.add(0)
    u16le(p, 29184); u16le(p, 35583); u16le(p, 13654)
    while (p.size < 11 + 1 + 64) p.add(0)
    u16le(p, 0)
    PrimDecoder.ingest(15, p.toByteArray())
    val objs = PrimDecoder.publish(128.0, 128.0, 25.0)
    assertEquals(1, objs.size)
    assertEquals(100.0, objs[0].x, 1.0)
    assertEquals(150.0, objs[0].y, 1.0)
    assertEquals(25.0, objs[0].z, 3.0)
  }
  // CITA bits R2 = OMV BitPack.cs:335-366 PackBitArray: bytes en orden LE, bits MSB-first en cada byte.
  private fun w(bits: MutableList<Int>, v: Int, k: Int) { for (i in k - 1 downTo 0) bits.add((v shr i) and 1) }
  private fun wLE(bits: MutableList<Int>, v: Int, k: Int) {
    var rem = k
    var sh = 0
    while (rem > 0) {
      val take = if (rem >= 8) 8 else rem
      val b = (v shr sh) and 0xFF
      for (i in take - 1 downTo 0) bits.add((b shr i) and 1)
      sh += 8
      rem -= take
    }
  }
  @Test fun landPrimerPaquete_noSeDescarta() {
    val bits = mutableListOf<Int>()
    fun wf(v: Float) { wLE(bits, java.lang.Float.floatToRawIntBits(v), 32) }
    wLE(bits, 264, 16); w(bits, 16, 8); w(bits, 0x4C, 8)
    w(bits, 0x88, 8); wf(25.0f); wLE(bits, 1, 16); wLE(bits, (3 shl 5) or 5, 10)
    w(bits, 1, 1); w(bits, 0, 1)
    w(bits, 97, 8)
    while (bits.size % 8 != 0) bits.add(0)
    val raw = ByteArray(bits.size / 8) { i ->
      var b = 0
      for (k in 0 until 8) b = (b shl 1) or bits[i * 8 + k]
      b.toByte()
    }
    val p = mutableListOf<Byte>()
    p.add(0x4C)
    u16le(p, raw.size)
    for (x in raw) p.add(x)
    val line = TerrainMesh.ingest(p.toByteArray())
    assertTrue(TerrainMesh.patchesGot() >= 1)
    val h = TerrainMesh.heightAt(3 * 16, 5 * 16)
    assertTrue(h.isFinite())
    assertEquals(25.5f, h, 0.15f)
    assertTrue(line == null || line.startsWith("TERRA-PATCH") || line.startsWith("TERRA-HEX") || line.startsWith("TERRA-ESTADO"))
  }
  // R4 LayerData Land real (node-metaverse LayerDataMessageL.json): Type=76, Data 331B, stride=264.
  // CITA header R2 = OMV TerrainCompressor.cs:361-380 DecodePatchHeader.
  @Test fun landR4_vectorReal_31patchesSanos() {
    val data = hex("0801104C8B000000000100073FFFE8B0000000001006C3FFFE8B0000000001008D3FFFE8B0000000001004B3FFFE8B000000000100EF3FFFE8B000000000100AE3FFFE8B000000000100083FFFE8B0000000001002A3FFFE8B000000000100CF3FFFE8B000000000100093FFFE8B0000000001006D3FFFE8B0000000001004C3FFFE8B0000000001008E3FFFE8B0000000001002B3FFFE8B000000000100AF3FFFE8B0000000001000A3FFFE8B0000000001004D3FFFE8B0000000001006E3FFFE8B0000000001002C3FFFE8B0000000001008F3FFFE8B0000000001000B3FFFE8B0000000001004E3FFFE8B0000000001002D3FFFE8B0000000001006F3FFFE8B0000000001000C3FFFE8B0000000001004F3FFFE8B0000000001002E3FFFE8B0000000001000D3FFFE8B0000000001002F3FFFE8B0000000001000E3FFFE8B0000000001000F3FFFE610")
    assertEquals(331, data.size)
    val before = TerrainMesh.patchesGot()
    val p = mutableListOf<Byte>()
    p.add(0x4C)
    u16le(p, data.size)
    for (x in data) p.add(x)
    val pay = p.toByteArray()
    TerrainMesh.ingest(pay)
    TerrainMesh.ingest(pay)
    assertTrue(TerrainMesh.patchesGot() >= before + 31)
    val h = TerrainMesh.heightAt(8, 120)
    assertTrue(h.isFinite())
    assertEquals(0.0f, h, 2.0f)
    assertTrue(TerrainMesh.minH.isFinite() && TerrainMesh.maxH.isFinite())
    assertTrue(TerrainMesh.minH >= -200f && TerrainMesh.maxH <= 2000f)
  }
  // M2: terse JAMAS escribe escala: full fija 2,3,4 + terse mueve => escala intacta, pos nueva.
  @Test fun fullThenTerse_preservaEscala() {
    val id = 777001L
    val p = mutableListOf<Byte>()
    for (i in 0 until 10) p.add(0)
    p.add(1)
    for (i in 0 until 40) p.add(0)
    val hb2 = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(id.toInt()).array()
    for (i in 0 until 4) p[11 + i] = hb2[i]
    p[11 + 25] = 9
    val s1 = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(2f).array()
    val s2 = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(3f).array()
    val s3 = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(4f).array()
    for (i in 0 until 4) { p[11 + 28 + i] = s1[i]; p[11 + 32 + i] = s2[i]; p[11 + 36 + i] = s3[i] }
    p.add(76)
    val ib0 = p.size
    for (i in 0 until 76) p.add(0)
    val x1 = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(100f).array()
    val y1 = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(150f).array()
    val z1 = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(30f).array()
    for (i in 0 until 4) { p[ib0 + 16 + i] = x1[i]; p[ib0 + 20 + i] = y1[i]; p[ib0 + 24 + i] = z1[i] }
    for (i in 0 until 30) p.add(0)
    u16le(p, 0)
    p.add(0)
    u16le(p, 0)
    u16le(p, 0)
    p.add(0)
    for (i in 0 until 4) p.add(0)
    p.add(0)
    p.add(0)
    p.add(0)
    for (i in 0 until 66) p.add(0)
    PrimDecoder.ingest(12, p.toByteArray())
    var objs = PrimDecoder.publish(128.0, 128.0, 25.0)
    assertEquals(1, objs.size)
    assertEquals(2f, objs[0].sx)
    assertEquals(3f, objs[0].sy)
    assertEquals(4f, objs[0].sz)
    val t = mutableListOf<Byte>()
    for (i in 0 until 10) t.add(0)
    t.add(1)
    t.add(44)
    val tb = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    tb.putInt(id.toInt())
    tb.put(0)
    tb.put(0)
    tb.putFloat(110f)
    tb.putFloat(160f)
    tb.putFloat(35f)
    while (tb.position() < 44) tb.put(0)
    for (x in tb.array()) t.add(x)
    u16le(t, 0)
    PrimDecoder.ingest(15, t.toByteArray())
    objs = PrimDecoder.publish(128.0, 128.0, 25.0)
    assertEquals(1, objs.size)
    assertEquals(110.0, objs[0].x, 0.01)
    assertEquals(160.0, objs[0].y, 0.01)
    assertEquals(35.0, objs[0].z, 0.01)
    assertEquals(2f, objs[0].sx)
    assertEquals(3f, objs[0].sy)
    assertEquals(4f, objs[0].sz)
    assertEquals(9, objs[0].tipo)
  }
  // M4: kill borra SOLO el id listado, el vivo queda.
  @Test fun killSoloIdListado_vivoQueda() {
    fun terse44(v: Long, x: Float, y: Float, z: Float): ByteArray {
      val t = mutableListOf<Byte>()
      for (i in 0 until 10) t.add(0)
      t.add(1)
      t.add(44)
      val tb = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      tb.putInt(v.toInt())
      tb.put(0)
      tb.put(0)
      tb.putFloat(x)
      tb.putFloat(y)
      tb.putFloat(z)
      while (tb.position() < 44) tb.put(0)
      for (b in tb.array()) t.add(b)
      u16le(t, 0)
      return t.toByteArray()
    }
    PrimDecoder.ingest(15, terse44(1111L, 50f, 60f, 25f))
    PrimDecoder.ingest(15, terse44(2222L, 70f, 80f, 26f))
    assertEquals(2, PrimDecoder.publish(128.0, 128.0, 25.0).size)
    val k = mutableListOf<Byte>()
    k.add(1)
    val kb = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1111).array()
    for (b in kb) k.add(b)
    PrimDecoder.ingest(16, k.toByteArray())
    val objs = PrimDecoder.publish(128.0, 128.0, 25.0)
    assertEquals(1, objs.size)
    assertEquals(2222L, objs[0].id)
    assertTrue(PrimDecoder.nKillHit >= 1)
  }
}
