package bo.edu.caseapp.ai;

import bo.edu.caseapp.project.Project;
import bo.edu.caseapp.uml.DiagramaClase;
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

@Entity @Table(name = "ai_conversation")
public class AiConversation {
  @Id @GeneratedValue private UUID id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id", nullable = false) private Project project;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "diagram_id") private DiagramaClase diagram;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) private AiConversationScope scope;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "owner_user_id") private User owner;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by", nullable = false) private User createdBy;
  @Column(nullable = false, length = 160) private String title;
  @Column(name = "archived_at") private Instant archivedAt;
  @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

  protected AiConversation() { }
  public AiConversation(Project project, DiagramaClase diagram, AiConversationScope scope, User owner, User createdBy, String title) {
    this.project = project; this.diagram = diagram; this.scope = scope; this.owner = owner;
    this.createdBy = createdBy; this.title = title; this.updatedAt = Instant.now();
  }
  public void rename(String title) { this.title = title; this.updatedAt = Instant.now(); }
  public void archive() { this.archivedAt = Instant.now(); this.updatedAt = this.archivedAt; }
  public void touch() { this.updatedAt = Instant.now(); }
  public UUID getId() { return id; }
  public Project getProject() { return project; }
  public DiagramaClase getDiagram() { return diagram; }
  public AiConversationScope getScope() { return scope; }
  public User getOwner() { return owner; }
  public User getCreatedBy() { return createdBy; }
  public String getTitle() { return title; }
  public Instant getArchivedAt() { return archivedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
