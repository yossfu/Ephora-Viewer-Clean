package com.lumiyaviewer.lumiya.slproto

import com.lumiyaviewer.lumiya.slproto.modules.SLConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object SLClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val templateReady = CompletableDeferred<Int>()

    val connection = SLConnection(scope, templateReady)

    var gridName: String = ""

    var loginUri: String = ""
}
