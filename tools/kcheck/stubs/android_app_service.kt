package android.app

import android.content.Intent
import android.os.IBinder

open class Service : android.content.Context() {
    open fun onCreate() {}
    open fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = 0
    open fun onDestroy() {}
    open fun onBind(intent: Intent?): IBinder? = null
    open fun onTimeout(startId: Int) {}
    fun startForeground(id: Int, notification: Notification) {}
    fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {}
    fun stopSelf() {}
    fun stopSelf(startId: Int) {}
    companion object {
        const val START_STICKY = 1
        const val START_NOT_STICKY = 2
        const val START_REDELIVER_INTENT = 3
    }
}

open class Notification {
    companion object {
        const val CATEGORY_SERVICE = "service"
    }
}

class PendingIntent {
    companion object {
        const val FLAG_UPDATE_CURRENT = 134217728
        const val FLAG_IMMUTABLE = 67108864
        const val FLAG_CANCEL_CURRENT = 268435456
        fun getActivity(context: android.content.Context?, requestCode: Int, intent: Intent?, flags: Int): PendingIntent = PendingIntent()
        fun getService(context: android.content.Context?, requestCode: Int, intent: Intent?, flags: Int): PendingIntent = PendingIntent()
        fun getBroadcast(context: android.content.Context?, requestCode: Int, intent: Intent?, flags: Int): PendingIntent = PendingIntent()
    }
}

open class NotificationManager {
    fun notify(id: Int, notification: Notification) {}
    fun cancel(id: Int) {}
    fun createNotificationChannel(channel: Any?) {}
}

open class NotificationChannel(id: String?, name: CharSequence?, importance: Int)
