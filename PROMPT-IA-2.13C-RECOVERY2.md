# PROMPT-IA-2.13C-RECOVERY2

Trabaja a partir del ZIP `EPHORA-2.13c-recovery2`. Esta iteración está basada en un diagnóstico real de dispositivo y contiene un cambio nativo muy concreto.

## HALLAZGO CONFIRMADO

El transporte de texturas NO es el bloqueo principal:
- 574 solicitadas
- 572 respuestas
- 567 con bytes
- 85.8 MiB en cache
- 567 encoladas para decode

El bloqueo está en OpenJPEG:
- decode OK: 0
- decode fallo: 567
- OpenJPEG: 567 fallos en etapa `8(comps)`
- el primer codestream reportado empieza por `FF4FFF51`

En el código anterior, etapa 8 se producía cuando el decoder exigía:
`comp.w == image.w && comp.h == image.h`
para todos los componentes. Esa condición es incorrecta para imágenes JPEG-2000 con componentes subsampleados.

## CAMBIO YA REALIZADO

`sl_j2k_decode.cpp` ahora:
- acepta `dx/dy` de componentes;
- acepta `x0/y0` de componente;
- asigna cada pixel de la imagen de referencia al sample correspondiente del componente;
- conserva límite de 2048x2048;
- maneja componentes unsigned y signed defensivamente;
- soporta gris+alpha;
- convierte SYCC a RGB;
- convierte CMYK a RGB;
- no considera CMYK como alpha;
- mantiene raw J2K y JP2.

## NO TOCAR

No tocar en esta iteración:
- TextureEntry
- ObjectUpdate/ObjectUpdateCompressed
- ExtraParams
- Parent/Child
- Choreographer/VSYNC
- camera basis
- movement
- renderer Filament
- GetTexture transport, salvo que la nueva prueba muestre evidencia directa

## TU TRABAJO

1. Audita el diff de recovery2.
2. Compila Kotlin y C++/Android cuanto sea posible.
3. Comprueba que no hay errores de JNI, nombres de funciones o includes.
4. Comprueba el pipeline `Decoded -> upload -> material textureDecoded -> apply`.
5. No hagas más cambios funcionales antes de comprobar la compilación.
6. Si compile OK, prepara ZIP + `SUBIR-A-GITHUB.bat`.

## PRUEBA REAL QUE DEBE HACER EL USUARIO

Después de instalar el APK:
- entrar al mundo;
- esperar a que lleguen texturas;
- abrir Diagnóstico;
- comprobar especialmente:
  `decode: OK N`
  `j2k etapas: ok N`
  `upload: OK N`
  `aplicadas: N materiales / N caras`

Si decode sigue en 0:
NO tocar transporte ni TextureEntry. Revisar únicamente el diagnóstico nativo/JNI/OpenJPEG y el código exacto generado por la compilación.

## SESIÓN / MENÚ

El síntoma de salir al menú sigue pendiente. Este reporte no lo reproduce. No declararlo resuelto. Añadir/usar trazas de:
`CONNECTION_STATE`, `EventQueue`, `ViewerSessionService`, `WorldViewActivity`, lifecycle y excepciones si la siguiente prueba vuelve a sacarnos al menú.

## ENTREGA

Antes de entregar, responde:
- compilación: OK/NO
- decoder native: OK/NO local
- pipeline upload/apply auditado: OK/NO
- cambios adicionales: lista exacta
- prueba Android/SL: NO realizada por la IA
