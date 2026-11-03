package bo.edu.caseapp.versioning;

import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.DiagramaClase;
import bo.edu.caseapp.uml.DiagramaClaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves an explicitly requested CU09 comparison against authorized immutable snapshots. */
@Service
public class VersionComparisonService {
  private static final Pattern VERSION_PAIR = Pattern.compile("\\bv(\\d+)\\s+(?:con|y|vs|contra)\\s+v(\\d+)\\b");
  private static final int MAX_CHANGES = 40;
  private final ProjectService projects;
  private final DiagramaClaseRepository diagrams;
  private final VersionDiagramaRepository versions;
  private final ObjectMapper mapper;

  public VersionComparisonService(ProjectService projects, DiagramaClaseRepository diagrams,
      VersionDiagramaRepository versions, ObjectMapper mapper) {
    this.projects = projects; this.diagrams = diagrams; this.versions = versions; this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public Result resolve(UUID projectId, UUID diagramId, String email, String question, List<UUID> selectedIds) {
    String normalized = normalize(question);
    Matcher explicit = VERSION_PAIR.matcher(normalized);
    boolean explicitPair = explicit.find();
    boolean comparison = explicitPair || normalized.contains("compar") || normalized.contains("diferencias entre versiones")
        || normalized.contains("cambios entre versiones");
    if (!comparison) return Result.notRequested();
    projects.requireAccess(projectId, email);
    if (diagramId == null) return Result.clarification("Abre el diagrama que quieres comparar y vuelve a pedir la comparación.");
    DiagramaClase diagram = diagrams.findById(diagramId).orElse(null);
    if (diagram == null || !projectId.equals(diagram.getProject().getId()))
      return Result.clarification("No pude validar el diagrama de esta comparación. Abre el diagrama del proyecto y vuelve a intentarlo.");

    VersionDiagrama before;
    VersionDiagrama after;
    if (explicitPair) {
      try {
        before = versions.findByDiagramaIdAndNumeroVersion(diagramId, Integer.parseInt(explicit.group(1))).orElse(null);
        after = versions.findByDiagramaIdAndNumeroVersion(diagramId, Integer.parseInt(explicit.group(2))).orElse(null);
      } catch (NumberFormatException invalid) { return Result.clarification("No pude identificar esos números de versión. Indica dos versiones existentes, por ejemplo: compara V2 con V5."); }
    } else {
      if (selectedIds == null || selectedIds.size() != 2 || Objects.equals(selectedIds.get(0), selectedIds.get(1)))
        return Result.clarification("Selecciona dos versiones en el comparador CU09 o indica cuáles comparar, por ejemplo: compara V2 con V5.");
      before = versions.findByIdAndDiagramaId(selectedIds.get(0), diagramId).orElse(null);
      after = versions.findByIdAndDiagramaId(selectedIds.get(1), diagramId).orElse(null);
    }
    if (before == null || after == null || before.getId().equals(after.getId()))
      return Result.clarification("No encontré las dos versiones en el diagrama autorizado. Selecciónalas de nuevo o indica dos versiones existentes.");
    return Result.compared("Comparación CU09 determinista entre v" + before.getNumeroVersion() + " y v" + after.getNumeroVersion()
        + " (snapshots inmutables; los IDs se resolvieron y validaron en este proyecto y diagrama):\n"
        + diff(before, after));
  }

  private String diff(VersionDiagrama before, VersionDiagrama after) {
    try {
      JsonNode a = mapper.readTree(before.getSnapshotJson());
      JsonNode b = mapper.readTree(after.getSnapshotJson());
      StringBuilder out = new StringBuilder();
      Map<String, String> classNames = classNames(a.path("elements"));
      classNames.putAll(classNames(b.path("elements")));
      appendDiff(out, "CLASE", byId(a.path("elements")), byId(b.path("elements")), Map.of());
      appendDiff(out, "RELACION", byId(a.path("relations")), byId(b.path("relations")), classNames);
      if (out.isEmpty()) out.append("No se detectaron diferencias estructurales entre los snapshots.");
      out.append("\nContexto UML de ").append(after.getNumeroVersion()).append(": ");
      out.append(context(b));
      return out.length() > 6000 ? out.substring(0, 5900) + "\n[resumen truncado]" : out.toString();
    } catch (Exception invalidSnapshot) {
      return "No se pudo leer un snapshot del historial. No se modificó ninguna versión.";
    }
  }

  private Map<String, JsonNode> byId(JsonNode array) {
    Map<String, JsonNode> result = new TreeMap<>();
    if (array.isArray()) array.forEach(item -> { if (item.path("id").isTextual()) result.put(item.path("id").asText(), item); });
    return result;
  }

  private void appendDiff(StringBuilder out, String kind, Map<String, JsonNode> before, Map<String, JsonNode> after, Map<String, String> classNames) {
    Set<String> ids = new TreeSet<>(); ids.addAll(before.keySet()); ids.addAll(after.keySet());
    int count = 0;
    for (String id : ids) {
      JsonNode old = before.get(id), current = after.get(id);
      if (Objects.equals(old, current)) continue;
      if (count++ == MAX_CHANGES) { out.append("\n… hay más cambios omitidos"); break; }
      String label = label(current != null ? current : old, classNames);
      out.append("\n- ").append(kind).append(" ").append(label).append(": ")
          .append(old == null ? "agregada" : current == null ? "eliminada" : "modificada");
      if (old != null) out.append("; antes=").append(compact(old));
      if (current != null) out.append("; después=").append(compact(current));
    }
  }

  private Map<String, String> classNames(JsonNode array) {
    Map<String, String> names = new HashMap<>();
    if (array.isArray()) array.forEach(item -> names.put(item.path("id").asText(), item.path("nombre").asText("clase")));
    return names;
  }

  private String context(JsonNode snapshot) {
    List<String> classes = new ArrayList<>(), relations = new ArrayList<>();
    JsonNode elements = snapshot.path("elements");
    if (elements.isArray()) elements.forEach(item -> { if (classes.size() < 30) classes.add(label(item) + " [" + item.path("tipoElemento").asText("UML") + "]"); });
    JsonNode links = snapshot.path("relations");
    if (links.isArray()) links.forEach(item -> { if (relations.size() < 40) relations.add(label(item) + " (" + item.path("tipoRelacion").asText("RELACION") + ")"); });
    return "clases=" + classes + "; relaciones=" + relations;
  }

  private String label(JsonNode node) { return label(node, Map.of()); }

  private String label(JsonNode node, Map<String, String> classNames) {
    if (!classNames.isEmpty()) {
      String source = classNames.get(node.path("elementoOrigen").asText());
      String target = classNames.get(node.path("elementoDestino").asText());
      if (source != null || target != null) return (source == null ? "?" : source) + " → " + (target == null ? "?" : target);
    }
    String name = node.path("nombre").asText("");
    if (name.isBlank()) name = node.path("nombreRelacion").asText("");
    if (name.isBlank()) name = node.path("id").asText("sin nombre");
    return name.replaceAll("[\\r\\n]", " ");
  }

  private String compact(JsonNode node) {
    String json = node.toString(); return json.length() > 700 ? json.substring(0, 680) + "…}" : json;
  }

  private String normalize(String text) {
    if (text == null) return "";
    return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
  }

  public record Result(boolean requested, String clarification, String context) {
    static Result notRequested() { return new Result(false, null, null); }
    static Result clarification(String text) { return new Result(true, text, null); }
    static Result compared(String context) { return new Result(true, null, context); }
  }
}
