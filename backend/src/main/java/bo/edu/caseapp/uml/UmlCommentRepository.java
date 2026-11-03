package bo.edu.caseapp.uml;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UmlCommentRepository extends JpaRepository<UmlComment, UUID> {
  List<UmlComment> findByDiagramaIdOrderByCreatedAtAsc(UUID diagramaId);
  Optional<UmlComment> findByIdAndDiagramaId(UUID id, UUID diagramaId);
}
