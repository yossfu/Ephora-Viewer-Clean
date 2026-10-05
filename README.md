# Ephora Viewer

A modern Android rebuild of **Lumiya** (the 2016 Second Life viewer,
`com.lumiyaviewer.lumiya` 3.4.2) that speaks the **real Second Life / OpenSim
protocol** — not a simulation — with a native **Google Filament** renderer.

* **App name:** Ephora Viewer
* **App id:** `com.ephora.viewer` (own identity, so it does not clash with the
  original Lumiya; code package stays `com.lumiyaviewer.lumiya`)
* **Repository:** https://github.com/yossfu/Ephora-Viewer
* Kotlin · AndroidX · Material 3 · coroutines · **Filament 1.75.1** ·
  **C++17 / NDK / CMake** (`slcore`) · Gradle Kotlin DSL
* **No `WebView` anywhere in the rendering path.**

---

## The graphics architecture

The renderer is *not* a Second Life renderer. Second Life's protocol is decoded
and interpreted first, then handed to a graphics engine through an
engine-agnostic interface:

```
Second Life network / protocol        slproto/            (untouched by this work)
  → SL world model                    world/WorldModel, ObjectUpdateDecoder
  → SL world layer                    slworld/            SLRegion, SLObject,
                                                          SLPrimitive, SLMesh,
                                                          SLTexture, SLAvatar
  → SL scene representation           slscene/            SLScene, SLCamera
  → Renderer interface                renderer/Renderer   (no engine types)
  → FilamentRenderer                  renderer/filament/
  → Filament 1.75.1
  → OpenGL ES 3.1  (Vulkan-ready)
```

The GL path is Filament's GLES backend: it needs OpenGL ES 3.0 to be present
(which the manifest requires) and uses 3.1 features where the device has them.
Nothing is written against a specific GL version by hand — that is exactly what
the Filament abstraction buys — so moving to Vulkan does not touch any code
above `FilamentRenderer`.

`Renderer.kt` is the seam. `slscene/` only ever calls it, so replacing Filament
means writing one more implementation of that interface — nothing in
`slproto/`, `world/`, `slworld/`, `slscene/` or the avatar/animation modules
would change. Filament never sees a packet, a UUID or a `TextureEntry`; it only
ever receives meshes, textures, materials, transforms and a camera.

The backend is chosen in one place (`FilamentBackend.OPENGL`), and the
`filamat` target API is set from the same value, so switching to Vulkan is a one
word change plus testing. Filament's GLES3 backend is used first because it is
the better-tested path on Android; nothing in the architecture assumes it.

Heavy work runs off the UI thread: every `Renderer` call happens on the
`SurfaceView`'s own render thread (`EphoraRender`), and geometry generation runs
on that same thread today and will move to a worker pool in the performance
phase (JPEG2000 decoding must never touch the UI thread).

## What actually works

| Feature | Status |
| --- | --- |
| LLSD login over HTTPS (`login.cgi`, incl. the `indeterminate` redirect handshake) | real |
| UDP circuit: sequence numbers, reliable delivery + resends, pending-ACK piggybacking, ACK messages | real |
| Zero-run compression (zerocoding) both directions | real |
| `message_template.msg` v2.0 parser → generic codec for all 475 messages | real |
| Circuit handshake: `UseCircuitCode` → ACK wait → `CompleteAgentMovement` → `RegionHandshake`/reply → `AgentThrottle` → `AgentDataUpdateRequest` | real |
| Keep-alive: `StartPingCheck`/`CompletePingCheck` both ways, 400 ms ACK flush, 1.2 s resend timeout | real |
| Local chat: send `ChatFromViewer`, receive `ChatFromSimulator` (incl. whisper/shout) | real |
| Avatar name, region name, initial position (`AgentMovementComplete`) | real |
| Live mini-map from `CoarseLocationUpdate` (real coarse avatar positions) | real |
| Movement: `AgentUpdate` control flags (walk / run / strafe / turn / jump / fly) | real |
| Session telemetry: ping, ACK backlog, bytes in/out | real |
| Seed capability → full capability map (HTTP LLSD POST, redirect/gzip/BOM safe) | real |
| Inventory, library, offline/online IMs, event queue (`EventQueueGet`) | real |
| Terrain `LayerData` codec: the real libopenmetaverse DCT/bit-packed format (verified to 0.15 m) | real |
| Object stream: `ObjectUpdate`, `ObjectUpdateCompressed`, `ImprovedTerseObjectUpdate`, `ObjectUpdateCached` (+`RequestMultipleObjects`), `KillObject` | real |
| Prim geometry: real `LLVolume` path/profile tessellation in C++ (`slcore`), 13 native checks (12 shapes + the legacy grass/tree meshes) | real |
| Filament renderer: `SurfaceView` + `Engine`/`Scene`/`View`/`Renderer`, sun + ambient, skybox, MSAA, frame stats (CPU/GPU/FPS) | real |
| Region objects drawn: `WorldModel` → `SLWorld` → `SLScene` → Filament entities, positioned from the simulator's own transforms | real |
| Per-face materials (one per `TextureEntry` face), camera-relative distance culling, mesh + material caching | real |
| Render diagnostics: one `@Volatile` record filled by the world, scene and renderer + an on-screen HUD + a periodic `EphoraDiag` logcat report | real |
| Object pipeline diagnostics: per-object log (first 20 objects, every field the region sent + the final classification), the first-real-prim route report, refusals with their reason and full stack trace, update-type counters, and "objects in front of the camera" | real |
| Filament start-up instrumentation: per-step `START/SUCCESS/FAIL`, the **full throwable** (class, message, cause chain, stack trace) on any failure, and the report written to `filesDir/ephora-filament.log` on its own | real |
| Native preflight: device ABIs, `nativeLibraryDir`, the APK's `lib/<abi>/*.so` entries, and `System.loadLibrary()` for `filament-jni`, `filamat-jni` and `slcore` individually | real |
| `FilamentProbeActivity`: PRUEBA A alone on its own screen (Activity + SurfaceView + Filament, no login, no SL, no `slcore`), with its report in `filesDir/ephora-probe.log` | debug |
| Diagnostic content modes: pure-Filament probe (PRUEBA A), test prim row, terrain only (PRUEBA C), region (PRUEBA B) | debug |
| Debug terrain mesh built from the real received `LayerData` height field, solid colour (the real terrain textures are phase 6) | debug |
| "Force one real region object 6 m in front of the camera" hook, to separate coordinate bugs from "nothing draws at all" | debug |
| World bounding box + camera framing of the whole region (MIN/MAX/CENTRO, is the centre in front of the camera) | debug |
| `TextureEntry` decoded per face: texture UUID, tint+alpha, UV repeat/offset/rotation, bump/shiny/fullbright, media flags, glow, render-material UUID | real, applied per face (2.13b) |
| Texture download: `GetTexture` capability → worker queue → cache → decode stage → GPU upload → per-face material, with the counters of every stage | pipeline real (2.13b); **no JPEG2000 decoder yet**, so faces keep their tint |
| JPEG2000 decoding via OpenJPEG (one `TextureDecoder` implementation + one line at the call site) | 2.13c |
| Texture cache eviction / LRU, distance-based discard level, memory budget | phase 4 |
| `GetMesh` mesh assets, mesh LODs, terrain system, adjacent regions/teleports | phases 5–7 |
| Avatars (appearance, skeleton, wearables, attachments) | phase 8 |
| Animation assets, mixer, blending, priority | phase 9 |
| WindLight: simulator sun/moon/sky, environment parameters | phase 10 |
| Frustum culling, LOD, pooling, streaming, budgets, profiling | phase 11 |

Two rules are enforced in the code, not just in the docs: **no real Second Life
data is ever replaced with invented data to hide a bug**, and **no avatar is
drawn as a stand-in** — there is no capsule, and `SLAvatar` only ever holds what
the region actually sent. The debug placeholder box that used to stand in for a
prim whose geometry could not be generated is **gone** (PRUEBA A already proves
Filament draws): such an object is reported as *sin geometría* with the reason
and the rest of the region is built normally.

If login succeeds but the UDP circuit never gets an ACK, the app says so
explicitly — on mobile networks that block outbound UDP there is nothing a viewer
can do about it, and a clear message beats a silent hang.

## The 3D screens

**Mundo 3D** (main screen) opens the live region: a `SurfaceView` whose render
thread creates the Filament engine, binds the swap chain, and each frame pulls
whatever changed out of `WorldModel`, turns it into scene objects, and draws.

**Prueba 3D (Fase 1)** (`ui/world/RenderTestActivity.kt`) shows the fixed
geometry test pattern instead of the region — one upright and one tilted copy of
ten real prim parameter sets (box, cylinder, prism, sphere, torus, tube, ring,
cut/tapered box, hollow cylinder, flexible sphere). It needs no login, so it
proves the whole chain (SL parameters → `slcore` → `SLMesh` → `SLScene` →
`FilamentRenderer` → Filament) on a device with the engine's own frame timings
on screen.

* **Controls:** drag = orbit, pinch = zoom, `Centrar cámara` = face where the
  avatar faces; the movement pad sends real `AgentUpdate` control flags.
* **Objects** lists what the simulator has actually sent (name, kind, distance).
* The camera is Z-up like Second Life's world (X east, Y north, Z up) and
  `SLCamera` produces a plain `CameraDesc`; the backend converts.

### The diagnostic row (this phase)

Both 3D screens have four extra buttons, because "the screen is black" can come
from any of seven layers and the only useful answer is *which one*:

* **Modo** cycles the content: `region` (everything the simulator sent, PRUEBA
  B) → `terreno` (the region's ground alone, PRUEBA C) → `prims` (the fixed test
  row, no login needed) → `probe` (geometry built by the renderer itself, no
  Second Life data at all, PRUEBA A). Switching clears the scene and rebuilds it,
  so each mode is isolated.
* **Encuadre** switches the camera between `agente` (orbit the agent, the normal
  behaviour) and `mundo` (frame the whole received bounding box, so "the camera
  was somewhere with nothing in front of it" cannot look like "nothing drew").
* **Forzar** takes the nearest object that actually has an entity, keeps its real
  mesh/scale/material, and parks it 6 m straight ahead of the camera. If it
  appears, the pipeline works and the fault is in the coordinate conversion; if
  it does not, the fault is below the scene layer. Turning it off restores the
  object's real transform.
* **DIAG** shows the full report on screen (monospace, selectable) — the same
  lines that go to logcat.
* **Probe cámara** (phase 2.9) draws one bright fullbright cube 3 m in front of
  the camera, built by the viewer and using no Second Life data. If it shows and
  the prims do not, the fault is in the SL transforms; if it does not show
  either, the fault is below the scene layer.
* **Ver prim** (phase 2.9) pins the camera to `prim + offset` looking straight at
  the nearest real prim within 50 m. The object is never moved; the report
  records the frustum test before and after, so "it should be on screen now" is
  checkable.
* **FOCO** (phase 2.10) follows one object by its local id — typed in, or tapped
  in the *objects nearby* list. It changes nothing about how the object is
  drawn: it turns on the parser's per-update history for that id, the scene's
  entity-event trace, and the report then prints the object's whole chain
  (`SL → Transform → renderer → engine`) beside a healthy object's. Since phase
  2.11 the same `DIAG` report also carries the parent/child resolution
  (`--- PARENT/CHILD resuelto (fase 2.11) ---`): the counters and the
  `parentLocalId → Transform.parent → Filament parentEntity` line for the first
  ten children, plus the children still waiting for a parent that has no entity.
* **Culling / Distancia / Sombras / LOD** (phase 2.12) are the A/B switches of
  the scalability work. Each is applied by the render thread on its next frame,
  each logs what it did, and each is reversible, so a measurement differs from
  its baseline by that one flag and nothing else. *Culling* is the engine's own
  frustum test (entities stay in the scene; only their submission is skipped).
  *Distancia* turns the draw-distance culling on or off. *Sombras* turns the whole
  shadow pass on or off. *LOD* stops far prims from *casting* shadows. Their
  state, and the counters behind them (renderables in scene / visible / culled by
  frustum / culled by distance), are in the HUD and the `DIAG` report.

The report is written to logcat under the tag **`EphoraDiag`**: a full report on
the first presented frame, one compact line every 5 s, and the whole report every
30 s or on failure. `adb logcat -s EphoraDiag` is the one command worth running
after a black screen.

## Build it (no Android Studio needed)

1. Put `SUBIR-A-GITHUB.bat` next to `ephora-viewer.zip` and double-click it.
   It unzips the project and pushes it to
   `https://github.com/yossfu/Ephora-Viewer` (the URL is already baked in — it
   does **not** ask). To push somewhere else, create `repo.txt` next to the
   `.bat` containing the other URL.
2. GitHub Actions (`.github/workflows/build.yml`) installs the NDK + CMake,
   **compiles and runs the native geometry self-test as a gate**, then builds
   with JDK 17 and Gradle 8.10.2 and uploads two artifacts:
   * `ephora-viewer-debug-apk` — install this one.
   * `ephora-viewer-release-apk` — same code, also debug-signed so it can be
     side-loaded.

   The Gradle part runs one phase at a time — `:app:externalNativeBuildDebug`
   (the NDK/CMake build of `slcore`), then `assembleDebug`, then
   `assembleRelease` — and stops at the first failure, so the failing phase is
   named in the job's *Annotations* panel and in the step summary instead of
   being buried in one long log.
3. Download the artifact, unzip it, install the `.apk` (allow "install from
   unknown sources"), open **Ephora Viewer**.

Local build with the Android SDK instead: `gradle assembleDebug`
(requires `ndk;26.1.10909125` and `cmake;3.22.1`).

## How to connect

1. Pick a grid: **Second Life (Agni)**, **Second Life Beta (Aditi)**, **OpenSim
   local**, or *Otro (URL personalizada)* and type any login URL such as
   `http://mi-grid.example:9000/`.
2. Enter the SL **first name + last name** (for OpenSim grids that use a single
   username, put it in the first field) and the password.
3. The password is sent only to the login server, over HTTPS, and is never stored.

## Diagnostics

**Ajustes → Auto-test del protocolo**, or **Diagnóstico → Auto-test**, runs the
wire layer offline and prints a pass/fail line per check (template load, message
ids, zerocode round-trip, `ChatFromViewer` encode/decode, UDP framing, appended
ACKs, UUID, LLSD XML/notation/binary, an instant message decoded from its LLSD
event form, and the terrain codec). Copy the output if you need to report a
problem. The same screen shows the live session log.

### Reading the session log

| Line | Meaning |
| --- | --- |
| `Capabilities disponibles: N endpoints` | the seed capability answered |
| `Sin capabilities: <reason>` | the seed request failed; the reason names the URL, status, content type and first bytes |
| `Mundo: N objetos, M avatares, K sin posicion` | what the simulator has actually sent |
| `No se pudo decodificar <Msg>: <Bloque.campo (offset/limite)>` | a packet the codec could not read; the named field ran out of bytes |
| `Terreno recibido: N parches, alturas x .. y m` | the heightmap decoded |
| `El sistema bloqueo el envio de paquetes (EPERM...)` | Android blocked outbound UDP (backgrounded/battery saver); the app runs as a foreground service, and should also be set to *Sin restricciones* in *Ajustes → Batería* |
| `Fallo del renderizador: <reason>` | the render thread died (HUD text on the 3D screen); usually a Filament/GL driver problem |
| `Cola de eventos: <reason>` | the IM/event long poll could not reach the capability; it retries with backoff |
| `primer frame presentado` (tag `EphoraDiag`) | `beginFrame` succeeded at least once, so a swap chain exists and the driver accepted the frame; everything above it is a report of what was submitted |
| `objeto real #N forzado a 6.0 m delante de la camara` (tag `EphoraDiag`) | the "Forzar" hook moved a real region object in front of the camera |

An EventQueue failure (HTTP 500/502 from `/EventQueueGet`) is reported as its own
line and does **not** stop the world: objects and terrain arrive over UDP, and the
two are deliberately independent. It is retried with backoff.

### The three acceptance tests (A / B / C)

The phase this instrumentation was built for asks three separate yes/no
questions, and the DIAG panel answers each with the counters behind it:

* **PRUEBA A — pure Filament.** `Modo: probe` builds three shapes and a ground
  plane out of nothing but vertex data inside `SLDiagnosticProbe` and pushes them
  through the same `Renderer` the region uses. If this is black, nothing about
  Second Life can be blamed: the fault is `SurfaceView → SwapChain → Renderer →
  View → Scene → Camera → present`. The probe holds a lit cube (needs the sun and
  the ambient term), a fullbright cube (ignores lighting), a high-vertex sphere
  (exercises the vertex/index buffers) and a 60 m ground plane (so "everything is
  off screen" cannot hide). There is now also a **standalone** version of PRUEBA
  A that shares nothing with the viewer at all — see "Filament start-up" below —
  reachable from the first screen without logging in.
* **PRUEBA B — a real region object.** `Modo: region`. The DIAG line counts the
  objects the region sent, the entities they became, the renderables, the
  geometry failures and the bounding box, and dumps one real object's full wire
  fields (PCode, position, rotation, scale, `pathCurve`, `profileCurve`,
  `TextureEntry` UUID, `ParentID`) next to the vertex/index counts of the mesh
  that was built from them.
* **PRUEBA C — the real region terrain.** `Modo: terreno`. The region's received
  `LayerData` height field becomes a triangle mesh (decimated by `SLTerrain`) with
  a solid debug colour. Patches received, vertices, triangles and rebuilds are
  counted.

A black screen is then attributable rather than mysterious: **only A** → the
Filament/surface layer; **A but not B** → the `ObjectUpdate → SLObject →
SLPrimitive → MeshData → VertexBuffer → IndexBuffer → Material → Renderable →
Entity → Scene.addEntity()` path (the per-object dump says where); **A and B but
not C** → the terrain path; **A and C but not B** → the prim path.

### Filament start-up: why `engine NO` happened, and what changed

The device reports that motivated this part were all of one shape — `engine NO`,
`renderer NO`, `Scene NO`, `view NO`, `camera NO`, `Swapchain NO`,
`Viewport 0x0`, `Surface 0x0`, `0 frames` — with the whole protocol layer below
it working (UDP circuit, `RegionHandshake`, terrain, 547 objects, 1 avatar). The
app reported only

```
ERROR en createRenderer: com.lumiyaviewer.lumiya.renderer.filament.FilamentRenderer
```

which is the *name of a class*, not a throwable — the signature of
`NoClassDefFoundError` on Android, whose message is the failing class.

**Cause, from Filament's own 1.75.1 sources:** `Filament.init()` is empty; the
library is loaded by `Filament`'s **static initializer**
(`System.loadLibrary("filament-jni")`). `FilamentRenderer`'s samplers lived in
its `companion object`

```kotlin
private companion object {
    val mipmappedSampler = TextureSampler(...)   // ← runs during <clinit>
    val plainSampler = TextureSampler(...)
}
```

and a Kotlin `companion object`'s properties are initialized by the class's
`<clinit>`, which runs **before the constructor body** — i.e. before the
`Filament.init()` inside it. `TextureSampler`'s constructor calls native
`nCreateSampler` immediately, so:

```
TextureSampler.<init> → nCreateSampler → UnsatisfiedLinkError
  → ExceptionInInitializerError (FilamentRenderer's <clinit> fails)
  → the class is permanently erroneous
  → every retry: NoClassDefFoundError: com.lumiyaviewer.lumiya.renderer.filament.FilamentRenderer
```

`EntityManager.get()` was in the same trap (`EntityManager`'s initializer calls
`nGetEntityManager`). One failed class initializer is unrecoverable for the whole
process, which is why *everything* read `NO` and no frame was ever attempted —
and why the on-screen text named the class instead of the exception.

**The two rules that follow, now enforced:**

1. `Filament.init()` runs at **application start**, from `FilamentBootstrap`
   (`EphoraApp.onCreate`), on a path no rendering code can jump ahead of. Every
   other entry point calls `FilamentBootstrap.ensureLoaded()` first anyway.
2. **No Filament object is ever constructed in a class initializer** — not in a
   `companion object`, not in a top-level `val`, not in a field initializer
   declared before `init`. Filament objects are built inside constructors or
   methods, after `ensureLoaded()`. `bootstrapCheck` in the Kotlin harness is the
   regression guard, and the same rule is stated in the README section "Notes for
   whoever touches this next".

**What the app now reports instead.** Every start-up step is wrapped in
`FilamentRenderer.step(name) { ... }` / `FilamentProbeActivity`'s identical
helper, which records `START` / `SUCCESS` / `FAIL`, and on failure
`RenderDiagnostics.failDetailed` stores the **whole throwable** — class, message,
cause chain (`Caused by`) and the complete stack trace, not one sentence. The
steps are `FILAMENT_INIT → ENGINE_CREATE → ENGINE_VALID → ENTITY_MANAGER →
RENDERER_CREATE → SCENE_CREATE → VIEW_CREATE → CAMERA_CREATE →
TRANSFORM/RENDERABLE/LIGHT_MANAGER → SAMPLER_MIPMAPPED → SAMPLER_PLAIN →
MATERIAL_COMPILER → VIEW_CONFIGURED → FALLBACK_TEXTURE → FALLBACK_MATERIAL →
LIGHTS → RENDERER_READY`, plus `SURFACE_*` and `SWAPCHAIN_*` from the surface
handshake. The first failure is kept as *the* error, the last one is shown
separately (a failed class initializer repeats its own name on every retry, which
is the symptom, not the cause), and the full text is written to
`filesDir/ephora-filament.log` (world view) or `filesDir/ephora-probe.log` (the
probe) **automatically on the first failure**, so it can be copied off the device
without adb. It is also logged under the tag `FilamentBootstrap`.

**`FilamentBootstrap.preflight`** answers the other half of the question —
whether the native libraries can actually load, rather than whether the APK
contains a file with that name. It records: manufacturer/model/API level, the
device's `Build.SUPPORTED_ABIS` (and the primary one), the `nativeLibraryDir`
contents, the `lib/<abi>/*.so` entries inside the installed APK (base APK plus
any splits), the `lib/` entries for every packaged ABI, and then
`System.loadLibrary()` for `filament-jni`, `filamat-jni` and `slcore`
**individually**, each with its own full throwable. The Filament version the
build targets (`1.75.1`) and the list of libraries it looks for are printed too.

**`FilamentProbeActivity` — PRUEBA A on its own screen.** A completely
independent `Activity` (`ui/diag/FilamentProbeActivity.kt`) with its own
`SurfaceView`, its own thread and nothing else: no login, no UDP, no
`EventQueue`, no `SLWorld`, no `SLScene`, no avatars, no terrain, no
`TextureEntry`, no JPEG2000, and **no `slcore`** — it does not even import the
Second Life packages. It creates the engine, renderer, scene, view, camera and
swap chain itself, compiles its own tiny unlit material with `filamat`, uploads
one triangle of three vertices, and draws it. Its status line is always on
screen (`FPS`, `frames`, `viewport`, and YES/NO for engine/renderer/scene/view/
camera/swapchain and the `beginFrame`/`render`/`endFrame` counters); "Ver
informe" expands the full start-up log, "Guardar" writes it to disk and
"Reiniciar" tears everything down and runs the whole sequence again. It is
reachable two ways, both without logging in: **"Probe Filament (sin login)"** on
the first screen, and

```
adb shell am start -n com.ephora.viewer/com.lumiyaviewer.lumiya.ui.diag.FilamentProbeActivity
```

The surface handshake is instrumented to the letter of the request:
`onAttachedToWindow`/`onDetachedFromWindow`, and
`surfaceCreated`/`surfaceChanged`/`surfaceDestroyed` with the validity, size,
format and callback thread. The screen writes either
`SURFACE CREATED  width = W  height = H` or
`SURFACE NOT CREATED  valida=false  0x0`, and **no swap chain is created until
the surface is valid and has a size**; when the surface goes away the swap chain
is destroyed first.

**If the engine starts but the surface stays `0x0`**, the rule is the surface
path — `Activity → SurfaceView → SurfaceHolder → surfaceCreated/surfaceChanged →
SwapChain` — and the report now says which of those never happened (a
`SURFACE_BIND NO (...)` line plus the callback counters) instead of leaving it to
be guessed. Second Life is not investigated until the probe shows
`beginFrame`, `render`, `endFrame` and `FPS` all above zero.

### Region objects: why 523 objects became 0 prims and 1 renderable

The first device run of region mode printed, with Filament itself provably fine
(the probe drew 4 entities / 506 triangles at 58 FPS):

```
SL clases: 0 prims, 522 arboles, 1 avatares
SL escena: 510 entidades ... solo 1 Renderable creado
ERROR en buildRenderable: length=0; index=-2
```

Two independent bugs, both in the object pipeline, both found by reading the
protocol sources rather than by guessing:

**1. The `PCode` constants were wrong, so every prim was classified as a tree.**
`SceneObject` declared `PCODE_PRIM = 6` and `PCODE_TREE = 9`. The wire values
(`indra/llmath/llvolume.h`, cross-checked against LibreMetaverse's `PCode`
enum) are:

| pcode | constant | meaning |
| --- | --- | --- |
| 9 | `LL_PCODE_VOLUME` | **every ordinary prim** |
| 47 | `LL_PCODE_LEGACY_AVATAR` | avatar |
| 95 | `LL_PCODE_LEGACY_GRASS` | grass |
| 111 | `LL_PCODE_TREE_NEW` | new tree |
| 143 | `LL_PCODE_LEGACY_PART_SYS` | particle system |
| 159 | `LL_PCODE_LEGACY_ROCK` | rock |
| 255 | `LL_PCODE_LEGACY_TREE` | old (single-billboard) tree |

So a real prim's pcode (`9`) matched the *tree* constant: 522 prims were
classified `TREE`, given `SLMeshLibrary.fixedKindOf`'s legacy tree mesh, and
reported in the HUD as "arboles". The one avatar was right only because 47
happened to be correct. `SceneObject` now carries the real values, `isPrim` /
`isGrass` / `isTree` are distinct predicates (the old `isTree` also swallowed
grass), and `SLObjectKind.of` classifies *anything that is not avatar, grass or
tree* as `PRIM` — the shape (box / cylinder / torus / …) still comes only from
`(pathCurve, profileCurve, profileHollow)`, never from the pcode.

**2. The legacy grass/tree meshes had no face group, and `createEntity` indexed
`faceGroups[-2]`.** `slcore`'s `buildGrassMesh()`/`buildTreeMesh()` filled
vertices and indices but never called `Builder::beginFace`/`endFace`, so
`MeshData.faces` was empty and the JNI hand-off produced a zero-length
`faceGroups` array. Kotlin's `MeshDesc.faceCount` is `faceGroups.size / 3`, i.e.
0, and `FilamentRenderer.createEntity` did
`faceGroups[minOf(i, faceCount - 1) * 3 + 1]` = `faceGroups[-1 * 3 + 1]` =
`faceGroups[-2]` on an array of length 0 — ART's exact
`ArrayIndexOutOfBoundsException: length=0; index=-2`. Every grass/tree object
threw inside `builder.build()`, the `catch` returned `EntityHandle(0)`, and
`SLScene` stored a slot anyway, which is how 510 "entities" coexisted with 1
renderable.

Both are fixed, and every layer now refuses to pass the problem on:

* `sl_prim_geometry.cpp` wraps the grass mesh in one face group and the tree in
  two (trunk, canopy) and assigns `mesh.faces`; `slcore_tests.cpp` gained
  `testLegacyFixedMeshes()`, which runs the full `checkMesh` invariants (unit
  normals, in-range indices, face groups covering every index) on grass and all
  three tree variants.
* `MeshDesc.geometryProblem()` is the single validation gate (empty vertices,
  non-multiple-of-8 stride, no indices, indices not a multiple of three, empty
  or malformed face groups, a face range past the index buffer, indices out of
  range, NaN/infinite vertices). Each message names a *different* generator bug.
  `MeshDesc.geometryWarning()` covers the one defect that must **not** hide an
  object: indices no face group claims. Those triangles are simply never
  submitted while everything the groups cover draws normally, so the mesh is
  accepted, the warning is counted (`geometryWarnings` in the HUD) and logged —
  rejecting it would turn a generator bug into a missing prim, which is the
  failure mode this phase exists to remove.
* `PrimGeometryNative.toMeshDesc` synthesises one whole-buffer face group when
  the native side returns none — the correct reading of "this mesh has a single
  face" — and **counts it** (`faceGroupFallbacks`, shown in the HUD) so the
  generator bug stays visible instead of being silently papered over.
* `FilamentRenderer.createEntity` validates before building and returns
  `EntityHandle.INVALID` (0, never a live id) instead of throwing; the catch path
  now uses `failDetailed` so the **full stack trace** reaches the report, and
  each refusal is named with its mesh counts, transform and face count.
* `SLScene.attach` never stores a slot for an invalid handle, counts the refusal,
  logs the object's whole field dump (first 5) and keeps building the rest — the
  acceptance rule "one bad prim must not stop the region" is now enforced by a
  test (`reject`) with a renderer that refuses everything and then accepts.

**Also fixed while verifying the same path:** `ObjectUpdateCompressed` was being
read with a **4-byte-shifted, incomplete layout** (`pcode, state, material,
clickAction, scale` — the real blob has a CRC word between `state` and
`material`, its flag bits are `ScratchPad 0x01 / Tree 0x02 / HasText 0x04 /
HasParticles 0x08 / HasSound 0x10 / HasParent 0x20 / TextureAnim 0x40 /
AngularVelocity 0x80 / NameValues 0x100 / MediaURL 0x200` — not 0x01/0x02/0x10/
0x20 — and its optional blocks, extra parameters, path/profile values and
`TextureEntry` were never read at all). It now follows LL's
`processObjectUpdateCompressed` order exactly, records the `TextureEntry` length
and any `Sculpt`/`Mesh` extra parameter, and treats a blob too short to carry the
path/profile block as a **placement-only** update: the object is moved but
`paramsKnown` stays false and `SLObject.from` refuses to build a primitive from
defaults. Inventing a shape there would have been the same class of bug as the
tree misclassification.

Two more things exist purely so the next device report can settle the question
without another round trip:

* **A per-object log** (`SLScene.objectLine`, first 20 distinct objects, capped
  and counted so truncation is visible) prints UUID, local id, pcode *and its
  wire name*, State, parent, position, scale, rotation, `TextureEntry` length +
  texture UUID + tint + fullbright, `ExtraParams` length and sculpt state, the
  path/profile values with the derived shape name, the mesh's
  vertices/indices/triangles/faces/cache key, and the final classification with
  the entity it became.
* **A first-real-prim report** (`RenderDiagnostics.firstPrimReport`) is filled in
  once, for the first prim that is not an avatar, a tree, grass or an attachment
  *and* that really produced a renderable: received → classified → parameters →
  geometry → material → renderable → entity in the scene → counters. It is never
  produced from a substituted test shape. If instead the first such prim is
  *refused*, `firstPrimFailure` records that separately (`Primer prim SL real
  RECHAZADO`), so "the region sent no prims" and "the first prim failed" can never
  look the same.

The "is anything in front of the camera" question is now answered properly too:
`RenderDiagnostics.objectsAhead` counts entities with a positive dot against the
camera's forward axis (out of every entity in the scene), because the *centre of
the region's bounding box* is a poor proxy — a region is 256 m across and its
centre is regularly behind a viewer standing near an edge. The camera itself now
starts 6 m from the agent at a shallow pitch, and when the agent's own position
has not arrived yet it aims at the nearest object with geometry instead of at the
region's centre, so the first frame already looks at something real.

### Why every object read "(cylinder)", and the 113-byte compressed block

The next device run confirmed the graphics path (Filament start-up OK, OpenGL ES
at 59.5 FPS, terrain 256 patches / 32 768 triangles, 13 prims with real geometry
and Renderables in the scene) and produced two findings that looked unrelated:

```
Error procesando ObjectUpdateCompressed: length=113; index=-872415225
Objetos: 22 m (cylinder), 23 m df8e228f-... (cylinder), ...  <- every object, always
9 ObjectUpdate completos, 56 comprimidos, 1973 terse, 332 "comprimidos sin parametros"
```

They are the same bug, and it is the reason only the objects that arrived in a
*full* `ObjectUpdate` ever got geometry.

**1. `ExtraParams` was read as "the whole rest of the blob".** The compressed
decoder did `val extraParams = reader.bytes(reader.remaining)`: it swallowed the
path/profile block and the `TextureEntry` that LL's
`processObjectUpdateCompressed` places *after* the extra parameters, then walked
those bytes as if they were extra-parameter entries. Two consequences:

* `reader.remaining` was always 0 afterwards, so the `remaining < 26` test was
  always true: **every** compressed update took the "placement only" branch. The
  path/profile block was never read, `paramsKnown` stayed false, and the object
  could not be drawn. That is the 332 "comprimidos sin parametros".
* The entry walk read a `U32` length out of the *parameter* bytes. A length is
  unsigned: `0xCC000007` is 3 422 552 071 bytes, and `ByteReader.skip()` moved the
  cursor to `position + count`, i.e. **before the array**, and the next read
  indexed it — ART's `ArrayIndexOutOfBoundsException: length=113;
  index=-872415225`, the index being the wire value itself. The original code
  even *caught* it per packet and continued, so the object silently lost the
  update.

Fixed: the section is walked entry by entry (`U8 count`, then `U16 type`,
`U32 length`, that many bytes), a length that does not fit in what is left is
**refused and reported** instead of becoming a cursor position, every entry is
aligned to its declared end whatever was read from it, and if the section cannot
be walked to its end the decoder refuses to *guess* where the path/profile block
starts — it keeps the definition the object already had and says so. `ByteReader`
itself can no longer leave the buffer: a negative or oversized `skip`, a `bytes()`
count or a `seek` is counted and named (`readerFirstViolation`), never applied.

**2. `COMPRESSED_PARAMS_SIZE` was one byte short (26, actually 27).** The
path/profile block is 23 bytes (`PathCurve(1) PathBegin(2) PathEnd(2)
PathScaleX(1) PathScaleY(1) PathShearX(1) PathShearY(1) PathTwist(1)
PathTwistBegin(1) PathRadiusOffset(1) PathTaperX(1) PathTaperY(1)
PathRevolutions(1) PathSkew(1) ProfileCurve(1) ProfileBegin(2) ProfileEnd(2)
ProfileHollow(2)`) plus the 4-byte `TextureEntry` length. A block with exactly 26
bytes left passed the test and then read the `TextureEntry` length past the end.
The headless check that builds a real 113-byte block is what caught it.

**3. The "(cylinder)" label was a silent default, not a decode result.** The
object list rendered `sceneObject.shape.name.lowercase()`, and `SceneObject.shape`
consulted `PrimShapeClassifier` unconditionally — but a `SceneObject` that has
never received path/profile still carries the constructor's placeholder values
`pathCurve = 16` (`PATH_LINE`), `profileCurve = 0` (`PROFILE_CIRCLE`), which are
literally SL's *default cylinder*. So every prim whose definition had been lost
by bug 1 was *labelled* a cylinder. The classifier and its constants were right
(`LL_PCODE_PROFILE_*` in the low nibble, the path type in the high nibble of
`PathCurve` — see `llvolume.h`); the mistake was consulting it without a
definition.

Both paths now read one function: `SceneObject.shape` returns
`PrimShape.UNKNOWN` unless `paramsKnown`, `shapeText` renders
`UNKNOWN/MISSING_SHAPE` when no definition arrived, and **both** the object list
(`WorldViewActivity.showNearby`) and the scene (`SLScene.objectLine`, the
per-object table) print exactly that. A check asserts the two agree object by
object (`samesource`), so they cannot drift apart again.

#### The incremental object model

`SceneObject` **is** the per-`LocalID` accumulative state (`WorldModel.getOrCreate`
returns the existing record; `WorldModel.put` bumps a monotonic `revision`), and
each update now merges through two named operations instead of raw assignments:

* `noteShapeReceived(UpdateSource)` — the update carried a path/profile block
  (`FULL` / `COMPRESSED` / `LOCAL`); sets `paramsKnown`, records the provenance.
* `noteUpdateWithoutShape(UpdateSource)` — the update carried **no** definition
  (terse movement, or a compressed block whose parameter section was unreadable
  or truncated). It touches placement, counts the update, and marks the existing
  definition as `PERSISTED`. It never clears, resets or substitutes anything —
  there is no `clearShape()`, no default write and no `path/profile = null`
  anywhere in the decoder.

Provenance is explicit (`ShapeSource`: `MISSING` / `RECEIVED_FULL` /
`RECEIVED_COMPRESSED` / `PERSISTED` / `LOCAL` / `UNRECORDED`) and
`hasCompleteShape` is the predicate the renderer and the reports use: a prim is
complete when a definition arrived, while trees, grass and avatars are complete
by class. `SceneObject.shapeIsFallback` is the invariant that must stay false — a
prim labelled with a shape while no definition is stored.

Counters are now separated so "548 prims recibidos" can never be read as "13
convertidos":

| counter | where it comes from |
| --- | --- |
| `objectsReceived` | `WorldModel.shapeCounts().received` |
| `objectsWithCompleteShape`, `objectsWithPartialState`, `objectsWithMissingShape` | `WorldModel.shapeCounts()` |
| `objectsUsingShapeFallback` | must be 0; computed from stored state, not asserted |
| `objectsWithPersistedShape` | objects whose earlier definition survived a partial update |
| `objectsInsideDrawDistance` | `SLScene.visibleEntities` |
| `geometryBuildAttempted` | distinct prims that reached `meshFor` |
| `geometryBuildSucceeded` | distinct prims for which the generator returned a mesh |
| `renderableCreated` | distinct prims whose entity was created |
| `renderableInScene` | entities currently in the scene |
| `visibleInFrustum` | in front of the camera, inside the draw radius |

#### What the report now carries (spec §1, §3, §4)

* **A per-field offset walk** of the first compressed block that decodes
  cleanly: `Campo [inicio..fin] = valor`, one line per field, so the layout the
  region actually sent can be compared against the decoder's assumption. On a
  failure the same walk is printed for the *failing* block together with the
  field being decoded, the offset, the pcode, the local id, the flag word, a hex
  preview of the first 48 bytes and the **full stack trace**. Failures go to
  logcat with `Log.e` and into `ObjectUpdateDiagnostics` (4 kept in full, the
  rest counted); a bad block no longer disappears silently.
* **Per-object state transitions** for the first 5 objects that carry geometry:
  `update #n <tipo>: campos=... ; forma <antes> -> <despues>`, including the
  terse updates that arrive for those objects — the evidence that a
  movement-only update leaves the definition alone.
* **A per-object shape table** (`SLScene.shapeTable`, 40 rows nearest-first,
  rebuilt at the HUD's own 400 ms rhythm): localID, UUID, pcode+kind, the shape
  the list shows, `formaCompleta`, the provenance, `pathCurve` / `profileCurve` /
  `hollow`, the last update type, `geomIntentada` / `geomGenerada` /
  `renderable` / `enScene` and the distance to the camera (plus the reason when
  there is no entity).
* **A device-runnable self-test** (`DiagnosticsActivity` -> *Protocol
  self-test*): the 113-byte block is decoded, the incremental transition is
  exercised (terse -> compressed -> terse) and the bogus `0xCC000007` length is
  thrown at the parser — with no region and no network, on the build that is
  actually installed.

#### The ramp (spec §10)

`SLScene.maxPrimEntities` (default `0` = no limit) draws only the N nearest
objects, and `SLScene.budgetHidden` reports how many the budget hid, so the
pipeline can be exercised with 1, 10, 50 and then all objects. Normal operation
does not cap anything.

#### Still open (next phases)

* `ImprovedTerseObjectUpdate`'s `TextureEntry` is read from offset 0, while
  LibreMetaverse skips the first four bytes (the face count) — see "FIXME: Why
  are we ignoring the first four bytes" in `ObjectManager.cs`. It only affects
  the texture id/tint of terse updates, so it belongs to the texture phase (3).
* `ObjectUpdateCached` asks the simulator for the full update of objects it
  believes we hold; the request path is a separate phase.
* `maxDrawDistance` is 300 m while a region is 256 m across, so distance culling
  only ever excludes objects of the neighbouring region.

### Is a prim that is in the Scene actually on screen? (phase 2.9)

Phase 2.8 settled the pipeline's *counters*: every object with a complete shape,
every prim with geometry, a renderable for each and all of them in the Scene,
with varied shapes (`SPHERE`, `TORUS`, `CYLINDER`, `TUBE`, `RING`, `PRISM`) and
over a thousand frames presented. None of those numbers can say whether an
entity is inside the camera's picture — and that is the only question this phase
asks. It changes **no** protocol decoding, no geometry, no material, no texture,
no terrain, no attachment handling and no Filament initialisation.

#### The trace: `SL position → entity matrix → camera → frustum`

`SLScene.cameraAudit(renderer, limit)` writes the camera the scene sends *and*
the camera the engine reports, then one block per object for the nearest
`FilamentWorldView.AUDIT_ROWS` (eight), nearest-first, with:

* the SL position, scale and rotation, the shape text/provenance and
  `hasCompleteShape`;
* the entity handle, whether the renderer still holds it, and whether the scene
  has it visible;
* the transform **three ways**: the matrix the scene built
  (`Transform.toMatrix16`), the local matrix read back out of
  `TransformManager.getTransform`, and the world matrix from
  `getWorldTransform` — with `aplicadaVsEscena=IGUAL` and the numeric difference,
  so "the transform reached the engine" is a fact and not an assumption;
* the mesh bounds, and the bounding-sphere radius with the object's scale
  applied;
* the distance to the camera and the rough band it falls in;
* the frustum test **twice**: once from the scene's own basis maths
  (`CameraFrustum`) and once by multiplying the point by the camera's own view
  and projection matrices (`MatrixFrustum`), each with depth, NDC and where on
  the screen it lands (`pantalla=(56%, 67%)`). The two are independent
  implementations; `acuerdo=SI` plus `diffDepth`/`diffNdc` is what makes the
  answer trustworthy. A disagreement means the engine is not using the camera
  the scene set;
* when the centre is outside but the bounding sphere still cuts the frustum, a
  note saying so — a 20 m prim whose centre is 12 m off-axis is half on screen.

A negative depth is reported as `DETRAS_DE_LA_CAMARA`, and the other five ways a
point can miss (`MAS_CERCA_QUE_NEAR`, `MAS_LEJOS_QUE_FAR`, `FUERA_POR_IZQUIERDA`,
`FUERA_POR_DERECHA`, `FUERA_POR_ARRIBA`, `FUERA_POR_ABAJO`) each get their own
name, because each one implies a different fix.

#### The two reversible instruments

Both are switches on the 3D screen, both are off by default, and both restore the
scene exactly when turned off. Neither invents Second Life geometry.

* **`Probe cámara`** (`SLCameraProbe`) — one bright, *fullbright* magenta cube,
  0.8 m across, rebuilt 3 m in front of the camera every frame. It uses no SL
  data at all, so it is the control experiment: **if it shows and the prims do
  not, the fault is in the SL transforms** (surface, swap chain, engine, camera
  and projection are all proven working); **if it does not show either, the fault
  is below the scene layer** and no transform fix would have helped. The report
  says where it should be (dead centre) and asks for the visual confirmation,
  because only a device can give it.
* **`Ver prim`** (`SLCamera.lockTo` / `FilamentWorldView.togglePrimLock`) — pins
  the camera to `eye = prim + offset`, `target = prim` for the nearest prim
  within 50 m. **The object is never moved**; only the camera is. The block
  records the frustum test *before* (with the camera where it was) and *after*
  (with the camera looking straight at it), the SL position, and the position the
  `TransformManager` actually holds. `visible ANTES=…` / `visible DESPUES=…` is
  the cleanest split the phase has: a prim that is `FUERA` before and `DENTRO`
  after, and still not visible, is a render-pass problem, not a placement one.

#### Honesty rule

No counter here claims anything is *visible*. The audit reports geometry: depth,
NDC, screen position, frustum membership. Every block that ends in a visual claim
ends with `visibleAJO=PENDIENTE` and says what to look for. The device is the
only instrument that can answer the visual question, and the phase is designed so
that one look settles it.

#### What was verified without a device

The headless harness grew **five** checks (27 → 32, all passing): the frustum
maths one failing condition at a time (in front, behind, closer than near, past
far, off each of the four sides, the screen position of a point at the top of the
picture, and a big off-axis sphere that still has to intersect); that the
matrix-based and the basis-based frustum tests agree point by point on the same
camera; the whole audit on the 20-entity test scene (20 entities seen, the
`TransformManager` read back, `aplicadaVsEscena=IGUAL`, `acuerdo=SI`, `DENTRO`
when aimed at the prims, the engine's camera agreeing with the scene's, and
exactly five rows traced); that the probe creates exactly one mesh, one material
and one entity, lands dead centre (`centro-x=50%`, `centro-y=50%`), reports
`DENTRO`, and frees everything on `destroy()`; and that the camera lock is
reversible (lock wins while on, the orbit is restored bit-for-bit, the framing
label goes back) plus the `REAL_PRIM_TEST` block saying `FUERA` before and
`DENTRO` after for a prim that starts behind the camera.

#### Still open (this phase)

* Frustum culling of the region is still a distance test (`prune`); the audit's
  per-object frustum test is a report, not what decides visibility yet. Wiring it
  in is the next step once the audit says the objects are where they should be.
* The probe and the lock are diagnostic switches; a later phase replaces them
  with a proper camera mode and real occlusion.

### Where does the parent/child chain break, and whose transform is `#830138250` reading? (phase 2.10)

**This phase is diagnosis only.** No matrix, no camera, no pipeline, no geometry
and no parent/child behaviour was changed — only read-backs and reports were
added. It exists because phase 2.9's audit left two questions open:

1. The chain `ObjectUpdateDecoder.parentId → SceneObject.parentId →
   SLObject.parentLocalId → Renderer.Transform.parent → setParent()` is carried by
   every hop except one: `SLScene.attach()` builds `Transform(translation,
   rotation, scale)` and never passes `parent`. So `SLObject.parentLocalId != 0`
   and `Transform.parent == null` at the same time. The audit now *prints* that,
   for every child in the scene, instead of leaving it to be inferred.
2. One object (`#830138250`) showed a `TransformManager` reading that did not
   match the matrix the scene handed over, while other objects matched perfectly.
   The new read-back answers the one question a matrix comparison cannot: **is
   the component instance the renderer remembered still the entity's own?**

#### What is read back, and why an instance number matters

`Renderer.entityProbe(handle)` (implemented by `FilamentRenderer`, read-only:
`getInstance`, `getParent`, `getChildCount`, `getTransform`, `getWorldTransform`)
returns, for one entity, both the component instance the scene stored when it
created the entity **and** the instance the engine answers with *now*:

```
ficha: TransformInstance cacheada=57  actual(getInstance)=57  COINCIDE=SI
ficha: parentEntity=0 (handle=0)  hijos=0  parent por la instancia cacheada=0
```

They can differ, and that is not a diagnostic artifact. Filament's component
managers compact their storage with **swap-and-pop**: when a component is removed
the *last* one is moved into the freed slot (`SingleInstanceComponentManager`:
`auto const [lastIndex, swappedEntity] = deallocator(...); map[swappedEntity] =
index;`, documented as "This invalidates all pointers components"), and
`Engine.destroyEntity` destroys the transform and renderable components
(`FEngine::destroy`: `mRenderableManager.destroy(...); mTransformManager.destroy(...)`).
So after any `destroyEntity` — which the scene does whenever an object leaves, is
rebuilt, or turns out not to be renderable — exactly one other entity's cached
instance is stale, and every later `setTransform`/`getTransform` through that
cached number operates on **another entity's component**. The audit now reports
`COINCIDE=NO` and reads the matrix both ways, so "the data is wrong" and "the
read-back was reading the wrong slot" can never be confused again.

#### The reports

* `SLScene.parentChainReport(renderer, limit)` — every drawn object with
  `parentLocalId != 0`: the last `ParentID` the parser saw for it, the
  `parentLocalId` the `SLObject` kept, the `Transform` the scene built (all four
  fields, `parent` included), the engine's `parentEntity`/`parentHandle`/child
  count, and a plain verdict line: `EL PARENT SE PIERDE EN SLScene.attach()` and
  `PARENT PERDIDO ENTRE SLScene Y RENDERER`. The parser's own counters come with
  it (blocks that carried `ParentID`, blocks whose `ParentID` was 0, and the count
  of parents *destroyed* by a compressed update without the flag).
* `SLScene.focusReport(renderer, localId)` — the whole life of one object chosen
  at runtime (`FOCO` button, or a tap in the objects-nearby list; nothing is
  hard-coded): the parser's per-update history for that id, the scene's own
  entity events (created / kept / destroyed-and-recreated, with the mesh key and
  the transform), `MATRIZ_ESCENA`, the engine reading for the cached **and** the
  current instance, the verdict on where the discrepancy appears, and the same
  full field set for one healthy neighbour (chosen by measurement: no parent,
  `aplicadaVsEscena=IGUAL`, instance not stale).
* The parser side (`ObjectUpdateDiagnostics`) gained a parent ledger: the
  transitions that matter — a parent appearing, and a parent being written to 0
  by a compressed update that did not carry `FLAG_HAS_PARENT` (the same thing
  LibreMetaverse's `ObjectManager` does) — plus the focused object's complete
  update history.

#### What was verified without a device

The harness grew **three** checks (32 → 35, all passing):

* `parentparser` — a compressed block carrying `ParentID` delivers it; a terse
  update in between does not touch it; a compressed block without the flag writes
  0, is counted as a destroyed parent, and appears in the ledger as
  `parent 700 -> 0`.
* `parentchain` — three objects, two of them children: the report counts them,
  shows `parentLocalId=900 pero el Transform lleva parent=null`, prints
  `PARENT PERDIDO ENTRE SLScene Y RENDERER`, and says the engine has no parent
  relation for them.
* `focusreport` — the followed object's report has the scene matrix, the healthy
  neighbour and `COINCIDE=SI`; then the fake renderer *moves that entity's
  component to another slot* (what Filament's compaction does) and the same
  report must show `COINCIDE=NO`, print what the wrong slot holds, and conclude
  that the discrepancy appears when reading the wrong instance — not when handing
  the transform over.

`SLScene.attach()` (line 633) is *unchanged*: the phase's job was to prove where
the parent is dropped and to make the object-specific discrepancy attributable.

### The parent/child correction (phase 2.11)

Phase 2.10 left the chain broken in exactly one place, and this phase fixes it —
and only it. Nothing about the camera, the frustum, the geometry generator, the
materials, the terrain, the avatars or the renderer's setup was touched; the
change is confined to `SLObject.parentLocalId → SLScene → Renderer.Transform.parent
→ FilamentRenderer.setParent()`.

#### Why `parent` and the local TRS is the whole conversion

Second Life sends a linkset child's placement *relative to its root*. The
reference viewer proves it: `LLViewerObject::processUpdateMessage()` reads the
wire position into a variable called `new_pos_parent` and compares it against
`getPosition()` (the object's *local* transform), and `setPositionParent()`
stores it with `LLViewerObject::setPosition()` for anything that is not a root —
while `getPositionRegion()` composes `parent->getPositionRegion() + (getPosition()
* parent->getRotation())`. Rotation works the same way (`getRotationRegion()` is
`getRotation() * parent->getRotation()`).

Filament's world matrix for a child is `parentWorld × local`, which is that same
expression. So there is nothing to convert: the child's own TRS is handed over
verbatim, together with the parent's entity, and the hierarchy produces the world
transform. No child is moved, none is converted to region coordinates, and the
engine is never told a world position for a child.

Roots (`parentLocalId == 0`) keep `Transform.parent = null` and behave exactly as
before.

#### Two-step resolution, and the index that makes it work

The scene already had the index — `slots` is `LocalID → Slot`, and `Slot.entity`
is the renderer's `EntityHandle`. On top of it the scene now keeps the relation
itself (`parentOf`, `childrenOf`, and the mirror `awaitingParent`/`awaitingFrom`
for children whose parent has no entity yet):

1. **Create/update**: `transformOf(object)` builds the `Transform` from the
   object's own `SLTranslation/rotation/scale` and resolves the parent through
   `parentEntityFor(childLocalId, parentLocalId)`. If the parent's entity exists,
   the relation is applied. If it does not, the child is registered as **pending**
   and gets `parent = null` — it is not moved, not converted and not given some
   other parent.
2. **Resolve**: after the whole `SLWorldDelta` has been applied,
   `resolvePendingParents()` links every waiting child whose parent has just
   appeared. That is what makes the result independent of the order the region
   sent the objects in (`ObjectUpdate` is not ordered parent-first).

Multi-level linksets work because each child points at *its own* parent's entity:
`C → B → A` is three entities each carrying one relation, and Filament chains
them (`setParent` re-inserts the node in the hierarchy and recomputes the world
matrix from the local one; it does **not** rewrite the local transform to
preserve a world position, which is exactly what makes "local + parent" correct
here — `FTransformManager::setParent`).

Updates keep the relation alive: an object that is only moving gets
`updateTransform` with the same parent re-resolved, and a child whose parent's
entity was rebuilt gets pointed at the new handle by `relinkChildrenOf()`.

#### An update that says nothing no longer unlinks

`ObjectUpdateCompressed` without `FLAG_HAS_PARENT` used to write `parentId = 0`,
which silently unlinked a child on any partial update. The decoder now leaves the
stored parent alone — the same thing the reference viewer does, where `parent_id`
is seeded with the object's current parent before the block is read. An update
that *does* carry the field with value 0 is the region unlinking the object, and
that is applied. The parser counter was renamed to match what it now counts
(`parentKeptWithoutField`).

#### Destruction

`SLScene.onObjectLeftScene(localId)` runs whenever an entity is destroyed (the
object left the region, stopped being renderable, or was rebuilt): the object
stops being a child (its relation is dropped) and it stops being a parent (its
children run through `relinkChildrenOf()`, which either points them at the new
entity or puts them back to *pending* with `parent = null`). No handle the
renderer has destroyed is ever left in a `Transform`. Filament orphans a
destroyed entity's children itself as well (`FTransformManager::destroyComponents`
sets `parent = 0` on them), so the two agree.

#### The instance a transform is written through

`EntityRecord.transformInstance` is remembered when the entity is created, but
Filament's component managers are compacting arrays: destroying any entity moves
the last component into the freed slot (`SingleInstanceComponentManager`,
swap-and-pop — the mechanism phase 2.10 identified). Writing a transform or a
parent through that remembered number would therefore address **another entity's**
component. The write path (`createEntity`'s parent application and
`updateTransform`) now asks the manager for the entity's current instance first
(`currentTransformInstance`). The remembered value is deliberately left untouched,
because the phase-2.10 read-backs print both and that is how the staleness stays
visible.

#### Draw distance uses a region placement

The scene itself needs a world position for two things: the draw-distance test
and the distances printed in the reports. For a child, `slot.transform.translation`
is local, so `placementInRegion(localId)` composes the chain
(`position = parentPosition + parentRotation * local`, `rotation = parentRotation
* localRotation`, the same product Filament computes, cached per `apply()` and
guarded by `MAX_PARENT_DEPTH` against a cyclic `ParentID`). The renderer is still
fed the local transform plus the relation — this composition only answers "how
far is it".

#### Counters and the new report

`SLScene` exposes `childrenWithParent`, `childrenWithoutParent`,
`parentsResolved`, `parentsPending` and `parentsUnresolved`
(`pending` = the parent never arrived; `unresolved` = the parent arrived and then
went away), and `parentLinkReport(renderer, limit)` prints, for the first ten
children:

```
#2509 (hijo de #2519)
  Child LocalID=2509  Parent LocalID=2519
  Child Entity=Entity#12  Parent Entity=Entity#7
  Transform.parent=EntityHandle(7)  Filament parentEntity=7 (handle=7)  hijosEnElMotor=1
  child local=0.16, 0.00, -3.24  child world (compuesto)=...  parent world=...
  -> parentLocalId=2519 -> Transform.parent=EntityHandle(7) -> Filament parentEntity=7  OK
```

so a resolved child can never again print `Transform.parent = null`, and a child
whose parent is not in the scene is listed separately (with the parent it waits
for, and whether that id was ever seen in the protocol at all).

#### What was verified without a device

The whole render subset (43 files: `slproto.world`, `slworld`, `renderer`,
`renderer.filament`, `slscene`) compiles with **0 errors**, with and without the
harness, and the harness grew a fourth parent check (35 → 36):

* `parentresolve` (new) — a child arriving **before** its parent stays pending
  without moving; when the parent arrives it resolves and nobody moved the child;
  when the parent then disappears the child goes back to *pending* with
  `parent = null`, keeps its entity and its local position, and no dead handle
  survives.
* `parentchain` (rewritten) — three objects delivered in reverse order (grandchild,
  child, root): both children end up with the parent's `EntityHandle`, the
  relation reaches the engine (`Filament parentEntity`), the counters are
  `childrenWithParent=2 childrenWithoutParent=1 parentsResolved=2
  parentsPending=0 parentsUnresolved=0`, the child's **local** translation is
  untouched, and its composed region position is `parent + local`.
* `parentparser` (updated) — the `ParentID` carried by a compressed block still
  arrives, a terse update still does not touch it, an update without the field now
  **keeps** the parent (counter + ledger), and an update that carries it as 0
  unlinks.
* `focusreport` (unchanged in intent) — the phase-2.10 diagnosis still detects a
  component instance that has moved to another entity.

The end-to-end numbers can only come from a real region: the phase-2.11 report is
published by the same `FOCO`/`DIAG` flow (`diagnostics.parentLinks`), so the
device run fills in the resolved/pending counts and the `#2509 → #2519` style
lines for real linksets.





```
app/src/main/
  assets/message_template.msg      Linden Lab protocol definition (475 messages)
  cpp/
    sl_prim_geometry.h/.cpp        LLVolume path/profile tessellation, ported from
                                   the official viewer's llvolume.cpp (no JNI, no
                                   Android types — testable on any host)
    slcore_tests.cpp               13 checks (12 shapes + the legacy grass/tree
                                   meshes), run by CI before the APK build
    slcore_jni.cpp                 JNI bridge: 19 wire ints in, vertices/indices/
                                   face groups out
    CMakeLists.txt                 slcore shared library (+ optional ctest)
  java/com/lumiyaviewer/lumiya/
    EphoraApp.kt                   loads the template into memory at startup
    slproto/                       SECOND LIFE PROTOCOL — decoding only
      base/LLUUIDUtil.kt           UUID <-> bytes
      base/SLVector.kt             Vector3/3d/4, Quaternion
      messages/MessageTemplate.kt  parser for message_template.msg
      messages/SLMessage*.kt       generic message model + binary codec
      circuit/                     PacketFlags, ZeroCodec, SLPacket, SLPacketWriter, Circuit
      llsd/                        LLSD XML/notation/binary read + write
      login/LoginModule.kt         HTTPS LLSD login + redirect handling
      caps/                        seed capability, capability map, event queue
      inventory/, messages/LLSDMessageDecoder.kt
      world/WorldModel.kt          everything known about the region (thread-safe)
      world/SceneObject.kt         prim/avatar record + shape classifier
      world/ObjectUpdateDecoder.kt Full/Compressed/Terse/Cached/KillObject
      world/ObjectUpdateDiagnostics.kt  what the bytes said: parse failures in
                                   full, shape provenance, the parent ledger
                                   (2.10; an update without the field keeps the
                                   parent since 2.11) and the focused object's
                                   history
      world/TerrainData.kt         LayerData patches -> 256x256 heightmap
      modules/SLConnection.kt      session state machine, chat, movement, world
      selftest/ProtocolSelfTest.kt offline wire-layer test suite
    slworld/                       SECOND LIFE WORLD MODEL — interpretation
      SLRegion.kt                  one region: its objects, its diff helpers
      SLObject.kt                  immutable snapshot: kind, transform, texture,
                                   parent, attachment point, revision
      SLPrimitive.kt               the 19 raw wire shape parameters + cache key
      SLMesh.kt / SLMeshLibrary    MeshDesc cache; prim + legacy fixed shapes
      SLTexture.kt                 one face: UUID, linear tint, UV transform
      SLTextureCache.kt            face -> material, keyed by everything that
                                   can change appearance
      SLAvatar.kt                  avatar identity/placement (phase 8 draws them)
      SLAnimation.kt               animation wire shape (phase 9 plays them)
      SLWorld.kt                   WorldModel -> SLWorldDelta, per-region
      SLTerrainSnapshot.kt         immutable copy of the 256x256 height grid
    slscene/                       GRAPHICS-AGNOSTIC SCENE
      SLScene.kt                   desired state + diffing + distance culling and
                                   the far-prim shadow LOD (phase 2.12: both are
                                   switches, both reversible), the LocalID ->
                                   EntityHandle parent resolution (phase 2.11:
                                   local TRS + parent entity, two passes, pending
                                   children, destruction) and the
                                   phase-2.9/2.10/2.11 reports
      SLCamera.kt                  Z-up orbit camera -> CameraDesc, world framing
                                   (+ the reversible lock of phase 2.9)
      CameraFrustum.kt             the frustum test, twice: from the camera's
                                   eye/forward/up/fov and from the matrices the
                                   engine reports back; depth/NDC/screen position
                                   per point, and a bounding-sphere test
      SLCameraProbe.kt             the phase-2.9 control experiment: one
                                   fullbright cube glued 3 m in front of the
                                   camera — no Second Life data at all
      SLTerrain.kt                 height field -> decimated mesh + entity (debug)
      SLDiagnosticProbe.kt         the pure-Filament probe (PRUEBA A) — a lit
                                   cube, a fullbright cube, a sphere and a 60 m
                                   ground plane, built with no SL data at all
      SLTestScene.kt               the phase-1 debug pattern (real parameters)
      PrimGeometryNative.kt        slcore via JNI; refuses malformed geometry
                                   (see MeshDesc.geometryProblem) instead of
                                   handing it to the backend
    renderer/                      THE SEAM
      Renderer.kt                  engine-agnostic interface + descriptors
      RenderDiagnostics.kt         the shared debug record (counters + HUD text +
                                   the start-up step log and full stack traces)
      filament/FilamentRenderer.kt the only class that knows Filament exists
      filament/FilamentBootstrap.kt loads libfilament-jni before anything else can
                                   reach it, and reports what the device's native
                                   loader actually sees (ABIs, APK libs, load
                                   results, full throwables)
      filament/FilamentMaterials.kt runtime material compilation (filamat)
    ui/
      diag/FilamentProbeActivity.kt  PRUEBA A alone on its own screen: Activity +
                                   SurfaceView + Filament, no Second Life at all
      world/FilamentWorldView.kt   SurfaceView + render thread + surface handshake
      world/RenderTestActivity.kt  phase-1 test screen
      world/WorldViewActivity.kt   3D screen: HUD, pad, mini-map, nearby list
      world/MinimapView.kt         mini-map canvas view
      service/ViewerSessionService.kt  foreground service + wake/Wi-Fi locks
      world/MovementActivity.kt    full-screen movement pad
      login/  main/  chat/  inventory/  diagnostics/  settings/  common/
```

### Render scalability (phase 2.12)

Phase 2.11 closed the parent/child chain; this phase makes the same scene cheaper
to draw **without changing what is on screen**, and without touching the
hierarchy. Three independent, reversible knobs:

* **Frustum culling** — the *engine's* own per-view test (`View.setFrustumCullingEnabled`
  plus the per-renderable flag). Nothing is removed from the scene: an entity
  keeps its slot, its renderable, its mesh and its material, and only the ones
  outside the frustum are skipped when the frame is submitted. It respects the
  parent hierarchy for free, because Filament culls with the *world* transform
  `TransformManager` produced (`parentWorld × childLocal`), not with anything this
  layer recomputes. `FilamentRenderer.setCullingEnabled` also walks the live
  renderables (`RenderableManager.setCulling`), because the per-renderable flag is
  baked in at build time and otherwise the switch would only affect objects built
  afterwards.
* **Distance culling** — `SLScene.distanceCullingEnabled` + `maxDrawDistance`. It is
  the existing mechanism (the entity is taken out of the draw set, counted in
  `culledByDistance`), now with a switch so the same binary can be measured both
  ways. It destroys nothing: no entity, mesh, material or texture is dropped.
* **Shadow LOD** — `SLScene.shadowLodDistance` (0 = off, the shipped default).
  Beyond that distance a prim stops *casting* shadows (`Renderer.setShadowCaster`
  → `RenderableManager.setCastShadows`); it keeps drawing, keeps receiving light,
  keeps its geometry and its material, so this can reduce cost but cannot change a
  shape. Turning the LOD off restores every caster.

**What was deliberately not done.** No geometry LOD: no mesh is simplified,
swapped, or downloaded, and no second mesh system was invented (the phase plan
keeps that for the mesh-asset work). No texture work. No parent/child change —
the relation is still exactly one `Transform.parent` per child, with no second,
parallel hierarchy, and distance/frustum culling use the composed *region*
placement (`placementInRegion`) so a linkset child is judged by where the
hierarchy puts it.

**Material/geometry reuse (audited, not changed).** Both were already shared and
are now reported instead of assumed: `SLMeshLibrary` keys one GPU mesh per prim
parameter set (position/rotation/scale excluded, so moving or resizing a prim
reuses it) and `SLTextureCache` keys one material per appearance (texture UUID +
tint + material code + fullbright). Nothing in this phase creates a duplicate
geometry, buffer or material, and the debug line prints the counts so a
regression would be visible (`mallas creadas / cache`, `materiales`, `buffers
VBO/IBO`, `texturas`).

**Instrumentation.** The new `Escalado` lines in the HUD/report carry: renderables
in the scene, renderables actually submitted, culled by frustum, culled by
distance, plus the state of each switch; the `Recursos` line carries the mesh,
material, buffer and texture counts. The frustum figure is not a second estimate
computed here — it is `scene.getRenderableCount() - view.getVisibleRenderableCount()`
read back from Filament after the frame, so it is the engine's own measurement.

**The DEBUG switches** are `Culling`, `Distancia`, `Sombras` and `LOD` on the 3D
screen's second button row. Each is a request the *render thread* applies on its
next frame (the UI thread never touches the renderer), each logs what it did, and
each is reversible. Their state is printed in the HUD as well as on the buttons,
so a screenshot says which settings produced the numbers.

### Frustum diagnostic correction (phase 2.12b)

The engine's culling was never wrong, but the **auxiliary frustum diagnostic** was
testing **two different points** and could therefore contradict itself. In
`SLScene.realPrimTestReport` the sample point was the object's `Transform.translation`
— its **local** position, relative to its parent — while the audit table's basis
route (`frustum(base)`) sampled `placementInRegion(localId)`, the **region**
position (`parentWorld × local`). For a linkset child the two differ (two thirds
of the region's objects are children), so a camera moved onto the child's real
place produced a negative depth at the local point — "FUERA
(DETRAS_DE_LA_CAMARA)" — while the region route said inside, and the two routes
of the audit "disagreed". It was a **sample-point** bug, not a matrix or
convention bug: the basis and matrix routes already matched on root objects.

What changed (only the diagnostic; the culling stays the engine's):

* **One sample point everywhere.** Every frustum test now samples the region
  placement, and the audit prints it explicitly (`posicion de REGION (parentWorld
  x local)=…   <- punto probado`) next to the local one, so a child's two
  positions are never confused again. `realPrimTestReport` also compares the
  region position against the engine's own `TransformManager` world matrix
  (`deltaVsRegion`).
* **`CameraFrustum.fromEngine(eye, forward, up, fov, aspect, near, far)`** builds
  the basis from the vectors the **engine** reports (falling back to the square
  `CameraDesc` basis only if a term is missing), instead of from the descriptor
  this layer sent. `cameraAudit` uses it and compares the axes
  (`right(motor=−left) vs right(base)`, `up(motor) vs up(base)`).
* **`MatrixTrace` / `MatrixFrustum.trace(point)`** expose the view-space,
  clip-space, NDC and depth of a point using the *engine's* view/projection
  matrices, and `sample()` reuses `trace()` so there is a single implementation.
* **`appendObjectFrustumTest`** is the compact per-object block (world position,
  camera eye/forward, view-space, clip-space, NDC, the three auxiliary verdicts
  and the engine's frame-aggregate culling figure). It runs for `REAL_PRIM_TEST`
  and for the focused object.
* **Honest labels.** The two auxiliary routes are marked `DIAGNOSTICO`, and the
  header states that the real culling is the engine's
  (`View.setFrustumCullingEnabled` + `RenderableManager`) and that nothing in the
  trace replaces it. `boundsRadiusWorld` now composes the scale along the parent
  chain.

`Renderer.kt`, `FilamentRenderer.kt` and the real culling were not touched.

### Benchmark A/B (phase 2.12c)

Phase 2.12c adds no mechanism: it is the reproducible A/B measurement of the ones
already shipped. The four states are the four switches, and each is measured with
the **same camera**, standing still, after a few seconds of settling:

| State | Culling | Distancia | Sombras | LOD |
|-------|---------|-----------|---------|-----|
| A (default) | ON | ON | ON | OFF |
| B | **OFF** | ON | ON | OFF |
| C | ON | **OFF** | ON | OFF |
| D | ON | ON | **OFF** | OFF |

The only code added for it is a **label**: `RenderDiagnostics` now prints
`Benchmark state <A|B|C|D|->` (the letter is derived from the engine's *applied*
flags, not from what the UI requested), so a copied report says which of the four
it is. Everything the measurement needs was already printed:

* `Escalado (fase 2.12)` — renderables in scene, visible, culled by frustum,
  culled by distance, entities.
* `Escalado DEBUG` — the four switches and their distances.
* `Recursos` — meshes (created/cache), materials, VBO/IBO, textures.
* `FPS … frame … ms cpu / … ms gpu` — already the mean of the last 20 frames
  (`FilamentRenderer.STATS_WINDOW`), so each snapshot is an average, not a spike.
* the parent/child counters (`childrenWithParent`, `childrenWithoutParent`,
  `parentsResolved`, `parentsPending`, `parentsUnresolved`) from `parentLinkReport`.

No default changed (culling ON, distance ON, shadows ON, LOD OFF, 300 m, 40 m),
and nothing about geometry, protocol, materials or the hierarchy is touched.

### TextureEntry parser (phase 2.13a)

The first sub-phase of the textures work. It decodes the whole `TextureEntry`
blob instead of only its first 16 bytes, and it adds the seam the asset pipeline
will hang off — but it deliberately changes **nothing that is drawn**.

**The format, as the region actually sends it.** `LLPrimitive::packTEMessage` is
the packer *and* `parseTEMessage`/`unpackTEMessage` is the unpacker (the simulator
links the same source), and the writer emits **eleven fields in a fixed order**,
each one a small face list:

```
[default value]              one value, the field's element size
[ (index flags, value) ... ] zero or more exception groups
[0x00]                       terminator (ten first fields only)
```

The default fills every face (Linden Lab writes the *last* face's value as the
default), and an exception group is a variable-length 7-bit bitfield — `bit i` =
"face i takes this value", MSB = continuation — followed by the value. The fields
are image ids (16), colours (4, RGB**A**, each byte stored as `255 - value`),
scale S/T (4 each, F32), offset S/T (2 each, S16/0x7FFF), rotation (2, S16/0x8000
× 2π), bump/shiny/fullbright (1, `SSFBBBBB`), media flags (1), glow (1) and the
render-material UUID (16). The old code read the first 16 bytes (which is the
default texture UUID) and nothing else — which is why the tint and the fullbright
flag were always white and `false`.

**Writer and reader disagree, and that is the whole revision of 2.13a.** The
writer **always** emits all eleven fields — the ten first values each followed by
a `0x00`, then the material id *unterminated* — so the smallest blob a region can
send is the all-default 63-byte / eleven-field one. The reader is laxer in two
ways, both visible in `llprimitive.cpp`:

* it **appends a phantom `0x00`** to the buffer before parsing ("The last field is
  not zero terminated. Rather than special case the unpack functions, just make it
  0x00 terminated"), which turns its own `source + size + 1 > source_end` check
  into exactly "the value must fit in the bytes received" — the rule this parser
  applies;
* it reads the material **only `if (cur_ptr < buffer_end)`** and swallows a
  failure there (`memset(material_data, 0, ...)`), so a blob that stops after
  `glow` — or inside the material — is *accepted*, with every face left without a
  material.

Consequently `parsedFields` alone is not a verdict, and the revision splits what
one counter used to mix: **`TRUNCADO`** means the blob ended *inside* a field
(never "it was short"); a blob that ends on a field boundary with fewer than
eleven fields is well formed but **not something the writer produces**
(`belowWriterMinimum`); and a truncation that stopped in the *optional* field
(`textureStopsInOptionalMaterial`) is the case the reader would have accepted
anyway. None of the counters was removed.

**What was built**

* `slproto/world/TextureEntry.kt` — the parser and the per-face model
  (`TextureEntryFace`, `TextureEntry`). It is self-delimiting: it needs no face
  count, resolves any face index on demand, and a blob that ends *inside* a field
  is reported (`truncated`, `stoppedAt`, `stop`, `stopFieldIndex`,
  `stopRemaining`, `stopExpectedSize`) instead of being guessed at. A blob
  shorter than one UUID is `null` (the object keeps what it had).
* The three decoder paths (`ObjectUpdate`, `ObjectUpdateCompressed`,
  `ImprovedTerseObjectUpdate`) all go through one `applyTextureEntry`: they store
  the parsed entry, keep the byte count (`textureEntrySize`), and keep the scalar
  `textureId` semantics each message had (a full/compressed update states the
  texture; a terse one only when it carries something).
* `slworld/SLTexture.kt` — `SLTextureFace` gained the per-face fields
  (bump/shiny/media/glow/material id) and a `fromFace` factory; `SLObject` carries
  the decoded entry so the per-face material work has it.
* `slproto/asset/` — `TextureAssetProvider` (the fetch seam), `TextureAssetCache`
  (per UUID + discard level: hit/miss/pending/failed/retry and the counters) and
  `FakeTextureAssetProvider`, which does nothing on its own and **never touches
  the network**. No HTTP, no decode, no GPU upload, no renderer change.
* A temporary diagnostic (`ObjectUpdateDiagnostics`): the totals, the split by
  classification and by message path, the stop-field histogram, and **verbatim
  samples** — one per interesting shape, each with its full hex (up to 1 KiB; the
  392-byte blob that used to be cut at 96 bytes now appears whole) plus the window
  of the buffer it was cut from. Every sample line is meant to be pasted into a
  report, and it is replaced in 2.13b.

**Deliberately not done:** the decoded tint and fullbright flag are *not* copied
into `textureColor`/`fullBright` yet, and no material samples the new fields, so
the picture is unchanged from 2.12c. HTTP/`GetTexture`, JPEG2000 and the GPU
upload are 2.13b–2.13e.

#### Revision of 2.13a — explaining the truncation count

The first device run of 2.13a printed `blobs decodificados 3125 · truncados 453 ·
demasiado cortos 9 · campos leidos 29829`. The revision answers *why* those 453
exist before any real texture download is added, and it is **diagnosis only**: the
renderer, the materials, the parent/child work, the geometry, the culling and the
overall protocol are untouched, and no texture is fetched, decoded (no OpenJPEG,
no JNI) or uploaded (no Filament texture).

What changed:

* `TextureEntry` now records *where* a parse stopped (`stop`, `stopFieldIndex`,
  `stopRemaining`, `stopExpectedSize`) and *why* (`StopReason.DEFAULT_INCOMPLETE`
  / `EXCEPTION_INCOMPLETE` / `BITFIELD_INCOMPLETE`). The reader semantics above
  were checked line by line against `llprimitive.cpp` before being encoded as
  regression tests.
* `ObjectUpdateDiagnostics` grew the split that turns one number into a cause:
  per message path (`ObjectUpdate` / `ObjectUpdateCompressed` /
  `ImprovedTerseObjectUpdate`), the histogram of fields read *and* of the field
  the parse stopped at, the size histogram of the truncated blobs, the count of
  well-formed-but-unwriterly entries, the count of empty blobs, and the count of
  truncations that stopped in the optional material. **No counter was removed or
  hidden** — `truncados` is still the same strict number.
* Every sample is now dumped **verbatim**: full hex (the old dump cut at 96 bytes
  and printed `... (296 mas)`), plus a window of the buffer the blob was cut from
  (re-encoded from the decoded message, or the neighbouring field when a synthetic
  definition makes the re-encode impossible). The `prim #<localId>`, the byte
  count, the fields read, the stop and the source path are all on the same line.

The two readings the device data will separate, both compatible with the aggregate
numbers, are: (A) blobs cut early, ~1 field read each; or (B) a set of one-field
17-byte blobs (well formed, `belowWriterMinimum`) *plus* truncations that read ten
fields and stopped in the material. `textureEntriesBelowWriter` and
`textureTruncatedStopFields` say which one is real on the next run.

`TextureEntry.kt`, `ObjectUpdateDiagnostics.kt` and `ObjectUpdateDecoder.kt` (the
latter only for the sample context and the framing check — no behavioural change)
are the files touched.

##### The classification the report now prints

Every blob lands in exactly one category, and the categories are printed as totals
**and per message path** (`ObjectUpdate` / `ObjectUpdateCompressed` /
`ImprovedTerseObjectUpdate`), because a broken path must not hide behind a total:

| Cat | What it is | What the region *writes* | What the reference *reader* accepts | What this parser accepts | Action |
|-----|------------|--------------------------|-------------------------------------|--------------------------|--------|
| A absent | well formed, exactly the ten mandatory fields | never (the material is always written) | **yes** — material left unset | complete 10-field entry, counted `materialAbsent`, **not** truncated | none: better diagnostics only |
| A partial | cut *inside* the material | never | **yes** — a failed material read is swallowed | `truncated` at field 10, counted `materialIncomplete` | none for rendering; the classification says it is the tolerated case |
| B | well formed, fewer than ten mandatory fields | never | **no** — the reader needs all ten mandatory fields to fit (≥ 46 bytes; measured boundary: 0 and ≥ 46 accepted, 1..45 rejected) | complete, `belowWriterMinimum`, counted `legacyFewerFields` | none; it means the blob did not come out of `packTEMessage` |
| C | cut inside a mandatory field | never | no — the reader drops the whole entry | `truncated`, stop field and remaining bytes reported | prove the cause before touching anything (see D) |
| D | the section's declared length is impossible: `> remaining`, or `1 .. 62` (below `TextureEntry.FULL_WIRE_SIZE` = 63, the writer's minimum) | `0` (no faces) or `≥ 63` | not applicable — the framing is wrong, not the entry | blob read as far as the bytes allow, attributed to framing | framing bug to fix **if** the next run shows a large D |
| E | 1..15 bytes: not even the default UUID | never | no | `null`, counted `subUuid` | none |
| legal empty | the update carries no entry (0 bytes) | yes, for a prim with no faces | nothing to read | `null`, counted `empty` | none |

`D` is an **attribution, not a bucket**: it overlaps A/B/C on purpose, because a
mis-sliced section can stop anywhere. The report prints its two provable causes
separately (`longitudes declaradas imposibles` and `longitudes mayores que los
bytes que quedan`) with the first concrete case. Nothing was moved out of
`truncados`: the split names what is already there.

Each kept sample also carries **what the reference reader would do with those same
bytes** (`la referencia ACEPTA … / RECHAZA …`), which is the per-case form of the
table above, and there are ten sample classes for ten slots so no class is ever
squeezed out.

No performance work was done, and none is allowed to follow from this phase: the
device's ~32 FPS (diagnosis build, shadows + culling on) is recorded as a
*baseline* in `TODO.md` together with the CPU/GPU frame times and the scene
counters, to be compared against only once the texture pipeline is in.


#### Revision of 2.13a — second pass: the counts the device produced, and the render loop (2.13a-rev)

The second device report separates the cases the first one could only guess at:

```
blobs decodificados 3448 · truncados 449
A material opcional incompleto 399 · C campo obligatorio incompleto 50
B bien formadas con menos campos que los 11 que escribe la region 1259
demasiado cortos 9 (todos vacios legales) · menores que UUID 0
framing D 0 · ExtraParams 0 imposibles / 0 truncados · ByteReader avances rechazados 0
```

This revision makes each of those numbers **provable** rather than plausible. It
is still diagnosis only: no parser behaviour changed, nothing is drawn
differently, no texture is fetched, and the render policy is untouched.

**1. The reference reader is now ported, and it is what decides A/B.** The old
2.13a text above said the reference accepts *any* prefix that ends on a field
boundary. That is **wrong**, and `TextureEntryReference.kt` (a line-by-line port
of `LLPrimitive::unpackTEMessage` / `unpack_TEField`, including the phantom
`0x00` and the `source + size + 1 > source_end` rule) is what shows it: the
boundary is **0 and ≥ 46 bytes accepted, 1..45 rejected**, where 46 = the ten
mandatory fields. So:

* a blob of **46..62 bytes** is ten fields = case **A absent** (the material is
  optional), and the reference accepts it;
* a blob that ends on a field boundary **before** the tenth field is case **B**,
  and the reference **rejects the whole entry** (the viewer prints *"Dropping
  changes on the floor"*). B is therefore *not* "a valid prefix" — it is "not
  something `packTEMessage` can produce", which is a different statement.

The writer side is unchanged and still the reason B cannot come from the region:
`packTEMessage` emits **eleven fields / 63 bytes**, or **nothing at all** when
`getNumTEs() == 0`. There is no 1..62-byte path in it. Every blob's reference
verdict is now **computed per blob** (not asserted), with the rejected field, the
size histogram and the message path of the rejects, in the report.

**2. Framing is measured, not guessed.** `SLMessage`/`SLMessageCodec` now record a
`FieldSpan(lengthWordOffset, dataOffset, declaredLength)` for every variable/fixed
field, and the message carries `bodyOffset` / `bodyLength` / `bodyComplete`. With
that, each texture sample reports `declarado=`, the length word's offset, the data
offset, `restantes=`, `fin_coincide=si/no` (does the blob end exactly at the end of
the message body?) and `cuerpo_completo=`. For a terse update that is the proof
that the cut is real on the wire and not our framing. `ByteReader` rejections are
counted too.

**3. The render loop is audited, with the policy unchanged.** (The audit below is
what the third pass acted on: see *Revision of 2.13a — third pass* for the
Choreographer pacing that replaced this loop. The description here is the state
the device report was taken against.) The report's
`Frames: 251325 intentados · beginFrame OK 7058 / fallo 244267` is not a Filament
fault and the code says why:

* `beginFrame()` is called from exactly one place, `FilamentRenderer.render()`;
* the render thread's loop (`FilamentWorldView.RenderThread.run()`) has **no
  Choreographer, no frame callback and no sleep between frames**;
* `setTargetFrameRate` was **never called** (now counted, so the report can say
  so);
* there is **no wait after `beginFrame == false`** — the loop retries immediately;
* `framesAttempted` does **not** include turns where the surface was not usable
  (it is incremented inside `render()`, after `applySurfaceRequest()` returned
  true and the renderer was ready), so the 251325 are real attempts at a swap
  chain that refused a buffer.

The counters added to `RenderDiagnostics` make the cost visible: loop iterations,
per-reason skips (no surface / renderer not ready / inactive), presented frames,
the current and worst run of consecutive refusals, wall time inside `drawFrame`,
the fraction of the thread's time spent on attempts that presented nothing, and
the mean cost per presented frame against the mean cost per refused attempt. The
rate arithmetic lives in `RenderDiagnostics.publishRenderRates()` (not in the
view) so it can be exercised without a device.

**4. Tests.** The canonical harness is `tools/kcheck/tests_render.kt` — **47
checks**. Two exist for this revision: `texref` (the reference boundary, its
verdict against a corpus of 4273 blobs, the parser/reference equivalence, and the
one documented divergence) and `texframing` (a real `ImprovedTerseObjectUpdate`
encoded and decoded, proving the length word's position, the declared length and
that the blob ends the body). A third, `renderloop`, asserts the loop's policy
lines and the rate arithmetic. The whole suite runs via `tools/kcheck/run_all.js`;
see `tools/kcheck/README.md`.

**Standing conclusion.** The classification and the anomaly detection work. The
50 case-C blobs (a cut inside a mandatory field) are the only protocol problem
still without a causal explanation, and they are **not** reclassified as A, hidden
inside B, or written off as "the parser is fine". 2.13b does not start until the
cause is demonstrated and the render-loop audit is reproducible.

#### Revision of 2.13a — third pass (2.13a-rev2): the busy-spin is corrected, and the terse field explains the cuts

The third device report turned the render-loop audit into numbers that leave no
room for interpretation:

```
beginFrame intentados 140673 · OK 3204 · fallo 137469 (97.7%)
1572.9 intentos/s · 99.5% del hilo del render dentro de drawFrame
68.8% del tiempo del hilo gastado en intentos que no presentaron nada
coste medio: 47.6 ms por frame presentado / 0.5 ms por intento fallido
sin Choreographer · sin frame callback · sin sleep entre frames
setTargetFrameRate NO · ningun throttle despues de beginFrame == false
```

**1. The pacing is corrected, and nothing else.** The loop is no longer a
`while (running)` that calls `beginFrame` as fast as the CPU allows. It is now
paced by the display:

* `FilamentWorldView.RenderThread.runPacedByVsync()` prepares a `Looper` on the
  render thread itself, creates a `Choreographer` there, posts one
  `FrameCallback` and enters `Looper.loop()`. Each callback performs **exactly one
  turn** (`safeTurn()`, byte-for-byte the old body: bind the surface if it
  changed, skip while the renderer/view is not ready, draw one frame) and then
  re-posts itself — so at most one `beginFrame` per display frame.
* A refused swap chain can therefore no longer be hammered: the next attempt
  waits for the next VSYNC.
* `runThrottled()` is the fallback **only** if a Looper/Choreographer cannot be
  set up on the thread: the same turns, spaced by one display period
  (`1e9 / refreshRate`, 60 Hz when the rate cannot be read). It also cannot
  spin — the old busy-spin is not reachable from either path.
* `shutdown()` sets `running = false`, interrupts, and calls
  `renderLooper?.quitSafely()`, which is what makes `Looper.loop()` return so the
  `finally` block still releases engine, meshes and swap chain.
* The swap chain is told the expected rate once per attach
  (`BaseSwapChain.setFrameRate(60)`, guarded by `isFrameRateChangeSupported()` and
  now by a `try/catch`, so a refused hint can never cost the surface). It is a
  hint to the platform's display-mode selection, never a hard cap.

Not touched, deliberately: `SLScene`, the `View`/`Camera`/`Scene` objects, culling,
the entity budget, materials, meshes, textures and the parent/child machinery.
This is a pacing change, not an optimisation, so the A/B comparison isolates one
variable.

**2. What the report adds (all previous counters kept).** `RenderDiagnostics` now
also carries `renderThreadName` / `uiThreadName` / `inputThreadName` (recorded in
`run()`, the constructor and `onTouchEvent`), `pacingMode`,
`displayRefreshRateHz`, the number of VSYNC callbacks, and presented/rejected frames
per second over a 3 s window. The report says, in three new lines:

```
Hilos: render 'EphoraRender'  ·  UI 'main'  ·  ultimo input 'main'  (el bucle de render NO comparte hilo con la UI ni con el input)
Pacing: Choreographer (VSYNC): un turno por frame del display  ·  refresco del display 60.0 Hz  ·  callbacks de frame 1234
Frames: 140673 intentados  ·  beginFrame OK 3204 / fallo 137469 (97.7% de los intentos)  ·  render …  ·  endFrame 3204
Bucle de render (2.13a-rev): …  ·  ritmo: 60.0 intentos/s / 60.0 presentados/s / 0.0 rechazados/s
Bucle de render, coste (tiempo de pared del hilo): dentro de drawFrame … ms de … ms de hilo (…%)  ·  …
Bucle de render, politicas: Choreographer (VSYNC): …  ·  setTargetFrameRate llamado: si (1, 60.0 fps)  ·  …
```

The thread names are recorded, **not interpreted**: whether the render loop shares
a thread with input/UI is evidence for the movement problem, and `Hilos:` prints
the comparison without claiming a cause.

**3. The terse field carries four bytes the full and compressed paths do not.**
The 1259 B and the 47/50 C blobs appear **only** in
`ImprovedTerseObjectUpdate` (per source: full 513 accepted / 0 refused;
compressed 639 / 0 / 9 without an entry; terse 455 accepted / 1004 refused).
Five independent pieces of evidence say why:

1. Linden Lab's own `message_template.msg` declares
   `ImprovedTerseObjectUpdate`'s `ObjectData` as
   `{ Data Variable 1 } { TextureEntry Variable 2 }` — **identical** to this
   project's template, so the template and the framing are not the problem.
2. OpenSim's terse block writer writes the field as the template length word
   `len + 4` followed by a **little-endian `U32` of the entry's length** and then
   the blob (`data[pos++] = (byte)len; // wtf ???` is upstream's own comment), and
   uses a **zero-length field** when there is no entry — which is what the 9
   "legal empties" are.
3. LibreMetaverse, which talks to the real grid, reads the field as
   `new Primitive.TextureEntry(block.TextureEntry, 4,
   block.TextureEntry.Length - 4)` with the comment *"FIXME: Why are we ignoring
   the first four bytes here?"*.
4. This project's **compressed** path already consumes that u32
   (`textureLength = reader.u32()`), which is why it refuses nothing; the terse
   path does not — and every refusal is a terse blob.
5. The harness proves the mechanism on real byte layouts
   (`terseprefix`, and the standalone probe
   `tools/kcheck/extra/probe_prefix.kt`):
   a one-face entry (64 B) with the prefix parses to 4 fields and stops inside
   `offsetS` (category **C**) and the reference **rejects** it; a two-face entry
   (81 B) with the prefix completes with eleven fields and the reference
   **accepts** it, but **7 of its 8 faces are no longer the object's** — the silent
   false positive that makes the 455 "accepted" terse blobs suspect; skipping
   exactly the four bytes gives the original entry back in both cases, accepted.

The three sources, with the exact excerpts and the commands to fetch them again,
are kept in **`tools/kcheck/ref/terse_prefix_evidence.md`** — in the repo on purpose,
because `scratch/` does not survive the session that downloaded them.

Those five points were, at the time, a measurement and not a fix: the parser was
deliberately left untouched and the report only *measured* the prefix. The device
then answered — `ImprovedTerseObjectUpdate 1975/1975 (100%)`, `ObjectUpdate 0/589`,
`ObjectUpdateCompressed 0/644` — and the fix was applied; see *Revision of 2.13a —
fourth pass* below. The measurement line is kept because it is the evidence the fix
rests on, and it is still taken on the field as it arrives.

**4. Tests.** The canonical harness is `tools/kcheck/extra/tests_render.kt` — **48
checks** at this point (49 after the fourth pass). `terseprefix` is the new one (it
builds the two blobs, proves both categories, the false positive, the recovery and
the report lines); `renderloop` was extended to assert the pacing mode, the thread
comparison and the attempt/presented/rejected rates. Groups: **21** light + **26**
heavy + 1 not runnable (`bootstrap`, needs the real Filament AAR) = 48, all green,
plus a compile check of `ui/world/FilamentWorldView.kt`.

**Pending on the device (this revision).** FPS, CPU ms, GPU ms, the
`beginFrame false` percentage and the thread occupancy — measured at the same
place, with the same baseline (36.2 FPS / 27.6 ms CPU / 19.5 ms GPU), so the two
loops can be compared. Plus the per-origin A–E split and the four terse samples
the report keeps, which close B and C causally.

#### Revision of 2.13a — fourth pass (2.13a-rev4): the terse prefix is consumed, and the metrics add up

The third device run closed the investigation: `ImprovedTerseObjectUpdate`
**1975/1975** fields began with their own remaining length, `ObjectUpdate` 0/589 and
`ObjectUpdateCompressed` 0/644, and every B/C blob was terse. With the cause
demonstrated, the correction was applied — **one** functional change, plus the
render-loop arithmetic.

**1. The one functional change: the terse field's four bytes are consumed at the
handover.** `ObjectUpdateDecoder.applyTerse` no longer hands `block.bytes("TextureEntry")`
to the parser; it hands `ObjectUpdateDiagnostics.consumeTersePrefix(field)`, which
removes the leading little-endian `u32` when — and only when — it equals the number
of bytes that follow it (`raw.size > 4 && leadingU32(raw) == raw.size - 4`). A field
that does not carry it is handed over untouched and counted as
`tersePrefixMissing`, so a grid that does not write it stays correct *and* visible.
Nothing else moved: **`TextureEntry.parse` is untouched**, `ObjectUpdate` and
`ObjectUpdateCompressed` are untouched (the compressed path already consumes its own
u32 inside `Data`), and geometry, the renderer, materials, Parent/Child and J2K are
untouched. `textureContext` still receives the *field*, so the framing numbers
(`declarado=`, `fin_coincide=`) keep describing what is on the wire.

Why the offset matters, in one line: with the four bytes in place, every field of
the entry is read four bytes early, which is exactly a cut inside a mandatory field
(category **C**) or a shorter-than-ten-fields blob (**B**) — never the entry.

**2. The before/after comparison is measured in the same pass.** `consumeTersePrefix`
classifies the field twice — as it arrives, and after consuming it — with the same
parser and the same reference reader the report uses everywhere else, and the report
prints both:

```
PARSER TextureEntry terse, prefijo u32 (correccion 2.13a-rev4): campos vistos N  ·  consumidos N  ·  sin prefijo que coincida (se entregan tal cual) 0
PARSER TextureEntry terse, ANTES (el campo tal cual llega): …  ·  B …  ·  C …  ·  la referencia acepta … / rechaza …
PARSER TextureEntry terse, DESPUES (consumido el u32): …  ·  B 0  ·  C 0  ·  la referencia acepta … / rechaza 0
```

So "B antes -> después", "C antes -> después" and the reference's verdict before and
after are all one subtraction away, from a single device run. The four-byte
measurement itself is still taken (`prefijo de 4 bytes … ImprovedTerseObjectUpdate
1975/1975`), now in `consumeTersePrefix`, and each kept sample still prints
`prefijo4=… (COINCIDE: es la longitud que queda) · saltando 4 bytes: campos=11` —
the sample's `size` stays the entry's, so the identity `prefix4 == size` still means
"the field carried its own length".

**3. The render-loop metrics are recomputed from the counters, not remembered.** The
device saw `56.4 intentos/s` printed next to `8244 intentos` and `1030 fallos` and
could not check the division, because the rates were window-based while the counters
were cumulative. `publishRenderRates` is now a pure function of
(counters, `loopNanos`, `now`):

* the **capture** rates divide the counters by the wall time the render thread has
  been running, and the report prints the division itself:
  `39.1 intentos/s = 8244 / 210.6 s · 34.3 presentados/s = 7214 / 210.6 s ·
  4.9 rechazados/s = 1030 / 210.6 s`;
* `beginFrameFailPercent()` is `100 * fallos / intentos`, the "intentados" of the
  same line (attempts that never reached `beginFrame` are named separately, so
  `OK + fallo + sin llegar a beginFrame == intentados` always holds);
* the cost line divides by the *same* denominator:
  `6932.6 ms (3.3% del hilo, 32.9 ms/s sobre 210.6 s)` — every number on it is
  `failedFrameNanos / loopNanos` or `failedFrameNanos / captureSeconds`;
* the recent rate is a **separate** line with its own duration
  (`ritmo reciente (ventana de 2.0 s): …`), so the steady state is still visible
  without ever mixing with the capture's averages.

The pacing itself is not touched again: the Choreographer/VSYNC loop from the third
pass eliminated the busy-spin and stays as it is.

**4. Tests.** `tests_render.kt` is now **49 checks** (`22` light + `26` heavy + the
unrunnable `bootstrap`). Two changed and one is new:

* `terseconsume` (new) — one face, several faces, a full material, the optional
  material left out, the case that used to end in C and the case that used to end in
  B, and `u32 == the remaining length` in every one of them; it also asserts the
  before/after counters (`B 1 -> 0 · C 1 -> 0 · la referencia acepta 0 -> 2 /
  rechaza 2 -> 0` on its corpus of 25 real entry layouts);
* `terseprefix` — still measures the mechanism on the raw blobs, and now asserts
  that the decoder hands the parser the *entry* (`textureEntrySize == 64`,
  eleven fields, the right texture) and that the four-byte counter still fires;
* `renderloop` — asserts the corrected arithmetic with the device's own numbers
  (8244 / 7214 / 1030 over 210.5757 s), including the exact formulas behind
  `rechazados/s`, the percentage of refusals and the thread-time share.

**Pending on the device (this revision).** The two counters the correction has to
move, in one capture: `terse, prefijo u32 … campos vistos N · consumidos N` and
`ANTES … DESPUES` (expected `B …, C …` → `B 0, C 0`, `la referencia acepta` up,
`rechaza 0`); the corrected rate line and cost line, for the A/B against the
36.2 FPS / 27.6 ms CPU / 19.5 ms GPU baseline. TextureEntry is **not** declared
finished until those counters arrive.

#### The movement audit (2.13a-rev5) — a separate investigation, in `MOVEMENT-AUDIT.md`

The movement pad is on screen and the avatar does not advance. That is **not**
assumed to be a texture problem or a render-loop problem: it is its own
investigation, with its own document (`MOVEMENT-AUDIT.md`) and its own check.

**The chain, located.** pad (`WorldViewActivity.holdToMove` / `MovementActivity`)
→ handler (`SLConnection.setMoveAction`, `:605`) → state (`controlFlags`,
`bodyYaw`, `viewYaw`) → command (`SLConnection.sendAgentUpdate`, now through the
pure `slproto/movement/AgentUpdateBuilder`) → send (`Circuit.send` →
`SLPacketWriter` → `ZeroCodec` → `sock.send`) → the region's answer
(`handleAgentMovementComplete`, `adoptAgentObject`) → the local reflection
(`FilamentWorldView.applyFraming` aims the camera at `agentPosition` every frame;
the HUD prints it every 400 ms).

**What the earlier device log already proves.** Its `Fallo al enviar AgentUpdate:
sendto failed: EPERM` line is printed inside `Circuit.transmit`'s `catch`, i.e.
*after* the packet was built, encoded and handed to the socket — so the pad
reached the engine, the command was built, and the command was sent. The same log
shows `AgentMovementComplete` arriving and `Avatar propio detectado: id local
545575791`. What is missing is the *simulator's reaction* — and that is what the
new counters measure.

**The one confirmed deviation (M1).** The three camera axes are constants, and
they are not a frame: `at = (0,0,-1)` points straight down in a Z-up world, `up`
is its exact opposite, and the triple is coplanar (volume 0). Every reference
client sends an orthonormal right-handed frame for the same heading
(`at = (cos h, sin h, 0)`, `left = up × at`, `up = (0,0,1)`; evidence fetched to
`scratch/ref`: LibreMetaverse's `AgentManagerMovement.cs` + `CoordinateFrame.cs`),
and OpenSim builds the agent's camera rotation from exactly this triple
(`ScenePresence.HandleAgentCamerasUpdate` → `Util.Axes2Rot(at, left, up)`). A walk
forward is "away from the camera, along its at-axis"; our at-axis is the down
vector. **It is the prime suspect for case D, not a proven cause** — the counters
below are what prove it. The correction is one argument at one call site
(`AgentUpdateBuilder.referenceBasis(viewYaw)`) and it is deliberately **not
applied** in this revision, so the "before" measurement is clean.

**How the next run answers it.** `slproto/movement/MovementAudit.kt` counts each
link and `verdict()` names the one that stops the chain: **A** the input never
reaches the engine, **B** the engine never builds a command, **C** the command is
built and not sent, **D** the simulator does not update us, **E** the position
changes and nothing reflects it. The report carries one line per link (including
the basis sent next to the reference basis for the same heading, and
`primer desplazamiento`, the direction the body did or did not move), the HUD
carries one line, and the verdict box carries the letter. The `moveaudit` check
(50th, light group) asserts the control table against the reference, the
action→bit mapping, the encoded `AgentUpdate` byte for byte, the basis finding,
and the A–E classifier with its counters — so the classifier is tested, not
trusted. The harness also gained a fourth, compile-only pass over
`slproto.modules` **with nothing skipped**, which is what actually compile-checks
the session code the ui-world pass has to skip.

One practical warning, from that same log: the `EPERM` is Android refusing
`sendto` while the app was backgrounded, and it swallows *every* send while it
lasts, so the movement test has to be done with the world screen in the
foreground.


```
createMesh / destroyMesh
createTexture / updateTexture / destroyTexture
createMaterial / updateMaterial / destroyMaterial
createEntity / destroyEntity
updateTransform / setVisible / updateMaterials
setCamera / setSun / setBackgroundColor
render / stats / destroy
```

Everything crosses that boundary as plain descriptors (`MeshDesc`, `TextureDesc`,
`MaterialDesc`, `EntityDesc`, `CameraDesc`, `LightDesc`, `Transform`) — no
Filament type appears in any signature, which is what keeps the backend
replaceable.

`FilamentRenderer` notes worth knowing:

* Filament has **no `NORMAL` vertex attribute**: normals + tangents are packed
  into `TANGENTS` as a normalised `SHORT4` quaternion, derived once per mesh with
  Filament's own `SurfaceOrientation` helper (the same thing glTF importers do).
* Materials are compiled at runtime with `filamat` and cached per
  (alpha mode, double sided); `filamat` needs a fixed target API, so the same
  `FilamentBackend` value drives both the engine and the material compiler.
* A single-level texture must not use a mipmapping min filter — sampled that way
  it renders black in GLES. `samplerFor()` picks the filter from the mip count.
* The swap chain is destroyed *before* `surfaceDestroyed` returns (the render
  thread is asked to detach and acknowledges), while the engine, meshes and
  textures outlive the surface.

### Textures: the asset pipeline (phase 2.13b)

2.13a ended with the UUID readable on every face and nothing downloaded. 2.13b
turns that UUID into pixels on a face, in four stages that are each one class:

| stage | class | package | what it owns |
| --- | --- | --- | --- |
| wire | `TextureTransport` → `GetTextureTransport` | `slproto.asset` / `slproto.caps` | one blocking POST to the `GetTexture` capability |
| queue | `GetTextureAssetProvider` | `slproto.asset` | worker threads, de-duplication, cancellation, retries, timings |
| identity | `TextureAssetCache` | `slproto.asset` | one entry per `uuid@discard`, hit/miss/failed |
| pixels | `TextureDecoder` (seam), `TexturePipeline` | `slproto.asset` | decode threads, decode/upload counters, "what is still owed" |
| binding | `TextureStreamer` + `SLScene` | `slworld` / `slscene` | per-face materials, GPU upload, fallback |

The dependency direction is the point: `slproto.caps` imports
`slproto.asset`, never the other way round, so the asset package compiles and is
tested **without** the session, the login or the capabilities at all.
`GetTextureTransport` is the only file in the viewer that knows a texture comes
over HTTP.

**What is asked for, and when.** Only faces that are *drawn* and *near*: the
scan walks the slots the distance test already visited, keeps the visible ones
within `SLScene.TEXTURE_NEAR_METRES` (64 m), sorts them nearest-first and asks
for at most `perScanBudget` (24) **new** textures per scan, twice a second. A
region's far half is a few dozen pixels on a phone screen; downloading it would
cost tens of megabytes for detail nobody can see. This is also why the counter
"caras sin textura" is not a bug report: 256 m of region contains far more
textures than a view needs.

**Threading, and the one rule per stage.** `request` and `pump` are the render
thread's whole interface to the pipeline: `request` is a cache lookup plus a
queue push, `pump` is "take what finished", and neither can block on a socket or
on a decode. The blocking fetch happens on two provider workers ("EphoraTexture-n")
and the decode on two more ("EphoraDecode-n"); both are daemons, both start
lazily on first use, and the render thread is the only place a
`Renderer.createTexture` can happen. Everything per frame is bounded by *what
completed*, never by how much is outstanding, so a region with thousands of
pending textures costs the same frame as one with none.

**"Not ready" and "no decoder" are not failures.** Two of the four stages can be
absent without anything being wrong: the capability does not exist until the
seed resolves (a request made then is *deferred*, and the same face asks again
next frame), and fase 2.13b ships **no JPEG2000 decoder**
(`UnavailableTextureDecoder`, `isAvailable = false`). Bytes that arrive with no
decoder are cached and **waited for** — counted as "esperando decoder", never as
a decode failure — which is what makes 2.13b and 2.13c separable: when OpenJPEG
is linked in, the whole backlog decodes from the cache without a single extra
download. The check proves exactly that (`texturePipelineCheck`, step 4).

**Per-face materials, with the sharing kept.** `TextureEntry` is per face, so
`SLScene.attach` now asks `SLTextureCache` for one material per drawn face
(`SLTextureFace.facesOf(entry, mesh.faceCount)`) and hands the renderer an
`IntArray`. Two faces that coincide still share one material, because the cache
key is (texture, tint, material code, fullbright) — the 20-object test scene
still creates exactly 10 materials and reuses each one once, and the per-face
material ids are printed in the focus report so the sharing can be seen.

**Fallback, in three flavours, each named.** A face with no pixels yet keeps its
real `TextureEntry` tint when the wire carried one, and a per-texture debug tint
when it did not (the 2.13a behaviour). A face whose texture the grid refused, one
whose codestream the decoder refused, and one that was never asked for all end
the same way on screen — and differently in the report
(`cache: fallidas`, `decode: fallo`, `esperando pixels`). A backend that refuses
`createTexture` does not break anything either: the streamer catches it,
`upload: fallo` goes up, and the material keeps its tinte (`textureBindingCheck`
runs a whole scene against a renderer that always refuses).

**The counters, and who owns each.** The report prints them side by side rather
than merging them, because "4 assets fallidos" and "2 descargas fallidas" are
different facts:

| counter (report label) | owner |
| --- | --- |
| UUIDs solicitados (miss) / cache hit / cache miss / reintentos | `TextureAssetStats` (the cache) |
| peticiones emitidas / en vuelo / respuestas recibidas / errores de red / canceladas / bytes / tiempo de descarga (media, máx, última) | `TextureTransportStats` (the provider) |
| assets listos / fallidos / peticiones diferidas / cortadas por tope | `TexturePipelineStats` |
| decode OK / fallo / encoladas / esperando decoder / tiempo de decode | `TexturePipelineStats` + `TexturePipeline.waitingForDecode` |
| upload OK / fallo / rechazadas por el backend / bytes subidos / tiempo de upload | `TexturePipelineStats` (reported by the streamer, which makes the call) |
| materiales con textura / caras con textura / entidades con textura / texturas decodificadas | `SLTextureCache` + `SLScene` |
| memoria: caché (blobs, bytes) e imágenes (bytes) | `TextureAssetCache.cachedBytes` + `TexturePipeline.decodedBytes` |

All of it goes into one block, `SLScene.textureReport()`, which the HUD
(`textureHudLine`) and the saved report (`RenderDiagnostics.textureReport`) carry.
Nothing about it is inferred from another number.

**The switch.** `TEXTURES_ENABLED` in `FilamentWorldView` (true) decides whether
the scene gets a pipeline at all; with it off the viewer behaves exactly as in
2.13a, which is what makes an A/B run possible on one device.
`TEXTURE_DECODER_SYNTHETIC` (false) swaps the missing decoder for
`SyntheticTextureDecoder`, which invents a uuid-derived image so the decode →
upload → rebind → per-face binding chain can be exercised on the device before
OpenJPEG exists. It is a constant and not a button on purpose: a build that shows
invented pixels has to be a deliberate act, and the report labels it as synthetic.

**What 2.13c still has to add.** A real `TextureDecoder`: OpenJPEG through the
NDK, asked for RGBA8, honouring the discard level the asset was fetched at. That
is one class (`isAvailable = true`) and one line at the call site — no change to
the transport, the provider, the cache, the pipeline, the streamer or the scene,
because none of them looks inside a codestream. The gating condition the user
set — "integrate JPEG2000 once the asset flow is demonstrated" — is what the
counters above are for.

**Verified without a device.** Two checks, run by the Kotlin Playground harness
against the real sources: `texturePipelineCheck` (`texasset`, light group, 24th)
drives the real provider over a fake transport — de-duplication, cancellation
mid-flight, deferred-while-not-ready, the cap, the no-decoder wait, the 2.13c
hand-off without a re-download, a refused codestream, an HTTP error, re-arming
after the GPU side is dropped — and `textureBindingCheck` (`texbind`, heavy
group, 27th) runs a whole 20-object scene through it and asserts 10 downloads,
20 textured faces, 10 uploaded textures, 10 rebuilt materials, the fallback
scene, and every label of the report block. 52 checks at that point (53 after
2.13b-rev1 added `capsfail`).

The last runs of that suite: `texasset` **OK** (and OK again after the group was
regenerated, `mineErrors: 0`), `texbind` **OK** inside the 27-check heavy group,
light **24/24 OK**, heavy **27/27 OK**, and both compile-only passes — the render
loop file and `slproto.modules` with nothing skipped — clean. They were obtained
group by group and not in one `run_all.js` sequence, because the backend refuses
the whole payload in most windows (see the 450 kB note under *Verification*).

**Still open, on the device.** Whether `GetTexture` answers with a codestream
for the URLs the region actually sends (the transport's counters say so in one
run), and what the download+decode timings look like for a real region — the
reason the report prints bytes and milliseconds per stage rather than a single
"textures loaded" count.

### Revision 2.13b-rev1 — the camera basis is corrected, and every texture failure is now named

The 2.13b device run settled the movement question and opened the texture one.
This revision changes exactly two things: the movement command's camera basis, and
what a failed texture fetch tells us.

#### Movement: the one argument the audit prepared (M1 applied)

The audit (`MOVEMENT-AUDIT.md`) put the chain on the record — 79 presses, 337
`AgentUpdate` built, 337 sent, 0 send errors, **16 changes of the avatar's own
position** — so the pad, the engine, the circuit and the simulator all work, and
what is left is the *content* of the command. The content had one wrong thing:
the camera basis was the constant triple `at (0,0,-1)`, `left (0,1,0)`, `up
(0,0,1)`, which is not a frame (the at-axis is straight down, the volume is
zero), and the first movement the device observed was towards **−Z** — the
direction that triple points. The correction is the one line the audit prepared:

```kotlin
val basis = AgentUpdateBuilder.referenceBasis(viewYaw)   // was currentBasis()
```

Nothing else in the movement path changed. `currentBasis()` is kept and
documented as the historical M1 value, because it is now the *contrast* the report
prints next to what is actually sent; the `moveaudit` check asserts both.

The report prints, read back **out of the built packet** rather than from the
argument (offsets 77 / 89 / 101), so a device log proves what the wire carried:

```
enlace 3-comando, flags serializados 0x00000001 AT_POS  ·  centro de camara serializado (...)
enlace 3-comando, base de camara serializada: at (...)  ·  izquierda (...)  ·  arriba (...)  ->  COINCIDE con la base de referencia de un visor que funciona
enlace 3-comando, base de referencia (rumbo enviado X rad): at (1.00, 0.00, 0.00)  ·  ...
enlace 3-comando, base de la revision anterior (hallazgo M1, ya no se envia): at (0.00, 0.00, -1.00)  ·  ...  (NO es una base: volumen 0.00, at.arriba=-1.00)
enlace 4-simulador, posicion antes (...)  ·  posicion despues (...)  ·  desplazamiento neto N m  ·  recorrido total N m
```

`rumbo enviado (serializado, cuerpo)`, `rumbo usado (base de camara)`,
`ControlFlags serializados`, the camera centre, the
serialized basis, the yaw it was built from, the before/after positions, the first
delta, the path length and the net displacement are all in that block, as the
iteration asked. The two headings are kept apart on purpose: the body faces where
the viewer walks and the basis faces where the camera looks, so the comparison
uses the *view* yaw the basis was built from — comparing against the body heading
would print a false "NO COINCIDE" as soon as the user turns with the yaw buttons
(the `moveaudit` check now asserts exactly that case). Whether it *works* is a
device question, and this revision does
not claim it: what it can show off-device is that the bytes now carry the frame a
reference viewer would send.

#### Textures: 649 requests, 649 errors, and now a reason for each one

The device asked for 649 UUIDs, emitted 649 requests, had 0 in flight, received
649 responses — and 0 bytes, with 649 network errors. "Received" was counting the
callback finishing, not a success, which is why this revision makes the counter
discriminate:

```
TEXTURAS: ... respuestas recibidas N (con bytes X / con error Y)  ·  errores por tipo (...)
TEXTURAS: ... lectura A-F: A .. · B .. · C .. · D .. · E .. · F .. · suma N
TEXTURAS: ... primer error: <hechos>  ·  ultimo error: <hechos>
TEXTURAS: ... http ... GetTexture ...  ·  EventQueueGet ...  ·  GetMesh ...  ·  seed ...
```

Each failed `GetTexture` / `GetTextureTransport` records a `TextureFetchFailure`
with the facts needed to tell the six causes apart — and only facts, never
secrets: the HTTP status (when there was a response at all), the exception class,
the capability (`GetTexture`), the HTTP method, the response size, the
Content-Type, `Server` / `Via`, and a sanitized excerpt of the body. `HttpText.sanitize`
turns every URL into `[url]`, strips `token=` / `session_id=`, `Cookie:` and
`Authorization:` values, collapses whitespace and truncates, so the report the
user pastes in chat cannot leak credentials, tokens, cookies or full private
URLs. `Capabilities` keeps these per capability (`lastFailureOf`,
`failureCountOf`, `endpointHost`) and records the seed's own failures under
`seed`, which is what lets the report line up `GetTexture` against
`EventQueueGet` — the HTTP 500 / 502 Proxy Error in the same log.

`TextureTransportStats` then classifies every failure into one of six kinds, and
`failureReading` (in `TextureFormat.kt`) does the reading the iteration asked for:

| letter | what it means | the kind(s) that feed it |
| --- | --- | --- |
| **A** | capability missing or not offered | `CapabilityMissing` |
| **B** | endpoint wrong / capability not ready | `NotReady` |
| **C** | HTTP error from the server or grid | `HttpError` |
| **D** | proxy / transport problem | `TransportException`, and any failure with `Via`/`Server` a proxy wrote (`looksProxied`) |
| **E** | valid response with 0 bytes | `EmptyResponse` |
| **F** | local parsing | `Unclassified` on a response that was received |

The six kinds are actually six: `CapabilityMissing`, `NotReady`, `HttpError`,
`TransportException`, `EmptyResponse`, `Unclassified` — so "request sent",
"HTTP error received", "transport exception" and "valid response with 0 bytes"
are four different counters and never one.

**Verified without a device.** `moveaudit` **OK** in its own ~400 kB one-check
group (the rewritten check, which now reads the basis back out of the packet,
asserts the corrected frame is orthonormal and equals the reference for the view
yaw, and asserts that case where the body heading differs from the basis yaw);
`capsfail` **OK (1 check)** — `capabilityFailureCheck`, a new light-weight group
that feeds fabricated responses through the real classifier and asserts the
sanitizer (a token, a session id, a cookie and a full URL all redacted), the
facts of a proxy 502, the six kinds, and the A–F partition (`A 1 · B 0 · C 1 ·
D 2 · E 1 · F 1 · suma 6`); `texasset` **OK**; and `light` **24/24 OK** in an
earlier, better window of the backend. 53 checks in total; the two compile-only
passes (`slproto.modules`
with nothing skipped, and `FilamentWorldView.kt`) were clean **200, 0
errores** earlier in the same session, and the backend was refusing every payload
over ~400 kB by the end of it (a 500, not a compiler error). The `heavy` group
(27 checks, `texbind`) passes
`27/27` when the backend accepts the payload; during this revision's window it
returned HTTP 500 repeatedly, and the evidence that this is the runner and not
the code is that the *same* 54-file payload returns 200 with `entry: false`
(compile only), that cutting the check code does not shrink it (a `texbind`-alone
group still measures 605 kB, because the project sources dominate), and that the
checks it covers were green before this revision's
purely additive report lines landed — see the 450 kB note under *Verification*.
It is re-run, not assumed.

**Still open, on the device.** Whether the corrected basis makes the avatar walk
forward (that is the user's next run), and which of A–F the 649 failures are —
the report now says, per error, enough to choose one.

### Revision 2.13b-rev2 — why the asset CDN answered 403, and the FORWARD window

The device run of 2.13b-rev1 answered the movement question (the corrected camera
basis serialized as a real frame, `COINCIDE`) and produced one hard fact about the
textures:

```
peticiones 755  ·  respuestas 755  ·  errores 755  ·  bytes 0
todos HTTP 403  ·  endpoint asset-cdn.glb.agni.lindenlab.com  ·  Content-Type text/html  ·  398 B
cuerpo: "Access Denied ... asset-cdn.glb.agni.lindenlab.com/"
```

while the movement report showed `flags serializados 0x00000000` — which, as the
user pointed out, is the *release* packet and therefore proves nothing about the
press. This revision answers both, and changes only those two things.

#### The 403: the request *form*, not the endpoint

The capability was offered, the endpoint was the right host, and the response
arrived — so `A`, `B`, `D`, `E` and `F` of the reading were all out and the
question was `C`: an HTTP error. The official viewer's code says why, and it is a
difference in the request, not in the grid:

| | official viewer | this viewer up to 2.13b-rev1 |
| --- | --- | --- |
| capability asked of the seed | `ViewerAsset` (`llviewerregion.cpp` builds the capability list and routes `ViewerAsset` to `mViewerAssetUrl`; it never asks for `GetTexture`) | `GetTexture` — which on agni resolves to the same asset CDN base |
| when the URL is taken | when the region's capability map resolves, and again after a crossing (the URL is re-read, not cached) | when the seed resolves |
| final URL | `http_url + "/?texture_id=" + mID` (`lltexturefetch.cpp`: `setUrl(http_url + "/?texture_id=" + mID.asString())`) | the base URL, unchanged |
| method | `GET` (optionally a byte-`Range` GET) | `POST` |
| body | none | LLSD XML `{texture_id, discard_level}` |
| headers | llcore defaults | `Content-Type`/`Accept: application/llsd+xml` |
| response | the raw JPEG2000 codestream | the same expectation |

An `asset-cdn` host is the CloudFront distribution in front of the asset store:
it serves `GET /?texture_id=<uuid>`, and a body-carrying `POST` to its root is
refused before it is read — which is exactly the body the device got (`Access
Denied` naming the host *root*, i.e. a URL with no texture id anywhere the
distribution looks). The second reference is the open PR `secondlife/viewer#6329`
("Retry HTTP 403 Forbidden texture fetches on region crossing"), which documents
the same `403` from the same CDN when the capability URL or its signature is stale
after a region crossing. It is a *secondary* reference: it shows that a 403 from
that host is a known failure mode, not that our case is identical — and the retry
it proposes is not what this revision does, because our URL is not stale, it is
being asked the wrong way.

So the transport now chooses, from the URL the region handed us
(`assetRequestFor` in `GetTextureTransport.kt`):

* **no path** → the asset CDN base → `GET <base>/?texture_id=<uuid>`, no body;
* **a path** → a legacy capability endpoint → the LLSD `POST` it has always taken
  (`{texture_id, discard_level}`), which is what keeps an older region working.

A URL that already ends in `/` or already carries a query is handled explicitly
(the official viewer's plain concatenation would produce `//?` and `?x=1/?`), and
the report prints both the **shape** of the region's URLs (scheme, host, whether
there is a path, whether there is a query, and the query's key *names* — never the
values, which can be a signature) and the **form** of the request that was sent.
Nothing else about the textures changes; `TextureEntry`, parent/child and the
render loop are untouched, and there is still no JPEG2000 decoder.

The 403 and the 502 stay separate categories: `C` counts HTTP errors that are not
a proxy in the path, `D` counts a proxy or a transport failure, and the check
asserts that a plain 403 from the CDN lands in `C` while the `502 Proxy Error` of
`EventQueueGet` lands in `D` — on the same counters, in one run.

#### The FORWARD window: what the last packet cannot say

Every run's last `AgentUpdate` is the release, so it carries `0x00000000` even if
every packet sent while the button was down carried `0x00000001`. The audit now
counts the *window* between the press and the release (`MovementAudit`, one window
per FORWARD press):

```
ventana FORWARD: pulsaciones N  ·  ahora PULSADO/soltado
ventana FORWARD, comandos con la tecla pulsada: U serializados · con AT_POS (0x00000001) F · sin AT_POS G · con NUDGE (legacy) 0
ventana FORWARD, flags serializados de esos paquetes: 0x00000001, 0x00000401
ventana FORWARD, primero con AT_POS: secuencia S · flags 0x… · posicion (…)
ventana FORWARD, posiciones propias: antes de pulsar (…) · con la tecla pulsada (…) · despues de soltar (…)
ventana FORWARD, movimientos durante la ventana: D cambios · recorrido P m · neto desde la pulsacion M m · primer delta (…) · ultimo delta (…) · deltas (…)
```

`U` and `F` are the answer to "did FORWARD=0x00000001 really travel while the key
was down"; `F == 0` with `U > 0` means the flag is being dropped between the
handler and the wire. The three positions and the per-change deltas answer "does
the avatar move *then*, and which way" without depending on the run's final state
— which matters, because the previous run reported `recorrido 187 m` with
`desplazamiento neto 0.04 m`: 14 position changes averaging 13 m that end back
where they started, and this is what tells that apart from a walk. The NUDGE bits
(`0x00080000`…, "legacy" in the reference client, and never generated by this
viewer) are in the flag table too, so an unknown bit in the `ControlFlags` word
can never be printed as a mystery hex value.

#### What was verified without a device

`moveaudit` **1/1 OK** (its own ~400 kB group) with the window counters, the
before/during/after positions, the deltas, the net, the flag table including the
NUDGE bits, and the assertion that the window remembers `AT_POS` while the last
packet is `0x0`; `capsfail` **1/1 OK** with the URL-form assertions (CDN base →
`GET ?texture_id=`, trailing slash, existing query, path-shaped capability → LLSD
`POST`), the `403` carrying its `GET`, and the 403/502 split across `C` and `D`;
`texasset` **1/1 OK, three runs in a row**; `slproto.modules` compile-only (nothing
skipped) **200, 0 errores**; `ui/world/FilamentWorldView.kt` **0 errores** in its
own pass.

The `light` group did reach the backend once — and that run found a **flaky
assertion in the harness**, not in the app: `texturePipelineCheck` asks the
provider for the same texture twice and expects the second to be a duplicate, but
the worker is fast enough (more so after 23 checks have warmed the JVM) to finish
the first fetch before the second call lands, which makes the second a genuine new
request. The check now holds the transport **blocked across the two calls**, so
the key is still in flight and the duplicate is deterministic; `texasset` then
passes three times in a row. The `light` group itself could not be re-run to see
`24/24` — together with `heavy` (27 checks) it was refused with HTTP 500 by the
playground backend in every window this session (30+ attempts), which is the
documented ~450 kB limit, not a verdict about the code. Both are re-run, not
assumed — see the 450 kB note in `tools/kcheck/README.md`.

#### Resolved by the next device run

Both objectives were answered by the 2.13b-rev2 device run:

* **B — the textures work.** `peticiones 412 · respuestas 412 (con bytes) · 99.1 MiB
  recibidos · errores 0 · assets listos 412 · assets fallidos 0`. The request form
  was the whole problem: the CDN answers the `GET <base>/?texture_id=<uuid>` the
  official viewer sends. **The `GetTexture` transport and `TextureEntry` are
  therefore not to be touched again**, and the next functional step is 2.13c
  (a JPEG2000 decoder behind the existing `TextureDecoder` seam, producing RGBA8).
* **A — `AT_POS` does travel.** The serialized basis `COINCIDE`s with the
  reference, `AT_POS = 0x00000001` is present during FORWARD, and the packets go
  out without error.

But the same run left two numbers that do not add up, and this revision is only
about making them measurable:

```
50 pulsaciones  ·  solo 2 AgentUpdate serializados con FORWARD=1
0 cambios de posicion  ·  0 m de recorrido durante la ventana
```

### Revision 2.13b-rev3 — the temporary FORWARD-window trace

**No protocol change, no camera basis change, no texture change.** The only code
that moved is `MovementAudit`: a *temporary* interleaved trace, marked as such in
the source, that answers the six questions the 2.13b-rev2 counters could not.

The refuted assumption is that the window's counters describe the *press*. They
describe the packets that happened to be *serialized* while the window was open,
and `sendAgentUpdateAsync()` hands the send to `Dispatchers.IO` — the packet for a
press can therefore be built after the release has already cleared the flag. That
is a **hypothesis**, and the trace exists to confirm or kill it, not to be
believed. What it adds:

| what | where | answers |
| --- | --- | --- |
| cumulative totals over *all* windows, not just the last | `totales: ventanas N · paquetes con la tecla pulsada U (con AT_POS F · sin AT_POS X) · ventanas sin ningun AT_POS W · enviados DESPUES de soltar (dentro de 1200 ms) L (con AT_POS FL)` | the "50 → 2" question, in one line |
| window duration, times, intervals | `tiempos: duracion de la ultima ventana D ms · tiempo total con la tecla pulsada T ms · primer AT_POS t=… · ultimo AT_POS t=… · intervalos entre AT_POS (…) ms` | duration and cadence, in-window *or* late |
| the engine's own control word, sampled independently of any packet — and specifically while the button was down | `estado interno del motor: muestras N (con AT_POS M) · muestras con la ventana ABIERTA N2 (con AT_POS M2)` | internal state vs. serialized state |
| the sends that arrived **after** the release | `envios TARDIOS (…): L (con AT_POS FL) · ultimos: +3 ms AT_POS · +3 ms sin` | the hypothesis, as a list |
| each position change tied to the sequence that preceded it | `posiciones y secuencias: (dx,dy,dz) tras seq S` | which send caused which movement |
| a per-window summary, the last 12 | `ultimas ventanas` | the pattern across taps, not just the last one |
| the interleaved line of time itself | `traza (t=ms; P=pulsado S=soltado; interno=…; E=enviado; M=movimiento)` | everything above at once |

A send that lands with **no** window open but within 1200 ms of a FORWARD event is
counted as *late* and listed (`+3 ms AT_POS`, `+3 ms sin`), which is the exact shape
the hypothesis predicts: many late unflagged sends, few in-window flagged ones.

The `moveaudit` check now drives all of it off the device: totals, the window log,
non-zero timestamps, the `M`/`E`/`P` entries in the trace, and — the case the whole
revision is about — a tap whose `AT_POS` packet is serialized *after* the release,
which has to land in `forwardLateFlagged` and in a window counted as empty. That
scenario is the device's "50 → 2" made deterministic.

What this revision does **not** do: fix anything. It measures. Until the trace comes
back from the phone, the movement report stays open, and nothing here is called
corrected.

## Second Life → Filament: the transform contract

Written down because "the coordinates are wrong" is one of the two ways a black
screen happens (the other being "nothing was ever submitted"), and this is
exactly what the diagnostic mode checks.

* **Frame and units.** Second Life is **right-handed, Z up**: X east, Y north, Z
  up, metres. The viewer keeps that frame *all the way through* — `SceneObject`
  (protocol) → `SLObject`/`SLTransform` (world) → `Transform` (renderer seam) →
  the 4×4 handed to `TransformManager`. There is no axis swap, no handedness
  flip and no unit conversion anywhere on the path, so an object at
  `(167.954, 142.02, 22.5211)` is at that same point in Filament's world.
* **Rotation.** The simulator sends a quaternion in **xyzw** order
  (`ByteReader.quaternionFromXyz`, packed 10-bit form in
  `quantisedQuaternion`); `Transform.rotation` is that same xyzw array. A region
  object's rotation is the quaternion the region sent — it is not re-normalised,
  re-ordered or re-based.
* **Position/scale.** `translation` and `scale` are also the raw wire values: SL
  prim scale is per-axis, and it is applied per-axis.
* **Matrix.** `Transform.toMatrix16` emits **column-major** `T · R · S` (rotation
  matrix columns multiplied by `sx, sy, sz`, translation in the last column),
  which is what `TransformManager.setTransform` expects.
* **Camera.** Filament's own convention is Y-up (camera looks down −Z), so
  `SLCamera` produces a Z-up eye/target and the backend passes `up = (0, 0, 1)`
  to `Camera.lookAt` plus `Camera.Fov.VERTICAL`. The up vector is explicit, so
  there is no global "rotate the world 90°" step to get wrong — the HUD prints
  the camera's eye, target, forward, near, far, fov and aspect so the frame can
  be checked against the world bounding box on screen.
* **The read-back (phase 2.9).** None of the above is taken on trust any more:
  `Renderer.entityLocalMatrix` / `entityWorldMatrix` return what `TransformManager`
  actually holds (`getTransform` / `getWorldTransform`), and
  `Renderer.cameraSnapshot` returns the view and projection matrices, the eye and
  the basis the engine's *own* camera has (`getViewMatrix`, `getProjectionMatrix`,
  `getPosition`, `getForwardVector`, `getLeftVector`, `getUpVector`, `getNear`,
  `getCullingFar`). The audit prints all of them next to the values that were
  sent, with the numeric difference, so "the transform reached the engine" and
  "the engine is using the camera the scene set" are measurements.
* **Local vs global.** For region objects the transform *is* the absolute region
  transform. A linkset child's `ObjectUpdate` also carries an absolute position
  (the simulator resolves it), so children become independent entities at that
  absolute transform rather than being re-composed from a parent. `Transform.parent`
  exists for the cases that really are relative — attachments hung off an avatar
  — and goes to `TransformManager.setParent`; until Phase 8 those objects are
  skipped and counted rather than drawn in the wrong place.
* **What is deliberately not converted.** Nothing. If a future change introduces
  a conversion, it belongs in `SLObject`/`SLTransform` (one place, testable
  headlessly), never scattered through the scene or the backend.

## Verification

Three gates, in increasing cost:

1. **`slcore` native self-test** — `slcore_tests.cpp` builds the real `LLVolume`
   tessellator for 12 shape parameter sets and checks the results (vertex counts,
   face groups, bounds, winding, the box/cylinder/prism/sphere/torus/tube
   topologies, cuts, hollows, tapers). CI compiles and runs it with the host
   `c++` before touching Gradle, so a broken tessellator fails the build.
1b. **JNI bridge** — the host `c++` self-test above does *not* see
   `slcore_jni.cpp` (it needs `jni.h`). The bridge is compiled with the same
   flags Gradle hands to the NDK (`-fno-exceptions -fno-rtti -Wall -Wextra`)
   against a real `jni.h`, so a bad cast or a wrong `MeshData` field in the
   array hand-off is caught before it can break the Gradle native build. CI
   builds that native phase on its own first (`:app:externalNativeBuildDebug`,
   see below), which covers the same ground with the NDK's own toolchain.
2. **Kotlin compile + logic harness** — every file under `app/src/main/java` is
   compiled with a real Kotlin 2.0.21 compiler against hand-written
   Android/androidx/Filament stubs, then run headless with the graphics backend
   replaced by a recording fake. The harness lives in `tools/kcheck/` and its
   entry point is `tools/kcheck/run_all.js` (`tools/kcheck/README.md` explains the
   procedure); it is the same harness that was developed in the editor's scratch
   space, moved into the project so it travels with it.

   The playground endpoint that hosts the compiler returns **HTTP 500** for
   submissions over roughly **450 kB** — no compiler error, no exception, just
   `{"message":"Internal Server Error"}` after ~50 s — and the threshold moves
   with the backend's load: the light group (504 kB) passes in some windows and
   not others, the heavy group (659 kB) needs many tries. So the harness (a) drops
   **whole-line** comments from the sources it submits — which cannot touch a
   string literal and buys ~200 kB — and (b) compiles **subsets**, by transitive
   package closure: the *pipeline* subset (protocol world + world + scene +
   renderer, which is what the checks run against) and, separately, the one file
   the pipeline subset cannot reach, `ui/world/FilamentWorldView.kt` (the UI tree
   needs the Filament AAR for real, so only that file is compiled, with the
   packages it does not need skipped — the errors the skipped packages leave
   behind are in *their* files, never in the file under test). The full tree is
   left to Gradle in CI.

   **A 500 means nothing about the code**, so `run_all.js` retries the two big
   groups in alternating rounds for up to 45 minutes and records every attempt in
   `result.attempts`; both big groups have been through (heavy **27/27 OK**, light
   **24/24 OK**, zero errors in the app's own files), the small `capsfail` group
   (**1/1 OK**, the capability-failure diagnosis of 2.13b-rev1) and the `moveaudit`
   group (**1/1 OK**, the movement correction of the same revision) pass even in
   the windows the big groups are refused, and the two compile-only
   passes (the render-loop file, and `slproto.modules` with nothing skipped) go
   through even in a bad window. The small group
   `extra/tests_texasset.kt` (one check, 389 kB, generated by `run_all.js`) is the
   fallback that always passes, so a green run of the newest code is always
   reachable. `tools/kcheck/README.md` has the measured table and the recipe.

   The canonical test file is `tools/kcheck/extra/tests_render.kt` — **53 checks**. It
   cannot compile as a whole (its `bootstrapCheck` calls
   `renderer.filament.FilamentBootstrap`, which needs the real AAR), so
   `make_group.js` slices it into runnable groups, one per set of package needs:
   `tests_light.kt` (24 checks, no `slscene`/`slworld`), `tests_heavy.kt` (27
   checks, needs both) and `tests_capsfail.kt` (1 check, `capabilityFailureCheck`,
   which needs `slproto.caps` but no scene — see *Revision 2.13b-rev1*). `run_all.js`
   regenerates all of them from the canonical file,
   runs them, compiles `FilamentWorldView.kt`, and compiles `slproto.modules`
   with nothing skipped (the session code the ui-world pass has to skip); it
   asserts that the three groups plus the one unrunnable check add up to 53, so a
   check added to the canonical file cannot be silently left out. It covers the
   protocol self-test, the terrain
   codec, the LLSD codecs, and fifty-three pipeline checks: shape classification, the
   `PCode` values and what they classify as (with the "a prim with no
   path/profile block must not be given a shape" rule), the mesh geometry
   validator (including the "no face groups" case that produced
   `length=0; index=-2`), transform maths, sRGB→linear colour, wire-parameter
   packing and mesh-key stability, the test scene, `SLWorld` diffing, the camera,
   camera framing of a bounding box, the full scene pipeline (mesh/material
   creation + reuse, distance culling, transform-only updates, removals,
   teardown), the scene's refusal handling (an invalid entity stores no slot,
   is counted and named, and the rest of the region still builds), the per-object
   log and the report's caps, the mesh cache, that avatars are recorded and *not*
   drawn, the probe's meshes (outward winding, exact bounds, sphere
   radius/normals), the terrain mesh (decimation, heights in region metres,
   up-facing winding, rebuild coalescing), the diagnostics record (the HUD text
   carries the real numbers, keeps the first error, and reports the three
   acceptance tests honestly), the start-up log (a failure keeps its class,
   message, cause chain and stack trace; the first error is preserved while the
   retry is visible as the last one; the native environment and the
   `SurfaceHolder` callbacks reach the report), and — since Phase 2.8 — the
   object-update decoder itself: a hand-built **113-byte** `ObjectUpdateCompressed`
   block whose path/profile must be read, the same block with an `ExtraParams`
   length of `0xCC000007` (which must be refused and reported, not used as an
   index), the incremental transition of one object across terse/compressed/terse
   updates, that a definition-less prim reads `UNKNOWN/MISSING_SHAPE` instead of
   a default cylinder, the shape census, that the object list and the renderer
   read the same shape text, and the entity-budget ramp — and, since Phase 2.9,
   the visibility trace: the frustum maths condition by condition, the agreement
   between the basis-based and matrix-based tests, the whole audit over the test
   scene, the camera probe's placement and teardown, and the reversibility of the
   camera lock plus the `REAL_PRIM_TEST` before/after pair — and, since Phase
   2.10, the parent/child chain (a compressed block carrying `ParentID` delivers
   it, a terse one leaves it alone, a compressed one without the flag *keeps* the
   parent it already had, a compressed one that carries it as 0 unlinks; two
   children in a scene are reported as losing their parent in
   `SLScene.attach()`), and the focused-object report, including the case where
   the renderer's remembered component instance is no longer the entity's own
   (the fake moves the component the way Filament's swap-and-pop compaction
   does), where the report must say where the discrepancy appears — and, since
   Phase 2.11, the correction itself: children delivered before their parent are
   resolved in a second pass without being moved (local TRS kept, composed region
   position correct), the relation reaches the engine, and a parent that
   disappears leaves its children pending with no dead handle — and, since Phase
   2.12, the scalability switches: distance culling takes objects out of the draw
   set without destroying an entity, a mesh or a material, switching it off
   brings all of them back, and the shadow LOD reduces far prims and restores
   them with geometry and materials untouched; and, since Phase 2.12b, the
   frustum diagnostic's single sample point: a linkset child whose local and
   region positions differ is judged by its *region* placement (`parentWorld ×
   local`), so a camera aimed at the child reports it inside instead of "behind
   the camera", and the report's basis and matrix routes agree because they no
   longer test two different points; and, since the revision of Phase 2.13a, the
   `TextureEntry` classification itself: the eleven-field blob, the ten-field one
   the reader accepts but the writer never emits (`belowWriterMinimum`, not a
   truncation, with and without its final separator), a 17-byte UUID-only blob,
   cuts inside a default value / inside an exception value / inside the variadic
   bitfield (each naming its stop field and the bytes that were left), a cut
   inside the *optional* material (reported as truncated **and** counted as the
   case the reader tolerates), an update whose texture section declares an
   impossible length (1..62 bytes → attributed to framing, category D), the
   A–E totals and the A–E split per message path (with the categories checked to
   cover every blob exactly once), the stop-field histogram, and that a kept
   sample carries its blob's hex in full plus the reference reader's verdict — the
   check fails if the report prints " mas)" anywhere — and, since the *second*
   revision of 2.13a, the reference reader itself (its 46-byte boundary case by
   case, its verdict against a 4273-blob corpus, the parser/reference equivalence
   and the one documented divergence), the framing of a `TextureEntry` inside a
   real terse message (the length word's position, the declared length, that the
   blob ends the message body and that the body was read whole), and the render
   loop's audit (the policy lines, the rate arithmetic over a window, and the mean
   cost per presented frame against the mean cost per refused attempt).
   (Frustum culling is the engine's
   own test and is deliberately *not* asserted here: the harness cannot build a
   Filament `View`, and faking one would turn a claim with no evidence green.)
3. **GitHub Actions** — the real Gradle + NDK build of both APKs. This is the
   only gate that can see the resource/manifest/ABI side, and the only one that
   links the actual Filament AARs, so a device test is still required for
   anything visual.

## Notes for whoever touches this next

* `MessageTemplate` is loaded once, from assets, on a background thread at app
  start; the login flow waits on that `CompletableDeferred` before connecting.
* Every outbound field goes through `MessageTemplate` + `SLMessageCodec`, so
  adding a message means only calling `MessageTemplate.byName("...")` and setting
  fields — there is no per-message boilerplate to write.
* The world layer is a **pull** model: the network threads only write
  `WorldModel`, and `SLWorld.sync` reports what changed (it early-outs on the
  model's version counter). Nothing is pushed across threads, so the render path
  needs no lock. `SceneObject.revision` is what distinguishes "changed" from
  "unchanged", and `WorldModel.put` only ever moves it forward.
* `slscene/` and `slworld/` must never import `com.google.android.filament.*`.
  If you find yourself wanting to, the missing piece belongs in
  `renderer/Renderer.kt`.
* **Never build a Filament object in a class initializer.** Not in a `companion
  object`, not in a top-level `val`, not in a field initializer written above
  `init`. Filament's classes load their JNI library from their own static
  initializer (`Filament.init()` is empty in 1.75.1 — see `Filament.java`), and a
  Kotlin `companion object` is initialized by the class's `<clinit>`, i.e.
  *before* the constructor body. `TextureSampler(...)` calls native
  `nCreateSampler` in its constructor, so building the samplers in
  `FilamentRenderer`'s companion made the class initializer fail; the JVM then
  marks the class erroneous and every later attempt throws
  `NoClassDefFoundError: ...FilamentRenderer`. That is exactly the bug that made
  the renderer report "no engine, no viewport, 0 frames". Build Filament objects
  inside `init`/methods after `FilamentBootstrap.ensureLoaded()`, and if you need
  a shared Filament object, create it lazily on the render thread. The harness's
  `bootstrapCheck` plus a scan of `app/src/main/java` (no property without an
  enclosing function may construct a Filament type) guard this.
* **Do not trust "the .so is in the APK" as proof that it loaded.**
  `FilamentBootstrap.preflight` loads each required library by hand and reports
  the real throwable; the environment block it produces is part of every report.
  When a device says `engine NO`, that block (`--- entorno nativo ---`) plus the
  `--- pasos de arranque ---` list is the whole diagnosis, and it is written to
  `filesDir/ephora-filament.log` without being asked.
* Start-up failures are recorded through `RenderDiagnostics.failDetailed`, which
  keeps the class, the message, the cause chain **and** the stack trace.
  `fail(stage, message)` (the old path) is for things that have no throwable —
  never for an exception, because "Filament no pudo iniciarse" without the
  exception is what this phase was spent undoing.
* **`EditText.text = "..."` does not compile on Android.** `EditText` narrows
  `getText()` to `Editable` while the inherited setter still takes
  `CharSequence`, so the synthetic `text` property is not a `String` property:
  use `setText("...")` for filling an input, and `.text.toString()` for reading
  it. `TextView` (`text = "..."`) is fine. The Kotlin harness models this, but
  if CI ever reports a type mismatch that the harness accepted, fix the stub as
  well as the call site — the stubs are a *model* of the platform, and a stub is
  only as good as the last mismatch it reproduced.
* `lint { checkReleaseBuilds = false }` is deliberate: `assembleRelease` runs
  `lintVitalRelease`, whose fatal-severity issues (usually "missing class" noise
  from the unused parts of a big prebuilt AAR like Filament's) must not be able
  to block the APK. Use `gradle :app:lintDebug` to read the report.
* **The `PCode` byte is a class, not a shape, and the values are Linden Lab's.**
  `9` is `LL_PCODE_VOLUME` — every ordinary prim — and `255` is the legacy tree;
  `SLObjectKind.of` treats everything that is not avatar/grass/tree as `PRIM`.
  The shape comes only from `(pathCurve, profileCurve, profileHollow)` via
  `PrimShapeClassifier`. If you find a hard-coded pcode literal anywhere, check
  it against `indra/llmath/llvolume.h` (the values are listed in
  `SceneObject`'s companion, with the enum names) before changing it.
* **A `MeshDesc` must pass `MeshDesc.geometryProblem()` before a backend sees
  it.** An empty `faceGroups` array makes `faceCount` zero, and a backend that
  indexes face groups by face index (`faceGroups[min(i, faceCount - 1) * 3 + 1]`)
  then reads `faceGroups[-2]` on a length-0 array — an
  `ArrayIndexOutOfBoundsException: length=0; index=-2` *inside* `builder.build()`,
  far from the data that was wrong. That is exactly what a grass/tree mesh
  without `beginFace`/`endFace` used to cause, once per object. If you add a mesh
  builder to `sl_prim_geometry.cpp`, wrap its triangles in a face group and set
  `mesh.faces`; `slcore_tests.cpp`'s `checkMesh` asserts that groups cover every
  index.
* **Never give an object geometry it was not told to have.** `SceneObject.paramsKnown`
  is false until the path/profile block actually arrives (`ObjectUpdate`, or a
  compressed update long enough to carry it), and `SLObject.from` returns a null
  `prim` in that case, which makes the object non-renderable. The compressed
  layout is LL's `processObjectUpdateCompressed` order — header (with its 4-byte
  CRC), flag-selected optional blocks, extra parameters, path/profile,
  `TextureEntry`, texture animation — and its flag bits are `0x01 ScratchPad,
  0x02 Tree, 0x04 HasText, 0x08 HasParticles, 0x10 HasSound, 0x20 HasParent,
  0x40 TextureAnim, 0x80 AngularVelocity, 0x100 NameValues, 0x200 MediaURL`.
  A 4-byte-shifted read here is silent: it produces plausible-but-wrong
  positions and shapes. Read `ObjectUpdateDecoder`'s class comment before
  touching the layout: the `ExtraParams` section is *length-delimited inside the
  blob*, not "the rest of it", and a `U32` length is unsigned (0xCC000007 is
  3 422 552 071, not −872 415 225).
* **A partial update never rewrites a definition.** `noteUpdateWithoutShape`
  exists so that "this message did not say" can never be read as "the object has
  no shape". If you add an update type, route it through `noteShapeReceived` /
  `noteUpdateWithoutShape` rather than assigning path/profile directly.
* **A shape label must come from `SceneObject.shapeText`.** The object list, the
  scene log and the per-object table all read it; `ensure` that stays true, since
  two shape paths is exactly how the list said "(cylinder)" while the renderer
  said "no shape".
* Do not invent protocol behaviour. Field names, message ids and asset layouts
  come from the official viewer
  (https://github.com/secondlife/viewer) and from the shipped
  `message_template.msg`; `TODO.md` names the exact files each later phase has to
  read. Never fill a gap with plausible-looking data: leave it out and let the
  debug overlay say what is missing.
* **The terse `TextureEntry` field carries its own length in front of the entry.**
  `ObjectUpdateDiagnostics.consumeTersePrefix` removes those four bytes, and only
  when they equal the number of bytes that follow them (a grid that does not write
  them keeps working, and shows up as `sin prefijo que coincida`). That is a
  property of `ImprovedTerseObjectUpdate`'s *field*, not of the entry: do not
  "fix" it inside `TextureEntry`, and do not add it to the full or compressed
  paths — the compressed one already consumes its own u32 inside `Data`. The
  evidence is in `tools/kcheck/ref/terse_prefix_evidence.md`.
* **Filament is pinned to 1.75.1 on purpose.** Its AAR metadata is what AGP's
  `checkDebugAarMetadata` reads: 1.76.0/1.76.1/1.77.0 declare
  `minCompileSdk=37` (they were built against Android 17's SDK) and ship Java 21
  bytecode, while this project compiles against API 35 and emits Java 17, so any
  of those versions fails the build before compiling a single line. 1.75.1 needs
  no minimum compile SDK and its classes are Java 17. Before bumping the version,
  check the AAR's metadata:
  `unzip -p filament-android-<v>.aar META-INF/com/android/build/gradle/aar-metadata.properties`
  — and if it does require a newer SDK, raise `compileSdk` in
  `app/build.gradle.kts` *and* the `platforms;android-<n>` package installed by
  `.github/workflows/build.yml` in the same change.
* The renderer owns no GL state between contexts: the engine outlives the
  surface but the swap chain does not, and mesh/texture uploads are re-done from
  the cached descriptors if a resource is invalidated.

## Licensing / provenance

* `assets/message_template.msg` is Linden Lab's message definition file, shipped
  by every third-party viewer; it is redistributed unchanged as protocol data.
* `sl_prim_geometry.cpp` is a port of the official viewer's
  `indra/llmath/llvolume.cpp` tessellation (Second Life viewer source, LGPL/LL
  terms) reduced to what a prim needs; the ports of each rule are named in the
  comments next to the code.
* The protocol implementation is written from scratch in Kotlin, using
  libopenmetaverse and the public Second Life protocol documentation as
  references.
* "Lumiya" is the name of the original viewer by its author; this rebuild
  (`Ephora Viewer`) is an independent community project, not affiliated with
  Linden Lab or the original author.

## Next steps

See `TODO.md`.


## Recovery 2.13c-recovery2
Native JPEG-2000 decoder now accepts valid component subsampling/origins exposed by the real device diagnostic. See `EPHORA-RECOVERY-2.13C-BASE.md`.

## Recovery 3 — camera-responsive loading (2.22)
The current pacing patch limits expensive new-object insertion to one object per frame and pauses new insertions during camera touch/pinch. See `EPHORA-2.22-RECOVERY3-PERFORMANCE.md` and `AI-OPERATING-RULES-EPHORA.md`.
