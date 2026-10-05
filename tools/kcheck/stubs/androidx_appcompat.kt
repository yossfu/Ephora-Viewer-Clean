package androidx.appcompat.app
open class AppCompatActivity : androidx.activity.ComponentActivity() {
    open fun onSupportNavigateUp(): Boolean = true
}

open class AlertDialog(context: android.content.Context?) : android.app.Dialog(context) {
    class Builder(private val context: android.content.Context?) {
        fun setTitle(title: CharSequence?): Builder = this
        fun setTitle(title: Int): Builder = this
        fun setMessage(message: CharSequence?): Builder = this
        fun setMessage(message: Int): Builder = this
        fun setView(view: android.view.View?): Builder = this
        fun setItems(
            items: Array<out CharSequence>?,
            listener: android.content.DialogInterface.OnClickListener?
        ): Builder = this
        fun setPositiveButton(text: CharSequence?, listener: android.content.DialogInterface.OnClickListener?): Builder = this
        fun setPositiveButton(text: Int, listener: android.content.DialogInterface.OnClickListener?): Builder = this
        fun setNegativeButton(text: CharSequence?, listener: android.content.DialogInterface.OnClickListener?): Builder = this
        fun setNegativeButton(text: Int, listener: android.content.DialogInterface.OnClickListener?): Builder = this
        fun setCancelable(cancelable: Boolean): Builder = this
        fun create(): AlertDialog = AlertDialog(context)
        fun show(): AlertDialog = AlertDialog(context)
    }
    fun setTitle(title: CharSequence?) {}
    fun setMessage(message: CharSequence?) {}
}
