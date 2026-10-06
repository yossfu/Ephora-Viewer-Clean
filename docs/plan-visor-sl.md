# Visor Second Life Real — APK Android — Plan CRS

Fuente de verdad del proyecto. Actualizar aquí antes que en `index.html`.
`index.html` es solo la vista; este MD manda.

## 1. Alcance REAL
- Login real: username/password + MFA + StartLocation (home/last/region/x,y,z)
- Grid: Second Life Main + Aditi, opensim opcional
- Render 3D: prims, sculpt, mesh, rigged/bento, texturas J2K, LOD, luces
- Avatares, animaciones, attachments, teleports, mapa, search
- Social: chat local, IM, grupos, amigos, push
- Inventario: carpetas, wear/unwear, outfits
- Economía L$: vía web Linden en v1
- Voz: Vivox oficial, WebRTC provisional
- Fuera MVP: build avanzado, importar mesh, LSL in-viewer, Ultra

## 2. Requisitos
### Funcionales
- RF-01 Login/logout, sesión, MFA, TOS — P0
- RF-02 Render 20-40 avatares a 30fps gama media — P0
- RF-03 Caminar, volar, sentarse, teleport, cámara orbit — P0
- RF-04 Chat local + IM + grupos + historial — P0
- RF-05 Inventario wear/unwear, outfits — P0
- RF-06 Mapa, perfiles, search, friends — P1
- RF-07 Voz espacial + gestos — P1
- RF-08 Tienda L$ web — P1
- RF-09 Gráficos ajustables + ahorro batería — P0
### No funcionales
- RNF-01 Android 9+ arm64, GLES 3.1 / Vulkan, 4GB RAM min
- RNF-02 APK <200MB, arranque <8s en 4G, reconexión auto
- RNF-03 TLS 1.2+, token OAuth, sin password en claro
- RNF-04 Crash-free >99%, ANR <0.5%
- RNF-05 LGPL + Third Party Viewer Policy cumplidas

## 3. Stack recomendado
Kotlin + Compose (UI) + C++17 NDK (core SL: UDP, LLSD, OpenJPEG, mesh, GL) + Filament/GLES + Vivox + Firebase push/crash.
Opción A: fork Alchemy/Firestorm. Opción B: Unity/Godot + libopenmetaverse. Opción C: estilo Lumiya ligero.

## 4. Módulos
Login XML-RPC, Seed cap, Event queue, Circuit UDP, LLSD, Assets caps, caché 1-2GB LRU, J2K a ETC2/ASTC, prims/sculpt/mesh LOD, Bento, BoM, attachments, jellydolls, FMOD/OpenSL, Vivox, KV local.

## 5. APK / Release
Flavors dev/beta/release, arm64-v8a, R8, permisos mínimos, Data Safety, 16KB page (Android 15+), beta cerrada 50-200, CI AAB + Firebase Distribution.
Legal: registrar Third-Party Viewer, publicar fuente LGPL, respetar marca Second Life.

## 6. Equipo / Coste / Roadmap
- C++ SL/NDK 1-2, Kotlin UI 1, 3D GLES 1, Backend/QA 1, PM/Diseño part-time
- Coste: 60-110k LatAm/Este, 120-180k+ USA/EU, +5k/año servers
- F0 M1-M2 login + cubo + chat Aditi / F1 M3-M6 MVP mover/teleport/avatar/IM/inventario / F2 M7-M9 voz/mapa/30fps/push/beta / F3 M10-M12 L$/outfits/release / F4+ build/foto/iOS

## 7. Riesgos
Bloqueo Linden por TPV, peso J2K/APK, gama baja (LOD/jellydolls), Vivox caro, phishing/2FA, fuente LGPL el día 1.

## 8. Flujo Opción 1 ELEGIDA — principio a fin
DECISIÓN: fork Alchemy + NDK + Kotlin Compose.
- Paso 0 Legal+Repo (S1): fork, LFS, TPV Policy, Aditi. Sale: compila en PC.
- Paso 1 Build Android (M1-M2): CMake NDK arm64, OpenJPEG, login JNI. Sale: APK entra a Aditi.
- Paso 2 Mundo mínimo (M3-M4): UDP, prims, cámara, mover/teleport. Sale: caminar sin crash.
- Paso 3 Avatares+Assets (M4-M6): mesh/Bento/BoM, caché, jellydolls. Sale: 20 avatares.
- Paso 4 Social (M6-M7): IM/grupos/inventario wear/mapa/UI. Sale: uso diario.
- Paso 5 Voz+Perf (M7-M9): Vivox, 30fps, push, ahorro. Sale: beta cerrada.
- Paso 6 Release (M10-M12): R8, AAB, Data Safety, fuente LGPL. Sale: Play Store.
Regla: un paso = una función, probar en móvil real en Aditi antes de avanzar.

## Changelog
- 2026-09-29: plan inicial CRS v1.
- 2026-09-29: elegida Opción 1 (fork Alchemy + NDK). Flujo 0-6 agregado.
