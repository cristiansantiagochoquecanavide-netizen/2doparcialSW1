package bo.edu.caseapp.uml;

import java.time.Instant;
import java.util.UUID;

public final class UmlCommentResponses {
  private UmlCommentResponses() {}
  public record CommentView(UUID id, UUID parentId, String authorName, String authorEmail, UUID elementoId,
      UUID relacionId, String elementoNombre, String origenNombre, String destinoNombre, Double posicionX,
      Double posicionY, int canvasWidth, int canvasHeight, String content, String status, Instant createdAt, Instant updatedAt,
      String resolvedByName, Instant resolvedAt) {}
}
