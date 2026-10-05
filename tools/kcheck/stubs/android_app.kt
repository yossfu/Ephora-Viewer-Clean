package android.app
open class Activity : android.content.Context() {
    open fun onCreate(savedInstanceState: android.os.Bundle?) {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onStart() {}
    open fun onStop() {}
    open fun onDestroy() {}
    override fun getString(resId: Int): String = ""
    override fun getString(resId: Int, vararg formatArgs: Any?): String = ""
    fun setContentView(view: android.view.View) {}
    fun startActivity(intent: android.content.Intent) {}
    fun finish() {}
    fun runOnUiThread(action: Runnable) { action.run() }
    val layoutInflater: android.view.LayoutInflater get() = TODO()
    fun isFinishing(): Boolean = false
}
open class Application : android.content.Context() {
    open fun onCreate() {}
    open fun onTerminate() {}
}

open class Dialog(context: android.content.Context?) {
    fun show() {}
    fun dismiss() {}
    fun setCancelable(cancelable: Boolean) {}
}
