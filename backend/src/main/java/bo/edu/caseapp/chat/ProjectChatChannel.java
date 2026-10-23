package bo.edu.caseapp.chat;

import bo.edu.caseapp.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_chat_channel")
public class ProjectChatChannel {
  @Id
  @GeneratedValue
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "project_id", nullable = false)
  private Project project;

  @Column(nullable = false, length = 80)
  private String slug;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 500)
  private String description;

  @Column(name = "channel_type", nullable = false, length = 8)
  private String channelType = "TEXT";

  @Column(name = "archived_at")
  private Instant archivedAt;

  @Column(name = "sort_order", nullable = false)
  private short sortOrder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  protected ProjectChatChannel() {}

  ProjectChatChannel(Project project, String slug, String name, String description, short sortOrder) {
    this.project = project;
    this.slug = slug;
    this.name = name;
    this.description = description;
    this.sortOrder = sortOrder;
  }

  public UUID getId() { return id; }
  public Project getProject() { return project; }
  public String getSlug() { return slug; }
  public String getName() { return name; }
  public String getDescription() { return description; }
  public short getSortOrder() { return sortOrder; }
  public Instant getCreatedAt() { return createdAt; }
  public String getChannelType() { return channelType; }
  public Instant getArchivedAt() { return archivedAt; }
  public void setChannelType(String value) { channelType = value; }
  public void rename(String value) { name = value; }
  public void archive() { archivedAt = Instant.now(); }
}
