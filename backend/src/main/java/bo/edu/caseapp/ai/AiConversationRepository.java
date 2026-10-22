package bo.edu.caseapp.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConversationRepository extends JpaRepository<AiConversation, UUID> {
  List<AiConversation> findByProject_IdOrderByUpdatedAtDesc(UUID projectId);
  Optional<AiConversation> findByIdAndProject_Id(UUID id, UUID projectId);
}
