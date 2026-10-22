package bo.edu.caseapp.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectChatChannelRepository extends JpaRepository<ProjectChatChannel, UUID> {
  List<ProjectChatChannel> findByProjectIdOrderBySortOrderAscNameAsc(UUID projectId);
  Optional<ProjectChatChannel> findByIdAndProjectId(UUID id, UUID projectId);

  @Modifying
  @Query(value = """
      INSERT INTO project_chat_channel (id, project_id, slug, name, description, sort_order, created_at)
      VALUES
        (gen_random_uuid(), :projectId, 'general', 'general', 'Conversación general entre integrantes del proyecto.', 0, CURRENT_TIMESTAMP),
        (gen_random_uuid(), :projectId, 'diseno-uml', 'diseño-uml', 'Discusión técnica sobre el modelo UML.', 1, CURRENT_TIMESTAMP)
      ON CONFLICT (project_id, slug) DO NOTHING
      """, nativeQuery = true)
  int ensureDefaultChannels(@Param("projectId") UUID projectId);
}
