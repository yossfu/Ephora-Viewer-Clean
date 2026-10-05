package android.view
import android.content.Context
import android.graphics.Canvas
open class View : android.content.Context {
    constructor(context: Context?) : super()
    constructor(context: Context?, attrs: android.util.AttributeSet?) : super()
    constructor(context: Context?, attrs: android.util.AttributeSet?, defStyleAttr: Int) : super()
    fun interface OnClickListener { fun onClick(v: View) }
    fun interface OnTouchListener { fun onTouch(v: View, event: MotionEvent): Boolean }
    open val display: Display? get() = null
    var visibility: Int = 0
    var isEnabled: Boolean = true
    var isPressed: Boolean = false
    var isFocusable: Boolean = false
    var isFocusableInTouchMode: Boolean = false
    val context: Context get() = TODO()
    fun setOnClickListener(l: OnClickListener?) {}
    fun setOnTouchListener(l: OnTouchListener) {}
    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {}
    fun setBackgroundColor(color: Int) {}
    fun post(action: Runnable): Boolean = true
    fun invalidate() {}
    open fun onDraw(canvas: Canvas) {}
    open fun onTouchEvent(event: MotionEvent): Boolean = false
    protected open fun onAttachedToWindow() {}
    protected open fun onDetachedFromWindow() {}
    open val width: Int get() = 0
    open val height: Int get() = 0
    open val parent: View? get() = null
    var keepScreenOn: Boolean = false
    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        const val FOCUS_DOWN = 130
    }
}
open class ViewGroup : View {
    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: android.util.AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: android.util.AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
    fun addView(child: View) {}
    fun addView(child: View, params: LayoutParams) {}
    fun removeAllViews() {}
    fun getChildCount(): Int = 0
    open class LayoutParams(width: Int, height: Int) {
        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
            const val FILL_PARENT = -1
        }
    }
}
class MotionEvent {
    val actionMasked: Int get() = 0
    val action: Int get() = 0
    val x: Float get() = 0f
    val y: Float get() = 0f
    val pointerCount: Int get() = 1
    fun getX(index: Int): Float = 0f
    fun getY(index: Int): Float = 0f
    fun getPointerId(index: Int): Int = 0
    fun findPointerIndex(id: Int): Int = 0
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
    }
}
class Surface {
    val isValid: Boolean get() = true
}
class Display {
    val refreshRate: Float get() = 60f
}
class Choreographer {
    fun interface FrameCallback {
        fun doFrame(frameTimeNanos: Long)
    }

    fun postFrameCallback(callback: FrameCallback) {}
    fun postFrameCallbackDelayed(callback: FrameCallback, delayMillis: Long) {}
    fun removeFrameCallback(callback: FrameCallback) {}

    companion object {
        fun getInstance(): Choreographer = Choreographer()
    }
}
interface SurfaceHolder {
    val surface: Surface
    fun addCallback(callback: Callback)
    fun removeCallback(callback: Callback)
    fun setFixedSize(width: Int, height: Int)
    interface Callback {
        fun surfaceCreated(holder: SurfaceHolder)
        fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int)
        fun surfaceDestroyed(holder: SurfaceHolder)
    }
}
open class SurfaceView : View {
    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: android.util.AttributeSet?) : super(context, attrs)
    val holder: SurfaceHolder get() = TODO()
}
open class LayoutInflater {
    companion object {
        fun from(context: Context): LayoutInflater = LayoutInflater()
    }
}
