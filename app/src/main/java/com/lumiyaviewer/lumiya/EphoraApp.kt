package com.lumiyaviewer.lumiya

import android.app.Application
import com.lumiyaviewer.lumiya.renderer.filament.FilamentBootstrap
import com.lumiyaviewer.lumiya.res.executors.BackgroundExecutor
import com.lumiyaviewer.lumiya.slproto.grids.GridManager
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.messages.MessageTemplateLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EphoraApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Load libfilament-jni.so (and check the rest of the native libraries)
        // before anything can reach rendering code. Filament's own class
        // initializer is what loads it, and a class initializer that reaches
        // native code too early is unrecoverable for the whole process — see
        // FilamentBootstrap.
        FilamentBootstrap.startUp(this)
        GridManager.bootstrap()
        BackgroundExecutor.start()
        scope.launch {
            try {
                SLClient.templateReady.complete(MessageTemplateLoader.load(this@EphoraApp))
            } catch (t: Throwable) {
                SLClient.templateReady.completeExceptionally(t)
            }
        }
    }

    override fun onTerminate() {
        BackgroundExecutor.shutdown()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: EphoraApp
            private set
    }
}
