package bo.edu.caseapp.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class ChatRequests {
  private ChatRequests() {}

  public record SendMessage(
      @NotNull UUID clientMessageId,
      @NotBlank @Size(max = 4000) String content,
      UUID replyToMessageId
  ) {}
}
