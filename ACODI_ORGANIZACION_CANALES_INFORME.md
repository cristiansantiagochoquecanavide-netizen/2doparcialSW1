# Corrección puntual: organización de canales y chats de ACODI

## Resultado

**COMPLETADO**, con los límites de validación indicados al final.

- Dos pestañas superiores: Canales y Chat / Participantes.
- Una navegación lateral con Canales de texto, Canales de voz y Mis chats con ACODI. Creación con `+`; renombrado y eliminación mediante `⋯`.
- Los historiales grupales anteriores siguen accesibles en Canales de texto, identificados con `IA`. Conservan su ámbito público; no se convirtieron en privados.
- Una sola vista de conversación seleccionada, sin selectores redundantes. Se conservan nombres, borradores por conversación, paginación, errores y reintentos existentes.
- Navegación y mensajes con scroll independiente; compositor fijo dentro del panel. Las respuestas extensas se pueden expandir sin modificar el mensaje almacenado ni su fuente de lectura.
- Ampliar el panel conserva estado y WebSocket. Cambiar de proyecto o sesión reinicia el componente para evitar arrastrar información privada.

## Persistencia y seguridad

Migración **V15__team_channel_navigation.sql**: agrega `channel_type` y `archived_at` a `project_chat_channel`, y registra la sala de diseño inicial. Conserva los IDs, mensajes y referencias existentes. No modifica migraciones antiguas, conversaciones, propuestas ni configuraciones de IA.

La eliminación de canales es lógica: dejan de aparecer y sus endpoints de mensajes los rechazan, pero se conservan los registros y referencias. `#general` está protegido por su identificador interno estable, incluso si cambia de nombre. Renombrar/eliminar bloquea la fila durante la transacción para impedir que una edición concurrente reactive un canal eliminado.

Todos los miembros autenticados del proyecto, incluidos lectores, pueden gestionar canales compartidos. Los eventos `PROJECT_CHANNELS_CHANGED` se emiten después del commit por el WebSocket autorizado existente. La reconexión vuelve a consultar el catálogo y los mensajes.

Las salas reutilizan WebRTC y su señalización actual, separada por ID de sala y validada contra el proyecto. Eliminar una sala requiere `confirmDisconnect=true`; se informa y cierra controladamente a sus participantes, sin afectar otras salas. El cliente detiene audio/micrófono y no reintenta entrar a una sala eliminada.

Los chats privados usan los endpoints, propiedad JWT y permisos existentes. No se publica su creación, título, eliminación ni contenido por WebSocket grupal. Eliminar uno exige confirmación en la interfaz y no elimina otras conversaciones. Las propuestas conservan su ámbito y autor propios aunque desaparezca la conversación referenciada.

No se incorporaron llamadas a Groq por crear, seleccionar, renombrar, eliminar, expandir o redimensionar. Se reutilizan el envío a ACODI, memoria, síntesis de voz y propuestas existentes. Si se elimina diseño-uml, las recomendaciones usan el canal general disponible.

## Endpoints incorporados

Bajo `/api/projects/{projectId}/chat`:

- `POST /channels`, `PATCH /channels/{channelId}`, `DELETE /channels/{channelId}`.
- `GET /voice-rooms`, `POST /voice-rooms`, `PATCH /voice-rooms/{channelId}`, `DELETE /voice-rooms/{channelId}?confirmDisconnect=true`.

Las conversaciones privadas reutilizan `/api/projects/{projectId}/ai/conversations`; no se duplicó su API.

## Archivos

- Backend: `chat/ProjectChatController.java`, `ProjectChatService.java`, `ProjectChatChannel.java`, `ProjectChatChannelRepository.java`, `ChatRequests.java`, `ProjectChatWebSocketHandler.java`, `ProjectVoiceWebSocketHandler.java` y nuevo `ProjectChannelsChanged.java`.
- Migración: `backend/src/main/resources/db/migration/V15__team_channel_navigation.sql`.
- Frontend: `LiveTeamChatPanel.tsx`, `AiConversationsPanel.tsx`, `AcodiChannelMessage.tsx`, `VoiceRoom.tsx`, `WorkspaceChrome.tsx`, `team-collaboration.css`; nuevos `TeamChannelNavigation.tsx` y `ExpandableMessage.tsx`, bajo `frontend/src/uml`.
- Pruebas: `AcodiChannelsPostgresIT.java`, `ProjectVoiceWebSocketHandlerTest.java`, `acodiChannelClient.test.mjs`, `acodiChatIntegration.test.mjs`, nuevo `teamNavigation.test.mjs` y `frontend/acodi-browser-check.mjs`.
- Evidencia: `docs/evidencias/acodi-canales/01-navegacion-unificada.png` y su README.

## Pruebas ejecutadas

| Verificación | Resultado final |
|---|---|
| Backend `mvn -Dtest=*Test,*PostgresIT test`, con configuración explícita de BD aislada y trabajadores pausados | **226/226**, cero fallos, errores u omitidas |
| Suites PostgreSQL incluidas en ese total | **75** pruebas; HTTP/JWT y dos usuarios WebSocket reales donde corresponde |
| Frontend `npm test` | **84/84**, cero fallos u omitidas |
| `npm run build` — TypeScript y Vite | Correcto |
| Build de contenedores con Java 21 y frontend | Correcto |
| Edge `node acodi-browser-check.mjs` | Correcto: CRUD, cancelación/confirmación, privacidad visual, borradores, ampliación sin reconectar, 280/340/480 px, 55 respuestas extensas, scroll independiente y reconexión |
| Edge `node canvas-proposal-browser-check.mjs` | Correcto: overlay completo, aprobación/rechazo de relación, preview privado, centrado y conflicto 409 |

Las nuevas integraciones prueban gestión por lectores, protección de general, conservación de mensajes anteriores, aislamiento por proyecto, eventos para dos miembros, dos historiales privados separados, rechazo del propietario del proyecto al chat privado ajeno, aislamiento entre salas del mismo proyecto y desconexión de la sala eliminada. Las operaciones de administración verifican ausencia de interacción con el proveedor IA.

La primera ejecución encontró interferencias de trabajadores de contextos de prueba y una suite antigua sin BD explícita. Se repitió con PostgreSQL temporal en `55439`, importación de `.env` deshabilitada y trabajadores pausados. También se corrigió una expectativa de prueba: la configuración de seguridad existente rechaza con 403 las solicitudes sin JWT. El resultado final indicado arriba corresponde a la ejecución completa posterior.

Logs finales, ignorados por Git: `backend/target/team-navigation-backend-verified.log`, `team-navigation-frontend-final.log`, `team-navigation-browser-final.log`, `team-navigation-canvas.log`, `team-navigation-build-final.log` y `team-navigation-containers.log`.

## Entorno local

Backend y frontend reconstruidos y reiniciados. Frontend **http://localhost:15174** con HTTP 200; API **http://localhost:18081/api/health** con `status=UP`, `database=UP`. Preflight CORS desde `http://localhost:15174`: HTTP 200 y origen permitido correcto. V15 aplicada (`15|t`).

Verificación antes/después: **42** mensajes públicos, **20** mensajes IA, **4** conversaciones, **16** propuestas, **6** elementos UML y **5** relaciones; los conteos se mantuvieron iguales. Respaldo previo ignorado por Git: `backend/target/local-backups/before-team-navigation-v15-20261023.dump`. El PostgreSQL temporal de pruebas se detuvo.

El `.env` raíz conserva el puerto 15173, ocupado por otro proyecto. Para este entorno se aplicaron los siguientes valores únicamente al proceso de Compose, sin editar archivos de credenciales:

```powershell
$env:BACKEND_PORT='18081'
$env:FRONTEND_PORT='15174'
$env:CORS_ALLOWED_ORIGINS='http://localhost:15173,http://localhost:15174,http://127.0.0.1:15174'
docker compose -p proyecto-case-pruebas up -d --no-deps backend frontend
```

## Límites

La captura y las pruebas de Edge usan componentes reales con HTTP/WebSocket/TTS simulados y datos sintéticos. La persistencia, autorización y señalización se verifican por separado con PostgreSQL y WebSocket reales. Sigue pendiente la escucha manual de audio WebRTC entre dos personas y la revisión visual del equipo con sus datos habituales.

No se realizaron llamadas reales a Groq durante las pruebas. Persiste la advertencia previa de Vite sobre el bundle mayor de 500 kB. Los cambios quedan locales para revisión.
