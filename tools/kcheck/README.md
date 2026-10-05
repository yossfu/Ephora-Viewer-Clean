# The check harness (`tools/kcheck`)

A headless verification of the app's Kotlin, with no Android device, no Gradle
and no Android Studio. It compiles the real sources under `app/src/main/java`
with a real **Kotlin 2.0.21** compiler against hand-written Android / androidx /
Filament stubs, and then **runs** the project's checks with the graphics backend
replaced by a recording fake.

It is the same harness that was built inside the editor's scratch space; it lives
here so it travels with the project and a later session can re-run it.

## Run it

`run_all.js` is the entry point. It expects a `fs` and a `fetch` that can reach
`https://api.kotlinlang.org/`, plus a workspace filesystem:

```js
const src = await fs.readTextFile("tools/kcheck/run_all.js");
const module = { exports: {} };
new Function("module", "exports", src)(module, module.exports);
const result = await module.exports({ fs, fetch, kcheck: "tools/kcheck" });
// result.allOk, result.expectedChecks (53), result.results[]
```

`result.allOk` is the verdict. Each entry of `result.results` names the group, its
HTTP status, its error count, how many submissions it took (`attempts`) and the
last line of its output. A group that fails on a 500 says `status: 500`; a group
that fails on the code says `mineErrors: n`. **A 500 is never a verdict about the
code** — see below.

The two big groups are retried in **rounds**, alternating: heavy, then light, then
heavy again, each round up to `retriesPerRound` submissions (default 6, 8 s
apart), until both have been through or `budgetMillis` (default 45 min) runs out.
The heavy group still goes **first**, after a 15 s quiet gap, because the backend
500s when two large submissions land close together. `result.attempts` records
every round that was tried, so a partial run can be read honestly: what passed,
what did not, and how patient the run was.

## The backend's payload limit, measured

The Kotlin Playground backend refuses large submissions with an HTTP 500 after
~50 s (never a compiler error, never an exception — just
`{"message":"Internal Server Error"}`). Measured on 2026-09: **~450 kB is the
limit, and it moves with whatever else the backend is doing.** The same payload
that returns 200 in one minute returns 500 four times in a row in the next.

| submission | files | code bytes | measured |
|---|---|---|---|
| `tests_texasset.kt` alone (light packages, no `slscene`) | 35 | 389 kB | 200 |
| `tests_moveaudit.kt` alone (movement + messages + world) | 35 | 403 kB | 200 |
| `tests_capsfail.kt` alone (caps + asset packages, no scene) | 34 | 185 kB | 200 |
| `tests_light.kt` (24 checks, no `slscene`) | 35 | 504 kB | 200 in some windows, 500 in others |
| `tests_heavy.kt` (27 checks, `slscene` + `slworld`) | 54 | 659 kB | needs many tries; 200 happened |

Consequences, and what to do about them:

* **Never conclude "the code is broken" from a 500.** If the status is 500 there
  is no output at all — no `render pipeline:` line, no error list.
* Cutting the *check* code does not rescue a heavy group: the payload is dominated
  by the project sources, so a group that runs `texbind` alone still measures
  **605 kB** (measured with `dryRun`) versus the full heavy group's 659 kB. The
  thing under the limit is the *package set*, which is why only the light-package
  groups (`texasset`, `capsfail`) can be squeezed small.
* Run `run_all.js` and read `result.attempts`. If the big groups failed after
  many rounds, the run simply hit a bad window: try again later. Both big groups
  have passed (heavy **27/27 OK**, light **24/24 OK**, `mineErrors: 0` each) — so
  the suite is green, just not always reachable in one session. The `light` group
  also revealed a **flaky assertion** in `texturePipelineCheck` in 2.13b-rev2 (its
  duplicate-request check raced the worker); it now holds the transport blocked
  across the two calls, so the duplicate is deterministic. See the `texasset`
  bullet below.
* To get a check through a bad window, run it as its own small group with
  `make_group.js` + `run_lean.js`. `run_all.js` generates three of these itself —
  `extra/tests_texasset.kt`, the texture pipeline's check alone at 389 kB,
  `extra/tests_moveaudit.kt`, the movement audit's check alone at 403 kB, and
  `extra/tests_capsfail.kt`, the capability-failure diagnosis (2.13b-rev1) at
  185 kB — so the command below is enough to re-run the newest code while the big
  groups are
  being refused. `run_lean.js` also takes `dryRun: true`, which reports the
  submission's file count and bytes without sending it; use it before adding
  files to a group.

```
const lean = { exports: {} };
new Function("module", "exports", await fs.readTextFile("<repo>/tools/kcheck/run_lean.js"))(lean, lean.exports);
await lean.exports({ fs, fetch, appBase: "<repo>/app/src/main/", harness: "<repo>/tools/kcheck",
  extras: ["tests_texasset.kt"],
  packages: ["com.lumiyaviewer.lumiya.renderer", "com.lumiyaviewer.lumiya.slproto.asset",
             "com.lumiyaviewer.lumiya.slproto.base", "com.lumiyaviewer.lumiya.slproto.messages",
             "com.lumiyaviewer.lumiya.slproto.movement", "com.lumiyaviewer.lumiya.slproto.world"] });
```
* What *always* passes regardless of the window: the two compile-only passes
  (`ui/world/FilamentWorldView.kt`, and `slproto.modules` with nothing skipped,
  53 files, ~400 kB) and the three one-check groups (`texasset` 389 kB,
  `moveaudit` 403 kB, `capsfail` 185 kB). So a run that shows only a 500 and those
  green is normal, not a regression.

## Why it is split into groups

The Kotlin Playground backend returns **500 on the execute path** once the
submitted code gets large (the heavy submission measures **659 kB** across 54
project files plus the stubs — see the table above). Two things keep the payloads
as small as they can be:

* **whole-line comments are dropped** before submitting (only lines that are
  entirely `//`, `/*`, `*` or `*/`, with `"""` raw-string state tracked, so a
  string literal is never touched) — that buys ~200 kB;
* the checks are **grouped**, one group per set of package needs:
  `tests_light.kt` (24 checks, needs neither `slscene` nor `slworld`),
  `tests_heavy.kt` (27 checks, needs both) and `tests_capsfail.kt` (1 check, needs
  `slproto.caps` but no scene — 185 kB, so it fits in windows the big groups do
  not).

## The canonical file, and the groups

`tests_render.kt` is the **single source of truth**: 53 checks, one
`renderPipelineChecks()` entry point. It **cannot compile as a whole** — one of its
checks, `bootstrapCheck`, calls `renderer.filament.FilamentBootstrap`, and that
package needs the real Filament AAR. It is meant to be sliced, not run.

`make_group.js` does the slicing:

```js
await build({ fs, out: "tools/kcheck/tests_light.kt", checks: [["texref", "textureEntryReferenceCheck"], ...] });
```

It takes the requested checks, adds every top-level declaration they mention,
transitively, and writes a file with those declarations plus a
`renderPipelineChecks()` that registers the requested checks. `run_all.js` does
this for the groups itself, so **adding a check to `tests_render.kt` and
listing it in `run_all.js` is the whole procedure** — and `run_all.js` asserts
that 24 + 27 + 1 (`capsfail`) + the one unrunnable check add up to 53, so a check
cannot be left out silently.

## The one file the groups cannot reach

`ui/world/FilamentWorldView.kt` is the actual render loop. The `ui` tree needs
real Android and real Filament to compile, so the check compiles **only that
file**, with the packages it does not need skipped (`skipPackages`) — the errors
the skipped packages leave behind are inside *their* own files (e.g.
`SLConnection.kt` when `slproto.circuit` is skipped), never in the file under
test. `run_all.js` filters the error list to `FilamentWorldView` for that reason.

Because that skip also hides the session's own errors, `run_all.js` runs a
**fourth, compile-only pass** over `slproto.modules` and every package it needs
(the circuit, the capabilities, the login, the inventory, the chat, the grids) with
**nothing skipped**, so its error count is meaningful. It runs no checks; a clean
compile is the whole verdict. That pass is what compile-checks the movement wiring
(`SLConnection`) and it needs no extra files.

## What the checks cover

The canonical file's list, in registration order. `run_all.js` is what decides
which group each one lands in.

```
shapes pcodes geomval transform color primparams testscene world camera framing
scene reject objectlog cache avatars probe terrain diagnostics bootstrap bytes
compressed placeonly incremental shapesource census samesource budget frustum
matrixfrustum camtrace camprobe primlock parentparser parentchain parentresolve
focusreport scalability childfrustum benchstate texentry texdecode texclass
texdiag texcache texref texframing terseprefix terseconsume renderloop moveaudit
texasset (light) texbind (heavy) capsfail (caps)
```

`bootstrap` is the one that cannot run (it needs the Filament AAR) and is
compile-checked by Gradle in CI instead.

## Notes

* The stubs are deliberately minimal and only need to satisfy the type checker;
  they are never executed by the app.
* `Timer`/threading is not stubbed: the checks call the code directly.
* A check that needs a `FilamentRenderer` uses `FakeRenderer`, which records what
  it was asked to do instead of drawing.
* `android_os.kt` and `android_view.kt` carry `Looper` and `Choreographer` stubs,
  because the render loop is now paced by a `Choreographer` on the render thread's
  own `Looper` (see `RenderThread.runPacedByVsync()`). `Looper.loop()` is a static
  member there, as on Android.
* `ref/terse_prefix_evidence.md` is not a build input: it is the upstream evidence
  (official template, OpenSim's sender, LibreMetaverse's reader) for the four
  extra bytes in the terse `TextureEntry`, kept in the repo so the conclusion can
  be re-checked without re-fetching anything. `extra/probe_prefix.kt` is a
  standalone probe for the same question.
* `extra/probe_socket.kt` is a one-off probe that records *why* there is no
  end-to-end HTTP test for `GetTexture` in this harness: the playground sandbox
  lets the code **bind** a `ServerSocket` on `127.0.0.1` but **denies connect**
  (`AccessControlException … "java.net.SocketPermission" "127.0.0.1:port"
  "connect,resolve"`), so the loopback client cannot reach its own server. The
  transport is therefore tested against a fake transport (2.13b) and the real
  request/response facts are produced and sanitized by `capsfail`.
* `extra/probe_move_report.kt` is the same kind of probe for the movement audit: it
  drives `MovementAudit` through a tap whose `AT_POS` packet is serialized after the
  release and prints `reportText()`, so the block the device copies can be rendered
  and read without a phone. Run it with `entry: false` and the `P_MOVE` packages
  (see the `moveaudit` group).
* `texasset` (light) is the **texture pipeline** without a scene: the real
  `GetTextureAssetProvider` over a fake `TextureTransport` (de-duplication,
  cancel mid-flight, bounded waiting for the capability, the request cap), and
  `TexturePipeline` with and without a decoder — including the 2.13c hand-off,
  where linking a decoder afterwards decodes the bytes already downloaded and
  **no** second request is made. The de-duplication case holds the fake transport
  **blocked across the two `request()` calls** on purpose: the duplicate is only a
  duplicate while the first fetch is in flight, and a fast worker (especially one
  warmed by the other 23 checks of the light group) would otherwise finish first
  and turn the second call into a fresh request — the assertion was flaky before
  2.13b-rev2 for exactly that reason. `texbind` (heavy) is the same pipeline with
  a real `SLScene`: 20 faces asking for 10 textures, 10 GPU uploads, 10 rebuilt
  materials, and every counter of the report block.
* `capsfail` (its own 185 kB group) is the **failure diagnosis** of 2.13b-rev1: it
  feeds fabricated responses through the real classifier and asserts that a
  request with no capability, a transport exception, an HTTP error from a proxy
  (with `Via`/`Server` facts), a valid 0-byte response and an unparsable response
  each land in their own kind — and that the sanitizer strips a `token=`, a
  `session_id=`, a `Cookie:` and a full URL before anything reaches the report. It
  also asserts the A–F reading (`failureReading`) partitions those failures
  without losing one. In 2.13b-rev2 it additionally asserts the **shape of the
  texture request**, which is what the device's 403 turned on: a base URL with no
  path (or just `/`) becomes a `GET <base>/?texture_id=<uuid>` with no body, a base
  URL that *does* carry a path stays a `POST` with the LLSD `{texture_id,
  discard_level}` body, a base that already has a query gets `&texture_id=`, and a
  403 recorded on the first form is labelled `GET` and is **not** counted as a
  proxy failure (the `EventQueueGet` 502 stays in its own `D` row, so the two
  categories are asserted to be separate).
* `moveaudit` belongs to the **movement investigation**, not to the texture
  pipeline: see `MOVEMENT-AUDIT.md` at the repository root. It asserts the
  `AgentUpdate` the viewer builds (bytes, field offsets, flags, frequency) and the
  audit's A–E classifier, which is what lets a single device run say where the
  movement chain cuts. It also reads the camera basis back out of the built packet
  and asserts it is the reference frame for the *view* yaw the packet was built
  from — with its own ~400 kB one-check group (`extra/tests_moveaudit.kt`), because
  the light group it belongs to is often refused. In 2.13b-rev2 it covers the
  **FORWARD window** as well: a three-packet press (two flagged, one not) has to
  come back as `U`/`F`/`X`, the first and last `AT_POS` packets and the positions
  they carried have to be the ones the series held, the per-change deltas/path/net
  have to be the ones laid out, and `lastFlagsSent == 0` must not erase
  `forwardWindowFlagged > 0` — the exact fact that made the first device run's
  `0x00000000` inconclusive. Revision 2.13b-rev3 adds the **temporary trace** it
  checks as well: the cumulative totals over every window, the per-window log, the
  non-zero timestamps, the `P`/`E`/`M` entries of the interleaved line of time, the
  internal-state samples, and — the case that revision exists for — a tap whose
  `AT_POS` packet is serialized *after* the release, which must land in
  `forwardLateFlagged` and in a window counted as empty.
