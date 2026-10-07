# EphoraSL 7.71

This build corrects the Second Life world-object pipeline at the wire-format boundary. The critical ConstructionData block is 23 bytes (not 31); both ObjectUpdate and ObjectUpdateCompressed now advance over the exact protocol layout before reading TextureEntry and ExtraParams.

It also fixes renderer coordinate basis for generated prim geometry, increases world/mesh/texture budgets, and avoids evicting active terrain textures.

The goal is a recognizable Second Life scene: actual prim topology (boxes, cylinders, tubes, spheres, cut/hollow/taper/twist forms), mesh objects, and then their textures, rather than proxy boxes.


## 7.72 – World loader / terrain / texture upload

Cambios principales:
- El `ObjectUpdate` completo usa el bloque de construcción correcto de 23 bytes antes de `TextureEntry`.
- `ObjectUpdateCompressed` conserva el orden oficial de campos opcionales y decodifica `ExtraParams`/mesh.
- Los objetos listos (forma o mesh) tienen prioridad sobre registros que todavía no tienen geometría.
- La escena no inventa cubos para objetos sin geometría válida.
- Las texturas reensambladas no se consideran listas hasta que OpenJPEG produce un `Bitmap` utilizable.
- La caché de bitmaps se limpia al iniciar una nueva sesión/región.
- Las texturas GLES limpian errores GL antiguos antes del upload y manejan NPOT con `CLAMP_TO_EDGE` sin mipmap.
- El terreno expone en diagnóstico sus cuatro UUID y cuántas texturas tienen `Bitmap` disponible.
- Presupuesto procedural aumentado para permitir más prims cercanos en la escena.

Versión Android: 7.72 / versionCode 140.
