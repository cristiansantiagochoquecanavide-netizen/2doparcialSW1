# Evidencia del overlay corregido

Capturas de Edge headless ejecutando `UmlCanvas` y React Flow reales, con cuatro clases y tres relaciones iniciales. HTTP, WebSocket y generación son fixtures explícitos; no contienen datos de colaboradores ni claves.

- `01-producto-clase-verde.png`: `clase: String` aparece dentro de Producto, en verde; las otras tres clases y relaciones permanecen.
- `02-venta-metodoPago-verde.png`: `metodoPago: String` aparece dentro de Venta; su cabecera y el resto del diagrama mantienen el estilo normal.
- `03-relacion-verde-conserva-existentes.png`: la relación Producto–Pago es verde; las tres relaciones previas continúan visibles. El atributo de Venta ya aprobado tiene el estilo normal.

Reproducir desde `frontend`: `node canvas-proposal-browser-check.mjs`. El script usa los controles reales de ajustar/alejar para encuadrar las cuatro clases y guarda las capturas aquí; también comprueba centrado, rechazo, aprobación, latencia, carga inicial y conservación de posiciones. Las pruebas de persistencia y autorización con PostgreSQL real están en `UmlProposalPostgresIT`.
