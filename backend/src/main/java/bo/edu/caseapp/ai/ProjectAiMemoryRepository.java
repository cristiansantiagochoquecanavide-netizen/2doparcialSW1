package bo.edu.caseapp.ai;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectAiMemoryRepository extends JpaRepository<ProjectAiMemory, UUID> { }
