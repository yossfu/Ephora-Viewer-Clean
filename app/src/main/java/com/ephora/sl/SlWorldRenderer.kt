package com.ephora.sl
import android.content.Context
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Viewport
import com.google.android.filament.View as FView
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.GestureDetector
import com.google.android.filament.utils.Manipulator
import java.nio.ByteBuffer
import java.nio.ByteOrder
class SlWorldRenderer(val ctx: Context) {
  var onStats: ((String) -> Unit)? = null
  var initError: String? = null
  var lastFase = "inicio"
  var startOk = false
  private var touchEyeX = Double.NaN
  private var touchEyeY = Double.NaN
  private var touchEyeZ = Double.NaN
    private set
  companion object EngineSet {
    var engine: Engine? = null
    var filt: Renderer? = null
    var scene: Scene? = null
    var fview: FView? = null
    var camera: Camera? = null
    var loader: AssetLoader? = null
    var resLoader: ResourceLoader? = null
    var provider: UbershaderProvider? = null
    var boxBytes: ByteArray? = null
    var waterBytes: ByteArray? = null
    var ground: FilamentAsset? = null
    var water: FilamentAsset? = null
    var me: FilamentAsset? = null
    val primSlots = ArrayList<FilamentAsset?>()
    val terrSlots = ArrayList<FilamentAsset?>()
    var terrVer = -1L
    var terraAsset: FilamentAsset? = null
    var terraBuiltVer = -1L
    var terraHMin = Float.NaN
    var terraHMax = Float.NaN
    var meshOk = false
    var boxesFallback = false
    var lastTerraBuild = 0L
    var groundY = 22.0
    var groundReal = 22.0
    var groundSunk = false
    var terraPhLogged = 0L
    val slotId = LongArray(256) { -1L }
    val slotTipo = IntArray(256) { -999 }
    val slotMat = IntArray(256) { -2 }
    var sunEnt = 0
    var camEnt = 0
    var iniciado = false
  }
  private var swapChain: SwapChain? = null
  private var uiHelper: UiHelper? = null
  private var running = false
  private var stopped = false
  private var framesTotal = 0L
  private var frames = 0L
  private var statT0 = 0L
  private var lastFps = 0
  private var camX = 0.0
  private var camY = 0.0
  private var camZ = 0.0
  private var tgtX = 0.0
  private var tgtY = 25.0
  private var tgtZ = 0.0
  private var manip: Manipulator? = null
  private var lastReattach = false
  private var gestures: GestureDetector? = null
  private var boundView: SurfaceView? = null
  private var vw = 1
  private var vh = 1
  private var lastFrameT = 0L
  private var lastFrameMs = 0L
  private var lastTapT = 0L
  private var lastTapX = 0f
  private var lastTapY = 0f
  private var drawLogged = false
  private var drawLogT = 0L
  private var lastObjM = 0
  private var lastEscenaN = 0
  private var touchCount = 0L
  private var lastTouchT = 0L
  private var lastRebuildT = 0L
  private var lastFolAx = Double.NaN
  private var lastFolAy = Double.NaN
  private var lastFolAz = Double.NaN
  private var camKeepT = 0L
  private var buildOkLogged = false
  @Volatile private var camErr: String? = null
  private val choreographer = Choreographer.getInstance()
  private fun mat(tx: Double, ty: Double, tz: Double, s: Double): FloatArray {
    return floatArrayOf(s.toFloat(), 0f, 0f, 0f, 0f, s.toFloat(), 0f, 0f, 0f, 0f, s.toFloat(), 0f, tx.toFloat(), ty.toFloat(), tz.toFloat(), 1f)
  }
  private fun matS(tx: Double, ty: Double, tz: Double, sx: Float, sy: Float, sz: Float, yaw: Float): FloatArray {
    val c = Math.cos(yaw.toDouble()).toFloat()
    val s = Math.sin(yaw.toDouble()).toFloat()
    return floatArrayOf(c * sx, 0f, -s * sx, 0f, 0f, sy, 0f, 0f, s * sz, 0f, c * sz, 0f, tx.toFloat(), ty.toFloat(), tz.toFloat(), 1f)
  }
  private fun tint(a: FilamentAsset?, r: Float, g: Float, b: Float) {
    try {
      val eng = EngineSet.engine ?: return
      val rm = eng.renderableManager
      val asset = a ?: return
      for (e in asset.entities) {
        try {
          val inst = rm.getInstance(e)
          if (inst == 0) continue
          val pc = rm.getPrimitiveCount(inst)
          for (p in 0 until pc) {
            try { rm.getMaterialInstanceAt(inst, p)?.setParameter("baseColorFactor", r, g, b, 1f) } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
      }
    } catch(_: Throwable) {}
  }
  private fun tintA(a: FilamentAsset?, r: Float, g: Float, b: Float, al: Float) {
    try {
      val eng = EngineSet.engine ?: return
      val rm = eng.renderableManager
      val asset = a ?: return
      for (e in asset.entities) {
        try {
          val inst = rm.getInstance(e)
          if (inst == 0) continue
          val pc = rm.getPrimitiveCount(inst)
          for (p in 0 until pc) {
            try { rm.getMaterialInstanceAt(inst, p)?.setParameter("baseColorFactor", r, g, b, al) } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
      }
    } catch(_: Throwable) {}
  }
  private fun spawn(sx: Double, sy: Double, sz: Double, s: Double): FilamentAsset? {
    try {
      val eng = EngineSet.engine ?: return null
      val bb = ByteBuffer.wrap(EngineSet.boxBytes ?: return null)
      val a = EngineSet.loader?.createAsset(bb) ?: return null
      EngineSet.resLoader?.loadResources(a)
      EngineSet.scene?.addEntities(a.entities)
      eng.transformManager.setTransform(a.root, mat(sx, sy, sz, s))
      return a
    } catch(_: Throwable) { return null }
  }
  private fun spawnWater(sx: Double, sy: Double, sz: Double, s: Double): FilamentAsset? {
    try {
      val eng = EngineSet.engine ?: return null
      val bb = ByteBuffer.wrap(EngineSet.waterBytes ?: return null)
      val a = EngineSet.loader?.createAsset(bb) ?: return null
      EngineSet.resLoader?.loadResources(a)
      EngineSet.scene?.addEntities(a.entities)
      eng.transformManager.setTransform(a.root, mat(sx, sy, sz, s))
      return a
    } catch(_: Throwable) { return null }
  }
  private fun buildManip(ntx: Double, nty: Double, ntz: Double, ox: Double, oy: Double, oz: Double) {
    try {
      if (vw <= 1 || vh <= 1) { camErr = "punto=buildManip-abort-sin-metricas vw=" + vw + "x" + vh; return }
      val nm = Manipulator.Builder().viewport(vw.coerceAtLeast(1), vh.coerceAtLeast(1)).targetPosition(ntx.toFloat(), nty.toFloat(), ntz.toFloat()).orbitHomePosition((ntx + ox).toFloat(), (nty + oy).toFloat(), (ntz + oz).toFloat()).orbitSpeed(0.01f, 0.01f).zoomSpeed(0.01f).build(Manipulator.Mode.ORBIT)
      manip = nm
      try {
        val bv = boundView
        if (bv != null) gestures = GestureDetector(bv, nm)
        else camErr = "punto=buildManip-gestures-bv-null"
      } catch(e: Throwable) { camErr = "punto=buildManip-gestures exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") }
      tgtX = ntx
      tgtY = nty
      tgtZ = ntz
      if (!buildOkLogged) {
        buildOkLogged = true
        val hd = Math.sqrt(ox * ox + oy * oy + oz * oz)
        try { onStats?.invoke("BUILD-MANIP-OK vw=" + vw + "x" + vh + " homeDist=" + "%.1f".format(hd) + " tgt=" + "%.1f,%.1f,%.1f".format(ntx, nty, ntz)) } catch(_: Throwable) {}
      }
    } catch(e: Throwable) {
      val st = e.stackTrace
      val ln = if (st != null && st.isNotEmpty()) st[0].lineNumber else -1
      camErr = "punto=buildManip exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") + " linea=" + ln
    }
  }
  private fun keepOffset(): Triple<Double, Double, Double> {
    try {
      val m0 = manip
      if (m0 != null) {
        val e = FloatArray(3)
        val g = FloatArray(3)
        val u = FloatArray(3)
        m0.getLookAt(e, g, u)
        val ox = (e[0] - g[0]).toDouble()
        val oy = (e[1] - g[1]).toDouble()
        val oz = (e[2] - g[2]).toDouble()
        val l = Math.sqrt(ox * ox + oy * oy + oz * oz)
        if (l.isFinite()) {
          if (l >= 2.0 && l <= 200.0) return Triple(ox, oy, oz)
        }
      }
    } catch(_: Throwable) {}
    return Triple(0.0, 4.0, 9.0)
  }
  private val clampE = FloatArray(3)
  private val clampG = FloatArray(3)
  private val clampU = FloatArray(3)
  private var camClampLogged = false
  private var camSpawnFixed = false
  private var camClampT = 0L
  private var camClamp2Logged = false
  private fun groundAtWx(wx: Double, wz: Double): Double {
    try {
      val sx = (wx + 128.0).toInt()
      val sy = (128.0 - wz).toInt()
      if (sx < 0 || sx > 255 || sy < 0 || sy > 255) return Double.NaN
      val h = TerrainMesh.heightAt(sx, sy)
      if (!h.isFinite()) return Double.NaN
      return h.toDouble()
    } catch (_: Throwable) { return Double.NaN }
  }
  private fun safeOrbit(ax: Double, ay: Double, az: Double, ox: Double, oy: Double, oz: Double) {
    try {
      val g = groundAtWx(ax, az)
      var oy2 = oy
      if (g.isFinite() && ay.isFinite()) {
        val want = Math.max(ay, g + 6.0) + 4.0 - ay
        if (want > oy2) oy2 = want
      }
      buildManip(ax, ay, az, ox, oy2, oz)
    } catch (_: Throwable) {
      try { buildManip(ax, ay, az, ox, oy, oz) } catch (_: Throwable) {}
    }
  }
  private fun clampCam() {
    try {
      val m0 = manip ?: return
      m0.getLookAt(clampE, clampG, clampU)
      val hE = groundAtWx(clampE[0].toDouble(), clampE[2].toDouble())
      val hT = groundAtWx(clampG[0].toDouble(), clampG[2].toDouble())
      val eLow = hE.isFinite() && clampE[1].toDouble() < hE + 1.0
      val tLow = hT.isFinite() && clampG[1].toDouble() < hT + 1.0
      if (!eLow && !tLow) {
        val nowK = System.currentTimeMillis()
        if (nowK - camKeepT > 10000L) {
          camKeepT = nowK
          try {
            val ox = (clampE[0] - clampG[0]).toDouble()
            val oy = (clampE[1] - clampG[1]).toDouble()
            val oz = (clampE[2] - clampG[2]).toDouble()
            onStats?.invoke("CAM-KEEP off=" + "%.1f,%.1f,%.1f".format(ox, oy, oz) + " eyeY=" + "%.1f".format(clampE[1]) + " tgtY=" + "%.1f".format(clampG[1]))
          } catch(_: Throwable) {}
        }
        try {
          if (camClampLogged && !camClamp2Logged && nowK - camClampT > 30000L) {
            camClamp2Logged = true
            onStats?.invoke("CAM-CLAMP2 eyeY=" + "%.1f".format(clampE[1]) + " tgtY=" + "%.1f".format(clampG[1]) + " avatarY=" + "%.1f".format(AgentLoop.pz) + " post-spawn")
          }
        } catch(_: Throwable) {}
        return
      }
      var ny = clampG[1].toDouble()
      if (tLow) ny = hT + 2.0
      var ey = clampE[1].toDouble()
      if (eLow) ey = hE + 2.0
      if (ey < ny + 1.0) ey = ny + 1.0
      buildManip(clampG[0].toDouble(), ny, clampG[2].toDouble(), clampE[0].toDouble() - clampG[0].toDouble(), ey - ny, clampE[2].toDouble() - clampG[2].toDouble())
      try { camSpawnFixed = true } catch(_: Throwable) {}
      if (!camClampLogged) {
        camClampLogged = true
        try { camClampT = System.currentTimeMillis() } catch(_: Throwable) {}
        try { onStats?.invoke("CAM-CLAMP eyeY=" + "%.1f".format(ey) + " tgtY=" + "%.1f".format(ny) + " avatarY=" + "%.1f".format(AgentLoop.pz) + " spawn-sobre-suelo solo-sube") } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}
  }
  private fun recenter() {
    try {
      val ax = AgentLoop.px - 128.0
      val az = -(AgentLoop.py - 128.0)
      val ay = AgentLoop.pz
      safeOrbit(ax, ay, az, 0.0, 4.0, 9.0)
    } catch(_: Throwable) {}
  }
  fun applyMetrics(w: Int, h: Int) {
    try {
      val mw = w.coerceAtLeast(1)
      val mh = h.coerceAtLeast(1)
      vw = mw
      vh = mh
      try {
        EngineSet.fview?.viewport = Viewport(0, 0, mw, mh)
        val a = mw.toDouble() / mh.toDouble().coerceAtLeast(1.0)
        EngineSet.camera?.setProjection(45.0, a, 0.5, 2000.0, Camera.Fov.VERTICAL)
      } catch(_: Throwable) {}
      try { manip?.setViewport(mw, mh) } catch(_: Throwable) {}
    } catch(_: Throwable) {}
  }
  fun start(sv: SurfaceView, fw: Int = 0, fh: Int = 0): Boolean {
    var fase = "inicio"
    try {
      if (running) {
        try { onStats?.invoke("3D-YA-ABIERTO loop-unico") } catch(_: Throwable) {}
        return false
      }
    } catch(_: Throwable) {}
    try {
      val needInit: Boolean
      synchronized(EngineSet) { needInit = !EngineSet.iniciado }
      try { lastReattach = !needInit } catch(_: Throwable) {}
      if (needInit) {
        fase = "engine"
        try { System.loadLibrary("filament-jni") } catch(e: Throwable) { initError = "fase=jni-filament exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje"); try { lastFase = fase } catch(_: Throwable) {}; try { stop() } catch(_: Throwable) {}; return false }
        try { System.loadLibrary("gltfio-jni") } catch(e: Throwable) { initError = "fase=jni-gltfio exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje"); try { lastFase = fase } catch(_: Throwable) {}; try { stop() } catch(_: Throwable) {}; return false }
        try { System.loadLibrary("filament-utils-jni") } catch(e: Throwable) { initError = "fase=jni-utils exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje"); try { lastFase = fase } catch(_: Throwable) {}; try { stop() } catch(_: Throwable) {}; return false }
        val eng = Engine.create()
        val ins = ctx.assets.open("models/box.glb")
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        while (true) { val r = ins.read(tmp); if (r < 0) break; buf.write(tmp, 0, r) }
        try { ins.close() } catch(_: Throwable) {}
        fase = "box.glb"
        val bytes = buf.toByteArray()
        val wins = ctx.assets.open("models/water.glb")
        val wbuf = java.io.ByteArrayOutputStream()
        while (true) { val r = wins.read(tmp); if (r < 0) break; wbuf.write(tmp, 0, r) }
        try { wins.close() } catch(_: Throwable) {}
        val wbytes = wbuf.toByteArray()
        fase = "provider"
        val prov = UbershaderProvider(eng)
        fase = "loader"
        val ld = AssetLoader(eng, prov, EntityManager.get())
        val rl = ResourceLoader(eng)
        fase = "escena"
        val sc = eng.createScene()
        fase = "skybox+sol"
        sc.skybox = Skybox.Builder().color(0.35f, 0.55f, 0.85f, 1f).build(eng)
        val sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f, 0.96f, 0.9f).intensity(60000f).direction(0.4f, -1f, 0.25f).castShadows(false).build(eng, sun)
        sc.addEntity(sun)
        fase = "vista+camara"
        val vw = eng.createView()
        vw.scene = sc
        val ce = EntityManager.get().create()
        val cam = eng.createCamera(ce)
        vw.camera = cam
        val fr = eng.createRenderer()
        fase = "suelo+proxies"
        synchronized(EngineSet) {
          EngineSet.engine = eng
          EngineSet.boxBytes = bytes
          EngineSet.waterBytes = wbytes
          EngineSet.provider = prov
          EngineSet.loader = ld
          EngineSet.resLoader = rl
          EngineSet.scene = sc
          EngineSet.sunEnt = sun
          EngineSet.fview = vw
          EngineSet.camEnt = ce
          EngineSet.camera = cam
          EngineSet.filt = fr
        }
        EngineSet.ground = spawn(0.0, 22.0, 0.0, 1.0)
        try { EngineSet.ground?.let { eng.transformManager.setTransform(it.root, floatArrayOf(256f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 256f, 0f, 0f, 22.0f, 0f, 1f)) } } catch(_: Throwable) {}
        tint(EngineSet.ground, 0.25f, 0.5f, 0.28f)
        EngineSet.water = spawnWater(0.0, 20.0, 0.0, 1.0)
        try { EngineSet.water?.let { eng.transformManager.setTransform(it.root, floatArrayOf(256f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 256f, 0f, 0f, 20.0f, 0f, 1f)) } } catch(_: Throwable) {}
        tintA(EngineSet.water, 0.2f, 0.5f, 0.95f, 0.55f)
        EngineSet.me = spawn(0.0, 1.0, 0.0, 1.0)
        tint(EngineSet.me, 0.13f, 0.83f, 0.93f)
        repeat(12) { EngineSet.primSlots.add(spawn(0.0, -100.0, 0.0, 0.001)) }
        for (a in EngineSet.primSlots) tint(a, 0.6f, 0.6f, 0.6f)
        synchronized(EngineSet) { EngineSet.iniciado = true }
      }
      fase = "reattach"
      val uh = UiHelper()
      uiHelper = uh
      uh.renderCallback = object : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
          try {
            val e2 = EngineSet.engine ?: return
            swapChain?.let { try { e2.destroySwapChain(it) } catch(_: Throwable) {} }
            swapChain = e2.createSwapChain(surface)
          } catch(_: Throwable) {}
        }
        override fun onDetachedFromSurface() {
          try {
            val e2 = EngineSet.engine ?: return
            swapChain?.let { try { e2.destroySwapChain(it) } catch(_: Throwable) {} }
            swapChain = null
          } catch(_: Throwable) {}
        }
        override fun onResized(width: Int, height: Int) {
          try {
            EngineSet.fview?.viewport = Viewport(0, 0, width, height)
            val a = width.toDouble() / height.toDouble().coerceAtLeast(1.0)
            EngineSet.camera?.setProjection(45.0, a, 0.5, 2000.0, Camera.Fov.VERTICAL)
          } catch(_: Throwable) {}
          try {
            vw = width.coerceAtLeast(1)
            vh = height.coerceAtLeast(1)
          } catch(_: Throwable) {}
          try { manip?.setViewport(width.coerceAtLeast(1), height.coerceAtLeast(1)) } catch(_: Throwable) {}
        }
      }
      fase = "uihelper+attach"
      uh.attachTo(sv)
      boundView = sv
      try {
        if (fw > 1 && fh > 1) {
          vw = fw
          vh = fh
        } else {
          var dw = 1080
          try { dw = ctx.resources.displayMetrics.widthPixels } catch(_: Throwable) {}
          var dh = 2200
          try { dh = ctx.resources.displayMetrics.heightPixels } catch(_: Throwable) {}
          vw = sv.width
          vh = sv.height
          try { if (vw <= 1) vw = if (dw > 1) dw else 1080 } catch(_: Throwable) {}
          try { if (vh <= 1) vh = if (dh > 1) dh else 2200 } catch(_: Throwable) {}
        }
      } catch(_: Throwable) {}
      drawLogged = false
      drawLogT = 0L
      lastFrameT = 0L
      try { lastFrameMs = 0L } catch(_: Throwable) {}
      lastTapT = 0L
      try {
        val ax0 = AgentLoop.px - 128.0
        val az0 = -(AgentLoop.py - 128.0)
        val ay0 = AgentLoop.pz
        val g0 = groundAtWx(ax0, az0)
        val ayS = if (g0.isFinite()) Math.max(ay0, g0 + 2.0) else Math.max(ay0, 24.0)
        try { AgentLoop.pz = ayS } catch(_: Throwable) {}
        safeOrbit(ax0, ayS, az0, 0.0, 4.0, 9.0)
        try { camSpawnFixed = false; camClampLogged = false; camClamp2Logged = false; camClampT = 0L } catch(_: Throwable) {}
      } catch(_: Throwable) {}
      try { onStats?.invoke(PrimDecoder.r3selftest()) } catch(_: Throwable) {}
      try { onStats?.invoke("RENDER-SLOTS n=256") } catch(_: Throwable) {}
      try {
        sv.setOnTouchListener { _, ev ->
          try {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
              val nowT = System.currentTimeMillis()
              val ddx = ev.x - lastTapX
              val ddy = ev.y - lastTapY
              if (nowT - lastTapT < 350L && ddx * ddx + ddy * ddy < 3600f) recenter()
              lastTapT = nowT
              lastTapX = ev.x
              lastTapY = ev.y
            }
          } catch(e: Throwable) { camErr = "punto=touch-tap exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") }
          try {
            touchCount++
            lastTouchT = System.currentTimeMillis()
            try { touchEyeX = camX } catch(_: Throwable) {}
            try { touchEyeY = camY } catch(_: Throwable) {}
            try { touchEyeZ = camZ } catch(_: Throwable) {}
            if (gestures == null && manip != null && boundView != null) {
              try {
                gestures = GestureDetector(boundView!!, manip!!)
                try { onStats?.invoke("CAM-REPAIR gestures") } catch(_: Throwable) {}
              } catch(e: Throwable) { camErr = "punto=touch-repair exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") }
            } else if (manip == null) {
              try {
                val ax = AgentLoop.px - 128.0
                val az = -(AgentLoop.py - 128.0)
                val ay = AgentLoop.pz
                safeOrbit(ax, ay, az, 0.0, 4.0, 9.0)
                try { onStats?.invoke("CAM-REBUILD manip") } catch(_: Throwable) {}
              } catch(e: Throwable) { camErr = "punto=touch-rebuild exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") }
            }
            gestures?.onTouchEvent(ev)
            if (gestures == null) camErr = "punto=touch-gestures-null touch=" + touchCount
          } catch(e: Throwable) { camErr = "punto=touch exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") }
          true
        }
      } catch(e: Throwable) { camErr = "punto=touch-listener exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") }
      fase = "choreographer"
      stopped = false
      frames = 0
      running = true
      statT0 = System.currentTimeMillis()
      try { lastFase = "ok" } catch(_: Throwable) {}
      try { startOk = true } catch(_: Throwable) {}
      choreographer.postFrameCallback(frameCb)
      return true
    } catch(e: Throwable) {
      try { lastFase = fase } catch(_: Throwable) {}
      try { initError = "fase=" + fase + " exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") } catch(_: Throwable) {}
      try { stop() } catch(_: Throwable) {}
      return false
    }
  }
  // M1: malla suave como GLB generado (POSITION+NORMAL como box.glb: ruta loader 100% probada).
  // Sin TANGENTS/UV inventados: el ubershader del box solo conoce NORMAL (box.glb: attrs NORMAL,POSITION).
  // 4 primitivas por banda (arena/verde/marron/gris) con baseColor embebido. 1 entidad, sin escalones.
  private fun buildTerraMesh(eng: Engine): Boolean {
    val N = 128
    if (TerrainMesh.patchesGot() < 8) return false
    val sc = EngineSet.scene ?: return false
    val H = FloatArray(N * N)
    for (jy in 0 until N) for (ix in 0 until N) {
      var h = Float.NaN
      try { h = TerrainMesh.heightAt(ix * 2, jy * 2) } catch (_: Throwable) {}
      H[jy * N + ix] = h
    }
    var s = 0.0
    var n = 0
    for (v in H) if (v.isFinite()) {
      s += v
      n++
    }
    if (n == 0) return false
    val mean = (s / n).toFloat()
    for (pass in 0 until 6) {
      var ch = 0
      for (jy in 0 until N) for (ix in 0 until N) {
        val k = jy * N + ix
        if (!H[k].isFinite()) {
          var av = 0f
          var m = 0
          if (ix > 0 && H[k - 1].isFinite()) {
            av += H[k - 1]
            m++
          }
          if (ix < N - 1 && H[k + 1].isFinite()) {
            av += H[k + 1]
            m++
          }
          if (jy > 0 && H[k - N].isFinite()) {
            av += H[k - N]
            m++
          }
          if (jy < N - 1 && H[k + N].isFinite()) {
            av += H[k + N]
            m++
          }
          if (m > 0) {
            H[k] = av / m
            ch++
          }
        }
      }
      if (ch == 0) break
    }
    for (k in H.indices) {
      var h = H[k]
      if (!h.isFinite()) h = mean
      H[k] = h.coerceIn(-200f, 2000f)
    }
    var hMin = Float.MAX_VALUE
    var hMax = -Float.MAX_VALUE
    for (v in H) {
      if (v < hMin) hMin = v
      if (v > hMax) hMax = v
    }
    fun hv(ix: Int, jy: Int): Float {
      return H[(jy.coerceIn(0, N - 1)) * N + (ix.coerceIn(0, N - 1))]
    }
    val Nrm = FloatArray(N * N * 3)
    for (jy in 0 until N) for (ix in 0 until N) {
      val ex = (hv(ix + 1, jy) - hv(ix - 1, jy)) / 4f
      val ez = (hv(ix, jy - 1) - hv(ix, jy + 1)) / 4f
      var nx = -ex
      var ny = 1f
      var nz = -ez
      val il = 1f / Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
      Nrm[(jy * N + ix) * 3] = nx * il
      Nrm[(jy * N + ix) * 3 + 1] = ny * il
      Nrm[(jy * N + ix) * 3 + 2] = nz * il
    }
    val bands = ArrayList<ArrayList<Int>>()
    bands.add(ArrayList<Int>())
    bands.add(ArrayList<Int>())
    bands.add(ArrayList<Int>())
    bands.add(ArrayList<Int>())
    fun bandOf(h: Float): Int {
      if (h < 20f) return 0
      if (h < 26f) return 1
      if (h < 40f) return 2
      return 3
    }
    for (jy in 0 until N - 1) for (ix in 0 until N - 1) {
      val a = jy * N + ix
      val bb2 = a + 1
      val c = a + N
      val d = c + 1
      val m1 = (H[a] + H[bb2] + H[d]) / 3f
      val m2 = (H[a] + H[d] + H[c]) / 3f
      val l1 = bands[bandOf(m1)]
      l1.add(a)
      l1.add(bb2)
      l1.add(d)
      val l2 = bands[bandOf(m2)]
      l2.add(a)
      l2.add(d)
      l2.add(c)
    }
    val glb = terraGlb(H, Nrm, N, bands)
    if (glb.isEmpty()) return false
    val asset = EngineSet.loader?.createAsset(ByteBuffer.wrap(glb)) ?: return false
    try { EngineSet.resLoader?.loadResources(asset) } catch (_: Throwable) {}
    sc.addEntities(asset.entities)
    try {
      val old = EngineSet.terraAsset
      if (old != null) {
        try { sc.removeEntities(old.entities) } catch (_: Throwable) {}
        try { EngineSet.loader?.destroyAsset(old) } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}
    EngineSet.terraAsset = asset
    EngineSet.terraHMin = hMin
    EngineSet.terraHMax = hMax
    return true
  }
  private fun terraGlb(H: FloatArray, Nrm: FloatArray, N: Int, bands: ArrayList<ArrayList<Int>>): ByteArray {
    val vcount = N * N
    val cols = floatArrayOf(0.76f, 0.7f, 0.5f, 0.25f, 0.5f, 0.28f, 0.45f, 0.38f, 0.25f, 0.7f, 0.7f, 0.72f)
    val order = ArrayList<Int>()
    for (bi in 0 until 4) if (bands[bi].size >= 3) order.add(bi)
    if (order.isEmpty()) return ByteArray(0)
    for (bi in order) {
      val l = bands[bi]
      if (l.size % 2 == 1) {
        val last = l[l.size - 1]
        l.add(last)
        l.add(last)
        l.add(last)
      }
    }
    val posLen = vcount * 12
    val nrmLen = vcount * 12
    var idxLen = 0
    for (bi in order) idxLen += bands[bi].size * 2
    val bin = ByteBuffer.allocate(posLen + nrmLen + idxLen).order(ByteOrder.LITTLE_ENDIAN)
    var pminx = Float.MAX_VALUE
    var pminy = Float.MAX_VALUE
    var pminz = Float.MAX_VALUE
    var pmaxx = -Float.MAX_VALUE
    var pmaxy = -Float.MAX_VALUE
    var pmaxz = -Float.MAX_VALUE
    for (jy in 0 until N) for (ix in 0 until N) {
      val x = ix * 2f - 128f
      val y = H[jy * N + ix]
      val z = -(jy * 2f - 128f)
      bin.putFloat(x)
      bin.putFloat(y)
      bin.putFloat(z)
      if (x < pminx) pminx = x
      if (y < pminy) pminy = y
      if (z < pminz) pminz = z
      if (x > pmaxx) pmaxx = x
      if (y > pmaxy) pmaxy = y
      if (z > pmaxz) pmaxz = z
    }
    for (f in Nrm) bin.putFloat(f)
    val idxOff = HashMap<Int, Int>()
    val idxCount = HashMap<Int, Int>()
    var ioff = posLen + nrmLen
    for (bi in order) {
      idxOff[bi] = ioff
      val l = bands[bi]
      for (v in l) bin.putShort(v.toShort())
      idxCount[bi] = l.size
      ioff += l.size * 2
    }
    val js = StringBuilder()
    js.append("{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}],")
    js.append("\"meshes\":[{\"primitives\":[")
    var pi = 0
    for (bi in order) {
      if (pi > 0) js.append(",")
      js.append("{\"attributes\":{\"POSITION\":0,\"NORMAL\":1},\"indices\":" + (2 + pi) + ",\"material\":" + pi + "}")
      pi++
    }
    js.append("]}],\"materials\":[")
    pi = 0
    for (bi in order) {
      if (pi > 0) js.append(",")
      js.append("{\"pbrMetallicRoughness\":{\"baseColorFactor\":[" + cols[bi * 3].toString() + "," + cols[bi * 3 + 1].toString() + "," + cols[bi * 3 + 2].toString() + ",1.0],\"metallicFactor\":0.0},\"name\":\"terra" + bi + "\"}")
      pi++
    }
    js.append("],\"accessors\":[")
    js.append("{\"bufferView\":0,\"componentType\":5126,\"count\":" + vcount + ",\"type\":\"VEC3\",\"min\":[" + pminx.toString() + "," + pminy.toString() + "," + pminz.toString() + "],\"max\":[" + pmaxx.toString() + "," + pmaxy.toString() + "," + pmaxz.toString() + "]},")
    js.append("{\"bufferView\":1,\"componentType\":5126,\"count\":" + vcount + ",\"type\":\"VEC3\"}")
    for (bi in order) {
      js.append(",{\"bufferView\":" + (2 + order.indexOf(bi)) + ",\"componentType\":5123,\"count\":" + idxCount[bi] + ",\"type\":\"SCALAR\"}")
    }
    js.append("],\"bufferViews\":[")
    js.append("{\"buffer\":0,\"byteOffset\":0,\"byteLength\":" + posLen + "},")
    js.append("{\"buffer\":0,\"byteOffset\":" + posLen + ",\"byteLength\":" + nrmLen + "}")
    for (bi in order) {
      js.append(",{\"buffer\":0,\"byteOffset\":" + idxOff[bi] + ",\"byteLength\":" + (idxCount[bi]!! * 2) + "}")
    }
    val binLen = posLen + nrmLen + idxLen
    js.append("],\"buffers\":[{\"byteLength\":" + binLen + "}]}")
    val jb = js.toString().toByteArray(Charsets.UTF_8)
    val jpad = (4 - jb.size % 4) % 4
    val total = 12 + 8 + jb.size + jpad + 8 + binLen
    val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(0x46546C67)
    out.putInt(2)
    out.putInt(total)
    out.putInt(jb.size + jpad)
    out.putInt(0x4E4F534A)
    out.put(jb)
    for (i in 0 until jpad) out.put(0x20.toByte())
    out.putInt(binLen)
    out.putInt(0x004E4942)
    out.put(bin.array())
    return out.array()
  }
  private val frameCb = object : Choreographer.FrameCallback {
    override fun doFrame(t: Long) {
      if (!running) return
      try { choreographer.postFrameCallback(this) } catch(_: Throwable) {}
      try {
        val eng = EngineSet.engine ?: return
        val gx = AgentLoop.px - 128.0
        val gz = -(AgentLoop.py - 128.0)
        val gy = AgentLoop.pz
        val tm = eng.transformManager
        EngineSet.me?.let { try { tm.setTransform(it.root, matS(gx, gy + 0.9, gz, 1.8f, 1.8f, 1.8f, 0f)) } catch(_: Throwable) {} }
        val objs: List<PrimDecoder.Prim> = try { AgentLoop.objetos } catch(_: Throwable) { emptyList() }
        try {
          val v = PrimDecoder.consumeVec()
          if (v != null) {
            try { onStats?.invoke(v) } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
        val m = objs.size.coerceAtMost(256)
        try {
          while (EngineSet.primSlots.size < m) {
            val na = spawn(0.0, -100.0, 0.0, 0.001)
            if (na == null) break
            tint(na, 0.6f, 0.6f, 0.6f)
            EngineSet.primSlots.add(na)
          }
        } catch(_: Throwable) {}
        for (i in 0 until 256) {
          if (i < m && i < EngineSet.primSlots.size) {
            val o = objs[i]
            val a = EngineSet.primSlots[i]
            if (a != null) {
              val dsx = if (o.sx.isFinite() && o.sx >= 0.5f) o.sx else 0.5f
              val dsy = if (o.sy.isFinite() && o.sy >= 0.5f) o.sy else 0.5f
              val dsz = if (o.sz.isFinite() && o.sz >= 0.5f) o.sz else 0.5f
              try { tm.setTransform(a.root, matS(o.x - 128.0, o.z, -(o.y - 128.0), dsx, dsy, dsz, o.yaw)) } catch(_: Throwable) {}
              if (EngineSet.slotId[i] != o.id || EngineSet.slotTipo[i] != o.tipo || EngineSet.slotMat[i] != o.mat) {
                EngineSet.slotId[i] = o.id
                EngineSet.slotTipo[i] = o.tipo
                EngineSet.slotMat[i] = o.mat
                if (o.tipo == 9) tint(a, 0.95f, 0.55f, 0.15f)
                else if (o.tipo == 47) tint(a, 0.13f, 0.83f, 0.93f)
                else if (o.tipo == -1) tint(a, 0.6f, 0.6f, 0.6f)
                else if (o.mat == 3) tint(a, 0.55f, 0.35f, 0.2f)
                else if (o.mat == 1) tint(a, 0.55f, 0.6f, 0.65f)
                else if (o.mat == 2) tint(a, 0.6f, 0.85f, 0.95f)
                else tint(a, 0.7f, 0.4f, 0.95f)
              }
            }
          } else if (i < EngineSet.primSlots.size) {
            val a = EngineSet.primSlots[i]
            if (a != null && EngineSet.slotId[i] != -1L) {
              EngineSet.slotId[i] = -1L
              EngineSet.slotTipo[i] = -999
              try { tm.setTransform(a.root, mat(0.0, -100.0, 0.0, 0.001)) } catch(_: Throwable) {}
            }
          }
        }
        try {
          val tv = TerrainMesh.version
          if (tv != EngineSet.terrVer) {
            EngineSet.terrVer = tv
            val nowB = System.currentTimeMillis()
            if (!EngineSet.meshOk && !EngineSet.boxesFallback) {
              var built = false
              var failed = false
              try { built = buildTerraMesh(eng) } catch (_: Throwable) { failed = true }
              if (built) {
                EngineSet.meshOk = true
                EngineSet.terraBuiltVer = tv
                EngineSet.lastTerraBuild = nowB
                try { onStats?.invoke("TERRA-DRAW malla=si verts=16384 hMin=" + "%.1f".format(EngineSet.terraHMin) + " hMax=" + "%.1f".format(EngineSet.terraHMax) + " patches=" + TerrainMesh.patchesGot() + "/256") } catch (_: Throwable) {}
              } else if (failed) {
                EngineSet.boxesFallback = true
                try { repeat(1024) { EngineSet.terrSlots.add(spawn(0.0, -100.0, 0.0, 0.001)) } } catch (_: Throwable) {}
                try { for (a in EngineSet.terrSlots) tint(a, 0.25f, 0.5f, 0.28f) } catch (_: Throwable) {}
                try { onStats?.invoke("TERRA-DRAW malla=no-fallback-cajas patches=" + TerrainMesh.patchesGot() + "/256") } catch (_: Throwable) {}
              }
            } else if (EngineSet.meshOk && tv != EngineSet.terraBuiltVer && nowB - EngineSet.lastTerraBuild > 3000L) {
              try {
                if (buildTerraMesh(eng)) {
                  EngineSet.terraBuiltVer = tv
                  EngineSet.lastTerraBuild = nowB
                  try { onStats?.invoke("TERRA-DRAW malla=si verts=16384 hMin=" + "%.1f".format(EngineSet.terraHMin) + " hMax=" + "%.1f".format(EngineSet.terraHMax) + " patches=" + TerrainMesh.patchesGot() + "/256") } catch (_: Throwable) {}
                }
              } catch (_: Throwable) {}
            }
          }
          if (EngineSet.boxesFallback) {
            var ti = 0
            var terrN = 0
            var hMin = Float.MAX_VALUE
            var hMax = -Float.MAX_VALUE
            for (cy in 0 until 32) for (cx in 0 until 32) {
              val a = if (ti < EngineSet.terrSlots.size) EngineSet.terrSlots[ti] else null
              if (a != null) {
                var hs = 0.0
                var hn = 0
                for (jy in 0 until 8) for (ix in 0 until 8) {
                  try {
                    val hv = TerrainMesh.heightAt(cx * 8 + ix, cy * 8 + jy)
                    if (hv.isFinite()) { hs += hv; hn++ }
                  } catch (_: Throwable) {}
                }
                if (hn == 0) {
                  try { tm.setTransform(a.root, mat(0.0, -100.0, 0.0, 0.001)) } catch (_: Throwable) {}
                } else {
                  val h = hs / hn
                  if (h < hMin) hMin = h.toFloat()
                  if (h > hMax) hMax = h.toFloat()
                  val wx = cx * 8 + 4 - 128.0
                  val wz = -((cy * 8 + 4) - 128.0)
                  try { tm.setTransform(a.root, matS(wx, h - 4.0, wz, 8f, 8f, 8f, 0f)) } catch (_: Throwable) {}
                  terrN++
                  if (h < 20.0) tint(a, 0.76f, 0.7f, 0.5f)
                  else if (h < 26.0) tint(a, 0.25f, 0.5f, 0.28f)
                  else if (h < 40.0) tint(a, 0.45f, 0.38f, 0.25f)
                  else tint(a, 0.7f, 0.7f, 0.72f)
                }
              }
              ti++
            }
            try { onStats?.invoke("TERRA-DRAW cols=" + terrN + " malla=no-fallback-cajas patches=" + TerrainMesh.patchesGot() + "/256 hMin=" + "%.1f".format(if (hMin <= hMax) hMin else Float.NaN) + " hMax=" + "%.1f".format(if (hMin <= hMax) hMax else Float.NaN) + " s3=" + "%.1f,%.1f,%.1f".format(TerrainMesh.heightAt(32, 32), TerrainMesh.heightAt(128, 128), TerrainMesh.heightAt(200, 100))) } catch (_: Throwable) {}
          }
        } catch (e: Throwable) { camErr = "punto=terra-sync exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") }
        try {
          if (TerrainMesh.nPatches == 0L && m > 0) {
            var s = 0.0
            for (o in objs) s += o.z
            val mh = (s / m).coerceIn(15.0, 40.0)
            if (Math.abs(mh - EngineSet.groundY) > 1.0) {
              EngineSet.groundY = mh
              try { EngineSet.groundReal = mh } catch(_: Throwable) {}
              try {
                EngineSet.ground?.let { eng.transformManager.setTransform(it.root, floatArrayOf(256f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 256f, 0f, 0f, mh.toFloat(), 0f, 1f)) }
              } catch (_: Throwable) {}
              val nowP = System.currentTimeMillis()
              if (nowP - EngineSet.terraPhLogged > 60000L) {
                EngineSet.terraPhLogged = nowP
                try { onStats?.invoke("TERRA-PLACEHOLDER sueloY=" + "%.1f".format(mh) + " (sin LayerData aun)") } catch (_: Throwable) {}
              }
            }
          } else if (TerrainMesh.nPatches > 0L && !EngineSet.groundSunk) {
            EngineSet.groundSunk = true
            EngineSet.groundY = -100.0
            try {
              EngineSet.ground?.let { eng.transformManager.setTransform(it.root, floatArrayOf(256f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 256f, 0f, 0f, -100f, 0f, 1f)) }
            } catch (_: Throwable) {}
            try { onStats?.invoke("TERRA-SUELO plano-oculto (relieve H visible)") } catch (_: Throwable) {}
          }
        } catch (e: Throwable) { camErr = "punto=terra-placeholder exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") }
        if (m > 0) {
          val nowD = System.currentTimeMillis()
          if (!drawLogged || nowD - drawLogT > 60000L) {
            drawLogged = true
            drawLogT = nowD
            for (k in 0 until m.coerceAtMost(3)) {
              val o2 = objs[k]
              try { onStats?.invoke("PRIMS-DRAW id=" + o2.id + " xyz=" + "%.1f,%.1f,%.1f".format(o2.x - 128.0, o2.z, -(o2.y - 128.0)) + " s=" + "%.2f,%.2f,%.2f".format(o2.sx, o2.sy, o2.sz) + " sueloY=" + "%.1f".format(EngineSet.groundReal) + " waterY=20 avatarY=" + "%.1f".format(gy)) } catch(_: Throwable) {}
            }
          }
        }
        try {
          val dx = gx - tgtX
          val dy = gy - tgtY
          val dz = gz - tgtZ
          val nowR = System.currentTimeMillis()
          val folDx = if (lastFolAx.isFinite()) gx - lastFolAx else Double.NaN
          val folDy = if (lastFolAy.isFinite()) gy - lastFolAy else Double.NaN
          val folDz = if (lastFolAz.isFinite()) gz - lastFolAz else Double.NaN
          val folMoved = !folDx.isFinite() || folDx * folDx + folDy * folDy + folDz * folDz > 25.0
          if (dx * dx + dy * dy + dz * dz > 3600.0 && folMoved && manip != null && nowR - lastRebuildT > 2000L && nowR - lastTouchT > 1500L) {
            lastRebuildT = nowR
            lastFolAx = gx; lastFolAy = gy; lastFolAz = gz
            val off = keepOffset()
            safeOrbit(gx, gy, gz, off.first, off.second, off.third)
            try { onStats?.invoke("CAM-REBUILD dx=" + "%.1f,%.1f,%.1f".format(dx, dy, dz) + " off=" + "%.1f,%.1f,%.1f".format(off.first, off.second, off.third)) } catch(_: Throwable) {}
          }
        } catch(e: Throwable) { camErr = "punto=frame-rebuild exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") }
        try {
          val m0 = manip
          if (m0 != null) {
            val dt = if (lastFrameT == 0L) 1f / 60f else ((t - lastFrameT).coerceIn(1L, 100000000L).toDouble() / 1000000000.0).toFloat()
            lastFrameT = t
            try { lastFrameMs = System.currentTimeMillis() } catch(_: Throwable) {}
            try { m0.update(dt) } catch(e: Throwable) { camErr = "punto=manip-update exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") }
            try { clampCam() } catch(_: Throwable) {}
            val e = FloatArray(3)
            val g = FloatArray(3)
            val u = FloatArray(3)
            try { m0.getLookAt(e, g, u) } catch(e: Throwable) { camErr = "punto=manip-getLookAt exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") }
            try { EngineSet.camera?.lookAt(e[0].toDouble(), e[1].toDouble(), e[2].toDouble(), g[0].toDouble(), g[1].toDouble(), g[2].toDouble(), u[0].toDouble(), u[1].toDouble(), u[2].toDouble()) } catch(e: Throwable) { camErr = "punto=camera-lookAt exc=" + e::class.java.simpleName + " msg=" + (e.message ?: "sin-mensaje") }
            camX = e[0].toDouble()
            camY = e[1].toDouble()
            camZ = e[2].toDouble()
            tgtX = g[0].toDouble()
            tgtY = g[1].toDouble()
            tgtZ = g[2].toDouble()
          } else {
            camX = gx
            camY = gy + 5.0
            camZ = gz + 9.0
            try { EngineSet.camera?.lookAt(camX, camY, camZ, gx, gy + 1.0, gz, 0.0, 1.0, 0.0) } catch(_: Throwable) {}
          }
        } catch(_: Throwable) {}
        val sw = swapChain
        if (sw != null) {
          try {
            if (EngineSet.filt?.beginFrame(sw, t) == true) {
              try { EngineSet.fview?.let { EngineSet.filt?.render(it) } } catch(_: Throwable) {}
              try { EngineSet.filt?.endFrame() } catch(_: Throwable) {}
            }
          } catch(_: Throwable) {}
        }
        frames++
        framesTotal++
        val now = System.currentTimeMillis()
        if (now - statT0 >= 10000L) {
          val el = (now - statT0).coerceAtLeast(1L)
          lastFps = (frames * 1000L / el).toInt()
          frames = 0
          statT0 = now
          val n = 3 + m
          lastObjM = m
          lastEscenaN = n
          try {
            val pd = "PD-STATE escena=" + n + " obj=" + m + " fps=" + lastFps + " manip=" + (if (manip != null) "si" else "no") + " gestures=" + (if (gestures != null) "si" else "no") + " touch=" + touchCount + " eye=" + "%.1f,%.1f,%.1f".format(camX, camY, camZ) + " tgt=" + "%.1f,%.1f,%.1f".format(tgtX, tgtY, tgtZ) + " R3-LAST " + PrimDecoder.r3latch + " VEC-LAST " + PrimDecoder.vecLatch
            onStats?.invoke(pd + (if (camErr != null) " camErr=" + camErr else ""))
            try { onStats?.invoke(gfxLine()) } catch(_: Throwable) {}
          } catch(_: Throwable) {}
        }
      } catch(_: Throwable) {}
    }
  }
  fun projectLabel(fx: Double, fy: Double, fz: Double): Pair<Float,Float>? {
    return try {
      var fxv = tgtX - camX
      var fyv = tgtY - camY
      var fzv = tgtZ - camZ
      val fl = Math.sqrt(fxv * fxv + fyv * fyv + fzv * fzv)
      if (fl < 1e-6) return null
      fxv /= fl; fyv /= fl; fzv /= fl
      var sx = -fzv
      var sz = fxv
      var sl = Math.sqrt(sx * sx + sz * sz)
      if (sl < 1e-6) return null
      sx /= sl; sz /= sl
      val ux = 0.0 * fzv - sz * fyv
      val uy = sz * fxv - sx * fzv
      val uz = sx * fyv - 0.0 * fxv
      val px = fx - camX
      val py = fy - camY
      val pz = fz - camZ
      val zc = px * fxv + py * fyv + pz * fzv
      if (zc < 0.5) return null
      val xc = px * sx + pz * sz
      val yc = px * ux + py * uy + pz * uz
      val tanV = Math.tan(Math.toRadians(22.5))
      val a = vw.toDouble() / vh.toDouble().coerceAtLeast(1.0)
      val xN = xc / (zc * tanV * a)
      val yN = yc / (zc * tanV)
      if (xN < -1.1 || xN > 1.1 || yN < -1.1 || yN > 1.1) return null
      Pair((((xN * 0.5 + 0.5) * vw).toFloat()), (((0.5 - yN * 0.5) * vh).toFloat()))
    } catch(_: Throwable) { null }
  }
  fun sunState(): String {
    return try {
      val sc = EngineSet.scene
      val sol = if (EngineSet.sunEnt != 0 && sc != null) "si" else "no"
      val sky = try { if (sc?.skybox != null) "si" else "no" } catch(_: Throwable) { "?" }
      val ent = try {
        var n = -1
        try {
          val m = sc?.javaClass?.getMethod("getEntityCount")
          val v = try { m?.invoke(sc) } catch(_: Throwable) { null }
          if (v is Int) n = v
        } catch(_: Throwable) {}
        n
      } catch(_: Throwable) { -1 }
      "SUN-STATE sol=" + sol + " sky=" + sky + " ent=" + ent + " primerInit=" + (if (lastReattach) "no" else "si")
    } catch(_: Throwable) { "SUN-STATE sol=? sky=? ent=-1" }
  }
  fun gfxLine(): String {
    try {
      val ax = AgentLoop.px - 128.0
      val az = -(AgentLoop.py - 128.0)
      val ay = AgentLoop.pz
      val nowG = System.currentTimeMillis()
      var edadMs = -1L
      try { if (lastFrameMs > 0L) edadMs = (nowG - lastFrameMs).coerceAtLeast(0L) } catch(_: Throwable) {}
      var dOjo = "sin-touch"
      try {
        if (touchEyeX.isFinite() && touchEyeY.isFinite() && touchEyeZ.isFinite()) {
          val dx = camX - touchEyeX
          val dy = camY - touchEyeY
          val dz = camZ - touchEyeZ
          dOjo = "%.1f".format(Math.sqrt(dx * dx + dy * dy + dz * dz))
        }
      } catch(_: Throwable) {}
      return "GFX-DIAG fps=" + lastFps + " escena=" + lastEscenaN + " obj=" + lastObjM + " touch=" + touchCount + " manip=" + (if (manip != null) "si" else "no") + " gestures=" + (if (gestures != null) "si" else "no") + " eye=" + "%.1f,%.1f,%.1f".format(camX, camY, camZ) + " tgt=" + "%.1f,%.1f,%.1f".format(tgtX, tgtY, tgtZ) + " touchOjo=" + dOjo + " frameEdadMs=" + edadMs + " startOk=" + (if (startOk) "si" else "no") + " fase=" + lastFase + " initErr=" + (initError ?: "-") + " sueloY=" + "%.1f".format(EngineSet.groundReal) + " avatar=" + "%.1f,%.1f,%.1f".format(ax, ay, az) + " R3-LAST " + PrimDecoder.r3latch + (if (camErr != null) " camErr=" + camErr else "")
    } catch (e: Throwable) { return "GFX-DIAG error=" + e::class.java.simpleName }
  }
  fun frameAgeMs(): Long {
    try {
      if (lastFrameMs <= 0L) return -1L
      return (System.currentTimeMillis() - lastFrameMs).coerceAtLeast(0L)
    } catch(_: Throwable) { return -1L }
  }
  fun touchAgeMs(): Long {
    try {
      if (lastTouchT <= 0L) return -1L
      return (System.currentTimeMillis() - lastTouchT).coerceAtLeast(0L)
    } catch(_: Throwable) { return -1L }
  }
  fun isAlive(): Boolean {
    try { return running } catch(_: Throwable) { return false }
  }
  fun stop() {
    if (stopped) return
    stopped = true
    val alive = running || framesTotal > 0
    try { running = false } catch(_: Throwable) {}
    try { choreographer.removeFrameCallback(frameCb) } catch(_: Throwable) {}
    try { uiHelper?.detach() } catch(_: Throwable) {}
    uiHelper = null
    try { boundView?.setOnTouchListener(null) } catch(_: Throwable) {}
    boundView = null
    gestures = null
    manip = null
    if (alive) {
      try {
        val pd = "PD-STATE escena=? obj=" + try { AgentLoop.objetos.size } catch(_: Throwable) { -1 } + " fps=" + lastFps + " manip=no gestures=no touch=" + touchCount + " eye=" + "%.1f,%.1f,%.1f".format(camX, camY, camZ) + " tgt=" + "%.1f,%.1f,%.1f".format(tgtX, tgtY, tgtZ)
        onStats?.invoke("3D-EXIT frames=" + framesTotal + " " + pd + (if (camErr != null) " camErr=" + camErr else ""))
      } catch(_: Throwable) { try { onStats?.invoke("3D-EXIT frames=" + framesTotal) } catch(_: Throwable) {} }
    }
  }
}
