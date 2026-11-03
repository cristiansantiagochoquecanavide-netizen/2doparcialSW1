package bo.edu.caseapp.versioning;

import java.time.Instant;
import java.util.UUID;

public final class VersionResponses {
  private VersionResponses() {}

  public record VersionSummary(
      UUID id,
      UUID diagramaId,
      int numeroVersion,
      String descripcion,
      String autorNombre,
      String autorEmail,
      Instant fecha,
      long sourceRevision,
      String operationType,
      UUID restoredFromVersionId,
      boolean working
  ) {}

  public record VersionDetail(
      UUID id,
      UUID diagramaId,
      int numeroVersion,
      String descripcion,
      String snapshotJson,
      String autorNombre,
      String autorEmail,
      Instant fecha,
      long sourceRevision,
      String operationType,
      UUID restoredFromVersionId,
      boolean working
  ) {}

  public record ActivityItem(
      UUID versionId,
      int numeroVersion,
      String autorNombre,
      String autorEmail,
      Instant fecha,
      long revision,
      String operacion,
      java.util.List<String> elementosAfectados
  ) {}
}
