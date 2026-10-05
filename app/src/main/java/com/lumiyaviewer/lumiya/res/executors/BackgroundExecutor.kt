package com.lumiyaviewer.lumiya.res.executors

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object BackgroundExecutor {

    private var scheduler: ScheduledExecutorService? = null

    @Synchronized
    fun start() {
        if (scheduler == null) {
            scheduler = Executors.newScheduledThreadPool(4) { runnable ->
                Thread(runnable, "lumiya-bg").apply { isDaemon = true }
            }
        }
    }

    @Synchronized
    fun shutdown() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    fun schedule(task: Runnable, delayMillis: Long) {
        scheduler?.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
    }
}
