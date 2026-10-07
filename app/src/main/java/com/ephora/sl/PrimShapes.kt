package com.ephora.sl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
// CITA-FUENTES formas reales (Official sources, ver FUENTES-OFICIALES.md formas-7.54):
// - message_template.msg ObjectUpdate High 12: bloque de construcción 23B (ParentID+UpdateFlags+17 params).
// - LibreMetaverse Primitive.cs Type-getter: Line+Circle=Cylinder, Line+Square=Box,
//   Line+Triangulos=Prism, Circle+HalfCircle=Sphere, Circle2+Circle=Sphere,
//   Circle+Circle=Sphere/Torus, Circle+Square=Tube, Circle+EqualTriangle=Ring.
// - OpenSim PrimitiveBaseShape.cs: ProfileShape Circle=0 Square=1 IsoTri=2 EquiTri=3
//   RightTri=4 HalfCircle=5; HollowShape Same=0 Circle=16 Square=32 Triangle=48;
//   Extrusion Straight=16 Curve1=32 Curve2=48 Flexible=128.
// - LSL wiki PRIM_TYPE: que params usa cada tipo (cut/twist/taper/shear/hole_size/...).
// - LibreMetaverse Primitive.cs quanta: CUT/HOLLOW 0.00002, SCALE/SHEAR/TAPER 0.01, REV 0.015.
object PrimShapes {
  data class Params(val path: Int, val prof: Int, val hole: Int, val pathBegin: Float, val pathEnd: Float, val pathScaleX: Float, val pathScaleY: Float, val pathShearX: Float, val pathShearY: Float, val pathTwist: Float, val pathTwistBegin: Float, val pathRadiusOffset: Float, val pathTaperX: Float, val pathTaperY: Float, val pathRevolutions: Float, val pathSkew: Float, val profileBegin: Float, val profileEnd: Float, val profileHollow: Float)
  data class Mesh(val buf: java.nio.FloatBuffer, val count: Int)
  private data class Key(val p: Params)
  private val cache = object : LinkedHashMap<Key, Mesh>(128, 0.75f, true) {
    override fun removeEldestEntry(e: MutableMap.MutableEntry<Key, Mesh>): Boolean {
      return size > 256
    }
  }
  @Volatile var budget = 0
  @Volatile var nBuilt = 0L
  @Volatile var nTry = 0L
  @Volatile var nCacheHit = 0L
  @Volatile var nFailPath = 0L
  @Volatile var nFailRango = 0L
  @Volatile var nFailGeo = 0L
  fun status(): String {
    return "SHAPES built=" + nBuilt + " try=" + nTry + " hits=" + nCacheHit + " cached=" + cache.size + " fPath=" + nFailPath + " fRango=" + nFailRango + " fGeo=" + nFailGeo
  }
  fun quantize(path: Int, profCurve: Int, pb: Float, pe: Float, psx: Float, psy: Float, shx: Float, shy: Float, tw: Float, twb: Float, ro: Float, tpx: Float, tpy: Float, rev: Float, sk: Float, qb: Float, qe: Float, qh: Float): Params {
    val prof = profCurve and 0x0F
    val hole = profCurve and 0xF0
    fun q(v: Float): Float {
      return (v * 100f).toInt() / 100f
    }
    return Params(path and 0xF0, prof, hole, q(pb.coerceIn(0f, 1f)), q(pe.coerceIn(0f, 1f)), q(psx.coerceIn(0f, 2f)), q(psy.coerceIn(0f, 2f)), q(shx), q(shy), q(tw), q(twb), q(ro), q(tpx), q(tpy), q(rev), q(sk), q(qb.coerceIn(0f, 1f)), q(qe.coerceIn(0f, 1f)), q(qh.coerceIn(0f, 1f)))
  }
  fun obtain(k: Params): Mesh? {
    try {
      synchronized(cache) {
        cache[Key(k)]?.let {
          nCacheHit++
          return it
        }
      }
      try { nTry++ } catch(_: Throwable) {}
      if (budget <= 0) return null
      val built = build(k) ?: return null
      val m = toRendererSpace(built)
      budget--
      synchronized(cache) {
        cache[Key(k)] = m
      }
      nBuilt++
      return m
    } catch (_: Throwable) {
      return null
    }
  }
  private data class V2(val x: Float, val y: Float)
  private fun circleLoop(n: Int): MutableList<V2> {
    val out = mutableListOf<V2>()
    var i = 0
    while (i < n) {
      val a = 2f * PI.toFloat() * i / n
      out.add(V2(cos(a) * 0.5f, sin(a) * 0.5f))
      i++
    }
    return out
  }
  private fun squareLoop(): MutableList<V2> {
    return mutableListOf(V2(-0.5f, -0.5f), V2(0.5f, -0.5f), V2(0.5f, 0.5f), V2(-0.5f, 0.5f))
  }
  private fun equiTriLoop(): MutableList<V2> {
    return mutableListOf(V2(0f, 0.5f), V2(-0.433f, -0.25f), V2(0.433f, -0.25f))
  }
  private fun isoTriLoop(): MutableList<V2> {
    return mutableListOf(V2(0f, 0.5f), V2(-0.3f, -0.5f), V2(0.3f, -0.5f))
  }
  private fun rightTriLoop(): MutableList<V2> {
    return mutableListOf(V2(-0.5f, -0.5f), V2(0.5f, -0.5f), V2(-0.5f, 0.5f))
  }
  private fun halfCircleLoop(n: Int): MutableList<V2> {
    val out = mutableListOf<V2>()
    var i = 0
    while (i <= n) {
      val a = -PI.toFloat() / 2f + PI.toFloat() * i / n
      out.add(V2(cos(a) * 0.5f, sin(a) * 0.5f))
      i++
    }
    return out
  }
  private fun baseLoop(prof: Int, hollowKind: Int, n: Int): MutableList<V2> {
    val kind = if (hollowKind != 0) hollowKind else prof
    if (kind == 16 || (hollowKind == 0 && prof == 0)) return circleLoop(n)
    if (kind == 32 || (hollowKind == 0 && prof == 1)) return squareLoop()
    if (kind == 48) return equiTriLoop()
    if (prof == 2) return isoTriLoop()
    if (prof == 3) return equiTriLoop()
    if (prof == 4) return rightTriLoop()
    if (prof == 5) return halfCircleLoop(n)
    return squareLoop()
  }
  private fun trimLoop(loop: MutableList<V2>, closed: Boolean, b: Float, e: Float, n: Int): MutableList<V2> {
    if (b <= 0f && e >= 1f) return loop
    if (e <= b) return mutableListOf()
    if (closed) {
      val total = loop.size
      val f0 = b * total
      val f1 = e * total
      val out = mutableListOf<V2>()
      var k = f0.toInt()
      out.add(lerpLoop(loop, f0))
      k++
      while (k < f1.toInt() + 1 && out.size < n + 2) {
        out.add(loop[k % total])
        k++
      }
      out.add(lerpLoop(loop, f1))
      return out
    }
    val total = (loop.size - 1).coerceAtLeast(1)
    val f0 = b * total
    val f1 = e * total
    val out = mutableListOf<V2>()
    out.add(lerpOpen(loop, f0))
    var k = f0.toInt() + 1
    while (k <= f1.toInt() && out.size < n + 2) {
      out.add(loop[k])
      k++
    }
    out.add(lerpOpen(loop, f1))
    return out
  }
  private fun lerpLoop(loop: MutableList<V2>, f: Float): V2 {
    val n = loop.size
    val i0 = ((f.toInt() % n) + n) % n
    val i1 = (i0 + 1) % n
    val t = f - f.toInt()
    val a = loop[i0]
    val b = loop[i1]
    return V2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
  }
  private fun lerpOpen(loop: MutableList<V2>, f: Float): V2 {
    val n = loop.size
    val c = f.coerceIn(0f, (n - 1).toFloat())
    val i0 = c.toInt().coerceIn(0, n - 1)
    val i1 = (i0 + 1).coerceAtMost(n - 1)
    val t = c - c.toInt()
    val a = loop[i0]
    val b = loop[i1]
    return V2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
  }
  private fun loopNormals(loop: MutableList<V2>, closed: Boolean): MutableList<V2> {
    val out = mutableListOf<V2>()
    val n = loop.size
    var i = 0
    while (i < n) {
      val a = loop[(i - 1 + n) % n]
      val b = loop[(i + 1) % n]
      var dx = b.x - a.x
      var dy = b.y - a.y
      var nx = dy
      var ny = -dx
      val len = sqrt(nx * nx + ny * ny).coerceAtLeast(1e-6f)
      out.add(V2(nx / len, ny / len))
      i++
    }
    return out
  }
  private class Soup {
    val d = mutableListOf<Float>()
    var n = 0
    fun tri(ax: Float, ay: Float, az: Float, anx: Float, any: Float, anz: Float, au: Float, av: Float, bx: Float, by: Float, bz: Float, bnx: Float, bny: Float, bnz: Float, bu: Float, bv: Float, cx: Float, cy: Float, cz: Float, cnx: Float, cny: Float, cnz: Float, cu: Float, cv: Float) {
      d.add(ax)
      d.add(ay)
      d.add(az)
      d.add(anx)
      d.add(any)
      d.add(anz)
      d.add(au)
      d.add(av)
      d.add(bx)
      d.add(by)
      d.add(bz)
      d.add(bnx)
      d.add(bny)
      d.add(bnz)
      d.add(bu)
      d.add(bv)
      d.add(cx)
      d.add(cy)
      d.add(cz)
      d.add(cnx)
      d.add(cny)
      d.add(cnz)
      d.add(cu)
      d.add(cv)
      n++
    }
    fun quad(ax: Float, ay: Float, az: Float, anx: Float, any: Float, anz: Float, au: Float, av: Float, bx: Float, by: Float, bz: Float, bnx: Float, bny: Float, bnz: Float, bu: Float, bv: Float, cx: Float, cy: Float, cz: Float, cnx: Float, cny: Float, cnz: Float, cu: Float, cv: Float, dx: Float, dy: Float, dz: Float, dnx: Float, dny: Float, dnz: Float, du: Float, dv: Float) {
      tri(ax, ay, az, anx, any, anz, au, av, bx, by, bz, bnx, bny, bnz, bu, bv, cx, cy, cz, cnx, cny, cnz, cu, cv)
      tri(ax, ay, az, anx, any, anz, au, av, cx, cy, cz, cnx, cny, cnz, cu, cv, dx, dy, dz, dnx, dny, dnz, du, dv)
    }
  }
  private fun capFan(s: Soup, ring: List<FloatArray>, flip: Boolean, cu: Float, cv: Float) {
    if (ring.size < 3) return
    var cx = 0f
    var cy = 0f
    var cz = 0f
    for (p in ring) {
      cx += p[0]
      cy += p[1]
      cz += p[2]
    }
    cx /= ring.size
    cy /= ring.size
    cz /= ring.size
    var nx = 0f
    var ny = 0f
    var nz = 0f
    val a = ring[0]
    val b = ring[1]
    val c = ring[2]
    val ux = b[0] - a[0]
    val uy = b[1] - a[1]
    val uz = b[2] - a[2]
    val vx = c[0] - a[0]
    val vy = c[1] - a[1]
    val vz = c[2] - a[2]
    nx = uy * vz - uz * vy
    ny = uz * vx - ux * vz
    nz = ux * vy - uy * vx
    val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f)
    nx /= len
    ny /= len
    nz /= len
    if (flip) {
      nx = -nx
      ny = -ny
      nz = -nz
    }
    var i = 0
    while (i < ring.size) {
      val p0 = ring[i]
      val p1 = ring[(i + 1) % ring.size]
      if (!flip) s.tri(cx, cy, cz, nx, ny, nz, cu, cv, p0[0], p0[1], p0[2], nx, ny, nz, p0[3], p0[4], p1[0], p1[1], p1[2], nx, ny, nz, p1[3], p1[4])
      else s.tri(cx, cy, cz, nx, ny, nz, cu, cv, p1[0], p1[1], p1[2], nx, ny, nz, p1[3], p1[4], p0[0], p0[1], p0[2], nx, ny, nz, p0[3], p0[4])
      i++
    }
  }
  fun build(k: Params): Mesh? {
    try {
      if (k.path != 16 && k.path != 32 && k.path != 48 && k.path != 128) { try { nFailPath++ } catch(_: Throwable) {}; return null }
      if (k.pathEnd <= k.pathBegin + 0.001f) { try { nFailRango++ } catch(_: Throwable) {}; return null }
      if (k.profileEnd <= k.profileBegin + 0.001f) { try { nFailRango++ } catch(_: Throwable) {}; return null }
      if (k.path == 16) return buildStraight(k)
      return buildCircular(k)
    } catch (_: Throwable) {
      return null
    }
  }
  private fun buildStraight(k: Params): Mesh? {
    val n = 20
    val profClosed = k.prof != 5
    var outer = baseLoop(k.prof, 0, n)
    outer = trimLoop(outer, profClosed, k.profileBegin, k.profileEnd, n)
    if (outer.size < 3) { try { nFailGeo++ } catch(_: Throwable) {}; return null }
    val hollowOn = k.profileHollow > 0.001f
    var inner = mutableListOf<V2>()
    if (hollowOn) {
      var hole = baseLoop(k.prof, k.hole, n)
      val hs = (1f - k.profileHollow * 0.5f).coerceIn(0.05f, 1f)
      var i = 0
      while (i < hole.size) {
        hole[i] = V2(hole[i].x * hs, hole[i].y * hs)
        i++
      }
      hole = trimLoop(hole, k.hole == 0 && k.prof != 5, k.profileBegin, k.profileEnd, n)
      if (hole.size >= 3) inner = hole
    }
    val outerOpen = k.profileBegin > 0.001f || k.profileEnd < 0.999f
    val steps = if (k.pathTwist != 0f || k.pathTwistBegin != 0f) 12 else 1
    val s = Soup()
    var si = 0
    val rings = mutableListOf<List<FloatArray>>()
    while (si <= steps) {
      val t = si.toFloat() / steps
      val tt = k.pathBegin + (k.pathEnd - k.pathBegin) * t
      val z = -0.5f + tt
      val tapX = (1f - k.pathTaperX * t).coerceAtLeast(0.02f)
      val tapY = (1f - k.pathTaperY * t).coerceAtLeast(0.02f)
      val ang = (k.pathTwistBegin + t * (k.pathTwist - k.pathTwistBegin)) * 2f * PI.toFloat()
      val ca = cos(ang)
      val sa = sin(ang)
      val shx = k.pathShearX * t
      val shy = k.pathShearY * t
      val ring = mutableListOf<FloatArray>()
      val on = loopNormals(outer, !outerOpen)
      var i = 0
      while (i < outer.size) {
        val p = outer[i]
        var lx = p.x * tapX + shx
        var ly = p.y * tapY + shy
        val rx = lx * ca - ly * sa
        val ry = lx * sa + ly * ca
        val nn = on[i]
        var lnx = nn.x * tapX
        var lny = nn.y * tapY
        val rnx = lnx * ca - lny * sa
        val rny = lnx * sa + lny * ca
        val rl = sqrt(rnx * rnx + rny * rny).coerceAtLeast(1e-6f)
        ring.add(floatArrayOf(rx, ry, z, rnx / rl, rny / rl, 0f, i.toFloat() / outer.size, t))
        i++
      }
      rings.add(ring)
      si++
    }
    var i = 0
    while (i < outer.size) {
      val j = (i + 1) % outer.size
      if (outerOpen && j == 0) break
      var si2 = 0
      while (si2 < steps) {
        val r0 = rings[si2]
        val r1 = rings[si2 + 1]
        val a0 = r0[i]
        val a1 = r0[j]
        val b0 = r1[i]
        val b1 = r1[j]
        s.quad(a0[0], a0[1], a0[2], a0[3], a0[4], 0f, a0[6], a0[7], a1[0], a1[1], a1[2], a1[3], a1[4], 0f, a1[6], a1[7], b1[0], b1[1], b1[2], b1[3], b1[4], 0f, b1[6], b1[7], b0[0], b0[1], b0[2], b0[3], b0[4], 0f, b0[6], b0[7])
        si2++
      }
      i++
    }
    if (inner.isNotEmpty()) {
      val irings = mutableListOf<List<FloatArray>>()
      var si3 = 0
      while (si3 <= steps) {
        val t = si3.toFloat() / steps
        val t2 = rings[si3]
        val z = t2[0][2]
        val tapX = (1f - k.pathTaperX * t).coerceAtLeast(0.02f)
        val tapY = (1f - k.pathTaperY * t).coerceAtLeast(0.02f)
        val ang = (k.pathTwistBegin + t * (k.pathTwist - k.pathTwistBegin)) * 2f * PI.toFloat()
        val ca = cos(ang)
        val sa = sin(ang)
        val shx = k.pathShearX * t
        val shy = k.pathShearY * t
        val ring = mutableListOf<FloatArray>()
        val on2 = loopNormals(inner, true)
        var ii = 0
        while (ii < inner.size) {
          val p = inner[ii]
          val lx = p.x * tapX + shx
          val ly = p.y * tapY + shy
          val rx = lx * ca - ly * sa
          val ry = lx * sa + ly * ca
          val nn = on2[ii]
          val rnx = -(nn.x * ca - nn.y * sa)
          val rny = -(nn.x * sa + nn.y * ca)
          ring.add(floatArrayOf(rx, ry, z, rnx, rny, 0f, ii.toFloat() / inner.size, t))
          ii++
        }
        irings.add(ring)
        si3++
      }
      var ii = 0
      while (ii < inner.size) {
        val jj = (ii + 1) % inner.size
        var si4 = 0
        while (si4 < steps) {
          val r0 = irings[si4]
          val r1 = irings[si4 + 1]
          s.quad(r0[ii][0], r0[ii][1], r0[ii][2], r0[ii][3], r0[ii][4], 0f, r0[ii][6], r0[ii][7], r0[jj][0], r0[jj][1], r0[jj][2], r0[jj][3], r0[jj][4], 0f, r0[jj][6], r0[jj][7], r1[jj][0], r1[jj][1], r1[jj][2], r1[jj][3], r1[jj][4], 0f, r1[jj][6], r1[jj][7], r1[ii][0], r1[ii][1], r1[ii][2], r1[ii][3], r1[ii][4], 0f, r1[ii][6], r1[ii][7])
          si4++
        }
        ii++
      }
      capRingWithHole(s, rings[0], irings[0], true)
      capRingWithHole(s, rings[steps], irings[steps], false)
    } else {
      capFan(s, rings[0].map { floatArrayOf(it[0], it[1], it[2], it[6], it[7]) }, true, 0.5f, 0.5f)
      capFan(s, rings[steps].map { floatArrayOf(it[0], it[1], it[2], it[6], it[7]) }, false, 0.5f, 0.5f)
    }
    if (outerOpen) {
      closeProfileCut(s, rings, 0, true)
      closeProfileCut(s, rings, outer.size - 1, false)
    }
    return soupToMesh(s)
  }
  private fun capRingWithHole(s: Soup, outer: List<FloatArray>, inner: List<FloatArray>, flip: Boolean) {
    val nz = if (flip) -1f else 1f
    var i = 0
    while (i < outer.size) {
      val j = (i + 1) % outer.size
      val k = (i * inner.size / outer.size) % inner.size
      val l = ((i + 1) * inner.size / outer.size) % inner.size
      val a = outer[i]
      val b = outer[j]
      val c = inner[l]
      val d = inner[k]
      if (!flip) s.quad(a[0], a[1], a[2], 0f, 0f, nz, a[0] + 0.5f, a[1] + 0.5f, b[0], b[1], b[2], 0f, 0f, nz, b[0] + 0.5f, b[1] + 0.5f, c[0], c[1], c[2], 0f, 0f, nz, c[0] + 0.5f, c[1] + 0.5f, d[0], d[1], d[2], 0f, 0f, nz, d[0] + 0.5f, d[1] + 0.5f)
      else s.quad(a[0], a[1], a[2], 0f, 0f, nz, a[0] + 0.5f, a[1] + 0.5f, d[0], d[1], d[2], 0f, 0f, nz, d[0] + 0.5f, d[1] + 0.5f, c[0], c[1], c[2], 0f, 0f, nz, c[0] + 0.5f, c[1] + 0.5f, b[0], b[1], b[2], 0f, 0f, nz, b[0] + 0.5f, b[1] + 0.5f)
      i++
    }
  }
  private fun closeProfileCut(s: Soup, rings: List<List<FloatArray>>, idx: Int, first: Boolean) {
    var si = 0
    while (si < rings.size - 1) {
      val r0 = rings[si]
      val r1 = rings[si + 1]
      val a = r0[idx]
      val b = r1[idx]
      val anx = if (first) -1f else 1f
      s.quad(a[0], a[1], a[2], anx, 0f, 0f, 0f, a[7], b[0], b[1], b[2], anx, 0f, 0f, 0f, b[7], b[0] + 0.01f, b[1], b[2], anx, 0f, 0f, 1f, b[7], a[0] + 0.01f, a[1], a[2], anx, 0f, 0f, 1f, a[7])
      si++
    }
  }
  private fun buildCircular(k: Params): Mesh? {
    val n = 18
    val isSphere = k.prof == 5
    var outer = baseLoop(k.prof, 0, n)
    val profClosed = k.prof != 5
    outer = trimLoop(outer, profClosed, k.profileBegin, k.profileEnd, n)
    if (outer.size < 2) { try { nFailGeo++ } catch(_: Throwable) {}; return null }
    val hollowOn = k.profileHollow > 0.001f && !isSphere
    var inner = mutableListOf<V2>()
    if (hollowOn) {
      var hole = baseLoop(k.prof, k.hole, n)
      val hs = (1f - k.profileHollow * 0.5f).coerceIn(0.05f, 1f)
      var i = 0
      while (i < hole.size) {
        hole[i] = V2(hole[i].x * hs, hole[i].y * hs)
        i++
      }
      hole = trimLoop(hole, true, k.profileBegin, k.profileEnd, n)
      if (hole.size >= 3) inner = hole
    }
    val steps = 24
    val s = Soup()
    val hx = k.pathScaleX.coerceIn(0f, 1f)
    val hy = k.pathScaleY.coerceIn(0f, 1f)
    val ringR: Float
    val kRad: Float
    val kVert: Float
    if (isSphere) {
      ringR = 0f
      kRad = 1f
      kVert = 1f
    } else {
      kRad = ((1f - hx) / 2f).coerceAtLeast(0.01f)
      ringR = 0.5f - 0.5f * kRad
      kVert = ((1f - hy) / 2f).coerceIn(0.02f, 0.5f)
    }
    val on = loopNormals(outer, profClosed && k.profileBegin <= 0.001f && k.profileEnd >= 0.999f)
    var si = 0
    val rings = mutableListOf<List<FloatArray>>()
    while (si <= steps) {
      val t = si.toFloat() / steps
      val tt = k.pathBegin + (k.pathEnd - k.pathBegin) * t
      val a = tt * 2f * PI.toFloat()
      val ca = cos(a)
      val sa = sin(a)
      val spin = (k.pathTwistBegin + t * (k.pathTwist - k.pathTwistBegin) + k.pathRevolutions * t) * 2f * PI.toFloat()
      val cs = cos(spin)
      val ss = sin(spin)
      val rr = ringR + k.pathRadiusOffset * 0.25f
      val tang = k.pathSkew * 0.5f * (t - 0.5f)
      val ring = mutableListOf<FloatArray>()
      var i = 0
      while (i < outer.size) {
        val p = outer[i]
        val pu = p.x * kRad
        var pv = p.y * kVert
        val su = pu * cs - pv * ss
        val sv = pu * ss + pv * cs
        val rad = rr + su
        val x = rad * ca - tang * sa
        val y = rad * sa + tang * ca
        val z = sv
        val nn = on[i]
        var nu = nn.x * cs - nn.y * ss
        var nv = nn.x * ss + nn.y * cs
        var nx = nu * ca
        var ny = nu * sa
        var nz = nv
        val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        ring.add(floatArrayOf(x, y, z, nx / nl, ny / nl, nz / nl, i.toFloat() / outer.size, t))
        i++
      }
      rings.add(ring)
      si++
    }
    val closed = k.pathBegin <= 0.001f && k.pathEnd >= 0.999f
    val segs = if (closed) steps else steps
    var i = 0
    while (i < outer.size) {
      val j = (i + 1) % outer.size
      if (profClosed && j == 0 && (k.profileBegin > 0.001f || k.profileEnd < 0.999f)) {
        i++
        continue
      }
      if (!profClosed && j == 0) {
        i++
        continue
      }
      var si2 = 0
      while (si2 < steps) {
        val r0 = rings[si2]
        val r1 = rings[(si2 + 1) % rings.size]
        if (!closed && si2 == steps) break
        if (closed && si2 == steps) {
          si2++
          continue
        }
        val a0 = r0[i]
        val a1 = r0[j]
        val b0 = r1[i]
        val b1 = r1[j]
        s.quad(a0[0], a0[1], a0[2], a0[3], a0[4], a0[5], a0[6], a0[7], a1[0], a1[1], a1[2], a1[3], a1[4], a1[5], a1[6], a1[7], b1[0], b1[1], b1[2], b1[3], b1[4], b1[5], b1[6], b1[7], b0[0], b0[1], b0[2], b0[3], b0[4], b0[5], b0[6], b0[7])
        si2++
      }
      i++
    }
    if (!closed) {
      capFan(s, rings[0].map { floatArrayOf(it[0], it[1], it[2], it[6], it[7]) }, true, 0.5f, 0.5f)
      capFan(s, rings[steps].map { floatArrayOf(it[0], it[1], it[2], it[6], it[7]) }, false, 0.5f, 0.5f)
    }
    return soupToMesh(s)
  }
  private fun toRendererSpace(srcMesh: Mesh): Mesh {
    val src = srcMesh.buf.duplicate()
    src.position(0)
    val out = ByteBuffer.allocateDirect(srcMesh.count * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    repeat(srcMesh.count) {
      val x = src.get(); val y = src.get(); val z = src.get()
      val nx = src.get(); val ny = src.get(); val nz = src.get()
      val u = src.get(); val v = src.get()
      // SL coordinates: X/Y horizontal, Z vertical. Renderer world: X/Z horizontal, Y vertical.
      // MeshAssets uses the same basis conversion: (x, y, z, nx, ny, nz) -> (x, z, -y, nx, nz, -ny).
      out.put(x).put(z).put(-y)
      out.put(nx).put(nz).put(-ny)
      out.put(u).put(v)
    }
    out.position(0)
    return Mesh(out, srcMesh.count)
  }
  private fun soupToMesh(s: Soup): Mesh? {
    if (s.n == 0) { try { nFailGeo++ } catch(_: Throwable) {}; return null }
    if (s.n > 20000) { try { nFailGeo++ } catch(_: Throwable) {}; return null }
    val bb = ByteBuffer.allocateDirect(s.d.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    for (v in s.d) bb.put(v)
    bb.position(0)
    return Mesh(bb, s.n * 3)
  }
}
