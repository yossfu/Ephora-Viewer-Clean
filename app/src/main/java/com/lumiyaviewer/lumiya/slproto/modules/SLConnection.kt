package com.lumiyaviewer.lumiya.slproto.modules

import com.lumiyaviewer.lumiya.slproto.asset.GetTextureAssetProvider
import com.lumiyaviewer.lumiya.slproto.asset.MeshAssetProvider
import com.lumiyaviewer.lumiya.slproto.asset.TextureAssetProvider
import com.lumiyaviewer.lumiya.slproto.base.LLUUIDUtil
import com.lumiyaviewer.lumiya.slproto.base.Vector3
import com.lumiyaviewer.lumiya.slproto.caps.Capabilities
import com.lumiyaviewer.lumiya.slproto.caps.EventQueue
import com.lumiyaviewer.lumiya.slproto.caps.GetTextureTransport
import com.lumiyaviewer.lumiya.slproto.caps.GetMeshTransport
import com.lumiyaviewer.lumiya.slproto.chat.ChatMessage
import com.lumiyaviewer.lumiya.slproto.chat.ChatSource
import com.lumiyaviewer.lumiya.slproto.circuit.Circuit
import com.lumiyaviewer.lumiya.slproto.grids.Grid
import com.lumiyaviewer.lumiya.slproto.inventory.InventoryClient
import com.lumiyaviewer.lumiya.slproto.inventory.InventoryFolderContents
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDParser
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDUuid
import com.lumiyaviewer.lumiya.slproto.login.LoginModule
import com.lumiyaviewer.lumiya.slproto.login.LoginResult
import com.lumiyaviewer.lumiya.slproto.messages.LLSDMessageDecoder
import com.lumiyaviewer.lumiya.slproto.messages.MessageTemplate
import com.lumiyaviewer.lumiya.slproto.messages.SLMessage
import com.lumiyaviewer.lumiya.slproto.movement.AgentControlFlags
import com.lumiyaviewer.lumiya.slproto.movement.AgentUpdateBuilder
import com.lumiyaviewer.lumiya.slproto.movement.MoveAction
import com.lumiyaviewer.lumiya.slproto.movement.MovementAudit
import com.lumiyaviewer.lumiya.slproto.world.ObjectUpdateDecoder
import com.lumiyaviewer.lumiya.slproto.world.ObjectUpdateDiagnostics
import com.lumiyaviewer.lumiya.slproto.world.WorldModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.HashSet
import java.util.Locale

enum class ConnectionState { DISCONNECTED, LOGGING_IN, CONNECTING, CONNECTED, ERROR }

data class SessionInfo(
    val agentName: String = "",
    val agentId: String = "",
    val gridName: String = "",
    val regionName: String = "",
    val position: Vector3 = Vector3.ZERO,
    val headingDegrees: Float = 0f,
    val flying: Boolean = false,
    val simIp: String = "",
    val simPort: Int = 0,
    val roundTripMillis: Int = 0,
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L,
    val pendingAcks: Int = 0,
    val connectedSince: Long = 0L,
    val coarseAgents: List<Vector3> = emptyList(),
    val coarseYou: Vector3? = null,
    val coarseSeen: Boolean = false,
    val positionKnown: Boolean = false,
    val waterHeight: Float = 20f,
    val capsReady: Boolean = false,
    val capsCount: Int = 0,
    val terrainReady: Boolean = false,
    val terrainMin: Float = 0f,
    val terrainMax: Float = 0f,
    val objectCount: Int = 0,
    val avatarCount: Int = 0,
    val objectsWithoutPosition: Int = 0,
    val eventCount: Long = 0L,
    /** Error owned by EventQueueGet; unrelated capability failures do not overwrite it. */
    val eventQueueError: String = "",
    val eventQueueFailures: Int = 0,
    /** Last capability-layer error for non-EventQueue diagnostics. */
    val capsError: String = "",
    val typingAgent: String = ""
)

/**
 * Owns the whole session: login handshake, UDP circuit, sim handshake, the chat
 * stream, avatar movement, the region contents (`WorldModel`) and the HTTP
 * capabilities used by the inventory and (later) the texture pipeline.
 *
 * Every method here is safe to call from the UI thread: anything that touches
 * the network is dispatched onto a background coroutine, which matters because
 * Android throws `NetworkOnMainThreadException` on a UDP send from the main
 * thread (the classic "chat does nothing" bug).
 */
class SLConnection(
    private val scope: CoroutineScope,
    private val templateReady: CompletableDeferred<Int>
) {

    private val circuit = Circuit(scope)
    private val loginModule = LoginModule()

    val capabilities = Capabilities()
    val world = WorldModel()

    /**
     * The texture provider the world view draws through (fase 2.13b).
     *
     * It is created lazily and lives with the session, not with the scene: the
     * capability is resolved once at login, and a scene that is torn down (a
     * content switch, a device rotation) must not throw away textures that are
     * already downloaded or in flight. Its workers are daemons and start on the
     * first request, so nothing is created before the grid has answered.
     *
     * The transport is the `GetTexture` capability; the JPEG2000 decode is the
     * scene's (see `TexturePipeline`), deliberately not here — this object knows
     * about the wire and nothing about images.
     */
    val textures: TextureAssetProvider by lazy {
        GetTextureAssetProvider(GetTextureTransport(capabilities))
    }

    /**
     * El proveedor de mallas de la sesión (fase 5).
     *
     * Vive con la sesión como el de texturas: la capability se resuelve una
     * vez en el login y una escena reconstruida no debe tirar assets ya
     * descargados. Solo wire (ViewerAsset/GetMesh + Range); el decode es del
     * pipeline de la escena.
     */
    val meshes: MeshAssetProvider by lazy {
        MeshAssetProvider(GetMeshTransport(capabilities))
    }

    private val stateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = stateFlow.asStateFlow()

    private val statusFlow = MutableStateFlow("Desconectado")
    val status: StateFlow<String> = statusFlow.asStateFlow()

    private val sessionFlow = MutableStateFlow(SessionInfo())
    val session: StateFlow<SessionInfo> = sessionFlow.asStateFlow()

    private val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = messagesFlow.asStateFlow()

    private val logFlow = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = logFlow.asStateFlow()

    /** Result of the last successful login, used by the inventory screen. */
    var lastLogin: LoginResult? = null
        private set

    private val ackedSequences = Collections.synchronizedSet(HashSet<Int>())
    private val sessionJobs = ArrayList<Job>()

    private var agentId = ""
    private var sessionId = ""
    private var circuitCode = 0
    private var nextMessageId = 1L
    private var chatChannel = 0

    @Volatile private var controlFlags = 0L
    @Volatile private var bodyYaw = 0f
    @Volatile private var viewYaw = 0f
    @Volatile private var stopPulse = false
    /**
     * NUDGE de un solo AgentUpdate (mecanismo gemelo de [stopPulse]): impulso
     * discreto para pulsaciones direccionales cortas, como el viewer funcional
     * (bits legacy del protocolo, no sistema paralelo). Se consume en el
     * siguiente [sendAgentUpdate].
     */
    @Volatile private var nudgePulse = 0L
    /** Inicio de la pulsación actual por acción (reloj monotónico). */
    private val pressStartMillis = HashMap<MoveAction, Long>()
    @Volatile private var coarseLogged = false
    @Volatile private var terrainLogged = false
    @Volatile private var pendingFullRequests = 0
    private val requestedFullIds = HashMap<Int, Long>()
    private val failedMessageNames = HashSet<String>()
    private var lastRequestLogMillis = 0L
    private var lastObjectLogCount = -1
    private var eventQueue: EventQueue? = null
    private val imSessions = HashMap<String, String>()
    private val unknownEventsLogged = HashSet<String>()
    private var instantMessagesRequested = false
    private var capsRetried = false

    val isFlying: Boolean
        get() = (controlFlags and AgentControlFlags.FLY) != 0L

    val isConnected: Boolean
        get() = stateFlow.value == ConnectionState.CONNECTED

    private fun setConnectionState(next: ConnectionState, reason: String) {
        val previous = stateFlow.value
        if (previous != next) {
            log("CONNECTION_STATE " + previous + " -> " + next +
                (if (reason.isEmpty()) "" else " · " + reason))
        }
        stateFlow.value = next
    }

    init {
        circuit.onSequenceAcked = { sequence -> ackedSequences.add(sequence) }
        scope.launch {
            circuit.messages.collect { message -> handleMessage(message) }
        }
        scope.launch {
            circuit.logs.collect { text -> log(text) }
        }
    }

    fun connect(grid: Grid, firstName: String, lastName: String, password: String) {
        val current = stateFlow.value
        if (current == ConnectionState.LOGGING_IN || current == ConnectionState.CONNECTING) {
            return
        }
        stopSession()
        messagesFlow.value = emptyList()

        sessionJobs.add(
            scope.launch {
                try {
                    runSession(grid, firstName, lastName, password)
                } catch (t: Throwable) {
                    setConnectionState(ConnectionState.ERROR, "exception: " + (t.message ?: t.javaClass.simpleName))
                    setStatus("Error: " + (t.message ?: t.javaClass.simpleName))
                    log("Excepcion en la sesion: " + t)
                }
            }
        )
    }

    private suspend fun runSession(
        grid: Grid,
        firstName: String,
        lastName: String,
        password: String
    ) {
        setConnectionState(ConnectionState.LOGGING_IN, "inicio de login")
        setStatus("Cargando plantillas de mensajes...")
        val templateCount = try {
            templateReady.await()
        } catch (t: Throwable) {
            -1
        }
        if (templateCount <= 0) {
            setConnectionState(ConnectionState.ERROR, "message_template.msg no disponible")
            setStatus("No se pudo cargar el message_template.msg")
            log("message_template.msg no disponible")
            return
        }
        log("Plantillas cargadas: " + templateCount + " mensajes")

        setStatus("Autenticando en " + grid.name + "...")
        log("Login -> " + grid.loginUri)
        val result = loginModule.login(grid.loginUri, firstName, lastName, password, true)
        if (!result.success) {
            setConnectionState(ConnectionState.ERROR, "login rechazado: " + result.reason)
            setStatus(result.message)
            log("Login fallido [" + result.reason + "]: " + result.message)
            return
        }

        agentId = result.agentId
        sessionId = result.sessionId
        circuitCode = result.circuitCode
        lastLogin = result
        // The login response carries the region's grid position (in 256 m
        // blocks); packing it that way gives Second Life's region handle.
        world.regionGridX = result.regionX
        world.regionGridY = result.regionY
        world.regionHandle = (result.regionX.toLong() shl 32) or (result.regionY.toLong() and 0xFFFFFFFFL)
        val displayName = (result.firstName + " " + result.lastName).trim()
        log("Agente: " + displayName + " (" + result.agentId + ")")
        log("Simulador: " + result.simIp + ":" + result.simPort + "  circuito=" + result.circuitCode)
        if (result.inventoryFolders.isNotEmpty()) {
            log("Inventario: " + result.inventoryFolders.size + " carpetas en el esqueleto")
        }

        sessionFlow.value = SessionInfo(
            agentName = displayName,
            agentId = result.agentId,
            gridName = grid.name,
            simIp = result.simIp,
            simPort = result.simPort,
            connectedSince = System.currentTimeMillis()
        )

        setStatus("Resolviendo capabilities (inventario, texturas)...")
        val capsOk = try {
            capabilities.resolve(result.seedCapability)
        } catch (t: Throwable) {
            false
        }
        if (capsOk) {
            log("Capabilities disponibles: " + capabilities.names().size + " endpoints")
            if (capabilities.url(InventoryClient.CAP_NAME) == null) {
                log("Aviso: esta simulacion no ofrece FetchInventoryDescendents2")
            }
        } else {
            log("Sin capabilities: " + capabilities.lastError)
        }

        setConnectionState(ConnectionState.CONNECTING, "resolucion de capabilities / apertura UDP")
        setStatus("Abriendo circuito UDP...")
        circuit.start(result.simIp, result.simPort)

        delay(120)
        val circuitSequence = sendUseCircuitCode()
        if (!awaitAck(circuitSequence, CIRCUIT_ACK_TIMEOUT_MILLIS)) {
            setConnectionState(ConnectionState.ERROR, "sin ACK de UseCircuitCode")
            setStatus("El simulador no respondio por UDP")
            log(
                "Sin ACK de UseCircuitCode tras " + (CIRCUIT_ACK_TIMEOUT_MILLIS / 1000) +
                    " s. Normalmente significa que la red bloquea UDP saliente (cortafuegos, " +
                    "NAT o datos moviles). Prueba con otra red Wi-Fi."
            )
            circuit.stop()
            return
        }
        log("Circuito confirmado por el simulador")

        sendCompleteAgentMovement()
        sendAgentThrottle()
        sendAgentDataUpdateRequest()

        setConnectionState(ConnectionState.CONNECTED, "login + circuito UDP confirmados")
        setStatus("Conectado a " + grid.name)
        appendMessage("System", "Conectado como " + displayName + ".", ChatSource.SYSTEM)
        sessionFlow.value = sessionFlow.value.copy(
            capsReady = capsOk,
            capsCount = capabilities.names().size
        )

        startPeriodicJobs()
    }

    private suspend fun awaitAck(sequence: Int, timeoutMillis: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMillis) {
            if (ackedSequences.contains(sequence)) {
                return true
            }
            delay(50)
            waited += 50
        }
        return ackedSequences.contains(sequence)
    }

    fun disconnect() {
        val stillConnected = circuit.isRunning && agentId.isNotEmpty()
        scope.launch(Dispatchers.IO) {
            if (stillConnected) {
                try {
                    sendLogoutRequest()
                    delay(150)
                } catch (t: Throwable) {
                    // ignore
                }
            }
            stopSession()
            setConnectionState(ConnectionState.DISCONNECTED, "logout explicito / stopSession")
            setStatus("Desconectado")
            log("Sesion finalizada")
        }
    }

    private fun stopSession() {
        for (job in sessionJobs) {
            job.cancel()
        }
        sessionJobs.clear()
        circuit.stop()
        eventQueue?.stop()
        eventQueue = null
        imSessions.clear()
        unknownEventsLogged.clear()
        instantMessagesRequested = false
        capsRetried = false
        lastObjectLogCount = -1
        ackedSequences.clear()
        controlFlags = 0L
        stopPulse = false
        bodyYaw = 0f
        viewYaw = 0f
        coarseLogged = false
        terrainLogged = false
        world.clear()
        world.terrain.clear()
        // The parser's counters and traces describe *this* session's region:
        // carrying them across a reconnect would mix two regions' evidence.
        ObjectUpdateDiagnostics.reset()
    }

    // ------------------------------------------------------------- chatting ---

    fun sendChat(text: String, chatType: Int = 1) {
        if (stateFlow.value != ConnectionState.CONNECTED) {
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val outbound = if (trimmed.length > MAX_CHAT_LENGTH) trimmed.substring(0, MAX_CHAT_LENGTH) else trimmed
        appendMessage(sessionFlow.value.agentName, outbound, ChatSource.LOCAL)
        scope.launch(Dispatchers.IO) {
            try {
                val def = MessageTemplate.byName("ChatFromViewer") ?: return@launch
                val message = SLMessage(def)
                message.block("AgentData").set("AgentID", agentId)
                message.block("AgentData").set("SessionID", sessionId)
                val chatData = message.block("ChatData")
                chatData.setString("Message", outbound)
                chatData.set("Type", chatType)
                chatData.set("Channel", chatChannel)
                circuit.send(message, true)
            } catch (t: Throwable) {
                log("Fallo al enviar ChatFromViewer: " + (t.message ?: t.javaClass.simpleName))
            }
        }
    }

    /**
     * Loads a folder's contents through the inventory capability (network). The
     * Library lives on another owner and has its own capability, so it needs
     * both its own root id and the grid's library owner id.
     */
    suspend fun loadFolder(folderId: String, library: Boolean = false): InventoryFolderContents? =
        withContext(Dispatchers.IO) {
            val login = lastLogin ?: return@withContext null
            if (!capabilities.isReady) {
                return@withContext null
            }
            val owner = if (library) login.libraryOwner.ifEmpty { login.agentId } else login.agentId
            InventoryClient.fetchFolder(capabilities, folderId, owner, library)
        }

    /**
     * Retries the capability handshake (and starts the event queue) when it
     * failed during login: the seed endpoint is hosted by the simulator itself
     * and answers badly if it is still finishing our connection, which is
     * exactly when the inventory and the instant messages go missing.
     */
    private fun ensureCapabilities() {
        val login = lastLogin ?: return
        if (!capabilities.isReady) {
            if (capsRetried) {
                return
            }
            capsRetried = true
            log("Reintentando las capabilities...")
            val ok = try {
                capabilities.resolve(login.seedCapability)
            } catch (t: Throwable) {
                false
            }
            if (ok) {
                log("Capabilities disponibles: " + capabilities.names().size + " endpoints")
            } else {
                log("Sin capabilities: " + capabilities.lastError)
            }
            sessionFlow.value = sessionFlow.value.copy(
                capsReady = capabilities.isReady,
                capsCount = capabilities.names().size
            )
        }
        startEventQueue()
        sendRetrieveInstantMessages()
    }

    private fun startEventQueue() {
        if (eventQueue?.running == true || !capabilities.isReady) {
            return
        }
        val queue = eventQueue ?: EventQueue(
            capabilities,
            scope,
            { text -> log(text) },
            { name, body -> onEvent(name, body) }
        )
        eventQueue = queue
        queue.start()
    }

    /**
     * Everything the simulator pushes over the event queue. Instant messages
     * are what the chat screen is for, so they are decoded into the same
     * message objects the UDP path produces.
     */
    private fun onEvent(name: String, body: Any?) {
        // An event body is the LLSD form of a normal message, so decode it into
        // the same object the UDP path produces and reuse those handlers
        // (instant messages, alerts, script dialogs, kicks...).
        val decoded = LLSDMessageDecoder.decode(name, body)
        if (decoded != null) {
            handleMessage(decoded)
        }
        if (unknownEventsLogged.add(name)) {
            log("Evento: " + name)
        }
        sessionFlow.value = sessionFlow.value.copy(
            eventCount = eventQueue?.eventCount ?: 0L
        )
    }

    // ------------------------------------------------------- instant messages ---

    /**
     * Handles an `ImprovedInstantMessage` (from the event queue or from UDP).
     * The `Dialog` field says what kind of message it is; the numeric values are
     * Linden Lab's `EInstantMessage`.
     */
    private fun handleInstantMessage(message: SLMessage) {
        val block = message.firstBlock("MessageBlock") ?: return
        val dialog = block.u8("Dialog")
        val text = block.string("Message")
        val fromName = block.string("FromAgentName").ifEmpty { "Alguien" }
        val senderId = message.firstBlock("AgentData")?.uuid("AgentID").orEmpty()
        val imSession = block.uuid("ID")
        val offline = block.u8("Offline") != 0

        when (dialog) {
            IM_TYPING_START -> {
                sessionFlow.value = sessionFlow.value.copy(typingAgent = fromName)
                return
            }
            IM_TYPING_STOP -> {
                sessionFlow.value = sessionFlow.value.copy(typingAgent = "")
                return
            }
        }
        if (text.isEmpty()) {
            log("Mensaje de " + fromName + " (tipo " + dialog + ")")
            return
        }
        when (dialog) {
            IM_NOTHING_SPECIAL, IM_SESSION_SEND, IM_FROM_TASK,
            IM_DO_NOT_DISTURB_AUTO_RESPONSE, IM_CONSOLE_AND_CHAT_HISTORY -> {
                val prefix = if (offline) "(sin conexion) " else ""
                appendMessage(fromName, prefix + text, ChatSource.IM, senderId, imSession)
            }
            IM_INVENTORY_OFFERED, IM_TASK_INVENTORY_OFFERED -> {
                appendMessage(fromName, "(ofrece un objeto) " + text, ChatSource.IM, senderId, imSession)
            }
            IM_FRIENDSHIP_OFFERED -> {
                appendMessage(fromName, "(quiere ser tu amigo) " + text, ChatSource.IM, senderId, imSession)
            }
            IM_GROUP_NOTICE -> {
                appendMessage(fromName, "(aviso de grupo) " + text, ChatSource.SYSTEM)
            }
            else -> {
                appendMessage(fromName, text, ChatSource.IM, senderId, imSession)
                log("IM tipo " + dialog + " de " + fromName)
            }
        }
    }

    /**
     * Sends an instant message. The grid wants the conversation session to exist
     * first (`ChatSessionRequest`, method "start session"), then the message
     * travels in a plain `ImprovedInstantMessage` packet with that session as
     * its `ID`.
     */
    fun sendInstantMessage(targetAgentId: String, targetName: String, text: String) {
        if (stateFlow.value != ConnectionState.CONNECTED) {
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty() || targetAgentId.isEmpty()) {
            return
        }
        appendMessage(targetName.ifEmpty { "IM" }, "-> " + trimmed, ChatSource.IM, targetAgentId, "")
        scope.launch(Dispatchers.IO) {
            try {
                val imSession = imSessions[targetAgentId] ?: startImSession(targetAgentId)
                val def = MessageTemplate.byName("ImprovedInstantMessage")
                    ?: throw IllegalStateException("sin plantilla de ImprovedInstantMessage")
                val message = SLMessage(def)
                val agentData = message.block("AgentData")
                agentData.set("AgentID", agentId)
                agentData.set("SessionID", sessionId)
                val block = message.block("MessageBlock")
                block.set("FromGroup", false)
                block.set("ToAgentID", targetAgentId)
                block.set("ParentEstateID", 0L)
                block.set("RegionID", world.regionId.ifEmpty { LLUUIDUtil.ZERO })
                block.set("Position", sessionFlow.value.position)
                block.set("Offline", 0)
                block.set("Dialog", IM_NOTHING_SPECIAL)
                block.set("ID", imSession)
                block.set("Timestamp", 0L)
                block.setString("FromAgentName", sessionFlow.value.agentName)
                block.setString("Message", trimmed)
                block.set("BinaryBucket", ByteArray(1))
                circuit.send(message, true)
            } catch (t: Throwable) {
                log("Fallo al enviar el mensaje instantaneo: " + (t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun startImSession(targetAgentId: String): String {
        val fallback = LLUUIDUtil.random()
        if (capabilities.url(CHAT_SESSION_CAP) == null) {
            imSessions[targetAgentId] = fallback
            return fallback
        }
        val params = LinkedHashMap<String, Any?>()
        params["agent_id"] = LLSDUuid(targetAgentId)
        val body = LinkedHashMap<String, Any?>()
        body["method"] = "start session"
        body["session_id"] = LLSDUuid(fallback)
        body["params"] = params
        val reply = capabilities.requestParsed(CHAT_SESSION_CAP, body)
        val session = LLSDParser.asString(LLSDParser.asMap(reply)["session_id"]).ifEmpty { fallback }
        imSessions[targetAgentId] = session
        return session
    }

    /**
     * `RetrieveInstantMessages` is how the grid flushes the messages that
     * arrived while we were offline: the simulator answers with a burst of
     * `ImprovedInstantMessage` packets.
     */
    private fun sendRetrieveInstantMessages() {
        if (instantMessagesRequested) {
            return
        }
        val def = MessageTemplate.byName("RetrieveInstantMessages") ?: return
        instantMessagesRequested = true
        try {
            val message = SLMessage(def)
            val block = message.block("AgentData")
            block.set("AgentID", agentId)
            block.set("SessionID", sessionId)
            circuit.send(message, true)
            log("Pidiendo los mensajes sin conexion")
        } catch (t: Throwable) {
            log("RetrieveInstantMessages: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    // ------------------------------------------------------------ movement ---

    /** The camera yaw in radians; the avatar turns to face it while moving. */
    fun setViewYaw(yawRadians: Float) {
        viewYaw = yawRadians
    }

    fun setMoveAction(action: MoveAction, pressed: Boolean) {
        MovementAudit.notePad(action.name, pressed)
        if (stateFlow.value != ConnectionState.CONNECTED) {
            MovementAudit.noteHandler(false, controlFlags, bodyYaw, viewYaw)
            return
        }
        val bit = AgentControlFlags.bitFor(action)
        val now = android.os.SystemClock.uptimeMillis()
        if (pressed) {
            pressStartMillis[action] = now
            if (action != MoveAction.FLY && action != MoveAction.TURN_LEFT && action != MoveAction.TURN_RIGHT) {
                // Walking follows the camera, exactly like the official viewer.
                bodyYaw = viewYaw
            }
        } else {
            val start = pressStartMillis.remove(action)
            // Pulsación direccional corta = impulso discreto (NUDGE), como el
            // viewer funcional: un toque de 60-130 ms con solo AT_POS apenas
            // integra velocidad en el simulador. El bit NUDGE viaja en el
            // AgentUpdate de la suelta, una sola vez (vía nudgePulse). Sin
            // inicio registrado (pulsación de antes de conectar) no hay NUDGE.
            if (start != null && now - start < NUDGE_MAX_HOLD_MILLIS) {
                val nudge = AgentControlFlags.nudgeBitFor(action)
                if (nudge != 0L) {
                    nudgePulse = nudgePulse or nudge
                    MovementAudit.noteNudgeEmitted(action.name, now - start)
                }
            }
        }
        controlFlags = if (pressed) controlFlags or bit else controlFlags and bit.inv()
        stopPulse = false
        if (action == MoveAction.FLY && pressed) {
            log(if (isFlying) "Vuelo activado" else "Vuelo desactivado")
            MovementAudit.noteFlyToggle(isFlying)
        }
        MovementAudit.noteHandler(true, controlFlags, bodyYaw, viewYaw)
        sendAgentUpdateAsync()
    }

    fun stopMovement() {
        MovementAudit.noteStopRequest()
        controlFlags = 0L
        stopPulse = true
        MovementAudit.noteState(controlFlags, bodyYaw, viewYaw)
        sendAgentUpdateAsync()
    }

    fun setFly(enabled: Boolean) {
        controlFlags = if (enabled) controlFlags or AgentControlFlags.FLY else controlFlags and AgentControlFlags.FLY.inv()
        log(if (enabled) "Vuelo activado" else "Vuelo desactivado")
        MovementAudit.noteFlyToggle(enabled)
        MovementAudit.noteState(controlFlags, bodyYaw, viewYaw)
        sendAgentUpdateAsync()
    }

    fun changeBodyYaw(deltaRadians: Float) {
        bodyYaw += deltaRadians
        MovementAudit.noteState(controlFlags, bodyYaw, viewYaw)
        sendAgentUpdateAsync()
    }

    private fun sendAgentUpdateAsync() {
        scope.launch(Dispatchers.IO) {
            try {
                sendAgentUpdate()
            } catch (t: Throwable) {
                val text = t.message ?: t.javaClass.simpleName
                MovementAudit.noteCommandError(text)
                log("AgentUpdate: " + text)
            }
        }
    }

    fun clearLog() {
        logFlow.value = emptyList()
    }

    fun addLogLine(text: String) {
        log(text)
    }

    // --------------------------------------------------------------- sends ---

    private fun sendUseCircuitCode(): Int {
        val def = MessageTemplate.byName("UseCircuitCode")
            ?: throw IllegalStateException("El message_template.msg no contiene UseCircuitCode")
        val message = SLMessage(def)
        val block = message.block("CircuitCode")
        block.set("Code", circuitCode.toLong() and 0xFFFFFFFFL)
        block.set("SessionID", sessionId)
        block.set("ID", agentId)
        val sequence = circuit.send(message, true)
        log("UseCircuitCode enviado (secuencia " + sequence + ")")
        return sequence
    }

    private fun sendCompleteAgentMovement() {
        val def = MessageTemplate.byName("CompleteAgentMovement") ?: return
        val message = SLMessage(def)
        val block = message.block("AgentData")
        block.set("AgentID", agentId)
        block.set("SessionID", sessionId)
        block.set("CircuitCode", circuitCode.toLong() and 0xFFFFFFFFL)
        circuit.send(message, true)
        log("CompleteAgentMovement enviado")
    }

    private fun sendAgentThrottle() {
        val def = MessageTemplate.byName("AgentThrottle") ?: return
        val message = SLMessage(def)
        val agentData = message.block("AgentData")
        agentData.set("AgentID", agentId)
        agentData.set("SessionID", sessionId)
        agentData.set("CircuitCode", circuitCode.toLong() and 0xFFFFFFFFL)
        val throttle = message.block("Throttle")
        throttle.set("GenCounter", 0L)
        throttle.set("Throttles", buildThrottle())
        circuit.send(message, true)
        log("AgentThrottle enviado")
    }

    private fun buildThrottle(): ByteArray {
        // Order: Resend, Land, Wind, Cloud, Task, Texture, Asset (bytes per second)
        val values = floatArrayOf(
            RESEND_THROTTLE,
            LAND_THROTTLE,
            WIND_THROTTLE,
            CLOUD_THROTTLE,
            TASK_THROTTLE,
            TEXTURE_THROTTLE,
            ASSET_THROTTLE
        )
        val out = ByteArray(values.size * 4)
        for (i in values.indices) {
            val bits = java.lang.Float.floatToIntBits(values[i])
            out[i * 4] = (bits and 0xFF).toByte()
            out[i * 4 + 1] = ((bits ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((bits ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((bits ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun sendAgentDataUpdateRequest() {
        val def = MessageTemplate.byName("AgentDataUpdateRequest") ?: return
        val message = SLMessage(def)
        val block = message.block("AgentData")
        block.set("AgentID", agentId)
        block.set("SessionID", sessionId)
        circuit.send(message, true)
    }

    /**
     * `ObjectUpdateCached` tells the viewer "you should already have this object";
     * since we have no object cache, we ask for the full record of every id.
     */
    private fun requestFullObjects(localIds: List<Int>) {
        val now = System.currentTimeMillis()
        val fresh = ArrayList<Int>(localIds.size)
        for (localId in localIds) {
            // The simulator repeats the cached list until it has seen a request
            // for it, so without this filter we would ask for the same 98 objects
            // forever (and fill the log doing it).
            if (world.get(localId)?.positionKnown == true) {
                continue
            }
            val lastAsked = requestedFullIds[localId]
            if (lastAsked != null && now - lastAsked < FULL_REQUEST_RETRY_MILLIS) {
                continue
            }
            fresh.add(localId)
        }
        if (fresh.isEmpty()) {
            return
        }
        var index = 0
        while (index < fresh.size) {
            val def = MessageTemplate.byName("RequestMultipleObjects") ?: return
            val message = SLMessage(def)
            val agentData = message.block("AgentData")
            agentData.set("AgentID", agentId)
            agentData.set("SessionID", sessionId)
            val chunk = REQUEST_CHUNK.coerceAtMost(fresh.size - index)
            for (i in 0 until chunk) {
                val block = message.addBlock("ObjectData")
                block.set("CacheMissType", 0)
                block.set("ID", fresh[index + i].toLong() and 0xFFFFFFFFL)
            }
            circuit.send(message, true)
            index += chunk
        }
        for (localId in fresh) {
            requestedFullIds[localId] = now
        }
        pendingFullRequests += fresh.size
        if (now - lastRequestLogMillis > REQUEST_LOG_INTERVAL_MILLIS) {
            lastRequestLogMillis = now
            log("Pedidos " + fresh.size + " objetos al simulador (no tenemos cache local)")
        }
        if (requestedFullIds.size > MAX_TRACKED_REQUESTS) {
            val cutoff = now - FULL_REQUEST_RETRY_MILLIS * 4
            val iterator = requestedFullIds.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value < cutoff) {
                    iterator.remove()
                }
            }
        }
    }

    private fun sendAgentUpdate() {
        val def = AgentUpdateBuilder.messageDef()
        if (def == null) {
            // No definition, no packet. Counted, because a silent `return` here
            // is exactly the failure mode the movement audit exists to expose.
            MovementAudit.noteTemplateMissing()
            return
        }
        var flags = controlFlags
        if (stopPulse) {
            flags = flags or AgentControlFlags.STOP
            stopPulse = false
        }
        if (nudgePulse != 0L) {
            flags = flags or (nudgePulse and AgentControlFlags.NUDGE_MASK)
            nudgePulse = 0L
        }
        world.agentHeadingRadians = bodyYaw
        val position = sessionFlow.value.position
        val cameraCenter = Vector3(position.x, position.y, position.z + CAMERA_HEIGHT_OFFSET)
        // The camera basis is finding M1 of MOVEMENT-AUDIT.md, and this is the
        // correction: the reference frame for the heading the camera is looking
        // at, `at = (cos h, sin h, 0)` with `left = up x at` — an orthonormal,
        // right-handed frame, which is what a working client sends. The degenerate
        // triple it replaces pointed the at-axis straight down, which is the one
        // unusual value in the packets of the run that moved the avatar 16 times,
        // all of it towards -Z.
        val basis = AgentUpdateBuilder.referenceBasis(viewYaw)
        MovementAudit.noteCommandBuilt()
        val message = AgentUpdateBuilder.build(
            def = def,
            agentId = agentId,
            sessionId = sessionId,
            bodyYawRadians = bodyYaw,
            cameraCenter = cameraCenter,
            basis = basis,
            far = AGENT_FAR,
            state = 0,
            controlFlags = flags,
            flags = 0
        )
        // What the audit reports is read back out of the built packet, not the
        // arguments above: "the basis we serialized" is a fact about the message,
        // and a report that echoes its own inputs could never contradict them.
        val serializedBasis = AgentUpdateBuilder.serializedBasis(message)
        val serializedFlags = AgentUpdateBuilder.serializedControlFlags(message)
        val serializedHeading = AgentUpdateBuilder.headingOf(AgentUpdateBuilder.serializedBodyRotation(message))
        val sequence = circuit.send(message, false)
        MovementAudit.noteCommandSent(
            sequence = sequence,
            controlFlags = serializedFlags,
            cameraCenter = AgentUpdateBuilder.serializedCameraCenter(message),
            basis = serializedBasis,
            bytes = AgentUpdateBuilder.BODY_BYTES,
            movementCompleteSeen = MovementAudit.movementCompleteCount > 0,
            bodyYawSent = serializedHeading,
            basisYaw = viewYaw
        )
    }

    private fun sendLogoutRequest() {
        val def = MessageTemplate.byName("LogoutRequest") ?: return
        val message = SLMessage(def)
        val block = message.block("AgentData")
        block.set("AgentID", agentId)
        block.set("SessionID", sessionId)
        circuit.send(message, true)
    }

    private fun sendRegionHandshakeReply() {
        val def = MessageTemplate.byName("RegionHandshakeReply") ?: return
        val message = SLMessage(def)
        val agentData = message.block("AgentData")
        agentData.set("AgentID", agentId)
        agentData.set("SessionID", sessionId)
        message.block("RegionInfo").set("Flags", 0L)
        circuit.send(message, true)
        log("RegionHandshakeReply enviado")
    }

    // ----------------------------------------------------------- receiving ---

    private fun handleMessage(message: SLMessage) {
        try {
            dispatchMessage(message)
        } catch (t: Throwable) {
            // A single unparseable packet must never take down the packet
            // handling coroutine: that used to kill chat, movement and the
            // world all at once, silently.
            val name = message.name()
            if (failedMessageNames.add(name)) {
                log("Error procesando " + name + ": " + (t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun dispatchMessage(message: SLMessage) {
        when (message.name()) {
            "RegionHandshake" -> handleRegionHandshake(message)
            "AgentMovementComplete" -> handleAgentMovementComplete(message)
            "AgentDataUpdate" -> {
                val data = message.firstBlock("AgentData")
                val first = data?.string("FirstName") ?: ""
                val last = data?.string("LastName") ?: ""
                val full = (first + " " + last).trim()
                if (full.isNotEmpty()) {
                    sessionFlow.value = sessionFlow.value.copy(agentName = full)
                    log("Nombre de agente: " + full)
                }
            }
            "ObjectUpdate" -> {
                val count = ObjectUpdateDecoder.applyFull(message, world)
                if (count > 0) {
                    world.noteFullUpdate()
                    adoptAgentObject()
                }
            }
            "ObjectUpdateCompressed" -> {
                val count = ObjectUpdateDecoder.applyCompressed(message, world)
                if (count > 0) {
                    world.noteCompressedUpdate()
                    adoptAgentObject()
                }
            }
            "ImprovedTerseObjectUpdate" -> {
                val count = ObjectUpdateDecoder.applyTerse(message, world)
                if (count > 0) {
                    world.noteTerseUpdate()
                    adoptAgentObject()
                }
            }
            "ObjectUpdateCached" -> {
                val ids = ObjectUpdateDecoder.cachedIds(message)
                if (ids.isNotEmpty()) {
                    requestFullObjects(ids)
                }
            }
            "KillObject" -> {
                for (id in ObjectUpdateDecoder.removedIds(message)) {
                    world.remove(id)
                }
            }
            "LayerData" -> handleLayerData(message)
            "CoarseLocationUpdate" -> handleCoarseLocations(message)
            "ChatFromSimulator" -> handleChat(message)
            "AlertMessage", "AgentAlertMessage" -> {
                val alert = message.firstBlock("AlertData") ?: return
                val text = alert.string("Message")
                if (text.isNotEmpty()) {
                    log("Alerta: " + text)
                    appendMessage("System", text, ChatSource.SYSTEM)
                }
            }
            "KickUser" -> {
                log("KickUser: " + message.describe())
                appendMessage("System", "Expulsado por el simulador.", ChatSource.SYSTEM)
            }
            "ImprovedInstantMessage" -> handleInstantMessage(message)
            "ScriptDialog" -> {
                val dialog = message.firstBlock("Data") ?: return
                val text = dialog.string("Message")
                val objectName = dialog.string("ObjectName")
                if (text.isNotEmpty()) {
                    appendMessage(objectName.ifEmpty { "Script" }, text, ChatSource.SYSTEM)
                    log("Dialogo de guion: " + text)
                }
            }
            "LogoutReply" -> {
                log("LogoutReply recibido")
            }
        }
    }

    private fun handleRegionHandshake(message: SLMessage) {
        val info = message.firstBlock("RegionInfo") ?: return
        val simName = info.string("SimName")
        val waterHeight = info.f32("WaterHeight")
        val flags = info.u32("RegionFlags")
        val detail0 = info.uuid("TerrainDetail0")
        val regionId = message.firstBlock("RegionInfo2")?.uuid("RegionID").orEmpty()
        world.regionName = simName
        if (regionId.isNotEmpty()) {
            world.regionId = regionId
        }
        world.waterHeight = if (waterHeight.isFinite() && waterHeight > -100f) waterHeight else 20f
        sessionFlow.value = sessionFlow.value.copy(
            regionName = simName,
            waterHeight = world.waterHeight
        )
        log(
            "RegionHandshake: " + simName + "  agua a " + world.waterHeight + " m" +
                "  flags=0x" + java.lang.Long.toHexString(flags) +
                "  terreno=" + detail0
        )
        appendMessage("System", "Region: " + simName, ChatSource.SYSTEM)
        sendRegionHandshakeReply()
    }

    private fun handleAgentMovementComplete(message: SLMessage) {
        val data = message.firstBlock("Data") ?: return
        val position = data.vector3("Position")
        sessionFlow.value = sessionFlow.value.copy(
            position = position,
            positionKnown = true
        )
        world.agentPosition = position
        world.agentPositionKnown = true
        MovementAudit.noteMovementComplete(position)
        log("Posicion inicial: " + position)
        // We are finally standing in the region, so this is the moment the
        // simulator will answer RetrieveInstantMessages.
        sendRetrieveInstantMessages()
    }

    /** Once our own avatar object shows up, follow its real position. */
    private fun adoptAgentObject() {
        if (world.agentLocalId < 0) {
            var found = -1
            world.forEach { sceneObject ->
                if (found < 0 && sceneObject.isAvatar && agentId.isNotEmpty() && sceneObject.uuid == agentId) {
                    found = sceneObject.localId
                }
            }
            if (found >= 0) {
                world.agentLocalId = found
                log("Avatar propio detectado: id local " + found)
            }
        }
        val local = world.agentLocalId
        if (local < 0) {
            return
        }
        val mine = world.get(local) ?: return
        if (mine.positionKnown && MovementAudit.movementCompleteCount > 0) {
            world.agentPosition = mine.position
            world.agentPositionKnown = true
            sessionFlow.value = sessionFlow.value.copy(position = mine.position, positionKnown = true)
            // The audit's link 4: our own object was seen, and here is where it
            // is. Whether this number ever changes is what separates "the
            // simulator is not moving us" from "nothing local follows us".
            MovementAudit.noteOwnObject(local, mine.position)
        }
    }

    private fun handleLayerData(message: SLMessage) {
        val blocks = message.blocks["LayerData"] ?: return
        for (block in blocks) {
            val data = block.bytes("Data")
            if (data.isEmpty()) {
                continue
            }
            if (world.terrain.applyLayerData(data)) {
                if (!terrainLogged) {
                    terrainLogged = true
                    log(
                        "Terreno recibido: " + world.terrain.patchCount + " parches, alturas " +
                            world.terrain.minHeight + " .. " + world.terrain.maxHeight + " m"
                    )
                }
                sessionFlow.value = sessionFlow.value.copy(
                    terrainReady = true,
                    terrainMin = world.terrain.minHeight,
                    terrainMax = world.terrain.maxHeight
                )
            } else if (!terrainLogged) {
                terrainLogged = true
                log("LayerData ignorado: " + world.terrain.lastError)
            }
        }
    }

    private fun handleChat(message: SLMessage) {
        val chat = message.firstBlock("ChatData") ?: return
        val from = chat.string("FromName")
        val text = chat.string("Message")
        val type = chat.u8("ChatType")
        if (text.isEmpty() || type == 3 || type == 4 || type == 5) {
            return
        }
        val prefix = when (type) {
            0 -> "(susurro) "
            2 -> "(grito) "
            else -> ""
        }
        val sender = if (from.isEmpty()) "Objeto" else from
        appendMessage(sender, prefix + text, ChatSource.LOCAL)
    }

    /**
     * The simulator broadcasts a coarse (4 m) position for every avatar in the
     * region; this is what powers the viewer mini-map. X and Y arrive as
     * region-local metres and Z as metres/4.
     */
    private fun handleCoarseLocations(message: SLMessage) {
        val blocks = message.blocks["Location"] ?: return
        if (blocks.isEmpty()) {
            return
        }
        val youRaw = message.firstBlock("Index")?.u16("You") ?: DEFAULT_YOU
        var you = if (youRaw > 32767) youRaw - 65536 else youRaw
        if (you < 0 || you >= blocks.size) {
            val ids = message.blocks["AgentData"]
            if (ids != null) {
                for (index in ids.indices) {
                    if (ids[index].uuid("AgentID") == agentId) {
                        you = index
                        break
                    }
                }
            }
        }
        val agents = ArrayList<Vector3>(blocks.size)
        for (block in blocks) {
            agents.add(
                Vector3(
                    block.u8("X").toFloat(),
                    block.u8("Y").toFloat(),
                    block.u8("Z").toFloat() * 4f
                )
            )
        }
        if (!coarseLogged) {
            coarseLogged = true
            log("Mini-mapa: " + agents.size + " avatares en la region")
        }
        sessionFlow.value = sessionFlow.value.copy(
            coarseAgents = agents,
            coarseYou = if (you in agents.indices) agents[you] else null,
            coarseSeen = true
        )
    }

    private fun startPeriodicJobs() {
        sessionJobs.add(
            scope.launch {
                var tick = 0
                while (true) {
                    delay(MOVEMENT_TICK_MILLIS)
                    tick += 1
                    val turning = (controlFlags and (AgentControlFlags.YAW_POS or AgentControlFlags.YAW_NEG)) != 0L
                    if (turning) {
                        val dt = MOVEMENT_TICK_MILLIS / 1000f
                        if ((controlFlags and AgentControlFlags.YAW_POS) != 0L) {
                            bodyYaw += TURN_RATE_RADIANS_PER_SECOND * dt
                        }
                        if ((controlFlags and AgentControlFlags.YAW_NEG) != 0L) {
                            bodyYaw -= TURN_RATE_RADIANS_PER_SECOND * dt
                        }
                    } else if ((controlFlags and (AgentControlFlags.AT_POS or AgentControlFlags.AT_NEG)) != 0L) {
                        // Avanzando sin girar con los botones: el cuerpo sigue
                        // a la cámara de forma continua (no solo en el instante
                        // de ACTION_DOWN), así la dirección efectiva de FORWARD
                        // es siempre la que el viewer pretende usar.
                        bodyYaw = viewYaw
                    }
                    MovementAudit.noteState(controlFlags, bodyYaw, viewYaw)
                    val active = controlFlags != 0L
                    if (!active && !stopPulse && tick % KEEPALIVE_TICKS != 0) {
                        continue
                    }
                    try {
                        sendAgentUpdate()
                    } catch (t: Throwable) {
                        val text = t.message ?: t.javaClass.simpleName
                        MovementAudit.noteCommandError(text)
                        log("AgentUpdate: " + text)
                    }
                }
            }
        )
        sessionJobs.add(
            scope.launch {
                while (true) {
                    delay(1000)
                    val current = sessionFlow.value
                    var heading = Math.toDegrees(bodyYaw.toDouble()).toFloat()
                    while (heading < 0f) {
                        heading += 360f
                    }
                    while (heading >= 360f) {
                        heading -= 360f
                    }
                    var avatars = 0
                    var missing = 0
                    world.forEach { sceneObject ->
                        if (sceneObject.isAvatar) {
                            avatars += 1
                        }
                        if (!sceneObject.positionKnown) {
                            missing += 1
                        }
                    }
                    sessionFlow.value = current.copy(
                        headingDegrees = heading,
                        flying = isFlying,
                        roundTripMillis = circuit.roundTripMillis,
                        bytesIn = circuit.bytesReceived,
                        bytesOut = circuit.bytesSent,
                        pendingAcks = circuit.pendingAckCount,
                        objectCount = world.size,
                        avatarCount = avatars,
                        objectsWithoutPosition = missing,
                        terrainReady = world.terrain.hasData,
                        waterHeight = world.waterHeight,
                        eventCount = eventQueue?.eventCount ?: 0L,
                        eventQueueError = eventQueue?.lastError ?: "",
                        eventQueueFailures = eventQueue?.consecutiveFailures ?: 0,
                        capsError = capabilities.lastError
                    )
                    if (world.size != lastObjectLogCount && world.size > 0) {
                        lastObjectLogCount = world.size
                        log(
                            "Mundo: " + world.size + " objetos, " + avatars + " avatares, " +
                                missing + " sin posicion"
                        )
                    }
                }
            }
        )
        sessionJobs.add(
            scope.launch {
                delay(CAPS_RETRY_MILLIS)
                ensureCapabilities()
            }
        )
    }

    private fun appendMessage(sender: String, text: String, source: ChatSource) {
        appendMessage(sender, text, source, "", "")
    }

    private fun appendMessage(
        sender: String,
        text: String,
        source: ChatSource,
        agentId: String,
        sessionId: String
    ) {
        val current = messagesFlow.value
        val updated = ArrayList<ChatMessage>(current.size + 1)
        updated.addAll(current)
        updated.add(
            ChatMessage(
                nextMessageId,
                sender,
                text,
                source,
                agentId = agentId,
                sessionId = sessionId
            )
        )
        nextMessageId += 1
        if (updated.size > 500) {
            updated.removeAt(0)
        }
        messagesFlow.value = updated
    }

    private fun setStatus(text: String) {
        statusFlow.value = text
    }

    private fun log(text: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val current = logFlow.value
        val updated = ArrayList<String>(current.size + 1)
        updated.addAll(current)
        updated.add(stamp + "  " + text)
        if (updated.size > 400) {
            updated.removeAt(0)
        }
        logFlow.value = updated
    }

    private companion object {
        const val MAX_CHAT_LENGTH = 1000
        const val CIRCUIT_ACK_TIMEOUT_MILLIS = 8000L
        const val DEFAULT_YOU = 0xFFFF
        const val REQUEST_CHUNK = 120
        const val FULL_REQUEST_RETRY_MILLIS = 10000L
        const val REQUEST_LOG_INTERVAL_MILLIS = 3000L
        const val MAX_TRACKED_REQUESTS = 4096
        const val CAMERA_HEIGHT_OFFSET = 1.5f
        /**
         * Pulsaciones direccionales más cortas que esto se cierran con un
         * impulso discreto (NUDGE) en el AgentUpdate de la suelta, como el
         * viewer funcional: un toque de ~100 ms con solo AT_POS apenas integra
         * velocidad en el simulador. Constante heurística de visor, visible en
         * la auditoría (nudges emitidos) para validarla en el dispositivo.
         */
        const val NUDGE_MAX_HOLD_MILLIS = 300L
        const val CHAT_SESSION_CAP = "ChatSessionRequest"
        const val CAPS_RETRY_MILLIS = 4000L

        // EInstantMessage (llinstantmessage.h): the Dialog field of an IM.
        const val IM_NOTHING_SPECIAL = 0
        const val IM_INVENTORY_OFFERED = 4
        const val IM_TASK_INVENTORY_OFFERED = 9
        const val IM_SESSION_SEND = 17
        const val IM_FROM_TASK = 19
        const val IM_DO_NOT_DISTURB_AUTO_RESPONSE = 20
        const val IM_CONSOLE_AND_CHAT_HISTORY = 21
        const val IM_GROUP_NOTICE = 32
        const val IM_FRIENDSHIP_OFFERED = 38
        const val IM_TYPING_START = 41
        const val IM_TYPING_STOP = 42

        const val MOVEMENT_TICK_MILLIS = 200L
        const val KEEPALIVE_TICKS = 20
        const val TURN_RATE_RADIANS_PER_SECOND = 1.6f

        /**
         * The draw distance the `AgentUpdate` declares. It is what the previous
         * revision sent: the movement audit records it, it does not tune it.
         */
        const val AGENT_FAR = 128f

        const val RESEND_THROTTLE = 10000f
        const val LAND_THROTTLE = 4000f
        const val WIND_THROTTLE = 0f
        const val CLOUD_THROTTLE = 0f
        const val TASK_THROTTLE = 20000f
        const val TEXTURE_THROTTLE = 20000f
        const val ASSET_THROTTLE = 50000f

        // The viewer's control bits live in `slproto.movement.AgentControlFlags`
        // (one table, asserted against the reference by the `moveaudit` check),
        // so there is exactly one place that decides what a control means.
    }
}
