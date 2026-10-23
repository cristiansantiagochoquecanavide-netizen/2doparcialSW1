package bo.edu.caseapp.uml.collab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class UmlWebSocketHandler extends TextWebSocketHandler {
  private static final Logger log = LoggerFactory.getLogger(UmlWebSocketHandler.class);
  private final Map<String, Set<WebSocketSession>> diagramSessions = new ConcurrentHashMap<>();
  @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
  public void onCanvasProposal(bo.edu.caseapp.ai.UmlCanvasProposalChanged event) {
    broadcastDiagramChanged(event.diagramId(),"UML_PROPOSAL_CHANGED");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    String diagramId = extractDiagramId(session);
    if (diagramId != null) {
      diagramSessions.computeIfAbsent(diagramId, k -> new CopyOnWriteArraySet<>()).add(session);
      log.info("Cliente WS conectado al diagrama: {} (sesiones activas: {})", diagramId, diagramSessions.get(diagramId).size());
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    String diagramId = extractDiagramId(session);
    if (diagramId != null && diagramSessions.containsKey(diagramId)) {
      diagramSessions.get(diagramId).remove(session);
      if (diagramSessions.get(diagramId).isEmpty()) {
        diagramSessions.remove(diagramId);
      }
      log.info("Cliente WS desconectado del diagrama: {}", diagramId);
    }
  }

  public void broadcastDiagramChanged(UUID diagramId, String eventType) {
    broadcastDiagramChanged(diagramId, eventType, null);
  }

  public void broadcastDiagramChanged(UUID diagramId, String eventType, Long revision) {
    if (diagramId == null) return;
    String dIdStr = diagramId.toString();
    Set<WebSocketSession> sessions = diagramSessions.get(dIdStr);
    if (sessions == null || sessions.isEmpty()) return;

    String payload;
    if (revision != null) {
      payload = String.format("{\"type\":\"%s\",\"diagramId\":\"%s\",\"revision\":%d,\"timestamp\":%d}",
          eventType != null ? eventType : "DIAGRAM_MUTATED", dIdStr, revision, System.currentTimeMillis());
    } else {
      payload = String.format("{\"type\":\"%s\",\"diagramId\":\"%s\",\"timestamp\":%d}",
          eventType != null ? eventType : "DIAGRAM_MUTATED", dIdStr, System.currentTimeMillis());
    }

    TextMessage message = new TextMessage(payload);
    for (WebSocketSession s : sessions) {
      if (s.isOpen()) {
        try {
          s.sendMessage(message);
        } catch (IOException e) {
          log.warn("Error al enviar mensaje WS a sesión {}: {}", s.getId(), e.getMessage());
        }
      }
    }
  }

  private String extractDiagramId(WebSocketSession session) {
    URI uri = session.getUri();
    if (uri == null) return null;
    String path = uri.getPath();
    String[] parts = path.split("/");
    for (int i = 0; i < parts.length; i++) {
      if ("diagrams".equals(parts[i]) && i + 1 < parts.length) {
        return parts[i + 1].trim();
      }
    }
    return null;
  }
}
