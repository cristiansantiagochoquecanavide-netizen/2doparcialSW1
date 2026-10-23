package bo.edu.caseapp.chat;

import bo.edu.caseapp.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_chat_message")
public class ProjectChatMessage {
  @Id
  @GeneratedValue
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "channel_id", nullable = false)
  private ProjectChatChannel channel;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "sender_user_id")
  private User sender;

  @Column(name = "client_message_id", nullable = false)
  private UUID clientMessageId;

  @Column(name = "sender_role", nullable = false, length = 20)
  private String senderRole;

  @Enumerated(EnumType.STRING)
  @Column(name = "author_type", nullable = false, length = 16)
  private ChatAuthorType authorType = ChatAuthorType.USER;

  @Column(nullable = false, length = 4000)
  private String content;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "reply_to_message_id")
  private ProjectChatMessage replyTo;
  @Column(name="canonical_ai_message_id") private UUID canonicalAiMessageId;

  protected ProjectChatMessage() {}

  ProjectChatMessage(
      ProjectChatChannel channel,
      User sender,
      UUID clientMessageId,
      String senderRole,
      ChatAuthorType authorType,
      String content,
      Instant createdAt,
      ProjectChatMessage replyTo
  ) {
    this.channel = channel;
    this.sender = sender;
    this.clientMessageId = clientMessageId;
    this.senderRole = senderRole;
    this.authorType = authorType;
    this.content = content;
    this.createdAt = createdAt;
    this.replyTo = replyTo;
  }

  public UUID getId() { return id; }
  public ProjectChatChannel getChannel() { return channel; }
  public User getSender() { return sender; }
  public UUID getClientMessageId() { return clientMessageId; }
  public String getSenderRole() { return senderRole; }
  public ChatAuthorType getAuthorType() { return authorType; }
  public String getContent() { return content; }
  public Instant getCreatedAt() { return createdAt; }
  public ProjectChatMessage getReplyTo() { return replyTo; }
  public UUID getCanonicalAiMessageId() {return canonicalAiMessageId;}
  public void setCanonicalAiMessageId(UUID id) {canonicalAiMessageId=id;}
}
