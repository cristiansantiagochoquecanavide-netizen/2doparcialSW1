package bo.edu.caseapp.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiConversationMessageRepository extends JpaRepository<AiConversationMessage, UUID> {
  Optional<AiConversationMessage> findByIdAndConversation_Id(UUID id, UUID conversationId);
  Optional<AiConversationMessage> findByConversation_IdAndSender_IdAndClientMessageId(UUID conversationId, UUID senderId, UUID clientMessageId);
  Optional<AiConversationMessage> findByReplyTo_Id(UUID replyToId);
  List<AiConversationMessage> findByConversation_IdOrderByCreatedAtDescIdDesc(UUID conversationId, Pageable pageable);
  @Query("select m from AiConversationMessage m where m.conversation.id = :conversationId and "
      + "(m.createdAt < :createdAt or (m.createdAt = :createdAt and m.id < :id)) order by m.createdAt desc, m.id desc")
  List<AiConversationMessage> findOlderThan(@Param("conversationId") UUID conversationId,
      @Param("createdAt") Instant createdAt, @Param("id") UUID id, Pageable pageable);
  List<AiConversationMessage> findByConversation_IdAndStatusOrderByCreatedAtDesc(UUID conversationId,
      AiConversationMessageStatus status, Pageable pageable);
}
