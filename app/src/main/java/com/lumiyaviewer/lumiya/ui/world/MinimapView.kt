package com.lumiyaviewer.lumiya.ui.world

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.lumiyaviewer.lumiya.slproto.base.Vector3
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Mini-map of the current region. Draws the real coarse positions broadcast by
 * the simulator (`CoarseLocationUpdate`) — the same data the official viewer
 * uses for its minimap — plus a heading arrow for the local avatar.
 */
class MinimapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#0E1116")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1E2A38")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#33506B")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val otherPaint = Paint().apply {
        color = Color.parseColor("#7FA6C9")
        style = Paint.Style.FILL
    }
    private val youPaint = Paint().apply {
        color = Color.parseColor("#FFB300")
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint().apply {
        color = Color.parseColor("#FFB300")
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val hintPaint = Paint().apply {
        color = Color.parseColor("#6B7A8C")
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private var agents: List<Vector3> = emptyList()
    private var you: Vector3? = null
    private var precise: Vector3? = null
    private var heading = 0f
    private var hasData = false

    fun update(agents: List<Vector3>, you: Vector3?, precise: Vector3?, headingDegrees: Float, hasData: Boolean) {
        this.agents = agents
        this.you = you
        this.precise = precise
        this.heading = headingDegrees
        this.hasData = hasData
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val right = left + size
        val bottom = top + size

        canvas.drawRect(left, top, right, bottom, backgroundPaint)

        // 256 m region divided into 32 m cells
        for (i in 1 until 8) {
            val fraction = i / 8f
            val x = left + size * fraction
            val y = top + size * fraction
            canvas.drawLine(x, top, x, bottom, gridPaint)
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        canvas.drawRect(left, top, right, bottom, borderPaint)

        if (!hasData && precise == null) {
            canvas.drawText(
                "Esperando datos de posición del simulador...",
                left + size / 2f,
                top + size / 2f,
                hintPaint
            )
            return
        }

        val dotRadius = size / 90f
        val localYou = you ?: precise
        for (agent in agents) {
            if (localYou != null && same(agent, localYou)) {
                continue
            }
            val p = project(agent, left, top, size)
            canvas.drawCircle(p.x, p.y, dotRadius, otherPaint)
        }

        if (localYou != null) {
            val p = project(localYou, left, top, size)
            canvas.drawCircle(p.x, p.y, dotRadius * 1.6f, youPaint)
            val radians = Math.toRadians(heading.toDouble())
            val length = dotRadius * 5f
            canvas.drawLine(
                p.x,
                p.y,
                p.x + (cos(radians) * length).toFloat(),
                p.y - (sin(radians) * length).toFloat(),
                arrowPaint
            )
        }
    }

    private fun same(a: Vector3, b: Vector3): Boolean {
        return kotlin.math.abs(a.x - b.x) < 0.5f && kotlin.math.abs(a.y - b.y) < 0.5f
    }

    /** Region-local metres (X east, Y north) to canvas coordinates. */
    private fun project(position: Vector3, left: Float, top: Float, size: Float): Point {
        val fx = (position.x / REGION_METRES).coerceIn(0f, 1f)
        val fy = (position.y / REGION_METRES).coerceIn(0f, 1f)
        return Point(left + fx * size, top + size - fy * size)
    }

    private class Point(val x: Float, val y: Float)

    private companion object {
        const val REGION_METRES = 256f
    }
}
