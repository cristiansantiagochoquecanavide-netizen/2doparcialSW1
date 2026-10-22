package bo.edu.caseapp.voice;

import bo.edu.caseapp.project.ProjectAccessRevokedEvent;
import bo.edu.caseapp.project.ProjectMember;
import bo.edu.caseapp.project.ProjectService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Authenticated signaling and ephemeral presence for a project's audio-only WebRTC room. */
@Component
public class ProjectVoiceWebSocketHandler extends TextWebSocketHandler {
  private static final Logger log = LoggerFactory.getLogger(ProjectVoiceWebSocketHandler.class);
  private static final int MAX_FRAME_CHARS = 32_768;
  private static final int MAX_SDP_CHARS = 16_000;
  private static final int MAX_CANDIDATE_CHARS = 2_048;
  private static final String PROJECT_ATTRIBUTE = "authorizedProjectId";
  private static final String EMAIL_ATTRIBUTE = "authenticatedUserEmail";
  private static final String EXPIRATION_ATTRIBUTE = "authorizedExpiresAt";
  private static final ScheduledThreadPoolExecutor EXPIRY_EXECUTOR = createExpiryExecutor();

  private final ConcurrentMap<UUID, ConcurrentMap<String, Connection>> rooms = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Connection> sessions = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, ScheduledFuture<?>> expiryTasks = new ConcurrentHashMap<>();
  private final ProjectService projects;
  private final ObjectMapper mapper;
  private final int maxParticipants;
  private final List<Map<String, Object>> iceServers;

  public ProjectVoiceWebSocketHandler(
      ProjectService projects,
      ObjectMapper mapper,
      @Value("${app.voice.max-participants:6}") int maxParticipants,
      @Value("${app.voice.ice-servers-json:[]}") String iceServersJson
  ) {
    this.projects = projects;
    this.mapper = mapper;
    if (maxParticipants < 2 || maxParticipants > 16) {
      throw new IllegalArgumentException("VOICE_MAX_PARTICIPANTS must be between 2 and 16 for mesh audio.");
    }
    this.maxParticipants = maxParticipants;
    try {
      this.iceServers = mapper.readValue(iceServersJson, new TypeReference<>() {});
      if (this.iceServers == null) throw new IllegalArgumentException("ICE server configuration must be a JSON array.");
      validateIceServers(this.iceServers);
    } catch (IOException exception) {
      throw new IllegalArgumentException("VOICE_ICE_SERVERS_JSON must contain a valid JSON array.", exception);
    }
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws IOException {
    UUID projectId = (UUID) session.getAttributes().get(PROJECT_ATTRIBUTE);
    String email = (String) session.getAttributes().get(EMAIL_ATTRIBUTE);
    Object expiryValue = session.getAttributes().get(EXPIRATION_ATTRIBUTE);
    if (projectId == null || email == null || !(expiryValue instanceof Instant expiresAt)) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    long delay = Duration.between(Instant.now(), expiresAt).toMillis();
    if (delay <= 0) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    try {
      ProjectMember member = projects.requireAccess(projectId, email);
      Connection connection = new Connection(session, projectId, email, member.getUser().getName(), UUID.randomUUID().toString());
      sessions.put(session.getId(), connection);
      expiryTasks.put(session.getId(), EXPIRY_EXECUTOR.schedule(
          () -> closeConnection(connection, CloseStatus.POLICY_VIOLATION), delay, TimeUnit.MILLISECONDS));
      send(connection, Map.of("type", "VOICE_READY", "participantId", connection.participantId,
          "name", connection.name, "maxParticipants", maxParticipants, "iceServers", iceServers));
    } catch (RuntimeException exception) {
      session.close(CloseStatus.POLICY_VIOLATION);
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
    Connection connection = sessions.get(session.getId());
    if (connection == null) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    if (message.getPayloadLength() > MAX_FRAME_CHARS) {
      session.close(CloseStatus.TOO_BIG_TO_PROCESS);
      return;
    }
    final JsonNode frame;
    try {
      frame = mapper.readTree(message.getPayload());
    } catch (IOException exception) {
      sendError(connection, "INVALID_JSON", "El mensaje de señalización no tiene un formato válido.");
      return;
    }
    if (frame == null || !frame.isObject() || !frame.path("type").isTextual()) {
      sendError(connection, "INVALID_FRAME", "El evento de voz no es válido.");
      return;
    }
    switch (frame.path("type").asText()) {
      case "VOICE_JOIN" -> join(connection);
      case "VOICE_LEAVE" -> leave(connection);
      case "VOICE_MUTE_STATE" -> updateMuteState(connection, frame);
      case "VOICE_OFFER", "VOICE_ANSWER" -> relayDescription(connection, frame);
      case "VOICE_ICE_CANDIDATE" -> relayCandidate(connection, frame);
      default -> sendError(connection, "UNKNOWN_EVENT", "Tipo de evento de voz desconocido.");
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    closeConnection(sessions.get(session.getId()), status);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProjectAccessRevoked(ProjectAccessRevokedEvent event) {
    for (Connection connection : sessions.values()) {
      if (connection.projectId.equals(event.projectId())
          && (event.userEmail() == null || connection.email.equalsIgnoreCase(event.userEmail()))) {
        closeConnection(connection, CloseStatus.POLICY_VIOLATION);
      }
    }
  }

  private void join(Connection connection) {
    if (connection.joined) {
      sendError(connection, "ALREADY_JOINED", "Ya estás en la sala de voz.");
      return;
    }
    try {
      projects.requireAccess(connection.projectId, connection.email);
    } catch (RuntimeException exception) {
      closeConnection(connection, CloseStatus.POLICY_VIOLATION);
      return;
    }
    ConcurrentMap<String, Connection> room = rooms.computeIfAbsent(connection.projectId, ignored -> new ConcurrentHashMap<>());
    synchronized (room) {
        if (sessions.get(connection.session.getId()) != connection || !connection.session.isOpen()) return;
        if (connection.joined) {
          sendError(connection, "ALREADY_JOINED", "Ya estás en la sala de voz.");
          return;
        }
        if (room.size() >= maxParticipants) {
          sendError(connection, "ROOM_FULL", "La sala alcanzó su límite de " + maxParticipants + " participantes.");
          return;
        }
        connection.joined = true;
        List<Map<String, Object>> existing = room.values().stream().map(Connection::participantView).toList();
        room.put(connection.participantId, connection);
        send(connection, Map.of("type", "VOICE_JOINED", "participantId", connection.participantId,
            "participants", existing, "maxParticipants", maxParticipants));
        broadcast(room, connection, Map.of("type", "VOICE_PARTICIPANT_JOINED", "participant", connection.participantView()));
    }
  }

  private void leave(Connection connection) {
    if (!connection.joined) return;
    ConcurrentMap<String, Connection> room = rooms.get(connection.projectId);
    if (room == null) {
      connection.joined = false;
      return;
    }
    synchronized (room) {
      if (room.remove(connection.participantId, connection)) {
        connection.joined = false;
        broadcast(room, null, Map.of("type", "VOICE_PARTICIPANT_LEFT", "participantId", connection.participantId));
      }
    }
  }

  private void updateMuteState(Connection connection, JsonNode frame) {
    if (!requireJoined(connection)) return;
    JsonNode mutedNode = frame.get("muted");
    if (mutedNode == null || !mutedNode.isBoolean()) {
      sendError(connection, "INVALID_MUTE_STATE", "El estado del micrófono debe ser verdadero o falso.");
      return;
    }
    connection.muted = mutedNode.booleanValue();
    ConcurrentMap<String, Connection> room = rooms.get(connection.projectId);
    if (room != null) broadcast(room, null, Map.of(
        "type", "VOICE_MUTE_STATE", "participantId", connection.participantId, "muted", connection.muted));
  }

  private void relayDescription(Connection sender, JsonNode frame) {
    if (!requireJoined(sender)) return;
    String type = frame.path("type").asText();
    JsonNode description = frame.get("description");
    String expectedType = "VOICE_OFFER".equals(type) ? "offer" : "answer";
    if (description == null || !description.isObject() || !expectedType.equals(description.path("type").asText())
        || !description.path("sdp").isTextual() || description.path("sdp").asText().length() > MAX_SDP_CHARS) {
      sendError(sender, "INVALID_DESCRIPTION", "La descripción WebRTC es inválida o demasiado grande.");
      return;
    }
    Connection recipient = recipient(sender, frame);
    if (recipient != null) send(recipient, Map.of("type", type, "fromParticipantId", sender.participantId,
        "fromName", sender.name, "description", description));
  }

  private void relayCandidate(Connection sender, JsonNode frame) {
    if (!requireJoined(sender)) return;
    JsonNode candidate = frame.get("candidate");
    if (candidate == null || !candidate.isObject() || !candidate.path("candidate").isTextual()
        || candidate.path("candidate").asText().length() > MAX_CANDIDATE_CHARS
        || (candidate.has("sdpMid") && !candidate.get("sdpMid").isNull() && !candidate.get("sdpMid").isTextual())
        || (candidate.has("sdpMLineIndex") && !candidate.get("sdpMLineIndex").isNull() && !candidate.get("sdpMLineIndex").canConvertToInt())) {
      sendError(sender, "INVALID_ICE_CANDIDATE", "El candidato ICE es inválido o demasiado grande.");
      return;
    }
    Connection recipient = recipient(sender, frame);
    if (recipient != null) send(recipient, Map.of("type", "VOICE_ICE_CANDIDATE", "fromParticipantId", sender.participantId,
        "fromName", sender.name, "candidate", candidate));
  }

  private Connection recipient(Connection sender, JsonNode frame) {
    if (!frame.path("toParticipantId").isTextual()) {
      sendError(sender, "INVALID_RECIPIENT", "Falta el destinatario de la señalización.");
      return null;
    }
    ConcurrentMap<String, Connection> room = rooms.get(sender.projectId);
    Connection recipient = room == null ? null : room.get(frame.path("toParticipantId").asText());
    if (recipient == null || recipient == sender || !recipient.joined
        || sessions.get(recipient.session.getId()) != recipient || !recipient.session.isOpen()) {
      sendError(sender, "RECIPIENT_UNAVAILABLE", "El participante destinatario no está en esta sala.");
      return null;
    }
    return recipient;
  }

  private boolean requireJoined(Connection connection) {
    ConcurrentMap<String, Connection> room = rooms.get(connection.projectId);
    if (connection.joined && room != null && room.get(connection.participantId) == connection
        && sessions.get(connection.session.getId()) == connection && connection.session.isOpen()) return true;
    sendError(connection, "NOT_IN_ROOM", "Debes entrar a la sala antes de enviar señalización.");
    return false;
  }

  private void closeConnection(Connection connection, CloseStatus status) {
    if (connection == null || !sessions.remove(connection.session.getId(), connection)) return;
    ScheduledFuture<?> task = expiryTasks.remove(connection.session.getId());
    if (task != null) task.cancel(false);
    leave(connection);
    try {
      if (connection.session.isOpen()) connection.session.close(status);
    } catch (IOException exception) {
      log.debug("No se pudo cerrar la sesión de voz {}: {}", connection.session.getId(), exception.getMessage());
    }
  }

  private void broadcast(ConcurrentMap<String, Connection> room, Connection excluded, Map<String, Object> event) {
    for (Connection connection : room.values()) if (connection != excluded) send(connection, event);
  }

  private void sendError(Connection connection, String code, String message) {
    send(connection, Map.of("type", "VOICE_ERROR", "code", code, "message", message));
  }

  private void send(Connection connection, Object event) {
    if (!connection.session.isOpen()) return;
    try {
      String payload = mapper.writeValueAsString(event);
      synchronized (connection.session) {
        if (connection.session.isOpen()) connection.session.sendMessage(new TextMessage(payload));
      }
    } catch (IOException exception) {
      log.debug("Señalización de voz no entregada a {}", connection.session.getId());
      closeConnection(connection, CloseStatus.SERVER_ERROR);
    }
  }

  private static void validateIceServers(List<Map<String, Object>> servers) {
    for (Map<String, Object> server : servers) {
      Object urls = server.get("urls");
      if (urls instanceof String url) {
        if (url.isBlank()) throw new IllegalArgumentException("ICE server urls cannot be blank.");
      } else if (urls instanceof List<?> urlList) {
        if (urlList.isEmpty() || urlList.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) {
          throw new IllegalArgumentException("ICE server urls must contain non-empty strings.");
        }
      } else {
        throw new IllegalArgumentException("Each ICE server must define urls as a string or array.");
      }
      if (server.containsKey("username") && !(server.get("username") instanceof String)) {
        throw new IllegalArgumentException("ICE server username must be a string.");
      }
      if (server.containsKey("credential") && !(server.get("credential") instanceof String)) {
        throw new IllegalArgumentException("ICE server credential must be a string.");
      }
    }
  }

  private static ScheduledThreadPoolExecutor createExpiryExecutor() {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
      Thread thread = Executors.defaultThreadFactory().newThread(runnable);
      thread.setName("project-voice-token-expiry");
      thread.setDaemon(true);
      return thread;
    });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  private static final class Connection {
    private final WebSocketSession session;
    private final UUID projectId;
    private final String email;
    private final String name;
    private final String participantId;
    private volatile boolean joined;
    private volatile boolean muted = true;

    private Connection(WebSocketSession session, UUID projectId, String email, String name, String participantId) {
      this.session = session;
      this.projectId = projectId;
      this.email = email;
      this.name = name;
      this.participantId = participantId;
    }

    private Map<String, Object> participantView() {
      return Map.of("participantId", participantId, "name", name, "muted", muted, "connectionState", "connected");
    }
  }
}
