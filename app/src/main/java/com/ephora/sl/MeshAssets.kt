package com.ephora.sl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Fetches Second Life mesh LODs through the region GetMesh2 cap and expands them for GLES. */
object MeshAssets {
  data class Face(val vertices: FloatBuffer, val vertexCount: Int)
  data class Mesh(val faces: List<Face?>)

  private const val MAX_HEADER_BYTES = 4096
  private const val MAX_MESH_BYTES = 16 * 1024 * 1024
  private const val MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024
  // Keep enough recently-used geometry for the current 47-object region view.
  // The old 12-entry LRU evicted meshes while the renderer was cycling through
  // visible objects, so most of them silently fell back to procedural shapes.
  private const val MAX_CACHE_ENTRIES = 48
  private const val MAX_VERTICES_PER_FACE = 65535
  private const val MAX_EXPANDED_VERTICES = 350000

  @Volatile private var meshCap = ""
  @Volatile private var worker: Job? = null
  @Volatile var requested = 0L
    private set
  @Volatile var decoded = 0L
    private set
  @Volatile var failed = 0L
    private set
  @Volatile var last = "-"

  private val lock = Any()
  private var queue = Channel<String>(Channel.UNLIMITED)
  private val scheduled = LinkedHashSet<String>()
  private val cache = object : LinkedHashMap<String, Mesh>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mesh>?): Boolean = size > MAX_CACHE_ENTRIES
  }
  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

  fun resetSession() {
    synchronized(lock) {
      meshCap = ""
      worker?.cancel()
      worker = null
      queue.close()
      queue = Channel(Channel.UNLIMITED)
      scheduled.clear()
      cache.clear()
      requested = 0L
      decoded = 0L
      failed = 0L
      last = "-"
    }
  }

  fun start(scope: CoroutineScope, capUrl: String) {
    meshCap = capUrl.trim().trimEnd('/')
    if (meshCap.isBlank()) {
      last = "sin-cap-GetMesh2"
      return
    }
    synchronized(lock) {
      if (worker?.isActive == true) return
      val channel = queue
      worker = scope.launch(Dispatchers.IO) {
        for (id in channel) {
          try {
            val mesh = fetchAndDecode(id)
            synchronized(cache) { cache[id] = mesh }
            decoded++
            last = "ok:$id faces=${mesh.faces.size}"
          } catch (e: CancellationException) {
            throw e
          } catch (e: Throwable) {
            failed++
            last = "fail:$id ${e.javaClass.simpleName}:${(e.message ?: "").take(80)}"
          } finally {
            synchronized(lock) { scheduled.remove(id) }
          }
        }
      }
    }
  }

  fun request(meshId: String) {
    val id = meshId.trim().lowercase(Locale.US)
    if (!isUuid(id) || meshCap.isBlank()) return
    synchronized(cache) { if (cache.containsKey(id)) return }
    synchronized(lock) {
      if (worker?.isActive != true || scheduled.contains(id)) return
      if (scheduled.size >= 128) return
      scheduled.add(id)
      if (queue.trySend(id).isSuccess) requested++ else scheduled.remove(id)
    }
  }

  fun mesh(id: String): Mesh? = synchronized(cache) { cache[id.lowercase(Locale.US)] }

  fun status(): String = "MESH-ASSETS req=$requested decoded=$decoded failed=$failed cache=${synchronized(cache) { cache.size }} pending=${synchronized(lock) { scheduled.size }} last=$last"

  private fun isUuid(s: String): Boolean = s.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

  private data class RangeResult(val code: Int, val bytes: ByteArray)

  private fun fetchRange(url: String, start: Long, length: Int): RangeResult {
    require(start >= 0 && length in 1..MAX_MESH_BYTES) { "rango-invalido" }
    val end = start + length - 1L
    val request = Request.Builder().url(url).get()
      .header("Range", "bytes=$start-$end")
      .header("Accept", "*/*")
      .header("Accept-Encoding", "identity")
      .header("User-Agent", "EPHORASL/7.42 (Android)")
      .build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("http-${response.code}")
      val body = response.body ?: throw IllegalStateException("sin-body")
      if (body.contentLength() > MAX_MESH_BYTES && response.code != 200) throw IllegalStateException("respuesta-demasiado-grande")
      val bytes = body.byteStream().use { readBounded(it, MAX_MESH_BYTES) }
      if (bytes.isEmpty() || bytes.size > MAX_MESH_BYTES) throw IllegalStateException("respuesta-demasiado-grande")
      return RangeResult(response.code, bytes)
    }
  }

  private fun fetchAndDecode(id: String): Mesh {
    val url = "$meshCap/?mesh_id=$id"
    val first = fetchRange(url, 0L, MAX_HEADER_BYTES)
    val headerReader = LlsdBinary(first.bytes)
    val header = headerReader.read() as? Map<*, *> ?: throw IllegalArgumentException("header-no-es-map")
    val headerSize = headerReader.position
    val lod = listOf("medium_lod", "high_lod", "low_lod", "lowest_lod")
      .firstNotNullOfOrNull { name -> (header[name] as? Map<*, *>)?.let { it } }
      ?: throw IllegalArgumentException("header-sin-lod")
    val offset = (lod["offset"] as? Number)?.toLong() ?: throw IllegalArgumentException("lod-offset")
    val size = (lod["size"] as? Number)?.toInt() ?: throw IllegalArgumentException("lod-size")
    require(offset >= 0 && size in 1..MAX_MESH_BYTES) { "lod-rango=$offset/$size" }
    val absolute = headerSize.toLong() + offset
    val lodBytes = if (first.code == 200 && first.bytes.size.toLong() >= absolute + size) {
      first.bytes.copyOfRange(absolute.toInt(), absolute.toInt() + size)
    } else {
      val response = fetchRange(url, absolute, size)
      if (response.bytes.size >= size) response.bytes.copyOfRange(0, size)
      else throw IllegalStateException("lod-incompleto=${response.bytes.size}/$size")
    }
    val inflated = inflateBounded(lodBytes)
    val faces = LlsdBinary(inflated).read() as? List<*> ?: throw IllegalArgumentException("lod-no-es-lista")
    val decodedFaces = faces.map { face -> (face as? Map<*, *>)?.let { unpackFace(it) } }
    if (decodedFaces.none { it != null }) throw IllegalArgumentException("lod-sin-caras")
    if (decodedFaces.sumOf { it?.vertexCount ?: 0 } > MAX_EXPANDED_VERTICES) throw IllegalArgumentException("lod-demasiados-vertices")
    return Mesh(decodedFaces)
  }

  private fun inflateBounded(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(minOf(data.size * 3, 1024 * 1024))
    val input = ByteArrayInputStream(data)
    val stream = if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
      GZIPInputStream(input)
    } else {
      // Some compatible grids return the same LLSD payload with a zlib wrapper.
      InflaterInputStream(input)
    }
    stream.use {
      val buf = ByteArray(16 * 1024)
      var total = 0
      while (true) {
        val n = it.read(buf)
        if (n < 0) break
        total += n
        if (total > MAX_DECOMPRESSED_BYTES) throw IllegalArgumentException("lod-expandido-demasiado-grande")
        out.write(buf, 0, n)
      }
    }
    return out.toByteArray()
  }

  private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      total += n
      if (total > limit) throw IllegalStateException("respuesta-demasiado-grande")
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  private fun unpackFace(face: Map<*, *>): Face? {
    if (face.containsKey("NoGeometry")) return null
    val posBytes = face["Position"] as? ByteArray ?: return null
    val triBytes = face["TriangleList"] as? ByteArray ?: return null
    val nVerts = posBytes.size / 6
    if (nVerts !in 1..MAX_VERTICES_PER_FACE || triBytes.size < 6) return null
    val positionDomain = face["PositionDomain"] as? Map<*, *>
    // The mesh specification defaults an omitted position domain to [-0.5, 0.5].
    val minP = numbers(positionDomain?.get("Min"), 3) ?: doubleArrayOf(-0.5, -0.5, -0.5)
    val maxP = numbers(positionDomain?.get("Max"), 3) ?: doubleArrayOf(0.5, 0.5, 0.5)
    val uvBytes = face["TexCoord0"] as? ByteArray ?: ByteArray(0)
    val uvDomain = face["TexCoord0Domain"] as? Map<*, *>
    val minUv = numbers(uvDomain?.get("Min"), 2) ?: doubleArrayOf(0.0, 0.0)
    val maxUv = numbers(uvDomain?.get("Max"), 2) ?: doubleArrayOf(1.0, 1.0)
    val normalBytes = face["Normal"] as? ByteArray ?: ByteArray(0)
    val indices = triBytes.size / 2
    val count = indices - indices % 3
    if (count <= 0 || count > 120000) return null
    val out = ByteBuffer.allocateDirect(count * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    val pb = ByteBuffer.wrap(posBytes).order(ByteOrder.LITTLE_ENDIAN)
    val nb = ByteBuffer.wrap(normalBytes).order(ByteOrder.LITTLE_ENDIAN)
    val ub = ByteBuffer.wrap(uvBytes).order(ByteOrder.LITTLE_ENDIAN)
    val ib = ByteBuffer.wrap(triBytes).order(ByteOrder.LITTLE_ENDIAN)
    val normalizedScale = numbers(face["NormalizedScale"], 3)
    for (i in 0 until count) {
      val vi = ib.getShort(i * 2).toInt() and 0xffff
      if (vi >= nVerts) return null
      val px = u16(pb, vi * 6) / 65535.0 * (maxP[0] - minP[0]) + minP[0]
      val py = u16(pb, vi * 6 + 2) / 65535.0 * (maxP[1] - minP[1]) + minP[1]
      val pz = u16(pb, vi * 6 + 4) / 65535.0 * (maxP[2] - minP[2]) + minP[2]
      val nx: Double
      val ny: Double
      val nz: Double
      if (normalBytes.size >= (vi + 1) * 6) {
        nx = u16(nb, vi * 6) / 65535.0 * 2.0 - 1.0
        ny = u16(nb, vi * 6 + 2) / 65535.0 * 2.0 - 1.0
        nz = u16(nb, vi * 6 + 4) / 65535.0 * 2.0 - 1.0
      } else { nx = 0.0; ny = 0.0; nz = 1.0 }
      val uv = vi * 4
      val u = if (uvBytes.size >= uv + 4) u16(ub, uv) / 65535.0 * (maxUv[0] - minUv[0]) + minUv[0] else 0.0
      val v = if (uvBytes.size >= uv + 4) u16(ub, uv + 2) / 65535.0 * (maxUv[1] - minUv[1]) + minUv[1] else 0.0
      val scaleX = normalizedScale?.get(0) ?: 1.0
      val scaleY = normalizedScale?.get(1) ?: 1.0
      val scaleZ = normalizedScale?.get(2) ?: 1.0
      // Simulator coordinates are Z-up; the renderer uses Y-up and negated region Y.
      out.put((px * scaleX).toFloat()).put((pz * scaleZ).toFloat()).put((-py * scaleY).toFloat())
      out.put(nx.toFloat()).put(nz.toFloat()).put((-ny).toFloat())
      out.put(u.toFloat()).put(v.toFloat())
    }
    out.position(0)
    return Face(out, count)
  }

  private fun numbers(value: Any?, count: Int): DoubleArray? {
    val list = value as? List<*> ?: return null
    if (list.size < count) return null
    val result = DoubleArray(count)
    for (i in 0 until count) result[i] = (list[i] as? Number)?.toDouble() ?: return null
    return result
  }

  private fun u16(buffer: ByteBuffer, offset: Int): Double = (buffer.getShort(offset).toInt() and 0xffff).toDouble()

  /** Bounded LLSD binary reader; mesh headers and compressed LODs only need scalar/map/array/binary values. */
  private class LlsdBinary(private val data: ByteArray) {
    var position = 0
      private set
    private var nodes = 0

    init {
      // LLSD writers vary the case and spacing in this XML-style binary header.
      if (data.size >= 4 && data[0] == '<'.code.toByte() && data[1] == '?'.code.toByte()) {
        val close = (2 until minOf(data.size - 1, 128)).firstOrNull {
          data[it] == '?'.code.toByte() && data[it + 1] == '>'.code.toByte()
        }
        if (close != null) {
          val header = String(data, 0, close + 2, Charsets.US_ASCII).lowercase(Locale.US)
          require(header.contains("llsd") && header.contains("binary")) { "llsd-header" }
          position = close + 2
          while (position < data.size && (data[position] == 10.toByte() || data[position] == 13.toByte() || data[position] == 32.toByte() || data[position] == 9.toByte())) position++
        }
      }
    }

    fun read(): Any? = value(0)

    private fun value(depth: Int): Any? {
      require(depth < 32 && ++nodes < 250000) { "llsd-limite" }
      val marker = byte().toInt().toChar()
      return when (marker) {
        '!' -> null
        '0' -> false
        '1' -> true
        'i' -> int32()
        'r' -> java.lang.Double.longBitsToDouble(int64())
        'u' -> uuid()
        's', 'l', 'k' -> string()
        'd' -> java.lang.Double.longBitsToDouble(int64())
        'b' -> binary()
        '[' -> {
          val count = int32()
          require(count in 0..200000) { "llsd-array-count=$count" }
          val out = ArrayList<Any?>()
          repeat(count) { out.add(value(depth + 1)) }
          require(byte().toInt().toChar() == ']') { "llsd-array" }
          out
        }
        '{' -> {
          val count = int32()
          require(count in 0..200000) { "llsd-map-count=$count" }
          val out = LinkedHashMap<String, Any?>()
          repeat(count) {
            require(byte().toInt().toChar() == 'k') { "llsd-map-key" }
            out[string()] = value(depth + 1)
          }
          require(byte().toInt().toChar() == '}') { "llsd-map" }
          out
        }
        else -> throw IllegalArgumentException("llsd-marker=${marker.code}")
      }
    }

    private fun string(): String {
      val len = int32()
      require(len in 0..MAX_MESH_BYTES && position + len <= data.size) { "llsd-string-len=$len" }
      val result = String(data, position, len, Charsets.UTF_8)
      position += len
      return result
    }

    private fun binary(): ByteArray {
      val len = int32()
      require(len in 0..MAX_DECOMPRESSED_BYTES && position + len <= data.size) { "llsd-binary-len=$len" }
      val result = data.copyOfRange(position, position + len)
      position += len
      return result
    }

    private fun uuid(): String {
      require(position + 16 <= data.size) { "llsd-uuid" }
      val b = data.copyOfRange(position, position + 16)
      position += 16
      val h = b.joinToString("") { "%02x".format(it.toInt() and 255) }
      return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20)
    }

    private fun byte(): Byte {
      require(position < data.size) { "llsd-eof" }
      return data[position++]
    }

    private fun int32(): Int {
      require(position + 4 <= data.size) { "llsd-i32" }
      val n = ((data[position].toInt() and 255) shl 24) or ((data[position + 1].toInt() and 255) shl 16) or
        ((data[position + 2].toInt() and 255) shl 8) or (data[position + 3].toInt() and 255)
      position += 4
      return n
    }

    private fun int64(): Long {
      require(position + 8 <= data.size) { "llsd-i64" }
      var n = 0L
      repeat(8) { n = (n shl 8) or (data[position++].toLong() and 255L) }
      return n
    }
  }
}
