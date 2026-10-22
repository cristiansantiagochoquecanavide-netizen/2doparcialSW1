package bo.edu.caseapp.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AiMemoryResponses {
  private AiMemoryResponses() { }

  public record ProjectMemory(UUID projectId, String systemName, String systemDescription,
      String businessDomain, String objectives, String additionalContext,
      UUID updatedBy, String updatedByName, Instant updatedAt) { }

  public record Entry(UUID id, UUID projectId, UUID diagramId, AiMemoryEntryType entryType,
      AiMemoryConfidence confidenceScope, String content, UUID createdBy, String createdByName,
      UUID updatedBy, String updatedByName, Instant createdAt, Instant updatedAt) { }

  public record MemoryView(ProjectMemory projectMemory, List<Entry> entries) { }
}
