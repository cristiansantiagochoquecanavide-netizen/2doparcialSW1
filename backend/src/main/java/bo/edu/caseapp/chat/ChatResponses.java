package bo.edu.caseapp.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChatResponses {
  private ChatResponses() {}

  public record Channel(
      UUID id,
      String slug,
      String name,
      String description,
      Instant createdAt
  ) {}

  public record Message(
      UUID id,
      UUID channelId,
      UUID senderUserId,
      String senderName,
      String senderEmail,
      String senderRole,
      String authorType,
      UUID clientMessageId,
      String content,
      Instant createdAt,
      UUID replyToMessageId,
      UUID canonicalAiMessageId,
      Invocation acodi
  ) { public Message(UUID id,UUID channelId,UUID senderUserId,String senderName,String senderEmail,String senderRole,String authorType,UUID clientMessageId,String content,Instant createdAt,UUID replyToMessageId) {
    this(id,channelId,senderUserId,senderName,senderEmail,senderRole,authorType,clientMessageId,content,createdAt,replyToMessageId,null,null);
  } }
  public record Invocation(String status,UUID diagramId,int attempts,String errorCode,Instant retryAfter,UUID responseMessageId,int maxAttempts) { }

  public record MessagePage(List<Message> messages, UUID nextCursor, boolean hasMore) {}

  public record SendMessageResult(Message message, boolean created) {}
}
