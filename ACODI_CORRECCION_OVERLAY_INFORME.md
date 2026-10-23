# Corrección de la vista previa UML de ACODI

## Qué estaba mal

La implementación anterior reconstruía los nodos y edges a partir de entidades durante la revisión. Esto descartaba mediciones y estado visual de React Flow y reutilizaba posiciones del snapshot de la propuesta. Tampoco protegía la llegada de una propuesta antes de terminar de cargar el diagrama base. Las pruebas visuales anteriores usaban una sola clase y no comprobaban la conservación del grafo completo.

La barra tenía fondo verde y «Ver cambio» desplegaba descripciones y validaciones. Además, al aprobar, una notificación podía retirar el preview antes de que llegara el modelo confirmado por REST; una prueba con latencia reprodujo este intervalo.

## Corrección

- El render parte de los **nodos y edges actuales**. Conserva los objetos no afectados y la posición, mediciones, selección y handles de las conexiones existentes. Combina únicamente el diff temporal sobre los objetos afectados y añade solo los CREATE nuevos.
- Los cambios se calculan sobre el modelo actual; no se reponen atributos ajenos, posiciones antiguas ni elementos que ya desaparecieron. El preview espera a que esté cargado el diagrama base y representado su conjunto de nodos y relaciones.
- Un atributo o método se muestra en verde **dentro de su clase**. El nombre y la cabecera de una clase existente mantienen su aspecto si no cambian. Las relaciones propuestas se superponen a las existentes; las eliminaciones siguen visibles tachadas o discontinuas hasta decidir.
- La barra es neutra. Se eliminó la lista de cambios/validaciones de esta vista. **Ver cambio** utiliza `fitView` sobre las clases afectadas o los extremos reales de la relación.
- Rechazar retira la capa y vuelve al grafo original. Aprobar mantiene el overlay hasta cargar el modelo confirmado, incluso al recibir la decisión de otro colaborador. Se conserva la protección de la cola offline.
- La corrección no añade escrituras UML provisionales, migraciones ni llamadas a Groq para visualizar o decidir. Se reutiliza la aplicación atómica existente.

## Archivos modificados en esta corrección

- En `frontend/src/uml/`: `UmlCanvas.tsx`, `UmlClassNode.tsx`, `CanvasProposalBar.tsx`, `useCanvasProposal.ts`, `canvasProposalPreview.mjs`, `canvasProposalPreview.d.mts`, `canvasProposalPreview.test.mjs`, `canvas-proposal.css`.
- `frontend/canvas-proposal-browser-check.mjs`.
- `backend/src/test/java/bo/edu/caseapp/ai/UmlProposalPostgresIT.java`: dos pruebas nuevas de conservación del modelo completo; sin cambios al backend de producción.
- Capturas y explicación en `docs/evidencias/acodi-preview/`.

## Pruebas ejecutadas

| Comprobación | Resultado |
|---|---|
| Frontend, incluidas siete nuevas pruebas del overlay | **75/75** |
| Motor de propuestas, propuestas PostgreSQL y chat ACODI | **56/56**, cero fallos/errores/omitidas |
| TypeScript y build Vite | Correctos; 229 módulos |
| Edge con React Flow real, cuatro clases y tres relaciones iniciales | Correcto |
| Edge: regresión de chat, voz local y borradores | Correcto |

La prueba visual verifica `clase: String` en Producto y `metodoPago: String` en Venta, nueva relación conservando las anteriores, rechazo exacto, aprobación sin perder posiciones ni entidades, centrado, modificación de método y eliminación temporal. También simula llegada anticipada de la propuesta y 250 ms de latencia del modelo base. Comprueba que no haya renders parciales de una a tres clases, panel verde, validaciones repetidas, modal, llamadas de guardado provisional ni entradas en la cola offline.

Las pruebas PostgreSQL verifican realmente aprobación/rechazo de atributos y relaciones manteniendo todos los elementos, posiciones y relaciones anteriores. Incluyen las pruebas existentes de JWT, aislamiento, conflictos y WebSocket. Log: `backend/target/canvas-overlay-correction.log` (**BUILD SUCCESS**).

Las capturas son del **componente real en Edge**, con HTTP/WebSocket simulados y datos de prueba identificados en pantalla. No se presentan como una sesión de usuarios reales ni como prueba de generación con Groq; no se consumieron llamadas reales en esta corrección. La advertencia de tamaño del bundle superior a 500 kB continúa.

## Evidencia visual

- [Producto: clase en verde](docs/evidencias/acodi-preview/01-producto-clase-verde.png).
- [Venta: metodoPago en verde](docs/evidencias/acodi-preview/02-venta-metodoPago-verde.png).
- [Nueva relación verde y relaciones anteriores conservadas](docs/evidencias/acodi-preview/03-relacion-verde-conserva-existentes.png).

## Estado final

**COMPLETADO**, con los casos solicitados verificados automáticamente. Pendiente de revisión del equipo sobre sus diagramas habituales. Los cambios se mantienen locales para revisión; no se iniciaron otras correcciones ni se publicaron en GitHub.

Frontend local reconstruido y servido en **http://localhost:15174**: HTTP 200, backend y base de datos `UP`. Backend de producción y PostgreSQL habitual conservados. La base efímera de pruebas se detuvo al finalizar. Recargar con Ctrl+F5 para obtener el frontend actualizado.
