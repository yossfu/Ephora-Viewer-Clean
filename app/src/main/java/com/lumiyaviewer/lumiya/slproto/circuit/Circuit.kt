package com.lumiyaviewer.lumiya.slproto.circuit

import com.lumiyaviewer.lumiya.slproto.messages.MessageTemplate
import com.lumiyaviewer.lumiya.slproto.messages.SLMessage
import com.lumiyaviewer.lumiya.slproto.messages.SLMessageCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * The Second Life UDP circuit: packet sequencing, reliable delivery with
 * resends, pending-ACK piggybacking, zerocoding and ping keep-alive.
 */
class Circuit(private val scope: CoroutineScope) {

    private class PendingOutgoing(
        val sequence: Int,
        val typeName: String,
        val base: ByteArray,
        var lastSentMillis: Long,
        var resendCount: Int
    )

    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var remotePort = 0

    private var receiveJob: Job? = null
    private var timerJob: Job? = null
    private var pingJob: Job? = null

    private val lock = Any()
    private val pendingAcks = ArrayDeque<Int>()
    private val needAck = LinkedHashMap<Int, PendingOutgoing>()

    private var nextSequence = 1
    private var pingId = 0
    private var lastPingId = -1
    private var lastPingSentMillis = 0L
    private var consecutiveSendFailures = 0
    private var lastSendFailureLogMillis = 0L

    private val messageFlow = MutableSharedFlow<SLMessage>(extraBufferCapacity = 1024)
    val messages: SharedFlow<SLMessage> = messageFlow.asSharedFlow()

    private val undecodableLogged = HashSet<String>()

    private val logFlow = MutableSharedFlow<String>(extraBufferCapacity = 1024)
    val logs: SharedFlow<String> = logFlow.asSharedFlow()

    var onSequenceAcked: ((Int) -> Unit)? = null

    var roundTripMillis: Int = 0
        private set

    var isRunning: Boolean = false
        private set

    var bytesSent: Long = 0L
        private set

    var bytesReceived: Long = 0L
        private set

    val pendingAckCount: Int
        get() = synchronized(lock) { needAck.size }

    fun start(host: String, hostPort: Int) {
        stop()
        val resolved = InetAddress.getByName(host)
        val newSocket = DatagramSocket()
        newSocket.soTimeout = 1000
        socket = newSocket
        address = resolved
        remotePort = hostPort
        nextSequence = 1
        pingId = 0
        lastPingId = -1
        bytesSent = 0
        bytesReceived = 0
        synchronized(lock) {
            pendingAcks.clear()
            needAck.clear()
        }
        isRunning = true
        log("Circuito UDP abierto hacia " + host + ":" + hostPort)
        receiveJob = scope.launch(Dispatchers.IO) { receiveLoop() }
        timerJob = scope.launch { timerLoop() }
        pingJob = scope.launch { pingLoop() }
    }

    fun stop() {
        isRunning = false
        receiveJob?.cancel()
        timerJob?.cancel()
        pingJob?.cancel()
        receiveJob = null
        timerJob = null
        pingJob = null
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
        socket = null
        address = null
    }

    fun send(message: SLMessage, reliable: Boolean): Int {
        val body = SLMessageCodec.encode(message)
        val sequence: Int
        synchronized(lock) {
            sequence = nextSequence
            nextSequence += 1
        }
        val base = SLPacketWriter.build(sequence, message.def, body, reliable)
        val pending = PendingOutgoing(sequence, message.def.name, base, 0L, 0)
        if (reliable) {
            synchronized(lock) { needAck[sequence] = pending }
        }
        transmit(pending)
        return sequence
    }

    fun oldestUnacked(): Int {
        synchronized(lock) {
            for (key in needAck.keys) {
                return key
            }
        }
        return 0
    }

    private fun transmit(pending: PendingOutgoing) {
        val sock = socket ?: return
        val destination = address ?: return

        var data = pending.base
        val acks = ArrayList<Int>()
        synchronized(lock) {
            val room = (MTU - data.size - 5) / 4
            while (acks.size < room && pendingAcks.isNotEmpty()) {
                acks.add(pendingAcks.removeFirst())
            }
        }
        if (acks.isNotEmpty()) {
            val merged = ByteArray(data.size + acks.size * 4 + 1)
            System.arraycopy(data, 0, merged, 0, data.size)
            var offset = data.size
            for (ack in acks) {
                Bytes.writeU32Be(merged, offset, ack)
                offset += 4
            }
            merged[offset] = acks.size.toByte()
            merged[0] = (merged[0].toInt() or PacketFlags.APPENDED_ACKS).toByte()
            data = merged
        }

        val onWire = if ((data[0].toInt() and PacketFlags.ZEROCODED) != 0) {
            ZeroCodec.encode(data, data.size)
        } else {
            data
        }

        pending.lastSentMillis = System.currentTimeMillis()
        try {
            sock.send(DatagramPacket(onWire, onWire.size, destination, remotePort))
            bytesSent += onWire.size
            consecutiveSendFailures = 0
        } catch (e: Exception) {
            consecutiveSendFailures += 1
            reportSendFailure(pending.typeName, e)
        }
    }

    /**
     * Android starts refusing `sendto` with EPERM when the process is not
     * allowed to use the network any more — the classic cause is the app being
     * backgrounded while the device restricts background data (battery saver,
     * data saver or a firewall app). Sending resumes by itself once the app is
     * in the foreground again, so this reports the situation instead of
     * pretending the packet was lost.
     */
    private fun reportSendFailure(typeName: String, error: Exception) {
        val message = error.message ?: error.javaClass.simpleName
        val now = System.currentTimeMillis()
        val blocked = message.contains("EPERM") || message.contains("Operation not permitted")
        if (consecutiveSendFailures > 1 && now - lastSendFailureLogMillis < SEND_FAILURE_LOG_INTERVAL_MILLIS) {
            return
        }
        lastSendFailureLogMillis = now
        if (blocked) {
            if (consecutiveSendFailures == 1) {
                log(
                    "El sistema bloqueo el envio de paquetes (" + message + "). Pasa Ephora Viewer a " +
                        "\"Sin restricciones\" en Ajustes > Bateria y manten la app en primer plano."
                )
            }
        } else {
            log("Fallo al enviar " + typeName + ": " + message)
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(8192)
        while (isRunning) {
            val sock = socket ?: return
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(datagram)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (isRunning) {
                    log("Circuito cerrado: " + e.message)
                }
                return
            }
            bytesReceived += datagram.length
            try {
                handleDatagram(datagram.data, datagram.length)
            } catch (e: Exception) {
                log("Error procesando paquete: " + e.message)
            }
        }
    }

    private fun handleDatagram(raw: ByteArray, length: Int) {
        val parsed = SLPacket.parse(raw, length) ?: run {
            log("Paquete UDP ilegible de " + length + " bytes")
            return
        }

        for (ack in parsed.acks) {
            val removed = synchronized(lock) { needAck.remove(ack) }
            if (removed != null) {
                onSequenceAcked?.invoke(ack)
            }
        }

        if (parsed.header.reliable) {
            synchronized(lock) { pendingAcks.add(parsed.header.sequence) }
        }

        val def = parsed.def
        if (def == null) {
            return
        }

        when (def.name) {
            "PacketAck" -> {
                val message = SLMessageCodec.decode(
                    def, parsed.data, parsed.bodyOffset, parsed.bodyLength
                ) ?: return
                val packets = message.blocks["Packets"] ?: return
                for (block in packets) {
                    val id = block.u32i("ID")
                    val removed = synchronized(lock) { needAck.remove(id) }
                    if (removed != null) {
                        onSequenceAcked?.invoke(id)
                    }
                }
            }
            "StartPingCheck" -> {
                val message = SLMessageCodec.decode(
                    def, parsed.data, parsed.bodyOffset, parsed.bodyLength
                ) ?: return
                val id = message.block("PingID").u8("PingID")
                val replyDef = MessageTemplate.byName("CompletePingCheck") ?: return
                val reply = SLMessage(replyDef)
                reply.block("PingID").set("PingID", id)
                send(reply, false)
            }
            "CompletePingCheck" -> {
                if (lastPingSentMillis > 0L) {
                    roundTripMillis = (System.currentTimeMillis() - lastPingSentMillis).toInt()
                }
            }
            else -> {
                if (INTERESTING.contains(def.name)) {
                    val message = SLMessageCodec.decode(
                        def,
                        parsed.data,
                        parsed.bodyOffset,
                        parsed.bodyLength,
                        LENIENT.contains(def.name)
                    )
                    if (message != null) {
                        messageFlow.tryEmit(message)
                    } else {
                        reportUndecodable(def.name, parsed.bodyLength)
                    }
                }
            }
        }
    }

    /**
     * A message we asked for but cannot read is the classic reason for a world
     * that stays empty: the generic codec drops it silently when a newer
     * simulator sends a field the template does not describe. Report it once
     * per message name, together with the field that broke.
     */
    private fun reportUndecodable(typeName: String, length: Int) {
        if (!undecodableLogged.add(typeName)) {
            return
        }
        val reason = SLMessageCodec.lastDecodeError
        log("No se pudo decodificar " + typeName + " (" + length + " bytes): " + reason)
    }

    private suspend fun timerLoop() {
        while (isRunning) {
            delay(TIMER_INTERVAL_MILLIS)
            try {
                flushAcks()
                resendUnacked()
            } catch (e: Exception) {
                log("Error en temporizador del circuito: " + e.message)
            }
        }
    }

    private suspend fun pingLoop() {
        while (isRunning) {
            delay(PING_INTERVAL_MILLIS)
            try {
                sendPing()
            } catch (e: Exception) {
                log("Error enviando ping: " + e.message)
            }
        }
    }

    private fun flushAcks() {
        val def = MessageTemplate.byName("PacketAck") ?: return
        val acks = ArrayList<Int>()
        synchronized(lock) {
            while (acks.size < 250 && pendingAcks.isNotEmpty()) {
                acks.add(pendingAcks.removeFirst())
            }
        }
        if (acks.isEmpty()) {
            return
        }
        val message = SLMessage(def)
        for (ack in acks) {
            message.addBlock("Packets").set("ID", ack.toLong())
        }
        send(message, false)
    }

    private fun resendUnacked() {
        val now = System.currentTimeMillis()
        val candidates = ArrayList<PendingOutgoing>()
        synchronized(lock) {
            for (pending in needAck.values) {
                if (pending.lastSentMillis != 0L && now - pending.lastSentMillis > RESEND_TIMEOUT_MILLIS) {
                    candidates.add(pending)
                }
            }
        }
        for (pending in candidates) {
            if (pending.resendCount >= MAX_RESEND_COUNT) {
                synchronized(lock) { needAck.remove(pending.sequence) }
                log("Descartado " + pending.typeName + " #" + pending.sequence + " sin ACK")
                continue
            }
            pending.resendCount += 1
            pending.base[0] = (pending.base[0].toInt() or PacketFlags.RESENT).toByte()
            pending.lastSentMillis = 0L
            transmit(pending)
        }
    }

    private fun sendPing() {
        val def = MessageTemplate.byName("StartPingCheck") ?: return
        val message = SLMessage(def)
        val id = pingId and 0xFF
        pingId = (pingId + 1) and 0xFF
        val block = message.block("PingID")
        block.set("PingID", id)
        block.set("OldestUnacked", oldestUnacked().toLong() and 0xFFFFFFFFL)
        lastPingId = id
        lastPingSentMillis = System.currentTimeMillis()
        send(message, false)
    }

    private fun log(text: String) {
        logFlow.tryEmit(text)
    }

    companion object {
        private const val MTU = 1200
        private const val TIMER_INTERVAL_MILLIS = 400L
        private const val PING_INTERVAL_MILLIS = 3000L
        private const val RESEND_TIMEOUT_MILLIS = 1200L
        private const val MAX_RESEND_COUNT = 6
        private const val SEND_FAILURE_LOG_INTERVAL_MILLIS = 8000L

        private val INTERESTING = setOf(
            "ChatFromSimulator",
            "RegionHandshake",
            "AgentMovementComplete",
            "AgentDataUpdate",
            "CoarseLocationUpdate",
            "LogoutReply",
            "AlertMessage",
            "AgentAlertMessage",
            "KickUser",
            "ImprovedInstantMessage",
            "ScriptDialog",
            // Region contents
            "ObjectUpdate",
            "ObjectUpdateCompressed",
            "ObjectUpdateCached",
            "ImprovedTerseObjectUpdate",
            "KillObject",
            "LayerData"
        )

        /**
         * Messages whose trailing blocks vary between simulator versions, or
         * whose body is big enough that a single bad object should not throw
         * away the whole packet.
         */
        private val LENIENT = setOf(
            "CoarseLocationUpdate",
            "ObjectUpdate",
            "ObjectUpdateCompressed",
            "ImprovedTerseObjectUpdate",
            "LayerData"
        )
    }
}
