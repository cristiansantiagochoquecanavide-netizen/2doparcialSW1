package bo.edu.caseapp.ai;

import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.project.ProjectMember;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.DiagramaClase;
import bo.edu.caseapp.uml.DiagramaClaseRepository;
import bo.edu.caseapp.uml.ElementoUML;
import bo.edu.caseapp.uml.ElementoUMLRepository;
import bo.edu.caseapp.uml.RelacionUML;
import bo.edu.caseapp.uml.RelacionUMLRepository;
import bo.edu.caseapp.uml.UmlService;
import bo.edu.caseapp.uml.validation.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds a bounded semantic snapshot inside a short read transaction. */
@Service
public class AiContextService {
  private static final int DEFAULT_CONTEXT_CHARS = 12_000;
  private final ProjectService projects;
  private final DiagramaClaseRepository diagrams;
  private final ElementoUMLRepository elements;
  private final RelacionUMLRepository relations;
  private final UmlService uml;
  private final ObjectMapper mapper;
  private final int contextLimit;
  @Autowired(required = false) private AiMemoryService memoryService;

  public AiContextService(ProjectService projects, DiagramaClaseRepository diagrams,
      ElementoUMLRepository elements, RelacionUMLRepository relations, UmlService uml,
      ObjectMapper mapper, @Value("${app.ai.context-max-chars:12000}") int contextLimit) {
    this.projects = projects;
    this.diagrams = diagrams;
    this.elements = elements;
    this.relations = relations;
    this.uml = uml;
    this.mapper = mapper;
    this.contextLimit = contextLimit > 0 ? contextLimit : DEFAULT_CONTEXT_CHARS;
  }

  @Transactional(readOnly = true)
  public String loadAuthorizedContext(String email, UUID projectId, UUID diagramId) {
    ProjectMember member = projects.requireAccess(projectId, email);
    DiagramaClase diagram = diagrams.findById(diagramId)
        .orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    if (!diagram.getProject().getId().equals(projectId)) {
      throw new NotFoundException("El diagrama no pertenece a este proyecto.");
    }
    List<ElementoUML> diagramElements = elements.findByDiagramaId(diagramId);
    List<RelacionUML> diagramRelations = relations.findByDiagramaId(diagramId);
    ValidationReport report = uml.validateDiagram(projectId, diagramId, member.getUser().getEmail());
    List<UmlAiContext.ClassContext> classes = diagramElements.stream().map(this::toClassContext).toList();
    List<UmlAiContext.RelationContext> relationContexts = diagramRelations.stream().map(relation ->
        new UmlAiContext.RelationContext(relation.getId(), relation.getElementoOrigen().getNombre(),
            relation.getElementoDestino().getNombre(), relation.getNombreRelacion(), relation.getTipoRelacion().name(),
            relation.getMultiplicidadOrigen(), relation.getMultiplicidadDestino())).toList();
    List<String> validationResults = report.issues().stream().limit(40)
        .map(issue -> issue.severity() + ": " + issue.mensaje()).toList();
    UmlAiContext.ProjectMemoryContext memoryContext = memoryContext(projectId, diagramId, member);
    UmlAiContext context = new UmlAiContext(member.getUser().getId(), projectId, diagram.getProject().getName(),
        diagramId, diagram.getNombre(), diagram.getDescripcion(), diagram.getRevision(),
        classes, relationContexts, validationResults, memoryContext);
    return boundedContextJson(context);
  }

  @Transactional(readOnly = true)
  public void authorize(String email, UUID projectId, UUID diagramId) {
    projects.requireAccess(projectId, email);
    var diagram = diagrams.findById(diagramId).orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    if (!diagram.getProject().getId().equals(projectId)) throw new NotFoundException("Diagrama no encontrado.");
  }

  @Transactional(readOnly = true)
  public String loadAuthorizedProjectContext(String email, UUID projectId) {
    ProjectMember member = projects.requireAccess(projectId, email);
    var project = member.getProject();
    ObjectNode root = mapper.createObjectNode().put("userId", member.getUser().getId().toString())
        .put("projectId", projectId.toString()).put("projectName", project.getName());
    if (project.getDescription() != null) root.put("projectDescription", project.getDescription());
    ArrayNode diagramArray = root.putArray("diagrams");
    diagrams.findByProjectIdOrderByFechaCreacionAsc(projectId).stream().limit(20).forEach(diagram ->
        diagramArray.addObject().put("id", diagram.getId().toString()).put("name", diagram.getNombre())
            .put("revision", diagram.getRevision()));
    root.set("memory", mapper.valueToTree(memoryContext(projectId, null, member)));
    root.put("contextTruncated", diagrams.findByProjectIdOrderByFechaCreacionAsc(projectId).size() > 20);
    try {
      String serialized = mapper.writeValueAsString(root);
      if (serialized.length() <= contextLimit) return serialized;
      diagramArray.removeAll();
      root.put("contextTruncated", true);
      serialized = mapper.writeValueAsString(root);
      if (serialized.length() <= contextLimit) return serialized;
      root.remove("projectDescription");
      root.remove("memory");
      return mapper.writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo preparar el contexto del proyecto.", e);
    }
  }

  private UmlAiContext.ProjectMemoryContext memoryContext(UUID projectId, UUID diagramId, ProjectMember member) {
    if (memoryService == null) return null;
    AiMemoryResponses.MemoryView saved = memoryService.get(projectId, diagramId, member.getUser().getEmail());
    AiMemoryResponses.ProjectMemory memory = saved.projectMemory();
    List<UmlAiContext.MemoryEntryContext> entries = saved.entries().stream()
        .filter(entry -> entry.diagramId() == null || (diagramId != null && diagramId.equals(entry.diagramId())))
        .map(entry ->
        new UmlAiContext.MemoryEntryContext(entry.entryType().name(), entry.confidenceScope().name(),
            entry.content(), entry.updatedByName(), entry.updatedAt().toString())).toList();
    return memory == null ? null : new UmlAiContext.ProjectMemoryContext(memory.systemName(), memory.systemDescription(), memory.businessDomain(),
        memory.objectives(), memory.additionalContext(), entries);
  }

  private UmlAiContext.ClassContext toClassContext(ElementoUML element) {
    try {
      JsonNode properties = mapper.readTree(element.getPropiedadesJson());
      return new UmlAiContext.ClassContext(element.getId(), element.getNombre(), element.getTipoElemento().name(),
          readMembers(properties.path("attributes"), false), readMembers(properties.path("methods"), true));
    } catch (Exception ignored) {
      return new UmlAiContext.ClassContext(element.getId(), element.getNombre(), element.getTipoElemento().name(), List.of(), List.of());
    }
  }

  private List<UmlAiContext.MemberContext> readMembers(JsonNode values, boolean method) {
    if (!values.isArray()) return List.of();
    List<UmlAiContext.MemberContext> result = new ArrayList<>();
    values.forEach(value -> result.add(new UmlAiContext.MemberContext(value.path("id").asText(""), value.path("visibility").asText(""),
        value.path("name").asText(""), method ? value.path("returnType").asText(value.path("type").asText(""))
            : value.path("type").asText(""), method ? value.path("parameters").asText("") : "")));
    return result;
  }

  private String boundedContextJson(UmlAiContext context) {
    ObjectNode root = mapper.valueToTree(context);
    try {
      String serialized = mapper.writeValueAsString(root);
      boolean truncated = false;
      for (String arrayName : List.of("validationResults", "relations", "classes")) {
        ArrayNode values = (ArrayNode) root.path(arrayName);
        while (serialized.length() > contextLimit && values.size() > 0) {
          values.remove(values.size() - 1);
          truncated = true;
          serialized = mapper.writeValueAsString(root);
        }
      }
      if (truncated) root.put("contextTruncated", true);
      while (serialized.length() > contextLimit && root.path("diagramDescription").isTextual()) {
        String description = root.path("diagramDescription").asText();
        if (description.isEmpty()) break;
        root.put("diagramDescription", description.substring(0, description.length() / 2));
        serialized = mapper.writeValueAsString(root);
      }
      if (serialized.length() <= contextLimit) return serialized;
      return mapper.writeValueAsString(mapper.createObjectNode().put("userId", context.userId().toString())
          .put("projectId", context.projectId().toString()).put("diagramId", context.diagramId().toString())
          .put("revision", context.revision()).put("contextTruncated", true));
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo preparar el contexto UML.", e);
    }
  }
}
