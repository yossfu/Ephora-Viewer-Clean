# EPHORA — Recovery 2.13c-recovery2

This point adds one narrowly targeted native JPEG-2000 fix on top of the previous recovery point, based on a real-device diagnostic.

## Real-device evidence that triggered this change
- 574 texture requests.
- 572 responses, 567 with bytes.
- 85.8 MiB cached codestreams.
- 567 decode attempts, 0 successes.
- OpenJPEG diagnostic: all failures at stage 8 (`comps`).
- First failing codestream began with `FF 4F FF 51`, the raw J2K codestream signature shown by the report.
- 3 HTTP 403 texture responses existed, but 567 valid byte responses reached the decoder, so transport is not the blocking stage.

## Root cause addressed
The previous native decoder required every OpenJPEG component to have exactly the same `w/h` as the reference image and rejected the image otherwise. JPEG-2000 permits component subsampling through `dx/dy` and component origins `x0/y0`. The new decoder accepts valid component geometry and maps reference-grid pixels to component samples. It also handles OpenJPEG SYCC and CMYK color spaces defensively.

## Protected invariants
- TextureEntry parser unchanged.
- ImprovedTerseObjectUpdate 4-byte TextureEntry prefix handling unchanged.
- ObjectUpdate/ObjectUpdateCompressed/ExtraParams/shape classification unchanged.
- Parent/Child unchanged.
- Choreographer/VSYNC render loop unchanged.
- movement/camera unchanged.
- Filament/geometry/terrain unchanged.
- Real GetTexture transport unchanged.

## What remains unproven
- The fix must be compiled into an Android APK and tested on the user's Xiaomi in real Second Life.
- This local environment cannot reproduce the Android JNI/OpenJPEG runtime or the real SL transport.
- The separate session/menu disconnect symptom was not reproduced by this diagnostic capture; EventQueue was alive with 108 events, so that issue remains a separate investigation.

## Expected next evidence
For textures, success means `decode OK > 0`, followed by `upload OK > 0` and `aplicadas > 0`.
For session stability, we need the connection-state/event-queue timeline when the app actually leaves the world.
