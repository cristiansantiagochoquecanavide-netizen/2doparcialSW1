# ACODI — Vista previa UML en el lienzo

> Informe de la primera implementación. La revisión posterior detectó deficiencias de render y cobertura visual. El estado vigente y las nuevas pruebas están en [Corrección del overlay](ACODI_CORRECCION_OVERLAY_INFORME.md).

Estado: **COMPLETADO**. Fecha: 22/10/2026.

## Funcionamiento

- Las solicitudes explícitas de edición por `@ACODI` utilizan el clasificador y motor de propuestas del Bloque 15. Se genera una propuesta compartida y una respuesta en el mismo canal, sin ejecutar operaciones UML.
- React Flow muestra una proyección temporal de la propuesta activa: clases, atributos, métodos y relaciones añadidos o modificados en verde; eliminaciones como elementos verdes tachados o relaciones discontinuas. Las entidades originales, snapshots y cola offline permanecen separados.
- La barra contextual ofrece **Aprobar**, **Rechazar** y **Ver cambio**, sin abrir ventanas. Las eliminaciones exigen confirmación explícita en la propia barra.
- Cada diagrama conserva una única selección activa en PostgreSQL. Los demás planes siguen pendientes y pueden seleccionarse desde la barra por un editor. Los lectores ven la selección compartida. Las propuestas privadas no se proyectan ni anuncian al equipo.
- Los cambios de selección y decisiones se notifican mediante el WebSocket del diagrama después del commit. Al abrir o reconectar se recupera la selección persistida.
- Aprobar reutiliza permisos JWT, bloqueos, validación, versión de respaldo y aplicación atómica existentes. Rechazar retira la capa sin escribir UML. Una revisión distinta impide aplicar y devuelve **HTTP 409**, con regeneración explícita disponible.
- Visualizar, seleccionar, aprobar y rechazar no consultan Groq. Solo preparar o regenerar genera una consulta. Se conservan chat, voz, memoria, recomendaciones e idempotencia.

## Archivos de esta corrección

Rutas relativas al repositorio:

- Backend nuevo: `backend/src/main/java/bo/edu/caseapp/ai/UmlCanvasProposalState.java`, `UmlCanvasProposalChanged.java`.
- Migración nueva: `backend/src/main/resources/db/migration/V13__shared_canvas_proposal.sql`. Añade exclusivamente la selección compartida por diagrama, referenciada a las propuestas existentes.
- Backend modificado: `ai/UmlProposalService.java`, `UmlProposalStore.java`, `UmlProposalController.java`, `UmlProposalExecutor.java`, `UmlProposalDecisions.java`; `chat/AcodiWorker.java`; `uml/collab/UmlWebSocketHandler.java`, bajo `backend/src/main/java/bo/edu/caseapp/`.
- Frontend nuevo, bajo `frontend/src/uml/`: `CanvasProposalBar.tsx`, `useCanvasProposal.ts`, `canvasProposalPreview.mjs`, `canvasProposalPreview.d.mts`, `canvas-proposal.css`, `canvasProposalPreview.test.mjs`.
- Frontend modificado: `frontend/src/uml/UmlCanvas.tsx`, `UmlClassNode.tsx`.
- Pruebas: ampliado `backend/src/test/java/bo/edu/caseapp/ai/UmlProposalPostgresIT.java`; nuevo `frontend/canvas-proposal-browser-check.mjs`.

Endpoints añadidos a `/api/projects/{projectId}/diagrams/{diagramId}/ai/proposals`: **GET `/canvas`** y **POST `/{id}/canvas`**. Se reutilizan aplicar, rechazar y regenerar.

## Pruebas ejecutadas

| Verificación | Resultado |
|---|---|
| Regresión backend: unitarias y PostgreSQL aislado | **211/211**, cero fallos/errores/omitidas |
| Frontend | **68/68** |
| TypeScript y build Vite | Correctos; 229 módulos |
| Edge: React Flow real con HTTP/WebSocket simulados | Verde temporal, sin persistir ni cola; rechazo, aprobación, conflicto 409 y ausencia de modal: correcto |
| Edge: regresión del chat y Escuchar | Correcta; controles a 480/340/280 px y borradores conservados |

La integración PostgreSQL incluye JWT, permisos, privacidad, selección persistida, dos conexiones WebSocket reales, eventos después del commit, rollback, propuestas pendientes, rechazo, aprobación atómica, conflicto y regeneración. El flujo `@ACODI` de edición verifica una generación y ninguna escritura provisional UML. Se adaptaron dos pruebas antiguas para distinguir la nueva notificación de vista previa de un evento de modificación UML.

Evidencia backend: `backend/target/canvas-proposal-regression.log` (**BUILD SUCCESS**). No se realizaron llamadas reales a Groq en esta corrección: la generación usa un proveedor simulado en las pruebas repetibles. La prueba de Edge verifica el DOM real; no equivale a una sesión manual entre dos personas.

Entorno local actualizado en **http://localhost:15174**: backend/frontend reconstruidos, V13 aplicada correctamente, frontend HTTP 200 y salud `status=UP`, `database=UP`. Respaldo previo: `backend/target/local-backups/before-canvas-v13-20261022-214147.dump`. El PostgreSQL efímero de pruebas se detuvo al finalizar.

## Límites y revisión

- Pendiente de revisión humana: claridad visual con diagramas grandes y sesión de dos personas usando sus navegadores habituales.
- No se corrigieron fallos ajenos al alcance del motor de generación, audio ni importación de imágenes. Una generación inválida sigue siendo un error; no se inventan cambios.
- Persiste la advertencia de Vite sobre el bundle superior a 500 kB.
- Los cambios están locales para revisión; no se publicaron en GitHub ni se iniciaron otras correcciones.
