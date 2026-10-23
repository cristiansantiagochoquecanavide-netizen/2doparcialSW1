package bo.edu.caseapp.chat;

import static bo.edu.caseapp.chat.ChatResponses.*;

import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.error.ConflictException;
import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.project.ProjectMember;
import bo.edu.caseapp.project.ProjectService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectChatService {
  private static final int DEFAULT_PAGE_SIZE = 50;
  private static final int MAX_PAGE_SIZE = 100;

  private final ProjectChatChannelRepository channels;
  private final ProjectChatMessageRepository messages;
  private final ProjectService projects;
  private final JdbcTemplate jdbc;
  private final ApplicationEventPublisher events;
  @org.springframework.beans.factory.annotation.Autowired private AcodiQueue acodi;

  public ProjectChatService(
      ProjectChatChannelRepository channels,
      ProjectChatMessageRepository messages,
      ProjectService projects,
      JdbcTemplate jdbc,
      ApplicationEventPublisher events
  ) {
    this.channels = channels;
    this.messages = messages;
    this.projects = projects;
    this.jdbc = jdbc;
    this.events = events;
  }

  @Transactional
  public List<Channel> listChannels(UUID projectId, String email) {
    projects.requireAccess(projectId, email);
    channels.ensureDefaultChannels(projectId);
    return channels.findByProjectIdOrderBySortOrderAscNameAsc(projectId).stream()
        .map(channel -> new Channel(channel.getId(), channel.getSlug(), channel.getName(), channel.getDescription(), channel.getCreatedAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public MessagePage history(UUID projectId, UUID channelId, String email, UUID before, int requestedLimit) {
    projects.requireAccess(projectId, email);
    ProjectChatChannel channel = channel(projectId, channelId);
    int limit = Math.max(1, Math.min(requestedLimit <= 0 ? DEFAULT_PAGE_SIZE : requestedLimit, MAX_PAGE_SIZE));

    List<ProjectChatMessage> rows;
    if (before == null) {
      rows = messages.findByChannelIdOrderByCreatedAtDescIdDesc(channelId, PageRequest.of(0, limit + 1));
    } else {
      ProjectChatMessage cursor = messages.findByIdAndChannelId(before, channelId)
          .orElseThrow(() -> new BadRequestException("El cursor no pertenece a este canal."));
      rows = messages.findOlderThan(channelId, cursor.getCreatedAt(), cursor.getId(), PageRequest.of(0, limit + 1));
    }

    boolean hasMore = rows.size() > limit;
    List<ProjectChatMessage> page = new ArrayList<>(rows.subList(0, Math.min(rows.size(), limit)));
    UUID nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null;
    Collections.reverse(page);
    return new MessagePage(page.stream().map(this::response).toList(), nextCursor, hasMore);
  }

  @Transactional
  public SendMessageResult send(UUID projectId, UUID channelId, String email, ChatRequests.SendMessage request) {
    ProjectMember membership = projects.requireAccess(projectId, email);
    ProjectChatChannel channel = channel(projectId, channelId);
    String content = request.content() == null ? "" : request.content().trim();
    if (content.isEmpty() || content.indexOf('\0') >= 0) {
      throw new BadRequestException("El mensaje debe contener texto válido.");
    }
    boolean mention=AcodiMention.present(content);
    UUID diagramId=mention && acodi!=null?acodi.diagram(projectId,email,request.diagramId()):null;

    ProjectChatMessage replyTo = null;
    if (request.replyToMessageId() != null) {
      replyTo = messages.findByIdAndChannelId(request.replyToMessageId(), channelId)
          .orElseThrow(() -> new BadRequestException("El mensaje respondido no pertenece a este canal."));
    }

    UUID userId = membership.getUser().getId();
    UUID messageId = UUID.randomUUID();
    UUID replyId = replyTo == null ? null : replyTo.getId();
    UUID insertedId = jdbc.query("""
        INSERT INTO project_chat_message
          (id, channel_id, sender_user_id, client_message_id, sender_role, author_type, content, created_at, reply_to_message_id)
        VALUES (?, ?, ?, ?, ?, 'USER', ?, CURRENT_TIMESTAMP, ?)
        ON CONFLICT (channel_id, sender_user_id, client_message_id) DO NOTHING
        RETURNING id
        """, this::readInsertedId, messageId, channelId, userId, request.clientMessageId(), membership.getRole().name(), content, replyId);

    if (insertedId == null) {
      ProjectChatMessage existing = messages.findByChannelIdAndSenderIdAndClientMessageId(channelId, userId, request.clientMessageId())
          .orElseThrow(() -> new ConflictException("La clave de idempotencia ya está siendo procesada. Reintenta el mismo mensaje."));
      if (!existing.getContent().equals(content) || !sameReply(existing.getReplyTo(), replyId)) {
        throw new ConflictException("La clave de idempotencia ya se usó con otro contenido.");
      }
      if(mention && acodi!=null) {
        var job=acodi.view(existing.getId());
        if(job!=null && !java.util.Objects.equals(job.diagramId(),diagramId)) throw new ConflictException("La clave del mensaje corresponde a otro diagrama.");
      }
      return new SendMessageResult(response(existing), false);
    }

    ProjectChatMessage saved = messages.findById(insertedId)
        .orElseThrow(() -> new IllegalStateException("El mensaje guardado no pudo recuperarse."));
    if(mention && acodi!=null) acodi.enqueue(projectId,channelId,insertedId,diagramId);
    Message result = response(saved);
    events.publishEvent(new ChatMessageCommittedEvent(projectId, result));
    return new SendMessageResult(result, true);
  }

  private ProjectChatChannel channel(UUID projectId, UUID channelId) {
    return channels.findByIdAndProjectId(channelId, projectId)
        .orElseThrow(() -> new NotFoundException("Canal no encontrado en este proyecto."));
  }

  private boolean sameReply(ProjectChatMessage existing, UUID requestedId) {
    return (existing == null && requestedId == null) || (existing != null && existing.getId().equals(requestedId));
  }

  private UUID readInsertedId(ResultSet resultSet) throws SQLException {
    return resultSet.next() ? resultSet.getObject("id", UUID.class) : null;
  }

  private Message response(ProjectChatMessage message) {
    var sender = message.getSender();
    var replyTo = message.getReplyTo();
    return new Message(
        message.getId(),
        message.getChannel().getId(),
        sender == null ? null : sender.getId(),
        sender == null ? "ACODI" : sender.getName(),
        sender == null ? null : sender.getEmail(),
        message.getSenderRole(),
        message.getAuthorType().name(),
        message.getClientMessageId(),
        message.getContent(),
        message.getCreatedAt() == null ? Instant.now() : message.getCreatedAt(),
        replyTo == null ? null : replyTo.getId(),
        message.getCanonicalAiMessageId(),
        acodi!=null && message.getAuthorType()==ChatAuthorType.USER && AcodiMention.present(message.getContent())?acodi.view(message.getId()):null
    );
  }

  @Transactional
  public Message publishAgent(UUID projectId,UUID channelId,String email,String content,UUID replyId,UUID canonicalId) {
    projects.requireAccess(projectId,email);var target=channel(projectId,channelId);
    if(canonicalId!=null) {
      var ids=jdbc.queryForList("SELECT id FROM project_chat_message WHERE canonical_ai_message_id=?",UUID.class,canonicalId);
      if(!ids.isEmpty()) return response(messages.findById(ids.getFirst()).orElseThrow());
    }
    var reply=replyId==null?null:messages.findByIdAndChannelId(replyId,channelId).orElseThrow(()->new BadRequestException("Consulta no encontrada en este canal."));
    if(content==null || content.isBlank() || content.length()>4000 || content.indexOf('\0')>=0) throw new BadRequestException("Respuesta de ACODI no válida.");
    var value=new ProjectChatMessage(target,null,UUID.randomUUID(),"AGENTE",ChatAuthorType.AGENT,content,Instant.now(),reply);
    value.setCanonicalAiMessageId(canonicalId);var saved=messages.saveAndFlush(value);var result=response(saved);
    events.publishEvent(new ChatMessageCommittedEvent(projectId,result));return result;
  }
  @Transactional
  public void publishUpdated(UUID projectId,UUID messageId) {
    var message=messages.findById(messageId).orElseThrow();
    if(!message.getChannel().getProject().getId().equals(projectId)) throw new NotFoundException("Mensaje no encontrado.");
    events.publishEvent(new ChatMessageCommittedEvent(projectId,response(message)));
  }
  @Transactional
  public void publishRecommendation(UUID projectId,String email,String content,UUID canonicalId) {
    projects.requireAccess(projectId,email);channels.ensureDefaultChannels(projectId);
    var target=channels.findByProjectIdOrderBySortOrderAscNameAsc(projectId).stream().filter(c->c.getSlug().equals("diseno-uml")).findFirst().orElseThrow();
    publishAgent(projectId,target.getId(),email,content,null,canonicalId);
  }
}
