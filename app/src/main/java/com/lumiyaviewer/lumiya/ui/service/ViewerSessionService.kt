package com.lumiyaviewer.lumiya.ui.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.modules.ConnectionState
import com.lumiyaviewer.lumiya.slproto.modules.SessionInfo
import com.lumiyaviewer.lumiya.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the session alive while the user is in another app.
 *
 * This is not cosmetic: when Ephora Viewer is backgrounded, Android can start
 * denying its UDP sends with `EPERM` (battery saver, data saver, an app
 * firewall, or the restricted standby bucket), which looks exactly like "the
 * viewer stopped working after a minute". A foreground service moves the process
 * out of that bucket, and the wake lock keeps the CPU awake with the screen off
 * so the circuit keeps answering the simulator's pings.
 */
class ViewerSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var lastNotifyRegion = ""
    @Volatile private var lastNotifyMillis = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireLocks()
        try {
            startForegroundCompat(getString(R.string.service_starting))
        } catch (t: Throwable) {
            // En Android 12+ el sistema puede rearrancar un servicio sticky con
            // la app en fondo, y startForeground lanza
            // ForegroundServiceStartNotAllowedException: morir en onCreate mata
            // el visor entero en bucle de reinicios (muertes del 2026-09-24).
            // Retroceder sin keepalive; la sesion sobrevive hasta que el
            // usuario vuelve.
            stopSelf()
            return
        }
        scope.launch {
            SLClient.connection.session.collect { session -> updateNotification(session) }
        }
        scope.launch {
            SLClient.connection.state.collect { state ->
                // Only an explicit DISCONNECTED state ends the keepalive service.
                // ERROR is a diagnostic state; stopping the service here can make a
                // recoverable network incident look like a full logout.
                if (state == ConnectionState.DISCONNECTED) {
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SLClient.connection.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        // Android 14+ caps dataSync services; end the session cleanly instead of
        // being killed mid-flight.
        SLClient.connection.disconnect()
        stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ----------------------------------------------------------- notification ---

    private fun createChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.service_channel_name))
            .setDescription(getString(R.string.service_channel_description))
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(getString(R.string.app_name), text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(session: SessionInfo) {
        // El flujo de sesion emite varias veces por segundo y cada notify
        // cuesta un IPC: el sistema ya nos recortaba por rate-limit (medido
        // 5-7/s en dispositivo). Solo renotificar con region nueva o cada 5 s.
        val now = SystemClock.elapsedRealtime()
        val region = session.regionName
        if (region == lastNotifyRegion && now - lastNotifyMillis < NOTIFY_MIN_INTERVAL_MILLIS) {
            return
        }
        lastNotifyRegion = region
        lastNotifyMillis = now
        val text = if (region.isEmpty()) {
            getString(R.string.service_connecting)
        } else {
            getString(R.string.service_connected, session.regionName)
        }
        NotificationManagerCompat.from(this)
            .notify(NOTIFICATION_ID, buildNotification(getString(R.string.app_name), text))
    }

    private fun buildNotification(title: String, text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_world)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0,
            getString(R.string.service_disconnect),
            PendingIntent.getService(
                this,
                1,
                Intent(this, ViewerSessionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    // ---------------------------------------------------------------- locks ---

    private fun acquireLocks() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            // A missing wake lock only costs battery life, never correctness.
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            // Same here.
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            // ignore
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            // ignore
        }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        const val ACTION_STOP = "com.lumiyaviewer.lumiya.action.STOP_SESSION"
        private const val CHANNEL_ID = "ephora_session"
        private const val NOTIFICATION_ID = 4711
        private const val NOTIFY_MIN_INTERVAL_MILLIS = 5000L
        private const val WAKE_LOCK_TAG = "ephora:session"
        private const val WIFI_LOCK_TAG = "ephora:wifi"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ViewerSessionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ViewerSessionService::class.java))
        }
    }
}
