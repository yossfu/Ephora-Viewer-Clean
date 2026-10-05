package android.widget
import android.content.Context
import android.view.View
import android.view.ViewGroup
open class TextView(context: Context?) : View(context) {
    var text: CharSequence? = ""
}
// Real Android's EditText *narrows* getText() to `Editable` while the inherited
// setText() still takes `CharSequence`, so Kotlin's synthetic `text` property is
// typed `Editable?` and `editText.text = "..."` does NOT compile (a String is not
// an Editable) -- this is exactly what the GitHub Actions kotlinc rejected in
// LoginActivity/ChatNewActivity. Kotlin cannot express that covariant-return
// pairing, so this stub models the *consequence* directly: `text` is an Editable,
// and the CharSequence form is a real `setText` method, as on Android.
open class EditText(context: Context?) : View(context) {
    var text: android.text.Editable = android.text.SpannableStringBuilder()
    var hint: CharSequence? = null
    var inputType: Int = 0
    fun setText(text: CharSequence?) {
        this.text = android.text.SpannableStringBuilder(text ?: "")
    }
}
open class Button(context: Context?) : TextView(context)
open class ImageView(context: Context?) : View(context) { fun setImageResource(resId: Int) {} }
open class ImageButton(context: Context?) : ImageView(context)
open class ProgressBar(context: Context?) : View(context)
open class LinearLayout(context: Context?) : ViewGroup(context)
open class FrameLayout(context: Context?) : ViewGroup(context) {
    open class LayoutParams : ViewGroup.LayoutParams {
        constructor(width: Int, height: Int) : super(width, height)
        constructor(source: ViewGroup.LayoutParams?) : super(-1, -1)
    }
}
open class ScrollView(context: Context?) : ViewGroup(context) { fun fullScroll(direction: Int) {} }
interface SpinnerAdapter
open class BaseAdapter : SpinnerAdapter
open class ArrayAdapter<T>(context: Context?, resource: Int, objects: MutableList<T>) : BaseAdapter() {
    fun setDropDownViewResource(resource: Int) {}
}
open class AdapterView<T>(context: Context?) : ViewGroup(context) {
    interface OnItemSelectedListener {
        fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long)
        fun onNothingSelected(parent: AdapterView<*>?)
    }
    var onItemSelectedListener: OnItemSelectedListener? = null
}
open class Spinner(context: Context?) : AdapterView<SpinnerAdapter>(context) {
    var adapter: SpinnerAdapter? = null
    var selectedItemPosition: Int = 0
    fun setSelection(position: Int) {}
}
class Toast {
    fun show() {}
    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        fun makeText(context: Context?, text: Int, duration: Int): Toast = Toast()
        fun makeText(context: Context?, text: CharSequence?, duration: Int): Toast = Toast()
    }
}
