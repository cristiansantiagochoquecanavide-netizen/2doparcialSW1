package bo.edu.caseapp.versioning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VersionDiagramaRepository extends JpaRepository<VersionDiagrama, UUID> {
  List<VersionDiagrama> findByDiagramaIdOrderByNumeroVersionDesc(UUID diagramaId);
  Page<VersionDiagrama> findByDiagramaIdOrderByNumeroVersionDesc(UUID diagramaId, Pageable pageable);

  @Query("SELECT COALESCE(MAX(v.numeroVersion), 0) FROM VersionDiagrama v WHERE v.diagrama.id = :diagramaId")
  int findMaxVersionNumber(UUID diagramaId);

  Optional<VersionDiagrama> findByIdAndDiagramaId(UUID id, UUID diagramaId);
  Optional<VersionDiagrama> findByDiagramaIdAndNumeroVersion(UUID diagramaId, int numeroVersion);
  Optional<VersionDiagrama> findByDiagramaIdAndIdempotencyKey(UUID diagramaId, UUID idempotencyKey);
  Optional<VersionDiagrama> findFirstByDiagramaIdOrderByNumeroVersionDesc(UUID diagramaId);
  Optional<VersionDiagrama> findFirstByDiagramaIdAndWorkingTrue(UUID diagramaId);
}
