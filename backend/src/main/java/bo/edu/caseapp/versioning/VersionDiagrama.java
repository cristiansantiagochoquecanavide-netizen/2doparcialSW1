package bo.edu.caseapp.versioning;

import bo.edu.caseapp.uml.DiagramaClase;
import bo.edu.caseapp.user.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "version_diagrama")
public class VersionDiagrama {
  @Id
  @GeneratedValue
  private UUID id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "diagrama_id")
  private DiagramaClase diagrama;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "usuario_id")
  private User usuario;

  @Column(name = "numero_version", nullable = false)
  private int numeroVersion;

  @Column(length = 255)
  private String descripcion;

  @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
  private String snapshotJson;

  @Column(name = "source_revision", nullable = false)
  private long sourceRevision;

  @Column(name = "operation_type", nullable = false, length = 24)
  private String operationType = "MANUAL";

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "restored_from_version_id")
  private VersionDiagrama restoredFrom;

  @Column(name = "idempotency_key")
  private UUID idempotencyKey;

  @Column(name = "is_working", nullable = false)
  private boolean working;

  @Column(nullable = false)
  private Instant fecha = Instant.now();

  protected VersionDiagrama() {}

  public VersionDiagrama(DiagramaClase diagrama, User usuario, int numeroVersion, String descripcion, String snapshotJson) {
    this(diagrama, usuario, numeroVersion, descripcion, snapshotJson, diagrama.getRevision(), "MANUAL", null, null);
  }

  public VersionDiagrama(DiagramaClase diagrama, User usuario, int numeroVersion, String descripcion, String snapshotJson,
      long sourceRevision, String operationType, VersionDiagrama restoredFrom, UUID idempotencyKey) {
    this.diagrama = diagrama;
    this.usuario = usuario;
    this.numeroVersion = numeroVersion;
    this.descripcion = descripcion;
    this.snapshotJson = snapshotJson;
    this.sourceRevision = sourceRevision;
    this.operationType = operationType;
    this.restoredFrom = restoredFrom;
    this.idempotencyKey = idempotencyKey;
  }

  public UUID getId() { return id; }
  public DiagramaClase getDiagrama() { return diagrama; }
  public User getUsuario() { return usuario; }
  public int getNumeroVersion() { return numeroVersion; }
  public String getDescripcion() { return descripcion; }
  public String getSnapshotJson() { return snapshotJson; }
  public Instant getFecha() { return fecha; }
  public long getSourceRevision() { return sourceRevision; }
  public String getOperationType() { return operationType; }
  public VersionDiagrama getRestoredFrom() { return restoredFrom; }
  public UUID getIdempotencyKey() { return idempotencyKey; }
  public boolean isWorking() { return working; }
  public void setWorking(boolean working) { this.working = working; }
  public void updateWorkingSnapshot(String snapshotJson, long sourceRevision) {
    if (!working) throw new IllegalStateException("No se puede modificar una versión cerrada.");
    this.snapshotJson = snapshotJson;
    this.sourceRevision = sourceRevision;
    this.fecha = Instant.now();
  }
}
