package bo.edu.caseapp.generator;

import bo.edu.caseapp.generator.GeneratorRequests.GenerateConfig;
import bo.edu.caseapp.generator.GeneratorResponses.GenerationHistoryItem;
import bo.edu.caseapp.generator.GeneratorResponses.GenerationPreview;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/diagrams/{diagramId}/generate")
public class GeneratorController {
  private final CodeGeneratorService generatorService;

  public GeneratorController(CodeGeneratorService generatorService) {
    this.generatorService = generatorService;
  }

  @PostMapping("/preview")
  public GenerationPreview preview(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @Valid @RequestBody(required = false) GenerateConfig config
  ) {
    GenerateConfig cfg = config != null ? config : new GenerateConfig(null, null, null, null, null, null, true, false);
    return generatorService.preview(projectId, diagramId, auth.getName(), cfg);
  }

  @PostMapping("/zip")
  public ResponseEntity<byte[]> downloadZip(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @Valid @RequestBody(required = false) GenerateConfig config
  ) {
    GenerateConfig cfg = config != null ? config : new GenerateConfig(null, null, null, null, null, null, true, false);
    byte[] zipBytes = generatorService.generateZip(projectId, diagramId, auth.getName(), cfg);

    String filename = cfg.resolvedProjectName() + ".zip";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(zipBytes);
  }

  @GetMapping("/history")
  public List<GenerationHistoryItem> history(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth
  ) {
    return generatorService.history(projectId, diagramId, auth.getName());
  }
}
