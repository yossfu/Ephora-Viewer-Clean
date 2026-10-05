package android.util
interface AttributeSet
object Log {
    fun d(tag: String, msg: String): Int = 0
    fun i(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String, tr: Throwable): Int = 0
    fun e(tag: String, msg: String): Int = 0
    fun e(tag: String, msg: String, tr: Throwable): Int = 0
}
class DisplayMetrics { val density: Float = 1f }
object Xml { fun newPullParser(): org.xmlpull.v1.XmlPullParser = TODO() }
object Base64 {
    const val NO_WRAP: Int = 2
    fun encodeToString(input: ByteArray, flags: Int): String = ""
}
