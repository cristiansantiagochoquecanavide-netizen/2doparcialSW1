package bo.edu.caseapp.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AiConversationResponses {
  private AiConversationResponses() { }
  public record Conversation(UUID id, UUID projectId, UUID diagramId, AiConversationScope scope,
      String title, UUID createdBy, String createdByName, Instant createdAt, Instant updatedAt,
      Instant archivedAt) { }
  public record Message(UUID id, UUID clientMessageId, AiConversationSenderType senderType,
      UUID senderUserId, String senderName, UUID recipientUserId, String recipientName,
      String content, AiConversationMessageStatus status, String errorCode, int promptTokens,
      int completionTokens, Long latencyMs, UUID replyToMessageId, Instant createdAt) { }
  public record MessagePage(List<Message> messages, UUID nextCursor, boolean hasMore) { }
  public record Turn(Message question, Message answer, boolean created) { }
}
