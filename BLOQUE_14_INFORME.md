# Bloque 14 — Análisis UML proactivo

Fecha: 21 de octubre de 2026, America/La_Paz.

**Estado: COMPLETADO en implementación y pruebas automatizadas.** La aceptación manual en navegadores, el micrófono físico y la calidad de las respuestas del modelo real siguen pendientes. No se inició el Bloque 15.

## Arquitectura y activación

`UmlService.reserveNextRevision` es el punto común auditado de reserva de revisiones. Antes de la mutación registra, con la identidad JWT del desarrollador, un trabajo en `ai_analysis_job`, dentro de la misma transacción del guardado. Si hay rollback o conflicto 409 también se revierte el trabajo. Otra conexión PostgreSQL no puede reclamarlo antes del commit.

Las rutas cubiertas son guardado y eliminación de elementos/relaciones, multiplicidades, metadatos del diagrama, posiciones, importación XMI/JSON, importación SQL, restauración de versiones y modelado por texto, voz o imagen que utiliza los servicios UML existentes. No se añadieron observadores independientes para cada ruta. Los cambios de contexto confirmado también programan una evaluación sujeta a la política automática del proyecto.

`AiAnalysisQueue` mantiene una fila por diagrama y una secuencia de cambios. Cada modificación pospone su vencimiento, por defecto doce segundos. `AiAnalysisWorker` reclama trabajos mediante `FOR UPDATE SKIP LOCKED`, con un identificador de ejecución y una concesión temporal persistente. Las llamadas al proveedor ocurren fuera de las transacciones de guardado, captura, reserva y publicación.

Los trabajos confirmados permanecen en PostgreSQL. Una ejecución abandonada conserva su reserva de tokens; al caducar su concesión se puede recuperar dentro del máximo de intentos. Se probó reemplazar el trabajador y recuperar ese trabajo persistido; no se simuló un reinicio completo del sistema operativo.

## Semántica, agrupación y resultados obsoletos

`AiSemanticSnapshot` calcula SHA-256 sobre el modelo semántico completo y la memoria confirmada pertinente. Incluye clases, descripciones, estereotipos, atributos, métodos, relaciones y multiplicidades. Excluye posiciones, estilos y UUID internos como definidores de significado; también normaliza el orden de clases y miembros.

Una importación o restauración con UUID nuevos puede ser semánticamente equivalente. El fingerprint completo se conserva localmente; no depende del recorte del contexto enviado a Groq. Los movimientos pueden provocar una comparación local al agruparse las escrituras, pero no una llamada al proveedor por sí solos.

El trabajador captura una revisión y su contexto autorizado bajo un bloqueo breve del diagrama. El orquestador recibe exactamente esa captura, sin reconstruirla después con otra revisión. Antes de publicar se vuelve a bloquear el diagrama y se compara el estado semántico actual, incluidos los cambios de contexto confirmado. Si cambió, se descarta el resultado y se conserva el trabajo correspondiente a los cambios posteriores.

## Memoria y contexto inicial

Se reutilizan `AiMemoryService`, sus entidades y sus permisos. Se añadió el tipo `SYSTEM_CONTEXT` para que una hipótesis confirmada cuente como propósito conocido, sin exigir rellenar además la descripción general.

Cuando hay evidencia suficiente y falta un propósito confirmado, el resultado estructurado puede proponer una hipótesis. Se guarda separadamente como `INFERRED`, con autor iniciador, fecha y auditoría; no reemplaza contexto confirmado. Se mantiene como máximo una hipótesis pendiente pertinente. Confirmarla o corregirla requiere un PROPIETARIO/EDITOR y la convierte en `CONFIRMED`.

El panel muestra opcionalmente la invitación a explicar el sistema. Se puede omitir y continuar modelando. Los modelos vacíos o con evidencia insuficiente no consumen Groq. El criterio considera contexto confirmado, clases, miembros y relaciones; no depende únicamente de alcanzar tres clases.

## Voz

`AiContextDictation` utiliza `SpeechRecognition`/`webkitSpeechRecognition`, como los comandos existentes del navegador. El usuario debe activar el micrófono, revisar o editar la transcripción y pulsar **Confirmar y guardar como contexto compartido**.

El resultado se guarda mediante el endpoint de memoria y el autor sale de JWT. La transcripción no se envía como orden de modelado ni modifica clases. Se puede descartar. El dictado se detiene al ocultar el panel y se libera al desmontarlo. No se consume ni graba audio del canal WebRTC.

## Groq y validación

Se reutilizan `AiOrchestrationService` y `LlmProvider`; no se añadió un cliente HTTP de Groq. El constructor de contexto conserva el límite configurable y ahora incluye identificadores UML para poder validar los elementos citados.

La solicitud contiene la captura autorizada con memoria confirmada/inferida diferenciada, validadores locales, resumen de cambios agrupados, hasta cinco recomendaciones anteriores del mismo diagrama y restricciones técnicas. No consulta historiales privados. Las instrucciones tratan nombres, descripciones, memorias e importaciones como datos no confiables.

`AiAnalysisResult` exige un objeto JSON, revisión exacta, campos y longitudes válidos, prioridad y categoría permitidas, confianza entre cero y uno e identificadores existentes. Rechaza identificadores inventados, varias recomendaciones en lugar de un objeto y contenido JSON adicional. Una confianza insuficiente o `hasRecommendation=false` no genera publicación.

El resultado distingue sugerencias conceptuales UML de transformaciones relacionales. Las relaciones reconstruidas desde imágenes se tratan como potencialmente inciertas.

## Publicación y deduplicación

`AiConversationStore.publishRecommendation` es una ruta interna del sistema. Crea o reutiliza una conversación GROUP del diagrama, persiste el mensaje AGENT y utiliza `AiConversationMessageCommittedEvent` y el WebSocket autorizado del Bloque 13 después del commit. No publica en conversaciones privadas.

La publicación se dirige a **Equipo** y muestra diagrama, revisión, fecha, explicación, sugerencia y fundamento. El panel permite ampliar detalles, ver los elementos implicados, abrir la conversación grupal y marcar la recomendación como atendida o descartada.

La deduplicación combina categoría, identidad semántica de los elementos, fingerprint de evidencia relevante y una ventana temporal. No compara únicamente textos. Los estados `RESOLVED` y `DISMISSED` se conservan; no se repite una observación con la misma evidencia. Una evidencia distinta puede reconsiderarse una vez superada la ventana configurada. La política es conservadora y puede agrupar observaciones muy parecidas sobre los mismos elementos.

## Presupuesto y ejecución

| Control | Valor inicial |
| --- | --- |
| Inactividad antes de evaluar | 12 segundos |
| Sondeo de trabajos | 2 segundos |
| Llamadas proactivas por proyecto | 8 por hora móvil |
| Presupuesto del proyecto | 100.000 tokens por periodo |
| Reserva para consultas explícitas | 50.000 tokens |
| Cupo de revisiones proactivas | Presupuesto menos reserva explícita |
| Salida por análisis | 700 tokens |
| Intentos máximos | 3 |
| Espera tras fallo transitorio | 60 segundos |
| Pausa del proyecto tras HTTP 429 | 120 segundos |
| Concesión de ejecución | Mayor entre 120 segundos y timeout de Groq + 60 |
| Ventana de supresión reciente | 24 horas |

Los valores iniciales están en `application.yml` y `.env.example`; el `.env` local no se modificó. El endpoint de política permite cambiar, por proyecto, activación, llamadas, periodo, presupuesto y reserva explícita. Solo PROPIETARIO/EDITOR puede hacerlo.

Antes de llamar a Groq se bloquea la política del proyecto y se reserva conservadoramente el coste, incluyendo solicitudes en curso. La estimación usa los bytes UTF-8 del contenido real, margen de protocolo y máximo de salida. El consumo informado sustituye la estimación cuando está completo; de lo contrario se conserva la reserva y se indica la limitación. También se registran latencia, HTTP y resultado.

El cupo se aplica a las revisiones del diagrama, automáticas o solicitadas manualmente; las preguntas explícitas del chat mantienen su servicio y no quedan bloqueadas por ese cupo. La reserva protege una asignación para ellas, pero este contador no pretende medir toda la facturación de la cuenta Groq ni limitar sus consultas explícitas. La edición UML y el chat humano continúan al agotarse el cupo proactivo.

## Persistencia y endpoints

La auditoría confirmó V9 como última migración previa. Se añadió **V10__create_proactive_ai_analysis.sql** con:

- `ai_analysis_policy`: activación, límites, reserva, pausa y autor de cambios.
- `ai_analysis_job`: trabajo agrupado, estado previo, secuencia, vencimiento, concesión, intentos y conversación grupal.
- `ai_analysis_run`: reserva, cargo, uso estimado/real, latencia, HTTP y resultado.
- `ai_recommendation`: resultado, revisión, huellas de deduplicación, estado, mensaje y conversación.
- `ai_analysis_audit`: solicitudes manuales, cambios de política y disposición de recomendaciones.
- Ampliación de las restricciones de memoria para `SYSTEM_CONTEXT` y auditoría `INFER`.

| Método | Endpoint, relativo a `/api/projects/{projectId}/ai/analysis` |
| --- | --- |
| GET | `/diagrams/{diagramId}` — estado, consumo, recomendaciones e hipótesis |
| PATCH | `/policy` — cambiar configuración del proyecto |
| POST | `/diagrams/{diagramId}/review` — solicitar evaluación agrupada; HTTP 202 |
| PATCH | `/diagrams/{diagramId}/recommendations/{recommendationId}` — RESOLVED o DISMISSED |

La lectura requiere membresía y pertenencia del diagrama al proyecto. Las acciones requieren permiso de edición. El remitente del agente no se acepta desde el cuerpo de una solicitud de chat.

## Correcciones necesarias del Bloque 13

La integración real encontró un fallo al crear `ProjectAiMemory` mediante merge con una identidad compartida. Se corrigió con `@MapsId` y el ciclo de vida `Persistable` para distinguir entidades nuevas. Se configuró `search_path` de las conexiones JDBC a `case_tool`, coherente con JPA/Flyway y con los SQL de chat y memoria.

También se corrigió el caso del orquestador para conversaciones sin diagrama y el reconocimiento del rol PROPIETARIO en el editor frontend. Cambiar entre Inspector, canales y agente conserva el panel y sus conexiones; ocultarlo no crea otra conexión WebSocket ni desconecta WebRTC. Se protegieron respuestas de historial retrasadas frente al cambio de conversación.

## Archivos

Nuevos en `backend/src/main/java/bo/edu/caseapp/ai/`:

- `AiAnalysisProperties.java`, `AiSemanticSnapshot.java`, `AiAnalysisQueue.java`.
- `AiAnalysisResult.java`, `AiAnalysisStore.java`, `AiAnalysisWorker.java`, `AiAnalysisController.java`.

Modificados para integrar el bloque:

- `uml/UmlService.java`.
- `ai/AiContextService.java`, `UmlAiContext.java`, `AiOrchestrationService.java`, `AiOperation.java`.
- `ai/AiMemoryService.java`, `AiMemoryEntryType.java`, `ProjectAiMemory.java`, `AiConversationStore.java`.
- `backend/src/main/resources/application.yml`, `backend/.env.example` y la nueva migración V10.

Nuevos en `frontend/src/uml/`:

- `AiProactivePanel.tsx`, `AiContextDictation.tsx`.
- `contextDictation.mjs`, `contextDictation.d.mts`, `contextDictation.test.mjs`.
- `agentAnalysisClient.mjs`, `agentAnalysisClient.d.mts`, `agentAnalysisClient.test.mjs`.

Modificados: `AiConversationsPanel.tsx`, `LiveTeamChatPanel.tsx`, `WorkspaceChrome.tsx`, `team-collaboration.css` y `frontend/package.json`.

Pruebas backend nuevas: `AiSemanticSnapshotTest.java`, `AiAnalysisResultTest.java`, `AiAnalysisPostgresIT.java`. Se conservaron los cambios locales anteriores de los Bloques 10–13.

## Pruebas ejecutadas

**Backend: 154 pruebas aprobadas, cero fallos, errores u omisiones.** Corresponden a 135 pruebas sin PostgreSQL y 19 de integración con PostgreSQL real: 16 de `AiAnalysisPostgresIT` y las 3 existentes de `UmlOptimisticConcurrencyPostgresIT`.

Se ejecutó la selección completa `*Test,AiAnalysisPostgresIT,UmlOptimisticConcurrencyPostgresIT`. Incluye las suites existentes de UML, Groq simulado, chat, señalización de voz, privacidad, concurrencia e interoperabilidad. La última ejecución completa incluyó el caso que impide reiniciar los intentos por movimientos visuales durante errores del proveedor. Resultado Maven: BUILD SUCCESS.

La base fue un contenedor PostgreSQL 16 temporal, `case_block14_test`, en el puerto local 55439, sin volumen de la aplicación. No se usó la base habitual del proyecto. Flyway y la validación de esquema Hibernate arrancaron correctamente. El historial de PostgreSQL confirmó V8, V9 y V10 con `success=true`.

Se verificaron con PostgreSQL real:

- Trabajo no visible antes del commit, rollback y conflicto de revisión.
- Agrupación de cambios y ausencia de llamadas por movimiento/estilo.
- JSON importado como evaluación global, hipótesis separada y confirmación sin sobrescritura.
- Aplicación del modelo confirmado de una imagen y restauración equivalente con UUID nuevos.
- Descarte de respuestas obsoletas.
- Reserva concurrente de tokens entre diagramas y exclusión de trabajadores del mismo diagrama.
- Cuota de llamadas, permisos LECTOR, pausa 429 e intentos limitados ante 503.
- Movimientos visuales durante fallos no reinician el límite de tres intentos; los cambios semánticos nuevos pueden programar su propia evaluación.
- Desactivación automática, revisión manual, falta de evidencia y resultado sin recomendación.
- Recuperación por otro trabajador y conservación del cargo de una ejecución abandonada.
- Supresión de una recomendación descartada y estimación conservadora ante uso no informado.
- Dos conexiones WebSocket Java reales, autenticadas con usuarios distintos, reciben la recomendación después del commit.
- Un tercero no autorizado no puede conectar; un chat privado ajeno no puede leerse ni aparece en el prompt grupal.
- Una solicitud HTTP con JWT que intenta enviar `senderType=AGENT` persiste un mensaje USER atribuido al usuario autenticado.

**Groq fue simulado en todas estas pruebas.** No se consumieron tokens reales ni se verificó disponibilidad/calidad del modelo configurado. La aplicación no se probó publicando en un navegador real.

**Frontend: 17 pruebas aprobadas**, incluyendo 9 de la cola offline, 4 del dictado y 4 de permisos/solicitudes del agente. Se ejecutaron `tsc --noEmit`, `vite build --configLoader runner` y `npm test`. El build pasó con la advertencia de paquete JavaScript mayor de 500 kB. Estas pruebas cubren controladores y clientes; no sustituyen una prueba DOM o visual integral.

Se utilizó Java 23 disponible en la máquina, compilando con `release 21` del proyecto. No se ejecutó la suite en una JVM Java 21 separada.

## Pendientes y límites de aceptación

La implementación está completa; la comprobación manual final debe incluir dictado con micrófono físico, publicación y controles en dos navegadores, continuidad de audio WebRTC, reconexión y calidad de recomendaciones de Groq real. La prueba de imagen utilizó un modelo ya interpretado y confirmado, sin demostrar precisión del reconocimiento visual. La cobertura XMI conserva sus pruebas existentes y la activación común auditada; la integración masiva nueva se comprobó con JSON, aplicación de imagen y restauración.

Se conservan los pendientes solicitados:

- PENDIENTE-01: comandos por voz para modificar atributos.
- PENDIENTE-02: precisión de reconstrucción UML desde imágenes.
- PENDIENTE-03: representación y tratamiento de clases de asociación UML.
- PENDIENTE-04: integración del chat con PostgreSQL y dos usuarios; en este bloque se verificó la publicación del agente a dos conexiones reales, queda la demostración con navegadores.
- PENDIENTE-05: audio bidireccional real WebRTC.
- PENDIENTE-06: revisión visual integral del espacio colaborativo.
- PENDIENTE-07: validación integral de memoria y conversaciones del Bloque 13; se cubrieron persistencia, confirmación y privacidad en PostgreSQL, queda la revisión manual y el reinicio completo del servidor.
- PENDIENTE-08: V8/V9 verificadas con PostgreSQL real aislado; queda confirmar su aplicación en el entorno habitual antes de la demostración.

No se modificaron los quince casos de uso académicos. No se implementó aplicación de propuestas UML, análisis de audio WebRTC, biometría ni voz hablada del agente. Las recomendaciones son información revisable y los estados atendida/descartada no ejecutan mutaciones UML.

Los cambios están en el workspace. La nueva versión no se desplegó ni se aplicó V10 a la base habitual. Se espera la revisión del informe antes de iniciar el Bloque 15.
