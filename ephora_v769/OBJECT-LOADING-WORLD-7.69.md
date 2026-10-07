# EphoraSL 7.69 — World object loading correction

This build fixes the world/object pipeline rather than using visual cube placeholders.

## Expected behavior
- Terrain remains visible while object assets load.
- Primitive objects are rendered only after their real primitive construction data is decoded.
- Mesh objects are rendered only after the corresponding GetMesh2 asset/LOD is available.
- TextureEntry data is retained per face; texture requests are driven by the decoded UUIDs.
- Objects that are still incomplete are temporarily omitted instead of being fabricated as cubes.
- KillObject removes pending shape/texture/mesh state so a recycled LocalID cannot inherit stale data.

## Root cause found in 7.68
The full ObjectUpdate parser had diverged from the previously working 7.66 live parser and rejected/failed to complete common full-object inner-data lengths used by this viewer. The parser also stopped before TextureEntry/ExtraParams in many packets, which produced the observed `MESH-PRIM extra=0`, `TEX-ESTADO con=0`, `mesh=0`, and thousands of `pelados` records.

## 7.69 changes
- Restored the proven full-object parsing path for 60/76/124/140-byte inner movement blocks.
- Added official 16/32/48-byte movement handling without replacing the proven full-object tail parser.
- TextureEntry offset/rotation conversion follows the OpenMetaverse wire mapping.
- Mesh ExtraParams extraction is retained and requests GetMesh2 immediately.
- Pending shape/texture/mesh state is applied when a LocalID record is created.
- Renderer no longer draws generic cube placeholders for unresolved world objects.
- Renderer does not fall back from a mesh object to primitive proxy geometry while the mesh asset is pending.
- Primitive shape cache increased to 128 entries and per-frame shape build budget to 180.
- Version bumped to 7.69 / versionCode 137.

## Validation
`PrimDecoder.kt` was syntax-compiled with Kotlin/JVM plus minimal stubs. A synthetic ObjectUpdate full packet was validated through `ingest(12, ...)` and `publish(...)` so that a single object produced valid shape data, TextureEntry data and a mesh UUID.
