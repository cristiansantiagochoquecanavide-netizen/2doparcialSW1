package bo.edu.caseapp.ai;

import static org.junit.jupiter.api.Assertions.*;
import bo.edu.caseapp.error.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiAnalysisResultTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final AiAnalysisResult validator = new AiAnalysisResult(mapper);
  private final String classId = UUID.randomUUID().toString();
  private AiSemanticSnapshot.Snapshot snapshot(boolean confirmed) {
    var model=mapper.createObjectNode(); model.putArray("classes").addObject().put("id",classId).put("name","Cliente");
    model.putArray("relations");
    return new AiSemanticSnapshot.Snapshot(UUID.randomUUID(),UUID.randomUUID(),7,"M","hash",model,3,0,0,confirmed);
  }
  private String recommendation(String id, int revision) {
    return """
        {"hasRecommendation":true,"type":"ATTRIBUTE","severity":"MEDIUM","title":"Identificador",
        "explanation":"Revisar identificación","elementIds":["%s"],"recommendedChange":"Evaluar un identificador",
        "justification":"Distinguir instancias","confidence":0.8,"analyzedRevision":%d,"hypothesis":null}
        """.formatted(id, revision);
  }
  @Test void validatesOneStructuredRecommendationAndExistingIds() {
    var result = validator.parse(recommendation(classId,7),snapshot(false));
    assertTrue(result.hasRecommendation()); assertEquals(classId,result.elementIds().getFirst());
  }
  @Test void inventedIdsDifferentRevisionAndListsOfRecommendationsAreRejected() {
    assertThrows(BadRequestException.class, () -> validator.parse(recommendation(UUID.randomUUID().toString(),7),snapshot(false)));
    assertThrows(BadRequestException.class, () -> validator.parse(recommendation(classId,8),snapshot(false)));
    assertThrows(BadRequestException.class, () -> validator.parse("["+recommendation(classId,7)+"]",snapshot(false)));
  }
  @Test void acceptsNoRecommendationAndKeepsInferenceSeparateFromConfirmedPurpose() {
    String response="{\"hasRecommendation\":false,\"analyzedRevision\":7,\"hypothesis\":{\"description\":\"Posible sistema de ventas\",\"confidence\":0.8}}";
    assertNotNull(validator.parse(response,snapshot(false)).hypothesis());
    assertNull(validator.parse(response,snapshot(true)).hypothesis());
    assertFalse(validator.parse(response,snapshot(false)).hasRecommendation());
  }
  @Test void malformedConfidenceAndMissingRequiredExplanationAreRejected() {
    assertThrows(BadRequestException.class, () -> validator.parse(recommendation(classId,7).replace("0.8","1.5"),snapshot(false)));
    assertThrows(BadRequestException.class, () -> validator.parse(recommendation(classId,7).replace("\"Revisar identificación\"", "null"),snapshot(false)));
  }
}
