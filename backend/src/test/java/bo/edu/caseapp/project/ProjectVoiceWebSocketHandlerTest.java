package bo.edu.caseapp.voice;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import bo.edu.caseapp.error.ForbiddenException;
import bo.edu.caseapp.project.ProjectAccessRevokedEvent;
import bo.edu.caseapp.project.ProjectMember;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class ProjectVoiceWebSocketHandlerTest {
  private ProjectService projects;
  private ObjectMapper mapper;
  private ProjectVoiceWebSocketHandler handler;
  private bo.edu.caseapp.chat.ProjectChatChannelRepository channels;
  private org.springframework.context.ApplicationEventPublisher events;

  @BeforeEach
  void setUp() {
    projects = mock(ProjectService.class);
    mapper = new ObjectMapper();
    channels = mock(bo.edu.caseapp.chat.ProjectChatChannelRepository.class);
    events = mock(org.springframework.context.ApplicationEventPublisher.class);
    when(channels.defaultVoiceRoom(any())).thenAnswer(call -> java.util.Optional.of(call.getArgument(0, UUID.class)));
    when(channels.existsByIdAndProject_IdAndChannelTypeAndArchivedAtIsNull(any(), any(), eq("VOICE"))).thenReturn(true);
    handler = new ProjectVoiceWebSocketHandler(projects, mapper, 6, "[]", channels, events);
  }

  @Test void observingUsesTheSignalingRosterAndPublishesJoinLeaveMuteAndDisconnect() throws Exception {
    UUID project = UUID.randomUUID(), room = UUID.randomUUID();
    var raul = session(project, "raul@test", "Raúl");
    var carlos = session(project, "carlos@test", "Carlos");
    connect(raul); connect(carlos);
    org.junit.jupiter.api.Assertions.assertEquals(0, handler.presence(project, room, "carlos@test").participants().size());
    send(raul, "{\"type\":\"VOICE_JOIN\",\"roomId\":\"" + room + "\"}");
    var snapshot = handler.presence(project, room, "carlos@test");
    org.junit.jupiter.api.Assertions.assertEquals("Raúl", snapshot.participants().getFirst().get("name"));
    org.junit.jupiter.api.Assertions.assertEquals(6, snapshot.maxParticipants());
    org.junit.jupiter.api.Assertions.assertEquals(1, handler.presence(project, room, "carlos@test").participants().size());
    verify(carlos, never()).sendMessage(argThat((TextMessage f) -> f.getPayload().contains("VOICE_JOINED")));
    send(raul, "{\"type\":\"VOICE_MUTE_STATE\",\"muted\":false}");
    org.junit.jupiter.api.Assertions.assertEquals(false, handler.presence(project, room, "carlos@test").participants().getFirst().get("muted"));
    send(carlos, "{\"type\":\"VOICE_JOIN\",\"roomId\":\"" + room + "\"}");
    org.junit.jupiter.api.Assertions.assertEquals(2, handler.presence(project, room, "carlos@test").participants().size());
    send(raul, "{\"type\":\"VOICE_LEAVE\"}");
    handler.afterConnectionClosed(carlos, CloseStatus.NORMAL);
    org.junit.jupiter.api.Assertions.assertTrue(handler.presence(project, room, "raul@test").participants().isEmpty());
    var capture = org.mockito.ArgumentCaptor.forClass(VoiceRoomPresenceChanged.class);
    verify(events, times(5)).publishEvent(capture.capture());
    org.junit.jupiter.api.Assertions.assertTrue(capture.getValue().revision() > snapshot.revision());
    org.junit.jupiter.api.Assertions.assertTrue(capture.getValue().participants().isEmpty());
  }

  @Test void presenceRejectsForeignRoomsAndUsersOutsideTheProject() {
    UUID project = UUID.randomUUID(), foreign = UUID.randomUUID();
    when(channels.existsByIdAndProject_IdAndChannelTypeAndArchivedAtIsNull(foreign, project, "VOICE")).thenReturn(false);
    org.junit.jupiter.api.Assertions.assertThrows(bo.edu.caseapp.error.NotFoundException.class, () -> handler.presence(project, foreign, "member@test"));
    doThrow(new ForbiddenException("No access")).when(projects).requireAccess(project, "outside@test");
    org.junit.jupiter.api.Assertions.assertThrows(ForbiddenException.class, () -> handler.presence(project, foreign, "outside@test"));
    verifyNoInteractions(events);
  }

  @Test
  void authorizedMemberJoinsAndRelaysSignalsOnlyToSameProjectRoom() throws Exception {
    UUID projectId = UUID.randomUUID();
    WebSocketSession alice = session(projectId, "alice@example.com", "Alice");
    WebSocketSession bob = session(projectId, "bob@example.com", "Bob");
    WebSocketSession outsiderRoom = session(UUID.randomUUID(), "other@example.com", "Other");
    connect(alice); connect(bob); connect(outsiderRoom);
    String aliceId = readyId(alice), bobId = readyId(bob);
    send(alice, "{\"type\":\"VOICE_JOIN\"}");
    send(bob, "{\"type\":\"VOICE_JOIN\"}");

    verify(alice, atLeastOnce()).sendMessage(argThat((TextMessage frame) -> frame.getPayload().contains("VOICE_PARTICIPANT_JOINED") && frame.getPayload().contains("Bob")));
    send(alice, "{\"type\":\"VOICE_OFFER\",\"toParticipantId\":\"" + bobId + "\",\"description\":{\"type\":\"offer\",\"sdp\":\"v=0\\r\\n\"}}");

    verify(bob, atLeastOnce()).sendMessage(argThat((TextMessage frame) -> frame.getPayload().contains("VOICE_OFFER") && frame.getPayload().contains(aliceId)));
    verify(outsiderRoom, never()).sendMessage(argThat((TextMessage frame) -> frame.getPayload().contains("VOICE_OFFER")));
  }

  @Test
  void outsiderIsRejectedAndExpiredJwtClosesBeforeJoining() throws Exception {
    UUID projectId = UUID.randomUUID();
    WebSocketSession outsider = session(projectId, "outside@example.com", "Outside");
    doThrow(new ForbiddenException("No access")).when(projects).requireAccess(projectId, "outside@example.com");

    handler.afterConnectionEstablished(outsider);

    verify(outsider).close(CloseStatus.POLICY_VIOLATION);
    verify(outsider, never()).sendMessage(any());

    WebSocketSession expired = mock(WebSocketSession.class);
    when(expired.getId()).thenReturn(UUID.randomUUID().toString());
    when(expired.getAttributes()).thenReturn(Map.of(
        "authorizedProjectId", projectId,
        "authenticatedUserEmail", "member@example.com",
        "authorizedExpiresAt", Instant.now().minusSeconds(1)));
    when(expired.isOpen()).thenReturn(true);
    handler.afterConnectionEstablished(expired);
    verify(expired).close(CloseStatus.POLICY_VIOLATION);
  }

  @Test
  void signalsCannotTargetUsersInAnotherProjectAndInvalidFramesAreRejected() throws Exception {
    WebSocketSession alice = session(UUID.randomUUID(), "alice@example.com", "Alice");
    WebSocketSession bob = session(UUID.randomUUID(), "bob@example.com", "Bob");
    connect(alice); connect(bob);
    String bobId = readyId(bob);
    send(alice, "{\"type\":\"VOICE_JOIN\"}");
    send(bob, "{\"type\":\"VOICE_JOIN\"}");
    send(alice, "{\"type\":\"VOICE_ANSWER\",\"toParticipantId\":\"" + bobId + "\",\"description\":{\"type\":\"answer\",\"sdp\":\"v=0\"}}");
    verify(alice, atLeastOnce()).sendMessage(argThat((TextMessage frame) -> frame.getPayload().contains("RECIPIENT_UNAVAILABLE")));
    verify(bob, never()).sendMessage(argThat((TextMessage frame) -> frame.getPayload().contains("VOICE_ANSWER")));

    send(alice, "{\"type\":\"VOICE_MUTE_STATE\",\"muted\":\"false\"}");
    verify(alice).sendMessage(argThat((TextMessage frame) -> frame.getPayload().contains("INVALID_MUTE_STATE")));
  }

  @Test
  void leaveUnexpectedDisconnectAndRevocationCleanRoomPresence() throws Exception {
    UUID projectId = UUID.randomUUID();
    WebSocketSession alice = session(projectId, "alice@example.com", "Alice");
    WebSocketSession bob = session(projectId, "bob@example.com", "Bob");
    connect(alice); connect(bob);
    send(alice, "{\"type\":\"VOICE_JOIN\"}");
    send(bob, "{\"type\":\"VOICE_JOIN\"}");
    send(alice, "{\"type\":\"VOICE_LEAVE\"}");
    verify(bob, atLeastOnce()).sendMessage(argThat((TextMessage frame) -> frame.getPayload().contains("VOICE_PARTICIPANT_LEFT")));
    handler.afterConnectionClosed(bob, CloseStatus.NORMAL);
    verify(bob, atLeastOnce()).sendMessage(argThat((TextMessage frame) -> frame.getPayload().contains("VOICE_PARTICIPANT_LEFT")));

    WebSocketSession revoked = session(projectId, "removed@example.com", "Removed");
    connect(revoked); send(revoked, "{\"type\":\"VOICE_JOIN\"}");
    handler.onProjectAccessRevoked(new ProjectAccessRevokedEvent(projectId, "removed@example.com"));
    verify(revoked).close(CloseStatus.POLICY_VIOLATION);
    assertTrue(phaseOf().equals(TransactionPhase.AFTER_COMMIT));
  }

  @Test
  void oversizedFrameIsClosed() throws Exception {
    WebSocketSession member = session(UUID.randomUUID(), "member@example.com", "Member");
    connect(member);
    String oversized = "{" + " ".repeat(32_768) + "}";
    send(member, oversized);
    verify(member).close(CloseStatus.TOO_BIG_TO_PROCESS);
  }

  private void connect(WebSocketSession session) throws Exception { handler.afterConnectionEstablished(session); }

  private String readyId(WebSocketSession session) throws Exception {
    var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
    verify(session, atLeastOnce()).sendMessage(captor.capture());
    for (TextMessage frame : captor.getAllValues()) {
      JsonNode json = mapper.readTree(frame.getPayload());
      if ("VOICE_READY".equals(json.path("type").asText())) return json.path("participantId").asText();
    }
    throw new AssertionError("VOICE_READY was not sent");
  }

  private void send(WebSocketSession session, String payload) throws Exception {
    handler.handleTextMessage(session, new TextMessage(payload));
  }

  private WebSocketSession session(UUID projectId, String email, String name) {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(UUID.randomUUID().toString());
    when(session.getAttributes()).thenReturn(new HashMap<>(Map.of(
        "authorizedProjectId", projectId,
        "authenticatedUserEmail", email,
        "authorizedExpiresAt", Instant.now().plusSeconds(3600))));
    when(session.isOpen()).thenReturn(true);
    User user = new User(name, email, "hash");
    ProjectMember member = mock(ProjectMember.class);
    when(member.getUser()).thenReturn(user);
    when(projects.requireAccess(projectId, email)).thenReturn(member);
    return session;
  }

  private TransactionPhase phaseOf() throws Exception {
    return ProjectVoiceWebSocketHandler.class
        .getMethod("onProjectAccessRevoked", ProjectAccessRevokedEvent.class)
        .getAnnotation(TransactionalEventListener.class).phase();
  }
}
