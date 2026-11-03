package bo.edu.caseapp.generator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GeneratorResponses {
  private GeneratorResponses() {}

  public record GeneratedFile(String path, String language, String content) {}

  public record GenerationPreview(
      String projectName,
      String basePackage,
      int totalBackendFiles,
      int totalFrontendFiles,
      int totalFlutterFiles,
      List<GeneratedFile> files
  ) {}

  public record GenerationHistoryItem(
      UUID id,
      String tipoGeneracion,
      String estado,
      Instant fecha
  ) {}
}
