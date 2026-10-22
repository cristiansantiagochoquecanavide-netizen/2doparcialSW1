# Sala de voz WebRTC

La sala de diseño usa audio P2P en topología mesh. El límite predeterminado es seis conexiones de navegador (`VOICE_MAX_PARTICIPANTS`, configurable entre 2 y 16). Con seis participantes cada navegador mantiene hasta cinco conexiones y la sala mantiene hasta quince pares. La carga de cada navegador crece con la cantidad de participantes; el tráfico agregado de la sala crece aproximadamente con el cuadrado de esa cantidad. Esta versión está pensada para salas pequeñas.

La señalización JSON autenticada va por `wss://<host>/ws/projects/{projectId}/voice`; el audio no pasa por el WebSocket. La presencia solo vive en memoria y desaparece al salir, cerrar la pestaña, caducar el JWT o revocar la membresía. No se graba ni almacena audio.

## ICE y despliegue

Configura `VOICE_MAX_PARTICIPANTS` y `VOICE_ICE_SERVERS_JSON` en `backend/.env` como un arreglo JSON compatible con `RTCIceServer`. Compose carga ese archivo para el backend. El valor vacío (`[]`) evita elegir o afirmar que existe un servicio STUN/TURN operativo; en localhost el navegador todavía puede usar candidatos de red local.

Ejemplo de formato con infraestructura propia (los dominios y credenciales son marcadores, no servicios disponibles):

```json
[
  { "urls": "stun:stun.example.net:3478" },
  {
    "urls": "turns:turn.example.net:5349",
    "username": "<credencial temporal>",
    "credential": "<secreto temporal>"
  }
]
```

La lista configurada se entrega solo a miembros autenticados al iniciar la señalización, ya que el navegador necesita esos datos para ICE. No uses credenciales TURN permanentes: emplea credenciales temporales emitidas por tu servicio TURN. No se instala ni se habilita aquí ningún proveedor STUN/TURN, servicio de pago o servidor TURN.

El micrófono requiere permiso explícito y un contexto seguro del navegador (localhost o HTTPS). Para clientes remotos se necesita HTTPS/WSS. Sin STUN/TURN configurado, la conexión entre redes distintas puede fallar; el cliente muestra reconexión y errores, pero no garantiza conectividad universal.
