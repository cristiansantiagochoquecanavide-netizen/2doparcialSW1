package bo.edu.caseapp.uml;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class UmlCommentRequests {
  private UmlCommentRequests() {}
  public record Create(@NotBlank @Size(max = 2000) String content, UUID elementoId, UUID relacionId, UUID parentId, Double posicionX, Double posicionY) {}
  public record Status(@NotBlank String status) {}
  public record Update(@Size(max = 2000) String content, Double posicionX, Double posicionY, Integer canvasWidth, Integer canvasHeight) {}
}
