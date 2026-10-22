package bo.edu.caseapp.chat;

import bo.edu.caseapp.project.ProjectAccessRevokedEvent;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.ai.AiConversationMessageCommittedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Project-scoped push channel. Messages are written only through the authenticated REST API. */
@Component
public class ProjectChatWebSocketHandler extends TextWebSocketHandler {
  private static final Logger log = LoggerFactory.getLogger(ProjectChatWebSocketHandler.class);
  private static final String PROJECT_ATTRIBUTE = "authorizedProjectId";
  private static final String EMAIL_ATTRIBUTE = "authenticatedUserEmail";
  private static final String EXPIRATION_ATTRIBUTE = "authorizedExpiresAt";
  private static final ScheduledThreadPoolExecutor EXPIRY_EXECUTOR = createExpiryExecutor();
  private final Map<UUID, Set<WebSocketSession>> projectSessions = new ConcurrentHashMap<>();
  private final Map<String, ScheduledFuture<?>> expiryTasks = new ConcurrentHashMap<>();
  private final ProjectService projects;
  private final ObjectMapper objectMapper;

  public ProjectChatWebSocketHandler(ProjectService projects, ObjectMapper objectMapper) {
    this.projects = projects;
    this.objectMapper = objectMapper;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws IOException {
    UUID projectId = (UUID) session.getAttributes().get(PROJECT_ATTRIBUTE);
    String email = (String) session.getAttributes().get(EMAIL_ATTRIBUTE);
    Object expiryValue = session.getAttributes().get(EXPIRATION_ATTRIBUTE);
    if (projectId == null || email == null || !(expiryValue instanceof java.time.Instant expiresAt)) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    long delay = java.time.Duration.between(java.time.Instant.now(), expiresAt).toMillis();
    if (delay <= 0) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    try {
      projects.requireAccess(projectId, email);
      projectSessions.computeIfAbsent(projectId, ignored -> new CopyOnWriteArraySet<>()).add(session);
      ScheduledFuture<?> task = EXPIRY_EXECUTOR.schedule(
          () -> closeRevokedSession(session, projectId), delay, TimeUnit.MILLISECONDS);
      expiryTasks.put(session.getId(), task);
    } catch (RuntimeException exception) {
      session.close(CloseStatus.POLICY_VIOLATION);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    ScheduledFuture<?> expiryTask = expiryTasks.remove(session.getId());
    if (expiryTask != null) expiryTask.cancel(false);
    UUID projectId = (UUID) session.getAttributes().get(PROJECT_ATTRIBUTE);
    if (projectId == null) return;
    Set<WebSocketSession> sessions = projectSessions.get(projectId);
    if (sessions != null) {
      sessions.remove(session);
      if (sessions.isEmpty()) projectSessions.remove(projectId, sessions);
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
    // Chat writes are REST-only so each one gets normal validation, idempotency, and transaction handling.
    session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Envía mensajes de chat mediante REST."));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMessageCommitted(ChatMessageCommittedEvent event) {
    Set<WebSocketSession> sessions = projectSessions.get(event.projectId());
    if (sessions == null || sessions.isEmpty()) return;

    final String payload;
    try {
      payload = objectMapper.writeValueAsString(Map.of(
          "type", "CHAT_MESSAGE",
          "projectId", event.projectId(),
          "channelId", event.message().channelId(),
          "message", event.message()
      ));
    } catch (Exception exception) {
      log.error("No se pudo serializar el evento de chat del proyecto {}", event.projectId(), exception);
      return;
    }

    TextMessage frame = new TextMessage(payload);
    for (WebSocketSession session : sessions) {
      if (!session.isOpen()) {
        remove(session, event.projectId());
        continue;
      }
      String email = (String) session.getAttributes().get(EMAIL_ATTRIBUTE);
      try {
        projects.requireAccess(event.projectId(), email);
        synchronized (session) {
          if (session.isOpen()) session.sendMessage(frame);
        }
      } catch (RuntimeException exception) {
        closeRevokedSession(session, event.projectId());
      } catch (IOException exception) {
        log.warn("No se pudo entregar un evento de chat al proyecto {}", event.projectId());
        closeQuietly(session);
      }
    }
  }

  /** Group AI messages reuse the authenticated project channel. Private turns never publish this event. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onAiConversationMessageCommitted(AiConversationMessageCommittedEvent event) {
    Set<WebSocketSession> sessions = projectSessions.get(event.projectId());
    if (sessions == null || sessions.isEmpty()) return;
    final String payload;
    try {
      payload = objectMapper.writeValueAsString(Map.of(
          "type", "AI_CONVERSATION_MESSAGE",
          "projectId", event.projectId(),
          "conversationId", event.conversationId(),
          "message", event.message()
      ));
    } catch (Exception exception) {
      log.error("No se pudo serializar un evento de conversación IA del proyecto {}", event.projectId());
      return;
    }
    TextMessage frame = new TextMessage(payload);
    for (WebSocketSession session : sessions) {
      if (!session.isOpen()) { remove(session, event.projectId()); continue; }
      String email = (String) session.getAttributes().get(EMAIL_ATTRIBUTE);
      try {
        projects.requireAccess(event.projectId(), email);
        synchronized (session) { if (session.isOpen()) session.sendMessage(frame); }
      } catch (RuntimeException exception) {
        closeRevokedSession(session, event.projectId());
      } catch (IOException exception) {
        log.warn("No se pudo entregar un evento de conversación IA al proyecto {}", event.projectId());
        closeQuietly(session);
      }
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProjectAccessRevoked(ProjectAccessRevokedEvent event) {
    Set<WebSocketSession> sessions = projectSessions.get(event.projectId());
    if (sessions == null) return;
    for (WebSocketSession session : sessions) {
      String email = (String) session.getAttributes().get(EMAIL_ATTRIBUTE);
      if (event.userEmail() == null || (email != null && email.equalsIgnoreCase(event.userEmail()))) {
        closeRevokedSession(session, event.projectId());
      }
    }
  }

  private void closeRevokedSession(WebSocketSession session, UUID projectId) {
    closeQuietly(session, CloseStatus.POLICY_VIOLATION);
    remove(session, projectId);
  }

  private void closeQuietly(WebSocketSession session) {
    closeQuietly(session, CloseStatus.SERVER_ERROR);
  }

  private void closeQuietly(WebSocketSession session, CloseStatus status) {
    try {
      if (session.isOpen()) session.close(status);
    } catch (IOException ignored) {
      // The connection is already unusable; REST history remains the recovery path.
    }
  }

  private void remove(WebSocketSession session, UUID projectId) {
    Set<WebSocketSession> sessions = projectSessions.get(projectId);
    if (sessions != null) {
      sessions.remove(session);
      if (sessions.isEmpty()) projectSessions.remove(projectId, sessions);
    }
  }

  private static ScheduledThreadPoolExecutor createExpiryExecutor() {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
      Thread thread = Executors.defaultThreadFactory().newThread(runnable);
      thread.setName("project-chat-token-expiry");
      thread.setDaemon(true);
      return thread;
    });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }
}
