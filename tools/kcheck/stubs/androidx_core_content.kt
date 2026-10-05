package androidx.core.content

import android.content.Context

object ContextCompat {
    fun checkSelfPermission(context: Context?, permission: String?): Int = 0
    fun startForegroundService(context: Context?, intent: android.content.Intent?) {
        context?.startService(intent)
    }
    fun startActivity(context: Context?, intent: android.content.Intent?) {}
}
