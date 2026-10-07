# EphoraSL 7.68 — Object/Prim/Mesh loading repair

Fixes are centered on the real Second Life ObjectUpdate wire layout.

- ObjectData lengths 16/32/48/60/76 are decoded; 76 now uses the correct rotation offset.
- U8/U16 rotations use all transmitted quaternion components.
- ParentID and UpdateFlags are consumed before the 31-byte prim construction block.
- TextureEntry and all following ObjectUpdate fields are parsed from the correct offset.
- Full ObjectUpdate blocks are no longer capped at 64 entries.
- Shape/texture/mesh metadata is retained if it arrives before its transform record.
- Attachments/HUD coordinates are not destructively discarded from the object cache.
- Slightly out-of-region coordinates are accepted; only absurd values are rejected.
- Compressed ObjectUpdate now extracts mesh ExtraParams early.
- The render publication excludes unresolved unknown-origin attachment updates from the 1024 world-object budget.
