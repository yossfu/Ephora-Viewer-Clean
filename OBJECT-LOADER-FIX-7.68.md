# EphoraSL 7.69 — Object Loader Fix

## Objetivo
Corregir la carga de objetos del mundo (prims, objetos procedurales y mesh) cuando llegan por `ObjectUpdate`, especialmente los bloques `ObjectData` de precisión 8/16/32 bits y las actualizaciones donde la forma/textura llegan en el mismo bloque después de la posición.

## Cambios principales
- `ObjectData` soporta 16, 32, 48, 60 y 76 bytes con los offsets y rangos de Second Life.
- Se corrige el avance del cursor después de `ObjectData`: `ParentID` + `UpdateFlags` preceden al bloque de construcción de 31 bytes.
- La forma se conserva como dato pendiente si el movimiento crea el registro antes de que llegue la forma.
- La `TextureEntry` se aplica al registro o queda pendiente cuando el movimiento todavía no existe.
- `ExtraParams` detecta sculpt/mesh y puede enlazar el UUID aunque el registro todavía no exista.
- Los objetos desconocidos con coordenadas viewer-relative cercanas al origen dejan de ocupar el presupuesto de render del mundo.
- Se mantiene la re-solicitud de objetos sin forma/mesh, pero evita insistir en registros que ya tienen forma o mesh.
- `reset()` limpia los estados pendientes de objetos, formas, texturas y mesh.
- Versión `7.69`, `versionCode 136`.

## Validación local
`PrimDecoder.kt` se compila con `kotlinc` y se ejecuta un arnés sintético que construye y decodifica bloques `ObjectUpdate` de 16/32/48/60/76 bytes, verificando posición, forma y UUID de textura. Los cinco formatos pasan. `PrimShapes.kt` también compila.

La compilación Android completa debe ejecutarse en el entorno de Android/Gradle del proyecto.
