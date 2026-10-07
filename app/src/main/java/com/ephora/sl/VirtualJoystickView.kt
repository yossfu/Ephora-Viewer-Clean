package com.ephora.sl

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class VirtualJoystickView @JvmOverloads constructor(
  context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
  @Volatile private var knobX = 0f
  @Volatile private var knobY = 0f
  @Volatile private var active = false
  private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

  fun setState(x: Float, y: Float, pressed: Boolean) {
    knobX = x.coerceIn(-1f, 1f)
    knobY = y.coerceIn(-1f, 1f)
    active = pressed
    postInvalidateOnAnimation()
  }

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
    return false
  }
}
