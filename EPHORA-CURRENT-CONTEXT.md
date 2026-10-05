# EPHORA — Documento de recuperación de contexto

Fecha: 2026-09-24. Reconstruido desde el árbol `ephora-viewer/` + mensaje del usuario.
NO contiene evidencia nueva de dispositivo. Nada de lo aquí descrito como
"pendiente de dispositivo" debe afirmarse como probado.

## 1. Qué es

Viewer de Second Life para Android, derivado de la base de Lumiya, con render
Filament. Arquitectura por capas (verificada en el código):

```
SL protocol (slproto/) → WorldModel/ObjectUpdateDecoder → slworld/ (SLRegion,
SLObject, SLPrimitive, SLMesh, SLTexture, SLAvatar) → slscene/ (SLScene, SLCamera)
→ renderer/Renderer (seam, sin tipos de motor) → renderer/filament/
(FilamentRenderer) → Filament 1.75.1 → OpenGL ES (Vulkan = cambio de una palabra + pruebas)
```

Kotlin = UI + alto nivel. C++/JNI (`slcore`) = geometría de prims (y ahora J2K).
Filament nunca ve paquetes, UUIDs ni TextureEntry: solo mallas, texturas,
materiales, transforms y cámara.

## 2. Flujo de trabajo real (invariante de proceso)

Agente (sin Android físico, sin APK, sin SL real): edita/analiza código, analiza
logs del usuario, crea tests locales sin Android/SL, documenta, prepara ZIP + BAT.
NUNCA afirma pruebas de dispositivo sin evidencia. NUNCA confunde harness local
(`tools/kcheck`) con prueba real en SL.
Usuario: ejecuta BAT → GitHub Actions compila → instala APK → prueba en SL →
entrega reporte → siguiente iteración.

## 3. Dispositivo

Xiaomi 2201116PG / POCO X4 Pro 5G, Android 16 / API 36, arm64-v8a, Adreno 619.
Filament 1.75.1, backend OpenGL ES. Filament probado en este dispositivo
(engine/renderer/scene/view/camera/swapchain/SurfaceView OK).

## 4. Cerrado (no reabrir sin regresión demostrada por log nuevo)

- ObjectUpdate / Compressed / Terse / Cached + KillObject: funcionando.
- ExtraParams: corregido, sin fallos de parseo actuales.
- Shape classification: enum PrimShape con UNKNOWN; formas reales variadas
  (BOX, SPHERE, CYLINDER, PRISM, RING, TUBE...) recibidas y generadas.
- TextureEntry: CERRADO. Prefijo u32 de ImprovedTerseObjectUpdate consumido
  (2.13a-rev4, `tersePrefixConsumed` en ObjectUpdateDiagnostics). Último dato:
  completos, 0 truncados, 0 A/B/C/D/E, 0 rechazos del lector de referencia.
- Parent/Child: cadena Decoder → SceneObject.parentId → SLObject.parentLocalId
  → Transform.parent → FilamentRenderer.setParent(). Hijo conserva TRS LOCAL;
  Filament compone parentWorld × local. NO convertir a coords de región.
- Geometría: miles de prims/renderables generados sin fallos (slcore C++).
- Render loop: busy-spin corregido; hilo `EphoraRender` + Choreographer/VSYNC,
  UI/input en `main`. NO tocar.
- Movimiento base de cámara: corregido a `AgentUpdateBuilder.referenceBasis(viewYaw)`
  (at=(1.00,0.09,0), izquierda=(-0.09,1.00,0), arriba=(0,0,1)). NO tocar basis.
- Texturas 2.13b (transporte): DEMOSTRADO en dispositivo — 412 UUIDs, 412
  peticiones, 412 respuestas con bytes, 0 errores, 99.1 MiB, 412 assets listos,
  0 fallidos. NO hay bloqueo de acceso al servidor SL.

## 5. Estado real del código en este árbol (¡incluye cambio local no verificado!)

El árbol contiene trabajo 2.13c hecho en la sesión anterior y entregado como
`ephora-viewer-2.13c.zip`, pero SIN evidencia de compilación CI ni de dispositivo:
- NUEVO `slproto/asset/OpenJpegTextureDecoder.kt` (J2KDecoderNative + decoder tras el seam).
- NUEVO `app/src/main/cpp/sl_j2k_decode.cpp` (decode J2K→RGBA8, cp_reduce=discard, topes 2048px/32MiB).
- `CMakeLists.txt`: FetchContent OpenJPEG v2.5.4 estático, link `openjp2` a `slcore`.
- `TexturePipeline` default = OpenJpegTextureDecoder; `FilamentWorldView`
  usa OpenJPEG con fallback a Unavailable si el símbolo nativo no enlaza.
- `versionCode 2 / 1.0.1-2.13c`; entrada 2.13c en TODO.md (device check pendiente).
Estado 2.13c: intento CI 2026-09-24: FetchContent OpenJPEG OK; fallo solo por
GetByteLength (no existe en JNI) -> corregido a GetArrayLength. Pendiente reintento CI.

## 6. Pendiente (orden)

1. 2.13c device check: `decoder OpenJPEG J2K`, `decode OK>0`, `esperando 0`,
   caras con textura aplicada >0. Si CI falla (FetchContent/hash/link), corregir.
2. Movimiento (NO localizado): ventana FORWARD de 50 pulsaciones → solo 2
   AgentUpdate con FORWARD=0x00000001, 0 m recorridos. Input llega, comandos se
   generan, socket envía, flags correctos en esos 2. Pregunta: por qué solo 2
   efectivos y qué hace el simulador tras recibirlos. Instrumentación ya existe
   (`MovementAudit` + ventana FORWARD + `MOVEMENT-AUDIT.md`). NO declarar solucionado.
3. Después: mesh assets (5), terrain (6), region streaming (7), avatares (8),
   animación (9), WindLight (10), culling/LOD/performance (11). AvatarAppearance
   aún no se recibe/renderiza; sin cápsulas sustitutas (regla del proyecto).

## 7. Reglas que no deben romperse

- Ningún dato real de SL se sustituye por datos inventados para ocultar un bug.
- Ningún avatar dibujado como sustituto (SLAvatar solo guarda lo recibido).
- TextureEntry parser: no tocar sin evidencia nueva.
- Harness local ≠ prueba en SL, siempre.


---

# CHECKPOINT 2.22-r3 — 2026-09-24: render-thread starvation corregido

El APK 2.22 del log `DIAG-EVIDENCE-2.22-20260924-130149.txt` mostró la causa del lag de cámara: el hilo `EphoraRender` pasó casi todo el tiempo dentro de `drawFrame`; la aplicación de objetos nuevos era el coste dominante. El código aplicaba hasta 60 objetos nuevos por frame, y el diagnóstico midió aproximadamente 135 ms por objeto nuevo. Eso podía producir frames del orden de 8 s.

La corrección Recovery 3 es deliberadamente pequeña:
- `applyNewPerFrame = 1`.
- mientras hay toque/pinch (`cameraTouchActive`) se pausa la creación de objetos nuevos; updates/removals continúan.
- se espera 250 ms tras terminar el gesto antes de reanudar inserciones.
- el estado de interacción cruza UI -> render mediante `@Volatile`.

No se toca en esta corrección: protocolo SL, TextureEntry, OpenJPEG, Parent/Child, matrices de cámara, AgentUpdate/movement, transporte de texturas, EventQueue ni Filament camera.

El diagnóstico de la misma APK 2.22 también registra EventQueue HTTP 500/502; es una incidencia separada de sesión y no se declara resuelta aquí.

Siguiente aceptación: instalar 2.22-r3 y verificar que la cámara sigue actualizándose durante drag/pinch aunque `pendingAdded` sea grande; comprobar además que las texturas siguen llegando/decodificando/subiendo.

## Recovery 3 delivery

Deliverable target: `Ephora 2.22-r3` / versionCode 15. Primary fix: render-thread pacing and camera-priority during world population. Evidence file: `DIAG-EVIDENCE-2.22-20260924-130149.txt`. Next AI must read `AI-OPERATING-RULES-EPHORA.md` and `PROMPT-IA-RECOVERY3.md` before editing.


---

# CHECKPOINT 2.22-r4 — 2026-09-24: metodo Lumiya de rendimiento (pacing de texturas)

Recovery 3 dejo dos caminos ilimitados en `drawFrame`: `pump()` drenaba todo lo decodificado por frame y `rebind()` barria todos los materiales por textura (O(NxM)). Referencia leida del decompilado Lumiya 3.4.2: `GLSyncLoadQueue` (16 cargas / 4 MiB / descanso 3 frames) + `WorldViewRenderer:722` (`if (!isResponsiveMode) RunLoadQueue()`, responsive = interactuando o fling).

Correccion r4 (dominio unico pacing): `pump(4, 4 MiB)` con progreso garantizado y fallos siempre emitidos; `rebind` indexado por UUID (mismo conjunto, mismos contadores); `holdLoads` (misma senal del gesto r3) pausa subidas y barridos; diagnostico `ultimo barrido / diferidas / en espera de subida`. Congelado todo lo demas (decode=1, DISCARD_LEVEL=2, perScanBudget=24).

Deliverable: `Ephora 2.22-r4` / versionCode 16. Evidencia base: DIAG-EVIDENCE-2.22-20260924-130149.txt. Estado: CI BUILD PENDING + DEVICE TEST PENDING.


## 2.26 (2026-09-26, sin compilar: solo codigo + ZIP)

Doble objetivo A (estabilidad) + B (encaje de texturas), sin UI nueva.

A) Lifecycle: `TextureAssetCache` con tope 64 MiB LRU (`evictExcess`, protege
claves en decode); `TexturePipeline.decodeLoop` reencola si el LRU expulso los
bytes (antes la clave quedaba en `decoding` para siempre); `cancelDead` retira
descargas de objetos que salieron; `SLTextureCache.gc/gcTextures` destruye
materiales e instancias y texturas GPU sin usuarios vivos (los slots son la
verdad de usuarios); `textureDecoded` destruye la GPU anterior al reemplazar;
`SLScene.clear` limpia `meshWaiters/meshWaiterObjects/meshGltfIds`;
`SLScene.destroy` detiene el hilo EphoraMesh; barrido lento (15 s) + linea
`RECURSOS` con vivos (entidades/renderables/materiales/instancias/texGPU/
meshGPU/VBO/IBO/objetos/rebuilds/swaps/pendientes/evicciones), nunca por frame.

B) Mapeo: verificado contra `llface.cpp` (`xform` = centro 0.5, rotar, repeat
con signo = flip, offset; `planarProjection` exacta incl. base binormal/
tangente). El shader convierte al final al convenio Filament (origen V abajo en
buffer, fila superior primero): `uv = (sl.x, 1-sl.y)`; espejos CPU igualados;
mallas fijas (grass/tree) a V oficial (0 abajo). Sin reconstruir renderables:
todo via `updateMaterial(s)/setMaterialInstanceAt` existentes.
