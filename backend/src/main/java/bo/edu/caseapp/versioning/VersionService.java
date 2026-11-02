package bo.edu.caseapp.versioning;

import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.uml.UmlResponses.DiagramFullView;
import bo.edu.caseapp.uml.UmlResponses.ElementDetail;
import bo.edu.caseapp.uml.UmlResponses.RelationDetail;
import bo.edu.caseapp.user.User;
import bo.edu.caseapp.user.UserRepository;
import bo.edu.caseapp.versioning.VersionRequests.CreateVersion;
import bo.edu.caseapp.versioning.VersionResponses.VersionDetail;
import bo.edu.caseapp.versioning.VersionResponses.VersionSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class VersionService {
  private final VersionDiagramaRepository versionRepo;
  private final DiagramaClaseRepository diagramRepo;
  private final ElementoUMLRepository elementRepo;
  private final RelacionUMLRepository relationRepo;
  private final ProjectService projectService;
  private final UserRepository userRepo;
  private final UmlService umlService;
  private final ObjectMapper objectMapper;

  public VersionService(
      VersionDiagramaRepository versionRepo,
      DiagramaClaseRepository diagramRepo,
      ElementoUMLRepository elementRepo,
      RelacionUMLRepository relationRepo,
      ProjectService projectService,
      UserRepository userRepo,
      UmlService umlService,
      ObjectMapper objectMapper
  ) {
    this.versionRepo = versionRepo;
    this.diagramRepo = diagramRepo;
    this.elementRepo = elementRepo;
    this.relationRepo = relationRepo;
    this.projectService = projectService;
    this.userRepo = userRepo;
    this.umlService = umlService;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<VersionSummary> listVersions(UUID projectId, UUID diagramId, String email) {
    projectService.requireAccess(projectId, email);
    validateDiagramBelongsToProject(projectId, diagramId);
    return versionRepo.findByDiagramaIdOrderByNumeroVersionDesc(diagramId)
        .stream()
        .map(this::toSummary)
        .toList();
  }

  @Transactional(readOnly = true)
  public Page<VersionSummary> listVersions(UUID projectId, UUID diagramId, String email, Pageable pageable) {
    projectService.requireAccess(projectId, email);
    validateDiagramBelongsToProject(projectId, diagramId);
    return versionRepo.findByDiagramaIdOrderByNumeroVersionDesc(diagramId, pageable).map(this::toSummary);
  }

  @Transactional(readOnly = true)
  public VersionDetail getVersion(UUID projectId, UUID diagramId, UUID versionId, String email) {
    projectService.requireAccess(projectId, email);
    validateDiagramBelongsToProject(projectId, diagramId);
    VersionDiagrama version = versionRepo.findByIdAndDiagramaId(versionId, diagramId)
        .orElseThrow(() -> new NotFoundException("Versión no encontrada."));
    return toDetail(version);
  }

  @Transactional(readOnly = true)
  public VersionDetail getVersionAtNumber(UUID projectId, UUID diagramId, int number, String email) {
    projectService.requireAccess(projectId, email);
    validateDiagramBelongsToProject(projectId, diagramId);
    VersionDiagrama version = versionRepo.findByDiagramaIdAndNumeroVersion(diagramId, number)
        .orElseThrow(() -> new NotFoundException("No existe la versión solicitada."));
    return toDetail(version);
  }

  public VersionSummary createVersion(UUID projectId, UUID diagramId, String email, CreateVersion request) {
    projectService.requireCanEdit(projectId, email);
    validateDiagramBelongsToProject(projectId, diagramId);
    DiagramaClase diagram = diagramRepo.lockById(diagramId)
        .orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    User user = userRepo.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new NotFoundException("Usuario no encontrado."));

    if (request.idempotencyKey() != null) {
      var existing = versionRepo.findByDiagramaIdAndIdempotencyKey(diagramId, request.idempotencyKey());
      if (existing.isPresent()) return toSummary(existing.get());
    }

    DiagramFullView fullView = umlService.getDiagram(projectId, diagramId, email);

    String snapshotJson;
    try {
      snapshotJson = objectMapper.writeValueAsString(fullView);
    } catch (JsonProcessingException e) {
      throw new BadRequestException("No se pudo generar el snapshot del diagrama.");
    }
    ensureSnapshotSize(snapshotJson);

    versionRepo.findFirstByDiagramaIdAndWorkingTrue(diagramId).ifPresent(active -> {
      active.setWorking(false);
      versionRepo.saveAndFlush(active);
    });

    int nextVersion = versionRepo.findMaxVersionNumber(diagramId) + 1;
    String descripcion = request.descripcion() != null && !request.descripcion().isBlank()
        ? request.descripcion().trim()
        : "Versión " + nextVersion;

    VersionDiagrama version = new VersionDiagrama(diagram, user, nextVersion, descripcion, snapshotJson,
        diagram.getRevision(), "MANUAL", null, request.idempotencyKey());
    version.setWorking(true);
    return toSummary(versionRepo.save(version));
  }

  @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void captureAutomatic(UmlVersionCaptureRequested event) {
    DiagramaClase diagram = diagramRepo.lockById(event.diagramId())
        .orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    projectService.requireCanEdit(diagram.getProject().getId(), event.actorEmail());
    DiagramFullView fullView = umlService.getDiagram(diagram.getProject().getId(), event.diagramId(), event.actorEmail());
    // An earlier after-commit callback may run after a newer collaborator revision. Let that newer event own its snapshot.
    if (fullView.diagram().revision() != event.revision()) return;
    String snapshotJson;
    try { snapshotJson = objectMapper.writeValueAsString(fullView); }
    catch (JsonProcessingException e) { throw new BadRequestException("No se pudo generar el snapshot automático."); }
    ensureSnapshotSize(snapshotJson);
    User user = userRepo.findByEmailIgnoreCase(event.actorEmail())
        .orElseThrow(() -> new NotFoundException("Usuario no encontrado."));
    String source = Set.of("ACODI", "IMPORT", "UML_EDIT", "INITIAL").contains(event.source()) ? event.source() : "UML_EDIT";
    var active = versionRepo.findFirstByDiagramaIdAndWorkingTrue(event.diagramId());
    if (active.isPresent()) {
      active.get().updateWorkingSnapshot(snapshotJson, fullView.diagram().revision());
      versionRepo.save(active.get());
      return;
    }
    int next = versionRepo.findMaxVersionNumber(event.diagramId()) + 1;
    String description = next == 1 ? "Versión inicial" : "Continuación del trabajo";
    VersionDiagrama initial = new VersionDiagrama(diagram, user, next, description, snapshotJson,
        fullView.diagram().revision(), source, null, null);
    initial.setWorking(true);
    versionRepo.save(initial);
  }

  private void ensureSnapshotSize(String snapshotJson) {
    if (snapshotJson.length() > 10 * 1024 * 1024)
      throw new BadRequestException("El snapshot excede el límite de almacenamiento de 10 MiB.");
  }

  private boolean equivalentSnapshot(String first, String second) {
    try {
      var a = objectMapper.readTree(first);
      var b = objectMapper.readTree(second);
      for (var node : List.of(a, b)) {
        var diagram = node.path("diagram");
        if (diagram.isObject()) {
          ((com.fasterxml.jackson.databind.node.ObjectNode) diagram).remove(List.of("revision", "bloqueado", "bloqueadoPor", "fechaBloqueo"));
        }
        for (String collection : List.of("elements", "relations")) {
          var array = node.path(collection);
          if (array.isArray()) {
            var sorted = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            array.forEach(sorted::add);
            sorted.sort(Comparator.comparing(item -> item.path("id").asText()));
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).set(collection, objectMapper.valueToTree(sorted));
          }
        }
      }
      return objectMapper.writeValueAsString(a).equals(objectMapper.writeValueAsString(b));
    } catch (Exception ignored) { return false; }
  }

  public DiagramFullView restoreVersion(UUID projectId, UUID diagramId, UUID versionId, String email) {
    return restoreVersion(projectId, diagramId, versionId, email, null);
  }

  public DiagramFullView restoreVersion(UUID projectId, UUID diagramId, UUID versionId, String email, Long expectedRevision) {
    return restoreVersion(projectId, diagramId, versionId, email, expectedRevision, UUID.randomUUID());
  }

  public DiagramFullView restoreVersion(UUID projectId, UUID diagramId, UUID versionId, String email,
      Long expectedRevision, UUID idempotencyKey) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = validateDiagramBelongsToProject(projectId, diagramId);

    VersionDiagrama repeated = versionRepo.findByDiagramaIdAndIdempotencyKey(diagramId, idempotencyKey).orElse(null);
    if (repeated != null) {
      try { return objectMapper.readValue(repeated.getSnapshotJson(), DiagramFullView.class); }
      catch (JsonProcessingException e) { throw new BadRequestException("Snapshot de restauración corrupto."); }
    }

    VersionDiagrama version = versionRepo.findByIdAndDiagramaId(versionId, diagramId)
        .orElseThrow(() -> new NotFoundException("Versión no encontrada."));

    DiagramFullView snapshot;
    try {
      snapshot = objectMapper.readValue(version.getSnapshotJson(), DiagramFullView.class);
    } catch (JsonProcessingException e) {
      throw new BadRequestException("Snapshot de versión corrupto o incompatible.");
    }

    if (snapshot == null || snapshot.diagram() == null || !diagramId.equals(snapshot.diagram().id())
        || snapshot.elements() == null || snapshot.relations() == null)
      throw new BadRequestException("El snapshot no pertenece a este diagrama o está incompleto.");
    umlService.requireNotLocked(diagram, email);
    long newRev = umlService.reserveNextRevision(diagramId, expectedRevision);
    diagram.setRevision(newRev);
    versionRepo.findFirstByDiagramaIdAndWorkingTrue(diagramId).ifPresent(active -> {
      active.setWorking(false);
      versionRepo.saveAndFlush(active);
    });

    // 1. Eliminar relaciones y elementos actuales del diagrama
    relationRepo.deleteByDiagramaId(diagramId);
    elementRepo.deleteByDiagramaId(diagramId);
    relationRepo.flush();
    elementRepo.flush();

    // 2. Mapeo de IDs antiguos a nuevos
    Map<UUID, ElementoUML> idMapping = new HashMap<>();
    for (ElementDetail el : snapshot.elements()) {
      ElementoUML nuevo = new ElementoUML(
          el.id(),
          diagram,
          el.tipoElemento(),
          el.nombre(),
          el.posicionX(),
          el.posicionY(),
          el.propiedadesJson()
      );
      ElementoUML saved = elementRepo.save(nuevo);
      idMapping.put(el.id(), saved);
    }

    // 3. Recrear relaciones
    for (RelationDetail rel : snapshot.relations()) {
      ElementoUML origen = idMapping.get(rel.elementoOrigen());
      ElementoUML destino = idMapping.get(rel.elementoDestino());
      if (origen == null || destino == null || !diagramId.equals(rel.diagramaId()))
        throw new BadRequestException("El snapshot contiene una relación con referencias inválidas.");
      {
        RelacionUML nuevaRel = new RelacionUML(
            rel.id(),
            diagram,
            origen,
            destino,
            rel.nombreRelacion(),
            rel.tipoRelacion(),
            rel.multiplicidadOrigen(),
            rel.multiplicidadDestino()
        );
        relationRepo.save(nuevaRel);
      }
    }

    elementRepo.flush();
    relationRepo.flush();
    DiagramFullView restored = umlService.getDiagram(projectId, diagramId, email);
    User user = userRepo.findByEmailIgnoreCase(email).orElseThrow(() -> new NotFoundException("Usuario no encontrado."));
    String snapshotJson;
    try { snapshotJson = objectMapper.writeValueAsString(restored); }
    catch (JsonProcessingException e) { throw new BadRequestException("No se pudo guardar la versión restaurada."); }
    ensureSnapshotSize(snapshotJson);
    int nextVersion = versionRepo.findMaxVersionNumber(diagramId) + 1;
    String description = "Restauración de v" + version.getNumeroVersion();
    VersionDiagrama restoreRecord = new VersionDiagrama(diagram, user, nextVersion, description, snapshotJson,
        newRev, "RESTORE", version, idempotencyKey);
    restoreRecord.setWorking(true);
    versionRepo.save(restoreRecord);
    umlService.notifyCollabAfterCommit(diagramId, "VERSION_RESTORED", newRev);
    return restored;
  }

  private DiagramaClase validateDiagramBelongsToProject(UUID projectId, UUID diagramId) {
    DiagramaClase diagram = diagramRepo.findById(diagramId)
        .orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    if (!diagram.getProject().getId().equals(projectId)) {
      throw new NotFoundException("Diagrama no pertenece al proyecto.");
    }
    return diagram;
  }

  private VersionSummary toSummary(VersionDiagrama v) {
    return new VersionSummary(
        v.getId(),
        v.getDiagrama().getId(),
        v.getNumeroVersion(),
        v.getDescripcion(),
        v.getUsuario().getName(),
        v.getUsuario().getEmail(),
        v.getFecha(), v.getSourceRevision(), v.getOperationType(),
        v.getRestoredFrom() == null ? null : v.getRestoredFrom().getId(), v.isWorking()
    );
  }

  private VersionDetail toDetail(VersionDiagrama v) {
    return new VersionDetail(v.getId(), v.getDiagrama().getId(), v.getNumeroVersion(), v.getDescripcion(),
        v.getSnapshotJson(), v.getUsuario().getName(), v.getUsuario().getEmail(), v.getFecha(), v.getSourceRevision(),
        v.getOperationType(), v.getRestoredFrom() == null ? null : v.getRestoredFrom().getId(), v.isWorking());
  }
}
