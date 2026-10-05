package android.content
interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun apply()
    }
}
class ClipData private constructor() {
    companion object { fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData = ClipData() }
}
class ClipboardManager { fun setPrimaryClip(clip: ClipData) {} }
class Intent(context: Context?, cls: Class<*>?) {
    var action: String? = null
    fun setAction(action: String?): Intent {
        this.action = action
        return this
    }
}

class ComponentName(pkg: String?, cls: String?)
interface DialogInterface {
    fun interface OnClickListener { fun onClick(dialog: DialogInterface?, which: Int) }
    companion object {
        const val BUTTON_POSITIVE = -1
        const val BUTTON_NEGATIVE = -2
    }
}
open class Context {
    open fun getString(resId: Int): String = ""
    open fun getString(resId: Int, vararg formatArgs: Any?): String = ""
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = TODO()
    open fun getSystemService(name: String): Any? = null
    open val applicationContext: Context get() = this
    open val applicationInfo: android.content.pm.ApplicationInfo get() = TODO()
    open val filesDir: java.io.File get() = TODO()
    open val cacheDir: java.io.File get() = TODO()
    fun stopService(service: Intent?): Boolean = true
    fun startService(service: Intent?): android.content.ComponentName? = null
    val assets: android.content.res.AssetManager get() = TODO()
    val resources: android.content.res.Resources get() = TODO()
    companion object {
        const val CLIPBOARD_SERVICE: String = "clipboard"
        const val MODE_PRIVATE: Int = 0
        const val POWER_SERVICE: String = "power"
        const val WIFI_SERVICE: String = "wifi"
        const val NOTIFICATION_SERVICE: String = "notification"
    }
}
