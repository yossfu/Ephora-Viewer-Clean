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
  private var running = false
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
  private var cube: FloatBuffer? = null
  private var sphere: FloatBuffer? = null
  private var terrain: FloatBuffer? = null
  private var terrainIndices: ShortBuffer? = null
  private var terrainCount = 0
  private var terrainVersion = -1L
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
  private var orbitPitch = 0.28
  private var orbitDistance = 16.0
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
  private var sceneObjects = 0
  private val glTextures = LinkedHashMap<String, Int>()
  private var texturedObjects = 0

  fun applyMetrics(w: Int, h: Int) { width = w.coerceAtLeast(1); height = h.coerceAtLeast(1) }

  fun start(surface: GLSurfaceView, fw: Int = 0, fh: Int = 0): Boolean {
    return try {
      view = surface
      if (fw > 1 && fh > 1) applyMetrics(fw, fh) else applyMetrics(surface.width, surface.height)
      if (configuredSurface !== surface) {
        surface.setEGLContextClientVersion(2)
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surface.preserveEGLContextOnPause = true
        surface.setRenderer(this)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        configuredSurface = surface
      } else surface.onResume()
      surface.setOnTouchListener { _, e -> onTouch(e); true }
      running = true
      startOk = true
      lastFase = "esperando-GLES"
      true
    } catch (e: Throwable) {
      initError = e.javaClass.simpleName + ":" + (e.message ?: "")
      lastFase = "start"
      false
    }
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
      glTextures.clear()
      cube = makeCube()
      sphere = makeSphere()
      terrainVersion = -1L
      lastFase = "GLES-ok"
    } catch (e: Throwable) {
      initError = "GLES " + e.javaClass.simpleName + ":" + (e.message ?: "")
      lastFase = "GLES-error"
    }
  }

  override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
    width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
    GLES20.glViewport(0, 0, width, height)
    Matrix.perspectiveM(projection, 0, 54f, width.toFloat() / height.toFloat(), 0.1f, 1800f)
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
      Matrix.multiplyMM(vp, 0, projection, 0, camera, 0)
      drawCount = 0
      texturedObjects = 0
      updateTerrain()
      drawTerrain()
      drawWater()
      drawAvatar(p, q, r)
      val n = objects.size.coerceAtMost(MAX_OBJECTS)
      for (i in 0 until n) {
        val o = objects[i]
        val x = o.x - 128.0; val y = o.z; val z = -(o.y - 128.0)
        val minX = eyeX - 220.0; val maxX = eyeX + 220.0
        val minZ = eyeZ - 220.0; val maxZ = eyeZ + 220.0
        if (x < minX || x > maxX || z < minZ || z > maxZ) continue
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
        drawMesh(if (isAvatar) sphere else cube, if (isAvatar) SPHERE_VERTS else CUBE_VERTS, x, y, z, sx, sy, sz, o.yaw, color,
          if (isAvatar) "" else o.tex, o.texScaleS, o.texScaleT, o.texOffsetS, o.texOffsetT, o.texRotation)
        if (!isAvatar && o.tex.isNotEmpty() && glTextures.containsKey(o.tex.lowercase())) texturedObjects++
      }
      sceneObjects = n
      fpsFrames++
      val now = SystemClock.elapsedRealtime()
      if (fpsT0 == 0L) fpsT0 = now
      if (now - fpsT0 >= 3000L) { fps = (fpsFrames * 1000L / (now - fpsT0).coerceAtLeast(1L)).toInt(); fpsFrames = 0; fpsT0 = now }
      lastFrame = now
      if (now - lastStats >= 5000L) { lastStats = now; try { onStats?.invoke(gfxLine()) } catch (_: Throwable) {} }
    } catch (e: Throwable) {
      initError = "frame " + e.javaClass.simpleName + ":" + (e.message ?: "")
      lastFase = "frame-error"
    }
  }
  private var lastStats = 0L

  private fun drawMesh(buffer: FloatBuffer?, vertexCount: Int, x: Double, y: Double, z: Double, sx: Float, sy: Float, sz: Float, yaw: Float, color: FloatArray,
                       textureUuid: String = "", scaleS: Float = 1f, scaleT: Float = 1f, offsetS: Float = 0f, offsetT: Float = 0f, rotation: Float = 0f) {
    val b = buffer ?: return
    Matrix.setIdentityM(model, 0)
    Matrix.translateM(model, 0, x.toFloat(), y.toFloat(), z.toFloat())
    Matrix.rotateM(model, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
    Matrix.scaleM(model, 0, sx, sy, sz)
    Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
    GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
    GLES20.glUniform4fv(colorLoc, 1, color, 0)
    GLES20.glUniform4f(uvTransformLoc, scaleS, scaleT, offsetS, offsetT)
    GLES20.glUniform1f(uvRotationLoc, rotation)
    val texId = if (textureUuid.isNotEmpty()) textureFor(textureUuid) else 0
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
    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
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
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
    GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
    glTextures[key] = id
    while (glTextures.size > 48) {
      val oldestKey = glTextures.keys.firstOrNull() ?: break
      val oldest = glTextures.remove(oldestKey) ?: continue
      GLES20.glDeleteTextures(1, intArrayOf(oldest), 0)
    }
    return id
  }

  private fun drawTerrain() {
    val b = terrain ?: return
    val ib = terrainIndices ?: return
    Matrix.setIdentityM(mvp, 0); Matrix.multiplyMM(mvp, 0, vp, 0, mvp, 0)
    GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
    GLES20.glUniform4f(colorLoc, 0.38f, 0.48f, 0.29f, 1f)
    GLES20.glUniform1i(useTextureLoc, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    b.position(0); GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, STRIDE, b)
    b.position(3); GLES20.glVertexAttribPointer(normalLoc, 3, GLES20.GL_FLOAT, false, STRIDE, b)
    b.position(6); GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, STRIDE, b)
    GLES20.glEnableVertexAttribArray(posLoc); GLES20.glEnableVertexAttribArray(normalLoc); GLES20.glEnableVertexAttribArray(uvLoc)
    ib.position(0)
    GLES20.glDrawElements(GLES20.GL_TRIANGLES, terrainCount, GLES20.GL_UNSIGNED_SHORT, ib)
    drawCount++
  }
  private fun drawWater() {
    val b = cube ?: return
    drawMesh(b, CUBE_VERTS, 0.0, 19.8, 0.0, 256f, 0.08f, 256f, 0f, floatArrayOf(0.16f, 0.40f, 0.58f, 1f))
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
      val h = TerrainMesh.heightAt(i * TERRAIN_STEP, j * TERRAIN_STEP)
      values[j * n + i] = if (h.isFinite()) h else mean
    }
    val vertices = ByteBuffer.allocateDirect(n * n * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
    for (j in 0 until n) for (i in 0 until n) {
      val h = values[j * n + i]
      val hx = values[j * n + (i + 1).coerceAtMost(n - 1)] - values[j * n + (i - 1).coerceAtLeast(0)]
      val hz = values[(j + 1).coerceAtMost(n - 1) * n + i] - values[(j - 1).coerceAtLeast(0) * n + i]
      var nx = -hx; var ny = 4f; var nz = hz
      val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.001f)
      nx /= len; ny /= len; nz /= len
      vertices.put(i * TERRAIN_STEP - 128f).put(h).put(-(j * TERRAIN_STEP - 128f)).put(nx).put(ny).put(nz).put(i / 4f).put(j / 4f)
    }
    vertices.position(0)
    val inds = ByteBuffer.allocateDirect((n - 1) * (n - 1) * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    for (j in 0 until n - 1) for (i in 0 until n - 1) {
      val a = j * n + i; val b = a + 1; val c = a + n; val d = c + 1
      inds.put(a.toShort()).put(c.toShort()).put(b.toShort()); inds.put(b.toShort()).put(c.toShort()).put(d.toShort())
    }
    inds.position(0)
    terrain = vertices; terrainIndices = inds; terrainCount = inds.capacity(); terrainVersion = TerrainMesh.version
  }

  private fun makeCube(): FloatBuffer {
    val faces = arrayOf(
      floatArrayOf(1f,0f,0f, .5f,-.5f,-.5f, .5f,.5f,-.5f, .5f,.5f,.5f, .5f,-.5f,.5f),
      floatArrayOf(-1f,0f,0f, -.5f,-.5f,.5f, -.5f,.5f,.5f, -.5f,.5f,-.5f, -.5f,-.5f,-.5f),
      floatArrayOf(0f,1f,0f, -.5f,.5f,-.5f, -.5f,.5f,.5f, .5f,.5f,.5f, .5f,.5f,-.5f),
      floatArrayOf(0f,-1f,0f, -.5f,-.5f,.5f, -.5f,-.5f,-.5f, .5f,-.5f,-.5f, .5f,-.5f,.5f),
      floatArrayOf(0f,0f,1f, -.5f,-.5f,.5f, .5f,-.5f,.5f, .5f,.5f,.5f, -.5f,.5f,.5f),
      floatArrayOf(0f,0f,-1f, .5f,-.5f,-.5f, -.5f,-.5f,-.5f, -.5f,.5f,-.5f, .5f,.5f,-.5f))
    val uv = floatArrayOf(0f,0f, 1f,0f, 1f,1f, 0f,1f)
    val out = ByteBuffer.allocateDirect(CUBE_VERTS * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
    for (f in faces) {
      val ids = intArrayOf(0,1,2,0,2,3)
      for (id in ids) { val k = 3 + id * 3; out.put(f[k]).put(f[k+1]).put(f[k+2]).put(f[0]).put(f[1]).put(f[2]).put(uv[id*2]).put(uv[id*2+1]) }
    }
    out.position(0); return out
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
  private fun linkProgram(vs: String, fs: String): Int {
    fun compile(type: Int, src: String): Int { val sh = GLES20.glCreateShader(type); GLES20.glShaderSource(sh, src); GLES20.glCompileShader(sh); val ok = IntArray(1); GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0); if (ok[0] == 0) throw IllegalStateException(GLES20.glGetShaderInfoLog(sh)); return sh }
    val v = compile(GLES20.GL_VERTEX_SHADER, vs); val f = compile(GLES20.GL_FRAGMENT_SHADER, fs); val p = GLES20.glCreateProgram()
    GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p); val ok = IntArray(1); GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0); if (ok[0] == 0) throw IllegalStateException(GLES20.glGetProgramInfoLog(p)); GLES20.glDeleteShader(v); GLES20.glDeleteShader(f); return p
  }
  fun gfxLine(): String = "GFX-DIAG backend=GLES fps=$fps obj=$sceneObjects tex=" + texturedObjects + " cacheGPU=" + glTextures.size + " draws=$drawCount terrain=" + TerrainMesh.patchesGot() + "/256 startOk=" + (if (startOk) "si" else "no") + " fase=$lastFase initErr=" + (initError ?: "-") + " eye=" + "%.1f,%.1f,%.1f".format(targetX + cos(orbitYaw)*orbitDistance, targetY + sin(orbitPitch)*orbitDistance, targetZ + sin(orbitYaw)*orbitDistance) + " target=" + "%.1f,%.1f,%.1f".format(targetX,targetY,targetZ) + " " + ImageAssets.status()
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
    private const val TERRAIN_RES = 129
    private const val TERRAIN_STEP = 2
    private const val MAX_OBJECTS = 2048
    private const val VERTEX = """
      attribute vec3 aPosition; attribute vec3 aNormal; attribute vec2 aUv; uniform mat4 uMvp; varying vec3 vNormal; varying vec2 vUv;
      void main(){ gl_Position=uMvp*vec4(aPosition,1.0); vNormal=aNormal; vUv=vec2(aUv.x,1.0-aUv.y); }
    """
    private const val FRAGMENT = """
      precision mediump float; uniform vec4 uColor; uniform vec4 uUvTransform; uniform float uUvRotation; uniform sampler2D uTexture; uniform int uUseTexture; varying vec3 vNormal; varying vec2 vUv;
      void main(){ vec3 n=normalize(vNormal); float l=0.38+0.62*max(dot(n,normalize(vec3(-0.35,0.88,0.28))),0.0); vec2 p=(vUv-vec2(0.5))*uUvTransform.xy; float c=cos(uUvRotation); float s=sin(uUvRotation); p=mat2(c,s,-s,c)*p+vec2(0.5)+uUvTransform.zw; vec4 base=uUseTexture==1?texture2D(uTexture,p)*uColor:uColor; gl_FragColor=vec4(base.rgb*l,base.a); }
    """
  }
}