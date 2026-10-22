package bo.edu.caseapp.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectAiMemoryEntryRepository extends JpaRepository<ProjectAiMemoryEntry, UUID> {
  List<ProjectAiMemoryEntry> findByProject_IdOrderByUpdatedAtDesc(UUID projectId);
  List<ProjectAiMemoryEntry> findByProject_IdAndDiagram_IdOrderByUpdatedAtDesc(UUID projectId, UUID diagramId);
  Optional<ProjectAiMemoryEntry> findByIdAndProject_Id(UUID id, UUID projectId);
}
