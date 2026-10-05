package com.lumiyaviewer.lumiya.slproto.login

import android.os.Build
import com.lumiyaviewer.lumiya.slproto.base.LLUUIDUtil
import com.lumiyaviewer.lumiya.slproto.inventory.InventoryFolder
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDParser
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLSD login against a Second Life / OpenSim login endpoint
 * (`.../cgi-bin/login.cgi`), the same handshake the official viewer uses.
 * Follows the `indeterminate` redirect handshake the grid can request when a
 * previous session is still shutting down.
 */
class LoginModule {

    suspend fun login(
        loginUri: String,
        firstName: String,
        lastName: String,
        password: String,
        agreeToTos: Boolean
    ): LoginResult = withContext<LoginResult>(Dispatchers.IO) {
        performLogin(loginUri, firstName, lastName, password, agreeToTos)
    }

    private suspend fun performLogin(
        loginUri: String,
        firstName: String,
        lastName: String,
        password: String,
        agreeToTos: Boolean
    ): LoginResult {
        var uri = loginUri
        var options = DEFAULT_OPTIONS
        var start = "last"
        var redirects = 0
        var startRetries = 0

        while (true) {

            val payload = LinkedHashMap<String, Any?>()
            payload["first"] = firstName
            payload["last"] = lastName
            payload["passwd"] = password
            payload["start"] = start
            payload["channel"] = CHANNEL
            payload["version"] = VERSION
            payload["platform"] = "Android"
            payload["platform_version"] = Build.VERSION.RELEASE ?: "0"
            payload["mac"] = randomMac()
            payload["id0"] = LLUUIDUtil.random().replace("-", "")
            payload["agree_to_tos"] = agreeToTos
            payload["read_critical"] = true
            payload["viewer_digest"] = LLUUIDUtil.random().replace("-", "")
            payload["last_exec_event"] = 0
            payload["options"] = options

            val body = LLSDWriter.toXml(payload).toByteArray(Charsets.UTF_8)

            val response = try {
                post(uri, body)
            } catch (e: Exception) {
                return LoginResult.failure(
                    "network",
                    "No se pudo contactar con el servidor de login (" + uri + "): " +
                        (e.message ?: e.javaClass.simpleName)
                )
            }

            if (response.status !in 200..299) {
                return LoginResult.failure(
                    "http",
                    "El servidor de login respondio HTTP " + response.status
                )
            }
            if (response.text.isBlank()) {
                return LoginResult.failure("empty", "Respuesta de login vacia")
            }

            val map = try {
                LLSDParser.asMap(LLSDParser.parse(response.text))
            } catch (e: Exception) {
                return LoginResult.failure(
                    "parse",
                    "No se pudo interpretar la respuesta: " + e.message
                )
            }
            if (map.isEmpty()) {
                return LoginResult.failure(
                    "parse",
                    "Respuesta inesperada: " + response.text.take(180)
                )
            }

            val state = loginState(map)
            val reason = LLSDParser.asString(map["reason"])
            val message = LLSDParser.asString(map["message"])

            if (state == "indeterminate") {
                val nextUrl = LLSDParser.asString(map["next_url"])
                val nextMethod = LLSDParser.asString(map["next_method"])
                val nextDuration = LLSDParser.asInt(map["next_duration"])
                if (nextUrl.isEmpty() || (nextMethod.isNotEmpty() && !nextMethod.equals("POST", true))) {
                    return LoginResult.failure(
                        "redirect",
                        "El grid pidio una redireccion de login no soportada" +
                            (if (nextMethod.isEmpty()) "" else " (" + nextMethod + ")")
                    )
                }
                if (redirects >= MAX_REDIRECTS) {
                    return LoginResult.failure(
                        "redirect",
                        "Demasiadas redirecciones del servidor de login"
                    )
                }
                redirects += 1
                val nextOptions = readOptions(map["next_options"])
                if (nextOptions.isNotEmpty()) {
                    options = nextOptions
                }
                uri = nextUrl
                val waitSeconds = nextDuration.coerceIn(1, 30).toLong()
                delay(waitSeconds * 1000L)
                continue
            }

            if (state != "true") {
                // "last" is the natural start location, but it fails for accounts
                // that never logged in before. Fall back to "home" once.
                if (startRetries == 0 && RETRY_WITH_HOME.contains(reason.lowercase())) {
                    startRetries += 1
                    start = "home"
                    continue
                }
                return LoginResult.failure(
                    if (reason.isEmpty()) "denied" else reason,
                    if (message.isEmpty()) "Login rechazado (" + reason + ")" else message
                )
            }

            return LoginResult(
                success = true,
                message = if (message.isEmpty()) "Login correcto" else message,
                reason = reason,
                agentId = LLSDParser.asString(map["agent_id"]),
                sessionId = LLSDParser.asString(map["session_id"]),
                secureSessionId = LLSDParser.asString(map["secure_session_id"]),
                circuitCode = LLSDParser.asInt(map["circuit_code"]),
                simIp = LLSDParser.asString(map["sim_ip"]),
                simPort = LLSDParser.asInt(map["sim_port"]),
                regionX = LLSDParser.asInt(map["region_x"]),
                regionY = LLSDParser.asInt(map["region_y"]),
                seedCapability = LLSDParser.asString(map["seed_capability"]),
                firstName = LLSDParser.asString(map["first_name"]).ifEmpty { firstName },
                lastName = LLSDParser.asString(map["last_name"]).ifEmpty { lastName },
                startLocation = LLSDParser.asString(map["start_location"]),
                inventoryRoot = readInventoryRoot(map["inventory-root"]),
                inventoryFolders = readInventorySkeleton(map["inventory-skeleton"]) +
                    readInventorySkeleton(map["inventory-skel-lib"]),
                libraryRoot = readInventoryRoot(map["inventory-lib-root"]),
                libraryOwner = readLibraryOwner(map["inventory-lib-owner"])
            )
        }
    }

    /**
     * `inventory-lib-root` gives the Library's folder id and
     * `inventory-lib-owner` the UUID that owns it — reading the Library needs
     * that owner, not ours.
     */
    private fun readLibraryOwner(value: Any?): String {
        val list = value as? List<*> ?: return ""
        for (item in list) {
            val map = LLSDParser.asMap(item)
            val id = LLSDParser.asString(map["agent_id"])
            if (id.isNotEmpty()) {
                return id
            }
            val folder = LLSDParser.asString(map["folder_id"])
            if (folder.isNotEmpty()) {
                return folder
            }
        }
        return ""
    }

    private class Response(val status: Int, val text: String)

    private fun post(uri: String, body: ByteArray): Response {
        val connection = URL(uri).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Content-Type", "application/llsd+xml")
            connection.setRequestProperty("Accept", "application/llsd+xml")
            connection.setRequestProperty("User-Agent", CHANNEL + " " + VERSION)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Response(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun loginState(map: Map<String, Any?>): String {
        val raw = map["login"] ?: return ""
        return when (raw) {
            is Boolean -> if (raw) "true" else "false"
            else -> raw.toString().trim().lowercase()
        }
    }

    private fun readInventoryRoot(value: Any?): String {
        val list = value as? List<*> ?: return ""
        for (item in list) {
            val map = LLSDParser.asMap(item)
            val id = LLSDParser.asString(map["folder_id"])
            if (id.isNotEmpty()) {
                return id
            }
        }
        return ""
    }

    private fun readInventorySkeleton(value: Any?): List<InventoryFolder> {
        val list = value as? List<*> ?: return emptyList()
        val out = ArrayList<InventoryFolder>(list.size)
        for (item in list) {
            val map = LLSDParser.asMap(item)
            val id = LLSDParser.asString(map["folder_id"])
            if (id.isEmpty()) {
                continue
            }
            out.add(
                InventoryFolder(
                    id = id,
                    parentId = LLSDParser.asString(map["parent_id"]),
                    name = LLSDParser.asString(map["name"])
                )
            )
        }
        return out
    }

    private fun readOptions(value: Any?): List<String> {
        val list = value as? List<*> ?: return emptyList()
        val out = ArrayList<String>(list.size)
        for (item in list) {
            val text = LLSDParser.asString(item).trim()
            if (text.isNotEmpty()) {
                out.add(text)
            }
        }
        return out
    }

    private fun randomMac(): String {
        val builder = StringBuilder(17)
        for (i in 0 until 6) {
            if (i > 0) {
                builder.append(':')
            }
            val value = (Math.random() * 256).toInt() and 0xFF
            builder.append(String.format("%02X", value))
        }
        return builder.toString()
    }

    companion object {
        const val CHANNEL = "Ephora Viewer"
        const val VERSION = "1.0.0"

        private const val MAX_REDIRECTS = 2

        /** Reasons that mean "this account has no last location yet". */
        private val RETRY_WITH_HOME = setOf(
            "last",
            "no_start",
            "no_start_location",
            "no_home",
            "no home",
            "home"
        )

        private val DEFAULT_OPTIONS = listOf(
            "inventory-root",
            "inventory-skeleton",
            "inventory-lib-root",
            "inventory-lib-owner",
            "inventory-skel-lib",
            "display-names"
        )
    }
}
