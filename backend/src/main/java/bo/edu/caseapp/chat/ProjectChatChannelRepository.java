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
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ProjectChatChannel c where c.id=:id and c.project.id=:projectId")
  Optional<ProjectChatChannel> findForManagement(@Param("id") UUID id, @Param("projectId") UUID projectId);
  boolean existsByIdAndProject_IdAndChannelTypeAndArchivedAtIsNull(UUID id, UUID projectId, String channelType);

  @Query(value = "SELECT id FROM project_chat_channel WHERE project_id=:projectId AND slug='voz-diseno' AND channel_type='VOICE' AND archived_at IS NULL", nativeQuery = true)
  Optional<UUID> defaultVoiceRoom(@Param("projectId") UUID projectId);

  @Modifying
  @Query(value = """
      INSERT INTO project_chat_channel (id, project_id, slug, name, description, sort_order, created_at, channel_type)
      VALUES
        (gen_random_uuid(), :projectId, 'general', 'general', 'Conversación general entre integrantes del proyecto.', 0, CURRENT_TIMESTAMP, 'TEXT'),
        (gen_random_uuid(), :projectId, 'diseno-uml', 'diseño-uml', 'Discusión técnica sobre el modelo UML.', 1, CURRENT_TIMESTAMP, 'TEXT'),
        (gen_random_uuid(), :projectId, 'voz-diseno', 'Sala de diseño', 'Audio en vivo del equipo.', 2, CURRENT_TIMESTAMP, 'VOICE')
      ON CONFLICT (project_id, slug) DO NOTHING
      """, nativeQuery = true)
  int ensureDefaultChannels(@Param("projectId") UUID projectId);
}
