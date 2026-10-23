package bo.edu.caseapp.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import bo.edu.caseapp.uml.TipoElementoUML;
import bo.edu.caseapp.uml.TipoRelacionUML;

public final class AiRequests {
  private AiRequests() {}

  public record CommandRequest(
      @NotBlank String command,
      String source,
      String selectedElementId,
      String selectedRelationId
  ) {
    public String resolvedSource() {
      return source != null && !source.isBlank() ? source.trim().toUpperCase() : "TEXT";
    }
  }

  public record ChatRequest(@NotBlank String question) {}

  /** Contrato interno legado; no contiene ni acepta credenciales del proveedor. */
  record LegacyReconstructImageRequest(
      String imageBase64,
      String filename,
      String promptHint,
      List<LegacyDetectedClass> clientDetectedClasses,
      List<LegacyDetectedRelation> clientDetectedRelations
  ) {}

  record LegacyDetectedClass(String name, Double posX, Double posY, List<LegacyDetectedAttr> attributes, List<LegacyDetectedMethod> methods) {}
  record LegacyDetectedAttr(String visibility, String name, String type) {}
  record LegacyDetectedMethod(String visibility, String name, String returnType) {}
  record LegacyDetectedRelation(String source, String target, String name, String tipo, String multSource, String multTarget) {}

  public record AnalyzeImageRequest(
      @NotBlank String imageBase64,
      String filename,
      String promptHint
  ) {}

  public enum ImageImportMode { ADD, REPLACE }

  public record ApplyImageModelRequest(
      @NotNull ImageModel model,
      @NotNull ImageImportMode mode,
      String filename
  ) {}

  public record ImageModel(List<ImageClass> classes, List<ImageRelation> relations, List<String> warnings) {}

  public record ImageClass(
      String name,
      TipoElementoUML type,
      Double confidence,
      Double x,
      Double y,
      List<ImageAttribute> attributes,
      List<ImageMethod> methods
  ) {}

  public record ImageAttribute(
      String visibility,
      String name,
      String type,
      Double confidence
  ) {}

  public record ImageMethod(
      String visibility,
      String name,
      String returnType,
      String parameters,
      Double confidence
  ) {}

  public record ImageRelation(
      String source,
      String target,
      String name,
      TipoRelacionUML type,
      String multiplicitySource,
      String multiplicityTarget,
      Double confidence,
      String associationClass
  ) {
    public ImageRelation(String source, String target, String name, TipoRelacionUML type,
        String multiplicitySource, String multiplicityTarget, Double confidence) {
      this(source, target, name, type, multiplicitySource, multiplicityTarget, confidence, null);
    }
  }
}
