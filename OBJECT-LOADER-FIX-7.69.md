EphoraSL 7.69 — world object pipeline correction

- Restores the proven live ObjectUpdate movement formats 60/76/124/140 and keeps 16/32/48 support.
- Parses ObjectUpdate in a best-effort manner: once the core object + shape are decoded, malformed optional trailing fields do not invalidate the object.
- Marks RequestMultipleObjects responses answered as soon as the core full object is received.
- Restores TextureEntry and ExtraParams extraction after the corrected full-object layout.
- Mesh references therefore reach GetMesh2 again.
- Renderer no longer displays fake beige cubes for undecoded world objects. It renders terrain/avatar normally and world objects only after valid prim/mesh geometry exists.
- Prim-shape build budget increased modestly to 120 per frame.
