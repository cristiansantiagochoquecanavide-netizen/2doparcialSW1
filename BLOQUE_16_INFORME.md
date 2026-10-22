# Bloque 16 — Voz hablada del agente colaborativo

## 1. Estado final

**COMPLETADO en implementación y pruebas automatizadas.** Pendiente de validación auditiva y de interacción en navegadores reales.

- Frontend: **50 pruebas aprobadas**, cero fallos u omisiones; TypeScript y build de producción correctos.
- Backend: **184 pruebas aprobadas**, cero fallos, errores u omisiones; incluye **36 pruebas con PostgreSQL real**.
- La nueva síntesis se probó con mocks del navegador. No se afirma haber escuchado audio real.
- Cambios disponibles en el workspace; no desplegados en los contenedores habituales.

Se espera revisión antes de iniciar la fase final de estabilización.

## 2. Auditoría e integración elegida

Se leyó la skill `.agents/skills/segundo-parcial-case-colaborativo/SKILL.md` y se inspeccionaron el espacio colaborativo, `VoiceRoom`, el WebSocket de chat, las conversaciones, las recomendaciones y los controles del lienzo.

Las recomendaciones ya se guardan y publican después del commit mediante el WebSocket autorizado del proyecto. Sus mensajes AGENT no tienen destinatario individual ni mensaje de respuesta asociado. Las respuestas individuales sí tienen destinatario o referencia a la pregunta. Se reutiliza esta distinción para seleccionar la lectura automática.

La síntesis usa `window.speechSynthesis` y `SpeechSynthesisUtterance`. Cada navegador produce su audio local: **no se añade un participante WebRTC ni se transmite la síntesis como pista de la sala**.

Se seleccionan exclusivamente voces con `localService=true` para evitar servicios remotos de síntesis. Preferencia: `es-BO`; alternativa: otra voz local disponible en español. Si no existe una voz compatible, se informa de ello y permanece disponible la respuesta escrita. La distinción local/remota corresponde a la propiedad documentada de la API. [Referencia MDN](https://developer.mozilla.org/en-US/docs/Web/API/SpeechSynthesisVoice/localService).

## 3. Componentes creados y modificados

Nuevos en `frontend/src/uml/`:

| Archivo | Función |
| --- | --- |
| `agentSpeech.mjs` | Controlador de reproducción, selección de voz, cola, deduplicación, validaciones y preferencias en memoria |
| `agentSpeech.d.mts` | Contratos TypeScript del controlador |
| `AgentSpeechUI.tsx` | Provider de sesión, hook y controles visibles en el pie del editor |
| `agentSpeech.test.mjs` | 20 pruebas nuevas con síntesis simulada y representación React |

Modificados:

- `WorkspaceChrome.tsx`: Provider y controles fuera del panel de equipo, accesibles al editar o usar el Inspector.
- `LiveTeamChatPanel.tsx`: comunica conexión/desconexión del WebSocket y entrega los mensajes recibidos al controlador local.
- `VoiceRoom.tsx`: comunica el estado local de conexión y del micrófono. No cambia señalización, peers ni pistas de audio.
- `AiConversationsPanel.tsx`: botón «Reproducir respuesta», asociación del mensaje con su conversación/ámbito y filtro del proyecto en eventos del navegador.
- `team-collaboration.css`: presentación de los controles y pie adaptable.
- Este informe.

No se añadieron dependencias, migraciones, endpoints o cambios de backend. Se conservaron los cambios anteriores del workspace.

## 4. Lectura manual

Las respuestas AGENT completadas muestran «Reproducir respuesta». Se utiliza el contenido escrito existente, sin consultar a Groq.

El controlador verifica proyecto, conversación abierta, ámbito y estado. La interfaz conserva la fuente de cada mensaje: cambiar rápidamente de conversación no reclasifica una respuesta privada como grupal.

Las respuestas privadas solo pueden reproducirse manualmente, con la conversación autorizada abierta y **fuera de la sala de voz**. Entrar en la sala cancela una lectura privada en curso. La voz del agente se detiene cuando se activa el micrófono propio; no se inicia reproducción manual con ese micrófono activo.

Los mensajes largos se dividen en fragmentos de hasta aproximadamente 450 caracteres y se envía una sola locución al navegador cada vez. Se conserva el texto de hasta 8.000 caracteres, límite de las respuestas existentes. Para lectura se retira el marcado, se omiten URL y se sustituye cada bloque de código por un aviso de que está disponible en el mensaje escrito. No se genera otro resumen con IA.

Cambiar de conversación o cerrar su vista cancela la reproducción manual. El texto persistente permanece disponible.

## 5. Condiciones de reproducción automática

La opción está **desactivada inicialmente**. Solo se lee una recomendación cuando se cumplen todas las condiciones:

1. El usuario activó expresamente «Voz automática del agente».
2. Existe una voz local disponible en español.
3. El WebSocket autorizado del proyecto está conectado.
4. El usuario está conectado a la sala de diseño del proyecto.
5. Su micrófono está silenciado.
6. El mensaje llega en vivo como una recomendación AGENT completada del mismo proyecto, sin destinatario individual ni referencia a una pregunta.
7. No está marcado como privado ni se reprodujo/procesó previamente.
8. Su fecha es posterior a la activación y a las conexiones actuales, tiene como máximo 30 segundos de antigüedad y no supera en más de 5 segundos el reloj local.
9. No es anterior a otra recomendación más reciente ya aceptada.

No se activa la síntesis al cargar historial por REST. Reconectar no reproduce mensajes antiguos o acumulados durante la desconexión. Las recomendaciones omitidas mientras la opción estaba desactivada tampoco se recuperan automáticamente.

La lectura automática usa como máximo 700 caracteres de la recomendación escrita para evitar intervenciones largas. El mensaje completo sigue disponible en el chat y por reproducción manual.

## 6. Cola e interrupciones

- Un mensaje activo y **un único mensaje pendiente** como máximo.
- Una recomendación nueva reemplaza la pendiente, sin superponer voces ni interrumpir continuamente el mensaje activo.
- La pendiente se descarta si envejeció antes de comenzar.
- Se deduplican identificadores de mensaje; memoria limitada a 512 IDs, junto al control de fechas y antigüedad.
- «Detener voz» vacía la cola y cancela la locución actual. La preferencia automática permanece activada para futuras recomendaciones; para silenciarla se desmarca la opción.
- Desactivar la opción, salir de la sala, perder la conexión o activar el micrófono cancela la reproducción y la cola.
- Cambiar de proyecto, usuario/sesión o desmontar el espacio elimina el controlador anterior. Sus callbacks tardíos no pueden reiniciar audio.
- Los eventos de cancelación se distinguen de los fallos. Ante rechazo de autoplay se desactiva la lectura automática y se ofrece recuperación manual.

El navegador mantiene una cola interna para `speak`; el controlador entrega solo el fragmento activo y gestiona su propia cola limitada para evitar acumulación. [Referencia MDN](https://developer.mozilla.org/en-US/docs/Web/API/SpeechSynthesis/speak).

## 7. Controles y preferencias

En el pie del editor, visibles aunque se cambie al Inspector o se cierre el panel de equipo:

- Activar/desactivar voz automática.
- Detener voz.
- Indicador «Agente hablando», inicio, pausa por micrófono o ausencia de síntesis/voz española.
- Volumen del agente entre 0 y 1; predeterminado 0,7.
- Velocidad entre 0,5 y 1,5; predeterminada 1.
- Error recuperable de síntesis o autoplay.

Volumen y velocidad se aplican al crear el siguiente fragmento, sin reescribir la respuesta. El volumen de los participantes WebRTC sigue siendo independiente.

Preferencias en memoria por usuario autenticado y sesión, sin `localStorage` global. Se conservan al cambiar de panel/proyecto durante esa sesión de aplicación; una nueva sesión o recarga no hereda automáticamente una preferencia permanente. La caché está limitada a ocho entradas.

La estructura del Provider no cambia de tipo al activar/desactivar la voz, y sus acciones estables no provocan reconexiones de los WebSockets por cada actualización del indicador.

## 8. Seguridad y privacidad

- Se reciben los mensajes por el canal con JWT y autorización existente. No se crea otra vía para obtener respuestas.
- La síntesis automática solo recibe mensajes en vivo del WebSocket grupal, no el historial ni las respuestas privadas por REST.
- Las respuestas individuales mantienen el nombre incluido en su texto original; las recomendaciones conservan su mensaje dirigido al equipo.
- Las respuestas privadas se bloquean en la sala y requieren una reproducción manual fuera de ella.
- Se verifican proyecto y conversación, también ante cambios de vista y callbacks tardíos.
- La síntesis no solicita claves, accede al `.env`, llama a Groq ni envía texto a un servicio remoto añadido.
- Se excluyen las voces que el navegador identifica como remotas.
- No se graba, transcribe, reconoce biométricamente ni envía a IA el audio de participantes.
- El controlador no modifica las pistas WebRTC, los mensajes persistidos o el UML; no llama a endpoints de aplicación de propuestas.
- Los controles del Bloque 15 y la aprobación humana se conservan.

La reproducción por altavoces puede escucharse físicamente en el entorno. La cancelación al activar el micrófono propio reduce interferencias; no se afirma haber verificado aislamiento acústico o cancelación de eco con hardware real.

## 9. Pruebas y resultados exactos

### Frontend

Última ejecución: `npm test`: **50 pruebas, 50 aprobadas, 0 fallos, 0 omitidas**.

Son 30 pruebas anteriores y 20 nuevas del Bloque 16. Las nuevas cubren:

1. Reproducción manual, voz española, volumen, velocidad e indicador de inicio/fin.
2. Opción desactivada por defecto y condiciones de sala/conexión.
3. Deduplicación por ID antes y después de terminar.
4. Cola corta que conserva la recomendación pendiente más reciente.
5. Descarte de mensajes fuera de orden o pendientes vencidos.
6. Desactivación y protección frente a callbacks tardíos.
7. Salida/reingreso en sala sin reproducir mensajes anteriores.
8. Reconexión de chat sin reproducir eventos antiguos.
9. Exclusión automática de privados y reproducción privada solo fuera de sala.
10. Exclusión de respuestas individuales, usuarios humanos, errores, fechas inválidas y otros proyectos.
11. Validación de conversación abierta y cancelación al cambiar de vista.
12. Navegador sin síntesis o sin voz local española.
13. Preferencia por `es-BO`, alternativa local en español y rechazo de voces remotas.
14. Autoplay rechazado y recuperación manual.
15. Excepciones del sintetizador y cancelaciones esperadas.
16. Pausa/cancelación al activar el micrófono humano.
17. Fragmentación secuencial de respuestas largas y cancelación de sus fragmentos restantes.
18. Limpieza de listeners y ausencia de reproducción tras desmontaje.
19. Preferencias separadas por usuario y sesión.
20. Renderizado React de controles, integración fuera del panel y ausencia de dependencias de red, grabación o mutación UML en el controlador.

`tsc --noEmit`: correcto.

`vite build --configLoader runner`: correcto, 223 módulos. Permanece la advertencia de paquete JavaScript mayor de 500 kB (aproximadamente 612 kB antes de gzip). La salida se generó en una carpeta temporal.

Se detectó y corrigió durante el build una ambigüedad de resolución en Windows entre nombres que solo diferían en mayúsculas/minúsculas: el componente quedó como `AgentSpeechUI.tsx` y el controlador como `agentSpeech.mjs`.

Los tests utilizan mocks de `speechSynthesis`, reloj controlado y renderizado React en servidor; **no son pruebas de reproducción auditiva ni de efectos DOM completos en un navegador**. La prueba de conservación del lienzo comprueba límites de integración y no sustituye interacción visual.

### Backend

Se ejecutó la selección completa:

```text
-Dtest=*Test,AiAnalysisPostgresIT,UmlOptimisticConcurrencyPostgresIT,UmlProposalPostgresIT
```

Resultado: **184 pruebas, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS; MavenExitCode=0**.

- 148 pruebas sin PostgreSQL.
- 16 integraciones de análisis proactivo.
- 3 integraciones de concurrencia UML.
- 17 integraciones de propuestas UML.

Las 36 integraciones usaron PostgreSQL 16 temporal, contenedor `case-block16-postgres`, puerto local 55439, base aislada `case_block14_test`, sin volumen de la aplicación. Se reutilizó ese nombre de base para ejecutar las suites existentes protegidas por esa comprobación.

Groq fue simulado; no se consumieron tokens reales. Se comprobaron las suites existentes de permisos, privacidad, conversaciones, WebSocket, señalización de voz, UML, concurrencia, propuestas y adaptador Groq. Esta ejecución no demuestra audio WebRTC físico ni síntesis de navegador real.

Evidencia: `backend/target/block16-backend-validation.log` e informes Surefire. Java disponible 23.0.2, con compilación `release 21`; no se ejecutó en otra JVM Java 21. `git diff --check`, considerando los finales CRLF del repositorio, no encontró errores de espacios en los cambios rastreados.

## 10. Límites del navegador y verificaciones pendientes

- Instalar/seleccionar una voz local española cuando el sistema no la proporcione. La disponibilidad y calidad de `es-BO` dependen del sistema y navegador.
- El navegador puede cargar las voces de forma asíncrona; se atiende `voiceschanged`.
- Las restricciones de reproducción automática pueden exigir interacción manual adicional.
- Se necesita un reloj local razonablemente sincronizado para distinguir mensajes nuevos de antiguos; ante fechas incompatibles se omite la lectura automática.
- No se implementa detección de quién está hablando entre participantes remotos. Se evita leer con el micrófono propio activo y se ofrece parada inmediata.
- Probar audífonos/altavoces: pronunciación, volumen, velocidad, fragmentos largos, cancelación y posible eco.
- Probar dos usuarios en navegadores reales: cada uno con su opción independiente; unirse/salir, reconectar, cambio de proyecto y ámbitos privados.
- Verificar controles con panel cerrado, Inspector, pantallas pequeñas y edición del lienzo sin pérdida de estado.
- Verificar que detener al agente mantiene el audio humano bidireccional.
- Validar privacidad y ausencia de realimentación con dictado/comandos por voz usando hardware real.
- Los contenedores habituales no se reconstruyeron: esta entrega requiere desplegarse antes de la demostración manual.

No se ejecuta automáticamente la estabilización. Se conservan los pendientes anteriores de voz para atributos, imágenes, clases de asociación, chat con dos navegadores, WebRTC físico, revisión visual, Groq real, migraciones/despliegue habitual y micrófono físico. No se alteraron los quince casos de uso académicos.
