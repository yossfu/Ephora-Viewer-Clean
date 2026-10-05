package com.lumiyaviewer.lumiya.slproto.movement

import com.lumiyaviewer.lumiya.slproto.base.Vector3
import kotlin.math.abs

/**
 * Counts what happens at each link of the movement chain, and says where it cuts.
 *
 * This is an **audit**, not a feature: it exists because the movement pad is on
 * screen and pressing it does not move the avatar, and "the controls do nothing"
 * has five very different causes that all look the same from the sofa. Each link
 * gets its own counters, so one device run can tell them apart:
 *
 * | link | what it counts | the failure it names |
 * |------|----------------|----------------------|
 * | 1 input | pad presses that reached the engine | **A** the input never arrives |
 * | 2 handler | accepted/rejected calls, the control flags they left behind | **B** the engine never builds a command |
 * | 3 command | `AgentUpdate` builds, sends, send errors, the bytes sent | **C** the command is built but not sent |
 * | 4 simulator | our own object's updates and whether its position ever changes | **D** the simulator does not update us |
 * | 5 local | the camera focus following that position | **E** the position changes and nothing reflects it |
 *
 * The counters are plain `@Volatile` numbers, written from the session's
 * coroutines and the render thread and read by the report, exactly like
 * `ObjectUpdateDiagnostics`. The compound state (the previous position, the
 * previous focus) is guarded by [lock], so the deltas are consistent.
 *
 * Nothing here changes what is sent: [verdict] only reads.
 */
object MovementAudit {

    private val lock = Any()

    // ------------------------------------------------------------- link 1 ---
    // The pad. A press here means the touch event reached the engine, which is
    // exactly what "the input does not arrive" (case A) denies.

    @Volatile var padPresses = 0
    @Volatile var padReleases = 0

    @Volatile var lastAction = "-"

    // ------------------------------------------------------------- link 2 ---
    // The handler and the movement state it leaves behind.

    @Volatile var handlerCalls = 0
    @Volatile var handlerRejectedNotConnected = 0
    @Volatile var stopRequests = 0
    @Volatile var flyToggles = 0
    @Volatile var flagsNow = 0L
    @Volatile var bodyYawNow = 0f
    @Volatile var viewYawNow = 0f

    // ------------------------------------------------------------- link 3 ---
    // The command. `built` counts the attempts, `sent` the ones the circuit
    // accepted, `sendErrors` the ones it threw away, `templateMissing` the ones
    // that never had a message definition to encode.

    @Volatile var updateBuilt = 0
    @Volatile var updateSent = 0
    @Volatile var updateSendErrors = 0
    @Volatile var templateMissing = 0
    @Volatile var lastSendError = ""
    @Volatile var lastSequence = 0
    @Volatile var lastFlagsSent = 0L
    @Volatile var lastCameraCenter = "-"
    @Volatile var lastBasis = "-"
    /** The heading read back out of the serialized `BodyRotation`. */
    @Volatile var lastBodyYawSent = 0f
    /**
     * The heading the camera basis in the packet was built from (the view yaw),
     * kept apart from [lastBodyYawSent]: the body faces where the viewer walks,
     * the camera basis faces where the camera looks, and they are only equal
     * while a walk button is held. Comparing the two would print a false
     * "NO COINCIDE" as soon as the user turns with the yaw buttons.
     */
    @Volatile var lastBasisYaw = 0f
    private var lastBasisValue: AgentUpdateBuilder.CameraBasis? = null
    @Volatile var lastSentBytes = 0

    /** Commands sent before `AgentMovementComplete`: the sim asks us not to. */
    @Volatile var sentBeforeMovementComplete = 0

    // ------------------------------------------------ link 3b: the FORWARD window ---
    // Everything above answers "did a packet leave, and what did it carry"; the
    // counters below answer the question the *last* packet cannot: "while FORWARD
    // was physically held, did the packets really carry FORWARD?". The last packet
    // of a run is the release (flags 0), so a report that printed only the last
    // flags would show 0x00000000 even if every held packet carried 0x00000001 —
    // which is exactly the ambiguity this block removes.
    //
    // One window per press: the counters describe the *most recent* press, the
    // totals count every press, and both are kept because a single short tap and a
    // long hold are different evidence.

    /** Presses of FORWARD seen (a window opens on each). */
    @Volatile var forwardWindows = 0
    /** True while FORWARD is physically held. */
    @Volatile var forwardWindowOpen = false
    /** `AgentUpdate` serialized while the window was open. */
    @Volatile var forwardWindowUpdates = 0
    /** Of those, how many carried `ControlFlags & AT_POS != 0`. */
    @Volatile var forwardWindowFlagged = 0
    /** Of those, how many carried no AT_POS at all. */
    @Volatile var forwardWindowUnflagged = 0
    @Volatile var forwardWindowFirstSequence = -1
    @Volatile var forwardWindowFirstFlags = -1L
    @Volatile var forwardWindowFirstPosition = "-"
    @Volatile var forwardWindowLastSequence = -1
    @Volatile var forwardWindowLastFlags = -1L
    /** Every distinct serialized `ControlFlags` seen with AT_POS set, in order. */
    @Volatile var forwardWindowFlagsSeen = ""
    /** The position before the first FORWARD=1 packet of the window. */
    @Volatile var forwardWindowPositionBefore = "-"
    /** The last position seen while the window was open. */
    @Volatile var forwardWindowPositionLast = "-"
    /** The first position seen *after* the release. */
    @Volatile var forwardWindowPositionAfter = "-"
    @Volatile var forwardWindowDeltas = 0
    @Volatile var forwardWindowPathMetres = 0f
    /** Net displacement from where the avatar stood when FORWARD went down. */
    @Volatile var forwardWindowNetMetres = 0f
    @Volatile var forwardWindowFirstDelta = "-"
    @Volatile var forwardWindowLastDelta = "-"
    /** The window's deltas in order, capped: enough to see a direction, short enough to print. */
    @Volatile var forwardWindowDeltaList = "-"
    /** Packets whose serialized flags carried any NUDGE bit (this viewer sets none). */
    @Volatile var nudgeUpdates = 0
    /** Short directional releases closed with a NUDGE pulse (PROD3). */
    @Volatile var nudgeEmitted = 0
    @Volatile var nudgeEmittedLast = "-"

    private var forwardWindowStart: Vector3? = null
    private var forwardWindowPrevious: Vector3? = null
    private var forwardWindowAwaitAfter = false
    private var forwardWindowFlagValues = LinkedHashSet<Long>()
    private val forwardWindowDeltasSeen = ArrayList<String>()

    // ------------------------- traza temporal (revision 2.13b-rev3) ---
    // DIAGNOSTICO TEMPORAL. La prueba de 2.13b-rev2 respondio la pregunta de
    // objetivo A (AT_POS llega al cable) pero dejo dos sin responder: por que 50
    // pulsaciones producen solo dos paquetes con el flag, y por que el avatar no
    // se mueve dentro de la ventana. Esta traza entrelaza en una sola linea de
    // tiempo las pulsaciones, el estado interno del motor, los paquetes enviados
    // y los cambios de posicion, para que la proxima prueba lo diga en vez de
    // deducirlo. Se borra (junto con las lineas del informe que la imprimen)
    // cuando la causa este localizada.

    /** Los ocho eventos de la traza, en orden de tiempo, con su t en ms. */
    private var traceBaseMs = 0L
    private val trace = ArrayList<String>()
    /** Resumen de las ultimas ventanas, una linea cada una. */
    private val windowLog = ArrayList<String>()
    /** Cada cambio de posicion con la secuencia del ultimo paquete enviado. */
    private val movesWithSequence = ArrayList<String>()

    private var windowUpdates = 0
    private var windowFlagged = 0
    private var windowUnflagged = 0
    private var windowOpenedMs = 0L
    private var lastForwardEventMs = 0L
    private var lastFlaggedSendMs = 0L
    private var lastWindowFlaggedMs = 0L
    private val flaggedIntervalsMs = ArrayList<Long>()
    private val allFlaggedIntervalsMs = ArrayList<Long>()
    private val lateSendList = ArrayList<String>()

    @Volatile var forwardTotalUpdates = 0
    @Volatile var forwardTotalFlagged = 0
    @Volatile var forwardTotalUnflagged = 0
    @Volatile var forwardWindowsWithoutFlag = 0
    @Volatile var forwardWindowDurationMs = 0L
    @Volatile var forwardHeldTotalMs = 0L
    @Volatile var forwardLateSends = 0
    @Volatile var forwardLateFlagged = 0
    @Volatile var forwardLateSendList = "-"
    @Volatile var forwardFlaggedFirstMs = 0L
    @Volatile var forwardFlaggedLastMs = 0L
    @Volatile var forwardFlaggedIntervals = "-"
    @Volatile var forwardStateSamples = 0
    @Volatile var forwardStateWithFlag = 0
    @Volatile var forwardStateOpenSamples = 0
    @Volatile var forwardStateOpenWithFlag = 0
    @Volatile var forwardTrace = "-"
    @Volatile var forwardWindowLog = "-"
    @Volatile var forwardMovesWithSequence = "-"

    /** The latest position the simulator gave our own object. */
    private var ownLatest: Vector3? = null

    // ------------------------------------------------------------- link 4 ---
    // The simulator's answer: our own object, and whether it ever moves.

    @Volatile var movementCompleteCount = 0
    @Volatile var ownLocalId = -1
    @Volatile var ownAdoptSamples = 0
    @Volatile var ownPositionChanges = 0
    @Volatile var ownLastPosition = "-"
    /** The position of the previous sample, for the before/after pair. */
    @Volatile var ownPreviousPosition = "-"
    @Volatile var ownFirstDelta = "-"
    @Volatile var ownPathMetres = 0f
    @Volatile var ownDistanceFromStart = 0f

    private var ownStart: Vector3? = null
    private var ownPrevious: Vector3? = null

    // ------------------------------------------------------------- link 5 ---
    // Whether anything local follows that position.

    @Volatile var focusUpdates = 0
    @Volatile var focusChanges = 0
    @Volatile var focusLast = "-"

    private var lastFocus: Vector3? = null

    // ----------------------------------------------------------- recording ---

    /** Link 1: a press or a release reached the engine. */
    fun notePad(action: String, pressed: Boolean) {
        lastAction = action + (if (pressed) " pulsado" else " soltado")
        if (pressed) {
            padPresses += 1
        } else {
            padReleases += 1
        }
        if (action == FORWARD_ACTION) {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                lastForwardEventMs = now
                traceAdd(now, if (pressed) "P pulsado" else "S soltado")
            }
            if (pressed) {
                openForwardWindow()
            } else {
                closeForwardWindow()
            }
        }
    }

    /** Link 3b: FORWARD went down; the window starts now, at the position we know. */
    private fun openForwardWindow() {
        synchronized(lock) {
            forwardWindows += 1
            forwardWindowOpen = true
            forwardWindowUpdates = 0
            forwardWindowFlagged = 0
            forwardWindowUnflagged = 0
            forwardWindowFirstSequence = -1
            forwardWindowFirstFlags = -1L
            forwardWindowFirstPosition = "-"
            forwardWindowLastSequence = -1
            forwardWindowLastFlags = -1L
            forwardWindowFlagsSeen = ""
            forwardWindowFlagValues.clear()
            forwardWindowDeltas = 0
            forwardWindowPathMetres = 0f
            forwardWindowNetMetres = 0f
            forwardWindowFirstDelta = "-"
            forwardWindowLastDelta = "-"
            forwardWindowDeltaList = "-"
            forwardWindowDeltasSeen.clear()
            forwardWindowAwaitAfter = false
            val current = ownLatest
            forwardWindowStart = current
            forwardWindowPrevious = current
            forwardWindowPositionBefore = prettyOrDash(current)
            forwardWindowPositionLast = forwardWindowPositionBefore
            // Traza temporal: la ventana empieza aqui.
            windowUpdates = 0
            windowFlagged = 0
            windowUnflagged = 0
            windowOpenedMs = System.currentTimeMillis()
            lastWindowFlaggedMs = 0L
            flaggedIntervalsMs.clear()
        }
    }

    /** Link 3b: FORWARD went up; the window closes and the next sample is the "after". */
    private fun closeForwardWindow() {
        synchronized(lock) {
            forwardWindowOpen = false
            forwardWindowAwaitAfter = true
            forwardWindowPositionAfter = prettyOrDash(ownLatest)
            // Traza temporal: cuanto duro la ventana, y como salio.
            val now = System.currentTimeMillis()
            val duration = if (windowOpenedMs == 0L) 0L else now - windowOpenedMs
            forwardWindowDurationMs = duration
            forwardHeldTotalMs += duration
            if (windowFlagged == 0) {
                forwardWindowsWithoutFlag += 1
            }
            val intervals = if (flaggedIntervalsMs.isEmpty()) {
                "-"
            } else {
                flaggedIntervalsMs.joinToString(", ")
            }
            windowSummaryAdd(
                "#" + forwardWindows + "  dur " + duration + " ms" +
                    "  ·  paquetes con la tecla pulsada " + windowUpdates +
                    " (con AT_POS " + windowFlagged + "  ·  sin AT_POS " + windowUnflagged + ")" +
                    "  ·  intervalos entre AT_POS (" + intervals + ") ms"
            )
            traceAdd(now, "ventana #" + forwardWindows + " cerrada tras " + duration + " ms, " +
                windowFlagged + " con AT_POS")
        }
    }

    /** Link 2: the handler ran, and whether it was willing to act. */
    fun noteHandler(accepted: Boolean, controlFlags: Long, bodyYaw: Float, viewYaw: Float) {
        handlerCalls += 1
        if (!accepted) {
            handlerRejectedNotConnected += 1
        }
        flagsNow = controlFlags
        bodyYawNow = bodyYaw
        viewYawNow = viewYaw
        if (accepted) {
            sampleInternalFlag(System.currentTimeMillis(), controlFlags)
        }
    }

    /** Link 2: the movement state as it stands, without a handler call. */
    fun noteState(controlFlags: Long, bodyYaw: Float, viewYaw: Float) {
        flagsNow = controlFlags
        bodyYawNow = bodyYaw
        viewYawNow = viewYaw
        sampleInternalFlag(System.currentTimeMillis(), controlFlags)
    }

    fun noteStopRequest() {
        stopRequests += 1
    }

    /** Link 2: a short release emitted a one-shot NUDGE pulse. */
    fun noteNudgeEmitted(action: String, heldMillis: Long) {
        nudgeEmitted += 1
        nudgeEmittedLast = action + " tras " + heldMillis + " ms"
    }

    fun noteFlyToggle(flying: Boolean) {
        flyToggles += 1
        lastAction = if (flying) "FLY activado" else "FLY desactivado"
    }

    /** Link 3: an attempt to build the command. */
    fun noteCommandBuilt() {
        updateBuilt += 1
    }

    /** Link 3: the message definition was not in the template at all. */
    fun noteTemplateMissing() {
        templateMissing += 1
    }

    /** Link 3: the circuit took the packet. */
    fun noteCommandSent(
        sequence: Int,
        controlFlags: Long,
        cameraCenter: Vector3,
        basis: AgentUpdateBuilder.CameraBasis,
        bytes: Int,
        movementCompleteSeen: Boolean,
        bodyYawSent: Float = 0f,
        basisYaw: Float = 0f
    ) {
        updateSent += 1
        lastSequence = sequence
        lastFlagsSent = controlFlags
        lastCameraCenter = AgentUpdateBuilder.pretty(cameraCenter)
        lastBasis = basis.describe()
        lastBodyYawSent = bodyYawSent
        lastBasisYaw = basisYaw
        lastBasisValue = basis
        lastSentBytes = bytes
        if (!movementCompleteSeen) {
            sentBeforeMovementComplete += 1
        }
        if (AgentControlFlags.hasNudge(controlFlags)) {
            nudgeUpdates += 1
        }
        // The window bookkeeping: this is the packet that answers "was FORWARD in
        // the word while the button was down", which the last packet of the run
        // (the release) can never answer.
        val hasForward = (controlFlags and AgentControlFlags.AT_POS) != 0L
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // Los intervalos entre paquetes con AT_POS se miden *sin importar* si
            // el envio salio dentro o despues de la ventana: si el caso del
            // dispositivo es el segundo, dentro de la ventana no habria ninguno y
            // una lista solo-de-ventana saldria vacia justo cuando mas importa.
            if (hasForward) {
                if (lastFlaggedSendMs != 0L && allFlaggedIntervalsMs.size < 8) {
                    allFlaggedIntervalsMs.add(now - lastFlaggedSendMs)
                    forwardFlaggedIntervals = allFlaggedIntervalsMs.joinToString(", ")
                }
                lastFlaggedSendMs = now
                if (forwardFlaggedFirstMs == 0L) {
                    forwardFlaggedFirstMs = now
                }
                forwardFlaggedLastMs = now
            }
            if (forwardWindowOpen) {
                forwardWindowUpdates += 1
                windowUpdates += 1
                forwardTotalUpdates += 1
                if (hasForward) {
                    forwardWindowFlagged += 1
                    windowFlagged += 1
                    forwardTotalFlagged += 1
                    if (forwardWindowFirstSequence < 0) {
                        forwardWindowFirstSequence = sequence
                        forwardWindowFirstFlags = controlFlags
                        forwardWindowFirstPosition = prettyOrDash(ownLatest)
                    }
                    forwardWindowLastSequence = sequence
                    forwardWindowLastFlags = controlFlags
                    if (forwardWindowFlagValues.size < MAX_DISTINCT_WINDOW_FLAGS && forwardWindowFlagValues.add(controlFlags)) {
                        forwardWindowFlagsSeen = forwardWindowFlagValues.joinToString(", ") { fmtFlags(it) }
                    }
                    if (lastWindowFlaggedMs != 0L) {
                        flaggedIntervalsMs.add(now - lastWindowFlaggedMs)
                    }
                    lastWindowFlaggedMs = now
                } else {
                    forwardWindowUnflagged += 1
                    windowUnflagged += 1
                    forwardTotalUnflagged += 1
                }
                traceAdd(
                    now,
                    (if (hasForward) "E+ AT_POS" else "E- sin AT_POS") + " seq " + sequence +
                        " flag " + fmtFlags(controlFlags) + " (con la tecla pulsada)"
                )
            } else if (nearForwardEvent(now)) {
                // El envio salio DESPUES de soltar: es el caso que 2.13b-rev2 no
                // podia ver, y el candidato directo a explicar "50 pulsaciones,
                // dos paquetes con AT_POS".
                forwardLateSends += 1
                if (hasForward) {
                    forwardLateFlagged += 1
                }
                val delay = now - lastForwardEventMs
                if (lateSendList.size < 8) {
                    lateSendList.add("+" + delay + " ms " + (if (hasForward) "AT_POS" else "sin"))
                    forwardLateSendList = lateSendList.joinToString("  ·  ")
                }
                traceAdd(
                    now,
                    (if (hasForward) "E+ AT_POS" else "E- sin AT_POS") + " seq " + sequence +
                        " flag " + fmtFlags(controlFlags) + " (TARDE, " +
                        delay + " ms tras el evento)"
                )
            }
        }
    }

    /** Link 3: the send threw. */
    fun noteCommandError(message: String) {
        updateSendErrors += 1
        lastSendError = message
    }

    /** Link 4: the simulator said where we are allowed to start. */
    fun noteMovementComplete(position: Vector3) {
        movementCompleteCount += 1
        ownLastPosition = AgentUpdateBuilder.pretty(position)
        synchronized(lock) {
            ownStart = position
            ownPrevious = null
            ownLatest = position
        }
    }

    /**
     * Link 4: our own object was seen while adopting it, with the position the
     * region last gave it. Called once per object-update batch, so
     * `ownPositionChanges == 0` over many samples means the simulator is not
     * moving the body however many commands it received.
     */
    fun noteOwnObject(localId: Int, position: Vector3) {
        ownLocalId = localId
        ownAdoptSamples += 1
        ownLastPosition = AgentUpdateBuilder.pretty(position)
        synchronized(lock) {
            ownLatest = position
            val start = ownStart
            if (start != null) {
                ownDistanceFromStart = position.distanceTo(start)
            }
            val previous = ownPrevious
            if (previous != null) {
                ownPreviousPosition = AgentUpdateBuilder.pretty(previous)
                val moved = position.distanceTo(previous)
                if (moved > 0.01f) {
                    ownPositionChanges += 1
                    ownPathMetres += moved
                    if (ownFirstDelta == "-") {
                        ownFirstDelta = deltaOf(previous, position)
                    }
                    // Traza temporal: cada cambio de posicion queda atado a la
                    // secuencia del ultimo paquete enviado antes de verlo, que es
                    // lo que permite decir "este movimiento lo causo aquel envio".
                    val entry = deltaOf(previous, position) + " tras seq " + lastSequence
                    if (movesWithSequence.size < MOVES_LIMIT) {
                        movesWithSequence.add(entry)
                        forwardMovesWithSequence = movesWithSequence.joinToString(" · ")
                    }
                    traceAdd(System.currentTimeMillis(), "M " + entry)
                    noteForwardWindowMove(position, moved)
                }
            }
            ownPrevious = position
            if (forwardWindowOpen) {
                forwardWindowPositionLast = AgentUpdateBuilder.pretty(position)
            }
            if (forwardWindowAwaitAfter) {
                // The first sample after the release is the "despues de soltar"
                // the report wants, and it is a real simulator position rather
                // than the last one seen while the button was down.
                forwardWindowPositionAfter = AgentUpdateBuilder.pretty(position)
                forwardWindowAwaitAfter = false
            }
        }
    }

    /** Link 3b: a position change that happened inside the FORWARD window. */
    private fun noteForwardWindowMove(position: Vector3, moved: Float) {
        val previous = forwardWindowPrevious ?: position
        forwardWindowPrevious = position
        forwardWindowPositionLast = AgentUpdateBuilder.pretty(position)
        forwardWindowDeltas += 1
        forwardWindowPathMetres += moved
        forwardWindowStart?.let { forwardWindowNetMetres = position.distanceTo(it) }
        val delta = deltaOf(previous, position)
        if (forwardWindowFirstDelta == "-") {
            forwardWindowFirstDelta = delta
        }
        forwardWindowLastDelta = delta
        if (forwardWindowDeltasSeen.size < MAX_LISTED_WINDOW_DELTAS) {
            forwardWindowDeltasSeen.add(delta)
            forwardWindowDeltaList = forwardWindowDeltasSeen.joinToString(" ")
        }
    }

    private fun deltaOf(from: Vector3, to: Vector3): String =
        "(" + AgentUpdateBuilder.fmt(to.x - from.x) + ", " + AgentUpdateBuilder.fmt(to.y - from.y) +
            ", " + AgentUpdateBuilder.fmt(to.z - from.z) + ")"

    /** A wall-clock stamp as milliseconds since the trace's first entry. */
    private fun relMs(value: Long): Long = if (value == 0L || traceBaseMs == 0L) 0L else value - traceBaseMs

    /** Link 5: the camera was aimed at a position (render thread). */
    fun noteFocus(x: Float, y: Float, z: Float) {
        focusUpdates += 1
        focusLast = "(" + AgentUpdateBuilder.fmt(x) + ", " + AgentUpdateBuilder.fmt(y) + ", " + AgentUpdateBuilder.fmt(z) + ")"
        synchronized(lock) {
            val previous = lastFocus
            if (previous == null || abs(previous.x - x) > 0.01f || abs(previous.y - y) > 0.01f || abs(previous.z - z) > 0.01f) {
                focusChanges += 1
            }
            lastFocus = Vector3(x, y, z)
        }
    }

    /**
     * True when the frame that went on the wire is the reference frame for the
     * heading the camera basis was built from.
     *
     * It compares the *serialized* basis against [AgentUpdateBuilder.referenceBasis]
     * of the view yaw that was used ([lastBasisYaw]), so the one line answers "the
     * camera basis in this packet is the one a working client would send" without
     * the reader having to compare three vectors by eye. The body heading in the
     * same packet is [lastBodyYawSent] and is *not* what the basis follows.
     */
    fun sentBasisMatchesReference(): Boolean {
        val sent = lastBasisValue ?: return false
        val reference = AgentUpdateBuilder.referenceBasis(lastBasisYaw)
        return sameAxis(sent.at, reference.at) &&
            sameAxis(sent.left, reference.left) &&
            sameAxis(sent.up, reference.up)
    }

    private fun sameAxis(a: Vector3, b: Vector3): Boolean =
        abs(a.x - b.x) <= 1e-3f && abs(a.y - b.y) <= 1e-3f && abs(a.z - b.z) <= 1e-3f

    /** A position as the report prints it, or `-` when the simulator has not said yet. */
    private fun prettyOrDash(position: Vector3?): String =
        if (position == null) "-" else AgentUpdateBuilder.pretty(position)

    /** `0x00000001`, for a report that has to show the exact word that went out. */
    private fun fmtFlags(flags: Long): String = "0x" + hex(flags)

    /** The same, for a value that may not have been set (no packet carried it). */
    private fun flagsText(flags: Long): String = if (flags < 0) "(ninguno con AT_POS)" else fmtFlags(flags)

    private fun hex(flags: Long): String =
        if (flags < 0) "?" else String.format(java.util.Locale.US, "%08x", flags)

    // --------------------------------- traza temporal (revision 2.13b-rev3) ---

    /** Adds one line to the interleaved trace. Caller holds [lock]. */
    private fun traceAdd(nowMs: Long, text: String) {
        if (traceBaseMs == 0L) {
            traceBaseMs = nowMs
        }
        trace.add("t=" + (nowMs - traceBaseMs) + " " + text)
        while (trace.size > TRACE_LIMIT) {
            trace.removeAt(0)
        }
        forwardTrace = trace.joinToString(" | ")
    }

    /** Adds one window summary, newest last, keeping only the last few. Caller holds [lock]. */
    private fun windowSummaryAdd(line: String) {
        windowLog.add(line)
        while (windowLog.size > WINDOW_LOG_LIMIT) {
            windowLog.removeAt(0)
        }
        forwardWindowLog = windowLog.joinToString("\n    ")
    }

    /**
     * Whether an event is close enough to a FORWARD event to belong to it: the
     * window itself, or the tail after the release in which an asynchronously
     * queued send can still land.
     */
    private fun nearForwardEvent(nowMs: Long): Boolean =
        forwardWindowOpen || (lastForwardEventMs != 0L && nowMs - lastForwardEventMs <= TRACE_TAIL_MS)

    /**
     * Samples the engine's own control word, independently of any packet, so
     * "does the engine keep FORWARD active internally?" is measured and not
     * inferred from what happened to get serialized.
     *
     * Two counts come out of it: every sample near a FORWARD event, and — the
     * precise one — the samples taken *while the window was open*, i.e. while the
     * button was physically down and a send could still have seen the flag.
     */
    private fun sampleInternalFlag(nowMs: Long, controlFlags: Long) {
        synchronized(lock) {
            if (!nearForwardEvent(nowMs)) {
                return
            }
            val hasForward = (controlFlags and AgentControlFlags.AT_POS) != 0L
            forwardStateSamples += 1
            if (hasForward) {
                forwardStateWithFlag += 1
            }
            if (forwardWindowOpen) {
                forwardStateOpenSamples += 1
                if (hasForward) {
                    forwardStateOpenWithFlag += 1
                }
            }
            traceAdd(nowMs, if (hasForward) "interno AT_POS" else "interno sin AT_POS")
        }
    }

    /** The pad action that opens a window; the audit counts FORWARD specifically. */
    private const val FORWARD_ACTION = "FORWARD"

    /** How many distinct flag values a window lists before it stops growing. */
    private const val MAX_DISTINCT_WINDOW_FLAGS = 8

    /** How many of the window's deltas the report prints (the rest are counted). */
    private const val MAX_LISTED_WINDOW_DELTAS = 12

    // --------------------------------- traza temporal (revision 2.13b-rev3) ---

    /** Entries the interleaved trace keeps; older ones fall off the front. */
    private const val TRACE_LIMIT = 120

    /** Windows the per-window summary keeps, newest last. */
    private const val WINDOW_LOG_LIMIT = 12

    /** Position changes the sequence-attribution list keeps. */
    private const val MOVES_LIMIT = 16

    /**
     * How long after a FORWARD event a send still counts as "caused by it".
     * A tap does not stall the world: the send goes out on the IO dispatcher, so
     * the packet for a press can be serialized after the release has already
     * cleared the flag. That is the case this window measures.
     */
    private const val TRACE_TAIL_MS = 1200L

    // -------------------------------------------------------------- verdict ---

    /** Which link stops the chain, as far as the counters can tell. */
    fun verdict(): MovementBreak {
        if (padPresses == 0 && handlerCalls == 0) {
            return MovementBreak.A_INPUT
        }
        if (updateBuilt == 0) {
            return MovementBreak.B_ENGINE
        }
        if (updateSent == 0) {
            return MovementBreak.C_SEND
        }
        if (ownAdoptSamples == 0 || ownPositionChanges == 0) {
            return MovementBreak.D_SIM
        }
        if (focusChanges == 0) {
            return MovementBreak.E_LOCAL
        }
        return MovementBreak.NONE
    }

    /** The verdict, spelled out for the HUD and the report. */
    fun verdictLine(): String {
        val kind = verdict()
        val detail = when (kind) {
            MovementBreak.NONE ->
                "los cinco enlaces funcionan; si aun asi no avanza, el problema no es la cadena sino el contenido del comando (mira las lineas 'ventana FORWARD' y la base de camara enviada)"
            MovementBreak.A_INPUT ->
                "la entrada no llega al motor: ninguna pulsacion del pad ha llamado al motor (revisa el overlay de botones y el hilo de input)"
            MovementBreak.B_ENGINE ->
                "el motor no genera el comando: hubo pulsaciones pero ningun AgentUpdate se construyo (mira 'rechazadas por no-conectado' en el enlace 2)"
            MovementBreak.C_SEND ->
                "el comando no se envia: se construyo pero el circuito no lo acepto (mira los errores y la plantilla)"
            MovementBreak.D_SIM ->
                "el simulador no actualiza nuestra posicion: se enviaron comandos y el objeto propio no se movio"
            MovementBreak.E_LOCAL ->
                "la posicion del avatar cambia pero la camara/posicion local no la refleja"
        }
        return "Movimiento, veredicto: " + kind.label + " · " + detail
    }

    /** The one line the always-visible HUD carries. */
    fun hudLine(): String =
        "Movimiento: pulsaciones " + padPresses + "  ·  comandos " + updateSent + "/" + updateBuilt +
            "  ·  posicion propia " + ownPositionChanges + "/" + ownAdoptSamples +
            "  ·  camara " + focusChanges + "  ·  " + verdict().label

    // --------------------------------------------------------------- report ---

    /** The audit block of the report: one line per link. */
    fun reportText(): String {
        val builder = StringBuilder(1400)
        synchronized(lock) {
            builder.append("Movimiento (auditoria, fase 2.13a-rev5): un enlace por linea\n")
            builder.append(
                "  enlace 1-entrada: pulsaciones " + padPresses + "  ·  sueltas " + padReleases +
                    "  ·  ultima accion " + lastAction + "\n"
            )
            builder.append(
                "  enlace 2-motor: llamadas " + handlerCalls + "  ·  rechazadas por no-conectado " +
                    handlerRejectedNotConnected + "  ·  paradas " + stopRequests + "  ·  vuelo " + flyToggles +
                    "  ·  flags " + AgentControlFlags.describe(flagsNow) +
                    "  ·  rumbo cuerpo " + AgentUpdateBuilder.fmt(bodyYawNow) +
                    " rad  ·  rumbo vista " + AgentUpdateBuilder.fmt(viewYawNow) + " rad\n"
            )
            builder.append(
                "  enlace 3-comando: AgentUpdate construidos " + updateBuilt + "  ·  enviados " + updateSent +
                    "  ·  errores de envio " + updateSendErrors +
                    "  ·  plantilla ausente " + templateMissing +
                    "  ·  ultima secuencia " + lastSequence +
                    "  ·  " + lastSentBytes + " bytes" +
                    "  ·  rumbo enviado (serializado, cuerpo) " + AgentUpdateBuilder.fmt(lastBodyYawSent) + " rad" +
                    "  ·  rumbo usado (base de camara) " + AgentUpdateBuilder.fmt(lastBasisYaw) + " rad" +
                    (if (lastSendError.isEmpty()) "" else "  ·  ultimo error " + lastSendError) +
                    (if (sentBeforeMovementComplete > 0) {
                        "  ·  ATENCION: " + sentBeforeMovementComplete + " enviados antes de AgentMovementComplete"
                    } else {
                        ""
                    }) + "\n"
            )
            builder.append(
                "  enlace 3-comando, flags serializados " + AgentControlFlags.describe(lastFlagsSent) +
                    "  ·  centro de camara serializado " + lastCameraCenter + "\n"
            )
            builder.append(
                "  enlace 3-comando, base de camara serializada: " + lastBasis +
                    "  ->  " + (if (sentBasisMatchesReference()) {
                        "COINCIDE con la base de referencia de un visor que funciona"
                    } else {
                        "NO COINCIDE con la base de referencia (revisa el rumbo y la correccion M1)"
                    }) + "\n"
            )
            builder.append(
                "  enlace 3-comando, base de referencia (rumbo usado " +
                    AgentUpdateBuilder.fmt(lastBasisYaw) + " rad): " +
                    AgentUpdateBuilder.referenceBasis(lastBasisYaw).describe() + "\n"
            )
            builder.append(
                "  enlace 3-comando, base de la revision anterior (hallazgo M1, ya no se envia): " +
                    AgentUpdateBuilder.currentBasis().describe() + "\n"
            )
            builder.append(
                "  ventana FORWARD: pulsaciones " + forwardWindows +
                    "  ·  ahora " + (if (forwardWindowOpen) "PULSADO" else "soltado") +
                    "  ·  flags en el informe = 0x00000000 son los del ultimo paquete (el de soltar)\n"
            )
            builder.append(
                "  ventana FORWARD, comandos con la tecla pulsada: " + forwardWindowUpdates +
                    " serializados  ·  con AT_POS (0x00000001) " + forwardWindowFlagged +
                    "  ·  sin AT_POS " + forwardWindowUnflagged +
                    "  ·  con NUDGE " + nudgeUpdates +
                    "  ·  NUDGE emitidos por sueltas cortas " + nudgeEmitted +
                    (if (nudgeEmittedLast == "-") "" else " (ultimo: " + nudgeEmittedLast + ")") + "\n"
            )
            builder.append(
                "  ventana FORWARD, flags serializados de esos paquetes: " +
                    (if (forwardWindowFlagsSeen.isEmpty()) {
                        "(ninguno con AT_POS)"
                    } else {
                        forwardWindowFlagsSeen
                    }) + "\n"
            )
            builder.append(
                "  ventana FORWARD, primero con AT_POS: secuencia " + forwardWindowFirstSequence +
                    "  ·  flags " + flagsText(forwardWindowFirstFlags) +
                    "  ·  posicion " + forwardWindowFirstPosition +
                    "  ·  ultimo con AT_POS: secuencia " + forwardWindowLastSequence +
                    "  ·  flags " + flagsText(forwardWindowLastFlags) + "\n"
            )
            builder.append(
                "  ventana FORWARD, posiciones propias: antes de pulsar " + forwardWindowPositionBefore +
                    "  ·  con la tecla pulsada " + forwardWindowPositionLast +
                    "  ·  despues de soltar " + forwardWindowPositionAfter + "\n"
            )
            builder.append(
                "  ventana FORWARD, movimientos durante la ventana: " + forwardWindowDeltas +
                    " cambios  ·  recorrido " + AgentUpdateBuilder.fmt(forwardWindowPathMetres) +
                    " m  ·  neto desde la pulsacion " + AgentUpdateBuilder.fmt(forwardWindowNetMetres) +
                    " m  ·  primer delta " + forwardWindowFirstDelta +
                    "  ·  ultimo delta " + forwardWindowLastDelta +
                    "  ·  deltas (" + forwardWindowDeltaList + ")\n"
            )
            builder.append(
                "  ventana FORWARD (temporal 2.13b-rev3), totales: ventanas " + forwardWindows +
                    "  ·  paquetes con la tecla pulsada " + forwardTotalUpdates +
                    " (con AT_POS " + forwardTotalFlagged + "  ·  sin AT_POS " + forwardTotalUnflagged + ")" +
                    "  ·  ventanas sin ningun AT_POS " + forwardWindowsWithoutFlag +
                    "  ·  enviados DESPUES de soltar (dentro de " + TRACE_TAIL_MS + " ms) " + forwardLateSends +
                    " (con AT_POS " + forwardLateFlagged + ")\n"
            )
            builder.append(
                "  ventana FORWARD (temporal), tiempos: duracion de la ultima ventana " + forwardWindowDurationMs +
                    " ms  ·  tiempo total con la tecla pulsada " + forwardHeldTotalMs + " ms" +
                    "  ·  primer AT_POS t=" + relMs(forwardFlaggedFirstMs) +
                    " ms  ·  ultimo AT_POS t=" + relMs(forwardFlaggedLastMs) +
                    " ms  ·  separacion " + (relMs(forwardFlaggedLastMs) - relMs(forwardFlaggedFirstMs)) +
                    " ms  ·  intervalos entre AT_POS (" + forwardFlaggedIntervals + ") ms\n"
            )
            builder.append(
                "  ventana FORWARD (temporal), estado interno del motor: muestras " + forwardStateSamples +
                    " (con AT_POS " + forwardStateWithFlag + ")" +
                    "  ·  muestras con la ventana ABIERTA " + forwardStateOpenSamples +
                    " (con AT_POS " + forwardStateOpenWithFlag + ")" +
                    "  ·  " + (if (forwardStateOpenWithFlag > 0) {
                        "el motor SI tiene FORWARD en su estado con el boton pulsado"
                    } else {
                        "el motor NO muestra FORWARD en el estado interno: el flag solo vive en el paquete"
                    }) + "\n"
            )
            builder.append(
                "  ventana FORWARD (temporal), envios TARDIOS (sin ventana abierta y dentro de " +
                    TRACE_TAIL_MS + " ms de un evento FORWARD): " + forwardLateSends +
                    " (con AT_POS " + forwardLateFlagged + ")  ·  ultimos: " + forwardLateSendList + "\n"
            )
            builder.append(
                "  ventana FORWARD (temporal), posiciones y secuencias: " + forwardMovesWithSequence + "\n"
            )
            builder.append(
                "  ventana FORWARD (temporal), ultimas ventanas (dur, paquetes y AT_POS):\n    " +
                    forwardWindowLog + "\n"
            )
            builder.append(
                "  ventana FORWARD (temporal), traza (t=ms; P=pulsado S=soltado; interno=estado del motor; E=enviado; M=movimiento):\n    " +
                    forwardTrace + "\n"
            )
            builder.append(
                "  enlace 4-simulador: AgentMovementComplete " + movementCompleteCount +
                    "  ·  avatar propio id local " + ownLocalId +
                    "  ·  muestras del objeto propio " + ownAdoptSamples +
                    "  ·  cambios de posicion " + ownPositionChanges +
                    "  ·  recorrido " + AgentUpdateBuilder.fmt(ownPathMetres) +
                    " m  ·  desde el inicio " + AgentUpdateBuilder.fmt(ownDistanceFromStart) +
                    " m  ·  primer desplazamiento " + ownFirstDelta + "\n"
            )
            builder.append(
                "  enlace 4-simulador, posicion antes " + ownPreviousPosition +
                    "  ·  posicion despues " + ownLastPosition +
                    "  ·  desplazamiento neto " + AgentUpdateBuilder.fmt(ownDistanceFromStart) + " m" +
                    "  ·  recorrido total " + AgentUpdateBuilder.fmt(ownPathMetres) + " m\n"
            )
            builder.append(
                "  enlace 5-reflejo local: focos de camara " + focusUpdates +
                    "  ·  cambios de foco " + focusChanges + "  ·  ultimo foco " + focusLast + "\n"
            )
            builder.append("  ").append(verdictLine()).append('\n')
        }
        return builder.toString()
    }

    fun reset() {
        padPresses = 0
        padReleases = 0
        lastAction = "-"
        handlerCalls = 0
        handlerRejectedNotConnected = 0
        stopRequests = 0
        flyToggles = 0
        flagsNow = 0L
        bodyYawNow = 0f
        viewYawNow = 0f
        updateBuilt = 0
        updateSent = 0
        updateSendErrors = 0
        templateMissing = 0
        lastSendError = ""
        lastSequence = 0
        lastFlagsSent = 0L
        lastCameraCenter = "-"
        lastBasis = "-"
        lastBodyYawSent = 0f
        lastBasisYaw = 0f
        lastSentBytes = 0
        sentBeforeMovementComplete = 0
        forwardWindows = 0
        forwardWindowOpen = false
        forwardWindowUpdates = 0
        forwardWindowFlagged = 0
        forwardWindowUnflagged = 0
        forwardWindowFirstSequence = -1
        forwardWindowFirstFlags = -1L
        forwardWindowFirstPosition = "-"
        forwardWindowLastSequence = -1
        forwardWindowLastFlags = -1L
        forwardWindowFlagsSeen = ""
        forwardWindowPositionBefore = "-"
        forwardWindowPositionLast = "-"
        forwardWindowPositionAfter = "-"
        forwardWindowDeltas = 0
        forwardWindowPathMetres = 0f
        forwardWindowNetMetres = 0f
        forwardWindowFirstDelta = "-"
        forwardWindowLastDelta = "-"
        forwardWindowDeltaList = "-"
        nudgeUpdates = 0
        movementCompleteCount = 0
        ownLocalId = -1
        ownAdoptSamples = 0
        ownPositionChanges = 0
        ownLastPosition = "-"
        ownPreviousPosition = "-"
        ownFirstDelta = "-"
        ownPathMetres = 0f
        ownDistanceFromStart = 0f
        focusUpdates = 0
        focusChanges = 0
        focusLast = "-"
        forwardTotalUpdates = 0
        forwardTotalFlagged = 0
        forwardTotalUnflagged = 0
        forwardWindowsWithoutFlag = 0
        forwardWindowDurationMs = 0L
        forwardHeldTotalMs = 0L
        forwardLateSends = 0
        forwardLateFlagged = 0
        forwardLateSendList = "-"
        forwardFlaggedFirstMs = 0L
        forwardFlaggedLastMs = 0L
        forwardFlaggedIntervals = "-"
        forwardStateSamples = 0
        forwardStateWithFlag = 0
        forwardStateOpenSamples = 0
        forwardStateOpenWithFlag = 0
        forwardTrace = "-"
        forwardWindowLog = "-"
        forwardMovesWithSequence = "-"
        synchronized(lock) {
            ownStart = null
            ownPrevious = null
            lastFocus = null
            traceBaseMs = 0L
            trace.clear()
            windowLog.clear()
            movesWithSequence.clear()
            allFlaggedIntervalsMs.clear()
            flaggedIntervalsMs.clear()
            windowUpdates = 0
            windowFlagged = 0
            windowUnflagged = 0
            windowOpenedMs = 0L
            lastForwardEventMs = 0L
            lastFlaggedSendMs = 0L
            lastWindowFlaggedMs = 0L
            lateSendList.clear()
        }
        lastBasisValue = null
    }
}

/** Where the movement chain stops, as far as the audit's counters can tell. */
enum class MovementBreak(val label: String) {
    NONE("ninguno"),
    A_INPUT("A"),
    B_ENGINE("B"),
    C_SEND("C"),
    D_SIM("D"),
    E_LOCAL("E")
}
