# REGLA 1 (SUPREMA, permanente): NINGUN cambio puede eliminar, reducir, ocultar ni romper una funcion ya verificada (chat enviar/recibir, amigos/en-linea, IM con notificacion, near, entrar al mundo, reporte, 3D abrir/salir/reabrir). Avances solo SUMAN. Cada RESULTADO incluye CHECKLIST DE REGRESION item por item con evidencia (linea de log o captura). Si alguna entrega viola esto, la siguiente iteracion es RESTAURAR, no avanzar.
# REGLAS-IA — orden permanente para todas las entregas EPHORASL
# Este archivo manda sobre cualquier instrucción contraria salvo orden explícita del usuario.
# Lista ampliable, nunca reducible. Actualizar en cada entrega (versión al pie).

## 1. LINEAS CONGELADAS del reporte (prohibido eliminar o renombrar; si una falta, la entrega se rechaza)
# - CHAT-TX
# - TX-NUM
# - TX-HEX ChatFromViewer
# - RETRY-ARMADO
# - RETRY-CANCELADO
# - RETRY-DISPARADO
# - CHAT-ACK seq=... si/no
# - CHAT-OK seq=<n> (eco)
# - IM-TX / IM-RX / IM-DUP / IM-OTRO / IM-RX-ESTADO / IM-RAW / IM-PARSE / IM-ACK-SI / IM-DEST / BUDDY-N / BUDDY-RAW / NAME-REQ / AMIGO / AMIGO-N / AMIGO-ON / AMIGO-OFF / AMIGO-ON-N / NAME-REPLY / NAME-REQ-ERROR / NAME-REPLY-ERROR / IM-F1 / IM-F2 / IM-F3 / IM-OTRO / IM-OFF / IM-OFF-DRAIN / IM-OFF-DRAIN-REINTENTO / IM-NOLEIDO / IM-HILO / IM-ESCRIBE / IM-FIN-ESCRIBE / IM-OFF-DRAIN-ERROR / RETRY-MANUAL-MISMOSEQ / RETRY-MANUAL-ERROR / RX-BURST / RX-BURST-MAX / NAME-REQ-REINTENTO / IM-DEST
# - ACK-RAW
# - ACK-COUNT
# - CHAT-RX <nombre>: <texto> (con marca (eco) en propios)
# - CHAT-RX-ESTADO
# - NEAR-ESTADO
# - PING-ESTADO
# - BG-OK
# - LOGIN-UI
# - RX-COUNT

# - Veredicto de entrega: cada mensaje dudoso se verifica en el visor oficial (el oficial manda).

## 1c. REGLA DE ORO REENVIOS: mismo seq = el sim deduplica (seguro); seq nuevo = duplicado en el oficial (prohibido salvo mensaje nuevo del usuario).
# 1b. REGLA PERMANENTE TX (verificado en cable 5.4/5.5, cierre 5.6)
# - Formato de envio en B verificado en cable: PROHIBIDO volver a tocarlo en ningun prompt futuro.

## 2. ARCHIVOS CONGELADOS (jamás tocar sin orden explícita del usuario)
# - app/src/main/java/com/ephora/sl/LoginManager.kt (login)
# - app/src/main/java/com/ephora/sl/CapsManager.kt (caps + seed)
# - app/src/main/java/com/ephora/sl/UdpCircuit.kt (handshake + codec; el resto solo con orden)
# - app/src/main/cpp/* + NdkCore.kt (NDK)
# - app/src/main/res/layout/*.xml y demás XML (layouts; solo con orden UI)
# - Formato TX V2LE de ChatManager.buildTx y parse RX que funciona (referencia byte a byte: tag 4.7)

## 3. REGLAS DE CODIGO Y DEPENDENCIAS
# - Una sentencia por línea en todo código nuevo o modificado.
# - Versiones de dependencias solo fijadas si existen en su maven-metadata.xml.
# - Cero hotlinks en runtime (nada de js/img remotos salvo CDNs fijados por orden).
# - core-ktx 1.13.1 fijo salvo orden explícita.
# - Rezip limpio: sin APKs, sin build/, sin .gradle/.

## 4. FORMATO DE ENTREGA (toda respuesta de entrega trae esto o se rechaza)
# - Checklist de ESTAS reglas, una por una: cumplida sí/no.
# - Diff: qué cambió y por qué, solo el alcance ordenado.
# - ARCHIVO:LINEA de cada cambio (rutas app/src/main/).
# - ZIP completo adjuntado + prueba de red NO realizada (salvo que el usuario la pida y traiga log).
# - Español. Sin rodeos.

# Historial: v4.9 2026-09-30 creación del archivo (nace con la entrega 4.9).
# Historial: v4.9-fix 2026-09-30 fix compilación: chatRing/chatLines declaradas antes de buildReport (MainActivity.kt). Sin cambios de layout ni versión.

# Historial: v5.2 2026-09-30 chat ida/vuelta: experimento A/B/C sin veredicto fijado, channel LE (libomv), ACK-TX+RX-DUP+ECO-OK, dedup 15s, retry solo manual 20s, bump 5.2. Orden explicita: prevalece sobre congelados S1 (eco) y S2 (buildTx) solo en alcance chat.
# Historial: v5.3 2026-09-30 diagnostico chat: eco por AgentID (NO existia), ACK-COUNT=ACK-TX enviados, RX-ESTADO=unicos pintados, anillo 100, version 5.3 total, primer EQ verificado real. Sin tocar envio/ack/dedup.
# Historial: v5.4 2026-09-30 ruta normal txVariant B (A truncaba), etiquetas CHAT-RX-UDP/EQ+t, oraculo CHAT-ACK-SI, version 5.4 total. Builders/dedup/eco/ack intactos.
# Historial: v5.5 2026-09-30 consola contenida en ScrollView 240dp nested (solo layout) + version 5.5 total. Truncado cerrado en cable (B). Sin tocar .kt salvo bump.
# Historial: v5.6 2026-09-30 consola limpia (ACK-TX silenciado, ACK-SUM/100) + CHAT-ACK-TARDE, version 5.6 total. Acks se siguen enviando igual.
# Historial: v5.7 2026-09-30 oraculo eco-manda (CHAT-OK + CANCELADO eco, no solo sin eco ni ack) + version 5.7. Drawer presente, no re-aplicado.
# Historial: v5.8 2026-09-30 no-con-hint + ECO-OK con t + version 5.8. Sin cambios de logica.
# Historial: v5.9 2026-09-30 IM 1:1 Dialog 0 (RX parse + reply-target + TX buildIm + toggle DECIR/IM) + version 5.9. Sin tocar chat local ni congelados.
# Historial: v6.0 2026-09-30 diagnostico 00FE (IM-RAW 128B + IM-PARSE + IM-ACK-SI) + campo Para(UUID) con IM-DEST + version 6.0. Parse/builders intactos.
# Historial: v6.0-fix 2026-09-30 isImUuid movida a ChatManager (fix Unresolved reference MA:191).
# Historial: v7.6 2026-10-01 Filament 1.77.1 (gltfio+utils) + SlWorldRenderer (suelo+proxies+cam seguidora) + nearPos + boton MUNDO 3D + 3D-ESTADO + version 7.6. Protocolo y chat intactos.
# Historial: v7.6-prims 2026-10-01 prims reales (PrimDecoder High 12/13/14/15/16 verificado contra visor oficial+OpenSim + AgentLoop.objetos 10Hz + pool 64 box.glb con escala/yaw/tint + 3D-ESTADO obj=M + 6 tests) + version 7.6 sin bump. Chat/IM/presencia/login/UI/dependencias intactos.
# Historial: v7.11 2026-10-01 decoder multivariante (terse 44/60-float + 64/32/80/48-U16 viewer; comp full>=84 + terse 44/60; full 60/76/124/140 con offset avatar; skip-no-break; terreno con gate stride==264 + zerofix + sin descartar 1er paquete + clamp + min/max finitos; UdpDecodeTest 3 vectores) + bump 7.11/72. LE intacto (verificado viewer+wiki+OMV). Resto intacto.
# Historial: v7.10 2026-10-01 terreno real LayerData High-11 (TerrainMesh.kt: BitReader MSB-first + tablas IDCT + DecodePatchHeader/Patch/DecompressPatch verificados contra OMV TerrainCompressor.cs/TerrainManager.cs/BitPack.cs con harness plano 25.5 exacto; columnas 8m 32x32 + TERRA-DRAW/PLACEHOLDER) + zero-fallback en 12/13/15 (harness: codificado da 0 directo y N tras expandir) + esc>64m a nEscMala + mat en tint + GFX-DIAG/TERRA/PRIMS-DEC en reporte + bump 7.10/71. LE y zerocode-base intactos (OMV confirma LE). Avanzadas auditadas: todo cableado, sin cambios. Resto intacto.
# Historial: v7.9 2026-10-01 causa raiz camara (loadLibrary filament-utils-jni, lib SHARED separada verificada en filament@v1.54.5 + BUILD-MANIP-OK + rebuild 25m/cooldown2s/sin-gesto + PD-STATE en 3D-EXIT) + decoder endurecido (layouts verificados byte-exactos contra libopenmetaverse PacketsBig.cs/PacketDecoder.cs: offsets correctos, longitudes estrictas 44/60-84-76, rango SL 0..256, PRIMS-HEX, fuera/lenMalo en PRIMS-ESTADO + harness con sinteticos verde) + bump 7.9/70. Offsets/endianness intactos (LE verificado). Resto intacto.
# Historial: v7.8 2026-10-01 diagnostico camara (PD-STATE manip/gestures/touch/eye/tgt+camErr + CAM-REPAIR/REBUILD auto-repair + PRIMS-DRAW xyz+s+groundY/waterY/avatarY + cero catch silenciosos en buildManip/touch/update/getLookAt + bump 7.8/69). Flujo/render/decode/protocolo/chat/UI/deps intactos.
# Historial: v7.7 2026-10-01 mundo navegable (Manipulator ORBIT+GestureDetector 1.54.5 verificados en fuente + suelo y=22 + agua BLEND y=20 + me 1.8m + PRIMS-DRAW + 3D-ESTADO tgt= + bump 7.7/68). Protocolo/chat/UI/deps intactos.
# Historial: v7.5 2026-10-01 foco post-render via chatInput.post tras renderChat/chatLogAdd (IM+local) + version 7.5.
# Historial: v7.4 2026-09-30 PREVIO sin gate <108 (el gate era el silenciador; decode-null descartado) + teclado sigue en pie tras enviar (IM+local) + version 7.4.
# Historial: v7.3 2026-09-30 IM-CORTO-PREVIO en rama rx-unknown + version 7.3. UI 8.0 y protocolo intactos.
# Historial: v7.2 2026-09-30 UI Messenger: lista chats + conversacion + ENVIAR unico + drawer END (abiertos/online-offline/accesos) + cero auto-saltos + version 7.2. Protocolo intacto.
# Historial: v7.1 2026-09-30 typing 41/42 por dedup key fromId|dlg|text + onTyping siempre + IM-CORTO 1 linea + version 7.1. ackPacket 7.0 intacto. Resto intacto.
# Historial: v7.0 2026-09-30 acks ignorados (ackPacket count-first+LE+header unreliable espejo del sim + dedupIm 120s + typing 41 por dedup + previews 80/120 + test ackEspejoSim) + version 7.0. Resto intacto.
# Historial: v6.9 2026-09-30 higiene de log (RX-BURST solo n>=40 + RX-BURST-MAX 10s + ACK-SUM fuera del ring + ring 300 + ringKeep/burstLoud testeados) + version 6.9. Ritmo red intacto.
# Historial: v6.8 2026-09-30 duplicados+huecos (resendChat/resendIm mismo-seq via pendingBytes + drenaje por tick con RX-BURST + rcvbuf 256k + AU throttle 10Hz + tick 20ms + test resendMismoSeq) + version 6.8. Auto, builders, parse, resto intacto.
# Historial: v6.7 2026-09-30 perdida movil (AUTO_RETRY_ENABLED=true 1 auto/seq, timer 8s IM y chat-B resto 20s, drain sin carrera con IM-OFF-DRAIN-ERROR + reintento espejo buddies, test reintentoUnicoGuard actualizado) + version 6.7. Dedup/acks/typing/layouts intactos.
# Historial: v6.6 2026-09-30 sistema IM (RetrieveInstantMessages Low255 + IM-OFF-DRAIN + hilos por amigo + IM-NOLEIDO/HILO + typing 41/42 + visible take500 + UI mundo con tabs LOCAL/IM + joystick compacto) + version 6.6. Login/caps/handshake/AU/EQ/ping/chat/parse/presence/NAME/builders intactos.
# Historial: v6.5 2026-09-30 IM saliente con null (buildIm msg-len+1 + allocate+1, onIm sin t=, IM-RX con t= solo en consola, test buildImLlevaNullEnLen) + version 6.5. Chat local, parse entrante, resto intacto.
# Historial: v6.4 2026-09-30 IM entrante (tryParseIm/imDiag con AgentData-32, remitente=AgentData.AgentID, IM-OTRO con name/text, IM-F1/F2/F3 hex completo, UdpDecodeTest 2 casos) + version 6.4. buildIm, chat local, presence, NAME, decode/dispatch/dedup/UI intactos.
# Historial: v6.3 2026-09-30 nombres+RX (NAME-REQ-ERROR socket-nulo/sin-buddies + NAME-REPLY-ERROR size/count/bounds + NAME-REPLY n= + BUDDY-N en chatRing tras login) + version 6.3. Formato buddy dual-tag, bytes buildNameReq, offsets onNameDatagram, decode/dispatch/dedup/acks/reintentos/UI intactos.
# Historial: v6.2 2026-09-30 amigos reales (buddyIds dual-tag <name>/<key> + BUDDY-RAW si 0 + presencia Online 0x142/Offline 0x143 con AMIGO-ON/OFF/ON-N + dialogo ordenado online primero + NAME-REQ inicial al entrar + IM-PARSE extendido to/sess/name/text) + version 6.2. Parse/builders/dedup/acks/reintentos/caps/EQ/ping intactos.
# Historial: v6.1 2026-09-30 amigos (buddy_id + UUIDNameRequest 235/Reply 236 + boton AMIGOS) + version 6.1. Sin tocar parse/builders ni congelados.
## 5. ESTANDAR DE CAPTURAS PERMANENTES (todo reporte futuro trae estas lineas; ninguna toca protocolo)
# - HISTO-BR: histograma de br del full-walk (contador por codigo skA/skB/skC/skD/skE/skF/skG/skH/hdr40/ilen1/ilenN/ilenX/in30/fix4/fix66/ok; clasifica sin hex dumps). Se emite tras cada PRIMS-ESTADO (PrimDecoder.kt:brHistLine).
# - FULL-MUESTRA rotativa: las 3 primeras + 1 cada 100 fulls con sufijo " rot" (misma sesion larga cubre nuevos ids).
# - TERSE-N: terse-por-id formalizado (n total + 3 primeros id:xyz; el distinto= del SIXTY-OFF sigue como prueba por-id).
# - CAM-CLAMP + CAM-CLAMP2: primero al abrir (spawn sobre suelo) y segundo a los 30s (post-spawn coherente con avatarY).
# - Toda falla de fetch con causa exacta (formato fetch-FAIL/fetch-HTTP con esq+hops+ini+loc; prohibido silenciarla).
# Historial: v7.24 cleartext LL (network_security_config.xml asset-cdn+agni + TexFetch https-primero con cadena ini/loc/hops) + capturas permanentes (HISTO-BR/TERSE-N/FULL-rot/CAM-CLAMP2) + bump 7.24/85. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, camara/terreno/luz/spawn/chat/IM/login/caps/UDP/UI+LE intactos.
# Historial: v7.25 forense-403 (TEX-403 hex64+cabeceras en ambos intentos + reintento sin-guiones + TEX-TABLA comparativa) + histo-fresco (tokens HISTO/TERSEN/ATTN: foto al emitir) + attach (contador attach=N + ATTACH-MUESTRA, ni render ni fuera) + bump 7.25/86. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, cleartext, camara/terreno/luz/spawn/chat/IM/login/caps/UDP/UI+LE intactos.
# Historial: v7.26 mensaje-403 (TEX-403 con msg=texto completo + https forzado en hops asset-cdn + TEX-HOP por hop) + drenaje-justo (extraQ antes que attachMuestra, 1 muestra por resumen) + bump 7.26/87. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, attach, cleartext, camara/terreno/luz/spawn/chat/IM/login/caps/UDP/UI+LE intactos.
# Historial: v7.27 watchdog-camara (GFX-DIAG por resumen + latch GFX-OPEN/GFX-EXIT aunque el renderer este destruido + sesion=fresca/reanudada con circuitoEdadS + touchOjo/frameEdadMs/fase/startOk/initErr) + bump 7.27/88. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, attach, histo, cleartext, TexFetch-120, terreno/luz/spawn/chat/IM/login/caps/UDP/UI+LE intactos.
# Historial: v7.28 hop0-visible (TEX-HOP n=0 FAIL siempre + err0= en TEX-403 + TEX-CAP capHost/esqCap) + suelo-real (groundReal separado del parking -100; DIAG/DRAW reportan groundReal) + bump 7.28/89. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, attach, histo, cleartext, forceHttps, watchdog, terreno/luz/spawn/chat/IM/login/caps/UDP/UI+LE intactos.
# Historial: v7.29 caza-bloqueo (heartbeat UI 2s + UI-DIAG por resumen 10s con view3d/drawer/renderer/service/eq/agent/inWorld/fase/touchEdad/frameEdad + volcado ui-freeze.txt si beat>10s con pickup al arrancar + fix frameEdadMs real con lastFrameMs) + bump 7.29/90. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, attach, histo, cleartext, forceHttps, TexFetch-122, watchdog-3D, terreno/luz/spawn/chat/IM/login/caps/UDP/UI+LE intactos.
# Historial: v7.30 anti-apilado-3D (guarda renderer-vivo + opening3d + start() ignora running + 3D-EXIT siempre + espera superficie real con 3D-SUPERFICIE-0) + watchdog-endurecido (vuelco directo a consola/archivo si beat>10s) + reset PrimDecoder+latches por login + bump 7.30/91. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, attach, histo, cleartext, forceHttps, TexFetch-122, terreno/luz/spawn/chat/IM/login/caps/UDP/UI+LE intactos.
# Historial: v7.31 visible-primero (view VISIBLE antes de medir + post-frame + fallback 1080x2200 con 3D-FALLBACK-METRICS; adios callejon GONE) + matriz-URL (shape sin token + 4 intentos http path/nosep x dash/nodash + TEX-CERT subject/issuer/SANs del https fallido) + bump 7.31/92. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, attach, histo, watchdogs, cleartext, terreno/luz/spawn/chat/IM/login/caps/UDP/UI+LE intactos.
# Historial: v7.32 altura-real (se ocultan chatListScroll/convBar/chatScroll/inputRow al abrir 3D y se restauran al salir + metricas forzadas a start/viewport/manip, prohibido 1x1) + textura-UDP (RequestImage High 8 por circuito + IMAGE-DATA/ImagePacket/NoInDB con TEX-TABLA-UDP) + bump 7.32/93. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, attach, histo, loop-unico, watchdogs, cleartext, terreno/luz/spawn/chat/IM/login/caps/UDP/UI+LE intactos.
# Historial: v7.33 throttle-ui (udpLog solo acumula + consolaSucia; ticker 2s max 1 rebuild; chats fuera de refreshConsole, solo en chatLogAdd/onIm/onTyping/showList/openConversation/drawer; skip rebuild si mismo numero de filas) + abort-1x1 (ABORT-SIN-METRICAS + start con displayMetrics + buildManip aborta si vw/vh<=1) + bump 7.33/94. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, attach, histo, loop-unico, watchdogs, RequestImage-UDP, terreno/luz/spawn/login/caps/UDP+LE intactos.
# Historial: v7.34 ruido-imagen (IMAGE-REQ-SENT id8/bytes/priority/seq + errores ruidosos + UUID-MALO sin ceros + priority 100000 + contadores IMAGE-RX-N/RX-DESCARTADOS en tick10s, ruteo 9/10/86 intacto) + slots-256 (arrays/loop/coerce + RENDER-SLOTS al abrir) + 3D-EXIT una linea + bump 7.34/95. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, attach, histo, loop-unico, throttle-2s, watchdogs, terreno/luz/spawn/chat/IM/login/caps/UDP+LE intactos.
# Historial: v7.35 anti-inanicion (TEX-UUID primero en drenaje f=12 + TEX-ESTADO emit=N + ticker 10s que invoca sendImageReqBody si hay no-enviados + imgReqSent reset por sesion + cita RequestImage Low 8, bytes intactos) + bump 7.35/96. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, attach, histo, loop-unico, throttle-2s, watchdogs, terreno/luz/spawn/chat/IM/login/caps/UDP+LE intactos.
# Regla: envio real nunca atado solo a linea de log estrangulable; todo request con ticker + contador de emision.
# Historial: v7.36 clavos-imagen (trigger UI a IO con src=ui/tick + retry expiry 20s max3 con IMAGE-REQ-RETRY + TX-HEX 64 primera vez + TEX-UUID full con guiones + topes 3->8 + nosep fuera + requestImage intacto) + bump 7.36/97. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, attach, histo, loop-unico, throttle-2s, watchdogs, terreno/luz/spawn/chat/IM/login/caps/UDP+LE intactos.
# Regla: jamas red en hilo UI; todo request sin respuesta lleva retry con expiry; nada de URLs imposibles ni uuids truncados.
# Historial: v7.36 clavos-imagen (trigger UI a IO src ui/tick + retry expiry 20s max3 RETRY + TX-HEX + TEX-UUID full + topes 8 + nosep fuera) + puente-firebase (StreamBridge REST OkHttp: latest 15s + history 5min/3D-EXIT/SUBIR, scrub, toggle STREAM OFF + SUBIR en drawer, streamDev + STREAM-ESTADO en reporte) + bump 7.36/97. R3/copia-fresca/DIRECT/huella, terse, full-walk, SIXTY, attach, histo, loop-unico, throttle-2s, watchdogs, terreno/luz/spawn/chat/IM/login/caps/UDP+LE intactos.
# Regla: red solo en IO (jamas en hilo UI); todo request sin respuesta lleva retry con expiry; scrub antes de subir; nada de URLs imposibles ni uuids truncados.
# Historial: v7.37 oraculo-rx (IMAGE-TEST sintetico 9/10/86 + IMAGE-RAW ante cualquier 9/10/86 + IMAGE-DEST 1x/sesion + texIdsReset por sesion + variante unreliable tras 90s sin DATA, requestImage reliable intacto) + bump 7.37/98. Resto verde + puente STREAM intactos.
# Regla: reset de estaticos por sesion; oraculo-RX antes de culpar al sim.
# Historial: v7.38 totalidad-tex (pendingTexQ cola cap8 1:1 con emit + TEST-SINT-OK al abrir 3D 1x/sesion + nodo session PUT al entrar En el mundo) + bump 7.38/99. Resto verde + puente STREAM intactos.
# Regla: nada truncado en logs; reset de estaticos por sesion; prueba visible en reporte.
# Historial: v7.39 diferencial-formato (RequestImage Low-8->High-8 1B + RX dispatch 9/10 + sondas low8-CTRL/type1 logueadas; H1/H2 falsas; TEX-UUID full exento de scrub + id8; ORACULO pegajoso en reporte; stream auto-on desde boot con snapOrBoot; resets 1x/sesion) + bump 7.39/100. Resto intacto.
# Regla: parcial-no-es-entregado (todo item sin linea visible = no hecho).
# Historial: v7.40 pivote-visible (M1 TEX-UUID directo via onTexLine + texEmitSesion, cola sin alimentar; M2 ViewerAsset en WANT + base /?texture_id= + TEX-CAP2/TEX-HTTP200, https-query/path testigos; M3 projectLabel + overlay YO/AV + escala min 0.5) + bump 7.40/101. Resto intacto.
# Historial: v7.41 mundo-negro (M1 SUN-STATE por apertura sol/sky/ent/via-reflexion + surf WxH, sin mover sol; M2 LABEL-ATTACHED reales con safe-cast, fuera hard-cast) + bump 7.41/102. Resto intacto.
# Regla: log-que-miente = bug (contar reales, no lista) + jamas-cast-de-SurfaceView-a-ViewGroup (safe-cast + parent!=null).
# Historial: v7.42 visor-F1 (teleport Low 62/63/65 + RX 64/66/69/72/73 + reentry + typing TX + btnTP) + bump 7.42/103. Resto intacto.
# Historial: v7.43 2026-10-06 mundo-texturas-1: texList 48->256 + RequestImage cupo 24/lista 256 (wiki RequestImage High/ImageData High; el sim solo manda lo pedido) + SIXTY-OFF throttle 10s + FIJO-PRIMS/TEX/ATTACH en reporte + RX-DESC-TOP + IMAGE-REQ-PEND/CUPO + MESH failIds + bump 7.43/111. Congelados intactos.
# Historial: v7.44 2026-10-06 wire-ids: regla oficial viewer buildMessage (High=1B, Medium=FF+1B, Low=4B FF FF+U16BE) verificada en cable; ImageNotInDatabase llega 4B 0xFFFF0056 y se perdía (ahora normalizado a 86); lista-desc excluye mids ya tratados (11, 0094, 00FE, 00EC/236, 0142/0143, 0056); imgDataOk a minúsculas (ok=0 con 55 completes era bug de mayúsculas); failIds con mensaje; desconocidos mapeados: 14=LogFailedMoneyTransaction, 1D=PlacesQuery, FF11=ViewerEffect, FF0D=AttachedSound, 008C=SimStats, 0096=SimulatorViewerTimeMessage. Bump 7.44/112.
# Historial: v7.45 2026-10-06 reintento-verdad: imgDataOk solo marca completo real (TEX-UDP-ENSAMBLADA o IMAGE-DATA con completo=si; antes cualquier paquete marcaba ok y bloqueaba los reintentos x3/20s); ok ahora = ensamblada o bitmap (imgLista = imgDataOk o ImageAssets.has); MAX_PENDING 32->96 (53 texturas en región normal evictaban a las 4 del terreno, pedidas primero); touchIds protege terreno+cola contra evicción LRU; tick-go incluye terreno; reporte suma TERRAIN-PEND (estado por id de terreno) + IMAGE-PEND-DET + vistas en IMAGE-REQ-PEND. Bump 7.45/113. Congelados intactos.
# Historial: v7.46 2026-10-06 mundo-completo: TerrainComposition 13 bytes desplazado (faltaban SimOwner 16 + IsEstateManager 1; message_template.msg RegionHandshake Low 148: tras SimName van SimOwner, IsEstateManager, WaterHeight, BillableFactor, CacheID; los 8 UUIDs eran basura y el sim jamás los servía); MeshAssets fallback entre LODs medium/high/low/lowest (antes un medium vacío = lod-sin-caras definitivo, ej. a2a889c4); publish 64->256 + PUB pub/recs en reporte; imgNoDb (no-en-db ya no cuenta como vista; TERRAIN-PEND muestra no-existe). Bump 7.46/114. Congelados intactos.
# Historial: v7.47 2026-10-06 censo-diag (solo diagnóstico, cero cambio de conducta): CENSO distinct-ids por familia (terse/full/cached/attach) + capT/capF (recorte silencioso por guard 256/64) + TIPOS (mesh/simple/sintipo/avatar/conTex en vivos); 2 líneas nuevas en reporte. Para decidir si el sim manda ~48 o cientos (parcela rala vs stream incompleto). Bump 7.47/115. Congelados intactos.
# Historial: v7.48 2026-10-06 posicion-real: la cámara y los AgentUpdate vivían anclados en (128,128,25) porque nada leía la posición real (AgentMovementComplete Low 250 trae Position tras AgentID+SessionID, offset 32, y CoarseLocationUpdate Medium 6 trae el índice You en el bloque Index; ambos se ignoraban y el 0xFA caía a desconocidos). Ahora POS-SIM fija px/py/pz al entrar y POS-COARSE lo mantiene con el índice You oficial; AgentUpdate, cámara, publish e interés del sim usan la posición verdadera. Bump 7.48/116. Congelados intactos.
# Historial: v7.49 2026-10-06 pide-cached: las estructuras estáticas jamás aparecían porque ObjectUpdateCached High 14 (solo id+crc, sin geometría) se contaba y se tiraba; el template oficial exige responder RequestMultipleObjects Medium 3 (CacheMissType 0 = no lo tengo). Ahora parseCached encola los IDs desconocidos, AgentLoop drena 64 por paquete (throttle 1s) y UdpCircuit.requestMultipleObjects lo envía fiable + zerocoded (flags 0xC0, zeroEncode espejo de zeroDecode); el sim responde con full y entran por la vía normal. Testigo REQ-MULT en reporte + FIJO-PRIMS-ESTADO. Bump 7.49/117. Congelados intactos.
# Historial: v7.50 2026-10-06 drena-mult: el drenaje solo corría al llegar un mid 14 (74 en 158s → solo 7 paquetes, 448 de 4714 pedidos). Ahora drena con cualquier tráfico de objetos (12/13/14/15, ~20/s) con throttle 500ms y 96 IDs por paquete (~515B, bajo MTU): los ~4600 pendientes salen en ~25s. Verificado en reporte 7.49: recs 91→243, simple 58→212, comp 0→150, paredes beige ya visibles en captura. Bump 7.50/118. Congelados intactos.
# Historial: v7.51 2026-10-06 queda-geometria: el reporte 7.50 (315s) mostró el colapso — FIJO obj=600 pero CENSO/PUB recs=28 y captura con ~50 cajas: el TTL de 300s en publish() borraba la geometría estática que el sim no refresca (solo manda full una vez tras REQ-MULT; los prims no se mueven). Ahora solo expiran avatares (tipo 47); la geometría vive hasta KillObject, reset() (login/tpReentry, verificados) o el tope-600 por distancia. Aclarado también: RX-UNKNOWN/RX-DESC-TOP med:14 es AvatarAnimation High 0x14=20, bien ignorado (no es geometría). Bump 7.51/119. Congelados intactos.
# Historial: v7.52 2026-10-06 pide-tex: con la geometría ya estable (600 recs, captura con muros) el beige dominaba porque 570/600 prims NO TENÍAN texture-UUID (FIJO-TEX sin=570): entraron por terse/comprimido (solo posición) y el drenaje REQ-MULT saltaba los IDs ya conocidos, así que jamás recibían su full con TextureEntry (cadena skipGet verificada fiel a plantilla 3280-3359; put() no borra tex, verificado). Ahora +sweepTexless() encola los sin-textura conocidos (no-avatares) al mismo drenaje 500ms×96, y texList(ax,ay,az) ordena por distancia (antes los 344 más nuevos morían en el take(256) por orden de inserción); cupo RequestImage 24→96 por tick. Bump 7.52/120. Congelados intactos.

# Historial: v7.53 2026-10-06 corrige-escala: los muros de la cabana salian tumbados (lajas beige al ras del suelo) porque las dos ramas no-mesh del renderer escalaban (sx,sy,sz) sobre ejes (x=Este,y=Arriba,z=Norte): el alto SL-Z caia en profundidad y el fondo SL-Y en vertical; la rama mesh ya usaba (sx,sz,sy) y por eso el sofa si se veia bien. Ahora las tres ramas usan (sx,sz,sy); avatar capsula intacto. Bump 7.53/121. Congelados intactos.
