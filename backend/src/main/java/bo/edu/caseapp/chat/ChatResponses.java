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
      UUID replyToMessageId
  ) {}

  public record MessagePage(List<Message> messages, UUID nextCursor, boolean hasMore) {}

  public record SendMessageResult(Message message, boolean created) {}
}
