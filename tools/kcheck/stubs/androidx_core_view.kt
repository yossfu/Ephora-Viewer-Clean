package androidx.core.view
import android.view.View
class WindowInsetsCompat {
    class Insets { val left: Int = 0; val top: Int = 0; val right: Int = 0; val bottom: Int = 0 }
    object Type { fun systemBars(): Int = 0 }
    fun getInsets(mask: Int): Insets = Insets()
    companion object { val CONSUMED: WindowInsetsCompat = WindowInsetsCompat() }
}
object ViewCompat {
    fun interface OnApplyWindowInsetsListener { fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat }
    fun setOnApplyWindowInsetsListener(view: View, listener: OnApplyWindowInsetsListener): WindowInsetsCompat = WindowInsetsCompat()
}