# EPHORA 2.22-r4 — Recovery 4: método Lumiya de rendimiento (pacing de texturas)

Fecha: 2026-09-24. Dominio único: rendimiento/pacing. No se toca protocolo,
J2K, Parent/Child, cámara, movement, EventQueue ni geometría.

## A. Problema encontrado

Recovery 3 racionó los objetos nuevos (1/frame, 0 en gesto), pero dejó dos
caminos ilimitados dentro del mismo `drawFrame`, ambos en el hilo
`EphoraRender`:

1. `TexturePipeline.pump()` drenaba TODO lo decodificado en cada frame: cada
   evento es un `createTexture` (allocateDirect + setImage + mipmaps) más un
   `rebind` en el hilo de render, sin tope.
2. `SLTextureCache.rebind()` barría TODOS los materiales por cada textura que
   llegaba (`entries.values` con `updateMaterial` JNI por coincidencia): coste
   O(texturas × materiales) que crece justo cuando la región se puebla
   (944 mat en el log 2.22).

## B. Por qué se sabe que ese es el problema

- Cadena: síntoma (frames de 6-8 s, `sync` 6792 ms vs `render` 3.4 ms en
  DIAG-EVIDENCE-2.22) → invariante roto ("el render thread nunca hace trabajo
  ilimitado por frame") → ubicación exacta: `TextureStreamer.pump()`
  (`pipeline.pump()` sin argumentos) + `SLTextureCache.rebind()` (bucle sobre
  `entries.values`).
- Referencia Lumiya (librería, fuente decompilado 3.4.2, leído, no citado de
  memoria):
  - `render/glres/GLSyncLoadQueue.java`: `MAX_LOADS_PER_FRAME = 16`,
    `MAX_SIZE_PER_FRAME = 4194304` (4 MiB), `WAIT_FRAMES_AFTER_LOAD = 3`.
  - `render/WorldViewRenderer.java:722`: `if (!isResponsiveMode)
    renderContext.RunLoadQueue()`; `updateResponsive()` (línea 294):
    `isResponsiveMode = isInteracting || isFlinging`, pausando además el
    cómputo de geometría en background (`PrimComputeExecutor`).
  - `render/tex/TexturePriority.java`: `PrimVisibleClose/Medium/Far` (lo
    visible y cercano primero — ya lo hace `scanTextures` con 64 m/400 ms).
- Lo que r4 replica: presupuesto de subidas por frame + pausa de cargas
  durante el gesto. Lo que NO replica (aún): cómputo de geometría en hilo de
  fondo (`PrimComputeExecutor`) ni descanso de 3 frames — siguiente caza si el
  coste por objeto nuevo (~126 ms) sigue mandando tras r4.

## C. Archivos modificados (6, solo pacing + versión)

1. `slproto/asset/TexturePipeline.kt`: `pump(maxDecodedPerFrame,
   maxDecodedBytesPerFrame)` (defectos = drenado total, compatible con los
   checks kcheck); `collectDecodes` emite todos los `DecodeFailed` y raciona
   los `Decoded` en orden (con garantía de progreso: el primero siempre pasa);
   `stats.uploadsDeferred` (+ reset); `pendingUploadCount`; línea corta
   `hudLine` con `en subida/diferidas`.
2. `slworld/TextureStreamer.kt`: `maxUploadsPerFrame = 4`,
   `maxUploadBytesPerFrame = 4 MiB` (punto conservador: las subidas Filament
   pesan más que una carga GL por el rebind); `@Volatile holdLoads`;
   `lastPumpUploads`; `pump()` respeta tope y pausa; bloque `upload:` y línea
   `enabled:` del informe con las cifras nuevas.
3. `slworld/SLTextureCache.kt`: índice `byTexture` (UUID → materiales);
   `rebind` solo toca los afectados (mismo conjunto, mismos contadores);
   `clear()` lo vacía.
4. `slscene/SLScene.kt`: `prune` salta el barrido de texturas con `holdLoads`
   (`pump()` ya se autopaúsa).
5. `ui/world/FilamentWorldView.kt`: `drawFrame` publica
   `holdLoads = !cameraInsertionsAllowed()` antes de `prune` (la misma señal
   de recovery 3; gracia de 250 ms incluida).
6. `app/build.gradle.kts`: versionCode 16, `Ephora 2.22-r4`.

## D. Partes congeladas

Protocolo/parser, TextureEntry, OpenJPEG/J2K, scene lifecycle (salvo este
pacing), Parent/Child, recursos Filament, SLCamera, movement, GetTexture/
transporte, EventQueue, distancia/culling/sombras, hilos de decode (=1),
`DISCARD_LEVEL = 2`, `perScanBudget = 24`.

## E. Checks realizados

- 0 merge markers en el árbol; llaves/paréntesis balanceados en los 5 .kt
  tocados; símbolos nuevos presentes donde deben y en ningún otro sitio.
- `pipeline.pump()` sin args sigue compilando (kcheck `tests_texasset`,
  `tests_light`, `tests_render` lo llaman así; los defectos preservan el
  drenado total).
- Constructor de `TextureStreamer`/`SLTextureCache` sin cambios: sin call-sites
  que actualizar. Compilación Android local: no disponible aquí →
  CI BUILD PENDING (la APK la compila GitHub Actions, yo no compilo).

## F. Falta verificar (dispositivo)

DEVICE TEST PENDING. Instalar `Ephora 2.22-r4`, región poblada, y durante la
carga: drag + ángulos + pinch + mover mientras llegan objetos; soltar; dejar
cargar. Pegar: `Fases del frame` + `Aplicacion por tiempo` + bloque
`--- texturas ---` completo + línea corta `texturas:`.

## G. Criterios de aceptación

- Durante el gesto: `nuevos = 0`, `ultimo barrido 0`, cámara viva.
- En reposo: `nuevos <= 1`, `ultimo barrido <= 4`, `en espera de subida`
  drenando a 0, `diferidas` estable (no creciendo sin fin = el decode alimenta
  más de lo que la subida traga; si crece, el siguiente tope a mover es
  decode, no upload).
- Sin regresión: decode/upload/aplicadas > 0; mismos objetos visibles que r3.

## H. Prompt para la siguiente IA

Ver `PROMPT-IA-RECOVERY4.md`. Si el coste por objeto nuevo sigue ~100+ ms tras
r4, la siguiente caza es el cómputo de geometría (candidato: equivalente a
`PrimComputeExecutor` de Lumiya, geometría pesada fuera del render thread,
buffers solo en render thread) — instrumentar `attach`/slcore por componente
antes de mover nada.
