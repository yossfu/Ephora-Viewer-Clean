# PROMPT — CONTINUACIÓN EPHORA 2.22-r4 / RECOVERY 5

Trabaja sobre `EPHORA-2.22-r4-RECOVERY4-PERF.zip` (versionCode 16, `Ephora 2.22-r4`).
No reconstruyas por memoria: la fuente de verdad es el repositorio +
`EPHORA-CURRENT-CONTEXT.md` + `EPHORA-2.22-RECOVERY4-PERF.md` +
`AI-OPERATING-RULES-EPHORA.md` + el log real que el usuario pegue.

## Estado heredado (medido, no hipotético)

- 2.22 medido: 15 frames en 115.1 s, sync 6792.8 ms / render 3.4 ms / post
  10.4 ms por presentado; apply 113870 ms, 900 nuevos a 126.5 ms, en espera
  6288. Causa: `applyNewPerFrame = 60` → ~8 s por frame.
- Recovery 3 (en el árbol): `applyNewPerFrame = 1`, `cameraTouchActive
  @Volatile`, 0 inserciones en gesto + 250 ms de gracia.
- Recovery 4 (en el árbol, sin probar en dispositivo): método Lumiya de
  pacing — `pump(maxDecodedPerFrame = 4, maxDecodedBytesPerFrame = 4 MiB)`,
  `rebind` indexado por UUID, `holdLoads` (misma señal del gesto) pausa
  subidas y barridos. Referencias exactas en `EPHORA-2.22-RECOVERY4-PERF.md`
  §B (GLSyncLoadQueue, WorldViewRenderer:722, updateResponsive).
- Texturas en el log 2.22: 35 recibidas / 7 decode OK / 7 upload OK. Sin
  regresión J2K conocida.
- EventQueue HTTP 500/502: dominio separado, no mezclar.

## Orden obligatorio

1. Lee los documentos citados + el último log del usuario.
2. Compara ANTES (2.22/r3) → DESPUÉS (r4) con números: sync/apply por
   presentado, media ms/nuevo, `ultimo barrido`, `diferidas`, `en espera de
   subida`, `pendingAdded`, VSYNC, Hilos.
3. Si r4 cumple (§G de la nota): pasa al siguiente dominio documentado con
   otro recovery independiente. NO optimices más en el mismo.
4. Si el coste por nuevo sigue ~100+ ms: NO reescribas SLCamera ni muevas
   Filament de hilo. Instrumenta por componente (`SLScene.attach`, slcore,
   `createEntity`, `createMaterial`, `Builder.build`, binding, transforms) y
   publica el siguiente coste dominante. Candidato documentado: equivalente a
   `PrimComputeExecutor` de Lumiya (geometría pesada fuera del render thread).
5. Entrega siempre: ZIP + BAT + nota recovery + este prompt actualizado + log
   usado + cambios + checks + pendientes + prueba concreta. Estados exactos:
   LOCAL CHECK OK / CI BUILD PENDING / DEVICE TEST PENDING|PASSED|FAILED.
   Nunca afirmes "APK compilado" sin build Android ejecutado.
