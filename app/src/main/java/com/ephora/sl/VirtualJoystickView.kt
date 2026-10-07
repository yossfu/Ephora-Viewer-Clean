package com.ephora.sl

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class VirtualJoystickView @JvmOverloads constructor(
  context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
  var onMove: ((x: Float, y: Float) -> Unit)? = null
  var onRelease: (() -> Unit)? = null
  private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private var knobX = 0f
  private var knobY = 0f
  private var active = false

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val cx = width * 0.5f
    val cy = height * 0.5f
    val radius = minOf(width, height) * 0.36f
    ringPaint.color = 0x99D6F4FF.toInt()
    canvas.drawCircle(cx, cy, radius, ringPaint)
    fillPaint.color = 0x3344B9E8
    canvas.drawCircle(cx, cy, radius, fillPaint)
    val maxOffset = radius * 0.62f
    fillPaint.color = if (active) 0xE622D3EE.toInt() else 0xBB22D3EE.toInt()
    canvas.drawCircle(cx + knobX * maxOffset, cy + knobY * maxOffset, radius * 0.30f, fillPaint)
    fillPaint.color = 0x88FFFFFF.toInt()
    canvas.drawCircle(cx + knobX * maxOffset, cy + knobY * maxOffset, radius * 0.11f, fillPaint)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    val cx = width * 0.5f
    val cy = height * 0.5f
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
        active = true
        val radius = minOf(width, height) * 0.36f
        val dx = event.x - cx
        val dy = event.y - cy
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val max = (radius * 0.62f).coerceAtLeast(1f)
        if (len > max) {
          knobX = dx / len
          knobY = dy / len
        } else {
          knobX = dx / max
          knobY = dy / max
        }
        onMove?.invoke(knobX.coerceIn(-1f, 1f), knobY.coerceIn(-1f, 1f))
        invalidate()
        return true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        active = false
        knobX = 0f
        knobY = 0f
        onMove?.invoke(0f, 0f)
        onRelease?.invoke()
        invalidate()
        performClick()
        return true
      }
    }
    return true
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }
}
