package bo.edu.caseapp.uml;

import bo.edu.caseapp.user.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "uml_comment")
public class UmlComment {
  @Id @GeneratedValue private UUID id;
  @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "diagrama_id") private DiagramaClase diagrama;
  @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "author_user_id") private User author;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "elemento_id") private ElementoUML elemento;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "relacion_id") private RelacionUML relacion;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id") private UmlComment parent;
  @Column(nullable = false, length = 2000) private String content;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status = Status.OPEN;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
  @Column(name = "resolved_at") private Instant resolvedAt;
  @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "resolved_by") private User resolvedBy;
  @Column(name = "posicion_x") private Double posicionX;
  @Column(name = "posicion_y") private Double posicionY;
  @Column(name = "canvas_width", nullable = false) private int canvasWidth = 180;
  @Column(name = "canvas_height", nullable = false) private int canvasHeight = 90;

  protected UmlComment() {}
  public UmlComment(DiagramaClase diagrama, User author, ElementoUML elemento, RelacionUML relacion, UmlComment parent, String content, Double posicionX, Double posicionY) {
    this.diagrama = diagrama; this.author = author; this.elemento = elemento; this.relacion = relacion; this.parent = parent; this.content = content; this.posicionX = posicionX; this.posicionY = posicionY;
  }
  public UUID getId() { return id; }
  public DiagramaClase getDiagrama() { return diagrama; }
  public User getAuthor() { return author; }
  public ElementoUML getElemento() { return elemento; }
  public RelacionUML getRelacion() { return relacion; }
  public UmlComment getParent() { return parent; }
  public String getContent() { return content; }
  public Status getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Instant getResolvedAt() { return resolvedAt; }
  public User getResolvedBy() { return resolvedBy; }
  public Double getPosicionX() { return posicionX; }
  public Double getPosicionY() { return posicionY; }
  public int getCanvasWidth() { return canvasWidth; }
  public int getCanvasHeight() { return canvasHeight; }
  public void resolve(User user) { status = Status.RESOLVED; resolvedBy = user; resolvedAt = Instant.now(); updatedAt = Instant.now(); }
  public void reopen() { status = Status.OPEN; resolvedBy = null; resolvedAt = null; updatedAt = Instant.now(); }
  public void updateCanvas(String content, Double posicionX, Double posicionY, Integer canvasWidth, Integer canvasHeight) {
    if (content != null && !content.isBlank()) this.content = content.trim();
    if (posicionX != null) this.posicionX = posicionX;
    if (posicionY != null) this.posicionY = posicionY;
    if (canvasWidth != null) this.canvasWidth = Math.max(120, Math.min(600, canvasWidth));
    if (canvasHeight != null) this.canvasHeight = Math.max(70, Math.min(600, canvasHeight));
    updatedAt = Instant.now();
  }
  public enum Status { OPEN, RESOLVED }
}
