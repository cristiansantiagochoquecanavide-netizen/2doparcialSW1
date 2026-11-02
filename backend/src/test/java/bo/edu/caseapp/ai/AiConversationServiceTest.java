package bo.edu.caseapp.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import bo.edu.caseapp.ai.AiConversationResponses.Conversation;
import bo.edu.caseapp.ai.AiConversationResponses.Message;
import bo.edu.caseapp.ai.AiConversationResponses.Turn;
import bo.edu.caseapp.ai.provider.LlmResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AiConversationServiceTest {
  private AiConversationStore store;
  private AiOrchestrationService orchestration;
  private AiConversationService service;
  private UUID projectId, conversationId, questionId, userId;
  private Conversation conversation;
  private Message question;

  @BeforeEach void setup() {
    store = mock(AiConversationStore.class);
    orchestration = mock(AiOrchestrationService.class);
    service = new AiConversationService(store, orchestration, 400);
    projectId = UUID.randomUUID(); conversationId = UUID.randomUUID(); questionId = UUID.randomUUID(); userId = UUID.randomUUID();
    conversation = new Conversation(conversationId, projectId, null, AiConversationScope.GROUP, "Diseño", userId,
        "Carmen", Instant.now(), Instant.now(), null);
    question = new Message(questionId, UUID.randomUUID(), AiConversationSenderType.USER, userId, "Carmen", null,
        null, "¿Qué multiplicidad uso?", AiConversationMessageStatus.PENDING, null, 0, 0, null, null, Instant.now());
    when(store.addQuestion(eq(projectId), eq(conversationId), eq("carmen@example.com"), any()))
        .thenReturn(new AiConversationStore.QuestionReceipt(conversation, question, true));
    when(store.recentPromptTurns(conversationId, questionId)).thenReturn(List.of());
  }

  @Test void sendsAuthorizedConversationThroughSharedOrchestratorAndPersistsNamedReply() {
    when(orchestration.generate(anyString(), eq(projectId), isNull(), eq(AiOperation.AI_CONVERSATION_GROUP),
        anyString(), anyList(), eq(400), eq(0.3))).thenReturn(success("Carmen, usa uno a muchos."));
    Turn saved = new Turn(question, new Message(UUID.randomUUID(), null, AiConversationSenderType.AGENT, null,
        "Agente CASE IA", userId, "Carmen", "Carmen, usa uno a muchos.", AiConversationMessageStatus.COMPLETED,
        null, 5, 8, 30L, questionId, Instant.now()), true);
    when(store.completeQuestion(eq(projectId), eq(conversationId), eq(questionId), eq("carmen@example.com"),
        eq("Carmen, usa uno a muchos."), eq(5), eq(8), anyLong())).thenReturn(saved);

    Turn result = service.send(projectId, conversationId, "carmen@example.com", new AiConversationRequests.Send(UUID.randomUUID(), question.content()));

    assertEquals("Carmen", result.answer().recipientName());
    ArgumentCaptor<String> instructions = ArgumentCaptor.forClass(String.class);
    verify(orchestration).generate(eq("carmen@example.com"), eq(projectId), isNull(),
        eq(AiOperation.AI_CONVERSATION_GROUP), instructions.capture(), anyList(), eq(400), eq(0.3));
    assertTrue(instructions.getValue().contains("dirígete por su nombre"));
    verify(store).completeQuestion(eq(projectId), eq(conversationId), eq(questionId), eq("carmen@example.com"),
        anyString(), anyInt(), anyInt(), anyLong());
  }

  @Test void failedProviderResponsePersistsErrorForExplicitRetry() {
    when(orchestration.generate(anyString(), eq(projectId), isNull(), eq(AiOperation.AI_CONVERSATION_GROUP),
        anyString(), anyList(), eq(400), eq(0.3))).thenReturn(LlmResponse.failure("Unavailable", 503));
    Turn failed = new Turn(question, null, true);
    when(store.failQuestion(projectId, conversationId, questionId, "carmen@example.com", 503)).thenReturn(failed);

    assertNull(service.send(projectId, conversationId, "carmen@example.com", new AiConversationRequests.Send(UUID.randomUUID(), question.content())).answer());
    verify(store).failQuestion(projectId, conversationId, questionId, "carmen@example.com", 503);
    verify(store, never()).completeQuestion(any(), any(), any(), anyString(), anyString(), anyInt(), anyInt(), anyLong());
  }

  @Test void duplicateIdempotencyKeyReturnsExistingTurnWithoutCallingModel() {
    when(store.addQuestion(eq(projectId), eq(conversationId), eq("carmen@example.com"), any()))
        .thenReturn(new AiConversationStore.QuestionReceipt(conversation, question, false));
    when(store.turn(projectId, conversationId, questionId, "carmen@example.com")).thenReturn(new Turn(question, null, false));

    Turn result = service.send(projectId, conversationId, "carmen@example.com", new AiConversationRequests.Send(UUID.randomUUID(), question.content()));

    assertFalse(result.created());
    verify(orchestration, never()).generate(anyString(), any(), any(), any(), anyString(), anyList(), any(), any());
  }

  @Test void selectedVersionContextIsValidatedAndAddedOnlyToRequestedPrivateOrGroupChatTurn() {
    var comparison = mock(bo.edu.caseapp.versioning.VersionComparisonService.class);
    ReflectionTestUtils.setField(service, "versionComparisons", comparison);
    List<UUID> selected = List.of(UUID.randomUUID(), UUID.randomUUID());
    when(comparison.resolve(projectId, null, "carmen@example.com", question.content(), selected))
        .thenReturn(new bo.edu.caseapp.versioning.VersionComparisonService.Result(true, null, "Comparación CU09: Venta modificada; Pago agregada."));
    when(orchestration.generate(anyString(), eq(projectId), isNull(), eq(AiOperation.AI_CONVERSATION_GROUP),
        anyString(), anyList(), eq(400), eq(0.3))).thenReturn(success("Carmen, se agregó Pago."));
    when(store.completeQuestion(eq(projectId), eq(conversationId), eq(questionId), eq("carmen@example.com"),
        anyString(), eq(5), eq(8), anyLong())).thenReturn(new Turn(question, question, true));

    service.send(projectId, conversationId, "carmen@example.com", new AiConversationRequests.Send(UUID.randomUUID(), question.content(), selected));

    verify(comparison).resolve(projectId, null, "carmen@example.com", question.content(), selected);
    ArgumentCaptor<String> instructions = ArgumentCaptor.forClass(String.class);
    verify(orchestration).generate(eq("carmen@example.com"), eq(projectId), isNull(),
        eq(AiOperation.AI_CONVERSATION_GROUP), instructions.capture(), anyList(), eq(400), eq(0.3));
    assertTrue(instructions.getValue().contains("Comparación CU09: Venta modificada; Pago agregada."));
  }

  @Test void comparisonWithoutSelectedOrExplicitVersionsAsksInChatWithoutCallingModel() {
    var comparison = mock(bo.edu.caseapp.versioning.VersionComparisonService.class);
    ReflectionTestUtils.setField(service, "versionComparisons", comparison);
    when(comparison.resolve(projectId, null, "carmen@example.com", question.content(), null))
        .thenReturn(new bo.edu.caseapp.versioning.VersionComparisonService.Result(true,
            "Selecciona dos versiones en el comparador CU09.", null));
    when(store.completeQuestion(eq(projectId), eq(conversationId), eq(questionId), eq("carmen@example.com"),
        contains("Selecciona dos versiones"), eq(0), eq(0), eq(0L))).thenReturn(new Turn(question, question, true));

    service.send(projectId, conversationId, "carmen@example.com", new AiConversationRequests.Send(UUID.randomUUID(), question.content()));

    verify(store).completeQuestion(eq(projectId), eq(conversationId), eq(questionId), eq("carmen@example.com"),
        contains("Selecciona dos versiones"), eq(0), eq(0), eq(0L));
    verifyNoInteractions(orchestration);
  }

  @Test void privateRetryReceivesTheCurrentSessionSelectionAgain() {
    var comparison = mock(bo.edu.caseapp.versioning.VersionComparisonService.class);
    ReflectionTestUtils.setField(service, "versionComparisons", comparison);
    List<UUID> selected = List.of(UUID.randomUUID(), UUID.randomUUID());
    when(store.retryQuestion(projectId, conversationId, questionId, "carmen@example.com"))
        .thenReturn(new AiConversationStore.QuestionReceipt(conversation, question, true));
    when(comparison.resolve(projectId, null, "carmen@example.com", question.content(), selected))
        .thenReturn(new bo.edu.caseapp.versioning.VersionComparisonService.Result(true, null, "CU09 v1 a v2"));
    when(orchestration.generate(anyString(), eq(projectId), isNull(), eq(AiOperation.AI_CONVERSATION_GROUP),
        anyString(), anyList(), eq(400), eq(0.3))).thenReturn(success("Carmen, comparación lista."));
    when(store.completeQuestion(eq(projectId), eq(conversationId), eq(questionId), eq("carmen@example.com"),
        anyString(), eq(5), eq(8), anyLong())).thenReturn(new Turn(question, question, true));

    service.retry(projectId, conversationId, questionId, "carmen@example.com", selected);

    verify(comparison).resolve(projectId, null, "carmen@example.com", question.content(), selected);
  }

  private LlmResponse success(String content) { return LlmResponse.success(content, 5, 8, "qwen/qwen3.8-27b"); }
}
