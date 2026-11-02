package bo.edu.caseapp.uml;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface DiagramaClaseRepository extends JpaRepository<DiagramaClase, UUID> {
  List<DiagramaClase> findByProjectIdOrderByFechaCreacionAsc(UUID projectId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT d FROM DiagramaClase d WHERE d.id = :id")
  Optional<DiagramaClase> lockById(@Param("id") UUID id);

  @Query("SELECT d.revision FROM DiagramaClase d WHERE d.id = :id")
  Optional<Long> findRevisionById(@Param("id") UUID id);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE DiagramaClase d SET d.revision = d.revision + 1 WHERE d.id = :id AND d.revision = :expectedRevision")
  int incrementRevisionIfMatches(@Param("id") UUID id, @Param("expectedRevision") long expectedRevision);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE DiagramaClase d SET d.revision = d.revision + 1 WHERE d.id = :id")
  int incrementRevisionUnconditional(@Param("id") UUID id);
}
