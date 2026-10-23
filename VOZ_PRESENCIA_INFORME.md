# Participantes visibles antes de unirse a voz

Estado: COMPLETADO. Corrección limitada a presencia de salas.

## Causa y solución

`VoiceRoom` llenaba su lista únicamente al recibir `VOICE_JOINED` o eventos de señalización de una llamada activa. Quien solo abría la sala no recibía esa información.

Se reutiliza el registro en memoria de `ProjectVoiceWebSocketHandler`, que ya controla miembros, mute, desconexiones y capacidad. La nueva consulta autenticada `GET /api/projects/{projectId}/chat/voice-rooms/{roomId}/participants` devuelve su snapshot completo. Las entradas, salidas, cierres y cambios de mute publican `VOICE_ROOM_PRESENCE` por el WebSocket de chat del proyecto existente.

La interfaz consulta al seleccionar una sala y al reconectar el chat. Mientras recupera presencia muestra un estado de consulta, evitando afirmar un cero falso. Las revisiones y el identificador del servidor evitan aplicar respuestas atrasadas; al desconectar se descarta el snapshot observado y se recupera desde el servidor. No se abre señalización ni se pide micrófono al observar.

Cada consulta valida membresía JWT y pertenencia de la sala al proyecto. Cada difusión vuelve a validar el acceso del destinatario. No se incluyen SDP, ICE, credenciales ni conversaciones privadas.

## Archivos de este cambio

- Backend: `ProjectVoiceWebSocketHandler.java`, `ProjectChatWebSocketHandler.java`, `ProjectChatController.java` y nuevo `VoiceRoomPresenceChanged.java`, bajo `backend/src/main/java/bo/edu/caseapp/chat/`.
- Frontend: `frontend/src/uml/VoiceRoom.tsx` y `frontend/src/uml/LiveTeamChatPanel.tsx`.
- Pruebas: `backend/src/test/java/bo/edu/caseapp/project/ProjectVoiceWebSocketHandlerTest.java`, `ProjectChatWebSocketHandlerTest.java` y nuevo `frontend/voice-presence-browser-check.mjs`.
- Evidencia: `docs/evidencias/voz-presencia/01-carlos-observa-raul.png`.
- Sin migraciones ni cambios de esquema.

## Resultados ejecutados

- Backend: `mvn -B -Dtest='*Test' test`, dentro de la imagen Maven temporal: **154 pruebas, 0 fallos, 0 errores, 0 omitidas**.
- Frontend: `npm.cmd test`: **84 aprobadas**.
- TypeScript: `tsc --noEmit`: aprobado.
- Builds de producción backend y frontend mediante Docker: aprobados. Vite mantiene su advertencia existente de bundle mayor de 500 kB.
- `node voice-presence-browser-check.mjs`: aprobado con **dos procesos Edge y perfiles independientes**, usuarios Raúl/Carlos, PostgreSQL temporal, JWT/HTTP/WebSocket y WebRTC reales. No hay mocks de red ni de presencia.

La prueba de navegador verificó: Raúl entra; Carlos observa **1/6** y su nombre; Raúl sale **0/6**; vuelve **1/6**; Carlos entra y ambos muestran **2/6**. También verificó reconexión del chat, recarga de Carlos, cambio de sala y desconexión/reconexión de señalización. Usuarios externos y consultas a proyectos ajenos fueron rechazados.

Mientras observaba, Carlos tuvo **0 llamadas a getUserMedia, 0 peers y 0 conexiones WebSocket de voz**. Tras unirse ambos, sus conexiones WebRTC llegaron a `connected`, el mute se sincronizó y se recibieron **1242 bytes de audio RTP** durante la comprobación.

## Alcance de la verificación real

Los navegadores usaron micrófonos sintéticos de Edge. Se verificó transporte real de audio; no se realizó escucha humana ni prueba con micrófonos físicos o redes distintas. La prueba montó los componentes reales del Espacio de Equipo en una página de verificación, contra un backend y PostgreSQL aislados. No utilizó proyectos ni historiales del entorno del usuario.

La aplicación local en 15174 se actualizó reconstruyendo únicamente backend y frontend. PostgreSQL del usuario y sus volúmenes se conservaron.
