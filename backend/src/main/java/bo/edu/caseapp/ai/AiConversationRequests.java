package bo.edu.caseapp.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import java.util.List;

public final class AiConversationRequests {
  private AiConversationRequests() { }
  public record Create(@NotNull AiConversationScope scope, UUID diagramId, @NotBlank @Size(max = 160) String title) { }
  public record Rename(@NotBlank @Size(max = 160) String title) { }
  public record Send(@NotNull UUID clientMessageId, @NotBlank @Size(max = 8000) String content,
      @Size(max = 2) List<@NotNull UUID> comparisonVersionIds) {
    public Send(UUID clientMessageId, String content) { this(clientMessageId, content, null); }
  }
  public record ComparisonSelection(@Size(max = 2) List<@NotNull UUID> comparisonVersionIds) { }
}
