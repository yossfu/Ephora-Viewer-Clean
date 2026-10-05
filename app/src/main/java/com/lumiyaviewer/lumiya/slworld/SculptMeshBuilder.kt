package com.lumiyaviewer.lumiya.slworld

import com.lumiyaviewer.lumiya.renderer.MeshDesc
import kotlin.math.sqrt

/**
 * Geometría real de prims esculpidos a partir de su sculpt map.
 *
 * Un sculpt map es una imagen donde cada píxel RGB es un vértice XYZ en el
 * espacio del sculpt ([-0.5, 0.5] por canal; la escala del prim lo dimensiona,
 * igual que el viewer funcional). La topología (cómo se cose la rejilla) la da
 * el tipo de sculpt del ExtraParams, según "Sculpted Prims: Technical
 * Explanation" del wiki de Second Life:
 *
 * * esfera: 33 columnas (32 + costura duplicada) x 33 filas (31 de datos + 2
 *   polos; la fila superior/inferior entera es el píxel central de la primera/
 *   última fila de la imagen);
 * * toro: 32x32 con costura en ambos ejes;
 * * plano: 33x33 sin costura;
 * * cilindro: 33 columnas con costura (esquema toro) x 33 filas sin costura
 *   (esquema plano).
 *
 * Los valores se muestrean bilinealmente, así vale cualquier nivel de descarte
 * decodificado, no solo 64x64. Las normales se calculan por diferencias
 * centrales sobre la rejilla. Una sola cara (grupo 0): el tint/textura de la
 * cara 0 del TextureEntry sigue aplicándose vía materiales de doble cara (el
 * byte de tipo puede traer flags de espejo/inversión cuya semántica exacta de
 * bobinado no se reproduce aquí; la doble cara evita que un bobinado invertido
 * vuelva invisible el objeto).
 *
 * El tipo 5 (malla/rigged) NO se construye aquí: necesita el asset .slm vía la
 * capability GetMesh (fase 5 completa), y hasta entonces el objeto conserva su
 * prim básico, sin sustituciones inventadas.
 */
object SculptMeshBuilder {

    const val TYPE_SPHERE = 1
    const val TYPE_TORUS = 2
    const val TYPE_PLANE = 3
    const val TYPE_CYLINDER = 4
    const val TYPE_MESH = 5
    const val TYPE_GLTF = 6

    /** Verdadero cuando este tipo tiene geometría sculpt generable aquí. */
    fun isBuildable(sculptType: Int): Boolean {
        return when (sculptType and TYPE_MASK) {
            TYPE_SPHERE, TYPE_TORUS, TYPE_PLANE, TYPE_CYLINDER -> true
            else -> false
        }
    }

    /** Verdadero cuando el objeto necesita el asset de malla (GetMesh). */
    fun needsMeshAsset(sculptType: Int): Boolean = (sculptType and TYPE_MASK) == TYPE_MESH

    /** Verdadero cuando el objeto es GLTF/PBR (fase posterior, no GetMesh). */
    fun isGltf(sculptType: Int): Boolean = (sculptType and TYPE_MASK) == TYPE_GLTF

    /**
     * Construye el MeshDesc desde los píxeles RGBA (primera fila primero, como
     * los entrega el pipeline), o null si el tipo no es generable o la imagen
     * no sirve. Nunca lanza: un mapa inútil devuelve null y el objeto conserva
     * su prim básico.
     */
    fun build(pixels: ByteArray, width: Int, height: Int, sculptType: Int): MeshDesc? {
        if (width <= 0 || height <= 0 || pixels.size != width * height * 4) {
            return null
        }
        return try {
            when (sculptType and TYPE_MASK) {
                TYPE_SPHERE -> buildSphere(pixels, width, height)
                TYPE_TORUS -> buildWrapped(pixels, width, height, 32, 32, false)
                TYPE_PLANE -> buildWrapped(pixels, width, height, 33, 33, true)
                TYPE_CYLINDER -> buildCylinder(pixels, width, height)
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun buildSphere(pixels: ByteArray, width: Int, height: Int): MeshDesc {
        val cols = 33
        val rows = 33
        val pos = FloatArray(cols * rows * 3)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val o = (row * cols + col) * 3
                if (row == 0) {
                    sampleInto(pixels, width, height, 0.5f, 0f, pos, o)
                } else if (row == rows - 1) {
                    sampleInto(pixels, width, height, 0.5f, 1f, pos, o)
                } else {
                    val u = (col % 32) / 32f
                    val v = row / 32f
                    sampleInto(pixels, width, height, u, v, pos, o)
                }
            }
        }
        val indices = gridIndices(cols, rows, wrapU = true, wrapV = false)
        return finish(pos, cols, rows, true, indices)
    }

    private fun buildCylinder(pixels: ByteArray, width: Int, height: Int): MeshDesc {
        val cols = 33
        val rows = 33
        val pos = FloatArray(cols * rows * 3)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val u = (col % 32) / 32f
                val v = row / 32f
                sampleInto(pixels, width, height, u, v, pos, (row * cols + col) * 3)
            }
        }
        val indices = gridIndices(cols, rows, wrapU = true, wrapV = false)
        return finish(pos, cols, rows, true, indices)
    }

    private fun buildWrapped(
        pixels: ByteArray,
        width: Int,
        height: Int,
        cols: Int,
        rows: Int,
        open: Boolean
    ): MeshDesc {
        val pos = FloatArray(cols * rows * 3)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val u: Float
                val v: Float
                if (open) {
                    u = col / (cols - 1f)
                    v = row / (rows - 1f)
                } else {
                    u = col / cols.toFloat()
                    v = row / rows.toFloat()
                }
                sampleInto(pixels, width, height, u, v, pos, (row * cols + col) * 3)
            }
        }
        val indices = gridIndices(cols, rows, wrapU = !open, wrapV = !open)
        return finish(pos, cols, rows, !open, indices)
    }

    private fun gridIndices(cols: Int, rows: Int, wrapU: Boolean, wrapV: Boolean): IntArray {
        val quadsU = if (wrapU) cols else cols - 1
        val quadsV = if (wrapV) rows else rows - 1
        val out = IntArray(quadsU * quadsV * 6)
        var k = 0
        for (row in 0 until quadsV) {
            val row2 = (row + 1) % rows
            for (col in 0 until quadsU) {
                val col2 = (col + 1) % cols
                val a = row * cols + col
                val b = row * cols + col2
                val c = row2 * cols + col
                val d = row2 * cols + col2
                out[k++] = a
                out[k++] = c
                out[k++] = b
                out[k++] = b
                out[k++] = c
                out[k++] = d
            }
        }
        return out
    }

    private fun finish(
        pos: FloatArray,
        cols: Int,
        rows: Int,
        wrapU: Boolean,
        indices: IntArray
    ): MeshDesc {
        val count = cols * rows
        val normals = FloatArray(count * 3)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val i = row * cols + col
                val iL = row * cols + colLeft(col, cols, wrapU)
                val iR = row * cols + colRight(col, cols, wrapU)
                val iU = ((row - 1 + rows) % rows) * cols + col
                val iD = ((row + 1) % rows) * cols + col
                val tx = pos[iR * 3] - pos[iL * 3]
                val ty = pos[iR * 3 + 1] - pos[iL * 3 + 1]
                val tz = pos[iR * 3 + 2] - pos[iL * 3 + 2]
                val bx = pos[iD * 3] - pos[iU * 3]
                val by = pos[iD * 3 + 1] - pos[iU * 3 + 1]
                val bz = pos[iD * 3 + 2] - pos[iU * 3 + 2]
                var nx = ty * bz - tz * by
                var ny = tz * bx - tx * bz
                var nz = tx * by - ty * bx
                var len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len < 1e-9f) {
                    nx = 0f
                    ny = 0f
                    nz = 1f
                    len = 1f
                }
                normals[i * 3] = nx / len
                normals[i * 3 + 1] = ny / len
                normals[i * 3 + 2] = nz / len
            }
        }
        val vertices = FloatArray(count * MeshDesc.VERTEX_FLOATS)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (i in 0 until count) {
            val x = pos[i * 3]
            val y = pos[i * 3 + 1]
            val z = pos[i * 3 + 2]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (z < minZ) minZ = z
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            if (z > maxZ) maxZ = z
            val o = i * MeshDesc.VERTEX_FLOATS
            vertices[o] = x
            vertices[o + 1] = y
            vertices[o + 2] = z
            vertices[o + 3] = normals[i * 3]
            vertices[o + 4] = normals[i * 3 + 1]
            vertices[o + 5] = normals[i * 3 + 2]
            vertices[o + 6] = (i % cols) / (cols - 1f)
            vertices[o + 7] = (i / cols) / (rows - 1f)
        }
        if (maxX - minX < 0.001f) {
            maxX = minX + 0.001f
        }
        if (maxY - minY < 0.001f) {
            maxY = minY + 0.001f
        }
        if (maxZ - minZ < 0.001f) {
            maxZ = minZ + 0.001f
        }
        return MeshDesc(
            vertices,
            indices,
            intArrayOf(0, 0, indices.size),
            floatArrayOf(minX, minY, minZ),
            floatArrayOf(maxX, maxY, maxZ)
        )
    }

    private fun colLeft(col: Int, cols: Int, wrapU: Boolean): Int {
        return if (col > 0) col - 1 else if (wrapU) cols - 1 else 0
    }

    private fun colRight(col: Int, cols: Int, wrapU: Boolean): Int {
        return if (col < cols - 1) col + 1 else if (wrapU) 0 else cols - 1
    }

    private fun sampleInto(
        pixels: ByteArray,
        width: Int,
        height: Int,
        u: Float,
        v: Float,
        out: FloatArray,
        offset: Int
    ) {
        val x = (u * (width - 1)).coerceIn(0f, (width - 1).toFloat())
        val y = (v * (height - 1)).coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt().coerceIn(0, width - 1)
        val y0 = y.toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceIn(0, width - 1)
        val y1 = (y0 + 1).coerceIn(0, height - 1)
        val fx = x - x0
        val fy = y - y0
        var r = 0f
        var g = 0f
        var b = 0f
        var w = 0f
        val xs = intArrayOf(x0, x1, x0, x1)
        val ys = intArrayOf(y0, y0, y1, y1)
        val ws = floatArrayOf((1f - fx) * (1f - fy), fx * (1f - fy), (1f - fx) * fy, fx * fy)
        for (k in 0 until 4) {
            val p = (ys[k] * width + xs[k]) * 4
            r += (pixels[p].toInt() and 0xFF) * ws[k]
            g += (pixels[p + 1].toInt() and 0xFF) * ws[k]
            b += (pixels[p + 2].toInt() and 0xFF) * ws[k]
            w += ws[k]
        }
        if (w > 0f) {
            r /= w
            g /= w
            b /= w
        }
        out[offset] = r / 255f - 0.5f
        out[offset + 1] = g / 255f - 0.5f
        out[offset + 2] = b / 255f - 0.5f
    }

    /** Solo el nibble bajo es el tipo; los bits altos son flags (espejo/inversión). */
    private const val TYPE_MASK = 0x0F
}
