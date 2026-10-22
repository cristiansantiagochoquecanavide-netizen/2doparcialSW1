package bo.edu.caseapp.ai;

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

@Entity @Table(name = "ai_conversation_message")
public class AiConversationMessage {
  @Id @GeneratedValue private UUID id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "conversation_id", nullable = false) private AiConversation conversation;
  @Column(name = "client_message_id", nullable = false) private UUID clientMessageId;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "sender_user_id") private User sender;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "recipient_user_id") private User recipient;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "reply_to_message_id") private AiConversationMessage replyTo;
  @Enumerated(EnumType.STRING) @Column(name = "sender_type", nullable = false, length = 8) private AiConversationSenderType senderType;
  @Column(nullable = false, length = 8000) private String content;
  @Enumerated(EnumType.STRING) @Column(name = "processing_status", nullable = false, length = 12) private AiConversationMessageStatus status;
  @Column(name = "error_code", length = 40) private String errorCode;
  @Column(name = "prompt_tokens", nullable = false) private int promptTokens;
  @Column(name = "completion_tokens", nullable = false) private int completionTokens;
  @Column(name = "latency_ms") private Long latencyMs;
  @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();

  protected AiConversationMessage() { }
  public AiConversationMessage(AiConversation conversation, UUID clientMessageId, User sender, User recipient,
      AiConversationMessage replyTo, AiConversationSenderType senderType, String content, AiConversationMessageStatus status) {
    this.conversation = conversation; this.clientMessageId = clientMessageId; this.sender = sender;
    this.recipient = recipient; this.senderType = senderType; this.content = content; this.status = status;
    this.replyTo = replyTo;
  }
  public void complete() { this.status = AiConversationMessageStatus.COMPLETED; this.errorCode = null; }
  public void fail(Integer statusCode) {
    this.status = AiConversationMessageStatus.ERROR;
    this.errorCode = statusCode == null ? "PROVIDER_ERROR" : "HTTP_" + statusCode;
  }
  public void reopenForRetry() { this.status = AiConversationMessageStatus.PENDING; this.errorCode = null; }
  public void recordUsage(int promptTokens, int completionTokens, long latencyMs) {
    this.promptTokens = Math.max(0, promptTokens); this.completionTokens = Math.max(0, completionTokens);
    this.latencyMs = Math.max(0, latencyMs);
  }
  public UUID getId() { return id; }
  public AiConversation getConversation() { return conversation; }
  public UUID getClientMessageId() { return clientMessageId; }
  public User getSender() { return sender; }
  public User getRecipient() { return recipient; }
  public AiConversationMessage getReplyTo() { return replyTo; }
  public AiConversationSenderType getSenderType() { return senderType; }
  public String getContent() { return content; }
  public AiConversationMessageStatus getStatus() { return status; }
  public String getErrorCode() { return errorCode; }
  public int getPromptTokens() { return promptTokens; }
  public int getCompletionTokens() { return completionTokens; }
  public Long getLatencyMs() { return latencyMs; }
  public Instant getCreatedAt() { return createdAt; }
}
