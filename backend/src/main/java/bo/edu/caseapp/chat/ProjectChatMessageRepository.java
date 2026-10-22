package bo.edu.caseapp.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectChatMessageRepository extends JpaRepository<ProjectChatMessage, UUID> {
  Optional<ProjectChatMessage> findByIdAndChannelId(UUID id, UUID channelId);
  Optional<ProjectChatMessage> findByChannelIdAndSenderIdAndClientMessageId(UUID channelId, UUID senderId, UUID clientMessageId);
  List<ProjectChatMessage> findByChannelIdOrderByCreatedAtDescIdDesc(UUID channelId, Pageable pageable);

  @Query("""
      SELECT message FROM ProjectChatMessage message
      WHERE message.channel.id = :channelId
        AND (message.createdAt < :createdAt OR (message.createdAt = :createdAt AND message.id < :messageId))
      ORDER BY message.createdAt DESC, message.id DESC
      """)
  List<ProjectChatMessage> findOlderThan(
      @Param("channelId") UUID channelId,
      @Param("createdAt") Instant createdAt,
      @Param("messageId") UUID messageId,
      Pageable pageable
  );
}
