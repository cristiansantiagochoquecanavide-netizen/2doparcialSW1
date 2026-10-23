package bo.edu.caseapp.ai;

import bo.edu.caseapp.ai.AiConversationRequests.Create;
import bo.edu.caseapp.ai.AiConversationRequests.Rename;
import bo.edu.caseapp.ai.AiConversationRequests.Send;
import bo.edu.caseapp.ai.AiConversationResponses.*;
import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.error.ConflictException;
import bo.edu.caseapp.error.ForbiddenException;
import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.project.ProjectMember;
import bo.edu.caseapp.project.ProjectRole;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.DiagramaClase;
import bo.edu.caseapp.uml.DiagramaClaseRepository;
import bo.edu.caseapp.user.User;
import bo.edu.caseapp.user.UserRepository;
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
@Transactional
public class AiConversationStore {
  @org.springframework.beans.factory.annotation.Autowired private bo.edu.caseapp.chat.ProjectChatService channelChat;
  private static final int DEFAULT_PAGE = 50;
  private static final int MAX_PAGE = 100;
  private final ProjectService projects;
  private final AiConversationRepository conversations;
  private final AiConversationMessageRepository messages;
  private final DiagramaClaseRepository diagrams;
  private final UserRepository users;
  private final JdbcTemplate jdbc;
  private final ApplicationEventPublisher events;

  public AiConversationStore(ProjectService projects, AiConversationRepository conversations,
      AiConversationMessageRepository messages, DiagramaClaseRepository diagrams, UserRepository users,
      JdbcTemplate jdbc, ApplicationEventPublisher events) {
    this.projects = projects; this.conversations = conversations; this.messages = messages;
    this.diagrams = diagrams; this.users = users; this.jdbc = jdbc; this.events = events;
  }

  public Conversation create(UUID projectId, String email, Create request) {
    ProjectMember member = projects.requireAccess(projectId, email);
    DiagramaClase diagram = request.diagramId() == null ? null : requireDiagram(projectId, request.diagramId());
    User owner = request.scope() == AiConversationScope.PRIVATE ? member.getUser() : null;
    return response(conversations.save(new AiConversation(member.getProject(), diagram, request.scope(), owner,
        member.getUser(), request.title().trim())));
  }

  @Transactional(readOnly = true)
  public List<Conversation> list(UUID projectId, String email, boolean includeArchived) {
    ProjectMember member = projects.requireAccess(projectId, email);
    return conversations.findByProject_IdOrderByUpdatedAtDesc(projectId).stream()
        .filter(value -> value.getScope() == AiConversationScope.GROUP || value.getOwner().getId().equals(member.getUser().getId()))
        .filter(value -> includeArchived || value.getArchivedAt() == null)
        .map(this::response).toList();
  }

  @Transactional(readOnly = true)
  public Conversation get(UUID projectId, UUID conversationId, String email) {
    ProjectMember member = projects.requireAccess(projectId, email);
    return response(authorized(projectId, conversationId, member));
  }

  public Conversation rename(UUID projectId, UUID conversationId, String email, Rename request) {
    ProjectMember member = projects.requireAccess(projectId, email);
    AiConversation conversation = authorized(projectId, conversationId, member);
    requireManage(conversation, member);
    conversation.rename(request.title().trim());
    return response(conversation);
  }

  public Conversation archive(UUID projectId, UUID conversationId, String email) {
    ProjectMember member = projects.requireAccess(projectId, email);
    AiConversation conversation = authorized(projectId, conversationId, member);
    requireManage(conversation, member);
    conversation.archive();
    return response(conversation);
  }

  public void delete(UUID projectId, UUID conversationId, String email) {
    ProjectMember member = projects.requireAccess(projectId, email);
    AiConversation conversation = authorized(projectId, conversationId, member);
    requireManage(conversation, member);
    conversations.delete(conversation);
  }

  @Transactional(readOnly = true)
  public MessagePage history(UUID projectId, UUID conversationId, String email, UUID before, int requestedLimit) {
    ProjectMember member = projects.requireAccess(projectId, email);
    AiConversation conversation = authorized(projectId, conversationId, member);
    int limit = Math.max(1, Math.min(requestedLimit <= 0 ? DEFAULT_PAGE : requestedLimit, MAX_PAGE));
    List<AiConversationMessage> rows;
    if (before == null) rows = messages.findByConversation_IdOrderByCreatedAtDescIdDesc(conversationId, PageRequest.of(0, limit + 1));
    else {
      AiConversationMessage cursor = messages.findByIdAndConversation_Id(before, conversationId)
          .orElseThrow(() -> new BadRequestException("El cursor no pertenece a esta conversación."));
      rows = messages.findOlderThan(conversationId, cursor.getCreatedAt(), cursor.getId(), PageRequest.of(0, limit + 1));
    }
    boolean more = rows.size() > limit;
    List<AiConversationMessage> page = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
    UUID next = more && !page.isEmpty() ? page.get(page.size() - 1).getId() : null;
    Collections.reverse(page);
    return new MessagePage(page.stream().map(this::response).toList(), next, more);
  }

  public QuestionReceipt addQuestion(UUID projectId, UUID conversationId, String email, Send request) {
    ProjectMember member = projects.requireAccess(projectId, email);
    AiConversation conversation = authorized(projectId, conversationId, member);
    if (conversation.getArchivedAt() != null) throw new ConflictException("La conversación está archivada.");
    String content = request.content().trim();
    UUID messageId = jdbc.query("""
        INSERT INTO ai_conversation_message
          (id, conversation_id, client_message_id, sender_user_id, sender_type, content, processing_status)
        VALUES (?, ?, ?, ?, 'USER', ?, 'PENDING')
        ON CONFLICT (conversation_id, sender_user_id, client_message_id) DO NOTHING
        RETURNING id
        """, this::readId, UUID.randomUUID(), conversationId, request.clientMessageId(), member.getUser().getId(), content);
    boolean created = messageId != null;
    AiConversationMessage question;
    if (created) {
      question = messages.findById(messageId).orElseThrow();
      conversation.touch();
      if (conversation.getScope() == AiConversationScope.GROUP) publish(conversation, question);
    } else {
      question = messages.findByConversation_IdAndSender_IdAndClientMessageId(conversationId,
          member.getUser().getId(), request.clientMessageId()).orElseThrow(() ->
          new ConflictException("La consulta ya está siendo procesada."));
      if (!question.getContent().equals(content)) throw new ConflictException("La clave idempotente ya se usó con otro contenido.");
    }
    return new QuestionReceipt(response(conversation), response(question), created);
  }

  public QuestionReceipt retryQuestion(UUID projectId, UUID conversationId, UUID questionId, String email) {
    ProjectMember member = projects.requireAccess(projectId, email);
    AiConversation conversation = authorized(projectId, conversationId, member);
    AiConversationMessage question = messages.findByIdAndConversation_Id(questionId, conversationId)
        .orElseThrow(() -> new NotFoundException("Consulta no encontrada."));
    if (question.getSenderType() != AiConversationSenderType.USER || question.getSender() == null
        || !question.getSender().getId().equals(member.getUser().getId())) {
      throw new NotFoundException("Consulta no encontrada.");
    }
    if (question.getStatus() != AiConversationMessageStatus.ERROR) {
      throw new ConflictException("Solo se pueden reintentar consultas fallidas.");
    }
    if (conversation.getArchivedAt() != null) throw new ConflictException("La conversación está archivada.");
    question.reopenForRetry();
    if (conversation.getScope() == AiConversationScope.GROUP) publish(conversation, question);
    return new QuestionReceipt(response(conversation), response(question), false);
  }

  @Transactional(readOnly = true)
  public List<PromptTurn> recentPromptTurns(UUID conversationId, UUID currentQuestionId) {
    List<AiConversationMessage> rows = messages.findByConversation_IdOrderByCreatedAtDescIdDesc(
        conversationId, PageRequest.of(0, 40));
    List<PromptTurn> turns = rows.stream().filter(message -> !message.getId().equals(currentQuestionId))
        .filter(message -> message.getStatus() == AiConversationMessageStatus.COMPLETED)
        .limit(10).map(message -> new PromptTurn(message.getSenderType() == AiConversationSenderType.AGENT ? "assistant" : "user",
            message.getSender() == null ? "ACODI" : message.getSender().getName(), message.getContent()))
        .toList();
    List<PromptTurn> chronological = new ArrayList<>(turns);
    Collections.reverse(chronological);
    return chronological;
  }

  public Turn completeQuestion(UUID projectId, UUID conversationId, UUID questionId, String email,
      String answer, int promptTokens, int completionTokens, long latencyMs) {
    ProjectMember member = projects.requireAccess(projectId, email);
    AiConversation conversation = authorized(projectId, conversationId, member);
    AiConversationMessage question = question(conversationId, questionId, member);
    question.complete();
    question.recordUsage(promptTokens, completionTokens, latencyMs);
    User recipient = question.getSender();
    AiConversationMessage assistant = messages.save(new AiConversationMessage(conversation, UUID.randomUUID(),
        null, recipient, question, AiConversationSenderType.AGENT, answer.trim(), AiConversationMessageStatus.COMPLETED));
    conversation.touch();
    if (conversation.getScope() == AiConversationScope.GROUP) publish(conversation, assistant);
    return new Turn(response(question), response(assistant), true);
  }

  public Turn failQuestion(UUID projectId, UUID conversationId, UUID questionId, String email, Integer statusCode) {
    ProjectMember member = projects.requireAccess(projectId, email);
    AiConversation conversation = authorized(projectId, conversationId, member);
    AiConversationMessage question = question(conversationId, questionId, member);
    question.fail(statusCode);
    conversation.touch();
    if (conversation.getScope() == AiConversationScope.GROUP) publish(conversation, question);
    return new Turn(response(question), null, true);
  }

  @Transactional(readOnly = true)
  public Turn turn(UUID projectId, UUID conversationId, UUID questionId, String email) {
    ProjectMember member = projects.requireAccess(projectId, email);
    AiConversation conversation = authorized(projectId, conversationId, member);
    AiConversationMessage question = question(conversationId, questionId, member);
    AiConversationMessage answer = messages.findByReplyTo_Id(questionId).orElse(null);
    var proposalIds=jdbc.queryForList("SELECT p.id FROM ai_uml_proposal p JOIN ai_proposal_request r ON r.id=p.request_id WHERE r.question_id=? AND p.project_id=? AND p.conversation_id=? ORDER BY p.created_at DESC LIMIT 1",UUID.class,questionId,projectId,conversationId);
    return new Turn(response(question), answer == null ? null : response(answer), false,proposalIds.isEmpty()?null:proposalIds.getFirst());
  }

  /** Internal system path: always a group conversation, never supplied through a message HTTP request. */
  public Published publishRecommendation(UUID projectId, UUID diagramId, UUID conversationId, String email,
      String content, int promptTokens, int completionTokens, long latencyMs) {
    ProjectMember member = projects.requireCanEdit(projectId, email);
    DiagramaClase diagram = requireDiagram(projectId, diagramId);
    AiConversation conversation = conversationId == null ? null : conversations.findByIdAndProject_Id(conversationId, projectId).orElse(null);
    if (conversation != null && (conversation.getScope() != AiConversationScope.GROUP
        || conversation.getDiagram() == null || !conversation.getDiagram().getId().equals(diagramId)))
      throw new NotFoundException("Conversación grupal no encontrada.");
    if (conversation == null || conversation.getArchivedAt() != null) {
      conversation = conversations.saveAndFlush(new AiConversation(member.getProject(), diagram, AiConversationScope.GROUP,
          null, member.getUser(), "Recomendaciones — " + diagram.getNombre()));
    }
    AiConversationMessage message = new AiConversationMessage(conversation, UUID.randomUUID(), null, null, null,
        AiConversationSenderType.AGENT, content, AiConversationMessageStatus.COMPLETED);
    message.recordUsage(promptTokens, completionTokens, latencyMs);
    message = messages.saveAndFlush(message);
    conversation.touch();
    publish(conversation, message);
    if(channelChat!=null) channelChat.publishRecommendation(projectId,email,content,message.getId());
    return new Published(conversation.getId(), message.getId());
  }
  public record Published(UUID conversationId, UUID messageId) { }

  public void publishProposalDecision(UUID projectId,UUID conversationId,String email,String decision,Long revision) {
    ProjectMember member=projects.requireCanEdit(projectId,email);
    AiConversation conversation=authorized(projectId,conversationId,member);
    if(conversation.getArchivedAt()!=null) return;
    var message=messages.saveAndFlush(new AiConversationMessage(conversation,UUID.randomUUID(),null,member.getUser(),null,
        AiConversationSenderType.AGENT,member.getUser().getName()+" "+decision+(revision==null?".":" (revisión "+revision+")."),AiConversationMessageStatus.COMPLETED));
    conversation.touch();
    if(conversation.getScope()==AiConversationScope.GROUP) publish(conversation,message);
  }

  private AiConversationMessage question(UUID conversationId, UUID questionId, ProjectMember member) {
    AiConversationMessage question = messages.findByIdAndConversation_Id(questionId, conversationId)
        .orElseThrow(() -> new NotFoundException("Consulta no encontrada."));
    if (question.getSenderType() != AiConversationSenderType.USER || question.getSender() == null
        || !question.getSender().getId().equals(member.getUser().getId())) throw new NotFoundException("Consulta no encontrada.");
    return question;
  }

  private AiConversation authorized(UUID projectId, UUID conversationId, ProjectMember member) {
    AiConversation value = conversations.findByIdAndProject_Id(conversationId, projectId)
        .orElseThrow(() -> new NotFoundException("Conversación no encontrada."));
    if (value.getScope() == AiConversationScope.PRIVATE && !value.getOwner().getId().equals(member.getUser().getId())) {
      throw new NotFoundException("Conversación no encontrada.");
    }
    return value;
  }

  private void requireManage(AiConversation conversation, ProjectMember member) {
    if (conversation.getScope() == AiConversationScope.PRIVATE) {
      if (!conversation.getOwner().getId().equals(member.getUser().getId())) throw new NotFoundException("Conversación no encontrada.");
      return;
    }
    boolean creator = conversation.getCreatedBy().getId().equals(member.getUser().getId());
    boolean editor = member.getRole() == ProjectRole.EDITOR || member.getRole() == ProjectRole.PROPIETARIO;
    if (!creator && !editor) throw new ForbiddenException("Solo el creador o un editor del proyecto puede administrar esta conversación.");
  }

  private DiagramaClase requireDiagram(UUID projectId, UUID diagramId) {
    DiagramaClase diagram = diagrams.findById(diagramId).orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    if (!diagram.getProject().getId().equals(projectId)) throw new NotFoundException("Diagrama no encontrado en este proyecto.");
    return diagram;
  }

  private Conversation response(AiConversation value) {
    return new Conversation(value.getId(), value.getProject().getId(),
        value.getDiagram() == null ? null : value.getDiagram().getId(), value.getScope(), value.getTitle(),
        value.getCreatedBy().getId(), value.getCreatedBy().getName(), value.getCreatedAt(), value.getUpdatedAt(), value.getArchivedAt());
  }

  private Message response(AiConversationMessage value) {
    User sender = value.getSender(), recipient = value.getRecipient();
    return new Message(value.getId(), value.getClientMessageId(), value.getSenderType(),
        sender == null ? null : sender.getId(), sender == null ? "ACODI" : sender.getName(),
        recipient == null ? null : recipient.getId(), recipient == null ? null : recipient.getName(),
        value.getContent(), value.getStatus(), value.getErrorCode(), value.getPromptTokens(),
        value.getCompletionTokens(), value.getLatencyMs(), value.getReplyTo() == null ? null : value.getReplyTo().getId(), value.getCreatedAt());
  }

  private void publish(AiConversation conversation, AiConversationMessage message) {
    events.publishEvent(new AiConversationMessageCommittedEvent(conversation.getProject().getId(),
        conversation.getId(), response(message)));
  }

  private UUID readId(ResultSet resultSet) throws SQLException { return resultSet.next() ? resultSet.getObject("id", UUID.class) : null; }
  public record QuestionReceipt(Conversation conversation, Message question, boolean created) { }
  public record PromptTurn(String role, String senderName, String content) { }
}
