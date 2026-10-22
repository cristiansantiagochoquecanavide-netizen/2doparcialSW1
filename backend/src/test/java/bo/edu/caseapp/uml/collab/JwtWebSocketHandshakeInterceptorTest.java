package bo.edu.caseapp.uml.collab;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import bo.edu.caseapp.auth.JwtService;
import bo.edu.caseapp.error.ForbiddenException;
import bo.edu.caseapp.project.Project;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.DiagramaClase;
import bo.edu.caseapp.uml.DiagramaClaseRepository;
import bo.edu.caseapp.user.User;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

class JwtWebSocketHandshakeInterceptorTest {
  private JwtService jwtService;
  private ProjectService projectService;
  private DiagramaClaseRepository diagrams;
  private JwtWebSocketHandshakeInterceptor interceptor;
  private UUID projectId;
  private UUID diagramId;
  private DiagramaClase diagram;
  private WebSocketHandler handler;

  @BeforeEach
  void setUp() {
    jwtService = mock(JwtService.class);
    projectService = mock(ProjectService.class);
    diagrams = mock(DiagramaClaseRepository.class);
    interceptor = new JwtWebSocketHandshakeInterceptor(jwtService, projectService, diagrams);
    projectId = UUID.randomUUID();
    diagramId = UUID.randomUUID();
    Project project = new Project(new User("Owner", "owner@example.com", "hash"), "Project", null);
    org.springframework.test.util.ReflectionTestUtils.setField(project, "id", projectId);
    diagram = new DiagramaClase(project, "Diagram", null);
    org.springframework.test.util.ReflectionTestUtils.setField(diagram, "id", diagramId);
    when(diagrams.findById(diagramId)).thenReturn(Optional.of(diagram));
    handler = mock(WebSocketHandler.class);
  }

  @Test
  void handshakeRequiresJwtEvenWhenDiagramIdIsKnown() {
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    boolean accepted = interceptor.beforeHandshake(request(false), response, handler, new HashMap<>());

    assertFalse(accepted);
    verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(projectService, diagrams);
  }

  @Test
  void authenticatedProjectMemberCanConnectAndReceivesAuthorizedScope() {
    when(jwtService.extractEmail("signed-token")).thenReturn("member@example.com");
    Map<String, Object> attributes = new HashMap<>();

    boolean accepted = interceptor.beforeHandshake(request(true), mock(ServerHttpResponse.class), handler, attributes);

    assertTrue(accepted);
    verify(projectService).requireAccess(projectId, "member@example.com");
    assertTrue(attributes.get("authorizedProjectId").equals(projectId));
    assertTrue(attributes.get("authorizedDiagramId").equals(diagramId));
  }

  @Test
  void authenticatedUserFromAnotherProjectCannotSubscribeToDiagramEvents() {
    when(jwtService.extractEmail("signed-token")).thenReturn("outsider@example.com");
    doThrow(new ForbiddenException("No tienes acceso a este proyecto."))
        .when(projectService).requireAccess(projectId, "outsider@example.com");
    ServerHttpResponse response = mock(ServerHttpResponse.class);

    boolean accepted = interceptor.beforeHandshake(request(true), response, handler, new HashMap<>());

    assertFalse(accepted);
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    verify(projectService).requireAccess(projectId, "outsider@example.com");
  }

  @Test
  void projectMemberCanSubscribeToProjectChatWithoutDiagramLookup() {
    when(jwtService.extractEmail("signed-token")).thenReturn("member@example.com");
    Map<String, Object> attributes = new HashMap<>();
    ServerHttpRequest request = requestForPath("/ws/projects/" + projectId + "/chat", "signed-token");

    boolean accepted = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class), handler, attributes);

    assertTrue(accepted);
    verify(projectService).requireAccess(projectId, "member@example.com");
    verifyNoInteractions(diagrams);
    assertTrue(attributes.get("authorizedProjectId").equals(projectId));
    assertTrue(attributes.get("authorizedScopeType").equals("PROJECT_CHAT"));
  }

  @Test
  void projectOutsiderCannotSubscribeToProjectChat() {
    when(jwtService.extractEmail("signed-token")).thenReturn("outsider@example.com");
    doThrow(new ForbiddenException("No tienes acceso a este proyecto."))
        .when(projectService).requireAccess(projectId, "outsider@example.com");
    ServerHttpResponse response = mock(ServerHttpResponse.class);

    boolean accepted = interceptor.beforeHandshake(
        requestForPath("/ws/projects/" + projectId + "/chat", "signed-token"), response, handler, new HashMap<>());

    assertFalse(accepted);
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    verifyNoInteractions(diagrams);
  }

  @Test
  void projectMemberCanSubscribeToProjectVoiceWithoutDiagramLookup() {
    when(jwtService.extractEmail("signed-token")).thenReturn("member@example.com");
    when(jwtService.extractExpiration("signed-token")).thenReturn(java.time.Instant.now().plusSeconds(300));
    Map<String, Object> attributes = new HashMap<>();
    ServerHttpRequest request = requestForPath("/ws/projects/" + projectId + "/voice", "signed-token");

    boolean accepted = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class), handler, attributes);

    assertTrue(accepted);
    verify(projectService).requireAccess(projectId, "member@example.com");
    verifyNoInteractions(diagrams);
    assertTrue(attributes.get("authorizedProjectId").equals(projectId));
    assertTrue(attributes.get("authorizedScopeType").equals("PROJECT_VOICE"));
  }

  @Test
  void projectOutsiderCannotSubscribeToProjectVoice() {
    when(jwtService.extractEmail("signed-token")).thenReturn("outsider@example.com");
    doThrow(new ForbiddenException("No tienes acceso a este proyecto."))
        .when(projectService).requireAccess(projectId, "outsider@example.com");
    ServerHttpResponse response = mock(ServerHttpResponse.class);

    boolean accepted = interceptor.beforeHandshake(
        requestForPath("/ws/projects/" + projectId + "/voice", "signed-token"), response, handler, new HashMap<>());

    assertFalse(accepted);
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    verifyNoInteractions(diagrams);
  }

  @Test
  void invalidJwtCannotSubscribe() {
    when(jwtService.extractEmail("invalid-token")).thenThrow(new IllegalArgumentException("invalid"));
    ServerHttpResponse response = mock(ServerHttpResponse.class);

    boolean accepted = interceptor.beforeHandshake(requestWithToken("invalid-token"), response, handler, new HashMap<>());

    assertFalse(accepted);
    verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(projectService, diagrams);
  }

  private ServerHttpRequest request(boolean withToken) {
    return requestWithToken(withToken ? "signed-token" : null);
  }

  private ServerHttpRequest requestWithToken(String token) {
    return requestForPath("/ws/diagrams/" + diagramId, token);
  }

  private ServerHttpRequest requestForPath(String path, String token) {
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    HttpHeaders headers = new HttpHeaders();
    headers.setOrigin("http://localhost:5173");
    headers.add("Sec-WebSocket-Protocol", token == null
        ? "case-events-v1"
        : "case-events-v1, bearer." + token);
    when(request.getHeaders()).thenReturn(headers);
    when(request.getURI()).thenReturn(java.net.URI.create("ws://localhost" + path));
    return request;
  }
}
