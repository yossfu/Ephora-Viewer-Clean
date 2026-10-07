# EphoraSL 7.67 — Object Loading Fix

## Problema corregido

El decodificador de `ObjectUpdate` solo aceptaba `ObjectData` de 60, 76, 124 y 140 bytes. El protocolo de Second Life también entrega variantes de 16, 32 y 48 bytes para actualizaciones de menor precisión. Cuando llegaban esas formas, el objeto se contabilizaba pero no se creaba correctamente en la escena.

## Cambios

- `PrimDecoder.kt`
  - Añadido decode de `ObjectData` 16 bytes (`U8Vec3 + U8Rot`).
  - Añadido decode de 32 bytes (`U16Vec3 + U16Rot`).
  - Añadido decode de 48 bytes (collision plane + payload de 32 bytes).
  - Conservadas las rutas 60/76/124/140.
  - Ajustado el rango vertical cuantizado para no depender de la altura local del terreno.
  - Ampliado el límite de Z aceptado para permitir objetos altos.
  - El filtro de attachments/HUD ya no descarta prims legítimos de región cerca de `(0,0)` cuando el pcode ya es conocido.
  - `sweepTexless()` ahora solicita de nuevo cualquier objeto que no tenga forma completa o textura real.

- `MainActivity.kt`
  - En TP/reentrada se reinicia `MeshAssets` para no reutilizar el worker/cache de la región anterior.
  - Tras obtener nuevas capabilities se vuelve a iniciar `GetMesh2`.
  - Las pruebas manuales de capabilities/UDP también reactivan `MeshAssets` cuando corresponde.

- `app/build.gradle.kts`
  - `versionCode = 135`
  - `versionName = "7.67"`

## Validación realizada

Se compiló `PrimDecoder.kt` de forma aislada con Kotlin/JVM y se ejecutó un harness que construye paquetes `ObjectUpdate` sintéticos para 16, 32 y 48 bytes. Los tres casos crean el objeto, conservan posición/forma y el objeto incompleto entra en la cola de re-solicitud.

También se validó un `ExtraParams` de mesh con UUID de 16 bytes y tipo de mesh: el UUID se decodifica y pasa a `MeshAssets.request()`.

Nota: en este entorno no hay Android SDK/Gradle wrapper disponible para producir una APK Android completa; el entregable es el proyecto fuente corregido listo para compilación con Android Studio/Gradle.
