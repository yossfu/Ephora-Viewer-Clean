# EPHORA — Operating rules for every AI coding session

You are maintaining a real Android Second Life viewer. The project is incremental and fragile. Treat each ZIP as a reproducible checkpoint, not as permission to redesign unrelated subsystems.

## 1. First action on every session

Read these files before editing code:
1. `EPHORA-CURRENT-CONTEXT.md`
2. the latest `EPHORA-*RECOVERY*.md`
3. `TODO.md`
4. `MOVEMENT-AUDIT.md`
5. the latest supplied device log, when present

Then inspect the current source tree. Never reconstruct architecture from memory.

## 2. Separate evidence from hypotheses

For every bug write down:
- symptom observed by the user;
- exact log evidence;
- exact source location;
- first point where the invariant breaks;
- what is proven;
- what is only a hypothesis;
- what remains untested.

Never promote a hypothesis to a fix merely because it sounds plausible.

## 3. Use a single-fault work rule

Work on one failure domain at a time. Typical domains are:
- protocol/parser;
- asset transport;
- JPEG-2000 decode;
- scene/object lifecycle;
- parent/child transforms;
- Filament resource lifetime;
- camera/input;
- movement/network commands;
- session/EventQueue;
- performance/pacing.

When one domain is being fixed, freeze the others unless evidence shows that the current patch directly affects them.

## 4. Before editing

Locate the smallest responsible path with code and logs. Prefer a minimal correction over a rewrite.

Always ask:
- What invariant must remain true?
- What thread owns this state?
- Can this operation block the render thread?
- Does this operation perform JNI/native/GPU-driver work?
- Does this change affect every object or only the failing case?

## 5. Render-thread rule

The render thread is latency-sensitive. Never introduce an unbounded loop of expensive native operations into one frame.

Any operation that can create/destroy a Filament entity, renderable, material instance, texture, mesh, or other native resource must have explicit pacing. If the measured cost is unknown, start conservatively and instrument it.

Camera/input presentation always has priority over bulk world population.

## 6. Threading rule

The UI thread receives input. The render thread owns Filament. Do not move Filament operations to the UI thread.

Cross-thread state must be explicit (`@Volatile`, queue, immutable snapshot, or another deliberate mechanism). Do not make thread ownership ambiguous.

## 7. Diagnostics rule

Diagnostics must answer questions, not merely print large dumps.

For performance bugs report at minimum:
- frame count;
- presented frame count;
- beginFrame failures;
- frame sync time;
- application time;
- render time;
- post time;
- number of new objects applied;
- average/max cost of new object creation when available;
- pending additions;
- input thread and render thread names.

For texture bugs report:
- requested;
- received with bytes;
- decoder successes/failures;
- upload successes/failures;
- applied materials/faces;
- first decoder failure stage.

For session bugs report:
- EventQueue status;
- HTTP status/error body;
- consecutive failure count;
- reconnect state;
- logout/menu transition;
- lifecycle callbacks;
- exceptions.

## 8. Version discipline

Every delivered ZIP gets a unique recovery label and a short changelog.

Never silently overwrite the only known-good checkpoint.

Use:
`EPHORA-<version>-RECOVERY<N>-<purpose>.zip`

The project itself must contain a matching markdown note describing exactly what changed and what did not.

## 9. Compile gate before delivery

Before claiming a version is ready:
1. run every local compile/check available;
2. run C++/JNI syntax checks when native code changed;
3. verify Gradle configuration and GitHub Actions workflow;
4. search for merge-conflict markers;
5. inspect the diff for accidental unrelated changes;
6. verify required files and scripts are present;
7. rebuild the ZIP from the exact tested directory;
8. list the ZIP contents.

If the local environment cannot run the Android build, say exactly that. Do not call the APK build verified.

## 10. Device validation gate

Never claim an Android/Second Life behavior is fixed until a new APK has been installed and the relevant scenario has been reproduced.

The AI may say:
- `code fix prepared`;
- `local syntax check passed`;
- `CI build required`;
- `device test pending`.

Do not collapse those into `fixed`.

## 11. Required delivery format

Every version delivery must include:
- ZIP filename;
- exact files changed;
- why each file changed;
- invariant(s) preserved;
- local checks executed and results;
- device tests still required;
- next acceptance test;
- a copy-paste prompt for the next AI session.

## 12. Never do these things

Do not:
- reset working source to an older revision without evidence;
- rewrite working protocol code because a visual symptom appears;
- disable correctness features permanently just to obtain FPS;
- change multiple subsystems in one speculative patch;
- claim an external/server behavior is fixed without server/device evidence;
- remove diagnostics that were added to prove a prior bug;
- delete a known-good backup before the new version passes its acceptance test.

## 13. Standard workflow

`READ → REPRODUCE → LOCATE → MEASURE → PATCH MINIMALLY → STATIC CHECK → PACKAGE → DEVICE TEST → COMPARE LOGS → DOCUMENT → NEXT PATCH`

At each step, stop broadening scope unless the evidence forces it.

## 14. When a previous AI conversation is lost

Treat the repository and its recovery notes as the source of truth.
Do not invent missing history.
Reconstruct intent from:
- current source;
- recovery notes;
- TODO;
- movement audit;
- device logs;
- scripts/workflows.

Preserve the project lineage by writing down every new discovery in the repository before changing to another subsystem.
