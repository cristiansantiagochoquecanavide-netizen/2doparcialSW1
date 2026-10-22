package bo.edu.caseapp.ai;

import bo.edu.caseapp.project.Project;
import bo.edu.caseapp.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;
import jakarta.persistence.Transient;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.MapsId;

@Entity
@Table(name = "project_ai_memory")
public class ProjectAiMemory implements Persistable<UUID> {
  @Id @Column(name = "project_id") private UUID projectId;
  @Transient private boolean newEntity = true;
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId @JoinColumn(name = "project_id", nullable = false)
  private Project project;
  @Column(name = "system_name", length = 160) private String systemName;
  @Column(name = "system_description", length = 4000) private String systemDescription;
  @Column(name = "business_domain", length = 1000) private String businessDomain;
  @Column(columnDefinition = "TEXT") private String objectives;
  @Column(name = "additional_context", columnDefinition = "TEXT") private String additionalContext;
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "updated_by", nullable = false) private User updatedBy;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

  protected ProjectAiMemory() { }

  public ProjectAiMemory(Project project, User author) {
    this.project = project;
    this.projectId = project.getId();
    this.updatedBy = author;
  }

  public void update(String name, String description, String domain, String objectives,
      String additionalContext, User author) {
    this.systemName = name;
    this.systemDescription = description;
    this.businessDomain = domain;
    this.objectives = objectives;
    this.additionalContext = additionalContext;
    this.updatedBy = author;
    this.updatedAt = Instant.now();
  }

  @Override public UUID getId() { return projectId; }
  @Override @Transient public boolean isNew() { return newEntity; }
  @PostLoad @PostPersist void markPersisted() { this.newEntity = false; }
  public UUID getProjectId() { return projectId; }
  public Project getProject() { return project; }
  public String getSystemName() { return systemName; }
  public String getSystemDescription() { return systemDescription; }
  public String getBusinessDomain() { return businessDomain; }
  public String getObjectives() { return objectives; }
  public String getAdditionalContext() { return additionalContext; }
  public User getUpdatedBy() { return updatedBy; }
  public Instant getUpdatedAt() { return updatedAt; }
}
