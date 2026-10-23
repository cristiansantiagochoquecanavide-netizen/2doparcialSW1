# ACODI integrado en el chat

Estado: **COMPLETADO**, con las verificaciones manuales indicadas al final pendientes.

## Cambios

- Eliminada la pestaña «Herramientas ACODI». Quedan **Canales y Chat** y **Participantes**. Historiales grupales y chats privados se abren desde la navegación del chat, con un selector compacto, creación, renombrado, archivo y paginación existentes. Se conservan los borradores al cambiar de vista y la sala WebRTC permanece montada.
- El clasificador reconoce peticiones como «¿Puedes eliminar…?», «Quiero que quites…», «Me podrías borrar…» y las operaciones existentes de clases, atributos, métodos y relaciones. Se retiró la instrucción que redirigía a la pestaña eliminada.
- Las eliminaciones ambiguas piden precisión en el mismo chat, sin generación ni cambios UML. Una eliminación concreta prepara el diff mediante el motor existente, permanece verde en el lienzo hasta decidir y requiere aprobación destructiva explícita. Se mantienen aplicación atómica, permisos, bloqueos, idempotencia y HTTP 409.
- Análisis explícito y respuestas aparecen en el canal. El análisis automático sigue usando la publicación canónica en `#diseño-uml`, sin duplicar generación ni voz.
- Contexto, reglas, restricciones, decisiones, convenciones, hipótesis, configuración, recomendaciones y propuestas anteriores son consultables mediante comandos. Las modificaciones de información compartida requieren una confirmación persistente del autor en el mismo canal. Una confirmación repetida no duplica cambios; una solicitud antigua no sobrescribe contexto actualizado por otra persona.
- Las conversaciones privadas conservan su aislamiento, incluso frente al propietario del proyecto. Sus propuestas pueden revisarse localmente en el lienzo mediante una carga autorizada; esa selección no se publica al equipo. Las escrituras de memoria compartida se solicitan y confirman en canales públicos, nunca se importan automáticamente desde una conversación privada.

## Ejemplos

```text
@ACODI agrega el atributo metodoPago: String a Venta
@ACODI ¿Puedes eliminar la relación entre Venta y Producto?
@ACODI analiza el diagrama
@ACODI revisión global
@ACODI contexto
@ACODI guardar contexto: Sistema de ventas con clientes, productos y pedidos
@ACODI confirmar ID_DE_LA_SOLICITUD
@ACODI guardar regla: Un pedido debe tener un cliente
@ACODI confirmar hipótesis ID_DE_LA_ENTRADA
@ACODI activar análisis automático
@ACODI configurar análisis: presupuesto=100000 reserva=50000 llamadas=5 periodo=3600
@ACODI recomendaciones
@ACODI preparar recomendación ID
@ACODI propuestas
@ACODI ver propuesta ID
@ACODI regenerar propuesta ID
```

`@ACODI ayuda` muestra las operaciones de memoria, configuración y recomendaciones. Las confirmaciones de contexto no aprueban operaciones UML: estas se deciden exclusivamente en la barra del lienzo. Visualizar, consultar memoria, confirmar información, aprobar, rechazar y leer por voz no generan consultas adicionales al LLM.

## Archivos de esta corrección

- Nuevos: `backend/src/main/java/bo/edu/caseapp/chat/AcodiChatActions.java`, `AcodiDeletionClarification.java`; `backend/src/main/resources/db/migration/V14__acodi_chat_actions.sql`.
- Backend modificado, bajo `backend/src/main/java/bo/edu/caseapp/`: `chat/AcodiWorker.java`, `chat/AcodiStore.java`; `ai/UmlProposalIntent.java`, `UmlProposalService.java`, `AiConversationService.java`, `AiConversationStore.java`, `AiConversationResponses.java`.
- Frontend, bajo `frontend/src/uml/`: `LiveTeamChatPanel.tsx`, `AiConversationsPanel.tsx`, `useCanvasProposal.ts`, `UmlCanvas.tsx`, `CanvasProposalBar.tsx`, `canvasProposalPreview.mjs`, `canvasProposalPreview.d.mts`, `team-collaboration.css`; nuevo `acodiChatIntegration.test.mjs`.
- Pruebas ampliadas: `backend/src/test/java/bo/edu/caseapp/ai/UmlProposalEngineTest.java`, `UmlProposalPostgresIT.java`; `frontend/acodi-browser-check.mjs`, `canvas-proposal-browser-check.mjs`.

**V14** incorpora únicamente el registro idempotente de comandos y confirmaciones (`acodi_chat_action`). No se borraron tablas, migraciones, historiales, memorias ni propuestas. La respuesta de conversaciones añade `proposalId` opcional para abrir su vista previa; se conserva el constructor y el contrato anterior de mensajes.

## Pruebas y resultados

| Verificación | Resultado |
|---|---|
| Regresión backend completa | **221/221**, cero fallos/errores/omitidas |
| Confirmaciones repetidas tras ajustar su bloqueo de concurrencia | **3/3** |
| Frontend | **80/80** |
| TypeScript y build Vite | Correctos; 229 módulos |
| Edge: navegación, voz local y controles públicos/privados a 280 px | Correctos |
| Edge: overlay completo, eliminación de relación, aprobación/rechazo y vista privada | Correctos |

La integración utiliza PostgreSQL aislado y dos conexiones WebSocket reales con miembros distintos. Comprueba que ambos reciben preparación y decisión; la eliminación afecta exclusivamente a la relación indicada. Incluye contexto sin confirmar, cancelación, confirmación duplicada, confirmación de hipótesis, rechazo de lectores, protección ante contexto concurrente, análisis en el canal y ausencia de difusión privada.

Edge también verifica que cambiar de privado a grupal no copie el borrador ni muestre el historial privado; al volver se recupera el borrador privado. «Escuchar» sigue funcionando al regresar al canal público. La vista privada compacta conserva sus controles dentro de un panel de 280 píxeles.

Evidencia: `backend/target/acodi-unified-regression.log` y `backend/target/acodi-unified-confirmations-final.log`, ambos **BUILD SUCCESS**. Las pruebas de Edge ejecutan componentes reales con HTTP/WebSocket simulados; la persistencia y autorización se prueban por separado con PostgreSQL. No se realizaron llamadas reales a Groq durante esta corrección.

## Límites

Entorno local actualizado en **http://localhost:15174**. V14 aplicada (`14|t`), frontend HTTP 200 y salud `status=UP`, `database=UP`. Respaldo previo en `backend/target/local-backups/before-acodi-chat-v14-20261022-232954.dump`. Backend y frontend reconstruidos; PostgreSQL habitual conservado y contenedor de pruebas detenido. Usar Ctrl+F5 para recargar la versión nueva.

- Pendiente: revisión manual con dos personas y evaluación de respuestas reales variadas de Groq. No se presenta una prueba con proveedor simulado como validación semántica del proveedor real.
- Persisten la advertencia del bundle mayor a 500 kB y los pendientes anteriores ajenos a esta corrección.
- Los cambios quedan locales para revisión; no se inició otra corrección ni se publicaron en GitHub.
