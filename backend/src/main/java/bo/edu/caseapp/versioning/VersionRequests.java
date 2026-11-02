package bo.edu.caseapp.versioning;

import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class VersionRequests {
  private VersionRequests() {}

  public record CreateVersion(@Size(max = 255) String descripcion, UUID idempotencyKey) {
    public CreateVersion(String descripcion) { this(descripcion, null); }
  }
}
