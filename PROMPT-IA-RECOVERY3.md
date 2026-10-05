# PROMPT — CONTINUACIÓN EPHORA 2.22-r3 / RECOVERY 3

Trabaja sobre el proyecto `ephora-viewer` que acompaña este prompt. Esta es una continuación de un visor Android de Second Life basado en el trabajo previo alrededor de Lumiya, OpenJPEG/J2K, slcore y Filament.

NO actúes como si conocieras un historial de chat que ya no existe. La fuente de verdad es el repositorio + estos documentos + los logs reales.

## ORDEN OBLIGATORIO AL INICIAR

Lee primero:
1. `EPHORA-CURRENT-CONTEXT.md`
2. `EPHORA-2.22-RECOVERY3-PERFORMANCE.md`
3. `AI-OPERATING-RULES-EPHORA.md`
4. `PROMPT-IA-RECOVERY3.md`
5. `TODO.md`
6. `MOVEMENT-AUDIT.md`
7. `DIAG-EVIDENCE-2.22-20260924-130149.txt`

Después inspecciona el código actual relacionado con el problema. No reconstruyas el proyecto por memoria.

## ESTADO CONFIRMADO DEL CHECKPOINT

La APK 2.22 original presenta una anomalía de rendimiento que congela visualmente la cámara:

- la cámara/input viven entre UI `main` y renderer `EphoraRender` separados;
- el renderer usa Choreographer a 60 Hz;
- el log registra frames de varios segundos;
- `drawFrame` pasa casi todo el tiempo en sincronización/aplicación de escena;
- el diagnóstico mide aproximadamente 135 ms por objeto nuevo;
- la versión 2.22 podía tomar hasta 60 objetos nuevos por frame;
- 60 × 135 ms ≈ 8.1 s de trabajo potencial en un solo frame.

La evidencia concreta del log es la última versión del archivo `DIAG-EVIDENCE-2.22-20260924-130149.txt`.

## CORRECCIÓN DE RECOVERY 3 YA PREPARADA

En `FilamentWorldView.kt`:

- `applyNewPerFrame` pasa de 60 a 1;
- se añade `cameraTouchActive` como estado `@Volatile` entre UI y render;
- mientras el usuario arrastra/pellizca, se pausa la creación de objetos nuevos;
- updates/removals continúan;
- después del gesto existe una gracia de 250 ms antes de iniciar otra creación costosa;
- se limpia `cameraTouchActive` si la ventana pierde foco sin producir `ACTION_UP`.

No tocar estos puntos durante esta iteración salvo evidencia directa de regresión:
- parser ObjectUpdate/ObjectUpdateCompressed;
- TextureEntry;
- OpenJPEG/J2K;
- Parent/Child;
- cámara matemática;
- AgentUpdate/movement;
- transporte GetTexture;
- EventQueue/sesión;
- matrices/culling de Filament.

## OJO: NO CONFUNDIR LOS DOS PROBLEMAS

El log también contiene errores EventQueue HTTP 500 con cuerpo 502 Proxy Error. Eso es un problema separado de sesión/conectividad.

No mezclar el arreglo de rendimiento con el arreglo de EventQueue.

## OBJETIVO DE ESTA ITERACIÓN

Demostrar que la cámara vuelve a responder durante la carga del mundo sin romper:
- geometría;
- texturas;
- parent/child;
- movimiento;
- sesión.

## PRUEBAS OBLIGATORIAS ANTES DE MÁS CAMBIOS

### A. Compilación
- intenta la compilación Android completa si el entorno lo permite;
- si no hay SDK/Gradle local, no inventes un resultado: deja claramente `CI BUILD PENDING`.

### B. Revisión estática
Comprueba:
- paréntesis/llaves balanceados;
- no hay merge conflict markers;
- no hay referencias a APIs inexistentes;
- el ZIP solo contiene los cambios intencionados;
- `SUBIR-A-GITHUB.bat` sigue encontrando `ephora-viewer\settings.gradle.kts`.

### C. Primer APK
Usar GitHub Actions del workflow existente.

### D. Prueba en Android
Durante carga del mundo:
1. entrar al mundo;
2. empezar drag de cámara;
3. mover varias veces durante la llegada de objetos;
4. comprobar que la cámara cambia continuamente y no espera varios segundos;
5. hacer pinch zoom;
6. comprobar que los objetos siguen llegando después de soltar.

## QUÉ MEDIR EN EL PRÓXIMO LOG

Comparar contra el log 2.22 original:
- FPS;
- frame sync;
- frame apply;
- nuevos por frame;
- promedio ms por nuevo;
- pendingAdded;
- callbacks VSYNC;
- tiempo total en drawFrame;
- input thread / render thread.

Éxito parcial: durante el gesto, `nuevos=0` y la cámara sigue cambiando.

Éxito de rendimiento: desaparecen los frames de varios segundos producidos por aplicar 60 objetos de una vez.

## SI SIGUE EL LAG

No reescribir la cámara.

Primero instrumentar por componente el coste de:
- `SLScene.apply`;
- `renderer.createEntity`;
- `renderer.createMaterial`;
- `RenderableManager.Builder.build`;
- `materials.createInstance`;
- transform creation;
- material updates.

El objetivo es encontrar el siguiente cuello de botella medido. No adivinar.

## SI LA CÁMARA YA ES FLUIDA

NO optimizar otras cosas en la misma versión. Pasar al siguiente problema documentado y crear otro recovery independiente.

## REGLAS DE ENTREGA DE CADA VERSIÓN

Nunca entregues solo “ya quedó”. Cada versión debe contener:

1. ZIP completo y reproducible.
2. BAT funcional para subir a GitHub y lanzar Actions.
3. Nota de recovery con fecha, causa, evidencia, cambios y pendientes.
4. Prompt para la siguiente IA.
5. Lista exacta de archivos modificados.
6. Checks ejecutados.
7. Qué NO se pudo verificar.
8. Prueba concreta que debe ejecutar el usuario.

El nombre debe seguir:
`EPHORA-<version>-RECOVERY<N>-<purpose>.zip`

## REGLA DE ORO

Una modificación no se considera “arreglo” hasta que:
`hipótesis -> evidencia -> cambio mínimo -> compilación -> APK -> prueba real -> log comparativo`

No introducir tres hipótesis en un mismo cambio.

No borrar diagnósticos existentes simplemente porque ya no son cómodos.
