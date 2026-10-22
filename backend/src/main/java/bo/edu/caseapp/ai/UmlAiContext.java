package bo.edu.caseapp.ai;

import java.util.List;
import java.util.UUID;

/** Contexto semántico acotado para IA. No incluye coordenadas ni estilos del lienzo. */
public record UmlAiContext(
    UUID userId,
    UUID projectId,
    String projectName,
    UUID diagramId,
    String diagramName,
    String diagramDescription,
    long revision,
    List<ClassContext> classes,
    List<RelationContext> relations,
    List<String> validationResults,
    ProjectMemoryContext memory
) {
  public record ProjectMemoryContext(String systemName, String systemDescription, String businessDomain,
      String objectives, String additionalContext, List<MemoryEntryContext> entries) { }
  public record MemoryEntryContext(String type, String confidence, String content, String updatedBy, String updatedAt) { }
  public record ClassContext(UUID id, String name, String type, List<MemberContext> attributes, List<MemberContext> methods) { }
  public record MemberContext(String id, String visibility, String name, String type, String parameters) { }
  public record RelationContext(UUID id, String source, String target, String name, String type,
      String sourceMultiplicity, String targetMultiplicity) { }
}
