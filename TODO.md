# Ephora Viewer — TODO

The viewer connects, walks, talks, reads inventory, keeps the session alive in
the background, and now renders the **real region objects** through Filament.
What follows is the phase plan. Each phase has to compile and leave a verifiable
test before the next one starts, and no phase may break the login/connection
path (which is feature-complete and untouched by the graphics work).

Reference sources, in order of authority: the official viewer
(https://github.com/secondlife/viewer), the shipped
`app/src/main/assets/message_template.msg`, and the Second Life wiki. Field
names and asset layouts are **read from those**, never guessed.

---

## Phase 1 — Filament foundation — **DONE**

- [x] `SurfaceView` + `SurfaceHolder.Callback`, dedicated render thread, swap
      chain released before `surfaceDestroyed` returns.
- [x] Filament `Engine`, `Renderer`, `Scene`, `View`, `Camera`, `Renderable`.
- [x] Basic lighting (directional sun + uniform ambient as a stand-in for
      WindLight), skybox background, MSAA, frame-time stats (CPU + GPU + FPS).
- [x] A visible test pattern of real prim shapes, reachable without logging in:
      **Prueba 3D (Fase 1)**.

## Phase 2 — Second Life primitives — **DONE**

- [x] `ObjectUpdate`/`Compressed`/`Terse` → internal `SLPrimitive` per object,
      keeping UUID, position, rotation, scale, geometry, parent/local transform
      and the `TextureEntry` default face.
- [x] Real geometry for the basic prim shapes, ported from the official viewer's
      `llvolume.cpp` (`slcore`, C++/NDK) — line/circle/flexible paths,
      circle/square/triangle/half-circle profiles, cuts, hollows, tapers, twist,
      revolutions, shear.
- [x] Prims, trees and grass become Filament entities at the transform the
      simulator sent. **No human capsules anywhere**: avatars are recorded in
      `SLAvatar` and reported in the overlay, never substituted with geometry.
- [x] `World → Region → Objects` structure from the start (`SLWorld`,
      `SLRegion`), so adjacent regions/teleports are additive later.
- [ ] Sculpted prims (`LLSculptTexture` + the sculpt map, which is itself a J2K
      texture) — needs Phase 4.
- [ ] Legacy fixed-shape trees/grass textures (the mesh variants exist in
      `slcore`; they currently share one default texture).

## Phase 2.5 — render pipeline diagnosis — **CODE DONE, DEVICE VERIFICATION PENDING**

Triggered by a report that the "Mundo 3D" screen was completely black while the
protocol log showed a healthy session (login, UDP circuit, `RegionHandshake`, 22
terrain patches, ~490 objects, 1 avatar). Everything below is **verified by code
and by the headless harness only** — no claim is made about what a phone shows.

- [x] One shared debug record, `renderer/RenderDiagnostics.kt`: counters for
      every stage (world → region → scene → mesh → buffers → entity →
      renderable → scene → frame), the surface/viewport size, camera eye/target/
      forward/near/far/fov/aspect, `beginFrame`/`render`/`endFrame` outcomes, the
      first error with its stage, the world bounding box, and the EventQueue
      state kept separate from the graphics state.
- [x] `Renderer.diagnostics()` added to the seam; `FilamentRenderer` fills it in
      (engine/scene/view/renderer/swapchain validity, entity + renderable +
      buffer + material + texture counts, frames, `view.getVisibleRenderableCount`,
      exceptions captured instead of killing the render thread).
- [x] `SLWorldDelta` carries the terrain snapshot, the object bounding box and
      the region's counters; `SLWorld.sync` detects terrain-only changes;
      `SLWorld.reset()` forgets regions so a mode switch re-adds everything.
- [x] `slscene/SLDiagnosticProbe.kt` — **PRUEBA A**: a lit cube, a fullbright
      cube, a sphere and a 60 m ground plane built from vertex data alone and
      pushed through the same `Renderer` as the region. Its camera is fixed and
      known.
- [x] `slscene/SLTerrain.kt` + `slworld/SLTerrainSnapshot.kt` — **PRUEBA C**: the
      received height field becomes a decimated triangle mesh (normals from
      central differences, region metres, Z up) with a solid debug colour and
      coalesced rebuilds. This is a *diagnostic* preview of Phase 6, not the real
      terrain system (no textures, no LOD, one hard-coded stride).
- [x] `SLScene` gained the terrain, a `primsEnabled` flag (so "terrain only" is
      possible), a camera-based `prune(camera)`, `forceObjectInFront` and a
      one-object field dump.
- [x] `SLCamera` gained `lookAt`, `forward`, `frameBounds` (frame the whole
      world) and a `framing` label.
- [x] `FilamentWorldView` gained four content modes (region / terrain / prims /
      probe), two camera framings (agent / world), the force-object hook, the
      on-screen DIAG panel and the `EphoraDiag` logcat report (full report on the
      first frame, one line every 5 s, full report every 30 s or on failure).
- [x] Filament defaults changed so a silent black screen is less likely and
      easier to attribute: frustum culling **off** (a slightly wrong bounding box
      silently removes a drawable), the near-black background replaced with sky
      blue, the ambient term raised, MSAA off (a second driver-dependent path
      that has nothing to do with geometry appearing).
- [x] **PRUEBA A confirmed on a device** (Xiaomi 2201116PG, Android 16 / API 36):
      `System.loadLibrary` OK for `filament-jni`, `filamat-jni` and `slcore`,
      `Filament.init()` OK, engine/renderer/scene/view/camera/swapchain OK,
      surface 1080x2400, OpenGL ES, ~58-59 FPS, **4 entities / 506 triangles /
      1466 frames**. Filament and the surface path are therefore proven, and
      Phase 2.7 was allowed to concentrate on the object pipeline.
- [ ] **Confirm PRUEBA B** — a real region object visible with a solid material.
- [ ] **Confirm PRUEBA C** — the region terrain visible with a solid material.
- [ ] If A fails: debug `SurfaceView → SwapChain → Renderer → View → Scene →
      Camera → present`. If A passes but B fails: the `ObjectUpdate → SLPrimitive
      → MeshData → VertexBuffer → IndexBuffer → Material → Renderable → Entity →
      Scene.addEntity()` path. If A and B pass but C fails: the terrain path.
- [ ] Re-enable MSAA/culling for the quality phase once the picture is confirmed.

## Phase 2.6 — Filament start-up: full-throwable diagnosis — **DONE**

Triggered by a device report where the protocol layer was healthy (UDP circuit,
`RegionHandshake`, terrain, 547 objects, 1 avatar) but **Filament never
initialised**: `engine NO`, `renderer NO`, `scene NO`, `view NO`, `camera NO`,
`swapchain NO`, `Viewport 0x0`, `Surface 0x0`, 0 frames, and the only output was
`ERROR en createRenderer: com.lumiyaviewer.lumiya.renderer.filament.FilamentRenderer`
— a class name, not a throwable. The request was explicit: find the exact root
cause, and **never** reduce an exception to "Filament no pudo iniciarse".

The work is verified by code and by the headless Kotlin harness (which now
includes the real Filament API surface, checked method by method against the
v1.75.1 sources) and by a scan that reproduces the bug it fixes. **No claim is
made about what a phone shows** — see the checklist at the end.

- [x] **Root cause found and fixed.** `Filament.init()` is empty; the JNI library
      is loaded by `Filament`'s static initializer. `FilamentRenderer`'s
      `mipmappedSampler`/`plainSampler` lived in its `companion object`, whose
      properties are initialised by the class's `<clinit>` — *before* the
      constructor body, i.e. before `Filament.init()`. `TextureSampler`'s
      constructor calls native `nCreateSampler` immediately, so the class
      initializer failed (`ExceptionInInitializerError`), which marks the class
      erroneous for the whole process, after which every retry threw
      `NoClassDefFoundError: ...FilamentRenderer`. `EntityManager.get()` was in
      the same trap. Fixed by declaring those fields without initialisers and
      assigning them inside `init`, after the library is loaded.
- [x] `renderer/filament/FilamentBootstrap.kt`: `ensureLoaded()` calls
      `Filament.init()` exactly once and records `FILAMENT_INIT START/SUCCESS/FAIL`;
      `preflight()` records the device (manufacturer, model, API level), the
      supported ABIs and the primary one, the `nativeLibraryDir` contents, the
      `lib/<abi>/*.so` entries in the installed APK (base + splits), the packaged
      ABIs, and `System.loadLibrary()` for `filament-jni`, `filamat-jni` and
      `slcore` **one by one**, each with its own full throwable. The Filament
      version the build targets is printed too.
- [x] `EphoraApp.onCreate` loads Filament at application start, before any
      activity can reach rendering code.
- [x] `FilamentRenderer.init` wraps every step in `step(name) { }`:
      `FILAMENT_INIT`, `ENGINE_CREATE`, `ENGINE_VALID`, `ENTITY_MANAGER`,
      `RENDERER_CREATE`, `SCENE_CREATE`, `VIEW_CREATE`, `CAMERA_CREATE`,
      `TRANSFORM/RENDERABLE/LIGHT_MANAGER`, `SAMPLER_MIPMAPPED`, `SAMPLER_PLAIN`,
      `MATERIAL_COMPILER`, `VIEW_CONFIGURED`, `FALLBACK_TEXTURE`,
      `FALLBACK_MATERIAL`, `LIGHTS`, `RENDERER_READY` — each `START` /
      `SUCCESS`, or `FAIL` plus the complete throwable.
- [x] `RenderDiagnostics` gained `failDetailed(stage, Throwable)` (class, message,
      cause chain and `printStackTrace` output — deliberately not
      `Log.getStackTraceString`, so the record stays Android-free), `step()`,
      `environment()`, `startUpReport()`, `fullReport()`, `writeTo(File)`,
      `describe()`, `surfaceEvent()` and the surface counters; `lines()` now
      prints the `SurfaceView` counters, the last error next to the first, and
      the last few `SurfaceHolder` callbacks.
- [x] `FilamentWorldView`: every `SurfaceHolder` callback and
      `onAttachedToWindow`/`onDetachedFromWindow` is instrumented with validity,
      size, format and thread; `SURFACE_BIND` says `SUCCESS`/`NO (reason)`; the
      renderer retry backs off to 2 s instead of spinning every 200 ms (the spin
      was overwriting the informative first error with its own consequence); the
      failure text now carries the full `describe(error)`; the report is written
      to `filesDir/ephora-filament.log` automatically on failure; a "Guardar
      informe" button was added to both 3D screens' DIAG panel.
- [x] **`ui/diag/FilamentProbeActivity.kt`** — PRUEBA A on a screen of its own:
      Activity → `SurfaceView` (created in code) → `Filament.init` → Engine →
      Renderer → Scene → View → Camera → `SwapChain` → one triangle → render
      loop. No login, no UDP, no `EventQueue`, no `SLWorld`/`SLScene`, no
      avatars, no terrain, no `TextureEntry`, no JPEG2000, no `slcore`; it does
      not import the Second Life packages at all. Its own `RenderDiagnostics`
      and its own thread record every step, the counters
      (`framesAttempted`, `beginFrameOk/Fail`, `renderCalls`, `endFrameCalls`,
      `FPS`) are always on screen, and the whole report can be expanded, saved to
      `filesDir/ephora-probe.log` (written automatically on the first failure)
      or retried. Reachable from the **login screen** ("Probe Filament (sin
      login)") and from a PC:
      `adb shell am start -n com.ephora.viewer/com.lumiyaviewer.lumiya.ui.diag.FilamentProbeActivity`.
- [x] `SURFACE CREATED width = W height = H` / `SURFACE NOT CREATED` are recorded
      once per state change, and **no swap chain is created until the surface is
      valid with a non-zero size**; when the surface goes away the swap chain is
      destroyed before anything else is reported.
- [x] Harness: 15 checks (was 14). The new `bootstrapCheck` pins the contract —
      `START`/`SUCCESS`/`FAIL` for each step, the full throwable with `Caused by`
      and frames, first-error-kept/last-error-reported, the environment block,
      the surface callbacks reaching the HUD, `errorSummary` keeping the cause
      chain, and the on-disk report containing the trace.
- [x] Every Filament method and enum the new code touches was checked against the
      real v1.75.1 sources (`MaterialBuilder`, `MaterialPackage`, `Material`,
      `MaterialInstance`, `RenderableManager`, `VertexBuffer`, `IndexBuffer`,
      `Box`, `View`, `Renderer`, `Engine`, `Camera`, `Scene`, `TransformManager`,
      `LightManager`, `Texture`). One real bug was caught this way:
      `Engine.isValidCamera` **does not exist** — the camera is now proven by
      configuring it (`CAMERA_CONFIGURE`).
- [x] A static scan of `app/src/main/java` proves that no property outside a
      function body constructs a Filament type (negative control: the same scan
      flags the old `companion object` shape and `private val x = EntityManager.get()`).
- [ ] **Run the probe on the device and send the report.** Success is: `Filament.init`
      SUCCESS, engine/renderer/scene/view/camera/swapchain YES, `Viewport > 0x0`,
      `beginFrame > 0`, `render > 0`, `endFrame > 0`, `FPS > 0` — and the
      triangle visible.
- [ ] **Then** confirm PRUEBA A/B/C in the world view, and only then return to
      `SLWorld → SLScene → objects → geometry`.
- [ ] Not touched in this phase, on purpose: login, UDP, `EventQueue`, the Second
      Life protocol, JPEG2000, avatars, and the Filament version (still 1.75.1 —
      see the pin note in `README.md`).

### Evidence to collect on the device (Phase 2.6)

Two ways in, both with no login: the "Probe Filament (sin login)" button on the
first screen, or `adb shell am start -n
com.ephora.viewer/com.lumiyaviewer.lumiya.ui.diag.FilamentProbeActivity`. Then:

* the on-screen report (tap "Ver informe", select, copy), and/or
* `adb logcat -s FilamentBootstrap EphoraDiag`, and/or
* `/data/data/com.ephora.viewer/files/ephora-probe.log` (probe) and
  `ephora-filament.log` (world view) — both are written without being asked when
  something fails.

## Phase 2.7 — Region object pipeline — **CODE DONE, DEVICE VERIFICATION PENDING**

Triggered by a region-mode device report in which Filament was **provably fine**
(PRUEBA A passed: 4 entities / 506 triangles at 58-59 FPS) but the region showed
`523 objetos recibidos`, `510 entidades`, `0 prims / 522 arboles / 1 avatares`,
**1 Renderable**, `ERROR en buildRenderable: length=0; index=-2`, and a correct
terrain (256 patches / 32768 triangles / 16641 vertices). The instruction was to
stop touching Filament/OpenGL/SurfaceView/login/UDP/EventQueue and work only on
`ObjectUpdate → SLWorld → clasificación → SLPrimitive/SLObject → geometría →
VertexBuffer/IndexBuffer → Material → Renderable → Scene`.

- [x] **Cause A found — the `PCode` constants were wrong.** `PCODE_PRIM` was 6 and
      `PCODE_TREE` was 9, but on the wire 9 is `LL_PCODE_VOLUME` (every ordinary
      prim) and 255 is `LL_PCODE_LEGACY_TREE`. Every real prim therefore matched
      the *tree* constant and received the legacy tree mesh. `SceneObject` now
      carries the real values (checked against `indra/llmath/llvolume.h` and
      LibreMetaverse's `PCode` enum), `isPrim`/`isTree`/`isGrass` are distinct
      (the old `isTree` also swallowed grass), and `SLObjectKind.of` treats
      anything that is not avatar/grass/tree as a prim — with an `UNKNOWN` bucket
      for the other pcodes instead of silently calling them prims.
- [x] **Cause B found — the legacy grass/tree meshes had no face group.**
      `buildGrassMesh()`/`buildTreeMesh()` never called `Builder::beginFace`/
      `endFace`, so `MeshData.faces` was empty, the JNI hand-off produced a
      zero-length `faceGroups`, `MeshDesc.faceCount` was 0, and
      `FilamentRenderer.createEntity`'s `faceGroups[min(i, faceCount - 1) * 3 + 1]`
      read `faceGroups[-2]` on a length-0 array — the exact
      `ArrayIndexOutOfBoundsException: length=0; index=-2`. The catch returned
      `EntityHandle(0)` while `SLScene` stored the slot anyway, which is how 510
      "entities" coexisted with 1 renderable.
- [x] `sl_prim_geometry.cpp`: grass wrapped in one face group, tree in two (trunk,
      canopy), `mesh.faces` assigned; `slcore_tests.cpp` gained
      `testLegacyFixedMeshes()`, running the full `checkMesh` invariants (unit
      normals, in-range indices, face groups covering every index, exact face
      counts) on grass and all three tree variants.
- [x] `MeshDesc.geometryProblem()`: the single gate every mesh passes before a
      backend sees it — no vertices, stride not a multiple of 8, no indices,
      indices not a multiple of 3, empty/malformed face groups, a face range past
      the index buffer, an index out of range, NaN/infinite vertices. Each message
      names a different generator bug. `MeshDesc.geometryWarning()` covers the one
      defect that must **not** hide an object — indices no face group claims — so
      a partially-covered mesh is drawn and counted as a warning instead of being
      turned into another "sin geometría".
- [x] `PrimGeometryNative.toMeshDesc` synthesises one whole-buffer face group when
      the native side returns none (the correct reading of "one face"), and
      **counts** it (`faceGroupFallbacks`, in the HUD) so the generator bug stays
      visible; anything still failing the validator is rejected with its reason.
- [x] `FilamentRenderer.createEntity` validates first and returns
      `EntityHandle.INVALID` (0, never a live id) instead of throwing; the catch
      path uses `failDetailed`, so the **full stack trace** reaches the report;
      each refusal is named with its mesh counts, transform and face count.
      `createMesh` uses the same validator.
- [x] `SLScene.attach` never stores a slot for an invalid handle, counts the
      refusal, logs the object's whole field dump (first 5) and keeps building the
      rest — "one bad prim must not stop the region", now enforced by the
      `reject` harness check (a renderer that refuses everything, then accepts).
- [x] **Never invent geometry**: `SceneObject.paramsKnown` is false until the
      path/profile block really arrives, and `SLObject.from` returns a null prim
      in that case, so the object is carried for its transform only. The debug
      placeholder box is **removed** (PRUEBA A already proves Filament draws): an
      object with no geometry is reported as *sin geometría* with the reason.
- [x] `ObjectUpdateCompressed` rewritten to LL's `processObjectUpdateCompressed`
      order (the 4-byte CRC was missing, the flag bits were wrong, and the
      optional blocks / extra parameters / path-profile / `TextureEntry` were
      never read): header, flag-selected blocks, extra params (with the
      `Sculpt`/`Mesh` id and type recorded, not dropped), path/profile,
      `TextureEntry`, texture animation. A blob too short for the path/profile
      block is treated as a placement-only update.
- [x] Per-object log for the first 20 distinct objects (`SLScene.objectLine`):
      UUID, local id, pcode *and wire name*, State, parent, position, scale,
      rotation, `TextureEntry` length + texture UUID + tint + fullbright,
      `ExtraParams` length, sculpt state, path/profile values, derived shape,
      mesh vertices/indices/triangles/faces/key, and the final classification.
      Capped and counted, so truncation is visible rather than misleading.
- [x] First-real-prim report (`RenderDiagnostics.firstPrimReport`): the whole
      route, once, for the first prim that is not an avatar/tree/grass/attachment
      *and* really produced a renderable — received → classified → parameters →
      geometry → material → renderable → entity in the scene → counters. Never
      from a substituted shape.
- [x] When the first such prim is refused instead, `firstPrimFailure` records it
      separately (`Primer prim SL real RECHAZADO`), so "no prims arrived" and "the
      first prim failed" cannot be confused.
- [x] Update-type counters in the HUD (full / compressed / terse / killed, plus
      compressed blobs without a header or without parameters) and
      `objectsAhead` — entities in front of the camera out of every entity in the
      scene, because the region's bounding-box centre is a poor proxy (a region is
      256 m across; its centre is regularly behind a viewer near an edge).
- [x] Camera: starts 6 m from the agent at a shallow pitch, and when the agent's
      position has not arrived yet it aims at the nearest object **with geometry**
      instead of at the region's centre, so the first frame already looks at
      something real.
- [x] Harness: 19 checks (was 15). New: `pcodes` (the wire values, the
      classification table, and "a prim with no path/profile block must not get a
      shape"), `geomval` (the validator, including the exact no-face-groups case),
      `reject` (an invalid entity stores no slot, is counted and named, and the
      rest builds; then the same objects build when the backend accepts),
      `objectlog` (the per-object log, its caps and the HUD text).
- [ ] **Confirm on the device** (this is the acceptance test for the phase):
      at least 1 real SL prim generates valid geometry; at least 1 Renderable
      exists for it; that Renderable is in the `Scene`; the renderable count is
      `> 1` once more objects arrive; the terrain is visible; and there is no
      `length=0; index=-2` anywhere. The per-object log and the first-prim report
      are what make a failure attributable without another round trip.
- [ ] Not touched, on purpose: Filament, OpenGL ES, `SurfaceView`, login, UDP,
      `EventQueue`, the Filament version, and the terrain path.

## Phase 2.8 — The incremental object model and the compressed-block bug — **DONE (confirmed on the device)**

Reported on the device after 2.7: `Error procesando ObjectUpdateCompressed:
length=113; index=-872415225`; every object in the object list reading
"(cylinder)"; `9 ObjectUpdate completos, 56 comprimidos, 1973 terse, 332
"comprimidos sin parametros"`. The graphics path itself was confirmed fine
(59.5 FPS, 13 prims with geometry + Renderable, terrain 256/32 768/16 641).

- [x] Root cause 1: `applyCompressed` read `ExtraParams` as
      `reader.bytes(reader.remaining)`, swallowing the path/profile block and the
      `TextureEntry` that follow it. Every compressed update therefore took the
      "placement only" branch (the 332), and the entry walk read a `U32` length
      from those parameter bytes — `0xCC000007` — which `ByteReader.skip` applied
      as a *negative* cursor move, producing an array index out of a wire value.
- [x] Root cause 2: `COMPRESSED_PARAMS_SIZE` was 26; the block plus its length
      word is 27 bytes.
- [x] Root cause 3: the "(cylinder)" label. The object list used
      `sceneObject.shape.name.lowercase()` and `SceneObject.shape` classified the
      constructor's placeholder `pathCurve = 16 / profileCurve = 0` (SL's default
      cylinder) when no definition had arrived. `shape` now returns `UNKNOWN`
      unless `paramsKnown`; `shapeText` says `UNKNOWN/MISSING_SHAPE`; the list and
      the scene print the same text (checked by `samesource`).
- [x] `ExtraParams` walked entry by entry, bounded; a length that does not fit is
      refused, counted and named; every entry aligned to its declared end; an
      unwalkable section means the path/profile block is *not* read from a
      guessed offset and the stored definition survives.
- [x] `ByteReader` hardened: negative/oversized `skip`, `bytes()` counts and
      `seek` are refused, counted and attributed to a field name (`at()`), so no
      cursor can leave the buffer and no failure is anonymous.
- [x] Incremental model: `noteShapeReceived` / `noteUpdateWithoutShape` +
      `ShapeSource` provenance + `hasCompleteShape`; terse and truncated updates
      never touch the definition; `ObjectUpdateDiagnostics` keeps the per-object
      transition traces for the first 5 objects.
- [x] Full diagnostics: per-field offset walk (first good block), failure report
      with field, offsets, pcode, local id, flags, hex preview and stack trace;
      `Log.e`; counters by shape state; per-object shape table; parser report in
      the DIAG panel and in `ephora-filament.log`.
- [x] Separated counters (`objectsReceived` … `visibleInFrustum`) in the HUD.
- [x] Ramp: `SLScene.maxPrimEntities` (0 = all) + `budgetHidden`.
- [x] Self-test runnable on the device from `DiagnosticsActivity` (no region, no
      network): the 113-byte block, the incremental transition, and the
      `0xCC000007` length.
- [x] Harness: 27 checks (was 19). New: `bytes`, `compressed`, `placeonly`,
      `incremental`, `shapesource`, `census`, `samesource`, `budget`.
- [x] **Confirmed on the device** (user's report): `ObjectUpdateCompressed` parses
      with no corrupt index; **194/194** objects with a complete shape; **83/83**
      prims with generated geometry, a renderable and an entity in the `Scene`;
      `slcore` OK; Filament engine/renderer/scene/view/camera/swapchain OK; 1392
      frames with `beginFrame`/`render`/`endFrame`; and the shapes are varied
      (`SPHERE`, `TORUS`, `CYLINDER`, `TUBE`, `RING`, `PRISM`). Attachments are
      deliberately skipped.
- [x] Not touched, on purpose (this phase was diagnosis only): Filament, OpenGL
      ES, `SurfaceView`, the camera, terrain, materials, login, UDP and
      `EventQueue`.

### Evidence to collect on the device (Phase 2.8)

Same screens as 2.7, plus:

* the *Protocol self-test* screen (`DiagnosticsActivity`) — the four new checks
  run with no region;
* the DIAG panel: `SL formas (estado acumulado)` (the separated counters),
  `--- parser de object updates ---` (field offsets, failures with stack traces,
  state transitions) and `--- tabla de formas por objeto ---`;
* `adb logcat -s EphoraDiag SLScene ObjectUpdateDecoder PrimGeometryNative`;
* `/data/data/com.ephora.viewer/files/ephora-filament.log`.

### Evidence to collect on the device (Phase 2.7)

Same screens as Phase 2.5/2.6 (`Modo: region` in **Mundo 3D**), plus:

* the DIAG panel (tap **DIAG**, select, copy) — it now contains
  `SL updates: …`, `SL prims: intentados … con geometria+renderable …`, the
  per-object log (`Primeros N objetos recibidos`), the first-real-prim block
  (`Primer prim SL real`), `Objetos delante de la camara`, and
  `Entidades rechazadas` if any;
* `adb logcat -s EphoraDiag SLScene PrimGeometryNative`;
* `/data/data/com.ephora.viewer/files/ephora-filament.log`.

## Phase 2.9 — Are the prims that are in the Scene actually on screen? — **CODE DONE, DEVICE VERIFICATION PENDING**

Phase 2.8 confirmed the pipeline's counters on the device (194/194 objects with a
complete shape, 83/83 prims with geometry, a renderable and an entity in the
`Scene`, 1392 frames presented, varied shapes). None of those numbers can say
whether an entity is *inside the camera's picture*, which is the only question
this phase asks. It is instrumentación plus two reversible switches: **no**
protocol decoding, geometry, material, texture, terrain, attachment or Filament
initialisation is touched.

- [x] `CameraFrustum`: the frustum test built from the same eye/target/up/fov/
      near/far/aspect the backend's camera is given, returning depth, horizontal
      and vertical offsets, NDC and screen position, and naming the failing
      condition (`DETRAS_DE_LA_CAMARA`, `MAS_CERCA_QUE_NEAR`, `MAS_LEJOS_QUE_FAR`,
      `FUERA_POR_IZQUIERDA`/`DERECHA`/`ARRIBA`/`ABAJO`). Plus a conservative
      bounding-sphere test (six explicit planes), so a large prim whose centre is
      off-axis is not called invisible.
- [x] `MatrixFrustum`: the *same* test, computed by multiplying the point by the
      camera's own view matrix and its own projection matrix. The audit prints
      both and `acuerdo=SI` + `diffDepth`/`diffNdc`; a disagreement means the
      engine is not using the camera the scene set.
- [x] `Renderer.entityLocalMatrix` / `entityWorldMatrix` / `entityExists` /
      `cameraSnapshot` / `frustumCullingEnabled`: read-back of what the engine
      actually holds (`TransformManager.getTransform` / `getWorldTransform`,
      `Camera.getViewMatrix` / `getProjectionMatrix` / `getPosition` /
      `getForwardVector` / `getLeftVector` / `getUpVector` / `getNear` /
      `getCullingFar`, `View.isFrustumCullingEnabled`). Default implementations
      returning null, so no other implementer is forced to change.
- [x] `SLScene.cameraAudit`: one block per object (nearest eight) with UUID,
      local id, shape text/provenance/`hasCompleteShape`, SL position/scale/
      rotation, entity handle + `enRenderer` + `enEscena`, the transform three
      ways (scene matrix, `TransformManager` local, `TransformManager` world) with
      `aplicadaVsEscena` and the numeric difference, mesh bounds, scaled
      bounding-sphere radius, distance to camera, both frustum results and the
      sphere-intersection note.
- [x] `SLCameraProbe` + **Probe cámara** button: one bright fullbright cube 3 m in
      front of the camera, no Second Life data at all. `CAMERA_TEST` says where it
      should be (dead centre) and asks for the visual confirmation.
- [x] `SLCamera.lockTo` / **Ver prim** button: pins the camera to
      `eye = prim + offset`, `target = prim` for the nearest prim within 50 m.
      The object is never moved. `REAL_PRIM_TEST` records the frustum test
      **before** and **after**, the SL position and the `TransformManager`
      position.
- [x] Both switches are off by default, reset on a content change, and restore
      the scene exactly (the orbit is untouched, the probe frees its resources).
- [x] Audit, probe and lock blocks in the DIAG panel, in logcat and in
      `ephora-filament.log` (written automatically with the first audit).
- [x] Harness: 32 checks (was 27). New: `frustum`, `matrixfrustum`, `camtrace`,
      `camprobe`, `primlock`.
- [ ] **Confirm on the device**: the audit's `frustum(base)=DENTRO` /
      `acuerdo=SI` / `aplicadaVsEscena=IGUAL` for the prims the camera is aimed
      at; whether the magenta probe cube is visible; and whether the locked prim
      is visible after the camera moved to face it. Visual confirmation is the
      only evidence accepted — the geometry test is a prediction, not a
      photograph.

### Evidence to collect on the device (Phase 2.9)

On **Mundo 3D** with `Modo: region`:

* tap **Probe cámara** → is a big magenta cube visible in the **centre** of the
  screen? (`CAMERA_TEST: … visibleAJO=PENDIENTE`);
* tap **Ver prim** → the camera moves to the nearest real prim (<50 m) and the
  object must be visible in the centre; check the `REAL_PRIM_TEST` block's
  `visible ANTES` / `visible DESPUES` lines and say whether the object is
  actually visible in each case;
* tap **DIAG** → copy `--- TRAZA SL -> entidad -> camara -> frustum ---` (camera
  read-back, agreement, and the eight rows) and `Camara BLOQUEADA …`;
* `adb logcat -s EphoraDiag SLScene` (the audit is logged as well as shown);
* `/data/data/com.ephora.viewer/files/ephora-filament.log`.

## Phase 2.10 — Where does the parent/child chain break, and whose transform is an object reading? — **DIAGNOSIS DONE, DEVICE EVIDENCE PENDING**

Diagnosis only: no matrix, camera, pipeline, geometry or parent/child change.
Two problems, kept separate on purpose.

### Problem 1 — Parent/Child

- [x] The chain is audited hop by hop and printed: `ObjectUpdateDecoder.parentId`
      (full update: line 126; compressed: lines 273/276 — a block **without**
      `FLAG_HAS_PARENT` writes 0, the same as LibreMetaverse's `ObjectManager`),
      `SceneObject.parentId`, `SLObject.parentLocalId` (`SLObject.kt:145`),
      `Renderer.Transform.parent` (`Renderer.kt:166`),
      `FilamentRenderer.setParent` (`FilamentRenderer.kt:703`).
- [x] `SLScene.attach()` builds `Transform(translation, rotation, scale)` and
      never passed `parent`: that is where the value that arrived was dropped
      (`upsert` is the only creation path). **Fixed in Phase 2.11** — `attach()`
      now goes through `transformOf(object_)`, which carries
      `Transform.parent = EntityHandle(...)` whenever the parent is already
      resolvable.
- [x] `parentChainReport(renderer, limit)`: for every child, the parser's last
      `ParentID`, the `SLObject.parentLocalId`, the four fields of the `Transform`
      the scene actually handed over, the engine's `parentEntity`/`parentHandle`/
      child count, and the verdict lines. **Phase 2.11** corrected the delivered
      `Transform` and rewrote the verdict text, so a resolved child now reads
      `parentLocalId=X -> Transform.parent=EntityHandle(Y) -> Filament
      parentEntity=Y OK` instead of `EL PARENT SE PIERDE EN SLScene.attach()`.
- [x] Parser counters + ledger: blocks carrying `ParentID`, blocks with a non-zero
      value, blocks writing an explicit 0, and parents *destroyed* by a compressed
      update without the flag; one line per transition for the first objects.
- [ ] **Confirm on the device**: does the region ever send a non-zero `ParentID`
      for the children in the scene, and does the report name `SLScene.attach()`
      as the loss point?

### Problem 2 — the `TransformManager` reading of one object

- [x] `Renderer.entityProbe(handle)` (read-only; implemented in
      `FilamentRenderer` with `getInstance`, `getParent`, `getChildCount`,
      `getTransform`, `getWorldTransform`): the component instance the scene
      remembered **and** the one the entity owns now, the parent the engine
      reports, the mesh, the visible flag, and the matrices read through both
      instances.
- [x] Code-level evidence of *how* the two can differ: Filament compacts component
      storage with swap-and-pop (`SingleInstanceComponentManager`, "This
      invalidates all pointers components") and `Engine.destroyEntity` destroys the
      transform and renderable components (`FEngine::destroy`). The scene destroys
      entities whenever an object leaves, is rebuilt or turns out not to be
      renderable (`FilamentRenderer.destroyEntity`).
- [x] `focusReport(renderer, localId)`: the object's whole life (parser history +
      scene entity events), `MATRIZ_ESCENA`, the engine reading for the cached and
      the current instance, the verdict on where the discrepancy appears, and the
      same field set for a healthy neighbour chosen by measurement.
- [x] `FOCO` button (input dialog) + tapping a row of the objects-nearby list
      sets the followed local id at runtime. **No local id is hard-coded.**
- [x] Harness: 35 checks (was 32). New: `parentparser`, `parentchain`,
      `focusreport` — the last one moves the fake's component the way Filament's
      compaction does and requires the report to say `COINCIDE=NO` and name the
      wrong-instance read as the point where the discrepancy appears.
- [ ] **Confirm on the device**: for `#830138250`, the `ficha:` line's
      `instanciaCacheada` / `instanciaActual` / `COINCIDE`, the two local matrices,
      and which of the two explanations the report lands on.

### Evidence to collect on the device (Phase 2.10)

On **Mundo 3D** with `Modo: region`:

1. tap **FOCO**, type the local id of the object to follow (or tap it in
   Objetos cercanos) — best done *before* connecting, so the whole life is
   recorded;
2. tap **DIAG** (or `adb logcat -s EphoraDiag`) and copy:
   * `--- PARENT/CHILD (fase 2.10) ---` … including the `parser` counters and the
     ledger, and whether any child says `EL PARENT SE PIERDE EN SLScene.attach()`;
   * `--- FOCO #<id> (fase 2.10) ---` … the `B)` block, the `B1 vida del objeto`,
     the `B4 veredicto`, and the `C) objeto sano` block with the same fields;
   * the `  ficha: TransformInstance cacheada=… actual(getInstance)=… COINCIDE=…`
     line for the followed object and for a healthy one;
3. `/data/data/com.ephora.viewer/files/ephora-filament.log`.

## Phase 2.11 — Parent/child correction — **IMPLEMENTED, DEVICE TEST PENDING**

Minimal, localised correction of the one confirmed bug: 269 children arrived
with `parentLocalId != 0` and the renderer received `parent=null`. Nothing
outside this chain was touched — no Filament, no camera, no coordinates, no
terrain, no avatar, no login/UDP/EventQueue.

### Why the local transform is already correct

Read from the reference sources (`scratch/sl-src/llviewerobject.cpp`): the wire
position of a child is **local to its parent** (`new_pos_parent` is compared
against `getPosition()`, which is the *local* position for a non-root object;
`getPositionRegion()` = parent region position + local × parent rotation). So
there is nothing to convert: hand Filament the local TRS plus the parent entity
and let `parentWorld × childLocal` produce the world transform.
`scratch/sl-src/filament_TM.cpp` confirms `FTransformManager::setParent` only
re-inserts the node and recomputes the world from the local transform (it does
**not** preserve the world transform), exactly what a linkset needs.

### What was changed

- `LocalID → EntityHandle` index reuses `slots` (the scene already keeps it);
  no new entity index was added.
- **Two-step resolution.** `attach()` resolves the parent if it is already
  known and, either way, `apply()` calls `resolvePendingParents()` after the
  whole delta — so a child that arrives **before** its parent is resolved as
  soon as the parent appears. `ObjectUpdate` order is never assumed.
- A child whose parent is unknown is registered in `awaitingParent` and left
  **exactly where it is**: no invented parent, no artificial move, no local →
  world conversion, no discard.
- **Multi-level**: every child points at its own parent, so `C → B → A` works.
- **Updates** apply parent/position/rotation/scale; a partial update that
  carries no `ParentID` **keeps** the existing parent.
- **Destruction**: `relinkChildrenOf()` re-resolves the children of a parent
  that is rebuilt or destroyed and `onObjectLeftScene()` clears the maps, so no
  dead handles are left. Filament orphans the children by itself (its
  `destroyComponents` sets their `parent = 0`) — untouched.
- Writes go through the **live** component instance
  (`FilamentRenderer.currentTransformInstance`, via
  `transformManager.getInstance`) so a transform is never written into another
  entity's component after a destruction. The value the scene *remembers* is
  left intact on purpose, so the 2.10 reading diagnosis stays visible.
- `placementInRegion()` composes `parentWorld × local` (translation + rotation,
  cached per `apply`, depth cap `MAX_PARENT_DEPTH = 16`) for culling and for the
  distances the reports print.

### One-line decoder exception

Requirement 7 ("if a partial update has no `ParentID`, keep the parent") cannot
be satisfied without it: `ObjectUpdateDecoder.decodeCompressedBlock` used to do
`else { mine.parentId = 0 }` when the block lacked `FLAG_HAS_PARENT`. That
`else` branch was removed, so the previous parent is carried over — which is
what `llviewerobject.cpp` does too (it pre-seeds `parent_id` and only overwrites
it when the flag is present). Everything else in the parser is untouched.

### Diagnostics

Counters kept in `SLScene`: `childrenWithParent`, `childrenWithoutParent`,
`parentsResolved`, `parentsPending`, `parentsUnresolved`. `parentLinkReport()`
prints, for the first ten children, `Child LocalID / Parent LocalID / Child
Entity / Parent Entity / Transform.parent / Filament parentEntity`, plus the
`PENDIENTES` block, under the header `--- PARENT/CHILD resuelto (fase 2.11) ---`
in `DIAG`/`EphoraDiag`. A resolved child must read
`parentLocalId=X -> Transform.parent=EntityHandle(Y) -> Filament parentEntity=Y`
and must **not** read `Transform.parent=null`.

### Verified (harness, headless)

`render pipeline: OK (4 comprobaciones)` — no errors — with the reduced harness
`scratch/kcheck/extra/tests_parent.kt` (the full one does not fit the Kotlin
playground runtime limit). The four parent checks (`parentparser`,
`parentchain`, `parentresolve`, `focusreport`) moved from 35 to **36** pipeline
checks. The `parentchain` check builds grand-child → child → root in reverse
order and asserts the local TRS is unchanged, the composed region position is
`100.05, 20.21, 29.10`, and that `Transform.parent` really carries the parent;
`parentresolve` covers a child arriving before its parent (pending, unmoved) and
a parent disappearing (`parentsUnresolved=1`, no dead handle).

### Still pending — device evidence

- [ ] Upload the zip, install the APK, **Mundo 3D** / `Modo: region`, let the
      objects load, tap **DIAG** (or `adb logcat -s EphoraDiag`) and copy the
      `--- PARENT/CHILD resuelto (fase 2.11) ---` block (five counters + the
      child rows) and the parser block.
- [ ] Confirm the real linkset: `#2509` (parent `2519`, local position
      `0.16, 0.00, -3.24`) must go from `parent=null` to `parent=Entity#N`
      keeping its local position, and the children must render next to their
      root, not near the region origin.
- Problem 2 of Phase 2.10 (`#830138250`, `MATRIZ_ESCENA` vs `TransformManager`)
      is a **separate** matter: writes now use the live instance, but the 2.10
      *readers* still read the remembered instance on purpose, so that
      discrepancy is expected to remain visible until a device run confirms it.
      No fix has been proposed for it here.

## Phase 2.12 — Render scalability — **IMPLEMENTED, DEVICE MEASUREMENT PENDING**

Baseline at the end of 2.11 (from the device): ~1731 objects, ~1697 renderables,
~1681 drawn, 1020 children with a parent, 203 parents resolved, 0 pending, 1
unresolved; culling OFF, shadows ON, ~49 ms GPU, ~18.6 FPS. This phase improves
the cost of the same picture; it changes no geometry, no material, no texture, no
protocol and no parent/child relation.

### What was implemented

- [x] **Frustum culling, for real** — the engine's own per-view test, plus the
      per-renderable flag applied to the live entities
      (`FilamentRenderer.setCullingEnabled` walks them with
      `RenderableManager.setCulling`, because the flag is baked in at build time).
      Nothing leaves the scene. It respects the parent hierarchy because Filament
      culls with the world transform `TransformManager` produced.
- [x] **Distance culling is a switch** — `SLScene.distanceCullingEnabled`
      (+ `maxDrawDistance`, still 300 m). Nothing is destroyed or unloaded; the
      objects only leave the draw set. Counted in `SLScene.distanceCulled` and in
      the report.
- [x] **Far-prim shadow LOD** — `SLScene.shadowLodDistance` (0 = off). Beyond the
      distance a prim stops *casting* shadows via the new
      `Renderer.setShadowCaster`; it keeps drawing, keeps receiving light and
      keeps its geometry and material. Restored when the LOD is switched off.
- [x] **Material/geometry reuse audited** — already shared
      (`SLMeshLibrary` one mesh per prim parameter set; `SLTextureCache` one
      material per appearance). Nothing new is duplicated; the counts are now
      printed (`Recursos: … mallas (…creadas, …cache) · materiales · buffers
      VBO/IBO · texturas`).
- [x] **Instrumentation** — the `Escalado (fase 2.12)` HUD line: renderables in
      scene, visible, culled by frustum, culled by distance; the `Escalado DEBUG`
      line: the state of the four switches; `Recursos`: mesh/material/buffer/
      texture counts. The frustum figure is
      `scene.getRenderableCount() - view.getVisibleRenderableCount()`, read back
      from Filament, i.e. the engine's own measurement.
- [x] **DEBUG switches** — *Culling*, *Distancia*, *Sombras*, *LOD* on a new
      button row of the 3D screen. Each is a `@Volatile` request the render thread
      applies in `applyScalability` on its next frame; the UI thread never touches
      the renderer. All four are reversible and log their state.
- [x] **LOD scope, stated honestly** — no geometry LOD was invented: no mesh is
      simplified, swapped or downloaded (that is the mesh-asset work). The only
      LOD shipped is the shadow-cast LOD, which cannot change a shape.

### Not touched

Cameras, terrain, avatar, login/UDP/EventQueue, the `ObjectUpdate` parser, the
parent/child resolution (there is still exactly one `Transform.parent` per child
and no second hierarchy), textures/JPEG2000, Mesh/Sculpt.

### Verified (harness)

- `tests_render.kt` (all **37** checks) compiles against the 43-file render subset
  with **0 errors** (`entry=false`).
- `tests_scale.kt` — the new `scalability` check — **runs**: `render pipeline: OK
  (1 comprobaciones)`. It asserts that a 2 m draw distance hides 18 of the 20 test
  objects without destroying an entity/mesh/material, that switching distance
  culling off brings all 20 back, that the LOD reduces 18 shadow casters and
  restores them, and that the report carries the flags.
- `tests_parent.kt` (the 2.10/2.11 checks) still **runs**: `OK (4 comprobaciones)`.
- The 79-file UI subset (including `FilamentWorldView.kt` and
  `WorldViewActivity.kt`, and the generated bindings for the new buttons)
  compiles: only the 11 expected errors caused by denying
  `RenderTestActivity.kt` / `FilamentProbeActivity.kt`.
- Note: the playground's *execute* path rejects the full harness (payload size),
  so the checks are run in small groups; `scratch/kcheck/make_parent_tests.js` is
  now a parameterised builder that generates each group from `tests_render.kt`.

### Pending on the device (the A/B measurement)

- [ ] With `Modo: region`, `Encuadre: agente`, stand still and copy the HUD
      `Escalado` / `Escalado DEBUG` lines and the `DIAG` report in four states:
      (1) Culling ON / Distancia ON / Sombras ON / LOD OFF (the "after"),
      (2) Culling OFF (the 2.11 baseline), (3) Distancia OFF, (4) Sombras OFF.
- [ ] Record FPS, frame ms and GPU ms for each state, plus renderables
      in-scene / visible / culled-by-frustum / culled-by-distance.
- [ ] Confirm the picture is the same with culling ON and OFF (nothing that
      should be visible disappears, linkset children still sit next to their
      root, and the terrain/camera are unaffected).

## Phase 2.12b — Frustum diagnostic correction — **IMPLEMENTED, DEVICE CONFIRMATION PENDING**

The real culling (the engine's `View.setFrustumCullingEnabled` +
`RenderableManager` culling) was **not** changed in this sub-phase. Only the
auxiliary diagnostic was corrected, because it could contradict itself for a
linkset child.

### Cause

The diagnostics tested **two different points**. `SLScene.realPrimTestReport`
sampled `Transform.translation` — the object's **local** position, relative to
its parent — and `appendAuditRow`'s matrix route did too, while its basis route
sampled `placementInRegion(localId)`, the **region** position (`parentWorld ×
local`). For a linkset child (most of the region's objects) those differ, so a
camera moved onto the child's real place left the local point *behind the
camera* → `depth < 0` → "FUERA (DETRAS_DE_LA_CAMARA)", while the region route
said inside. The apparent matrix/basis "disagreement" was the same confusion.
It was **not** a projection/view convention bug — both routes already agreed on
root objects (which is why the earlier camera-trace check passed: its scene has
no children).

### Fix (diagnostic only)

- [x] **One sample point** — every frustum test samples the region placement;
      the audit prints the region position explicitly (`posicion de REGION
      (parentWorld x local)=…   <- punto probado`) next to the local one, and
      `realPrimTestReport` compares it against the engine's `TransformManager`
      world matrix (`deltaVsRegion`).
- [x] **`CameraFrustum.fromEngine(...)`** — the basis is built from the vectors
      the engine reports, not from the square `CameraDesc` this layer sent;
      `cameraAudit` compares the axes.
- [x] **`MatrixTrace` / `MatrixFrustum.trace(point)`** — view-space, clip-space,
      NDC and depth from the *engine's* matrices; `sample()` reuses it.
- [x] **`appendObjectFrustumTest`** — compact block for `REAL_PRIM_TEST` and for
      the focused object (world position, eye, forward, view/clip/NDC, the three
      auxiliary verdicts, the engine's frame culling figure).
- [x] **Honest labels** — auxiliary routes marked `DIAGNOSTICO`; header states
      the real culling is the engine's. `boundsRadiusWorld` composes the scale
      along the parent chain.

### Not touched

`Renderer.kt`, `FilamentRenderer.kt`, the real culling, Parent/Child, transforms,
`ObjectUpdate` / `ObjectUpdateCompressed` / terse updates, geometry, materials,
distance culling, shadows, the toggle UI. No textures, no JPEG2000, no
Mesh/Sculpt/Avatar.

### Verified (harness)

- `tests_scale.kt` — the new `childFrustum` check (2.12b) plus `scalability` —
  **runs**: `render pipeline: OK (2 comprobaciones)`. It reproduces the exact
  failure geometry (root at (100,0,0), child `parentId=500` at local (0,0,-8) →
  region (100,0,-8); camera at (92,0,-8) looking at the child) and asserts that
  local ≠ region, that the child is inside, that the region position is printed,
  that the compact block appears, and that the audit shows no `acuerdo=NO` and
  no `depth=-`.
- `tests_parent.kt` (the 2.10/2.11 checks) still **runs**: `OK (4
  comprobaciones)`.
- `tests_render.kt` (all **38** checks) compiles with **1 error**, the same
  expected one (`bootstrapCheck` uses `FilamentBootstrap`, which the lean
  endpoint subset deliberately omits).
- The 79-file UI subset still compiles: only the 11 expected errors from denying
  `RenderTestActivity.kt` / `FilamentProbeActivity.kt`; `SLScene.kt` and
  `CameraFrustum.kt` are clean.
- Harness size note: the playground's *execute* path rejects payloads above
  ~676 KB and the *compile* path above ~760 KB, so the runnable subset is the
  40-file `lean` one (no `com.lumiyaviewer.lumiya.renderer.filament`) at ~578 KB.

### Pending on the device

- [ ] With `Modo: region`, focus / aim at `#625146406` (a linkset child), copy the
      `--- PRUEBA DE FRUSTUM #625146406 ---` block, its `REAL_PRIM_TEST` lines and
      its row of the `TRAZA SL -> entidad -> camara -> frustum` table.
- [ ] Confirm it now reads `visible DESPUES=DENTRO` and `acuerdo=SI`, with the
      region position marked as the tested point.

## Phase 2.12c — Benchmark A/B — **IMPLEMENTED, DEVICE MEASUREMENT PENDING**

No mechanism was added. This phase only makes the A/B measurement of the
already-shipped scalability switches reproducible, and labels each measurement.

### What was added

- [x] **`Benchmark state <A|B|C|D|->`** in the diagnostics report
      (`RenderDiagnostics.benchmarkState()`), derived from the engine's *applied*
      flags (culling/distance/shadows/LOD), so a copied report says which of the
      four states it is. `-` = any combination outside the four (e.g. LOD on).
- [x] **Nothing else.** The four states are the four existing toggles; no default
      changed (culling ON, distance ON, shadows ON, LOD OFF, 300 m, 40 m). No
      geometry, protocol, material, Parent/Child or avatar change. No textures,
      no JPEG2000, no Mesh/Sculpt/Avatar, no lights, no animation, no geometric
      LOD.

### The four states (same camera, never moved between them)

| State | Culling | Distancia | Sombras | LOD |
|-------|---------|-----------|---------|-----|
| A (default) | ON | ON | ON | OFF |
| B | **OFF** | ON | ON | OFF |
| C | ON | **OFF** | ON | OFF |
| D | ON | ON | **OFF** | OFF |

### Verified (harness)

- `tests_bench.kt` — the new `benchstate` check — **runs**: `render pipeline: OK
  (1 comprobaciones)`. It asserts that the label is A by default, B with culling
  off, C with distance off, D with shadows off, and `-` with the LOD on.
- `tests_scale.kt` (2.12/2.12b) still **runs**: `render pipeline: OK (2
  comprobaciones)`.
- `tests_parent.kt` still **runs**: `OK (4 comprobaciones)`.
- `tests_render.kt` (all **39** checks) compiles with the same single expected
  error (`bootstrapCheck` uses `FilamentBootstrap`, outside the lean subset).
- The 79-file UI subset still compiles with only the 11 expected errors from the
  two denied activities; `RenderDiagnostics.kt` is clean.

### Pending on the device

- [ ] Measure A, B, C and D with the same camera, stand still: 5-10 s settle,
      then copy several consecutive `DIAG` snapshots per state.
- [ ] Record FPS (mean, already the 20-frame mean), frame ms, GPU ms, renderables
      in scene / visible / culled-by-frustum / culled-by-distance, entities,
      meshes, materials, VBO/IBO, textures, and the five parent/child counters.
- [ ] Confirm the picture is identical in A/B/C (nothing visible disappears when
      culling is on) and that only the shadows change in D.

## Phase 2.13a — TextureEntry parser + asset seam — **CLOSED ON DEVICE (2.13a-rev4)**

The last device run closed it, and the parser is done:

```
blobs decodificados 5747   ·   truncados 0   ·   demasiado cortos 33 (todos vacios legales)
B 0   ·   C 0   ·   D 0   ·   E 0
campos completos 11:5747   ·   lector de referencia acepta 5747 / rechaza 0
ImprovedTerseObjectUpdate: prefijo4 3190/3190  ·  prefijo u32 consumido 3190/3190
despues de consumirlo: B 0, C 0, A 0, 11 campos 3190/3190
```

Nothing was left half-explained: the 453 truncations and the B/C blobs were the
terse field's own `u32` prefix, the correction consumed it at the handover, and
the parser itself has not been touched since. Phase 2.13a is **closed**; the
parser is not to be edited again unless a new reproducible case appears (the
user's instruction), and phase 2.13b starts from the UUID it produces.

Sub-phase 1 of the textures work (the project's "Phase 3 + Phase 4" split into
2.13a–h). Scope was strictly: parse the whole `TextureEntry`, model it per face,
wire it into the three decoder paths, and add the asset-provider seam. **Nothing
is drawn differently.**

### What was implemented

- [x] **Full `TextureEntry` parser** (`slproto/world/TextureEntry.kt`): the eleven
      packed fields of `LLPrimitive::packTEMessage` — image ids, colours (RGB**A**
      with the `255 - value` trick), scale S/T, offset S/T, rotation,
      bump/shiny/fullbright, media flags, glow, render-material UUID — each one a
      default plus variable-length 7-bit exception bitfields. Self-delimiting, so
      no face count is needed; truncated blobs are reported (`truncated`,
      `stoppedAt`) and never guessed at.
- [x] **Per-face model** (`TextureEntryFace`; `TextureEntry.face(i)`), plus
      `SLTextureFace.fromFace`/`facesOf` and `SLObject.textureEntry` so the scene
      layer has the per-face data.
- [x] **All three decoder paths** go through one `applyTextureEntry`:
      `ObjectUpdate`, `ObjectUpdateCompressed` and `ImprovedTerseObjectUpdate`.
      Each keeps its own semantics for `textureId` (full/compressed state it,
      terse only when it carries something).
- [x] **`textureEntrySize` conservation** kept everywhere (including the terse
      path, which now stores the size for a 1-byte blob too instead of skipping).
- [x] **Terse offset verified**: the message template declares
      `ObjectData { Data Variable 1; TextureEntry Variable 2 }` and the codec
      returns the *field* bytes (u16 length stripped), so offset 0 is correct here
      — there is no 4-byte shift to fix in this codebase (see the deviation note
      in `notes` below).
- [x] **`TextureAssetProvider`** (interface) + **`TextureAssetCache`**
      (UUID + discard level: UNKNOWN/PENDING/READY/FAILED, hit/miss/failed/retry,
      and the counters the report prints) + **`FakeTextureAssetProvider`** — no
      network, no HTTP, no decode, no upload.
- [x] **Temporary diagnostic**: parser counters and a bounded hex dump of the
      largest real blob, so the device run validates the parser against the bytes
      the region actually sends.

### Not touched (deliberately)

No HTTP/`GetTexture`, no `BackgroundExecutor` download path, no OpenJPEG, no JNI,
no CMake change, no JPEG2000 decode, no Filament texture, no GPU upload, no
streaming, no renderer change. The decoded tint/fullbright are stored but **not**
fed to the material yet, so the on-screen picture is the 2.12c one.

### Verified (harness)

- `tests_texture.kt` (parser + classification + diagnostics) **runs**:
  `render pipeline: OK (5 comprobaciones)`. Covers: empty/short blobs, the 16-byte
  minimal entry, all-zero, a full single-face entry, an 8-face entry mixing
  texture/tint/bump+glow/UV/material, a 12-face entry whose single exception needs
  a two-byte bitfield, rotation and alpha, truncation inside a field (with the
  field named), and — since the revision — the classification of a short blob
  (eleven fields / ten fields / UUID-only / cut in a default value / cut in an
  exception / cut in the variadic bitfield / cut in the optional material), plus
  the diagnostics' per-path split, stop-field histogram and verbatim samples.
- `tests_texdecode.kt` (the three decoder paths) **runs**: `OK (1)`.
- `tests_texcache.kt` (cache + fake provider) **runs**: `OK (1)`. Covers miss →
  pending → ready, a shared texture being fetched once (hit), failure + retry,
  and the discard level as part of the key.
- `tests_render.kt` (all **44** checks) compiles with the same single expected
  error (`bootstrapCheck` uses `FilamentBootstrap`, outside the lean subset).
- `tests_parent.kt` (`OK (4)`), `tests_scale.kt` (`OK (2)`), `tests_bench.kt`
  (`OK (1)`) still pass — the decoder changes did not disturb them.
- The 83-file UI subset still compiles with only the 11 expected errors from the
  two denied activities.

### Deviation from the audit plan

The audit said the terse path "reads TextureEntry at offset 0 while
LibreMetaverse reads it at offset 4". That is **not** how this codebase works: the
terse path takes the codec's `TextureEntry` *field*
(`ObjectUpdateDecoder.applyTerse`), and `SLMessageCodec.readField` strips the
`Variable 2` length, so the bytes start at the field's own offset 0. There is no
manual offset to correct. What was done instead: the parser is fed that same field
and the assumption is now asserted by a test.

### Device run (result)

- [x] Run with `Modo: region`: **confirmed working** (the viewer renders the
      region). The report printed `blobs decodificados 3125 · truncados 453 ·
      demasiado cortos 9 · campos leidos 29829` — `textureEntryFieldsRead` is *not*
      11 × the decoded entries, so the field walk is not the clean 11-per-entry
      case that was hoped for, and `truncados` is not 0.
- [x] The 453 truncations — and the 9 short blobs — are the subject of the
      revision below. **They are not reclassified or hidden until the revision's
      device data says which reading is real.**

## Phase 2.13a revision — why 453 entries look truncated — **IMPLEMENTED, DEVICE CHECK PENDING**

The first device run of 2.13a printed `blobs decodificados 3125 · truncados 453 ·
demasiado cortos 9 · campos leidos 29829`. Before adding any real texture
download, the revision has to say *what those 453 are*. **Diagnosis only**: the
renderer, the materials, the parent/child work, the geometry, the culling and the
rest of the protocol are untouched, and there is still no HTTP, no OpenJPEG, no
JNI and no Filament texture.

### What the reference sources actually say (`llprimitive.cpp`, in `scratch/ref/`)

- **The writer always emits all eleven fields.** Ten values each followed by a
  `0x00`, then the material UUID *unterminated*. The smallest blob a region
  sends is the all-default 63-byte, eleven-field one; there is no region-sent
  ten-field blob.
- **The reader appends a phantom `0x00`** ("the last field is not zero
  terminated… just make it 0x00 terminated") and requires a value to be followed
  by at least one byte (`source + size + 1 > source_end`). With the phantom byte
  that reduces to "the value must fit in the bytes received" — the rule this
  parser already applies, so no endianness, length or field-order bug is involved.
- **The reader tolerates a missing/short material** (`if (cur_ptr < buffer_end)`,
  failure swallowed). So a blob that stops after `glow`, or inside the material,
  is *accepted* with no material.

### What the revision adds

- [x] `TextureEntry` records the stop (`stop`, `stopFieldIndex`, `stopRemaining`,
      `stopExpectedSize`) and its reason (`DEFAULT_INCOMPLETE`,
      `EXCEPTION_INCOMPLETE`, `BITFIELD_INCOMPLETE`), and `belowWriterMinimum`
      (well formed — not truncated — but fewer than eleven fields: the reader
      accepts it, the writer never produces it). `OPTIONAL_FIELD_INDEX` names the
      tolerated field.
- [x] `ObjectUpdateDiagnostics`: the **A–E classification**, as totals *and per
      message path*, plus the fields-read histogram, the stop-field histogram, the
      size histogram of the truncated blobs, the empty-blob count and the count of
      truncations that stopped in the optional material. **No counter was removed
      or hidden** — `truncados` is still the strict number, and the categories only
      split it.
- [x] **Category D is now detected, not assumed.** The compressed path knows the
      length the section declares, so a declared length of `1 .. 62` — below the
      63 bytes `packTEMessage` always writes — or one larger than the bytes left is
      recorded (`impossibleTextureLengths` / `bogusSectionLengths`, with the first
      concrete case) and the blob is *attributed* to framing instead of being read
      as evidence about the entry. D is an attribution: it overlaps A/B/C.
- [x] Samples are dumped **verbatim**: full hex (up to 1 KiB — the 392-byte blob
      the old report cut at 96 bytes with `... (296 mas)` now appears whole), the
      window of the buffer it was cut from, and **what the reference reader would
      do with those same bytes** (`la referencia ACEPTA/RECHAZA …`). Ten sample
      classes fit in the budget, so no class is ever squeezed out.
- [x] Only `TextureEntry.kt`, `ObjectUpdateDiagnostics.kt` and
      `ObjectUpdateDecoder.kt` (the sample context, the framing check and one
      counter) changed.

### The classification every blob falls into

| Cat | Meaning | Reference reader | This parser |
|-----|---------|------------------|-------------|
| A absent | ten fields: the optional material is missing | accepts (no material) | complete, `materialAbsent` |
| A partial | cut inside the optional material | accepts (no material) | `truncated` at field 10, `materialIncomplete` |
| B | well formed, fewer than the ten mandatory fields | **rejects** the whole entry (the ten mandatory fields must fit: ≥ 46 bytes) | complete, `legacyFewerFields` |
| C | cut inside a mandatory field | rejects the whole entry | `truncated`, stop field + bytes left |
| D | the section's declared length is impossible | n/a (framing) | read as far as the bytes allow, attributed |
| E | 1–15 bytes: not even a UUID | rejects | `null`, `subUuid` |
| legal empty | the update carries no entry at all | nothing to read | `null`, `empty` |

### Pending on the device

- [ ] Paste the whole new `PARSER TextureEntry …` block: the totals, the split, the
      **CLASIFICACION A-E** line, the **per-path A-E** line, the framing line, the
      histograms and the sample lines with their `contexto=…` and their
      `la referencia …` verdict (plus `PARSER secciones con longitud imposible: …`
      if it appears).
- [ ] Only then, and only if the data proves it, move a category: the six numbers
      asked for are `453 = A + B + C + D + otros` and `9 = vacios + realmente
      incompletos`, per path.

## Phase 2.13a revision 2 — the device's 3448/449/399/50/1259 and the beginFrame rate — **IMPLEMENTED, DEVICE CHECK PENDING**

The second device report answers the questions the first revision could only pose,
and moves the problem from "how many" to "which ones, and why":

```
blobs decodificados 3448 · truncados 449 · demasiado cortos 9 (todos vacios legales)
A material opcional incompleto 399 · C campo obligatorio incompleto 50
B bien formadas con menos campos que los 11 que escribe la region 1259
menores que UUID 0 · framing D 0 · ExtraParams 0 imposibles / 0 truncados
ByteReader avances rechazados 0
render: Frames intentados 251325 · beginFrame OK 7058 · fallo 244267 · endFrame 7058
```

**Diagnosis only, again**: the parser's behaviour did not change (no blob is
parsed differently), nothing about the picture changed, and the render policy is
untouched. What changed is that A, B and the loop can now be *demonstrated*.

### The reference reader is ported, and it is what decides A/B

- [x] `slproto/world/TextureEntryReference.kt` — a line-by-line port of
      `LLPrimitive::unpackTEMessage` / `unpack_TEField` (phantom `0x00`,
      `source + size + 1 > source_end`, the optional material). Its verdict is
      **computed**, not asserted: `ACCEPTED` / `REJECTED(field)` / `NO_ENTRY`,
      with `MIN_ACCEPTED_SIZE = 46`.
- [x] The boundary was measured (sizes 0..100, one by one): **0 and ≥ 46 are
      accepted, 1..45 are rejected**. This **corrects** the first revision's claim
      that any field-boundary prefix is accepted: a B blob (fewer than ten
      mandatory fields) is **rejected** by the reference, and the viewer prints
      *"Dropping changes on the floor"*. B is therefore "not something the region
      writes", not "a valid prefix".
- [x] The writer's law is unchanged and still the reason B cannot come from the
      region: `packTEMessage` writes **eleven fields / 63 bytes**, or **nothing**
      when `getNumTEs() == 0`.
- [x] Every blob now carries its reference verdict in the report, with the field
      the rejects stopped at, the size histogram of the rejects, and the same
      split per message path.

### Framing is measured, not inferred

- [x] `SLMessage` / `SLMessageCodec` record a `FieldSpan(lengthWordOffset,
      dataOffset, declaredLength)` per variable/fixed field, and the message
      carries `bodyOffset` / `bodyLength` / `bodyComplete`.
- [x] Each texture sample line now prints `declarado=`, the length word's offset,
      the data offset, `restantes=`, `fin_coincide=si/no` (**does the blob end
      exactly at the end of the message body?**) and `cuerpo_completo=`. For a
      terse update that is the proof that a cut is real on the wire rather than
      our framing, and it is what the 50 C samples are waiting for.
- [x] `ByteReader` forward rejections are counted.

### The render loop is audited (policy unchanged)

- [x] `beginFrame()` is called from exactly one place: `FilamentRenderer.render()`.
- [x] `FilamentWorldView.RenderThread.run()` has **no Choreographer, no frame
      callback, no sleep between frames**.
- [x] `setTargetFrameRate` was **never called** (now counted, and the report says
      so).
- [x] There is **no wait after `beginFrame == false`** → immediate retry.
- [x] `framesAttempted` does **not** count turns where the surface was unusable
      (it is incremented inside `render()`), so the 251325 are real attempts.
- [x] New counters: loop iterations, skips by reason (no surface / renderer not
      ready / inactive), presented frames, current and worst run of consecutive
      refusals, wall time inside `drawFrame`, the share of the thread's time spent
      on attempts that presented nothing, and the mean cost per presented frame vs
      per refused attempt. The rate arithmetic lives in
      `RenderDiagnostics.publishRenderRates()` so the harness can exercise it.

### Tests (reproducible, no device needed)

- [x] The canonical harness (`tools/kcheck/tests_render.kt`, **47 checks**) gained
      `texref`, `texframing` and `renderloop`; `tools/kcheck/run_all.js` runs the
      whole suite (20 + 26 + the one unrunnable check) and asserts the total.
      `tools/kcheck/README.md` documents the procedure.
- [x] `camtrace`'s assertion was repaired: it still expected the pre-2.9 spelling
      `frustum(base)=DENTRO` while the report prints `frustum(base,
      DIAGNOSTICO)=DENTRO`, so it had been failing for a formatting reason, not a
      maths one.

### Standing conclusion

> The classification and anomaly detection work. The 50 case-C blobs remain
> **without a causal explanation**.

The 50 are **not** reclassified as A, **not** hidden inside B, and **not** written
off as "the parser is fine". 2.13b does not start until (a) the cause of the 50 is
demonstrated, (b) the beginFrame audit is reproducible, and (c) there are
reproducible tests for both.

### Pending on the device (this revision)

- [ ] Paste the whole `PARSER TextureEntry …` block: totals, split, **CLASIFICACION
      A-E**, **per-origin A-E**, the **reference verdict** line, the framing line,
      the size/fields histograms and the **sample lines** (each with `declarado=`,
      `palabra=@`, `offset=@`, `restantes=`, `fin_coincide=`, `cuerpo_completo=`,
      the context hex and the reference verdict).
- [ ] Paste the three new `Bucle de render (2.13a-rev): …` lines (policy, cost,
      rates) — they carry the loop iteration/skip counters, the consecutive-refusal
      run, the per-attempt cost and the fraction of the thread spent on attempts
      that presented nothing.
- [ ] No parser change until the 50 C samples are read. No geometry/texture/
      material/mesh optimisation, and no Parent/Child change, in this phase.

## Phase 2.13a revision 3 — the busy-spin is corrected and the terse field explains B/C — **IMPLEMENTED, DEVICE MEASUREMENT PENDING**

The third device report closed the render-loop audit with numbers, and those
numbers were a busy-spin, not a Filament fault:

```
beginFrame intentados 140673 · OK 3204 · fallo 137469 (97.7%) · 1572.9 intentos/s
99.5% del tiempo del hilo dentro de drawFrame · 68.8% gastado en intentos que no presentaron nada
coste medio 47.6 ms por frame presentado / 0.5 ms por intento fallido
sin Choreographer · sin frame callback · sin sleep entre frames
setTargetFrameRate NO · ningun throttle despues de beginFrame == false
```

### The pacing fix (and only the pacing)

- [x] `FilamentWorldView.RenderThread` no longer loops with `while (running)`.
      `runPacedByVsync()` prepares a `Looper` **on the render thread**, creates a
      `Choreographer` there, posts one `FrameCallback` and enters `Looper.loop()`.
      Each callback runs **exactly one turn** (`safeTurn()` — byte for byte the old
      body) and re-posts itself: **at most one `beginFrame` per display frame**, so
      a refused swap chain can no longer be hammered.
- [x] `runThrottled()` is the fallback **only** when no Looper/Choreographer can be
      set up on the thread: the same turns, spaced by one display period
      (`1e9 / refreshRate`, 60 Hz when unreadable). Neither path can spin.
- [x] `shutdown()` = `running = false` + `interrupt()` + `renderLooper?.quitSafely()`,
      which is what makes `Looper.loop()` return so `releaseEverything()` still runs.
- [x] The swap chain gets a rate hint once per attach
      (`BaseSwapChain.setFrameRate(60)`, guarded by `isFrameRateChangeSupported()`
      and by a `try/catch` so a refused hint can never cost the surface). A hint,
      never a hard cap.
- [x] **Not touched**: `SLScene`, `View`/`Camera`/`Scene`, culling, entity budget,
      materials, meshes, textures, Parent/Child. This is a pacing change, not an
      optimisation, so the A/B isolates one variable.
- [x] Failure handling is unchanged: a surface change still stops the thread to
      release the swap chain, and every exception inside a turn still leaves the
      stage and the message in the report.

### What the report now carries (all previous counters kept)

- [x] `Hilos: render '…' · UI '…' · ultimo input '…'` plus an explicit comparison:
      *"ATENCION: el render comparte hilo con la UI"* or *"el bucle de render NO
      comparte hilo con la UI ni con el input"*. Recorded, **not interpreted**.
- [x] `Pacing: …` with the mode, the display refresh rate and the number of VSYNC
      callbacks; `Bucle de render, politicas:` repeats the mode and states that
      after a `beginFrame=false` there is no wait of its own (the next turn arrives
      with the VSYNC).
- [x] `Frames: … (…% de los intentos)` — `beginFrame OK` and `beginFrame false`
      with their percentage.
- [x] `Bucle de render (2.13a-rev): … ritmo: N intentos/s / M presentados/s /
      K rechazados/s` — the rates the A/B comparison needs.
- [x] `Bucle de render, coste …`: wall time inside `drawFrame`, the share of the
      thread lost to attempts that presented nothing (ms and ms/s), and the mean
      cost per presented frame vs per refused attempt.

### The terse field: why B and C exist only in `ImprovedTerseObjectUpdate`

- [x] Five pieces of evidence, kept in `tools/kcheck/ref/terse_prefix_evidence.md`
      (so it outlives the session that fetched it): Linden Lab's own
      `message_template.msg` (`{ Data Variable 1 } { TextureEntry Variable 2 }`,
      identical to ours), OpenSim's terse writer (`totlen = len + 4` in the field's
      length word, then a **little-endian u32 of the entry's length**, `// wtf ???`
      upstream, and a **zero-length field** when there is no entry — the 9 "legal
      empties"), LibreMetaverse's reader (`new Primitive.TextureEntry(
      block.TextureEntry, 4, …Length - 4)` with *"FIXME: Why are we ignoring the
      first four bytes here?"*), our own **compressed** path (which already
      consumes `reader.u32()` and therefore refuses nothing), and a reproduction of
      the mechanism on real byte layouts.
- [x] So the terse field carries **four bytes of length that the full and
      compressed paths do not**: with them, a one-face entry stops inside a
      mandatory field (category **C**, the reference **rejects**) and a two-face
      entry completes but **7 of its 8 faces are no longer the object's** (the
      reference **accepts** — the silent false positive that makes the 455 accepted
      terse blobs suspect). Skipping exactly four bytes gives the entry back.
- [x] The parser is **still untouched**. What ships is the instrument: the report
      prints `PARSER TextureEntry prefijo de 4 bytes … <matches/total per source>`,
      the first terse whose prefix does **not** match, and per sample `prefijo4=…`
      and `saltando 4 bytes: campos=…`.

### Tests (reproducible, no device needed)

- [x] Canonical harness `tools/kcheck/tests_render.kt` — **48 checks**. `terseprefix`
      is new; `renderloop` now asserts the pacing mode, the thread comparison and
      the attempt/presented/refused rates.
- [x] `tools/kcheck/run_all.js`: light **21** + heavy **26** + 1 unrunnable
      (`bootstrap`, needs the real Filament AAR) = **48**, all green, plus the
      `ui/world/FilamentWorldView.kt` compile check. Retries raised (light 6,
      heavy 10, uiworld 6, 6 s apart) because the playground backend 500s are
      load-dependent at these payload sizes — the heavy group alone hit one.

### Pending on the device (this revision)

- [ ] FPS, CPU ms, GPU ms, the `beginFrame false` percentage and the thread
      occupancy, measured where the baseline was taken, so the two loops can be
      compared against **36.2 FPS / 27.6 ms CPU / 19.5 ms GPU**.
- [ ] The **per-origin A–E split** and the reference verdict per origin
      (`PARSER TextureEntry por origen, clasificacion A-E: …`) — the log needs it
      to move B and C from 1259/50 to a cause.
- [ ] The four terse samples with `declarado=`, `palabra=@`, `offset=@`,
      `restantes=`, `fin_coincide=`, `cuerpo_completo=`, the context hex, their
      reference verdict, and the new `prefijo4=` / `saltando 4 bytes:` lines.
- [ ] No 2.13b, no OpenJPEG/J2K, no texture download or render until the above
      arrives. (The terse prefix correction *is* applied, in revision 4 below: it
      changes the handover, not the parser.)

## Phase 2.13a revision 4 — the terse prefix is consumed, and the metrics add up — **IMPLEMENTED, DEVICE CHECK PENDING**

The third device run closed the case the third revision had opened:

```
ImprovedTerseObjectUpdate: prefijo4 coincide en 1975/1975 (100%)
ObjectUpdate: 0/589      ObjectUpdateCompressed: 0/644
muestras reales: campos incompletos -> 11 campos correctos al saltar 4 bytes
los B/C se concentran exclusivamente en ImprovedTerseObjectUpdate
```

### The one functional change

- [x] `ObjectUpdateDecoder.applyTerse` hands the parser
      `ObjectUpdateDiagnostics.consumeTersePrefix(field)` instead of the raw field.
      The leading little-endian `u32` is removed **only when it equals the bytes
      that follow it** (`raw.size > 4 && leadingU32(raw) == raw.size - 4`); a field
      that does not carry it is handed over untouched and counted
      (`tersePrefixMissing`), so a grid that does not write it stays correct and
      visible.
- [x] `TextureEntry.parse` (the parser) is **not touched**. `ObjectUpdate` and
      `ObjectUpdateCompressed` are **not touched** (the compressed path already
      consumes its own u32 inside `Data`). Geometry, the renderer, materials,
      Parent/Child and J2K are **not touched**.
- [x] `textureContext` still receives the field as it arrives, so `declarado=`,
      `palabra=@`, `offset=@`, `restantes=` and `fin_coincide=` keep describing the
      wire, and each kept sample still prints `prefijo4=… (COINCIDE: es la longitud
      que queda) · saltando 4 bytes: campos=11`.
- [x] The pacing is **not** touched again: the Choreographer/VSYNC loop from the
      third revision stays as it is.

### The counters the device has to move

- [x] `consumeTersePrefix` classifies the field twice — as it arrives and after
      consuming it — with the same parser and the same reference reader, and the
      report prints both:
      `PARSER TextureEntry terse, prefijo u32 (correccion 2.13a-rev4): campos
      vistos N · consumidos N · sin prefijo que coincida 0`, then
      `terse, ANTES (el campo tal cual llega)` and
      `terse, DESPUES (consumido el u32)`, each with `11 campos / A ausente /
      A a medias / B / C / otros / la referencia acepta / rechaza`.
- [x] The four-byte measurement moved into `consumeTersePrefix` (the last place the
      field can be seen) so the `prefijo de 4 bytes` line keeps reporting
      `ImprovedTerseObjectUpdate 1975/1975`.

### The render-loop arithmetic

- [x] `publishRenderRates` is a pure function of (counters, `loopNanos`, `now`):
      the **capture** rates divide the counters by the wall time the render thread
      has been running, and the report prints the division
      (`39.1 intentos/s = 8244 / 210.6 s · 34.3 presentados/s = 7214 / 210.6 s ·
      4.9 rechazados/s = 1030 / 210.6 s`).
- [x] `beginFrameFailPercent()` is `100 * fallo / intentos` — the "intentados" of the
      same line — and attempts that never reached `beginFrame` are named separately,
      so `OK + fallo + sin llegar a beginFrame == intentados`.
- [x] The cost line divides by the same denominator:
      `… 6932.6 ms (3.3% del hilo, 32.9 ms/s sobre 210.6 s)`, all of it
      `failedFrameNanos / loopNanos` or `/ captureSeconds`.
- [x] The recent rate stays available as its own line with its own duration
      (`ritmo reciente (ventana de 2.0 s): …`) so it can never be mistaken for a
      capture figure.

### Tests

- [x] `tests_render.kt` — **49 checks** (`22` light + `26` heavy + the unrunnable
      `bootstrap`).
- [x] `terseconsume` (new): one face, several faces, a full material, the optional
      material left out, the case that used to end in C, the case that used to end
      in B, and `u32 == the remaining length` in all of them; plus the before/after
      counters (`B 1 -> 0 · C 1 -> 0 · la referencia acepta 0 -> 2 / rechaza 2 -> 0`
      over its corpus of 25 entry layouts).
- [x] `terseprefix`: still measures the mechanism on raw blobs, and now asserts the
      decoder hands over the entry (64 B, eleven fields, right texture) while the
      four-byte counter still fires.
- [x] `renderloop`: asserts the corrected formulas with the device's numbers
      (8244 / 7214 / 1030 over 210.5757 s).

### Pending on the device (this revision)

- [x] `PARSER TextureEntry terse, prefijo u32 (correccion 2.13a-rev4): campos
      vistos N · consumidos N · sin prefijo que coincida M` — the run closed it:
      **3190 vistos, 3190 consumidos, 0 sin prefijo**.
- [x] The `ANTES`/`DESPUES` pair: `B 0 · C 0 · A 0 · 11 campos 3190/3190` after
      consuming it, and the reference accepts 5747 of 5747 entries — **TextureEntry
      is declared finished** (2.13a closed).
- [x] The corrected `Frames:`, `ritmo de la captura` and `coste` lines — measured;
      the numbers are in the device reference table below and in the render-loop
      revision of the README.
- [x] Still parked in that revision: 2.13b, OpenJPEG/J2K, texture download and
      texture rendering — **2.13b is done now**; see its own section below.

## Phase 2.13b — texture asset pipeline (petición → cache → asset → decode → upload) — **IMPLEMENTED, DEVICE EVIDENCE PENDING**

Written against the nine points the user gave for this phase, in their order. The
scope was kept where they asked: **the `TextureEntry` parser is not touched**
(point 7), and **neither Parent/Child nor geometry** (point 8); the JPEG2000
decoder is deliberately *not* part of this phase (point 3: "once the asset flow is
demonstrated"), so 2.13b proves the flow and 2.13c links OpenJPEG into a seam that
is already there.

### What was implemented

- [x] **1. The texture from the UUID `TextureEntry` already extracts.**
      `SLScene.attach` now takes the UUID **per face**
      (`SLTextureFace.facesOf(entry, mesh.faceCount)`) instead of face 0 only, and
      asks the streamer for each face's texture. Nothing in the parser moved.
- [x] **2. The request/cache/asset pipeline.** Four stages, four owners:
      `TextureTransport` → `GetTextureTransport` (the capability, the only HTTP in
      the viewer), `GetTextureAssetProvider` (two worker threads, de-duplication,
      cancellation, bounded waiting for the capability, timings),
      `TextureAssetCache` (one entry per `uuid@discard`, unchanged from 2.13a) and
      `TexturePipeline` (the frame contract, the decode queue, the counters).
- [x] **3. JPEG2000/OpenJPEG** — *gated, not done*, as instructed. The seam is
      `TextureDecoder` (+ `DecodedTexture`); 2.13b ships
      `UnavailableTextureDecoder` (`isAvailable = false`), and the pipeline **keeps
      and waits** for the bytes rather than failing them. The check proves the
      hand-off: setting a decoder afterwards decodes the already-downloaded bytes
      with **zero** extra requests.
- [x] **4. The format Filament consumes.** `TextureDesc` (RGBA8, 8-bit, top row
      first) is produced from `DecodedTexture`, and a decoder that returns a
      buffer whose size does not match its dimensions is refused before it can
      reach the GPU.
- [x] **5. Applied to materials/faces, respecting `TextureEntry`.** One material
      **per face** (`SLTextureCache.materialsFor`), with repeat/offset from the
      face, its tint, its fullbright, the prim's material code, and its alpha
      (`AlphaMode.forAlpha`: not opaque → blended, the reference viewer's rule).
      Faces that coincide still share one material, so the 20-object test scene
      creates exactly 10.
- [x] **6. Fallback while a texture is not available.** Three distinct cases, three
      distinct report figures: not asked for yet (`esperando pixels`), refused by
      the grid (`cache: fallidas`), refused by the decoder (`decode: fallo`). On
      screen all three are the face's own `TextureEntry` tint, or the per-texture
      debug tint when the wire carried none. A backend that refuses
      `createTexture` is caught too (`upload: fallo`) — a whole scene is run
      against a renderer that always refuses in `textureBindingCheck`.
- [x] **7. The `TextureEntry` parser was not modified.** `git`-less proof: the
      files touched are listed below, and `slproto/world/TextureEntry.kt` and
      `TextureEntryReference.kt` are not among them.
- [x] **8. Parent/Child and geometry untouched** — no file under
      `slscene/…Geometry`, no parent-link code, no mesh code.
- [x] **9. The counters.** `TextureAssetStats` (cache), `TextureTransportStats`
      (wire: peticiones/en vuelo/respuestas/errores/canceladas/bytes/tiempo de
      descarga), `TexturePipelineStats` (diferidas/tope/assets/decode/upload,
      tiempos de decode y de upload) and the scene's live figures (materiales con
      textura, caras con textura, entidades con textura, memoria de caché e
      imágenes). All of them in one block, `SLScene.textureReport()`, on the HUD
      (one line) and in `filesDir/ephora-filament.log`.

### Files

New: `slproto/asset/TextureTransport.kt`, `slproto/asset/TextureDecoder.kt`,
`slproto/asset/GetTextureAssetProvider.kt`, `slproto/asset/TexturePipeline.kt`,
`slproto/asset/TextureFormat.kt`, `slproto/caps/GetTextureTransport.kt`,
`slworld/TextureStreamer.kt`.
Modified: `slproto/asset/TextureAssets.kt` (interface + transport counters),
`slproto/asset/TextureAssetCache.kt` (held-bytes/pending/failed accessors),
`slproto/asset/FakeTextureAssetProvider.kt` (the same interface),
`slproto/caps/Capabilities.kt` (**thread-safety**: the resolved map is published
as an immutable snapshot, because the texture workers read it off the session
thread), `slproto/modules/SLConnection.kt` (`val textures`),
`slworld/SLTexture.kt` (`DEFAULT_FACE`), `slworld/SLTextureCache.kt` (per-face
materials, alpha mode), `slscene/SLScene.kt` (per-face attach, the visible-and-near
scan, the pump, the report), `renderer/Renderer.kt` (`AlphaMode.forAlpha`),
`renderer/RenderDiagnostics.kt` (the texture HUD line and report block),
`ui/world/FilamentWorldView.kt` (builds the pipeline and carries the switch).
`slproto/world/TextureEntry.kt` and its reference reader: **untouched**.

### Threading and load, stated because it is the part that can go wrong

- The render thread only ever calls `request` (cache lookup + queue push) and
  `pump` (take what finished). It never blocks on a socket and never decodes.
- Two provider workers ("EphoraTexture-n") do the blocking POST; two decode
  workers ("EphoraDecode-n") decode. Both pools are daemons and start on first
  use, so nothing is created before the grid has answered.
- Only *visible* objects within `SLScene.TEXTURE_NEAR_METRES` (64 m) are asked
  for, nearest first, at most `perScanBudget` (24) **new** textures per scan
  (twice a second). A whole 256 m region is never requested on arrival, which was
  an explicit requirement.
- Per frame the pipeline's work is bounded by what completed, not by what is
  outstanding.

### Tests

- [x] `texasset` (`texturePipelineCheck`, **light** group, 24th): the real
      provider over a fake transport — a repeat is one fetch, a cancel mid-flight
      produces no result, "not ready" defers instead of failing, the cap stops
      without breaking, no decoder means *waiting* and not *failing*, linking a
      decoder afterwards decodes the cached bytes with no re-download, a refused
      codestream and an HTTP error are remembered with their reason, and re-arming
      after the GPU side is dropped re-decodes without re-downloading. Plus the
      HUD/summary lines carry their counters.
- [x] `texbind` (`textureBindingCheck`, **heavy** group, 27th): a full 20-object
      scene through the real pipeline — 20 faces ask for **10** textures (the
      sharing is tested, not assumed), 20 faces end up with pixels, 10 textures are
      uploaded, 10 materials are rebuilt, the report prints every counter the user
      asked for, the alpha rule holds, and a renderer that refuses every texture
      leaves the scene drawn and counted.
- [x] The group split is asserted by `run_all.js` (`expected === 52`: 24 light +
      27 heavy + the unrunnable `bootstrap`), so a check cannot be silently
      dropped from a group.
- [x] Green, measured: light **24/24 OK**, heavy **27/27 OK** (`mineErrors: 0`),
      both compile-only passes clean (`ui/world/FilamentWorldView.kt`,
      `slproto.modules` with nothing skipped), and `texasset` OK again on its own
      389 kB group after the group was regenerated. The two big groups were
      reached in their own windows rather than in one `run_all.js` sequence: the
      playground endpoint returns HTTP 500 for submissions over ~450 kB (the
      light group is 504 kB, the heavy one 659 kB), which says nothing about the
      code — `run_all.js` now retries them in alternating rounds, records every
      attempt, and generates `extra/tests_texasset.kt` as the fallback that always
      fits. Numbers and recipe: `tools/kcheck/README.md`.

### Pending on the device (this phase)

- [ ] The texture block of the report
      (`--- texturas (fase 2.13b) ---` …) from a real region: it says whether
      `GetTexture` answers, how many UUIDs were asked for, how long the downloads
      took, how much memory the cache holds, and — with no decoder — how many
      assets are *waiting* for 2.13c.
- [ ] Confirm `en vuelo` goes back to 0 and `respuestas recibidas` matches
      `peticiones emitidas` (no hung requests), and that `errores de red` is 0 or
      carries a reason.
- [ ] Confirm the visual fallback: prims are drawn with their `TextureEntry`
      tint (and not the debug tint) when the wire sent one — that is what 2.13b
      looks like with no decoder.
- [ ] If a download fails with `HTTP 403/404` for every UUID, paste one line:
      it means the region does not offer `GetTexture` and the UDP
      `RequestImage` path (Phase 4) becomes the fallback.
- [ ] Optional, to exercise the rest of the chain on the device before OpenJPEG:
      set `TEXTURE_DECODER_SYNTHETIC = true` in `FilamentWorldView` and check the
      upload/decode counters and `caras con textura`. The report labels the
      decoder as synthetic, so this can never be mistaken for real textures.

## Phase 2.13b revision 1 — the camera basis corrected + every GetTexture failure named — **IMPLEMENTED, DEVICE TEST PENDING**

The 2.13b device run answered the movement question and opened the texture one:

```
pulsaciones 79  ·  AgentUpdate construidos 337  ·  enviados 337  ·  errores de envio 0
cambios de posicion del avatar propio 16
base de camara enviada: at (0,0,-1)  ·  izquierda (0,1,0)  ·  arriba (0,0,1)
UUIDs solicitados 649  ·  peticiones emitidas 649  ·  en vuelo 0
respuestas recibidas 649  ·  errores de red 649  ·  bytes 0  ·  assets listos 0
```

So the movement chain works end to end and the command's content is the suspect,
and every texture request failed with 0 bytes. This revision changes **only** those
two things.

### Movement — M1 applied

- [x] `SLConnection.sendAgentUpdate` now builds the camera basis with
      `AgentUpdateBuilder.referenceBasis(viewYaw)` instead of `currentBasis()`.
      **Nothing else in the movement path changed.** `currentBasis()` is kept and
      documented as the historical M1 value (the report prints it as a contrast).
- [x] `AgentUpdateBuilder` — reads the basis / flags / body-rotation / camera-center
      back **out of the built packet** (`serializedBasis`, `serializedControlFlags`,
      …), so the report shows what the wire carries, not what was passed in.
- [x] `MovementAudit` — `lastBasisValue`, `sentBasisMatchesReference()`,
      `lastBodyYawSent`, `ownPreviousPosition`. The report block now prints: the
      serialized camera basis + whether it matches the reference frame for the
      heading sent, the serialized `ControlFlags`, the yaw used, the M1 basis as a
      contrast line, and position before/after + net displacement + total path.
- [x] `moveaudit` check rewritten to read the basis at offsets 77/89/101, assert the
      corrected frame is orthonormal (volume +1) and equals `referenceBasis(yaw)`,
      and still assert the degenerate M1 triple as the *old* value.

### Textures — a reason for each of the 649 failures

- [x] `TextureFetchFailure` (`TextureTransport.kt`) — capability, HTTP method,
      endpoint host:port, whether a response existed, status, Content-Type, size,
      exception + class, `Server`/`Via`, a **sanitized** body excerpt, `looksProxied`.
      `HttpText.sanitize` redacts every URL, `token=`, `session_id=`, `Cookie:` and
      `Authorization:` — so the pasted report cannot leak credentials, tokens,
      cookies or full private URLs.
- [x] `Capabilities` — records the failure of every capability call
      (`lastFailureOf` / `failureCountOf` / `endpointHost`) and the seed's own
      failures under `seed`, which is what lets the report line `GetTexture` up
      against `EventQueueGet` (the HTTP 500 / 502 Proxy Error of the same log).
- [x] `TextureTransportStats` — separates success from error
      (`respuestas recibidas (con bytes / con error)`), and classifies each failure:
      `CapabilityMissing`, `NotReady`, `HttpError`, `TransportException`,
      `EmptyResponse`, `Unclassified`, plus `proxiedFailures` and first/last
      failure with the UUID.
- [x] `failureReading` (`TextureFormat.kt`) — the A–F reading the iteration asked
      for: A capability missing/wrong · B endpoint/not ready · C HTTP error ·
      D proxy/transport · E valid response with 0 bytes · F local parsing, with
      the parts adding up to the total.
- [x] `TextureStreamer` report — `respuestas recibidas (con bytes/con error)`,
      `errores por tipo`, `lectura A-F`, `primer error`, `ultimo error`, and the
      `GetTexture` / `EventQueueGet` / `GetMesh` / `seed` comparison lines.
- [x] **`TextureEntry`, parent/child and the render loop were not touched.**

### Verified without a device

- [x] `moveaudit` **1/1 OK** in its own ~400 kB one-check group
      (`tests_moveaudit.kt`, new fallback like `texasset`), which verifies the
      rewritten check off-device: the basis read back out of the packet is
      orthonormal and equals `referenceBasis(viewYaw)`, and the comparison stays
      true when the body heading differs from the view yaw.
- [x] `capsfail` **1/1 OK** (`capabilityFailureCheck`, new group that proves the
      sanitizer, the facts of a proxy 502, the six kinds and the A–F partition),
      `texasset` **OK**, and `light` **24/24 OK** in an earlier window.
- [x] Compile-only (earlier in the session): `slproto.modules` (nothing skipped)
      **200, 0 errores**; `ui/world/FilamentWorldView.kt` **200, 0 errores**;
      `tests_heavy.kt` payload compiles (`entry: false`) **200, 0 errores**.
      By the end of the session the backend was refusing every payload over
      ~400 kB (500, not a compiler error).
- [ ] `heavy` (27 checks, `texbind`) 27/27 pending: the backend refused the 659 kB
      payload with HTTP 500 during this revision's window; a `texbind`-alone group
      measures 605 kB (the sources dominate), so there is no smaller equivalent.
      Re-run, not assumed.

### Pending on the device (this revision)

- [ ] Paste the movement block with the pad pressed in the foreground, paying
      attention to `base de camara serializada … -> COINCIDE`, `primer
      desplazamiento`, `desplazamiento neto` and `recorrido total`. This is what
      says whether the avatar now walks *forward*.
- [ ] Paste the texture block: `respuestas recibidas (… con bytes / con error)`,
      `errores por tipo`, `lectura A-F`, `primer error`, `ultimo error`, and the
      `EventQueueGet` / `GetMesh` / `seed` comparison lines — enough to choose
      between A and F.
- [ ] No OpenJPEG / J2K integration in this revision (the user's instruction).

## Phase 2.13b revision 2 — el 403 del CDN (forma de la peticion) y la ventana FORWARD — **IMPLEMENTED, DEVICE TEST PENDING**

La prueba en dispositivo de 2.13b-rev1 dio un hecho duro sobre las texturas y dejó
el movimiento sin localizar:

```
peticiones 755  ·  respuestas 755  ·  errores 755  ·  bytes 0  ·  assets listos 0
todos HTTP 403  ·  asset-cdn.glb.agni.lindenlab.com  ·  text/html  ·  398 B
cuerpo: "Access Denied ... asset-cdn.glb.agni.lindenlab.com/"
movimiento: pulsaciones 45  ·  construidos 219  ·  enviados 219  ·  cambios de posicion 14
            flags serializados 0x00000000  <- el paquete de SOLTAR
```

La base de camara serializada (`at (0.59,-0.81,0.00) ... COINCIDE`) confirma la
correccion M1, asi que `referenceBasis`/`currentBasis` **no se han vuelto a tocar**.

### A) El 403: la forma de la peticion, no el endpoint

- [x] El flujo se comparó con el visor oficial (`indra/newview/lltexturefetch.cpp`
      y `llviewerregion.cpp`, rama `develop`): el visor oficial pide la capability
      **`ViewerAsset`** y construye `GET <base>/?texture_id=<uuid>`
      (`setUrl(http_url + "/?texture_id=" + mID.asString())`), sin cuerpo. Este
      visor pedia `GetTexture` (que en agni resuelve al **mismo** CDN de assets) y
      enviaba un **POST con cuerpo LLSD** a la raiz → `403 Access Denied`.
- [x] PR de referencia secundaria: `secondlife/viewer#6329` ("Retry HTTP 403
      Forbidden texture fetches on region crossing", abierto) documenta el mismo
      `403` del mismo CDN con la URL/firma caducada tras un cruce de region. No se
      adopta su reintento (nuestro caso no es una firma caducada sino la forma).
- [x] `GetTextureTransport.assetRequestFor` + `assetUrlFor`: URL **sin ruta** →
      `GET <base>/?texture_id=<uuid>`; URL **con ruta** → `POST` LLSD
      (`texture_id`, `discard_level`) como antes. Casos de barra final y de query
      ya presente cubiertos.
- [x] `Capabilities.http` (método explícito, sin cuerpo en GET) +
      `Capabilities.requestAsset` + `CapabilityCallFailure.method` (el fallo
      conserva si se pidió con `GET` o `POST`) + `Capabilities.urlShape`.
- [x] El informe imprime la **forma** de las URLs resueltas (`GetTexture`,
      `ViewerAsset`: esquema, host, si hay ruta/query y los **nombres** de las
      claves de la query; nunca los valores) y la **forma de la peticion** usada.
- [x] 403 del CDN (`C`) y 502 de proxy de `EventQueueGet` (`D`) siguen siendo
      categorias separadas, y se comprueba con los mismos contadores.
- [x] **No** se toca `TextureEntry`, Parent/Child ni el bucle de render. **No** se
      integra OpenJPEG.

### B) La ventana de pulso de FORWARD (instrumentacion)

- [x] `MovementAudit`: una ventana por pulsacion de FORWARD. Cuenta los
      `AgentUpdate` serializados con la tecla pulsada, cuantos llevan
      `ControlFlags & 0x00000001 != 0`, el primero y el ultimo con `AT_POS` con su
      secuencia y sus flags serializados exactos, el conjunto de flags vistos, los
      paquetes con NUDGE, y las posiciones **antes / con la tecla pulsada /
      despues de soltar**.
- [x] Deltas por cada cambio de posicion dentro de la ventana (primero, ultimo y
      lista, max. 12) + recorrido + **neto desde la pulsacion**. No se usa el
      estado final.
- [x] `AgentControlFlags`: bits NUDGE (`0x00080000`..) y `hasNudge`, para que
      ningun bit desconocido del word de `ControlFlags` salga como hex misterioso.
- [x] `moveaudit` ampliado: la tabla de flags con NUDGE, la ventana completa, y la
      asercion de que la ventana recuerda `AT_POS` aunque el ultimo paquete sea
      `0x0`.

### Verificado sin dispositivo

- [x] `moveaudit` **1/1 OK** (grupo propio de ~400 kB), `capsfail` **1/1 OK**
      (formas de URL, `403` con su `GET`, particion `C`/`D` del 403+502),
      `texasset` **OK (3 ejecuciones seguidas)**, `slproto.modules` (sin saltos)
      **200, 0 errores**, `ui/world/FilamentWorldView.kt` **0 errores**.
- [x] El grupo `light` alcanzó el backend **una vez** (200, compilacion limpia) y
      descubrió una **asercion floja de la propia auditoria**, no del visor:
      `texturePipelineCheck` pedia dos veces la misma textura esperando un
      duplicado, pero el worker terminaba la primera descarga antes de la segunda
      llamada (mas aun con la JVM caliente tras 23 comprobaciones), y entonces la
      segunda era una peticion nueva. Ahora el transporte se mantiene **bloqueado
      entre las dos llamadas**, asi que el duplicado es determinista; `texasset`
      pasa 3/3 despues del cambio.
- [ ] `light` (24) y `heavy` (27) completos: el backend devolvio HTTP 500 en todas
      las ventanas de la sesion (mas de 30 intentos); se reintentan, no se asumen.
      El limite medido (~450 kB) esta en `tools/kcheck/README.md`.

### Pendiente en el dispositivo

- [x] ~~Bloque de movimiento con el pad pulsado en primer plano: `ventana FORWARD,
      comandos con la tecla pulsada: U ... con AT_POS F ...`~~ → **entregado**: la
      base `COINCIDE`, `AT_POS = 0x00000001` durante FORWARD y los paquetes salen
      sin error. Pero quedaron dos numeros que no cuadran: **50 pulsaciones y solo
      2 paquetes con FORWARD=1**, **0 cambios de posicion** y **0 m de recorrido**
      dentro de la ventana. Ver la revision 3 abajo.
- [x] ~~Bloque de texturas: que `errores` caiga y `assets listos` suba~~ →
      **CONFIRMADO EN DISPOSITIVO**: `412 solicitudes · 412 respuestas con bytes ·
      99.1 MiB · 0 errores · 412 assets listos · 0 fallidos`. El transporte
      `GetTexture` y `TextureEntry` **ya no se tocan**.
- [ ] OpenJPEG sigue sin integrarse: es el paso funcional siguiente de texturas
      (fase 2.13c), sobre el seam `TextureDecoder` existente, hasta RGBA8.

## Phase 2.13b revision 3 — la ventana FORWARD medida de verdad — **TEMPORARY DIAGNOSIS, DEVICE RUN PENDING**

El transporte de texturas ya esta confirmado en el dispositivo (arriba), asi que
esta revision **no toca texturas**: ni `GetTextureTransport`, ni `TextureEntry`, ni
el bucle de render. Tampoco toca el protocolo de movimiento ni la base de camara.
Lo unico que cambia es `MovementAudit`, y es **diagnostico temporal** (marcado como
tal en el codigo, para borrarlo cuando la causa este localizada).

### La duda

La ventana de 2.13b-rev2 cuenta los paquetes que **se serializaron mientras la
ventana estaba abierta**. Pero `sendAgentUpdateAsync()` entrega el envio a
`Dispatchers.IO`, asi que el paquete de una pulsacion puede construirse **despues**
de que el soltar ya haya borrado el flag. Eso explicaria el `50 -> 2` — pero es una
**hipotesis**, y la traza existe para confirmarla o matarla, no para creerla.

### Que se a~nade (solo a `MovementAudit`)

- [x] Totales acumulados de **todas** las ventanas, no solo la ultima: paquetes con
      la tecla pulsada, con/sin `AT_POS`, ventanas sin ningun `AT_POS`, y
      **envios que salen DESPUES de soltar** (dentro de 1200 ms) con/sin `AT_POS`.
- [x] Duracion real de la ultima ventana y tiempo total con la tecla pulsada;
      primer y ultimo `AT_POS` (t relativo) y la lista de intervalos entre ellos.
- [x] Muestreo del **estado interno** del motor (el word `controlFlags`), al margen
      de lo que se serialice: muestras, cuantas con `AT_POS`, y la conclusion
      `SI/NO mantiene FORWARD activo entre paquetes`.
- [x] Cada cambio de posicion atado a la **secuencia** del ultimo paquete enviado:
      `(dx,dy,dz) tras seq S`.
- [x] Resumen por ventana de las ultimas 12: duracion, paquetes, con `AT_POS`,
      enviados tras soltar e intervalos.
- [x] **Traza entrelazada** con `t` en ms: `P` pulsado, `S` soltado,
      `interno AT_POS`/`interno sin AT_POS`, `E+`/`E-` enviado con/sin `AT_POS`
      (con `seq`, `flag` hex y si fue tarde), `M` movimiento.
- [x] `moveaudit` comprueba todo eso **sin dispositivo**, incluido el caso entero
      de la revision: un toque cuyo `AT_POS` se serializa **despues** de soltar,
      que tiene que caer en `forwardLateFlagged` y en una ventana contada como
      vacia (el "50 -> 2" hecho determinista).

### Verificado sin dispositivo

- [x] `moveaudit` **1/1 OK** (0 errores) con las nuevas aserciones,
      `capsfail` **1/1 OK**, `texasset` **1/1 OK**,
      `slproto.modules` compile-only (sin saltos) **200, 0 errores**,
      `ui/world/FilamentWorldView.kt` **0 errores**.
- [ ] `light` (24) y `heavy` (27): no se reintentan esta ronda (el backend los
      rechaza con HTTP 500 y cada intento cuesta ~1 min); siguen pendientes de una
      ventana buena.

### Pendiente en el dispositivo

- [ ] El bloque de movimiento **completo**, con el pad pulsado en primer plano:
      las lineas `ventana FORWARD` de 2.13b-rev2 **mas** las seis nuevas
      (`totales`, `tiempos`, `estado interno del motor`, `posiciones y secuencias`,
      `ultimas ventanas`, `traza`).

## Movement audit — the pad is on screen and the avatar does not advance — **INSTRUMENTED, DEVICE RUN PENDING**

A separate investigation from the textures (its own document:
`MOVEMENT-AUDIT.md`). The chain is: **pad → handler → control flags →
`AgentUpdate` → the simulator → our own object's updates → camera/HUD**. The point
is to make each link measurable so one device run says *where it cuts* (A input,
B engine, C send, D simulator, E local reflection) instead of "the controls do
nothing".

### What was done

- [x] The chain was located and documented with file and line, and the earlier
      device log was read against it: it proves the pad reached the engine, the
      packet was built and encoded, and it reached `sendto` (the `EPERM` line is
      printed *inside* `Circuit.transmit`, after encoding) — and that
      `AgentMovementComplete` arrived and our own avatar was found
      (`Avatar propio detectado: id local 545575791`).
- [x] `slproto/movement/AgentControlFlags.kt` — one control-bit table for the
      whole app (was duplicated inside `SLConnection`), with the reference values.
- [x] `slproto/movement/AgentUpdateBuilder.kt` — the `AgentUpdate` is now built by
      a **pure function**, so the check harness encodes the same message the
      device sends. The camera basis is a *parameter*, not a constant inside the
      encoder. `MoveAction` moved here too, so the action→bit mapping is testable.
- [x] `slproto/movement/MovementAudit.kt` — counters for all five links, and a
      `verdict()` that names the link that stops the chain. Printed in the report
      (one line per link), in the HUD (one line) and in the verdict box.
- [x] The existing code was rewired to count, **not to change behaviour**:
      `SLConnection` (pad, handler, state, command build/send/error, spawn,
      own-object adoption) and `FilamentWorldView` (the camera focus, and the
      report/HUD/verdict lines).
- [x] `moveaudit` check (50th check, light group): the control table against the
      reference, the action→bit mapping, the encoded `AgentUpdate` byte for byte,
      the basis finding, and the A–E classifier with its counters.
- [x] The harness grew a fourth pass, `slproto.modules (compila)`: the session and
      its real dependencies compiled **with nothing skipped**, which is what
      actually compile-checks the movement wiring (the ui-world pass skips those
      packages by design).

### Findings (in `MOVEMENT-AUDIT.md`)

- [x] **M1 — the camera basis is not a frame (the prime suspect).** `at` is
      `(0,0,-1)` (straight down, in a Z-up world), `up` is its exact opposite and
      the three vectors are coplanar: the frame has zero volume. Every reference
      client sends an orthonormal, right-handed frame for the same heading
      (`at = (cos h, sin h, 0)`, `left = up × at`, `up = (0,0,1)`; evidence:
      `AgentManagerMovement.cs` + `CoordinateFrame.cs` of LibreMetaverse). OpenSim
      builds the agent's camera rotation from this triple
      (`Axes2Rot(at, left, up)`), so a degenerate triple is not inert.
- [x] **M2 — the send rate** (5/s while pressed, keepalive every 4 s) versus the
      reference's 2–10/s: recorded, not judged; it satisfies OpenSim's
      significance test.
- [x] **M3 — no guard on `AgentMovementComplete`**: an `AgentUpdate` may be sent
      inside the window `CompleteAgentMovement` → reply, which LibreMetaverse
      warns "causes corruption of the agent position in the simulator" since
      1.40.4. Counted as `sentBeforeMovementComplete`.
- [x] **M4 — `CameraCenter` is the last known position**, so it freezes if link 4
      stalls (a consequence, not a cause).

### Pending

- [ ] The device report block
      (`Movimiento (auditoria, fase 2.13a-rev5): …`) with the pad pressed and the
      world loaded: which letter comes out, and — for D — `primer desplazamiento`,
      the direction the body did or did not move.
- [ ] **M1 correction, deliberately not applied yet** so the "before" measurement
      is clean: `AgentUpdateBuilder.referenceBasis(viewYaw)` instead of
      `currentBasis()` at the one call site in `SLConnection.sendAgentUpdate`.
      Apply it *after* the counters above, in the same run that measures it.
- [ ] Keep the world screen in the foreground during the test: the previous run's
      `EPERM` is Android blocking `sendto` while the app was backgrounded, and it
      swallows *every* send while it lasts.
- [ ] Locomotion proper (walking animation, jumping, sitting, fly semantics) stays
      parked behind this; only the direction/rate correction above is in scope for
      the movement chain right now.

## Device performance reference (phase 2.13a) — **record only, not a target**

The point of writing these down is to have a *baseline* to compare against once
the texture work is in, not to tune anything now. Optimization stays parked until
the basic world functionality is finished (the user's instruction), so nothing in
this phase may trade correctness for frame time.

| Measurement | Value |
|-------------|-------|
| FPS | 36.2 (diagnosis build: shadows **OFF**, culling ON; the report's counters on) |
| CPU frame time | 27.6 ms |
| GPU frame time | 19.5 ms |
| renderables (scene / drawn / frustum-culled / distance-culled) | 734 / 573 / 161 / 1 |
| materials | 423 |
| meshes | 100 |
| buffers (VBO/IBO) | 105 / 105 |
| textures | 0 |
| shadows / culling | OFF / ON |

These are the lines the DIAG report already prints (`FPS … frame … ms cpu / … ms
gpu`, `Escalado (fase 2.12)`, `Recursos`); they are copied from the device, never
guessed. They are a *diagnostic* baseline (the parser diagnostics run on top of
the draw), not a target, and they must not be compared against anything until the
texture pipeline is in.

## Phase 3 — TextureEntry — **DONE (parser 2.13a, applied per face 2.13b)**

The parser is done and closed on the device (2.13a: 5747 blobs, 0 truncated,
5747/5747 accepted by the reference reader, terse prefix consumed 3190/3190) and
the per-face data is now *used* (2.13b), not only decoded.

- [x] Full `TextureEntry` parser: the eleven packed fields of
      `indra/llprimitive/lltextureentry.cpp` — the default colour, the
      variable-length per-face UUID list with its 7-bit face bitfield, and the
      per-face colour/scale/offset/rotation/bump/media/glow/material buckets, each
      with its own bitfield (`slproto/world/TextureEntry.kt`, closed in 2.13a).
- [x] Per-face data model: texture UUID, tint (BGRA on the wire), alpha, repeat
      U/V, offset U/V, rotation, fullbright, glow, material, bump/shiny
      (`SLTextureFace.fromFace`).
- [x] `SLScene` builds **one material per face**:
      `SLTextureFace.facesOf(entry, mesh.faceCount)` → `SLTextureCache.materialsFor`
      → an `IntArray` into `EntityDesc.materialOfFace`. Faces that coincide still
      share one material (the 20-object test scene creates exactly 10).
- [x] Repeat/offset into the material (`MaterialDesc.uvTransform`, applied by
      `FilamentMaterials`' shader).
- [x] Alpha: the face's alpha selects the blend mode
      (`AlphaMode.forAlpha`: anything not fully opaque is `BLEND`, the reference
      viewer's rule). `MASKED` is deliberately not chosen from the wire alone.
- [ ] **Rotation** (`SLTextureFace.rotation`) is decoded and reported but not yet
      applied by the shader: `uvTransform` is a float4 (repeat + offset) and
      rotation needs a 2x2. The matrix to implement is LibreMetaverse's
      `Primitive.TextureEntryFace.GetTexMatrix()`:
      `T(0.5,0.5) · R(-rotation) · S(repeatU,repeatV) · T(offsetU-0.5, offsetV-0.5)`.
      This is a *rendering* change (uniform type + shader), so it belongs with the
      face-appearance pass, not with the parser.
- [ ] Glow, bump/normal and specular maps → material parameters (after the base
      pipeline works; they also need the decoded pixels).
- [ ] `Material`/`AlphaMask` extra params (`ExtraParams` block) → `MASKED` and the
      per-prim alpha mask.

Verification: the parser checks (`texentry`, `texdecode`, `texclass`, `texdiag`,
`texref`, `texframing`) over hand-built blobs and the reference reader; the
per-face application is asserted by `texbind` (heavy) and can be seen on the
device in the per-object focus report, which prints one material id per face.

## Phase 4 — JPEG2000 textures

The **pipeline** half of this phase is done (2.13b): the download, the cache, the
queue, the priority and the GPU binding all exist and are counted. What is left
is the decoder itself and the cache policy that needs decoded pixels.

- [x] Texture transport: the `GetTexture` capability
      (`slproto/caps/GetTextureTransport.kt`) — the only HTTP in the viewer.
- [x] Off the render thread: two provider workers for the wire and two decode
      workers for the pixels; the render thread only ever queues and drains.
- [x] Texture cache keyed by (UUID, discard level), with hit/miss/failed/retry and
      never two downloads of the same UUID (`TextureAssetCache`).
- [x] **Do not** request a whole region's textures on arrival: only the visible,
      near (64 m) faces are asked for, nearest first, in a rationed scan
      (`SLScene.scanTextures`).
- [x] `SLTextureCache.textureDecoded(uuid, handle)` — every material already using
      that UUID is rebuilt when the pixels arrive.
- [ ] **OpenJPEG via NDK/CMake** to decode J2K → RGBA8 (2.13c): one
      `TextureDecoder` (`isAvailable = true`) and one line at the call site. The
      bytes already in the cache are decoded without a new download; the report
      has said how many are waiting for exactly this.
- [ ] Honour the **discard level** the asset was fetched at, and choose it by
      distance (a reduced-resolution codestream is the grid's own texture LOD).
- [ ] Cache policy on decoded pixels: LRU, memory budget, mipmaps where
      appropriate, eviction that calls `SLTextureCache.forgetTexture`.
- [ ] The UDP `RequestImage`/`ImageData`/`ImagePacket` path as a fallback for a
      region that does not offer `GetTexture`.
- [ ] Then: `RegionHandshake`'s sky/water textures, sculpt maps, and avatar baked
      textures become possible.

## Phase 5 — Mesh assets

- [ ] `GetMesh` capability + the `Mesh`/`MeshData` blob format (the official
      viewer's `indra/llmath/llvolume.cpp` `LLVolume::unpackVolumeFaces` +
      `LLMeshRepository`). Submeshes, material slots and the per-LOD block
      table.
- [ ] `SLMesh` grows: vertices, indices, normals, UVs, LODs and submeshes
      (today a `MeshDesc` is single-LOD with face groups).
- [ ] LOD selection by distance/camera, driven from the render thread but
      computed off it.
- [ ] Sculpted prims decode through this path (sculpt is a mesh-like mapping,
      not a mesh asset — implement it in `SLPrimitive` once textures work).

## Phase 6 — Terrain

- [ ] Its own `SLTerrain` in `SLWorld` (independent of the objects), built from
      the existing verified `LayerData` decoder.
      *Started for real in Phase 2.5:* `slscene/SLTerrain.kt` already turns a
      received height field into a mesh, with a fixed stride and a solid debug
      colour. What is left here is the real patch layout, LOD and textures.
- [ ] Terrain heightmap → mesh with the real patch layout (four 64 m patches per
      region, 33×33 stitched grid), region-relative placement.
- [ ] Terrain textures by height (`RegionHandshake` carries the four detail
      textures + the height ranges) once Phase 4 lands; water plane from
      `WaterHeight`.
- [ ] Parcel information (`ParcelOverlay` via the capability) when available.

## Phase 7 — World / region management

- [ ] `World → Region → Objects → Terrain → Avatars` for real: `SLWorld` already
      keys regions by handle; add streaming and the load/unload lifecycle.
- [ ] Adjacent regions, teleport (`TeleportLocationRequest` →
      `TeleportProgress`/`TeleportFinish`), region crossing (a new circuit per
      region) and the object cache (`ObjectUpdateCached` handled from a cache
      instead of re-requesting).
- [ ] Per-region object/terrain/texture caches with a budget, and unloading what
      is out of range.

## Phase 8 — Avatars (only after prims + textures + mesh + terrain work)

- [ ] `AvatarAppearance` → `VisualParam` values, `AppearanceData`
      (`AppearanceVersion`, `CofVersion`, `Flags`) — see the message layout in
      `message_template.msg`.
- [ ] The skeleton from the official `avatar_skeleton.xml`, and the avatar mesh
      from the standard avatar asset.
- [ ] Baked/wearable textures and clothing layers (`LLVOAvatar::mBakedTextureDatas`).
- [ ] Attachments (prims with a non-zero `AttachmentPoint`, parented to the
      avatar's transform — `SLObject.attachmentPoint` is already carried).

## Phase 9 — Animation

- [ ] Animation assets (`GetAsset` → the `LLAnimationObject` BVH-derived format),
      the animation mixer, blending, priorities and pose updates.
- [ ] `AvatarAnimation` (incoming) / `AgentAnimation` (outgoing) decode into
      `SLAnimationPlay`; the real stop/priority/blend semantics come from
      `LLVOAvatar`/`LLAnimationObject`, not from guesswork.

## Phase 10 — Lighting / WindLight

- [ ] `SimulatorViewerTimeMessage` → sun/moon position and direction;
      `RegionHandshake`/`EnvironmentSettings` → ambient, sky, fog, water.
- [ ] Replace the Phase-1 stand-in sun + uniform ambient in
      `FilamentRenderer.setSun/setAmbient` with the simulator's environment.

## Phase 11 — Performance

- [ ] Frustum culling (Filament already does per-renderable bounding-box tests;
      add our own for the object list), distance culling (in place), LOD.
- [ ] Object pooling, mesh cache, texture cache, GPU memory budget, async asset
      decoding, background loading, region streaming, frame-time monitoring with
      an adaptive quality/resolution fallback on low-end devices.
- [ ] Profile on a real device: target a stable frame rate at a 128 m draw
      distance with a few thousand prims.

---

## Outside the phase plan

### Social / daily-driver
- [x] IM send/receive, online + offline, event queue, chat.
- [ ] Group chat and the p2p chat capability.
- [ ] Friendship offers (`IM_FRIENDSHIP_OFFERED`) and the friend list.
- [ ] Nearby-avatar names on the mini-map (`GetDisplayNames`).
- [ ] Voice (`RECORD_AUDIO` + the capability plumbing are declared).
- [ ] Persist "stay logged in" (`session_id` + `secure_session_id`, guarded by
      the Android Keystore).
- [ ] Notifications for IMs while backgrounded.
- [ ] A settings screen that actually edits something (draw distance, quality).

### Known limitations
- Outbound UDP must not be blocked by the network; the app reports this clearly.
- `CoarseLocationUpdate` on very old simulators omits the trailing `AgentData`
  block — the codec already decodes such packets leniently.
- Until Phase 3/4, materials use a debug tint derived from the texture UUID
  (documented in `SLTextureCache`) so objects are distinguishable; that tint is
  **not** Second Life data and it disappears on its own once real colours and
  real pixels arrive.
- The release APK is signed with the debug key so it can be side-loaded; replace
  `signingConfig` in `app/build.gradle.kts` before any real distribution.


## Phase 2.13c — OpenJPEG JPEG2000 decode — **IMPLEMENTED, DEVICE CHECK PENDING**

- [x] OpenJPEG v2.5.4 via CMake FetchContent (estatico, sin codec/MJ2/JPIP/tests), enlazado a `slcore`.
- [x] `sl_j2k_decode.cpp`: JNI `nativeDecodeJ2K` (OPJ_CODEC_J2K, cp_reduce = discard 0..3, RGBA8 primera-fila-primero, tope 2048px / 32MiB, sin excepciones).
- [x] `OpenJpegTextureDecoder` tras el seam `TextureDecoder`: disponible solo si el simbolo nativo enlaza; si no, el pipeline sigue en espera como en 2.13b.
- [x] Cableado: default de `TexturePipeline` + rama no-sintetica de `FilamentWorldView.texturePipeline()` usan OpenJPEG con fallback a Unavailable.
- [ ] En dispositivo: pegar `decoder OpenJPEG J2K`, `decode OK>0`, `esperando 0`, caras con textura aplicada >0.

## Phase 2.13c-rev2 — stream en modo salida + etapas de fallo — **IMPLEMENTED, DEVICE CHECK PENDING**

Reporte 2026-09-24: decoder enlazado pero `decode OK 0 / fallo 239`, 0 ms.
Causa por inspeccion: `opj_stream_create(1024, OPJ_FALSE)` — el stream se creo
de SALIDA; toda lectura (header) falla al instante. Fix: OPJ_TRUE (entrada).

Para no volver a ciegas: el JNI devuelve codigo de etapa en cada fallo
(1 codec, 2 setup, 3 stream, 4 header, 5 decode, 6 end, 7 dims, 8 comps,
9 pixels-alloc, 10 previo-nativo, 11 contrato); `OpenJpegTextureDecoder`
agrega contadores + primera (uuid, bytes, 4 bytes magicos hex, etapa) en la
linea `j2k etapas:` del reporte; estado ya no dice "sin decoder" cuando el
decoder existe pero falla.
- [ ] En dispositivo: pegar la linea `j2k etapas:` + `decode: OK`.

## Phase 2.13c-recovery3 — GEN_MIPMAPPABLE en upload — **IMPLEMENTED, DEVICE CHECK PENDING**

Reporte recovery2: `decode OK 374 / fallo 0`, `j2k etapas: ok 374`, pero
`upload OK 0 / fallo 374`: `Precondition in generateMipmaps:697 — Texture usage
does not have GEN_MIPMAPPABLE set`. El Builder no pedia el flag (default =
UPLOADABLE|SAMPLEABLE). Fix de una linea en `FilamentRenderer.uploadTexture`:
`.usage(SAMPLEABLE or GEN_MIPMAPPABLE)` (constantes verificadas contra
Texture.java v1.75.1). Stub kcheck actualizado con `Texture.Usage`.
- [ ] En dispositivo: `upload: OK N`, `aplicadas: N mat/N caras`.

## Recovery 3 — performance/camera
- [x] Identify render-thread starvation caused by applying 60 new objects per frame.
- [x] Pace new object creation to 1 per frame.
- [x] Pause new object creation during camera touch/pinch and for 250 ms after release.
- [ ] Install new APK and confirm camera remains responsive while the region continues loading.
- [ ] Separately investigate EventQueue HTTP 500/502 session/menu disconnects.
