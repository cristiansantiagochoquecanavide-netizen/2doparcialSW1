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

@Entity
@Table(name = "project_ai_memory_entry")
public class ProjectAiMemoryEntry {
  @Id @GeneratedValue private UUID id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "project_id", nullable = false) private Project project;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "diagram_id") private DiagramaClase diagram;
  @Enumerated(EnumType.STRING) @Column(name = "entry_type", nullable = false, length = 32) private AiMemoryEntryType entryType;
  @Enumerated(EnumType.STRING) @Column(name = "confidence_scope", nullable = false, length = 16) private AiMemoryConfidence confidenceScope;
  @Column(nullable = false, length = 4000) private String content;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "created_by") private User createdBy;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "updated_by", nullable = false) private User updatedBy;
  @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

  protected ProjectAiMemoryEntry() { }

  public ProjectAiMemoryEntry(Project project, DiagramaClase diagram, AiMemoryEntryType type,
      AiMemoryConfidence confidence, String content, User author) {
    this.project = project;
    this.diagram = diagram;
    this.entryType = type;
    this.confidenceScope = confidence;
    this.content = content;
    this.createdBy = author;
    this.updatedBy = author;
  }

  public void updateAsConfirmed(AiMemoryEntryType type, String content, User author) {
    this.entryType = type;
    this.content = content;
    this.confidenceScope = AiMemoryConfidence.CONFIRMED;
    this.updatedBy = author;
    this.updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public Project getProject() { return project; }
  public DiagramaClase getDiagram() { return diagram; }
  public AiMemoryEntryType getEntryType() { return entryType; }
  public AiMemoryConfidence getConfidenceScope() { return confidenceScope; }
  public String getContent() { return content; }
  public User getCreatedBy() { return createdBy; }
  public User getUpdatedBy() { return updatedBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
