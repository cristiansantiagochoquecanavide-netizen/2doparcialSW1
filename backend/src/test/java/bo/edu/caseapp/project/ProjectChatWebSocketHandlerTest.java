package bo.edu.caseapp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import bo.edu.caseapp.project.ProjectAccessRevokedEvent;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.ai.AiConversationMessageCommittedEvent;
import bo.edu.caseapp.ai.AiConversationMessageStatus;
import bo.edu.caseapp.ai.AiConversationResponses.Message;
import bo.edu.caseapp.ai.AiConversationSenderType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class ProjectChatWebSocketHandlerTest {
  private ProjectService projects;
  private ProjectChatWebSocketHandler handler;

  @BeforeEach
  void setUp() {
    projects = mock(ProjectService.class);
    handler = new ProjectChatWebSocketHandler(projects, new ObjectMapper().findAndRegisterModules());
  }

  @Test
  void broadcastsOnlyProjectScopedCommittedMessage() throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID channelId = UUID.randomUUID();
    WebSocketSession session = session(projectId, "member@example.com");
    handler.afterConnectionEstablished(session);
    var response = new ChatResponses.Message(
        UUID.randomUUID(), channelId, UUID.randomUUID(), "Member", "member@example.com", "EDITOR",
        "USER", UUID.randomUUID(), "Hola <equipo>", Instant.parse("2026-01-01T10:00:00Z"), null);

    handler.onMessageCommitted(new ChatMessageCommittedEvent(projectId, response));

    var payload = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
    verify(session).sendMessage(payload.capture());
    assertTrue(payload.getValue().getPayload().contains("\"type\":\"CHAT_MESSAGE\""));
    assertTrue(payload.getValue().getPayload().contains("\"channelId\":\"" + channelId + "\""));
    assertTrue(payload.getValue().getPayload().contains("Hola <equipo>"));
    verify(projects, times(2)).requireAccess(projectId, "member@example.com");
    assertEquals(TransactionPhase.AFTER_COMMIT, phaseOf("onMessageCommitted", ChatMessageCommittedEvent.class));
  }

  @Test
  void removedMemberSessionIsClosedAndOtherProjectMemberRemainsConnected() throws Exception {
    UUID projectId = UUID.randomUUID();
    WebSocketSession member = session(projectId, "removed@example.com");
    WebSocketSession other = session(projectId, "other@example.com");
    handler.afterConnectionEstablished(member);
    handler.afterConnectionEstablished(other);

    handler.onProjectAccessRevoked(new ProjectAccessRevokedEvent(projectId, "removed@example.com"));

    verify(member).close(CloseStatus.POLICY_VIOLATION);
    verify(other, never()).close(any(CloseStatus.class));
    assertEquals(TransactionPhase.AFTER_COMMIT, phaseOf("onProjectAccessRevoked", ProjectAccessRevokedEvent.class));
  }

  @Test
  void broadcastsCommittedAiConversationOnlyToAuthorizedProjectSessions() throws Exception {
    UUID projectId = UUID.randomUUID(), conversationId = UUID.randomUUID();
    WebSocketSession session = session(projectId, "member@example.com");
    handler.afterConnectionEstablished(session);
    Message message = new Message(UUID.randomUUID(), null, AiConversationSenderType.AGENT, null,
        "Agente CASE IA", UUID.randomUUID(), "Member", "Member, mi sugerencia es…",
        AiConversationMessageStatus.COMPLETED, null, 12, 8, 125L, UUID.randomUUID(), Instant.now());

    handler.onAiConversationMessageCommitted(new AiConversationMessageCommittedEvent(projectId, conversationId, message));

    var payload = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
    verify(session).sendMessage(payload.capture());
    assertTrue(payload.getValue().getPayload().contains("\"type\":\"AI_CONVERSATION_MESSAGE\""));
    assertTrue(payload.getValue().getPayload().contains("\"conversationId\":\"" + conversationId + "\""));
    assertTrue(payload.getValue().getPayload().contains("Member, mi sugerencia"));
    assertEquals(TransactionPhase.AFTER_COMMIT, phaseOf("onAiConversationMessageCommitted", AiConversationMessageCommittedEvent.class));
  }

  @Test void voicePresenceReusesChatAndNeverReachesAnotherProjectOrRevokedObserver() throws Exception {
    UUID project = UUID.randomUUID();
    var observer = session(project, "observer@test");
    var foreign = session(UUID.randomUUID(), "foreign@test");
    var revoked = session(project, "revoked@test");
    handler.afterConnectionEstablished(observer); handler.afterConnectionEstablished(foreign); handler.afterConnectionEstablished(revoked);
    doThrow(new bo.edu.caseapp.error.ForbiddenException("revoked")).when(projects).requireAccess(project, "revoked@test");
    handler.onVoiceRoomPresenceChanged(new bo.edu.caseapp.voice.VoiceRoomPresenceChanged(project, UUID.randomUUID(), "server", 1L, 6,
        java.util.List.of(Map.of("participantId", "raul", "name", "Raúl", "muted", true, "connectionState", "connected"))));
    verify(observer).sendMessage(argThat((TextMessage f) -> f.getPayload().contains("VOICE_ROOM_PRESENCE") && f.getPayload().contains("Raúl")));
    verify(foreign, never()).sendMessage(any()); verify(revoked, never()).sendMessage(any());
    verify(revoked).close(CloseStatus.POLICY_VIOLATION);
  }

  private WebSocketSession session(UUID projectId, String email) {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(UUID.randomUUID().toString());
    when(session.getAttributes()).thenReturn(Map.of(
        "authorizedProjectId", projectId,
        "authenticatedUserEmail", email,
        "authorizedExpiresAt", Instant.now().plusSeconds(3600)));
    when(session.isOpen()).thenReturn(true);
    return session;
  }

  private TransactionPhase phaseOf(String method, Class<?> eventType) throws Exception {
    return ProjectChatWebSocketHandler.class.getMethod(method, eventType)
        .getAnnotation(TransactionalEventListener.class).phase();
  }
}
