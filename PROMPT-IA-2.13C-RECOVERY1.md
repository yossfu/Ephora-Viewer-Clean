# PROMPT-IA-2.13C-RECOVERY1

Trabaja a partir del ZIP `EPHORA 2.13c-recovery1`. Es una base de recuperación; no hagas un refactor general.

## REGLAS
- Primero inspecciona y compila; después modifica.
- NO tocar TextureEntry, ObjectUpdate, ExtraParams, Parent/Child, render loop Choreographer/VSYNC, camera basis ni movement salvo evidencia directa.
- NO afirmar pruebas en teléfono/Second Life: el agente no tiene ese entorno.
- Mantener el transporte de texturas que ya demostró recepción real.

## CAMBIOS YA INCLUIDOS
- EventQueue separa su error propio de `Capabilities.lastError` y reintenta sin logout.
- `ViewerSessionService` y `MainActivity` ya no detienen el servicio por `ERROR`; solo por `DISCONNECTED`.
- GetTexture reintenta dos veces errores transitorios 408/425/429/500/502/503/504 y excepciones de transporte.
- OpenJPEG usa un único worker/in-flight por defecto y libera la copia RGBA de CPU después del upload.
- El streamer usa discard level 2 en esta prueba de estabilidad.
- OpenJPEG acepta J2K y JP2.
- Los errores de upload conservan el mensaje real del renderer.

## TU TRABAJO AHORA
1. Revisa el diff de esta base y verifica que compila.
2. Ejecuta todos los checks locales existentes (`tools/*check*`, pruebas nativas/Kotlin, etc.).
3. Comprueba referencias rotas y que no haya cambios accidentales en los subsistemas protegidos.
4. NO añadas otro cambio funcional grande todavía.
5. Verifica que `releaseDecoded()` ocurre después de éxito/fallo de upload y que el codestream sigue en cache para `rearmDecodes()`.
6. Verifica que los retries no afectan 401/403/404.
7. Verifica que HTTP 500/502 de EventQueue NO cambia `ConnectionState`.
8. Verifica que no existe una navegación automática al menú causada por `ERROR`.

## PRÓXIMO TEST REAL
El APK debe permitir distinguir:
- EventQueue: `CONNECTION_STATE`, `EventQueueGet`, fallos consecutivos y retries.
- Texturas: request, HTTP, bytes, decode OK/fallo, upload OK/fallo, materiales/aplicadas.
- Memoria: codestream cache e imágenes decodificadas.

## CRITERIOS DE ÉXITO
A) permanecer en el mundo durante una sesión prolongada;
B) errores HTTP transitorios no fuerzan logout;
C) texturas avanzan hasta decode/upload/apply;
D) ningún OOM, SIGSEGV, FATAL EXCEPTION ni error nativo OpenJPEG.

## ENTREGA
Si los checks locales pasan, deja listo el proyecto para GitHub Actions y explica exactamente qué se verificó localmente y qué queda pendiente en teléfono/Second Life.
No mezcles movement con esta reparación.
