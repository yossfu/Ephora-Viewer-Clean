package com.lumiyaviewer.lumiya.slproto.caps

import com.lumiyaviewer.lumiya.slproto.llsd.LLSDInteger
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * The region's event queue: a long-poll HTTP capability the simulator uses to
 * push everything that is not a movement or chat packet — instant messages,
 * offline messages, teleports, script dialogs, group notices, friendship
 * offers.
 *
 * The loop is: POST `{ "ack": <last id>, "done": false }`, and the server
 * answers — eventually — with `{ "id": <n>, "events": [ { "message": name,
 * "body": map }, ... ] }`. When nothing happens for a minute it answers
 * 502/503/504 instead, which only means "ask again". Raising the HTTP read
 * timeout above the server's hold time is what keeps the connection useful.
 */
class EventQueue(
    private val capabilities: Capabilities,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onEvent: (String, Any?) -> Unit
) {

    private var job: Job? = null
    private var lastId: Long = -1L

    var running: Boolean = false
        private set

    var eventCount: Long = 0L
        private set

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var consecutiveFailures: Int = 0
        private set

    fun start() {
        if (job != null || !capabilities.isReady) {
            return
        }
        if (capabilities.url(CAP_NAME) == null) {
            log("La simulacion no ofrece " + CAP_NAME + ": sin mensajes instantaneos")
            return
        }
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
    }

    private suspend fun loop() {
        running = true
        log("Cola de eventos abierta")
        var failures = 0
        while (coroutineContext.isActive) {
            val body = LinkedHashMap<String, Any?>()
            body["ack"] = LLSDInteger(lastId)
            body["done"] = false
            val parsed = try {
                capabilities.requestParsed(CAP_NAME, body, READ_TIMEOUT_MILLIS)
            } catch (t: Throwable) {
                null
            }
            if (parsed == null) {
                failures += 1
                consecutiveFailures = failures
                lastError = capabilities.lastFailureOf(CAP_NAME)?.describe()
                    ?: capabilities.lastError
                if (failures == 1 || failures % 8 == 0) {
                    log("Cola de eventos: " + lastError + " · reintentando; esto NO cierra la sesion")
                }
                delay(backoffMillis(failures))
                continue
            }
            failures = 0
            consecutiveFailures = 0
            lastError = ""
            val map = LLSDParser.asMap(parsed)
            val id = LLSDParser.asLong(map["id"], -1L)
            if (id >= 0L) {
                lastId = id
            }
            for (event in map["events"] as? List<*> ?: emptyList<Any?>()) {
                deliver(event)
            }
        }
    }

    private fun deliver(event: Any?) {
        val map = LLSDParser.asMap(event)
        val name = LLSDParser.asString(map["message"])
        if (name.isEmpty()) {
            return
        }
        eventCount += 1
        try {
            onEvent(name, map["body"])
        } catch (t: Throwable) {
            log("Error procesando el evento " + name + ": " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun backoffMillis(failures: Int): Long {
        val seconds = when {
            failures < 3 -> 1L
            failures < 8 -> 3L
            else -> 10L
        }
        return seconds * 1000L
    }

    companion object {
        const val CAP_NAME = "EventQueueGet"

        /** Longer than the server's own hold time, so a quiet queue is not an error. */
        const val READ_TIMEOUT_MILLIS = 90000
    }
}
