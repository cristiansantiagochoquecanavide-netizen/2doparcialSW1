package bo.edu.caseapp.ai;

import bo.edu.caseapp.error.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AiAnalysisResult {
  private static final Set<String> TYPES = Set.of("ATTRIBUTE", "RESPONSIBILITY", "RELATION", "MULTIPLICITY", "IDENTIFIER", "NORMALIZATION", "BUSINESS_RULE", "GENERAL");
  private final ObjectMapper mapper;
  public AiAnalysisResult(ObjectMapper mapper) { this.mapper=mapper; }
  public Result parse(String content, AiSemanticSnapshot.Snapshot snapshot) {
    try {
      if (content == null || content.length() > 14000) throw new IllegalArgumentException();
      String json = content.trim();
      if (json.startsWith("```")) json = json.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
      JsonNode node = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
      if (!node.isObject() || !node.path("hasRecommendation").isBoolean()
          || !node.path("analyzedRevision").isIntegralNumber() || node.path("analyzedRevision").asLong() != snapshot.revision()) throw new IllegalArgumentException();
      String hypothesis = null;
      if (node.hasNonNull("hypothesis")) {
        if (!node.get("hypothesis").isObject()) throw new IllegalArgumentException();
        double certainty = confidence(node.get("hypothesis"));
        String description = text(node.get("hypothesis"), "description", 3000);
        if (!snapshot.confirmedPurpose() && certainty >= 0.6) hypothesis = "Hipótesis pendiente de confirmación (confianza " + certainty + "): " + description;
      }
      if (!node.path("hasRecommendation").asBoolean()) return new Result(false, null, null, null, null, List.of(), null, null, 0, snapshot.revision(), hypothesis);
      String type = text(node, "type", 40), severity = text(node, "severity", 12);
      if (!TYPES.contains(type) || !Set.of("HIGH", "MEDIUM", "LOW").contains(severity)) throw new IllegalArgumentException();
      if (!node.path("elementIds").isArray() || node.path("elementIds").size() > 20) throw new IllegalArgumentException();
      SortedSet<String> ids = new TreeSet<>();
      for (JsonNode id : node.path("elementIds")) {
        if (!id.isTextual() || !snapshot.allowedIds().contains(id.asText())) throw new IllegalArgumentException();
        ids.add(UUID.fromString(id.asText()).toString());
      }
      double confidence = confidence(node);
      return new Result(confidence >= 0.6, type, severity, text(node, "title", 160), text(node, "explanation", 2000),
          List.copyOf(ids), text(node, "recommendedChange", 1000), text(node, "justification", 2000), confidence, snapshot.revision(), hypothesis);
    } catch (Exception invalid) { throw new BadRequestException("El proveedor devolvió un análisis estructurado no válido."); }
  }
  private static double confidence(JsonNode node) {
    if (!node.path("confidence").isNumber()) throw new IllegalArgumentException();
    double value = node.path("confidence").asDouble();
    if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException();
    return value;
  }
  private static String text(JsonNode node, String field, int max) {
    if (!node.path(field).isTextual()) throw new IllegalArgumentException();
    String value = node.path(field).asText().trim();
    if (value.isEmpty() || value.length() > max) throw new IllegalArgumentException(); return value;
  }
  public record Result(boolean hasRecommendation, String type, String severity, String title, String explanation,
      List<String> elementIds, String recommendedChange, String justification, double confidence,
      long analyzedRevision, String hypothesis) {
    public String teamMessage(String diagramName) {
      return "Equipo — " + diagramName + " (revisión " + analyzedRevision + ")\n" + title + "\n" + explanation
          + "\nSugerencia: " + recommendedChange + "\nJustificación: " + justification
          + "\nConfianza: " + confidence + ". Revisad las reglas del negocio antes de aplicar cambios.";
    }
  }
}
