package androidx.core.app

import android.app.Notification
import android.content.Context

object NotificationManagerCompat {
    const val IMPORTANCE_LOW = 2
    const val IMPORTANCE_DEFAULT = 3
    const val IMPORTANCE_HIGH = 4

    fun from(context: Context?): NotificationManagerCompat = NotificationManagerCompat

    fun notify(id: Int, notification: Notification?) {}
    fun cancel(id: Int) {}
    fun cancelAll() {}
    fun areNotificationsEnabled(): Boolean = true
    fun createNotificationChannel(channel: NotificationChannelCompat?) {}
}

class NotificationChannelCompat private constructor(val id: String, val importance: Int) {
    class Builder(private val id: String, private val importance: Int) {
        fun setName(name: CharSequence?): Builder = this
        fun setDescription(description: String?): Builder = this
        fun setShowBadge(showBadge: Boolean): Builder = this
        fun build(): NotificationChannelCompat = NotificationChannelCompat(id, importance)
    }
}

class NotificationCompat private constructor() {
    class Builder(private val context: Context?, private val channelId: String?) {
        fun setSmallIcon(icon: Int): Builder = this
        fun setContentTitle(title: CharSequence?): Builder = this
        fun setContentText(text: CharSequence?): Builder = this
        fun setContentIntent(intent: android.app.PendingIntent?): Builder = this
        fun addAction(icon: Int, title: CharSequence?, intent: android.app.PendingIntent?): Builder = this
        fun setOngoing(ongoing: Boolean): Builder = this
        fun setOnlyAlertOnce(onlyAlertOnce: Boolean): Builder = this
        fun setShowWhen(show: Boolean): Builder = this
        fun setAutoCancel(cancel: Boolean): Builder = this
        fun setCategory(category: String?): Builder = this
        fun setPriority(priority: Int): Builder = this
        fun build(): Notification = Notification()
    }

    companion object {
        const val CATEGORY_SERVICE = "service"
        const val CATEGORY_MESSAGE = "msg"
        const val PRIORITY_LOW = -1
        const val PRIORITY_DEFAULT = 0
    }
}

object ActivityCompat {
    fun requestPermissions(activity: android.app.Activity?, permissions: Array<String>, requestCode: Int) {}
}
