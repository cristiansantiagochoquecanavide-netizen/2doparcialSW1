package bo.edu.caseapp.uml.collab;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import bo.edu.caseapp.chat.ProjectChatWebSocketHandler;
import bo.edu.caseapp.voice.ProjectVoiceWebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class UmlWebSocketConfig implements WebSocketConfigurer {
  private final UmlWebSocketHandler handler;
  private final ProjectChatWebSocketHandler chatHandler;
  private final ProjectVoiceWebSocketHandler voiceHandler;
  private final JwtWebSocketHandshakeInterceptor authInterceptor;
  private final String[] allowedOrigins;

  public UmlWebSocketConfig(
      UmlWebSocketHandler handler,
      ProjectChatWebSocketHandler chatHandler,
      ProjectVoiceWebSocketHandler voiceHandler,
      JwtWebSocketHandshakeInterceptor authInterceptor,
      @Value("${app.cors.allowed-origins}") String allowedOrigins
  ) {
    this.handler = handler;
    this.chatHandler = chatHandler;
    this.voiceHandler = voiceHandler;
    this.authInterceptor = authInterceptor;
    this.allowedOrigins = java.util.Arrays.stream(allowedOrigins.split(","))
        .map(String::trim)
        .filter(origin -> !origin.isEmpty())
        .toArray(String[]::new);
    if (this.allowedOrigins.length == 0 || java.util.Arrays.asList(this.allowedOrigins).contains("*")) {
      throw new IllegalArgumentException("WebSocket requires explicit allowed origins; wildcard origins are not permitted.");
    }
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    DefaultHandshakeHandler handshakeHandler = new DefaultHandshakeHandler();
    handshakeHandler.setSupportedProtocols("case-events-v1");
    registry.addHandler(handler, "/ws/diagrams/{diagramId}")
        .addInterceptors(authInterceptor)
        .setHandshakeHandler(handshakeHandler)
        .setAllowedOrigins(allowedOrigins);
    registry.addHandler(chatHandler, "/ws/projects/{projectId}/chat")
        .addInterceptors(authInterceptor)
        .setHandshakeHandler(handshakeHandler)
        .setAllowedOrigins(allowedOrigins);
    registry.addHandler(voiceHandler, "/ws/projects/{projectId}/voice")
        .addInterceptors(authInterceptor)
        .setHandshakeHandler(handshakeHandler)
        .setAllowedOrigins(allowedOrigins);
  }
}
