package bo.edu.caseapp.ai;

import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.uml.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Full canonical fingerprint. Only the compact authorized context goes to Groq. */
@Service
public class AiSemanticSnapshot {
  private final DiagramaClaseRepository diagrams;
  private final ElementoUMLRepository elements;
  private final RelacionUMLRepository relations;
  private final ProjectAiMemoryRepository memories;
  private final ProjectAiMemoryEntryRepository entries;
  private final ObjectMapper mapper;
  public AiSemanticSnapshot(DiagramaClaseRepository diagrams, ElementoUMLRepository elements,
      RelacionUMLRepository relations, ProjectAiMemoryRepository memories,
      ProjectAiMemoryEntryRepository entries, ObjectMapper mapper) {
    this.diagrams=diagrams; this.elements=elements; this.relations=relations;
    this.memories=memories; this.entries=entries; this.mapper=mapper;
  }

  @Transactional(readOnly = true)
  public Snapshot read(UUID diagramId) {
    DiagramaClase diagram = diagrams.findById(diagramId).orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    List<ElementoUML> classes = elements.findByDiagramaId(diagramId);
    List<RelacionUML> links = relations.findByDiagramaId(diagramId);
    ObjectNode root = model(diagram.getNombre(), diagram.getDescripcion(), classes, links);
    ObjectNode memory = mapper.createObjectNode();
    memories.findById(diagram.getProject().getId()).ifPresent(value -> {
      memory.put("name", value.getSystemName()).put("description", value.getSystemDescription())
          .put("domain", value.getBusinessDomain()).put("objectives", value.getObjectives())
          .put("additional", value.getAdditionalContext());
    });
    ArrayNode confirmed = memory.putArray("confirmedEntries");
    var confirmedEntries = entries.findByProject_IdOrderByUpdatedAtDesc(diagram.getProject().getId()).stream()
        .filter(value -> value.getConfidenceScope() == AiMemoryConfidence.CONFIRMED)
        .filter(value -> value.getDiagram() == null || value.getDiagram().getId().equals(diagramId))
        .toList();
    confirmedEntries.stream()
        .map(value -> value.getEntryType().name() + ":" + value.getContent()).sorted().forEach(confirmed::add);
    root.set("confirmedMemory", memory);
    boolean hasPurpose = !memory.path("description").asText("").isBlank() || !memory.path("domain").asText("").isBlank()
        || confirmedEntries.stream().anyMatch(value -> value.getEntryType() == AiMemoryEntryType.SYSTEM_CONTEXT);
    int members = classes.stream().mapToInt(value -> properties(value.getPropiedadesJson()).path("attributes").size()
        + properties(value.getPropiedadesJson()).path("methods").size()).sum();
    return new Snapshot(diagram.getProject().getId(), diagramId, diagram.getRevision(), diagram.getNombre(),
        fingerprint(root), root, classes.size(), links.size(), members, hasPurpose);
  }

  ObjectNode model(String name, String description, List<ElementoUML> classes, List<RelacionUML> links) {
    ObjectNode root = mapper.createObjectNode().put("name", name).put("description", description);
    ArrayNode nodes = root.putArray("classes");
    classes.stream().sorted(Comparator.comparing(value -> value.getId().toString())).forEach(value -> {
      ObjectNode node = nodes.addObject().put("id", value.getId().toString()).put("name", value.getNombre())
          .put("type", value.getTipoElemento().name());
      node.set("properties", properties(value.getPropiedadesJson()));
    });
    ArrayNode edges = root.putArray("relations");
    links.stream().sorted(Comparator.comparing(value -> value.getId().toString())).forEach(value ->
        edges.addObject().put("id", value.getId().toString()).put("source", value.getElementoOrigen().getId().toString())
            .put("target", value.getElementoDestino().getId().toString()).put("name", value.getNombreRelacion())
            .put("type", value.getTipoRelacion().name()).put("sourceMultiplicity", value.getMultiplicidadOrigen())
            .put("targetMultiplicity", value.getMultiplicidadDestino()));
    return root;
  }

  private ObjectNode properties(String source) {
    ObjectNode result = mapper.createObjectNode();
    try {
      JsonNode parsed = mapper.readTree(source == null ? "{}" : source);
      result.put("description", parsed.path("description").asText("")).put("stereotype", parsed.path("stereotype").asText(""));
      for (String group : List.of("attributes", "methods")) {
        List<JsonNode> canonical = new ArrayList<>();
        if (parsed.path(group).isArray()) parsed.path(group).forEach(member -> {
          ObjectNode item = mapper.createObjectNode();
          for (String field : List.of("name", "type", "returnType", "visibility", "parameters", "isStatic", "isAbstract", "defaultValue", "multiplicity"))
            if (member.has(field)) item.set(field, canonical(member.get(field)));
          canonical.add(item);
        });
        canonical.sort(Comparator.comparing(JsonNode::toString));
        ArrayNode array = result.putArray(group); canonical.forEach(array::add);
      }
    } catch (Exception malformed) { result.put("invalidProperties", true); }
    return result;
  }

  private JsonNode canonical(JsonNode value) {
    if (value.isObject()) {
      ObjectNode sorted = mapper.createObjectNode();
      List<String> fields = new ArrayList<>(); value.fieldNames().forEachRemaining(fields::add);
      fields.stream().sorted().forEach(field -> sorted.set(field, canonical(value.get(field)))); return sorted;
    }
    if (value.isArray()) { ArrayNode array = mapper.createArrayNode(); value.forEach(item -> array.add(canonical(item))); return array; }
    return value;
  }

  public static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception impossible) { throw new IllegalStateException(impossible); }
  }

  /** UUIDs locate elements but do not define semantics; equivalent imports can assign fresh IDs. */
  static String fingerprint(ObjectNode model) {
    ObjectNode result = model.deepCopy();
    Map<String,String> keys = new HashMap<>(); List<String> classes = new ArrayList<>(), relations = new ArrayList<>();
    model.path("classes").forEach(value -> {
      ObjectNode node = value.deepCopy(); node.remove("id");
      String canonical = node.toString(); keys.put(value.path("id").asText(), hash(canonical)); classes.add(canonical);
    });
    model.path("relations").forEach(value -> {
      ObjectNode link = value.deepCopy(); link.remove("id");
      link.put("source", keys.getOrDefault(value.path("source").asText(),"unknown"));
      link.put("target", keys.getOrDefault(value.path("target").asText(),"unknown")); relations.add(link.toString());
    });
    ArrayNode nodes = result.putArray("classes"), links = result.putArray("relations");
    classes.stream().sorted().forEach(nodes::add); relations.stream().sorted().forEach(links::add);
    return hash(result.toString());
  }

  public record Snapshot(UUID projectId, UUID diagramId, long revision, String diagramName, String hash,
      ObjectNode model, int classCount, int relationCount, int memberCount, boolean confirmedPurpose) {
    public boolean hasEvidence() {
      return classCount > 0 && (confirmedPurpose || classCount >= 3 || (classCount >= 2 && (relationCount > 0 || memberCount >= 4)));
    }
    public Set<String> allowedIds() {
      Set<String> ids = new HashSet<>();
      model.path("classes").forEach(value -> ids.add(value.path("id").asText()));
      model.path("relations").forEach(value -> ids.add(value.path("id").asText()));
      return ids;
    }
    public String evidence(List<String> ids) {
      if (ids.isEmpty()) return hash;
      ObjectNode subset = model.deepCopy(); ArrayNode links = subset.putArray("relations"); Set<String> selected = new HashSet<>(ids);
      model.path("relations").forEach(value -> {
        if (ids.contains(value.path("id").asText()) || ids.contains(value.path("source").asText()) || ids.contains(value.path("target").asText())) {
          links.add(value); selected.add(value.path("source").asText()); selected.add(value.path("target").asText());
        }
      });
      ArrayNode classes = subset.putArray("classes");
      model.path("classes").forEach(value -> { if (selected.contains(value.path("id").asText())) classes.add(value); });
      return fingerprint(subset);
    }
    public String recommendationKey(String type, List<String> ids) {
      Map<String,String> names = new HashMap<>(); List<String> identity = new ArrayList<>();
      model.path("classes").forEach(value -> {
        String name=value.path("type").asText()+":"+value.path("name").asText(); names.put(value.path("id").asText(),name);
        if (ids.contains(value.path("id").asText())) identity.add(name);
      });
      model.path("relations").forEach(value -> {
        if (ids.contains(value.path("id").asText())) identity.add("REL:" + names.get(value.path("source").asText()) + ":" + names.get(value.path("target").asText()) + ":" + value.path("name").asText());
      });
      identity.sort(String::compareTo); return AiSemanticSnapshot.hash(type+":"+String.join("|",identity));
    }
  }
}
