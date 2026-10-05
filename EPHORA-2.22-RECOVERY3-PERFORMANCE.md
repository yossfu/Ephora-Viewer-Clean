# EPHORA 2.22 — Recovery 3: camera-responsive world loading

## Evidence from the actual Android run

The 2.22 log shows:
- 24.7 FPS reported at the sampling point, with a 40.5 ms CPU frame and 37.7 ms GPU frame.
- 713 scene entities, 814 entities created, and 715 renderables on the last sampled frame.
- The renderer itself reports `render` around the tens of milliseconds, while `drawFrame` is dominated by synchronization/application work.
- Most importantly, `beginFrame` is only the final presentation step; a blocked render thread prevents new camera state from being submitted.
- The log also shows `culling OFF` and `shadows ON` for that run, but this is not the primary cause of the multi-second stalls.

The source code in 2.22 was applying up to **60 new objects per frame** from `pendingAdded`.
The run's own diagnostics measured approximately **135 ms per new object** in the scene-application path. That makes a worst-case frame roughly:

`60 × 135 ms = 8100 ms`

which matches the observed multi-second frame starvation and the report of many frames taking several seconds.

## Root cause

The UI thread still receives touch input, and the render thread is a separate thread. However, `drawFrame()` performed expensive `SLScene.apply()` work before `camera.desc()` and `FilamentRenderer.setCamera()`. While those object insertions were running, the render thread could not submit a frame using the newer camera state.

The observed symptom "the camera no longer moves" is therefore a scheduling/starvation symptom, not evidence that `SLCamera.orbit()` or the touch path was broken.

## Correction in Recovery 3

1. `applyNewPerFrame` is now `1` instead of `60`.
2. A volatile `cameraTouchActive` flag is set by touch/pinch events.
3. While camera interaction is active, new object insertion is paused; updates/removals continue.
4. After the gesture ends, a 250 ms grace period lets the camera settle before a new object is created.
5. Remaining additions stay queued in `pendingAdded` and are resumed gradually.

This deliberately does **not** change:
- ObjectUpdate parsing;
- ObjectUpdateCompressed parsing;
- TextureEntry;
- OpenJPEG decoder;
- Parent/Child representation;
- camera math;
- AgentUpdate movement serialization;
- Filament camera matrices;
- texture transport;
- EventQueue/session handling.

## Texture regression guard

The earlier JPEG-2000 fix is preserved. The next run must continue to show non-zero decode/upload/apply counters.

## Session warning

The supplied 2.22 log also records EventQueue HTTP 500/502 proxy responses. That is a separate session-stability issue and is intentionally not mixed into this performance patch.

## Acceptance criteria for the next APK

During a camera drag/pinch:
- new object count applied per frame should be 0 while touching;
- camera should continue updating from UI input;
- frame stalls should no longer be multi-second solely because of object insertion.

When idle:
- at most one new object is applied per frame;
- `pendingAdded` decreases gradually;
- decode/upload/apply texture counters continue increasing when assets arrive.

Do not infer performance success from one FPS number alone. Compare frame-sync/application timing and the absence of multi-second `drawFrame` stalls.
