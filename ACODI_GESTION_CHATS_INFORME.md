# ACODI: gestión de chats y respuestas breves

## Cambios

- Las conversaciones privadas nuevas reciben el título `New Chat` desde el panel y el backend, sin consultar Groq. Las conversaciones ya existentes conservan su título.
- Se mantiene la administración de conversaciones desde el menú lateral existente: cambiar nombre y eliminar con confirmación; las rutas existentes conservan autorización por propietario. Los canales y salas mantienen sus opciones y protección de `#general`.
- Se ajustó la ubicación del menú contextual para alinearlo al lado derecho y abrir hacia arriba, reduciendo recortes en el scroll lateral.
- Las respuestas libres de ACODI ahora piden 2–4 oraciones y usan límite por defecto de 300 tokens. Las propuestas estructuradas conservan su límite separado de 1800.
- El contexto de las respuestas conversacionales incluye seis mensajes recientes en lugar de diez. Se mantiene intacto el snapshot UML autorizado y la memoria compartida.

## Archivos de este bloque

- `frontend/src/uml/LiveTeamChatPanel.tsx`
- `frontend/src/uml/AiConversationsPanel.tsx`
- `frontend/src/uml/team-collaboration.css`
- `backend/src/main/java/bo/edu/caseapp/ai/AiConversationStore.java`
- `backend/src/main/java/bo/edu/caseapp/ai/AiConversationService.java`
- `backend/src/main/java/bo/edu/caseapp/chat/AcodiWorker.java`
- `backend/src/main/resources/application.yml`
- `backend/.env.example`
- Prueba de título en `backend/src/test/java/bo/edu/caseapp/ai/AiAnalysisPostgresIT.java`
- `frontend/src/uml/teamNavigation.test.mjs`

## Verificación

- Frontend: `npm.cmd test` — 84 pruebas aprobadas.
- TypeScript: `tsc --noEmit` — aprobado.
- Build Vite: transformó 231 módulos, pero no pudo escribir la carpeta de salida por `EPERM` en este entorno.
- Backend: no ejecutado; no hay Maven/wrapper disponible y el acceso al daemon Docker fue denegado.

## Limitaciones

La prueba de título añadida requiere ejecución backend con PostgreSQL. La reducción de contexto conversacional aplica al historial del chat; no se eliminan elementos UML ni reglas del contexto autorizado.
