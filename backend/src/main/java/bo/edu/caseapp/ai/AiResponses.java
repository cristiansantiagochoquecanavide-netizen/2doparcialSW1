package bo.edu.caseapp.ai;

import bo.edu.caseapp.uml.UmlResponses.DiagramFullView;
import bo.edu.caseapp.ai.AiRequests.ImageModel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AiResponses {
  private AiResponses() {}

  public record CommandResponse(
      boolean success,
      String actionType,
      String message,
      DiagramFullView updatedDiagram
  ) {}

  public record ChatResponse(
      String answer,
      String suggestion
  ) {}

  public record TranscriptionResponse(String text) {}

  public record ImageReconstructionResponse(
      boolean success,
      String message,
      int classesDetected,
      int relationsDetected,
      DiagramFullView updatedDiagram,
      List<String> warnings
  ) {
    public ImageReconstructionResponse(boolean success, String message, int classesDetected, int relationsDetected, DiagramFullView updatedDiagram) {
      this(success, message, classesDetected, relationsDetected, updatedDiagram, List.of());
    }
  }

  public record ImageAnalysisResponse(ImageModel model, List<String> warnings, String provider) {}

  public record InteractionHistoryItem(
      UUID id,
      UUID usuarioId,
      String usuarioNombre,
      String tipoInteraccion,
      String entradaUsuario,
      String respuestaAgente,
      Instant fecha
  ) {}
}
