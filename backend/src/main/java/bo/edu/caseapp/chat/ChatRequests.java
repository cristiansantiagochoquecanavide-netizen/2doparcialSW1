package bo.edu.caseapp.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import java.util.List;

public final class ChatRequests {
  private ChatRequests() {}

  public record ChannelName(@NotBlank @Size(max = 100) String name) {}

  public record SendMessage(
      @NotNull UUID clientMessageId,
      @NotBlank @Size(max = 4000) String content,
      UUID replyToMessageId,
      UUID diagramId,
      @Size(max = 2) List<@NotNull UUID> comparisonVersionIds
  ) {
    public SendMessage(UUID clientMessageId,String content,UUID replyToMessageId) {this(clientMessageId,content,replyToMessageId,null,null);}
    public SendMessage(UUID clientMessageId,String content,UUID replyToMessageId,UUID diagramId) {this(clientMessageId,content,replyToMessageId,diagramId,null);}
  }
}
