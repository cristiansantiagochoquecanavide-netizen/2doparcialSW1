# Navegación unificada de canales y ACODI

`01-navegacion-unificada.png` es una captura de Edge del componente real, obtenida ejecutando `node acodi-browser-check.mjs` desde `frontend`.

Los usuarios, canales, mensajes, WebSocket y síntesis de voz de esta captura son **fixtures explícitos**. No contiene datos del proyecto del usuario ni demuestra una llamada real a Groq o audio entre micrófonos.

La prueba visual comprueba gestión de texto/voz/chats privados, confirmación y cancelación, borradores separados, paneles de 280/340/480 px, ampliación sin reconectar, respuestas expandibles, scroll independiente, reconexión y conservación de `@ACODI` y lectura local. Las pruebas backend con PostgreSQL y dos usuarios verifican por separado persistencia, JWT, privacidad, difusión de canales y aislamiento de señalización por sala.
