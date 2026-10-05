# Movement audit — why the pad is on screen and the avatar does not advance

This document is the **movement investigation**, and it is deliberately separate
from the texture work (phase 2.13b). Its job is to *locate* the movement chain and
to make each link measurable, so that the next device run says **where the chain
cuts** instead of "the controls do nothing", which has five very different
causes that look identical from the sofa:

| | what it means | what it looks like from outside |
|---|---|---|
| **A** | the input never reaches the engine | the pad is drawn, pressing it does nothing at all |
| **B** | the engine never builds the command | the handler runs, no `AgentUpdate` is built |
| **C** | the command is built and not sent | `AgentUpdate` built, the circuit never sends it |
| **D** | the simulator does not update us | commands are sent, our own body never moves |
| **E** | the position changes and nothing reflects it | the body moves, the camera/HUD stay still |

**Status: the deviation this audit found (M1, below) is CORRECTED** — the fix is
the one line the audit prepared (`referenceBasis(viewYaw)` instead of
`currentBasis()`), applied in revision 2.13b-rev1 after the device run confirmed
the chain works end to end, and **confirmed on the device**: the 2.13b-rev2 run
serialized `at (0.59,-0.81,0.00) · izquierda (0.81,0.59,0.00) · arriba (0,0,1)` →
`COINCIDE`. Everything else the audit measures is unchanged, and the report prints
the
*serialized* values (read back out of the packet) so the correction is visible in a
device log instead of being taken on trust. `referenceBasis`/`currentBasis` are
**not touched again**.

**Revision 2.13b-rev2 adds the measurement the first run showed was missing.** That
run ended with `flags serializados 0x00000000`, and — as the user put it — that may
simply be the *release* packet, which proves nothing about the press. The last
packet of every run is the release, so the audit now reports the **FORWARD window**:
everything sent between a press and its release, counted separately (§3b).

**Revision 2.13b-rev3 is a temporary trace, and only that** (§3c). The device run of
2.13b-rev2 answered objective A — `AT_POS = 0x00000001` does reach the wire, the
serialized basis `COINCIDE`s, the sends do not error — but produced two numbers that
do not add up: **50 presses, 2 packets carrying `FORWARD=1`, and no movement at all
inside the window**. The window counters describe the packets that were *serialized*
while the window was open, and the send is handed to an IO dispatcher, so the packet
belonging to a press can be built after the release cleared the flag. That is the
hypothesis; §3c is what confirms or kills it. Nothing else in the movement path
changes, and the textures are untouched.

## The device run that made the correction the next step

The audit did its job on the device: the chain is not where it cuts.

```
pulsaciones 79  ·  AgentUpdate construidos 337  ·  enviados 337  ·  errores de envio 0
cambios de posicion del avatar propio 16
base de camara enviada: at (0,0,-1)  ·  izquierda (0,1,0)  ·  arriba (0,0,1)
```

The pad reaches the engine, the engine builds the packet, the circuit sends it, the
simulator *does* move the body (16 changes), and the camera follows it. So A, B, C
and E are all out, and what is left is the content of the command — and the one
unusual thing in it is exactly the degenerate camera basis, with the first
movement observed towards **−Z**, which is the direction that basis points.

---

## 1. The chain, link by link

Everything below is the state of the code as of phase 2.13a revision 5.

### Link 1 — the input (the pad) → the engine

| | |
|---|---|
| code | `ui/world/WorldViewActivity.kt` `holdToMove()` (the world screen's pad) and `ui/world/MovementActivity.kt` `holdToMove()` (the full-screen pad + mini-map) |
| what it does | `setOnTouchListener` on each button: `ACTION_DOWN` → `setMoveAction(action, true)`, `ACTION_UP`/`ACTION_CANCEL` → `setMoveAction(action, false)` |
| layout | `res/layout/activity_world_view.xml`: the pad lives in the bottom `LinearLayout`, added **after** the `viewportCtn` that holds the `SurfaceView`, so the buttons are on top of the surface and receive touches |
| input thread | `FilamentWorldView.onTouchEvent` records `Thread.currentThread().name` in `RenderDiagnostics.inputThreadName`; the device reported `main`, and `Hilos:` prints it next to the render thread's name |
| counter | `MovementAudit.padPresses` / `padReleases` / `lastAction` |

Evidence this link works: none, before this revision — nothing counted a press.

### Link 2 — the handler and the movement state

| | |
|---|---|
| code | `slproto/modules/SLConnection.kt` `setMoveAction()` (`:605`), `stopMovement()`, `setFly()`, `changeBodyYaw()` |
| what it does | refuses to act unless `stateFlow.value == CONNECTED`; maps the action to a control bit (`AgentControlFlags.bitFor`), sets or clears it in `controlFlags`, turns the body towards the camera (`bodyYaw = viewYaw`) for a forward/strafe press, then asks for an `AgentUpdate` |
| the state | `controlFlags` (`:133`), `bodyYaw`, `viewYaw`, `stopPulse` — all `@Volatile`, written from the UI/coroutine side and read by the send |
| counter | `MovementAudit.handlerCalls` / `handlerRejectedNotConnected` / `flagsNow` / `bodyYawNow` / `viewYawNow` |

The bit table is now a single source of truth,
`slproto/movement/AgentControlFlags.kt`, and the `moveaudit` check asserts it
against the reference (`EControlFlags` / `AgentManager.ControlFlags`):
`AT_POS 0x1`, `AT_NEG 0x2`, `LEFT_POS 0x4`, `LEFT_NEG 0x8`, `UP_POS 0x10`,
`UP_NEG 0x20`, `YAW_POS 0x100`, `YAW_NEG 0x200`, `FAST_AT 0x400`,
`FAST_LEFT 0x800`, `FAST_UP 0x1000`, `FLY 0x2000`, `STOP 0x4000`.

### Link 3 — the command: building and sending `AgentUpdate`

| | |
|---|---|
| code | `SLConnection.sendAgentUpdate()` (called from `sendAgentUpdateAsync()` and from the 200 ms movement tick in `startPeriodicJobs()`) |
| build | `slproto/movement/AgentUpdateBuilder.build()` — a pure function, so the `moveaudit` check encodes the same message off the device |
| wire shape | `AgentUpdate High 4 NotTrusted Zerocoded`, one `AgentData` block, **122 bytes**: AgentID(16) SessionID(16) BodyRotation(16) HeadRotation(16) State(1) CameraCenter(12) CameraAtAxis(12) CameraLeftAxis(12) CameraUpAxis(12) Far(4) ControlFlags(4) Flags(1) |
| send | `Circuit.send(message, reliable = false)` → `SLMessageCodec.encode` → `SLPacketWriter.build` → `ZeroCodec.encode` → `sock.send` |
| rate | one send per press/release, plus one every 200 ms while any control is held, plus one keepalive every 20 ticks (4 s) |
| counters | `MovementAudit.updateBuilt` / `updateSent` / `updateSendErrors` / `templateMissing` / `lastSequence` / `lastFlagsSent` / `lastCameraCenter` / `lastBasis` / `sentBeforeMovementComplete` |

**Evidence this link is wired all the way to the socket — from the device log the
user already sent** (`scratch`-era report, 20:51:53):

```
Fallo al enviar AgentUpdate: sendto failed: EPERM (Operation not permitted)
```

That message is printed by `Circuit.reportSendFailure` inside
`transmit()`'s `catch`, i.e. **after** the message was built, encoded and handed
to `DatagramSocket.send`. So A, B and C are not where the chain cuts: the pad
called the engine, the engine built the packet, and the packet reached the
socket. (The `EPERM` itself is Android refusing `sendto` because the app had been
backgrounded — see §4.)

### Link 4 — the simulator's answer: our own avatar

| | |
|---|---|
| code | `SLConnection.handleAgentMovementComplete()` (the spawn position) and `SLConnection.adoptAgentObject()`, called after every `ObjectUpdate` / `ObjectUpdateCompressed` / `ImprovedTerseObjectUpdate` batch |
| how our body is found | once, by scanning the world for an avatar whose `uuid == agentId`, and remembering its `world.agentLocalId` |
| how the position is followed | every time an update batch arrives, `mine.position` is copied into `world.agentPosition` and into `sessionFlow.position` — this is the number the HUD prints |
| counters | `MovementAudit.movementCompleteCount` / `ownLocalId` / `ownAdoptSamples` / `ownPositionChanges` / `ownFirstDelta` / `ownPathMetres` / `ownDistanceFromStart` / `ownLastPosition` |

**Evidence from the same device log**: `AgentMovementComplete` arrived
(`Posicion inicial: (167.962, 141.954, 22.5211)`) and the avatar was found
(`Avatar propio detectado: id local 545575791`). So the *wiring* of link 4 is
proven; what is not yet known is whether the number ever changes after a command.

### Link 5 — the local reflection: camera and HUD

| | |
|---|---|
| code | `FilamentWorldView.RenderThread.applyFraming()` — `camera.setFocus(agentPosition.x, y, z + AGENT_EYE_HEIGHT)` on **every** frame while `framing == AGENT`, so the camera follows the position by construction; `WorldViewActivity.updateHud()` (every 400 ms) prints `session.position` |
| counters | `MovementAudit.focusUpdates` / `focusChanges` / `focusLast` |

### Where a report of it lands

`FilamentWorldView.fullReportText()` (and therefore the file `writeReport()`
writes) now ends with `MovementAudit.reportText()`: one line per link, then the
verdict. The debug HUD carries the one-line version
(`Movimiento: pulsaciones N · comandos N/N · posicion propia N/N · camara N · X`)
and the verdict line is appended to the short verdict box
(`worldView.verdictLines()`), so a screenshot is enough to see which letter the
run produced.

---

## 2. Findings

### M1 — the camera basis is not a frame (confirmed deviation; CORRECTED in 2.13b-rev1)

`sendAgentUpdate` used to fill the three camera fields with constants
(`SLConnection.kt`, before this revision):

```kotlin
block.set("CameraAtAxis",   Vector3(0f, 0f, -1f))
block.set("CameraLeftAxis", Vector3(0f, 1f, 0f))
block.set("CameraUpAxis",   Vector3(0f, 0f, 1f))
```

In Second Life's frame X is east, Y is north, **Z is up**. So the at-axis points
straight **down**, and it is the exact opposite of the up-axis. The three vectors
are coplanar, the "frame" has zero volume, and it is not a rotation at all.

`moveaudit` reads the three vectors back **out of the built packet** (offsets 77 /
89 / 101), so before the correction the deviation was on the record, and after it
the same check prints the corrected, orthonormal basis:

```
# before (hallazgo M1, ya no se envia)
MOVIMIENTO: base enviada at (0.00, 0.00, -1.00)  ·  izquierda (0.00, 1.00, 0.00)  ·  arriba (0.00, 0.00, 1.00)  (NO es una base: volumen 0.00, at.arriba=-1.00)
# after (2.13b-rev1, lo que el visor envia ahora)
MOVIMIENTO: base serializada (correccion M1 aplicada) at (0.76, 0.64, 0.00)  ·  izquierda (-0.64, 0.76, 0.00)  ·  arriba (0.00, 0.00, 1.00)
MOVIMIENTO: base de referencia (rumbo 0) at (1.00, 0.00, 0.00)  ·  izquierda (0.00, 1.00, 0.00)  ·  arriba (0.00, 0.00, 1.00)
```

What a working client sends, from the reference at hand
(`openmetaversefoundation/libopenmetaverse`, fetched to `scratch/ref/`:
`AgentManagerMovement.cs` + `CoordinateFrame.cs`):

* `AgentCamera.AtAxis`/`LeftAxis`/`UpAxis` are the camera frame's own axes, set by
  `LookDirection(heading)` for the same heading the body rotation uses;
* `CoordinateFrame` documents X as *"Forward/At in grid terms"*, Y as *"Left in
  grid terms"*, Z as up, and builds the frame from `left = up × at`;
* for heading 0 (the identity body rotation, facing east) that is
  `at = (1, 0, 0)`, `left = (0, 1, 0)`, `up = (0, 0, 1)` — an orthonormal,
  right-handed frame, volume +1.

The consequence, in the two simulators we can read:

* **OpenSim** builds the agent's camera rotation out of exactly these three
  vectors — `ScenePresence.HandleAgentCamerasUpdate` does
  `CameraRotation = Util.Axes2Rot(CameraAtAxis, CameraLeftAxis, CameraUpAxis)`.
  Feeding it a degenerate triple gives the region a non-rotation for our camera.
* **Second Life** (which the user's log shows this session is connected to:
  `login.agni.lindenlab.com`, region Chadara) never publishes its movement code,
  so the simulation of `AGENT_CONTROL_AT_POS` cannot be read. What *is* known is
  that a walk-forward is "away from the camera, along the camera's at-axis", and
  the at-axis we send is the down vector. An avatar being pushed into the ground
  is stopped by collision — which looks exactly like "the controls appear and I
  cannot advance".

**So this is the prime suspect for case D, not a proven cause.** The counters in
§3 are what prove it: if the body moves at all, `ownFirstDelta` will show *which
way* (a downward delta with the camera basis as the only unusual input is as
close to proof as one device run gets).

The correction is one argument at the call site (the builder already takes the
basis as a parameter), and it is **applied** in 2.13b-rev1 — nothing else in the
movement path changed:

```kotlin
val basis = AgentUpdateBuilder.referenceBasis(viewYaw)   // instead of currentBasis()
```

`currentBasis()` still exists (documented as the historical M1 value) so the
`moveaudit` check keeps asserting what was wrong and the report keeps printing it
as a *contrast* line next to the basis that is actually being sent.

### M2 — the send rate is not the reference's (noted, not judged)

The reference library's own timer sends an `AgentUpdate` every
`DEFAULT_AGENT_UPDATE_INTERVAL` (500 ms in the current master of
LibreMetaverse), and OpenSim's reader notes *"Every client sends 10 AgentUpdate
UDP messages per second, even if it is not moving"*. This viewer sends one per
input change plus one every 200 ms while a control is held, and one keepalive
every 4 s when idle. That is inside the range a region accepts, and OpenSim's
significance test (`CheckAgentMovementUpdateSignificance`, which returns true
whenever the control flags are non-zero and more than 20 ms have passed) is
satisfied by a held control. Recorded here so the number is not a surprise; it is
not treated as a cause.

### M3 — no guard on `AgentMovementComplete` (noted)

LibreMetaverse refuses to send an `AgentUpdate` until the simulator has answered
`CompleteAgentMovement`, with a comment worth quoting:

> *"Since version 1.40.4 of the Linden simulator, sending this update causes
> corruption of the agent position in the simulator."*

This viewer gates on `stateFlow.value == CONNECTED`, which is set immediately
after `CompleteAgentMovement` is **sent** — i.e. before `AgentMovementComplete`
arrives. The keepalive tick can therefore send an `AgentUpdate` inside that
window. The audit counts it (`sentBeforeMovementComplete`) and the report prints a
`ATENCION:` note when it happens, so the next run says whether it actually
happened instead of us guessing.

### M4 — `CameraCenter` is the last position we know (noted)

`CameraCenter` is `sessionFlow.position + 1.5 m`, and `sessionFlow.position` only
changes when our own object's updates are adopted (link 4). If link 4 stalls, the
camera centre we report freezes with it. This is a *consequence* of D/E, not a
cause, but it is why the report prints the camera centre next to the flags.

---

## 3. What the next device run has to show

Run the world, **keep the app in the foreground** (§4), press the forward button
for a few seconds, then open the diagnostics panel and copy the report. The block
added to it is:

```
Movimiento (auditoria, fase 2.13a-rev5): un enlace por linea
  enlace 1-entrada: pulsaciones N  ·  sueltas N  ·  ultima accion FORWARD pulsado
  enlace 2-motor: llamadas N  ·  rechazadas por no-conectado 0  ·  ...  ·  flags 0x00000001 AT_POS  ·  rumbo cuerpo 0.00 rad  ·  rumbo vista 0.00 rad
  enlace 3-comando: AgentUpdate construidos N  ·  enviados N  ·  errores de envio 0  ·  plantilla ausente 0  ·  ultima secuencia N  ·  122 bytes  ·  rumbo enviado (serializado, cuerpo) X rad  ·  rumbo usado (base de camara) Y rad
  enlace 3-comando, flags serializados 0x00000001 AT_POS  ·  centro de camara serializado (...)
  enlace 3-comando, base de camara serializada: at (...)  ·  izquierda (...)  ·  arriba (...)  ->  COINCIDE con la base de referencia de un visor que funciona
  enlace 3-comando, base de referencia (rumbo usado Y rad): at (1.00, 0.00, 0.00)  ·  ...
  enlace 3-comando, base de la revision anterior (hallazgo M1, ya no se envia): at (0.00, 0.00, -1.00)  ·  ...  (NO es una base: volumen 0.00, at.arriba=-1.00)
  ventana FORWARD: pulsaciones N  ·  ahora {PULSADO|soltado}  ·  flags en el informe = 0x00000000 son los del ultimo paquete (el de soltar)
  ventana FORWARD, comandos con la tecla pulsada: U serializados  ·  con AT_POS (0x00000001) F  ·  sin AT_POS X  ·  con NUDGE (legacy, este visor no la genera) Z
  ventana FORWARD, flags serializados de esos paquetes: 0x00000001, 0x00000401, ...
  ventana FORWARD, primero con AT_POS: secuencia A  ·  flags 0x00000001 AT_POS  ·  posicion (...)   ·  ultimo con AT_POS: secuencia B  ·  flags ...
  ventana FORWARD, posiciones propias: antes de pulsar (...)  ·  con la tecla pulsada (...)  ·  despues de soltar (...)
  ventana FORWARD, movimientos durante la ventana: D cambios  ·  recorrido D m  ·  neto desde la pulsacion D m  ·  primer delta (dx, dy, dz)  ·  ultimo delta (...)  ·  deltas ((...), (...))
  ventana FORWARD (temporal 2.13b-rev3), totales: ventanas N  ·  paquetes con la tecla pulsada U (con AT_POS F  ·  sin AT_POS X)  ·  ventanas sin ningun AT_POS W  ·  enviados DESPUES de soltar (dentro de 1200 ms) L (con AT_POS FL)
  ventana FORWARD (temporal), tiempos: duracion de la ultima ventana D ms  ·  tiempo total con la tecla pulsada T ms  ·  primer AT_POS t=A ms  ·  ultimo AT_POS t=B ms  ·  separacion S ms  ·  intervalos entre AT_POS (i, i, ...) ms
  ventana FORWARD (temporal), estado interno del motor: muestras N (con AT_POS M)  ·  muestras con la ventana ABIERTA N2 (con AT_POS M2)  ·  ...
  ventana FORWARD (temporal), envios TARDIOS (sin ventana abierta y dentro de 1200 ms de un evento FORWARD): L (con AT_POS FL)  ·  ultimos: +d ms AT_POS  ·  +d ms sin  ·  ...
  ventana FORWARD (temporal), posiciones y secuencias: (dx,dy,dz) tras seq S  ·  ...
  ventana FORWARD (temporal), ultimas ventanas (dur, paquetes y AT_POS):
    #1  dur D ms  ·  paquetes con la tecla pulsada U (con AT_POS F  ·  sin AT_POS X)  ·  intervalos entre AT_POS (i, ...) ms
  ventana FORWARD (temporal), traza (t=ms; P=pulsado S=soltado; interno=estado del motor; E=enviado; M=movimiento):
    t=0 P pulsado | t=0 interno AT_POS | t=0 E+ AT_POS seq 1 flag 0x00000001 (con la tecla pulsada) | t=41 S soltado | t=41 interno sin AT_POS | t=44 E- sin AT_POS seq 2 flag 0x00000000 (TARDE, 3 ms tras el evento) | ...
  enlace 4-simulador: AgentMovementComplete 1  ·  avatar propio id local N  ·  muestras del objeto propio N  ·  cambios de posicion N  ·  recorrido N m  ·  desde el inicio N m  ·  primer desplazamiento (...)
  enlace 4-simulador, posicion antes (...)  ·  posicion despues (...)  ·  desplazamiento neto N m  ·  recorrido total N m
  enlace 5-reflejo local: focos de camara N  ·  cambios de foco N  ·  ultimo foco (...)
  Movimiento, veredicto: X · ...
```

The two lines to look at first, because they answer the question this revision
was built around: **`base de camara serializada … -> COINCIDE`** (the vectors the
packet really carries, read back at offsets 77/89/101, are the reference frame for
the heading we send) and **`primer desplazamiento` / `desplazamiento neto`** (if
the body moves now, it moves *forward*, not down).

Reading it:

* `pulsaciones 0` → **A** (the touch never reached the engine; look at the pad's
  overlay, not at the protocol).
* `pulsaciones N` with `construidos 0` → **B** (and `rechazadas por no-conectado`
  says whether the engine simply was not connected).
* `construidos N` with `enviados 0` → **C**; add `errores de envio` /
  `plantilla ausente` to say why.
* `enviados N` with `cambios de posicion 0` → **D**: the simulator is not moving
  the body. `primer desplazamiento` then separates "not at all" from "in the
  wrong direction, and the direction is the finding".
* `cambios de posicion N` with `cambios de foco 0` → **E**.

The last packet of any run is always the release, and its flags are `0x00000000`,
so the single `flags serializados` line cannot show whether `AT_POS` was ever
carried while the key was held. That is what the `ventana FORWARD` block answers;
it is the direct answer to the question this revision was built around.

### 3b. The FORWARD window — what the release packet hid

A *window* opens when the pad reports `FORWARD` down and closes when it reports
`FORWARD` up. Everything sent in between is counted on its own, so a lone
`0x00000000` at the end of the report is no longer the only evidence.

| line | field | answers |
|---|---|---|
| `ventana FORWARD: pulsaciones N · ahora …` | `forwardWindows`, `forwardWindowOpen` | how many presses were seen, and whether the last one is still held |
| `comandos con la tecla pulsada: U serializados · con AT_POS F · sin AT_POS X` | `forwardWindowUpdates`, `forwardWindowFlagged`, `forwardWindowUnflagged` | **requirements 1 and 2** — `U` = packets serialized while the key was physically down; `F` = of those, how many carry `ControlFlags & 0x00000001` |
| `flags serializados de esos paquetes: …` | `forwardWindowFlagsSeen` | **requirement 4** — the *distinct* exact flag values those packets carried (hex), e.g. `0x00000001`, `0x00000401` |
| `primero con AT_POS: secuencia A · flags … · ultimo con AT_POS: secuencia B · flags …` | `forwardWindowFirst/LastSequence`+`Flags`+`Position` | **requirement 3** — the first and last packet that *did* carry `AT_POS`, with their sequence numbers so `U`/`F` cannot be a rounding artefact |
| `posiciones propias: antes de pulsar … · con la tecla pulsada … · despues de soltar …` | `forwardWindowPositionBefore/Last/After` | **requirement 6** — the avatar's own position before the first `FORWARD=1`, during the held series, and after release |
| `movimientos durante la ventana: D cambios · recorrido D m · neto … · primer delta … · ultimo delta … · deltas (…)` | `forwardWindowDeltas`, `forwardWindowPathMetres`, `forwardWindowNetMetres`, `forwardWindowDeltaList` | **requirement 7** — per-change ΔX/ΔY/ΔZ over the FORWARD period, plus the path length and the net displacement since the press |
| `con NUDGE (legacy, este visor no la genera) Z` | `nudgeUpdates` | **requirement 5** — whether `AGENT_CONTROL_NUDGE_AT_POS` (`0x00080000`) went out; this viewer never sets it, so `Z` should be `0` |

Reading the window:

* `U 0` with `pulsaciones ≥ 1` → nothing was serialized while the key was held: the
  press bit never reached the builder (the counter lives in `noteCommandSent`, so
  the builder ran without the flag) — check `enlace 2-engine`'s `flags`.
* `U > 0` but `F 0` → the packets were built with the key held, **and the flag was
  lost between the handler and the wire**; the `flags serializados` line then names
  the value that *was* sent.
* `F > 0` but `movimientos durante la ventana: 0 cambios` → the flag reaches the
  wire and the simulator ignores it (case D with the content now proven, not
  guessed) — `primer delta` says whether the body moved at all and in which
  direction.
* `F > 0` and `neto > 0 m` → the command content is right and the body advances;
  compare `primer delta` against the camera basis (`base serializada`) to confirm
  it moves *along* the at-axis, not down it.

`MovementAudit.verdict()` computes that letter from the counters, and the
`moveaudit` check feeds it each of the five scenarios, so the classifier itself is
tested rather than trusted.

### 3c. The temporary trace (revision 2.13b-rev3)

The device run of 2.13b-rev2 closed objective A and left this:

```
pulsaciones 50  ·  solo 2 AgentUpdate serializados con FORWARD=1
0 cambios de posicion  ·  0 m de recorrido durante la ventana
```

So the block above needs a layer underneath it. `MovementAudit` now *also* prints,
temporarily, six lines that measure the window itself rather than the packets that
happened to fall inside it. The code is marked in the source as temporary and is to
be deleted once the cause is known.

```
  ventana FORWARD (temporal 2.13b-rev3), totales: ventanas N · paquetes con la tecla pulsada U (con AT_POS F · sin AT_POS X) · ventanas sin ningun AT_POS W · enviados DESPUES de soltar (dentro de 1200 ms) L (con AT_POS FL)
  ventana FORWARD (temporal), tiempos: duracion de la ultima ventana D ms · tiempo total con la tecla pulsada T ms · primer AT_POS t=A ms · ultimo AT_POS t=B ms · separacion S ms · intervalos entre AT_POS (i, i, …) ms
  ventana FORWARD (temporal), estado interno del motor: muestras N (con AT_POS M) · muestras con la ventana ABIERTA N2 (con AT_POS M2) · el motor SI tiene FORWARD en su estado con el boton pulsado
  ventana FORWARD (temporal), envios TARDIOS (sin ventana abierta y dentro de 1200 ms de un evento FORWARD): L (con AT_POS FL) · ultimos: +3 ms AT_POS · +3 ms sin
  ventana FORWARD (temporal), posiciones y secuencias: (dx,dy,dz) tras seq S · …
  ventana FORWARD (temporal), ultimas ventanas (dur, paquetes y AT_POS):
    #1  dur D ms  ·  paquetes con la tecla pulsada U (con AT_POS F · sin AT_POS X)  ·  intervalos entre AT_POS (i, …) ms
    …
  ventana FORWARD (temporal), traza (t=ms; P=pulsado S=soltado; interno=estado del motor; E=enviado; M=movimiento):
    t=0 P pulsado | t=0 interno AT_POS | t=0 E+ AT_POS seq 1 flag 0x00000001 (con la tecla pulsada) | t=41 S soltado | t=41 interno sin AT_POS | t=44 E- sin AT_POS seq 2 flag 0x00000000 (TARDE, 3 ms tras el evento) | …
```

What each line is for:

1. **`totales`** — the totals are over *every* window, not just the last one, so
   "50 presses produced 2 flagged packets" is visible as a number rather than
   inferred from one window.
2. **`tiempos`** — how long the window really lasted, how long the key was really
   held in total, and the cadence of the flagged packets (first, last, gaps), counted
   whether the send was in-window or late. If the 50 presses are taps of a few tens
   of milliseconds, the 200 ms tick can never fire inside one, which is itself half
   the answer.
3. **`estado interno del motor`** — the engine's own `controlFlags`, sampled at the
   pad events and on the movement tick, independently of anything being sent. The
   second pair is the decisive one: samples taken *while the window was open*, i.e.
   while the button was physically down. `M2 > 0` says the engine *does* hold FORWARD
   in its own state (so the loss is on the serialization side); `M2 == 0` says the
   state itself is never set, and the race is not the explanation.
4. **`envios TARDIOS`** — the sends that went out with no window open but within
   1200 ms of a FORWARD event, each with its delay and whether it carried `AT_POS`.
   This is the shape the hypothesis predicts: many late unflagged sends, few
   in-window flagged ones.
5. **`posiciones y secuencias`** — every position change with the sequence of the
   last packet sent before it, so a movement can be attributed to a send.
6. **`ultimas ventanas`** — the same numbers per window, so the pattern across taps
   is visible instead of one aggregate.
7. **`traza`** — the whole thing on one timeline, `t` in milliseconds from the first
   entry: `P`/`S` (pad), `interno …` (engine state), `E+`/`E-` (sent, with the exact
   serialized flag word and whether it was in-window or late), `M … tras seq S`
   (movement).

How to read it, in one line: if `interno` shows `AT_POS` between every `P` and its
`S` (so `M2 > 0`) while the in-window flagged count `F` stays small and `L`/`FL`
shows the packets arriving late, then the flag is set correctly and **lost between
the handler and the wire** — the async send reads a `controlFlags` the release
already cleared. If instead `M2` is 0, the state is cleared before any send could
see it, and the race is not the explanation.

The `moveaudit` check drives all six lines off the device, including the case this
revision exists for: a tap whose `AT_POS` packet is serialized *after* the release,
which must land in `forwardLateFlagged` and in a window counted as empty. That is the
device's "50 → 2" made deterministic.


---

## 4. One practical warning for the run

The `EPERM` in the previous device log was Android refusing `sendto` while the app
was in the background (battery saver / data saver / a screenshot break long
enough for the process to be restricted). While that state lasts, **every** send
fails — `AgentUpdate` and `StartPingCheck` both — so a movement test done while
the log is being read on another screen proves nothing about movement. Keep the
world screen in the foreground while pressing the pad, and read the report
afterwards from the file (`writeReport()`, button `Guardar`).

---

## 5. Reproducing the off-device part

```
const src = await fs.readTextFile("<repo>/tools/kcheck/run_all.js")
const mod = { exports: {} }
new Function("module", "exports", src)(mod, mod.exports)
await mod.exports({ fs, fetch, kcheck: "<repo>/tools/kcheck", appBase: "<repo>/app/src/main/" })
```

The `moveaudit` check lives in `tools/kcheck/extra/tests_render.kt` and is part of
the light group (the runner also emits it as its own `moveaudit` group, which is
how it was verified while the two large groups could not reach the backend). It
asserts: the control table against the reference; the
action→bit mapping; the encoded `AgentUpdate` byte for byte (122 bytes, field
offsets, body rotation, flags at 117, `Far` at 113, `Flags` at 121, id 4, High,
zerocoded); that the basis **actually serialized** in the packet is the reference
frame for the heading being sent, orthonormal (volume +1) and not the degenerate
M1 triple, which is still asserted as the *old* value; the split of the NUDGE bits
in the flag table (`hasNudge`, `NUDGE_MASK`, `describe(0x00080001)`); the FORWARD
window itself — that `U`/`F`/`X` count a three-packet press correctly, that the
first/last `AT_POS` packets and their positions are the ones the series carried,
that the per-change Δ lists, the path length and the net displacement are the ones
laid out, that `lastFlagsSent == 0` while `forwardWindowFlagged > 0` (the release
does *not* erase the press), and that `nudgeUpdates` stays 0; and the A–E
classifier, including the counters that feed it. Its output on a green run:

```
  MOVIMIENTO: base serializada (correccion M1 aplicada) at (0.76, 0.64, 0.00)  ·  izquierda (-0.64, 0.76, 0.00)  ·  arriba (0.00, 0.00, 1.00)
  MOVIMIENTO: base de referencia (rumbo 0) at (1.00, 0.00, 0.00)  ·  izquierda (0.00, 1.00, 0.00)  ·  arriba (0.00, 0.00, 1.00)
  MOVIMIENTO: base de la revision anterior (M1, ya no se envia) at (0.00, 0.00, -1.00)  ·  izquierda (0.00, 1.00, 0.00)  ·  arriba (0.00, 0.00, 1.00)  (NO es una base: volumen 0.00, at.arriba=-1.00)
  MOVIMIENTO: veredictos A/B/C/D/E/ninguno comprobados; cuerpo del AgentUpdate 122 bytes, ControlFlags en 117
```

(24 checks pass in the light group; the texture-side off-device harness for this
revision — 53 checks total, group `capsfail` proving the failure diagnostics —
lives in the same runner.)
