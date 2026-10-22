package bo.edu.caseapp.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AiMemoryRequests {
  private AiMemoryRequests() { }

  public record UpdateProjectMemory(
      @Size(max = 160) String systemName,
      @Size(max = 4000) String systemDescription,
      @Size(max = 1000) String businessDomain,
      @Size(max = 12000) String objectives,
      @Size(max = 12000) String additionalContext
  ) { }

  public record SaveEntry(
      @NotNull AiMemoryEntryType entryType,
      UUID diagramId,
      @NotBlank @Size(max = 4000) String content
  ) { }
}
