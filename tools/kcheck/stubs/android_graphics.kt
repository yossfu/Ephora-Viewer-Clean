package android.graphics

class Canvas {
    constructor()
    constructor(bitmap: Bitmap)
    fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: Paint) {}
    fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {}
    fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, paint: Paint) {}
    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) {}
    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {}
    fun drawText(text: String, x: Float, y: Float, paint: Paint) {}
}

class Paint {
    constructor()
    constructor(flags: Int)

    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Align { LEFT, CENTER, RIGHT }

    class FontMetrics {
        var ascent: Float = 0f
        var descent: Float = 0f
        var top: Float = 0f
        var bottom: Float = 0f
        var leading: Float = 0f
    }

    var color: Int = 0
    var style: Style = Style.FILL
    var strokeWidth: Float = 0f
    var strokeCap: Cap = Cap.BUTT
    var textSize: Float = 0f
    var textAlign: Align = Align.LEFT
    var isAntiAlias: Boolean = false
    var typeface: Typeface = Typeface.DEFAULT
    val fontMetrics: FontMetrics get() = FontMetrics()

    fun measureText(text: String): Float = 0f
    fun setShadowLayer(radius: Float, dx: Float, dy: Float, color: Int) {}

    companion object {
        const val ANTI_ALIAS_FLAG = 1
    }
}

class Bitmap {
    enum class Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888 }

    val width: Int get() = 0
    val height: Int get() = 0
    fun recycle() {}

    companion object {
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap = Bitmap()
        fun createBitmap(src: Bitmap): Bitmap = Bitmap()
    }
}

class Typeface {
    companion object {
        val DEFAULT: Typeface = Typeface()
        val DEFAULT_BOLD: Typeface = Typeface()
        val MONOSPACE: Typeface = Typeface()
    }
}

class RectF(l: Float, t: Float, r: Float, b: Float) {
    constructor() : this(0f, 0f, 0f, 0f)
}

object Color {
    fun parseColor(colorString: String): Int = 0
    fun rgb(red: Int, green: Int, blue: Int): Int = 0
    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int = 0
    const val WHITE = -1
    const val BLACK = -16777216
    const val TRANSPARENT = 0
}
