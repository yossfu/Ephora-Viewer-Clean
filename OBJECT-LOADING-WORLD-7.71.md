# EphoraSL 7.71 — real ObjectUpdate world reconstruction

## Critical protocol correction

Second Life's ObjectUpdate ConstructionData is 23 bytes: PathCurve(1), ProfileCurve(1), PathBegin(2), PathEnd(2), PathScaleX(1), PathScaleY(1), PathShearX(1), PathShearY(1), PathTwist(1), PathTwistBegin(1), PathRadiusOffset(1), PathTaperX(1), PathTaperY(1), PathRevolutions(1), PathSkew(1), ProfileBegin(2), ProfileEnd(2), ProfileHollow(2). The upstream message template confirms this field order; the next field is TextureEntry.

## Full ObjectUpdate

The parser now consumes ObjectData movement, then ParentID + UpdateFlags (8 bytes), then exactly 23 bytes of ConstructionData, followed by TextureEntry(V2), TextureAnim(V1), NameValue(V2), Data(V2), Text(V1), TextColor, MediaURL(V1), PSBlock(V1), ExtraParams(V1), sound and joint data.

## ObjectUpdateCompressed

The parser now follows the OpenMetaverse-compatible compressed order: UUID, LocalID, PCode, State, CRC, Material, ClickAction, Scale, Position, Rotation, CompressedFlags, OwnerID, optional angular velocity/parent/tree/scratch/text/media/particles, ExtraParams, optional sound/name-values, then the 23-byte ConstructionData and the U32-length TextureEntry.

## Rendering

Generated prim geometry is converted from SL Z-up `(X,Y,Z)` to the renderer basis `(X,Z,-Y)`, matching the mesh decoder. Mesh and generated shapes therefore share the same orientation and scale convention.

## Texture/asset budgets

World publication is raised to 1800 records; mesh visibility to 256 IDs with a 192-entry cache; image requests to 768 IDs and decoded bitmaps to 256. Active terrain textures are protected from renderer texture eviction.

## Diagnostics expected after compile

`app=7.71`
`ADV-SCENE forma` should move from hundreds toward the majority of prims. `ADV-FRAME forma` should be far above 20. `SHAPES built` should climb. `MESH-ASSETS req/decoded` should stay non-zero on mesh-heavy regions. `TERRAIN-TEX` should report the four terrain detail IDs and `terrainGpu` should become non-zero once those bitmaps are available to the GL thread.
