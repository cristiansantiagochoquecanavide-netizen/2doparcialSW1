package bo.edu.caseapp.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import bo.edu.caseapp.ai.provider.LlmMessage;
import bo.edu.caseapp.ai.provider.LlmProvider;
import bo.edu.caseapp.ai.provider.LlmResponse;
import bo.edu.caseapp.error.ForbiddenException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiOrchestrationServiceTest {
  private AiContextService contexts;
  private LlmProvider provider;
  private AiOrchestrationService service;

  @BeforeEach
  void setUp() {
    contexts = mock(AiContextService.class);
    provider = mock(LlmProvider.class);
    service = new AiOrchestrationService(contexts, provider, new ObjectMapper(), 800, 800, 1500, 1000);
  }

  @Test
  void generate_rejectsUnauthorizedProjectBeforeLoadingDiagramData() {
    UUID projectId = UUID.randomUUID();
    UUID diagramId = UUID.randomUUID();
    when(contexts.loadAuthorizedContext("outsider@example.com", projectId, diagramId))
        .thenThrow(new ForbiddenException("No access"));

    assertThrows(ForbiddenException.class, () -> service.generate("outsider@example.com", projectId,
        diagramId, AiOperation.TECHNICAL_CHAT, "instructions", List.of(LlmMessage.user("question")), 500, 0.2));

    verifyNoInteractions(provider);
  }

  @Test
  void generate_buildsSemanticContextWithoutCanvasCoordinates() throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID diagramId = UUID.randomUUID();
    when(contexts.loadAuthorizedContext("member@example.com", projectId, diagramId))
        .thenReturn("{\"projectName\":\"Tienda\",\"classes\":[{\"name\":\"Cliente\",\"attributes\":[{\"name\":\"email\"}]}],\"revision\":7}");
    when(provider.chat(anyList(), anyInt(), anyDouble())).thenReturn(LlmResponse.success("respuesta", 12, 4, "qwen/qwen3.8-27b"));

    LlmResponse response = service.generate("member@example.com", projectId, diagramId,
        AiOperation.TECHNICAL_CHAT, "instructions", List.of(LlmMessage.user("pregunta")), 900, 0.2);

    assertTrue(response.success());
    ArgumentCaptor<List<LlmMessage>> messages = ArgumentCaptor.forClass(List.class);
    verify(provider).chat(messages.capture(), eq(800), eq(0.2));
    String systemMessage = messages.getValue().get(0).content();
    assertTrue(systemMessage.contains("Tienda"));
    assertTrue(systemMessage.contains("Cliente"));
    assertTrue(systemMessage.contains("email"));
    assertTrue(systemMessage.contains("\"revision\":7"));
    assertFalse(systemMessage.contains("posicionX"));
    assertFalse(systemMessage.contains("posicionY"));
  }
}
