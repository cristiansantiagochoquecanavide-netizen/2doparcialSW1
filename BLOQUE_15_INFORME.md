# Bloque 15 — Propuestas UML y aplicación controlada

## 1. Estado final

**COMPLETADO en implementación y pruebas automatizadas.** Pendientes de validación: Groq real, revisión visual y operación conjunta en navegadores. Esta entrega no declara esas verificaciones realizadas.

Resultado final: **184 pruebas backend y 30 frontend aprobadas**, cero fallos, errores u omisiones. TypeScript y build frontend correctos. De las pruebas backend, **36 usan PostgreSQL real**. El proveedor Groq se simula en las pruebas repetibles.

Los cambios quedan en el workspace. No se reconstruyeron los contenedores habituales ni se aplicó V11 a su base de datos. No se inició el Bloque 16. Se espera revisión de este informe.

## 2. Auditoría inicial

Se leyó `.agents/skills/segundo-parcial-case-colaborativo/SKILL.md` y se inspeccionaron los servicios UML, propiedades de atributos/métodos, relaciones, revisiones, bloqueos, validación, versiones, WebSocket, orquestador, memoria, conversaciones y recomendaciones existentes.

Hallazgos reutilizados:

- `UmlService` concentra creación, actualización y eliminación; atributos y métodos pertenecen a `propiedadesJson` de la clase.
- La revisión se reserva mediante el CAS existente en `reserveNextRevision`; las notificaciones se registran después del commit.
- `ProjectService` determina pertenencia y permiso de edición. El usuario se obtiene de JWT.
- `VersionService.createVersion` permite conservar el estado previo sin implementar otro historial.
- `AiContextService`, `AiOrchestrationService` y `LlmProvider` ya construyen contexto autorizado y ejecutan llamadas externas.
- `AiConversationStore` conserva autoría, ámbitos y publicaciones grupales después del commit. Las conversaciones privadas no se publican.
- Las recomendaciones del Bloque 14 tienen proyecto, diagrama, revisión y conversación de origen.
- La última migración disponible era V10. Se añadió V11 sin cambiar migraciones anteriores.
- El lienzo recibe eventos del diagrama y vuelve a consultar su estado; conserva el tratamiento existente de la cola offline.

Los cambios locales anteriores de los Bloques 10–14 se conservaron.

## 3. Arquitectura

Flujo de preparación:

1. Una instrucción explícita o la selección humana de una recomendación inicia la solicitud.
2. `UmlProposalStore.begin` valida permisos y ámbito, deduplica la clave y captura una revisión/contexto coherentes en una transacción corta.
3. `UmlProposalService` llama al orquestador fuera de la transacción de persistencia.
4. `UmlProposalEngine` interpreta el JSON como datos, resuelve referencias contra el diagrama y proyecta el cambio sin escribir UML.
5. Se guardan el plan y el parche exacto de vista previa, con UUID asignados por el servidor.
6. La respuesta de conversación invita a revisar y decidir; no aplica cambios.

Flujo de decisión:

1. La vista previa recupera el parche persistido mediante GET.
2. Aplicar requiere un POST explícito con revisión base, clave de solicitud y confirmación adicional si hay eliminaciones.
3. `UmlProposalExecutor` vuelve a validar autorización, privacidad, bloqueo, estado, revisión y parche.
4. Guarda una versión previa y ejecuta el lote por los métodos de negocio existentes, dentro de una sola transacción.
5. Confirma la decisión y publica un único evento UML después del commit.
6. Si la transacción falla, `UmlProposalApplication` registra el resultado mediante `UmlProposalDecisions` en una transacción posterior al rollback.

No hay cliente HTTP de IA adicional ni escrituras del agente sobre las tablas UML mediante SQL.

## 4. Archivos creados y modificados

Nuevos en `backend/src/main/java/bo/edu/caseapp/ai/`:

- `UmlProposalContract.java`: operaciones, plan, cambios, vista previa y estados.
- `UmlProposalEngine.java`: validación y proyección local.
- `UmlProposalStore.java`: solicitudes, propuestas, permisos y auditoría persistentes.
- `UmlProposalService.java`: generación y regeneración por el orquestador existente.
- `UmlProposalExecutor.java`: aplicación transaccional.
- `UmlProposalApplication.java`: separación entre rollback y registro de fallo.
- `UmlProposalDecisions.java`: rechazo y decisiones de conflicto/fallo.
- `UmlProposalIntent.java`: clasificación conservadora de instrucciones y decisiones.
- `UmlProposalController.java`: endpoints autenticados.

Backend modificado en este bloque:

- `uml/UmlService.java`: lote con un CAS y una notificación posterior al commit.
- `ai/UmlAiContext.java`, `AiContextService.java`: IDs reales de atributos y métodos en el contexto.
- `ai/AiOperation.java`, `AiOrchestrationService.java`: operación de propuesta y límite de salida independiente.
- `ai/AiConversationService.java`: preparación desde instrucciones explícitas; decisiones textuales remiten a la vista previa.
- `ai/AiConversationStore.java`: notificación de decisiones en la conversación de origen, respetando su ámbito.
- `backend/src/main/resources/application.yml`, `backend/.env.example`: `AI_MAX_OUTPUT_TOKENS_PROPOSAL=1800`.

Nuevos en `frontend/src/uml/`:

- `UmlProposalsPanel.tsx`: solicitudes, lista, vista previa y decisiones.
- `UmlProposalChanges.tsx`: representación React del parche exacto.
- `umlProposalClient.mjs`, `umlProposalClient.d.mts`: cliente tipado y controles de aplicación.
- `umlProposalClient.test.mjs`: pruebas de cliente, renderizado y límites de integración.

Frontend modificado: `AiConversationsPanel.tsx`, `AiProactivePanel.tsx`, `team-collaboration.css`.

Pruebas backend nuevas: `UmlProposalEngineTest.java`, `UmlProposalPostgresIT.java`.

Otros archivos nuevos: migración V11 y este informe. El `.env` con credenciales no se modificó en este bloque.

## 5. Persistencia y Flyway

Migración: `backend/src/main/resources/db/migration/V11__create_uml_ai_proposals.sql`.

| Tabla | Información |
| --- | --- |
| `ai_proposal_request` | Usuario, proyecto/diagrama, instrucción, fuentes, ámbito, clave idempotente, hash, estado de generación y vencimiento |
| `ai_uml_proposal` | Plan, parche exacto, solicitante, fuente, revisión base, estado, decisor, fechas, versión previa, revisión aplicada, uso y latencia |
| `ai_proposal_audit` | Autor, acción, resultado y fecha de preparación/decisión |

Límites: instrucción de 8.000 caracteres, descripción de 2.000, plan de 64 KiB y vista previa de 128 KiB. La base también aplica límites de tamaño y estados permitidos. Las propuestas rechazadas y fallidas se conservan; la eliminación del proyecto/diagrama elimina sus registros dependientes.

Flyway arrancó y Hibernate validó el esquema en PostgreSQL 16 aislado. Consulta de `case_tool.flyway_schema_history`: V8, V9, V10 y V11 con `success=true`. No constituye validación de migraciones sobre la instancia habitual.

## 6. Contrato JSON

Ejemplo de salida solicitada al modelo:

```json
{
  "description": "Agregar Pago vinculado con Venta",
  "clarification": null,
  "operations": [
    {"type": "CREATE_CLASS", "ref": "new:pago", "name": "Pago", "classType": "CLASE"},
    {"type": "ADD_ATTRIBUTE", "target": "new:pago", "name": "monto", "dataType": "BigDecimal", "visibility": "-"},
    {"type": "CREATE_RELATION", "ref": "new:venta-pago", "source": "UUID_REAL_DE_VENTA", "destination": "new:pago", "relationType": "ASOCIACION", "sourceMultiplicity": "1", "targetMultiplicity": "0..*"}
  ]
}
```

`UUID_REAL_DE_VENTA` es un marcador explicativo; el servidor exige el ID real del diagrama. Las referencias `new:...` solo resuelven elementos declarados previamente y sus UUID definitivos se guardan en la vista previa.

Operaciones soportadas:

- `CREATE_CLASS`, `UPDATE_CLASS`, `DELETE_CLASS`.
- `ADD_ATTRIBUTE`, `UPDATE_ATTRIBUTE`, `DELETE_ATTRIBUTE`.
- `ADD_METHOD`, `UPDATE_METHOD`, `DELETE_METHOD`.
- `CREATE_RELATION`, `UPDATE_RELATION`, `DELETE_RELATION`, `UPDATE_MULTIPLICITY`.

Editar/eliminar un miembro requiere su `memberId` real y la clase correspondiente. Los tipos son los enums UML actuales. Las multiplicidades se validan localmente, incluidos límites numéricos y rangos invertidos. Una aclaración tiene `operations=[]`; no puede contener simultáneamente operaciones ejecutables.

## 7. Estados

| Origen | Acción | Destino |
| --- | --- | --- |
| Generación validada | Preparar cambios | `PENDIENTE` |
| Solicitud ambigua | Pedir aclaración sin cambios | `ACLARACION` |
| `PENDIENTE` | Aplicación confirmada | `APLICADA` |
| `PENDIENTE` | Revisión incompatible | `CONFLICTO` y HTTP 409 |
| `PENDIENTE` | Fallo de ejecución | `FALLIDA` tras rollback |
| Pendiente/aclaración/conflicto/fallida | Rechazar | `RECHAZADA` |
| `APLICADA` | Repetir aplicación | Devuelve el resultado guardado sin ejecutar otra vez |
| `RECHAZADA` | Repetir rechazo | Devuelve el resultado guardado |

Falta de permiso, bloqueo o ausencia de confirmación destructiva no convierte la propuesta en aplicada ni consume su estado pendiente. Regenerar crea otra propuesta con el contexto actual y conserva la original.

No se necesita un estado visible `APLICANDO`: el bloqueo de fila y la transacción protegen la ejecución; el estado aplicado solo resulta visible al confirmar.

## 8. Endpoints

Base: `/api/projects/{projectId}/diagrams/{diagramId}/ai/proposals`.

| Método/ruta relativa | Función |
| --- | --- |
| `GET` | Listar hasta 50 propuestas autorizadas; filtro opcional `conversationId` |
| `POST` | Preparar: `clientRequestId`, `instruction`, `conversationId` y/o `recommendationId` |
| `GET /requests/{id}` | Consultar la generación propia sin repetir Groq |
| `GET /{id}` | Recuperar vista previa, estado y autoría autorizados |
| `POST /{id}/apply` | Aplicar: `clientRequestId`, `expectedRevision`, `confirmDestructive` |
| `POST /{id}/reject` | Rechazar conservando auditoría |
| `POST /{id}/regenerate` | Generar con una nueva clave y el contexto actual |

Preparación: 200 si está lista, 202 si continúa una solicitud previa y 422 ante un error de generación. Revisión obsoleta: 409. La identidad se obtiene de `Authentication`, no del cuerpo.

Las propuestas sin conversación tienen ámbito grupal. El formulario del panel exige seleccionar una conversación para conservar explícitamente su ámbito, evitando publicar un borrador privado como grupal.

## 9. Validación y autorización

- Se comprueban JWT, membresía, pertenencia del diagrama al proyecto y acceso a la propuesta.
- Solo PROPIETARIO/EDITOR pueden preparar, aplicar o rechazar. LECTOR puede consultar propuestas autorizadas.
- Una propuesta privada solo es accesible para su solicitante; el propietario del proyecto tampoco puede consultarla o aplicarla.
- El cliente no suministra estados aplicados, identidad del agente ni parches a ejecutar. El parche lo produce y valida el servidor.
- JSON malformado, campos desconocidos, claves duplicadas, enums numéricos y operaciones no soportadas se rechazan.
- Se validan referencias, miembros, identificadores temporales, tamaños, identificadores UML y multiplicidades; se ejecuta el validador UML existente.
- Las hipótesis no se convierten en decisiones confirmadas ni las conversaciones privadas alimentan memoria compartida.
- No se ejecuta SQL del modelo. El SQL nuevo usa parámetros y se limita a metadatos de propuestas y bloqueo del diagrama.
- Las API keys no se incluyen en prompts ni respuestas; React escapa el texto no confiable del modelo.

El validador impide nuevos errores UML; no exige corregir todos los errores preexistentes ajenos al cambio. Las advertencias quedan disponibles en la vista previa.

## 10. Vista previa

El panel muestra descripción, ámbito, solicitante, diagrama, revisión base/actual, estado, decisor y revisión aplicada. El modal representa el parche persistido con **antes/después**, IDs, propiedades de atributos/métodos, extremos y multiplicidades de relaciones.

Agregados, modificados y eliminados tienen etiquetas y colores distintos. La eliminación de una clase incluye en la vista previa sus relaciones afectadas. Eliminar atributos/métodos también activa confirmación destructiva aunque la clase permanezca.

Abrir la vista previa vuelve a consultar el estado del servidor y no llama a Groq. Aplicar solo está disponible desde el modal, con permiso y estado válidos. Un conflicto conserva la propuesta y permite regenerar para una revisión nueva; no cambia silenciosamente su base.

El panel se integra en las conversaciones existentes. No desmonta el lienzo ni escribe directamente sus nodos, relaciones o cola offline. El chat humano y la sala de voz conservan su integración previa.

## 11. Ejecución transaccional

La aplicación bloquea el diagrama y después la propuesta, refresca el estado JPA, revisa bloqueos y compara la revisión. Verifica que cada estado anterior del parche coincida con el modelo actual.

La versión previa, la reserva de revisión, las mutaciones UML y la decisión aplicada pertenecen a la misma transacción. El lote reutiliza `createElement`, `updateElement`, `deleteElement`, `createRelation`, `updateRelation` y `deleteRelation`.

Orden: eliminar relaciones afectadas, crear/actualizar clases y miembros, crear/actualizar relaciones, eliminar clases. Se valida de nuevo el resultado. Si falla una operación se revierten también las operaciones anteriores, el incremento de revisión y la versión previa.

La prueba de fallo comprueba que las clases nuevas y la revisión reservada existen dentro de la transacción antes de provocar el error; después verifica su rollback y ausencia de evento UML. El fallo queda auditado en una transacción separada.

## 12. Concurrencia e idempotencia

- Se reutiliza el CAS de revisión existente una vez por lote.
- La revisión base es inmutable. Un cambio posterior de otro usuario provoca HTTP 409 y estado `CONFLICTO`.
- Una propuesta aplicada devuelve su resultado persistido ante repeticiones, incluso concurrentes, sin duplicar clases, relaciones, versiones o auditoría de aplicación.
- Las generaciones se deduplican por proyecto, usuario y `clientRequestId`; el hash impide reutilizar la clave para otra instrucción.
- Una generación en curso tiene un vencimiento de 180 segundos. Consultar/repetir su solicitud no dispara otra llamada externa.
- Un fallo técnico conserva el estado de error. Para volver a consultar al modelo se solicita una nueva preparación/regeneración explícita; el reintento del mismo mensaje no duplica Groq.
- El frontend conserva la clave de generación ante errores de transporte mientras permanezca en la misma conversación.

Un movimiento visual puede incrementar la revisión normal del editor y hacer que una propuesta resulte obsoleta. No se regenera automáticamente: el usuario decide cuándo generar otra.

## 13. Conversaciones y recomendaciones

Las instrucciones imperativas reconocidas en conversaciones grupales/privadas preparan propuestas. Las preguntas y recomendaciones generales conservan el flujo de conversación anterior. La clasificación es conservadora: formulaciones no reconocidas pueden utilizar el formulario explícito «Preparar propuesta».

«Sí», «dale», «aplica» o «rechaza» no ejecutan decisiones mediante texto: la respuesta local dirige al usuario a los botones de la vista previa. Esto evita aprobar un cambio ambiguo o una propuesta distinta de la revisada.

Las respuestas de preparación usan el nombre autenticado. El origen y el ámbito quedan guardados. Al aplicar/rechazar se publica una decisión atribuida al usuario responsable en la conversación original, respetando su privacidad.

«Preparar propuesta para la recomendación» solo genera tras un clic humano. Recupera la recomendación autorizada y captura el modelo actual; informa de la revisión original en una advertencia persistida y en la conversación. No prepara automáticamente todas las recomendaciones.

## 14. WebSocket

Las escrituras internas del lote no difunden eventos individuales. Tras confirmar se publica un único `PROPOSAL_APPLIED` con ID de diagrama y revisión por el WebSocket UML habitual.

Una aplicación privada comparte el cambio UML confirmado, sin publicar texto privado, descripción de la propuesta o historial al canal grupal. Las respuestas/decisiones privadas no se envían por el WebSocket grupal. Los fallos y conflictos no emiten cambios UML.

La integración real verifica recepción después del commit y ausencia de notificación tras rollback. El lienzo conserva su mecanismo existente de actualización y protección de la cola offline.

## 15. Tokens y llamadas externas

- Se reutiliza el contexto semántico autorizado y limitado del Bloque 12, con IDs de miembros para editar por referencia real.
- Se envían contexto, revisión, solicitud/recomendación y contrato; no historiales completos ni chats privados ajenos.
- Máximo de 32 operaciones y 128 elementos en el parche; se exige dividir solicitudes mayores.
- Salida configurable: `AI_MAX_OUTPUT_TOKENS_PROPOSAL`, predeterminado 1800.
- Uso/latencia se guardan en las propuestas válidas y se registran mediante el orquestador cuando el proveedor los informa.
- Preparar es una acción explícita independiente del presupuesto del análisis proactivo.
- Abrir, listar, consultar estado, aplicar y rechazar no llaman al modelo.
- No hay una transacción PostgreSQL abierta durante la llamada externa; la integración verifica esta condición dentro del proveedor simulado.

## 16. Pruebas ejecutadas

### Backend

Última ejecución completa: **184 pruebas, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS**.

| Tipo | Cantidad |
| --- | ---: |
| Sin PostgreSQL: suites existentes y 13 pruebas nuevas de proyección/contrato | 148 |
| PostgreSQL: `AiAnalysisPostgresIT` | 16 |
| PostgreSQL: `UmlOptimisticConcurrencyPostgresIT` | 3 |
| PostgreSQL: `UmlProposalPostgresIT` | 17 |
| Total | 184 |

Se ejecutó Maven con la selección `-Dtest=*Test,AiAnalysisPostgresIT,UmlOptimisticConcurrencyPostgresIT,UmlProposalPostgresIT`, datasource aislado `jdbc:postgresql://localhost:55439/case_block14_test` y polling proactivo de una hora. Evidencia local: `backend/target/block15-full-validation.log` y los informes Surefire. Los avisos de Mockito sobre APIs obsoletas y carga dinámica de agente no impidieron la ejecución.

Los 17 casos PostgreSQL nuevos cubren:

1. Preparación sin mutación y aplicación exacta con atributo, clase, relación, multiplicidad, revisión única y versión previa.
2. Generación duplicada, lectura sin consumo y rechazo de reutilización de clave con otra instrucción.
3. Respuestas malformadas, operaciones no soportadas y referencias inexistentes sin escrituras UML.
4. Permisos HTTP/JWT de LECTOR, externo, falta de JWT y aislamiento entre proyectos.
5. Modificación por otro usuario, HTTP 409, conservación de conflicto y regeneración actualizada.
6. Fallo después de escrituras de clases; rollback de clases/relaciones/revisión/versión y ausencia de evento.
7. Rechazo auditado e idempotente; imposibilidad de aplicar una propuesta rechazada.
8. Confirmación adicional para eliminación de atributo.
9. Privacidad frente al propietario; ausencia de difusión grupal y evento UML sin contenido privado.
10. Respuesta personalizada y evento único observable tras commit.
11. Preparación desde chat; aprobación textual sin ejecución ni llamada adicional a Groq.
12. Bloqueo por otro colaborador y aplicación solo después de liberarlo.
13. Dos usuarios aplicando propuestas distintas sobre la misma revisión: un solo commit.
14. Repetición concurrente de una propuesta: mismo resultado y ninguna duplicación.
15. Prompt autorizado sin el contenido privado de otro usuario.
16. Error del proveedor, conservación de pregunta y reintento técnico sin duplicar generación.
17. Recomendación de revisión anterior: origen conservado, advertencia y contexto actual.

Las pruebas unitarias nuevas cubren también métodos, actualizaciones de clase/relación, cambios de multiplicidad, eliminación en cascada, serialización de la vista previa, límites, aclaraciones, claves JSON duplicadas y enums numéricos.

PostgreSQL fue un contenedor 16 temporal exclusivo, `case-block15-postgres`, sin volumen de la aplicación. El nombre de base `case_block14_test` se reutilizó para ejecutar los tests previos que lo exigen. Los fixtures nuevos verifican ese nombre antes de limpiar datos. Se probaron transacciones/HTTP/WebSocket reales con usuarios distintos, **no dos navegadores**.

Groq se simuló en las integraciones. Los tests existentes del adaptador Groq usan un servidor HTTP simulado. No se consumieron tokens reales ni se comprobó disponibilidad/calidad del modelo remoto.

Java disponible: 23.0.2; compilación `release 21` del proyecto. No se ejecutó en una JVM Java 21 separada.

### Frontend

**30 pruebas aprobadas**, cero fallos u omisiones: 17 existentes y 13 nuevas. Comandos: `npm test`, `tsc --noEmit` y `vite build --configLoader runner` con salida temporal.

Cobertura nueva: GET sin ejecución, filtro de conversación, aprobación con revisión exacta, rechazo, conflicto 409, errores, lectores, confirmación destructiva, estados terminales, representación React del parche, escape de texto no confiable y formulario que exige conversación. Las pruebas de representación compilan los componentes TSX y usan `react-dom/server` con el compilador disponible mediante Vite.

El límite con el lienzo se comprueba mediante una prueba de arquitectura que detecta escrituras directas o importación del lienzo desde el panel. **No equivale a probar gestos, foco, conservación visual del lienzo, reconexión o audio en un navegador.**

Build correcto: 221 módulos; advertencia de paquete JavaScript mayor de 500 kB. No se modificó el empaquetado para resolver esa advertencia dentro de este bloque.

## 17. Riesgos y límites de validación

- Falta probar una generación con Groq real y revisar calidad del JSON producido por el modelo configurado.
- Falta revisión visual y flujo completo con dos navegadores, incluido el lienzo con modificaciones locales pendientes y la continuidad de WebRTC.
- El contexto limitado puede excluir parte de un diagrama grande. No se inventan referencias para compensarlo: la respuesta debe aclararse o rechazarse y la solicitud puede dividirse.
- La lista muestra las 50 propuestas más recientes dentro del filtro autorizado. No se añadió paginación de propuestas; el historial de conversaciones mantiene su paginación anterior.
- La clasificación del chat es conservadora y no intenta resolver aprobaciones ambiguas. El formulario explícito siempre permite solicitar preparación.
- Las propuestas sobreviven entre transacciones y quedan en PostgreSQL; no se ejecutó una demostración manual de reinicio completo de la aplicación.
- V11 se verificó en una base nueva aislada. El despliegue y la aplicación de migraciones en la instancia habitual siguen pendientes.
- Las suites existentes reducen riesgo de regresión en UML, interoperabilidad, generación, cola offline, memoria, chat y señalización de voz; no sustituyen validar todos los casos de uso manualmente.

## 18. Pendientes conservados

- PENDIENTE-01: interpretación de comandos por voz para modificar atributos.
- PENDIENTE-02: precisión de reconstrucción UML desde imágenes.
- PENDIENTE-03: representación y semántica de clases de asociación.
- PENDIENTE-04: validación integral del chat entre dos usuarios en navegadores.
- PENDIENTE-05: prueba real de audio bidireccional WebRTC.
- PENDIENTE-06: comprobación visual del entorno colaborativo.
- PENDIENTE-07: memoria y conversaciones con Groq real.
- PENDIENTE-08: comprobar migraciones V8/V9 y posteriores en la instancia habitual; V8–V11 verificadas aquí en PostgreSQL aislado.
- PENDIENTE-09: análisis proactivo con Groq real.
- PENDIENTE-10: dictado de contexto con micrófono físico.
- PENDIENTE-11: despliegue del Bloque 14 en la instancia habitual; integrar también V11 y esta entrega antes de demostrar el Bloque 15.

No se alteraron las definiciones de los quince casos de uso académicos ni se adelantó la participación hablada del agente.
