# ACODI en canales de texto

Fecha: 22 de octubre de 2026. Estado final: **COMPLETADO**, con las verificaciones manuales indicadas al final pendientes.

## Arquitectura y flujo

1. El endpoint existente de chat autentica mediante JWT y verifica proyecto, canal y diagrama. Detecta localmente `@ACODI`, sin distinguir mayúsculas y sin aceptar correos, nombres parciales ni la palabra sin arroba.
2. Persiste el mensaje humano y un trabajo en la misma transacción. Los mensajes normales no crean trabajos ni consultan Groq.
3. `AcodiWorker` reclama el trabajo con bloqueo y lease. Prepara contexto semántico y memoria compartida mediante los servicios existentes y reserva presupuesto en transacciones breves.
4. El orquestador y `LlmProvider` existentes consultan Groq fuera de la transacción. No se creó otro cliente HTTP.
5. Valida la respuesta y la guarda como ACODI en el mismo canal, vinculada al mensaje humano. La personalización utiliza el nombre autenticado, sin una segunda generación.
6. Publica por el WebSocket existente después del commit. REST recupera el historial y estados después de reconectar.

Estados persistidos: PENDIENTE, PROCESANDO, COMPLETADO y FALLIDO. Los trabajos pendientes sobreviven al reinicio; los trabajos interrumpidos cuyo lease vence requieren reintento explícito. Esta decisión evita repetir automáticamente una llamada externa cuyo resultado se desconoce.

## Persistencia y endpoints

Migración **V12__acodi_channel_invocations.sql**, posterior a V11:

- Tabla `acodi_channel_job`: identidad del mensaje, proyecto/canal/diagrama, estado, intentos, lease, clave de reintento, error, respuesta y métricas.
- Referencia única `project_chat_message.canonical_ai_message_id` a la recomendación histórica.
- Índice único de respuesta del agente por mensaje humano.
- Campo `ai_analysis_run.kind` para distinguir PROACTIVE y EXPLICIT en el presupuesto compartido.

Se conserva `POST /api/projects/{projectId}/chat/channels/{channelId}/messages`, con `diagramId` opcional. El servidor comprueba su pertenencia y acceso; si se omite, solo resuelve automáticamente cuando el proyecto tiene un único diagrama.

Nuevo endpoint: `POST /api/projects/{projectId}/chat/channels/{channelId}/messages/{messageId}/acodi/retry`, con `clientRequestId` y `diagramId` opcional. Solo el autor autenticado puede reintentar su consulta. El historial existente incluye estado del trabajo y referencia canónica.

## Seguridad, idempotencia y consumo

- Identidad y membresía obtenidas en servidor; el cliente no asigna el tipo AGENT.
- Revalidación de acceso antes de preparar el contexto y antes de publicar la respuesta.
- Ningún historial privado ni historial completo del canal se incorpora al prompt. Se reutiliza el contexto semántico limitado existente, sin coordenadas, estilos o zoom.
- Datos UML, memoria y consulta tratados como contenido no confiable. Las respuestas se muestran como texto escapado por React.
- No se ejecuta SQL generado ni se modifica el UML mediante esta conversación.
- Idempotencia HTTP por mensaje y clave de reintento; bloqueo SKIP LOCKED, token de ejecución y restricciones únicas para evitar procesamiento/respuestas duplicadas.
- Una generación por intento lógico; después de fallos solo se permiten reintentos explícitos, con espera y máximo configurable de tres intentos.
- Salida predeterminada de 600 tokens, configurable mediante `ACODI_MAX_OUTPUT_TOKENS`; máximo predeterminado de 30 llamadas por proyecto y ventana de presupuesto mediante `ACODI_MAX_CALLS`.
- Reservas conservadoras antes de consultar y conciliación con consumo real, latencia y HTTP cuando están disponibles. Si no hay métricas fiables se conserva el cargo reservado.
- Presupuesto y pausa 429 compartidos con el análisis proactivo. Las consultas explícitas usan la reserva existente; el trabajador proactivo cede cuando hay consultas explícitas pendientes o procesándose.
- La clave permanece en el entorno del backend; no se modificó ni publicó `.env`.

## Interfaz, voz y recomendaciones

ACODI aparece como integrante virtual, con avatar y rol Agente IA. El botón `@ACODI` inserta la mención en el cursor, conserva el texto, enfoca el campo y evita duplicados. Se muestran estados de cola, procesamiento, fallos y reintentos con mensajes comprensibles.

Los borradores se conservan por canal. La composición se adapta a paneles estrechos. Las herramientas de memoria, propuestas y conversaciones privadas permanecen accesibles en **Herramientas ACODI**; se conserva el montaje del lienzo y de la sala WebRTC.

**Escuchar** reutiliza la síntesis local existente, sus controles de detener, volumen y velocidad, sin llamadas adicionales a Groq. Las recomendaciones proactivas se proyectan una sola vez en `#diseño-uml`, conservando el mensaje histórico como registro canónico. El mismo ID canónico evita doble lectura de los eventos histórico y de canal. Abrir el historial no dispara lectura automática.

## Archivos

Rutas relativas al repositorio:

- Nuevos backend: `backend/src/main/java/bo/edu/caseapp/chat/{AcodiMention,AcodiQueue,AcodiStore,AcodiWorker}.java`; migración `backend/src/main/resources/db/migration/V12__acodi_channel_invocations.sql`.
- Chat modificado: `backend/src/main/java/bo/edu/caseapp/chat/{ChatRequests,ChatResponses,ProjectChatController,ProjectChatMessage,ProjectChatService}.java`.
- IA modificada: `backend/src/main/java/bo/edu/caseapp/ai/{AiAnalysisStore,AiAnalysisWorker,AiConversationService,AiConversationStore,AiMemoryService,AiOrchestrationService,UmlProposalService}.java`. Incluye presupuesto, proyección canónica y nombre visible, sin renombrar identificadores históricos.
- Configuración: `backend/src/main/resources/application.yml`, `backend/.env.example`.
- Nuevos frontend: `frontend/src/uml/AcodiChannelMessage.tsx`, `acodiChannelClient.mjs`, `acodiChannelClient.d.mts`, `acodiChannelClient.test.mjs`; `frontend/acodi-browser-check.mjs`.
- Frontend modificado en `frontend/src/uml/`: `LiveTeamChatPanel.tsx`, `AgentSpeechUI.tsx`, `AiConversationsPanel.tsx`, `TeamCollaborationPanel.tsx`, `WorkspaceChrome.tsx`, `agentSpeech.test.mjs`, `team-collaboration.css`.
- Nuevas pruebas backend en `backend/src/test/java/bo/edu/caseapp/project/`: `AcodiMentionTest.java`, `AcodiChannelsPostgresIT.java`, `AcodiRealGroqIT.java`. Usan el paquete Java de chat sin crear una estructura adicional de servicios.

## Pruebas y evidencia

- Pruebas específicas iniciales: **21/21**, incluyendo 18 casos con PostgreSQL real aislado.
- La primera regresión detectó un fallo de orden de eventos de recomendaciones. Se corrigió conservando primero el evento histórico; la repetición de ambas suites afectadas pasó **34/34**.
- Frontend: **59/59** pruebas; TypeScript sin errores. Compilación Vite completada. Persiste la advertencia de tamaño del bundle superior a 500 kB.
- Edge headless con los componentes reales y fixtures HTTP/WebSocket/TTS: controles accesibles a 480, 340 y 280 px; inserción en cursor/foco; envío único; estado pendiente; respuesta por evento; Escuchar; cambio de canal conserva borradores e historial. Esta prueba no demuestra audio físico ni conectividad WebRTC entre personas.
- **Groq real: 1/1**, una única consulta con datos sintéticos y la configuración local. HTTP **200**, estado **COMPLETADO**, respuesta guardada en el canal. Log: `backend/target/acodi-live-groq.log`.
- Regresión completa: se ejecutaron **205 pruebas**; 202 pasaron y tres pruebas antiguas de concurrencia no pudieron iniciar por apuntar a `localhost:5433`, sin servidor. Se repitieron las tres contra el PostgreSQL aislado correcto: **3/3, BUILD SUCCESS**. Resultado consolidado de la regresión: **205/205**. Logs: `backend/target/acodi-final-regression.log` y `backend/target/acodi-concurrency-corrected.log`. No se presenta el primer comando como una ejecución íntegra exitosa.
- Informes XML finales de Surefire: **206 pruebas, cero fallos, cero errores, cero omitidas**, incluyendo la única prueba real de Groq. Desglose: 151 pruebas sin PostgreSQL, 54 de integración PostgreSQL y una adicional con Groq real.
- Contenedores backend y frontend reconstruidos correctamente con Java 21 y Vite; PostgreSQL habitual conservado. Respaldo previo en `backend/target/local-backups/before-acodi-v12-20261022-205806.dump`. V12 aplicada: `12|t` en `case_tool.flyway_schema_history`. Frontend HTTP 200 y `/api/health` devuelve `status=UP`, `database=UP`.

Las pruebas repetibles utilizan un proveedor simulado, PostgreSQL en el puerto 55439 y conexiones WebSocket reales con usuarios distintos. Incluyen privacidad, JWT, rechazo de externos, difusión después del commit, concurrencia, idempotencia, presupuesto, 429, recuperación/reintentos y ausencia de escrituras UML. La persistencia/recuperación de trabajos se comprueba desde la base de datos; no se simula una caída física del proceso durante una llamada real.

Comandos principales, desde backend (Maven del wrapper disponible):

```text
mvn -Dtest=*Test,AiAnalysisPostgresIT,UmlOptimisticConcurrencyPostgresIT,UmlProposalPostgresIT,AcodiChannelsPostgresIT -Dapp.ai.acodi.poll-ms=3600000 -Dapp.ai.proactive.poll-ms=3600000 test
mvn -Dtest=AcodiRealGroqIT -Dacodi.live=true test
```

El primer comando requiere configurar también `spring.datasource.url`, `spring.datasource.username` y `spring.datasource.password` para las pruebas antiguas de concurrencia: usar exclusivamente la base de pruebas del puerto 55439, nunca la base habitual. El segundo comando es optativo, consume una llamada real y necesita ese PostgreSQL de pruebas. No forma parte de la regresión repetible. Frontend: `npm.cmd test`, `npx.cmd tsc --noEmit`, `npm.cmd run build` y `node acodi-browser-check.mjs`.

## Verificaciones pendientes y límites

- Validación manual con dos personas en navegadores: sesión habitual, reconexión, voz física, volumen y calidad de audio WebRTC. Las conexiones WebSocket sí se verificaron automáticamente con PostgreSQL real.
- Evaluación humana de la calidad UML de respuestas variadas; una llamada real mínima no certifica todos los posibles diagramas.
- No se corrigieron los pendientes ajenos al alcance: comandos de voz para ciertos atributos, reconstrucción desde imágenes, clases de asociación y determinadas propuestas con `INVALID_OR_FAILED_GENERATION`.
- No se iniciaron otros bloques ni se publicaron estos cambios en GitHub.

## Revisión local

Disponible en **http://localhost:15174**. Abrir un proyecto y diagrama, entrar en un canal de texto e invocar `@ACODI` o utilizar su botón. Los cambios están en el árbol de trabajo para revisión. No se iniciarán otras correcciones hasta recibirla.
