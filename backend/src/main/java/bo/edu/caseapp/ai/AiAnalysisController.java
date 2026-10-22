package bo.edu.caseapp.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/projects/{projectId}/ai/analysis")
public class AiAnalysisController {
  private final AiAnalysisStore store;
  public AiAnalysisController(AiAnalysisStore store) { this.store=store; }
  @GetMapping("/diagrams/{diagramId}")
  public Map<String,Object> status(@PathVariable UUID projectId, @PathVariable UUID diagramId, Authentication auth) {
    return store.status(projectId, diagramId, auth.getName());
  }
  @PatchMapping("/policy")
  public ResponseEntity<Void> policy(@PathVariable UUID projectId, Authentication auth,
      @Valid @RequestBody AiAnalysisStore.PolicyUpdate update) {
    store.updatePolicy(projectId, auth.getName(), update); return ResponseEntity.noContent().build();
  }
  @PostMapping("/diagrams/{diagramId}/review")
  public ResponseEntity<Void> review(@PathVariable UUID projectId, @PathVariable UUID diagramId, Authentication auth) {
    store.requestReview(projectId, diagramId, auth.getName()); return ResponseEntity.accepted().build();
  }
  @PatchMapping("/diagrams/{diagramId}/recommendations/{recommendationId}")
  public ResponseEntity<Void> disposition(@PathVariable UUID projectId, @PathVariable UUID diagramId,
      @PathVariable UUID recommendationId, Authentication auth, @Valid @RequestBody Disposition request) {
    store.disposition(projectId, diagramId, recommendationId, auth.getName(), request.status());
    return ResponseEntity.noContent().build();
  }
  public record Disposition(@NotBlank String status) { }
}
