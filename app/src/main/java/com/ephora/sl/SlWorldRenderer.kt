package com.ephora.sl

import android.content.Context
import android.opengl.GLUtils
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** Clean OpenGL ES world view. Network/circuit state stays in AgentLoop and TerrainMesh. */
class SlWorldRenderer(private val ctx: Context) : GLSurfaceView.Renderer {
  var onStats: ((String) -> Unit)? = null
  var initError: String? = null
  var lastFase = "inicio"
  var startOk = false
    private set
  @Volatile private var running = false
  private var view: GLSurfaceView? = null
  private var configuredSurface: GLSurfaceView? = null
  private var width = 1
  private var height = 1
  private var program = 0
  private var posLoc = -1
  private var normalLoc = -1
  private var mvpLoc = -1
  private var colorLoc = -1
  private var uvLoc = -1
  private var uvTransformLoc = -1
  private var uvRotationLoc = -1
  private var samplerLoc = -1
  private var useTextureLoc = -1
  private var terrainModeLoc = -1
  private var terrainSamplerLocs = IntArray(4) { -1 }
  private var terrainStartLoc = -1
  private var terrainRangeLoc = -1
  private var cube: FloatBuffer? = null
  private var sphere: FloatBuffer? = null
  private var cylinder: FloatBuffer? = null
  private var waterPlane: FloatBuffer? = null
  private var terrain: FloatBuffer? = null
  private var terrainIndices: ShortBuffer? = null
  private var terrainCount = 0
  private var terrainVersion = -1L
  private var terrainTextureUuid = ""
  private var terrainGpuTextures = 0
  private var terrainBitmapHits = 0
  private var terrainBitmapMisses = 0
  private val projection = FloatArray(16)
  private val camera = FloatArray(16)
  private val model = FloatArray(16)
  private val vp = FloatArray(16)
  private val mvp = FloatArray(16)
  private val identity = FloatArray(16)
  private var targetX = 0.0
  private var targetY = 25.0
  private var targetZ = 0.0
  private var orbitYaw = 0.25
  private var orbitPitch = 0.10
  private var orbitDistance = 12.0
  private var downX = 0f
  private var downY = 0f
  private var downSpan = 0f
  private var fpsT0 = 0L
  private var fpsFrames = 0
  private var fps = 0
  private var drawCount = 0
  private var touchCount = 0L
  private var lastTouch = 0L
  private var lastFrame = 0L
  @Volatile private var firstFrameLatencyMs = -1L
  @Volatile private var glSurfaceCreated = false
  @Volatile private var glSurfaceChanged = false
  private var openStartedAt = 0L
  private var openGeneration = 0
  private var sceneObjects = 0
  private var meshReferences = 0
  private var meshObjects = 0
  private val glTextures = LinkedHashMap<String, Int>()
  private var texturedObjects = 0
  @Volatile private var frPub = 0
  @Volatile private var frCull = 0
  @Volatile private var frMesh = 0
  @Volatile private var frShaped = 0
  @Volatile private var frShapedTry = 0
  @Volatile private var frTex = 0
  @Volatile private var frBeige = 0
  @Volatile private var frAvatar = 0
  @Volatile private var frMuMesh = "-"
  @Volatile private var frMuShaped = "-"
  @Volatile private var frMuTex = "-"
  @Volatile private var frMuBeige = "-"
  @Volatile private var frDiffLatch = "sin-frame-aun"
  private var frPrev = LinkedHashSet<Long>()
  private var lastFrDiff = 0L
  private fun probeTex(o: PrimDecoder.Prim): String {
    var u = ""
    try { u = o.texFaces.firstOrNull()?.uuid ?: o.tex } catch(_: Throwable) {}
    try { if (u == NULL_TEXTURE_UUID) return "" } catch(_: Throwable) { return "" }
    return u
  }
  private fun texHit(o: PrimDecoder.Prim): Boolean {
    var u = ""
    try { u = probeTex(o) } catch(_: Throwable) {}
    try { if (u.isEmpty()) return false } catch(_: Throwable) { return false }
    try { return glTextures.containsKey(u.lowercase()) } catch(_: Throwable) { return false }
  }
  private fun sid(s: String, o: PrimDecoder.Prim): String {
    return s + (if (s.isEmpty()) "" else " ") + o.id.toString() + ":" + "%.0f,%.0f,%.0f".format(o.x, o.y, o.z)
  }
  private fun updateFrDiff(objects: List<PrimDecoder.Prim>) {
    val ids = LinkedHashSet<Long>()
    try { for (o in objects) ids.add(o.id) } catch(_: Throwable) {}
    var ap: List<Long> = emptyList()
    try { ap = ids.filter { !frPrev.contains(it) } } catch(_: Throwable) {}
    var go: List<Long> = emptyList()
    try { go = frPrev.filter { !ids.contains(it) } } catch(_: Throwable) {}
    try { frDiffLatch = "pantalla-entra=" + ap.size + " [" + ap.take(3).joinToString(" ") + "] pantalla-sale=" + go.size + " [" + go.take(3).joinToString(" ") + "]" } catch(_: Throwable) {}
    try { frPrev.clear() } catch(_: Throwable) {}
    try { frPrev.addAll(ids) } catch(_: Throwable) {}
  }
  fun frameLine(): String {
    return "ADV-FRAME pub=" + frPub + " cull=" + frCull + " mesh=" + frMesh + " forma=" + frShaped + " formaTry=" + frShapedTry + " tex=" + frTex + " beige=" + frBeige + " avatar=" + frAvatar + " malla=[" + frMuMesh + "] formaM=[" + frMuShaped + "] texM=[" + frMuTex + "] beigeM=[" + frMuBeige + "] " + frDiffLatch
  }

  fun applyMetrics(w: Int, h: Int) { width = w.coerceAtLeast(1); height = h.coerceAtLeast(1) }

  /** Register the GL renderer before the hidden SurfaceView is first made visible. */
  fun prepare(surface: GLSurfaceView): Boolean {
    return try {
      view = surface
      if (configuredSurface !== surface) {
        surface.setEGLContextClientVersion(2)
        surface.setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        surface.preserveEGLContextOnPause = true
        surface.setRenderer(this)
        configuredSurface = surface
      }
      surface.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
      true
    } catch (e: Throwable) {
      initError = "prepare " + e.javaClass.simpleName + ":" + (e.message ?: "")
      lastFase = "prepare-error"
      false
    }
  }

  fun start(surface: GLSurfaceView, fw: Int = 0, fh: Int = 0): Boolean {
    return try {
      view = surface
      if (fw > 1 && fh > 1) applyMetrics(fw, fh) else applyMetrics(surface.width, surface.height)
      openGeneration++
      val generation = openGeneration
      openStartedAt = SystemClock.elapsedRealtime()
      firstFrameLatencyMs = -1L
      running = true
      startOk = true
      lastFase = if (glSurfaceCreated && glSurfaceChanged) "GLES-preparado" else "esperando-superficie"
      if (!prepare(surface)) throw IllegalStateException(initError ?: "renderer-prepare")
      surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
      surface.onResume()
      surface.requestRender()
      surface.post {
        if (running) {
          try { surface.requestRender() } catch (_: Throwable) {}
          requestFirstFrame(surface, generation, 0)
        }
      }
      surface.setOnTouchListener { _, e -> onTouch(e); true }
      true
    } catch (e: Throwable) {
      running = false
      startOk = false
      initError = e.javaClass.simpleName + ":" + (e.message ?: "")
      lastFase = "start"
      false
    }
  }

  /** Keep nudging a newly visible surface while Android completes its first traversal. */
  private fun requestFirstFrame(surface: GLSurfaceView, generation: Int, attempt: Int) {
    if (!running || generation != openGeneration || firstFrameLatencyMs >= 0L || attempt >= 16) return
    try {
      if (!surface.holder.surface.isValid || !surface.isShown) lastFase = "esperando-superficie"
      surface.requestRender()
      surface.postDelayed({ requestFirstFrame(surface, generation, attempt + 1) }, 150L)
    } catch (_: Throwable) {}
  }

  private fun onTouch(e: MotionEvent) {
    try {
      val now = SystemClock.elapsedRealtime()
      touchCount++
      lastTouch = now
      if (e.pointerCount > 1) {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        val span = kotlin.math.sqrt(dx * dx + dy * dy)
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) downSpan = span
        else if (e.actionMasked == MotionEvent.ACTION_MOVE && downSpan > 0f) {
          orbitDistance = (orbitDistance * (downSpan / span.coerceAtLeast(1f))).coerceIn(3.0, 180.0)
          downSpan = span
        }
      } else when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
        MotionEvent.ACTION_MOVE -> {
          val dx = e.x - downX; val dy = e.y - downY
          orbitYaw -= dx * 0.006
          orbitPitch = (orbitPitch + dy * 0.004).coerceIn(-1.15, 1.15)
          downX = e.x; downY = e.y
        }
        MotionEvent.ACTION_UP -> if (now - lastTap < 350L) recenter()
      }
      if (e.actionMasked == MotionEvent.ACTION_UP) lastTap = now
    } catch (_: Throwable) {}
  }
  private var lastTap = 0L
  private fun recenter() {
    targetX = AgentLoop.px - 128.0
    targetY = AgentLoop.pz
    targetZ = -(AgentLoop.py - 128.0)
  }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    try {
      GLES20.glClearColor(0.34f, 0.55f, 0.78f, 1f)
      GLES20.glEnable(GLES20.GL_DEPTH_TEST)
      GLES20.glDepthFunc(GLES20.GL_LEQUAL)
      GLES20.glDisable(GLES20.GL_BLEND)
      program = linkProgram(VERTEX, FRAGMENT)
      posLoc = GLES20.glGetAttribLocation(program, "aPosition")
      normalLoc = GLES20.glGetAttribLocation(program, "aNormal")
      uvLoc = GLES20.glGetAttribLocation(program, "aUv")
      mvpLoc = GLES20.glGetUniformLocation(program, "uMvp")
      colorLoc = GLES20.glGetUniformLocation(program, "uColor")
      uvTransformLoc = GLES20.glGetUniformLocation(program, "uUvTransform")
      uvRotationLoc = GLES20.glGetUniformLocation(program, "uUvRotation")
      samplerLoc = GLES20.glGetUniformLocation(program, "uTexture")
      useTextureLoc = GLES20.glGetUniformLocation(program, "uUseTexture")
      terrainModeLoc = GLES20.glGetUniformLocation(program, "uTerrainMode")
      terrainSamplerLocs = IntArray(4) { GLES20.glGetUniformLocation(program, "uTerrain$it") }
      terrainStartLoc = GLES20.glGetUniformLocation(program, "uTerrainStart")
      terrainRangeLoc = GLES20.glGetUniformLocation(program, "uTerrainRange")
      glTextures.clear()
      cube = makeCube()
      sphere = makeSphere()
      cylinder = makeCylinder()
      waterPlane = makeWaterPlane()
      terrainVersion = -1L
      glSurfaceCreated = true
      glSurfaceChanged = false
      lastFase = "GLES-contexto"
    } catch (e: Throwable) {
      initError = "GLES " + e.javaClass.simpleName + ":" + (e.message ?: "")
      lastFase = "GLES-error"
    }
  }

  override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
    width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
    GLES20.glViewport(0, 0, width, height)
    Matrix.perspectiveM(projection, 0, 54f, width.toFloat() / height.toFloat(), 0.1f, 1800f)
    glSurfaceChanged = true
    lastFase = "GLES-superficie-${width}x$height"
  }

  override fun onDrawFrame(gl: GL10?) {
    if (!running || program == 0) return
    try {
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
      GLES20.glUseProgram(program)
      val objects = AgentLoop.objetos
      val p = AgentLoop.px - 128.0; val q = AgentLoop.pz; val r = -(AgentLoop.py - 128.0)
      targetX = p; targetY = q; targetZ = r
      val cp = cos(orbitPitch); val sp = sin(orbitPitch)
      val eyeX = targetX + cos(orbitYaw) * cp * orbitDistance
      val eyeY = targetY + sp * orbitDistance
      val eyeZ = targetZ + sin(orbitYaw) * cp * orbitDistance
      Matrix.setLookAtM(camera, 0, eyeX.toFloat(), eyeY.toFloat(), eyeZ.toFloat(), targetX.toFloat(), targetY.toFloat(), targetZ.toFloat(), 0f, 1f, 0f)
      val slEx = eyeX + 128.0
      val slEy = -eyeZ + 128.0
      val slEz = eyeY
      val slTx = targetX + 128.0
      val slTy = -targetZ + 128.0
      val slTz = targetY
      var cax = slTx - slEx
      var cay = slTy - slEy
      var caz = slTz - slEz
      var cal = Math.sqrt(cax * cax + cay * cay + caz * caz)
      if (cal < 1e-6) cal = 1.0
      cax /= cal
      cay /= cal
      caz /= cal
      var crx = cay
      var cry = -cax
      var crz = 0.0
      var crl = Math.sqrt(crx * crx + cry * cry)
      if (crl < 1e-6) crx = 1.0
      if (crl < 1e-6) cry = 0.0
      if (crl < 1e-6) crl = 1.0
      crx /= crl
      cry /= crl
      val cux = cry * caz - crz * cay
      val cuy = crz * cax - crx * caz
      val cuz = crx * cay - cry * cax
      try { AgentLoop.camVec = floatArrayOf(slEx.toFloat(), slEy.toFloat(), slEz.toFloat(), cax.toFloat(), cay.toFloat(), caz.toFloat(), (-crx).toFloat(), (-cry).toFloat(), (-crz).toFloat(), cux.toFloat(), cuy.toFloat(), cuz.toFloat()) } catch(_: Throwable) {}
      Matrix.multiplyMM(vp, 0, projection, 0, camera, 0)
      drawCount = 0
      PrimShapes.budget = 1000
      texturedObjects = 0
      meshReferences = 0
      meshObjects = 0
      updateTerrain()
      drawTerrain()
      if (DRAW_WATER_SURFACE) drawWater()
      drawAvatar(p, q, r)
      val n = objects.size.coerceAtMost(MAX_OBJECTS)
      val minX = eyeX - 220.0; val maxX = eyeX + 220.0
      val minZ = eyeZ - 220.0; val maxZ = eyeZ + 220.0
      val visibleMeshIds = ArrayList<String>()
      for (i in 0 until n) {
        val o = objects[i]
        val x = o.x - 128.0
        val z = -(o.y - 128.0)
        if (o.tipo != 47 && o.meshId.isNotEmpty() && x in minX..maxX && z in minZ..maxZ) {
          visibleMeshIds.add(o.meshId)
        }
      }
      MeshAssets.updateVisibleMeshes(visibleMeshIds)
      frPub = n
      frCull = 0
      frMesh = 0
      frShaped = 0
      frShapedTry = 0
      frTex = 0
      frBeige = 0
      frAvatar = 0
      var sMeshIds = ""
      var sShapedIds = ""
      var sTexIds = ""
      var sBeigeIds = ""
      for (i in 0 until n) {
        val o = objects[i]
        val x = o.x - 128.0; val y = o.z; val z = -(o.y - 128.0)
        if (x < minX || x > maxX || z < minZ || z > maxZ) { try { frCull++ } catch(_: Throwable) {}; continue }
        val sx = o.sx.coerceIn(0.05f, 64f); val sy = o.sy.coerceIn(0.05f, 64f); val sz = o.sz.coerceIn(0.05f, 64f)
        val isAvatar = o.tipo == 47
        val color = when {
          isAvatar -> floatArrayOf(0.12f, 0.74f, 0.86f, 1f)
          o.tex.isNotEmpty() -> floatArrayOf(o.texR, o.texG, o.texB, o.texA)
          o.tipo == 9 -> floatArrayOf(0.72f, 0.66f, 0.52f, 1f)
          o.mat == 3 -> floatArrayOf(0.55f, 0.35f, 0.20f, 1f)
          o.mat == 1 -> floatArrayOf(0.57f, 0.62f, 0.66f, 1f)
          else -> floatArrayOf(0.66f, 0.63f, 0.58f, 1f)
        }
        val isSphere = isAvatar || (o.pathCurve == 0x20 || o.pathCurve == 0x21) && (o.profileCurve and 0x0f) == 0
        val isCylinder = !isSphere && o.pathCurve == 0x10 && (o.profileCurve and 0x0f) == 0
        val mesh = if (isSphere) sphere else if (isCylinder) cylinder else cube
        val vertexCount = if (isSphere) SPHERE_VERTS else if (isCylinder) CYLINDER_VERTS else CUBE_VERTS
        val meshGeometry = if (!isAvatar && o.meshId.isNotEmpty()) MeshAssets.mesh(o.meshId) else null
        val shaped = if (!isAvatar && o.hasShape) PrimShapes.obtain(PrimShapes.quantize(o.pathCurve, o.profileCurve, o.shPb, o.shPe, o.shPsx, o.shPsy, o.shShx, o.shShy, o.shTw, o.shTwb, o.shRo, o.shTpx, o.shTpy, o.shRev, o.shSk, o.shQb, o.shQe, o.shQh)) else null
        if (!isAvatar && o.meshId.isNotEmpty()) meshReferences++
        try { if (!isAvatar && o.hasShape) frShapedTry++ } catch(_: Throwable) {}
        if (isAvatar) frAvatar++
        else if (meshGeometry != null) frMesh++
        else if (shaped != null) frShaped++
        else if (texHit(o)) frTex++ else frBeige++
        try { if (!isAvatar && meshGeometry != null && frMesh <= 3) sMeshIds = sid(sMeshIds, o) } catch(_: Throwable) {}
        try { if (!isAvatar && meshGeometry == null && shaped != null && frShaped <= 3) sShapedIds = sid(sShapedIds, o) } catch(_: Throwable) {}
        try { if (!isAvatar && meshGeometry == null && shaped == null && texHit(o) && frTex <= 3) sTexIds = sid(sTexIds, o) } catch(_: Throwable) {}
        try { if (!isAvatar && meshGeometry == null && shaped == null && !texHit(o) && frBeige <= 3) sBeigeIds = sid(sBeigeIds, o) } catch(_: Throwable) {}
        if (meshGeometry != null) {
          meshObjects++
          for ((faceIndex, face) in meshGeometry.faces.withIndex()) {
            if (face == null) continue
            val tf = o.texFaces.getOrNull(faceIndex) ?: o.texFaces.firstOrNull()
            val tex = tf?.uuid?.takeUnless { it == NULL_TEXTURE_UUID } ?: o.tex
            val tint = if (tf != null) floatArrayOf(tf.r, tf.g, tf.b, tf.a) else color
            drawMesh(face.vertices, face.vertexCount, x, y, z, sx, sy, sz, o.yaw, tint, tex,
              tf?.scaleS ?: o.texScaleS, tf?.scaleT ?: o.texScaleT,
              tf?.offsetS ?: o.texOffsetS, tf?.offsetT ?: o.texOffsetT, tf?.rotation ?: o.texRotation)
          }
        } else if (o.meshId.isEmpty() && shaped != null) {
          val face0 = o.texFaces.firstOrNull()
          val tex0 = face0?.uuid?.takeUnless { it == NULL_TEXTURE_UUID } ?: o.tex
          val tint0 = if (face0 != null) floatArrayOf(face0.r, face0.g, face0.b, face0.a) else color
          drawMesh(shaped.buf, shaped.count, x, y, z, sx, sy, sz, o.yaw, tint0, tex0,
            face0?.scaleS ?: o.texScaleS, face0?.scaleT ?: o.texScaleT,
            face0?.offsetS ?: o.texOffsetS, face0?.offsetT ?: o.texOffsetT,
            face0?.rotation ?: o.texRotation)
        } else if (isAvatar) {
          drawMesh(mesh, vertexCount, x, y, z, sx, sy, sz, o.yaw, color)
        } else {
          // Never fabricate a cube for a real-world object. Until its primitive
          // shape or mesh LOD is decoded, keep it pending/invisible. This avoids
          // the misleading “all cubes” scene and lets the world converge to the
          // actual Second Life geometry as assets arrive.
          continue
        }
      }
      try { frMuMesh = if (sMeshIds.isEmpty()) "-" else sMeshIds } catch(_: Throwable) {}
      try { frMuShaped = if (sShapedIds.isEmpty()) "-" else sShapedIds } catch(_: Throwable) {}
      try { frMuTex = if (sTexIds.isEmpty()) "-" else sTexIds } catch(_: Throwable) {}
      try { frMuBeige = if (sBeigeIds.isEmpty()) "-" else sBeigeIds } catch(_: Throwable) {}
      val nowFr = SystemClock.elapsedRealtime()
      try { if (nowFr - lastFrDiff >= 5000L) { lastFrDiff = nowFr; updateFrDiff(objects) } } catch(_: Throwable) {}
      sceneObjects = n
      fpsFrames++
      val now = SystemClock.elapsedRealtime()
      if (fpsT0 == 0L) fpsT0 = now
      if (now - fpsT0 >= 3000L) { fps = (fpsFrames * 1000L / (now - fpsT0).coerceAtLeast(1L)).toInt(); fpsFrames = 0; fpsT0 = now }
      lastFrame = now
      if (firstFrameLatencyMs < 0L) {
        firstFrameLatencyMs = (now - openStartedAt).coerceAtLeast(0L)
        lastFase = "GLES-ok"
      }
      if (now - lastStats >= 5000L) { lastStats = now; try { onStats?.invoke(gfxLine()) } catch (_: Throwable) {} }
    } catch (e: Throwable) {
      initError = "frame " + e.javaClass.simpleName + ":" + (e.message ?: "")
      lastFase = "frame-error"
    }
  }
  private var lastStats = 0L

  private fun drawMesh(buffer: FloatBuffer?, vertexCount: Int, x: Double, y: Double, z: Double, sx: Float, sy: Float, sz: Float, yaw: Float, color: FloatArray,
                       textureUuid: String = "", scaleS: Float = 1f, scaleT: Float = 1f, offsetS: Float = 0f, offsetT: Float = 0f, rotation: Float = 0f, firstVertex: Int = 0) {
    val b = buffer ?: return
    Matrix.setIdentityM(model, 0)
    Matrix.translateM(model, 0, x.toFloat(), y.toFloat(), z.toFloat())
    Matrix.rotateM(model, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
    Matrix.scaleM(model, 0, sx, sy, sz)
    Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
    GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
    GLES20.glUniform4fv(colorLoc, 1, color, 0)
    GLES20.glUniform1i(terrainModeLoc, 0)
    GLES20.glUniform4f(uvTransformLoc, scaleS, scaleT, offsetS, offsetT)
    GLES20.glUniform1f(uvRotationLoc, rotation)
    val texId = if (textureUuid.isNotEmpty()) textureFor(textureUuid) else 0
    if (texId != 0) texturedObjects++
    GLES20.glUniform1i(useTextureLoc, if (texId != 0) 1 else 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
    GLES20.glUniform1i(samplerLoc, 0)
    b.position(0)
    GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, STRIDE, b)
    b.position(3)
    GLES20.glVertexAttribPointer(normalLoc, 3, GLES20.GL_FLOAT, false, STRIDE, b)
    b.position(6)
    GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, STRIDE, b)
    GLES20.glEnableVertexAttribArray(posLoc); GLES20.glEnableVertexAttribArray(normalLoc); GLES20.glEnableVertexAttribArray(uvLoc)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, firstVertex, vertexCount)
    drawCount++
  }
  private fun textureFor(uuid: String): Int {
    val key = uuid.lowercase()
    glTextures[key]?.let { return it }
    val bitmap = ImageAssets.bitmap(key) ?: return 0
    val names = IntArray(1)
    GLES20.glGenTextures(1, names, 0)
    val id = names[0]
    if (id == 0) return 0
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
    try {
      // Clear a stale GL error first; otherwise an unrelated previous draw call
      // can make a perfectly valid texture upload look like a failure.
      while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { }
      GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      val pot = fun(v: Int): Boolean = v > 0 && (v and (v - 1)) == 0
      val isPot = pot(bitmap.width) && pot(bitmap.height)
      if (isPot) {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
      } else {
        // GLES2 does not guarantee REPEAT+mipmap for NPOT textures.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
      }
      val err = GLES20.glGetError()
      if (err != GLES20.GL_NO_ERROR) {
        GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        return 0
      }
    } catch (_: Throwable) {
      try { GLES20.glDeleteTextures(1, intArrayOf(id), 0) } catch(_: Throwable) {}
      return 0
    }
    glTextures[key] = id
    while (glTextures.size > 192) {
      val oldestKey = glTextures.keys.firstOrNull { it !in TerrainComposition.textureIds().map(String::lowercase) } ?: break
      val oldest = glTextures.remove(oldestKey) ?: continue
      GLES20.glDeleteTextures(1, intArrayOf(oldest), 0)
    }
    return id
  }

  private fun drawTerrain() {
    val b = terrain ?: return
    val ib = terrainIndices ?: return
    terrainTextureUuid = TerrainComposition.baseTexture()
    Matrix.setIdentityM(mvp, 0); Matrix.multiplyMM(mvp, 0, vp, 0, mvp, 0)
    GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
    GLES20.glUniform4f(colorLoc, 0.38f, 0.48f, 0.29f, 1f)
    GLES20.glUniform4f(uvTransformLoc, 1f, 1f, 0f, 0f)
    GLES20.glUniform1f(uvRotationLoc, 0f)
    val terrainIds = TerrainComposition.detailTextures().take(4)
    val fallbackId = terrainIds.firstOrNull { it.isNotEmpty() } ?: terrainTextureUuid
    terrainBitmapHits = 0
    terrainBitmapMisses = 0
    val fallbackTexture = if (fallbackId.isNotEmpty()) textureFor(fallbackId) else 0
    val textureIds = IntArray(4) { i ->
      val uuid = terrainIds.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: fallbackId
      if (uuid.isNotEmpty()) {
        if (ImageAssets.has(uuid)) terrainBitmapHits++ else terrainBitmapMisses++
        textureFor(uuid).takeIf { it != 0 } ?: fallbackTexture
      } else fallbackTexture
    }
    val hasTerrainTexture = textureIds.any { it != 0 }
    terrainGpuTextures = textureIds.count { it != 0 }
    GLES20.glUniform4f(colorLoc, if (hasTerrainTexture) 1f else 0.38f, if (hasTerrainTexture) 1f else 0.48f, if (hasTerrainTexture) 1f else 0.29f, 1f)
    fun corners(values: List<Float>, fallback: Float): FloatArray =
      if (values.size < 4) floatArrayOf(fallback, fallback, fallback, fallback)
      else floatArrayOf(values[0], values[1], values[2], values[3])
    GLES20.glUniform4fv(terrainStartLoc, 1, corners(TerrainComposition.startHeights(), 0f), 0)
    GLES20.glUniform4fv(terrainRangeLoc, 1, corners(TerrainComposition.heightRanges(), 40f), 0)
    GLES20.glUniform1i(terrainModeLoc, if (hasTerrainTexture) 1 else 0)
    GLES20.glUniform1i(useTextureLoc, 0)
    for (i in 0 until 4) {
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[i])
      GLES20.glUniform1i(terrainSamplerLocs[i], i)
    }
    b.position(0); GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, STRIDE, b)
    b.position(3); GLES20.glVertexAttribPointer(normalLoc, 3, GLES20.GL_FLOAT, false, STRIDE, b)
    b.position(6); GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, STRIDE, b)
    GLES20.glEnableVertexAttribArray(posLoc); GLES20.glEnableVertexAttribArray(normalLoc); GLES20.glEnableVertexAttribArray(uvLoc)
    ib.position(0)
    GLES20.glDrawElements(GLES20.GL_TRIANGLES, terrainCount, GLES20.GL_UNSIGNED_SHORT, ib)
    GLES20.glUniform1i(terrainModeLoc, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    drawCount++
  }
  private fun drawWater() {
    val b = waterPlane ?: return
    GLES20.glEnable(GLES20.GL_BLEND)
    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    GLES20.glDepthMask(false)
    drawMesh(b, 6, 0.0, WATER_LEVEL.toDouble(), 0.0, 256f, 1f, 256f, 0f, floatArrayOf(0.10f, 0.34f, 0.50f, 0.48f))
    GLES20.glDepthMask(true)
    GLES20.glDisable(GLES20.GL_BLEND)
  }
  private fun drawAvatar(x: Double, y: Double, z: Double) {
    drawMesh(sphere, SPHERE_VERTS, x, y + 0.9, z, 0.42f, 0.9f, 0.32f, 0f, floatArrayOf(0.12f, 0.76f, 0.86f, 1f))
  }

  private fun updateTerrain() {
    if (terrainVersion == TerrainMesh.version && terrain != null) return
    val n = TERRAIN_RES
    val mean = TerrainMesh.meanH().takeIf { it.isFinite() } ?: 22f
    val values = FloatArray(n * n)
    for (j in 0 until n) for (i in 0 until n) {
      values[j * n + i] = TerrainMesh.heightAtFilled(i * TERRAIN_STEP, j * TERRAIN_STEP, mean)
    }
    val vertices = ByteBuffer.allocateDirect(n * n * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
    for (j in 0 until n) for (i in 0 until n) {
      val h = values[j * n + i]
      val hx = values[j * n + (i + 1).coerceAtMost(n - 1)] - values[j * n + (i - 1).coerceAtLeast(0)]
      val hz = values[(j + 1).coerceAtMost(n - 1) * n + i] - values[(j - 1).coerceAtLeast(0) * n + i]
      var nx = -hx; var ny = 4f; var nz = hz
      val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.001f)
      nx /= len; ny /= len; nz /= len
      // SL terrain detail textures repeat in world space. A 16 m repeat prevents the
      // severe stretching caused by mapping one 256 m region across a single image.
      vertices.put(i * TERRAIN_STEP - 128f).put(h).put(-(j * TERRAIN_STEP - 128f)).put(nx).put(ny).put(nz).put(i * TERRAIN_STEP / 16f).put(j * TERRAIN_STEP / 16f)
    }
    vertices.position(0)
    val inds = ByteBuffer.allocateDirect((n - 1) * (n - 1) * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    for (j in 0 until n - 1) for (i in 0 until n - 1) {
      val a = j * n + i; val b = a + 1; val c = a + n; val d = c + 1
      inds.put(a.toShort()).put(c.toShort()).put(b.toShort()); inds.put(b.toShort()).put(c.toShort()).put(d.toShort())
    }
    inds.position(0)
    terrain = vertices; terrainIndices = inds; terrainCount = inds.capacity(); terrainVersion = TerrainMesh.version
    terrainTextureUuid = TerrainComposition.baseTexture()
  }

  private fun makeCube(): FloatBuffer {
    val faces = arrayOf(
      floatArrayOf(0f,1f,0f, -.5f,.5f,-.5f, -.5f,.5f,.5f, .5f,.5f,.5f, .5f,.5f,-.5f),
      floatArrayOf(0f,-1f,0f, -.5f,-.5f,.5f, -.5f,-.5f,-.5f, .5f,-.5f,-.5f, .5f,-.5f,.5f),
      floatArrayOf(0f,0f,1f, -.5f,-.5f,.5f, .5f,-.5f,.5f, .5f,.5f,.5f, -.5f,.5f,.5f),
      floatArrayOf(1f,0f,0f, .5f,-.5f,-.5f, .5f,.5f,-.5f, .5f,.5f,.5f, .5f,-.5f,.5f),
      floatArrayOf(0f,0f,-1f, .5f,-.5f,-.5f, -.5f,-.5f,-.5f, -.5f,.5f,-.5f, .5f,.5f,-.5f),
      floatArrayOf(-1f,0f,0f, -.5f,-.5f,.5f, -.5f,.5f,.5f, -.5f,.5f,-.5f, -.5f,-.5f,-.5f))
    val uv = floatArrayOf(0f,0f, 1f,0f, 1f,1f, 0f,1f)
    val out = ByteBuffer.allocateDirect(CUBE_VERTS * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
    for (f in faces) {
      val ids = intArrayOf(0,1,2,0,2,3)
      for (id in ids) { val k = 3 + id * 3; out.put(f[k]).put(f[k+1]).put(f[k+2]).put(f[0]).put(f[1]).put(f[2]).put(uv[id*2]).put(uv[id*2+1]) }
    }
    out.position(0); return out
  }
  private fun makeWaterPlane(): FloatBuffer {
    val out = ByteBuffer.allocateDirect(6 * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
    val v = arrayOf(
      floatArrayOf(-0.5f, 0f, -0.5f, 0f, 1f, 0f, 0f, 0f),
      floatArrayOf( 0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 0f),
      floatArrayOf( 0.5f, 0f,  0.5f, 0f, 1f, 0f, 1f, 1f),
      floatArrayOf(-0.5f, 0f, -0.5f, 0f, 1f, 0f, 0f, 0f),
      floatArrayOf( 0.5f, 0f,  0.5f, 0f, 1f, 0f, 1f, 1f),
      floatArrayOf(-0.5f, 0f,  0.5f, 0f, 1f, 0f, 0f, 1f))
    for (vertex in v) out.put(vertex)
    out.position(0)
    return out
  }
  private fun makeSphere(): FloatBuffer {
    val out = ByteBuffer.allocateDirect(SPHERE_VERTS * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
    val latN = 12; val lonN = 16
    fun vertex(lat: Int, lon: Int) {
      val a = Math.PI * lat / latN; val b = 2.0 * Math.PI * lon / lonN
      val x = (sin(a) * cos(b)).toFloat(); val y = cos(a).toFloat(); val z = (sin(a) * sin(b)).toFloat()
      out.put(x*.5f).put(y*.5f).put(z*.5f).put(x).put(y).put(z).put(lon.toFloat()/lonN).put(lat.toFloat()/latN)
    }
    for (la in 0 until latN) for (lo in 0 until lonN) {
      vertex(la,lo); vertex(la+1,lo); vertex(la+1,lo+1)
      vertex(la,lo); vertex(la+1,lo+1); vertex(la,lo+1)
    }
    out.position(0); return out
  }
  private fun makeCylinder(): FloatBuffer {
    val segments = 16
    val out = ByteBuffer.allocateDirect(CYLINDER_VERTS * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
    fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, t: Float) {
      out.put(x).put(y).put(z).put(nx).put(ny).put(nz).put(u).put(t)
    }
    for (i in 0 until segments) {
      val a0 = 2.0 * Math.PI * i / segments
      val a1 = 2.0 * Math.PI * (i + 1) / segments
      val x0 = (0.5 * kotlin.math.cos(a0)).toFloat(); val z0 = (0.5 * kotlin.math.sin(a0)).toFloat()
      val x1 = (0.5 * kotlin.math.cos(a1)).toFloat(); val z1 = (0.5 * kotlin.math.sin(a1)).toFloat()
      val n0x = (2.0 * x0).toFloat(); val n0z = (2.0 * z0).toFloat()
      val n1x = (2.0 * x1).toFloat(); val n1z = (2.0 * z1).toFloat()
      val u0 = i.toFloat() / segments; val u1 = (i + 1).toFloat() / segments
      v(x0,-0.5f,z0,n0x,0f,n0z,u0,0f); v(x0,0.5f,z0,n0x,0f,n0z,u0,1f); v(x1,0.5f,z1,n1x,0f,n1z,u1,1f)
      v(x0,-0.5f,z0,n0x,0f,n0z,u0,0f); v(x1,0.5f,z1,n1x,0f,n1z,u1,1f); v(x1,-0.5f,z1,n1x,0f,n1z,u1,0f)
      v(0f,0.5f,0f,0f,1f,0f,0.5f,0.5f); v(x1,0.5f,z1,0f,1f,0f,0.5f+x1,0.5f+z1); v(x0,0.5f,z0,0f,1f,0f,0.5f+x0,0.5f+z0)
      v(0f,-0.5f,0f,0f,-1f,0f,0.5f,0.5f); v(x0,-0.5f,z0,0f,-1f,0f,0.5f+x0,0.5f+z0); v(x1,-0.5f,z1,0f,-1f,0f,0.5f+x1,0.5f+z1)
    }
    out.position(0)
    return out
  }
  private fun linkProgram(vs: String, fs: String): Int {
    fun compile(type: Int, src: String): Int { val sh = GLES20.glCreateShader(type); GLES20.glShaderSource(sh, src); GLES20.glCompileShader(sh); val ok = IntArray(1); GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0); if (ok[0] == 0) throw IllegalStateException(GLES20.glGetShaderInfoLog(sh)); return sh }
    val v = compile(GLES20.GL_VERTEX_SHADER, vs); val f = compile(GLES20.GL_FRAGMENT_SHADER, fs); val p = GLES20.glCreateProgram()
    GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p); val ok = IntArray(1); GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0); if (ok[0] == 0) throw IllegalStateException(GLES20.glGetProgramInfoLog(p)); GLES20.glDeleteShader(v); GLES20.glDeleteShader(f); return p
  }
  fun gfxLine(): String = "GFX-DIAG backend=GLES fps=$fps firstFrameMs=$firstFrameLatencyMs surfaceCreated=" + (if (glSurfaceCreated) "si" else "no") + " surfaceChanged=" + (if (glSurfaceChanged) "si" else "no") + " holderValid=" + (if (try { view?.holder?.surface?.isValid == true } catch (_: Throwable) { false }) "si" else "no") + " shown=" + (if (try { view?.isShown == true } catch (_: Throwable) { false }) "si" else "no") + " obj=$sceneObjects meshRef=$meshReferences meshReady=$meshObjects tex=" + texturedObjects + " terrainTex=" + (if (terrainTextureUuid.isNotEmpty()) terrainTextureUuid.take(8) else "-") + " terrainGpu=$terrainGpuTextures terrainBmp=$terrainBitmapHits/$terrainBitmapMisses" + " cacheGPU=" + glTextures.size + " draws=$drawCount terrain=" + TerrainMesh.patchesGot() + "/256 water=" + (if (DRAW_WATER_SURFACE) "on" else "off") + " frameAgeMs=" + frameAgeMs() + " startOk=" + (if (startOk) "si" else "no") + " fase=$lastFase initErr=" + (initError ?: "-") + " eye=" + "%.1f,%.1f,%.1f".format(targetX + cos(orbitYaw)*orbitDistance, targetY + sin(orbitPitch)*orbitDistance, targetZ + sin(orbitYaw)*orbitDistance) + " target=" + "%.1f,%.1f,%.1f".format(targetX,targetY,targetZ) + " " + TerrainComposition.status() + " " + ImageAssets.status() + " " + TexFetch.status() + " " + MeshAssets.status() + " " + PrimShapes.status()
  fun sunState(): String = "WORLD-SCENE backend=GLES mesh=procedural terrainPatches=" + TerrainMesh.patchesGot()
  fun projectLabel(fx: Double, fy: Double, fz: Double): Pair<Float,Float>? = null
  fun frameAgeMs(): Long = if (lastFrame > 0L) (SystemClock.elapsedRealtime() - lastFrame).coerceAtLeast(0L) else -1L
  fun touchAgeMs(): Long = if (lastTouch > 0L) (SystemClock.elapsedRealtime() - lastTouch).coerceAtLeast(0L) else -1L
  fun isAlive(): Boolean = running
  fun stop() { running = false; try { view?.setOnTouchListener(null); view?.onPause() } catch (_: Throwable) {} }

  companion object {
    private const val STRIDE = 8 * 4
    private const val CUBE_VERTS = 36
    private const val SPHERE_VERTS = 12 * 16 * 6
    private const val CYLINDER_VERTS = 16 * 12
    private const val WATER_LEVEL = 20f
    private const val DRAW_WATER_SURFACE = false
    private const val TERRAIN_RES = 129
    private const val TERRAIN_STEP = 2
    private const val MAX_OBJECTS = 2048
    private const val NULL_TEXTURE_UUID = "00000000-0000-0000-0000-000000000000"
    private const val VERTEX = """
      attribute vec3 aPosition; attribute vec3 aNormal; attribute vec2 aUv; uniform mat4 uMvp; varying vec3 vNormal; varying vec2 vUv; varying vec3 vTerrainPos;
      void main(){ gl_Position=uMvp*vec4(aPosition,1.0); vNormal=aNormal; vUv=vec2(aUv.x,1.0-aUv.y); vTerrainPos=aPosition; }
    """
    private const val FRAGMENT = """
      precision mediump float;
      uniform vec4 uColor; uniform vec4 uUvTransform; uniform float uUvRotation;
      uniform sampler2D uTexture; uniform int uUseTexture; uniform int uTerrainMode;
      uniform sampler2D uTerrain0; uniform sampler2D uTerrain1; uniform sampler2D uTerrain2; uniform sampler2D uTerrain3;
      uniform vec4 uTerrainStart; uniform vec4 uTerrainRange;
      varying vec3 vNormal; varying vec2 vUv; varying vec3 vTerrainPos;
      void main(){
        vec3 n=normalize(vNormal);
        float l=0.38+0.62*max(dot(n,normalize(vec3(-0.35,0.88,0.28))),0.0);
        vec4 base;
        if(uTerrainMode==1){
          float x=clamp((vTerrainPos.x+128.0)/256.0,0.0,1.0);
          float y=clamp((128.0-vTerrainPos.z)/256.0,0.0,1.0);
          float sh=mix(mix(uTerrainStart.x,uTerrainStart.y,x),mix(uTerrainStart.z,uTerrainStart.w,x),y);
          float hr=mix(mix(uTerrainRange.x,uTerrainRange.y,x),mix(uTerrainRange.z,uTerrainRange.w,x),y);
          float layer=clamp((vTerrainPos.y-sh)*4.0/max(hr,0.01),0.0,3.0);
          vec4 t0=texture2D(uTerrain0,vUv); vec4 t1=texture2D(uTerrain1,vUv);
          vec4 t2=texture2D(uTerrain2,vUv); vec4 t3=texture2D(uTerrain3,vUv);
          if(layer<1.0) base=mix(t0,t1,layer);
          else if(layer<2.0) base=mix(t1,t2,layer-1.0);
          else base=mix(t2,t3,layer-2.0);
          base*=uColor;
        } else {
          vec2 p=(vUv-vec2(0.5))*uUvTransform.xy;
          float c=cos(uUvRotation); float s=sin(uUvRotation);
          p=mat2(c,s,-s,c)*p+vec2(0.5)+uUvTransform.zw;
          base=uUseTexture==1?texture2D(uTexture,p)*uColor:uColor;
        }
        gl_FragColor=vec4(base.rgb*l,base.a);
      }
    """
  }
}
