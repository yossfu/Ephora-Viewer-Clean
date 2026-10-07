# EphoraSL 7.70 — ObjectUpdate framing fix

Root cause confirmed from 7.68 runtime diagnostics: `ObjectUpdate` full packets were reaching the parser, shape bytes were partially visible, but the parser read `NameValue` as Variable 1. The Second Life message template defines `NameValue` as Variable 2. This one-byte/ two-byte framing mismatch shifted every subsequent field, so TextureEntry, ExtraParams, sound/joint data and the successful-object counter were never reached.

## Correct ObjectUpdate sequence

RegionData -> ObjectData count -> fixed object header -> ObjectData (movement) -> ParentID/UpdateFlags -> 31-byte prim construction -> TextureEntry Variable2 -> NameValue Variable2 -> Data Variable2 -> Text Variable1 -> TextColor 4 -> MediaURL Variable1 -> PSBlock Variable1 -> ExtraParams Variable1 -> 66-byte sound/joint tail.

The mesh path already has a valid GetMesh2 capability and a decoder for the standard gzip LLSD mesh LOD format. Once the full ObjectUpdate framing is corrected, real mesh UUIDs can flow into MeshAssets again.

Version: 7.70 / versionCode 138.
