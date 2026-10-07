package com.ephora.sl

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Linden Binary Mesh (.llm) reader for the actual Second Life system avatar.
 */
object LegacyAvatarMesh {
  private const val TAG = "EphoraAvatarLLM"
  private const val MAGIC = "Linden Binary Mesh 1.0"
  private const val HEADER = 24

  data class Mesh(
    val name: String,
    val pos: FloatArray,
    val normal: FloatArray,
    val uv: FloatArray,
    val indices: ShortArray,
    val weights: FloatArray,
    val joints: List<String>
  ) {
    val vertices: Int get() = pos.size / 3
  }

  private val cache = HashMap<String, Mesh>()

  fun load(context: Context, asset: String): Mesh? {
    synchronized(cache) { cache[asset]?.let { return it } }
    return try {
      val bytes = context.assets.open("avatar/$asset").use { it.readBytes() }
      val mesh = parse(bytes, asset)
      if (mesh != null) synchronized(cache) { cache[asset] = mesh }
      mesh
    } catch (e: Throwable) {
      Log.w(TAG, "load $asset failed: " + (e.message ?: ""))
      null
    }
  }

  private fun parse(bytes: ByteArray, name: String): Mesh? {
    if (bytes.size < HEADER + 25 + 2) return null
    if (String(bytes, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) return null
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    bb.position(HEADER)
    return try {
      val hasWeights = bb.get().toInt() != 0
      val hasDetail = bb.get().toInt() != 0
      val px = bb.float; val py = bb.float; val pz = bb.float
      bb.float; bb.float; bb.float
      bb.get()
      bb.float; bb.float; bb.float

      val nv = bb.short.toInt() and 0xFFFF
      if (nv <= 0 || nv > 200_000) return null

      val pos = FloatArray(nv * 3)
      for (i in 0 until nv) {
        pos[i * 3] = bb.float + px
        pos[i * 3 + 1] = bb.float + py
        pos[i * 3 + 2] = bb.float + pz
      }

      val normal = FloatArray(nv * 3)
      for (i in 0 until nv) {
        normal[i * 3] = bb.float
        normal[i * 3 + 1] = bb.float
        normal[i * 3 + 2] = bb.float
      }

      repeat(nv) { bb.float; bb.float; bb.float }

      val uv = FloatArray(nv * 2)
      for (i in 0 until nv) {
        uv[i * 2] = bb.float
        uv[i * 2 + 1] = bb.float
      }

      if (hasDetail) repeat(nv) { bb.float; bb.float }

      val weights = if (hasWeights) FloatArray(nv) else FloatArray(0)
      if (hasWeights) for (i in 0 until nv) weights[i] = bb.float

      val faces = bb.short.toInt() and 0xFFFF
      if (faces <= 0 || faces > 200_000) return null
      val indices = ShortArray(faces * 3)
      for (i in indices.indices) indices[i] = bb.short

      val joints = ArrayList<String>()
      if (hasWeights && bb.remaining() >= 2) {
        val count = bb.short.toInt() and 0xFFFF
        if (count in 1..256 && bb.remaining() >= count * 64) {
          val buf = ByteArray(64)
          repeat(count) {
            bb.get(buf)
            val n = buf.indexOf(0)
            joints.add(String(buf, 0, if (n >= 0) n else 64, Charsets.US_ASCII).trim())
          }
        }
      }

      Mesh(name, pos, normal, uv, indices, weights, joints)
    } catch (e: Throwable) {
      Log.w(TAG, name + " parse failed at " + bb.position() + ": " + (e.message ?: ""))
      null
    }
  }

  fun expand(mesh: Mesh): FloatArray {
    val out = FloatArray(mesh.indices.size * 8)
    var d = 0
    for (ii in mesh.indices) {
      val i = ii.toInt() and 0xFFFF
      out[d++] = mesh.pos[i * 3]
      out[d++] = mesh.pos[i * 3 + 2]
      out[d++] = -mesh.pos[i * 3 + 1]
      out[d++] = mesh.normal[i * 3]
      out[d++] = mesh.normal[i * 3 + 2]
      out[d++] = -mesh.normal[i * 3 + 1]
      out[d++] = mesh.uv[i * 2]
      out[d++] = 1f - mesh.uv[i * 2 + 1]
    }
    return out
  }
}
