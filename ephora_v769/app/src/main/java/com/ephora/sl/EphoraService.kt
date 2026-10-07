package com.ephora.sl
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
class EphoraService : Service() {
  companion object {
    const val CH = "ephora-fg"
    const val ACT_START = "EPHORA_START"
    const val ACT_STOP = "EPHORA_STOP"
    var running = false
    var serviceScope: CoroutineScope? = null
    var who = ""
    fun loopScope(fallback: CoroutineScope): CoroutineScope {
      val s = serviceScope
      if (s != null) return s
      val n = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      serviceScope = n
      return n
    }
    fun start(ctx: Context, name: String) {
      who = name
      val i = Intent(ctx, EphoraService::class.java).setAction(ACT_START)
      i.putExtra("who", name)
      try { ctx.startForegroundService(i) } catch(_: Throwable) { try { ctx.startService(i) } catch(_: Throwable) {} }
    }
    fun stop(ctx: Context) {
      try { ctx.startService(Intent(ctx, EphoraService::class.java).setAction(ACT_STOP)) } catch(_: Throwable) {}
    }
  }
  override fun onBind(i: Intent?): IBinder? = null
  override fun onStartCommand(i: Intent?, f: Int, sid: Int): Int {
    when (i?.action) {
      ACT_STOP -> {
        try { AgentLoop.onTick = null } catch(_: Throwable) {}
        try { EventQueue.EQLoop.onTick = null } catch(_: Throwable) {}
        try { AgentLoop.stop() } catch(_: Throwable) {}
        try { EventQueue.EQLoop.stop() } catch(_: Throwable) {}
        running = false
        try { serviceScope?.cancel() } catch(_: Throwable) {}
        serviceScope = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch(_: Throwable) {}
        stopSelf()
        return START_NOT_STICKY
      }
      else -> {
        val w = i?.getStringExtra("who")
        if (!w.isNullOrBlank()) who = w
        if (running) return START_STICKY
        running = true
        if (serviceScope == null) serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
          if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            try { nm.createNotificationChannel(NotificationChannel(CH, "EPHORA SL", NotificationManager.IMPORTANCE_LOW)) } catch(_: Throwable) {}
          }
          val stopI = PendingIntent.getService(this, 1, Intent(this, EphoraService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
          val nb = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
          val n = nb.setContentTitle("EPHORA SL · conectado como " + who.ifBlank { "?" })
            .setContentText("Manteniendo avatar, movimiento y chat")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Desconectar", stopI)
            .setOngoing(true).build()
          startForeground(1, n)
        } catch(_: Throwable) {}
        return START_STICKY
      }
    }
  }
}
