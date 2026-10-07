package com.ephora.sl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Production texture transport for Second Life.
 *
 * HTTP is the primary path (ViewerAsset, then GetTexture). UDP RequestImage is
 * managed separately by AgentLoop with a hard two-transfer limit, matching
 * the reference/Lumiya transfer model.
 *
 * No diagnostic URL probing or synthetic protocol traffic belongs here.
 */
object TexFetch {
  private const val MAX_WORKERS = 4
  private const val MAX_QUEUE = 768
  private const val MAX_RETRIES = 4
  private const val MAX_BYTES = 16 * 1024 * 1024

  private data class Request(
    val uuid: String,
    val priority: Int,
    val enqueuedAt: Long,
    var attempt: Int = 0
  ) : Comparable<Request> {
    override fun compareTo(other: Request): Int {
      val p = other.priority.compareTo(priority)
      return if (p != 0) p else enqueuedAt.compareTo(other.enqueuedAt)
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val queue = PriorityBlockingQueue<Request>()
  private val queued = ConcurrentHashMap.newKeySet<String>()
  private val inFlight = ConcurrentHashMap.newKeySet<String>()
  private val retryAt = ConcurrentHashMap<String, Long>()
  private val notFound = ConcurrentHashMap.newKeySet<String>()
  private val workers = ArrayList<Job>()

  @Volatile private var httpOk = 0L
  @Volatile private var httpFail = 0L
  @Volatile private var http404 = 0L
  @Volatile private var decodeAccepted = 0L
  @Volatile private var lastError = "-"
  @Volatile var done = false
    private set

  private val client: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
      .dispatcher(okhttp3.Dispatcher().apply {
        maxRequests = MAX_WORKERS
        maxRequestsPerHost = MAX_WORKERS
      })
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(45, TimeUnit.SECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .followRedirects(true)
      .followSslRedirects(true)
      .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
      .build()
  }

  init {
    repeat(MAX_WORKERS) {
      workers += scope.launch { workerLoop() }
    }
  }

  fun reset() {
    done = false
    notFound.clear()
    retryAt.clear()
    queued.clear()
    queue.clear()
    inFlight.clear()
    lastError = "-"
    httpOk = 0L
    httpFail = 0L
    http404 = 0L
    decodeAccepted = 0L
  }

  /**
   * Queue the nearest visible textures first. PrimDecoder already returns
   * texture UUIDs in distance order, so this queue preserves spatial priority.
   */
  fun requestVisible(ids: List<String>, max: Int = 24): Int {
    if (max <= 0 || ids.isEmpty()) return 0
    if (textureBaseUrls().isEmpty()) return 0
    var added = 0
    val now = System.currentTimeMillis()

    for (raw in ids.distinct()) {
      if (added >= max || queue.size >= MAX_QUEUE) break
      val uuid = raw.lowercase()
      try { UUID.fromString(uuid) } catch (_: Throwable) { continue }
      if (ImageAssets.has(uuid) || inFlight.contains(uuid) || queued.contains(uuid) || notFound.contains(uuid)) continue
      if ((retryAt[uuid] ?: 0L) > now) continue
      if (queued.add(uuid)) {
        queue.offer(Request(uuid, 1000 - added, now))
        added++
      }
    }
    return added
  }

  /** Compatibility hook for a TextureEntry event; actual work stays in the bounded queue. */
  fun kick(onLine: (String) -> Unit) {
    try {
      val uuid = PrimDecoder.pollTexFull()?.lowercase() ?: return
      if (requestVisible(listOf(uuid), 1) > 0) onLine("TEX-QUEUE id8=" + uuid.take(8))
    } catch (_: Throwable) {}
  }

  private fun textureBaseUrls(): List<String> {
    val out = LinkedHashSet<String>()
    try { CapsManager.caps["ViewerAsset"]?.takeIf { it.isNotBlank() }?.let { out.add(it) } } catch (_: Throwable) {}
    try { CapsManager.caps["GetTexture"]?.takeIf { it.isNotBlank() }?.let { out.add(it) } } catch (_: Throwable) {}
    return out.toList()
  }

  private suspend fun workerLoop() {
    while (currentCoroutineContext().isActive) {
      val request = try { queue.take() } catch (_: InterruptedException) { continue }
      queued.remove(request.uuid)
      if (ImageAssets.has(request.uuid) || notFound.contains(request.uuid)) continue
      inFlight.add(request.uuid)
      try {
        val ok = fetch(request)
        if (!ok && !notFound.contains(request.uuid) && request.attempt < MAX_RETRIES) {
          request.attempt++
          val backoff = 1500L * (1L shl (request.attempt - 1))
          retryAt[request.uuid] = System.currentTimeMillis() + backoff
          delay(backoff)
          retryAt.remove(request.uuid)
          if (!ImageAssets.has(request.uuid) && queued.add(request.uuid)) queue.offer(request)
        }
      } catch (e: Throwable) {
        httpFail++
        lastError = e::class.java.simpleName + ":" + (e.message ?: "").take(120)
        if (!notFound.contains(request.uuid) && request.attempt < MAX_RETRIES) {
          request.attempt++
          delay(1500L * (1L shl (request.attempt - 1)))
          if (!ImageAssets.has(request.uuid) && queued.add(request.uuid)) queue.offer(request)
        }
      } finally {
        inFlight.remove(request.uuid)
      }
    }
  }

  private suspend fun fetch(request: Request): Boolean {
    if (textureBaseUrls().isEmpty()) return false

    // Asset URLs are region-scoped. A 403 on a previously valid URL can mean
    // the ViewerAsset/CDN capability became stale after a region transition.
    // Reacquire caps once (bounded by CapsManager) and retry against fresh URLs.
    var refreshed = false
    while (true) {
      val bases = textureBaseUrls()
      if (bases.isEmpty()) return false
      var retryWithFreshCaps = false

      for ((index, base) in bases.withIndex()) {
      val url = base.trimEnd('/') + "/?texture_id=" + request.uuid
      try {
        val http = okhttp3.Request.Builder()
          .url(url)
          .header("Accept", "image/x-j2c, image/jp2, image/jpeg, image/png, image/webp, image/*")
          .header("Accept-Encoding", "identity")
          .header("User-Agent", "EPHORASL/7.70")
          .build()

        client.newCall(http).execute().use { response ->
          when {
            response.code == 404 -> {
              http404++
              httpFail++
              lastError = "HTTP404"
              if (index == bases.lastIndex) notFound.add(request.uuid)
            }

            !response.isSuccessful -> {
              httpFail++
              lastError = "HTTP" + response.code
              if (response.code == 403 && !refreshed) {
                retryWithFreshCaps = true
              }
            }

            else -> {
              val body = response.body?.bytes() ?: ByteArray(0)
              if (body.isEmpty() || body.size > MAX_BYTES) {
                httpFail++
                lastError = "bad-body=" + body.size
              } else {
                httpOk++
                val contentType = response.header("Content-Type") ?: "application/octet-stream"
                if (ImageAssets.acceptHttpTexture(request.uuid, contentType, body)) {
                  decodeAccepted++
                  done = true
                  try {
                    AgentLoop.onTick?.invoke(
                      "TEX-HTTP-OK id8=" + request.uuid.take(8) +
                        " bytes=" + body.size +
                        " code=" + response.code +
                        " ct=" + contentType +
                        " base=" + if (index == 0) "ViewerAsset" else "GetTexture"
                    )
                  } catch (_: Throwable) {}
                  return true
                }
                httpFail++
                lastError = "decode-rejected"
              }
            }
          }
        }
      } catch (e: Throwable) {
        httpFail++
        lastError = e::class.java.simpleName + ":" + (e.message ?: "").take(120)
      }
      if (retryWithFreshCaps) break
    }

    if (retryWithFreshCaps && !refreshed) {
      refreshed = true
      try {
        val ok = CapsManager.refreshTextureCaps("HTTP403")
        if (ok) continue
      } catch (_: Throwable) {}
    }
    return false
  }

  fun status(): String {
    return "TEX-HTTP ok=" + httpOk +
      " fail=" + httpFail +
      " 404=" + http404 +
      " accepted=" + decodeAccepted +
      " queued=" + queue.size +
      " inflight=" + inFlight.size +
      " cache=" + ImageAssets.bitmapCount() +
      " last=" + lastError
  }
}
