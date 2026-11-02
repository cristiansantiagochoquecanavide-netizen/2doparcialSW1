package bo.edu.caseapp.uml;

import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.error.ConflictException;
import bo.edu.caseapp.error.ForbiddenException;
import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.project.Project;
import bo.edu.caseapp.project.ProjectMember;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.UmlRequests.*;
import bo.edu.caseapp.uml.UmlResponses.*;
import bo.edu.caseapp.uml.relational.RelationalModelService;
import bo.edu.caseapp.uml.relational.RelationalSchema;
import bo.edu.caseapp.uml.validation.UmlValidatorService;
import bo.edu.caseapp.uml.validation.ValidationReport;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Transactional
public class UmlService {
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private bo.edu.caseapp.ai.AiAnalysisQueue aiAnalysisQueue;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.context.ApplicationEventPublisher eventPublisher;
  private enum SaveMode { CREATE, UPDATE }
  private record Batch(UUID diagramId, long baseRevision, long revision) { }
  private final ThreadLocal<Batch> activeBatch = new ThreadLocal<>();

  private final DiagramaClaseRepository diagrams;
  private final ElementoUMLRepository elements;
  private final RelacionUMLRepository relations;
  private final ProjectService projectService;
  private final UmlValidatorService validatorService;
  private final RelationalModelService relationalModelService;
  private final bo.edu.caseapp.uml.collab.UmlWebSocketHandler collabHandler;

  public UmlService(
      DiagramaClaseRepository diagrams,
      ElementoUMLRepository elements,
      RelacionUMLRepository relations,
      ProjectService projectService,
      UmlValidatorService validatorService,
      RelationalModelService relationalModelService
  ) {
    this(diagrams, elements, relations, projectService, validatorService, relationalModelService, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public UmlService(
      DiagramaClaseRepository diagrams,
      ElementoUMLRepository elements,
      RelacionUMLRepository relations,
      ProjectService projectService,
      UmlValidatorService validatorService,
      RelationalModelService relationalModelService,
      @org.springframework.beans.factory.annotation.Autowired(required = false) bo.edu.caseapp.uml.collab.UmlWebSocketHandler collabHandler
  ) {
    this.diagrams = diagrams;
    this.elements = elements;
    this.relations = relations;
    this.projectService = projectService;
    this.validatorService = validatorService;
    this.relationalModelService = relationalModelService;
    this.collabHandler = collabHandler;
  }

  public void notifyCollabAfterCommit(UUID diagramId, String eventType, Long revision) {
    notifyCollabAfterCommit(diagramId, eventType, revision, null);
  }

  public void notifyCollabAfterCommit(UUID diagramId, String eventType, Long revision, String actorEmail) {
    notifyCollabAfterCommit(diagramId, eventType, revision, actorEmail, null);
  }

  public void notifyCollabAfterCommit(UUID diagramId, String eventType, Long revision, String actorEmail, String sourceOverride) {
    if (activeBatch.get() != null && activeBatch.get().diagramId().equals(diagramId)) return;
    if (diagramId == null) return;
    String source = sourceOverride != null ? sourceOverride : "PROPOSAL_APPLIED".equals(eventType) ? "ACODI"
        : eventType != null && (eventType.contains("IMPORT") || eventType.contains("XMI")) ? "IMPORT" : "UML_EDIT";
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          if (collabHandler != null) collabHandler.broadcastDiagramChanged(diagramId, eventType, revision);
          if (actorEmail != null && revision != null && eventPublisher != null)
            eventPublisher.publishEvent(new bo.edu.caseapp.versioning.UmlVersionCaptureRequested(diagramId, actorEmail, revision, source));
        }
      });
    } else {
      if (collabHandler != null) collabHandler.broadcastDiagramChanged(diagramId, eventType, revision);
      if (actorEmail != null && revision != null && eventPublisher != null)
        eventPublisher.publishEvent(new bo.edu.caseapp.versioning.UmlVersionCaptureRequested(diagramId, actorEmail, revision, source));
    }
  }

  private void notifyCollab(UUID diagramId, String eventType) {
    notifyCollab(diagramId, eventType, null);
  }

  private void notifyCollab(UUID diagramId, String eventType, Long revision) {
    notifyCollabAfterCommit(diagramId, eventType, revision);
  }

  public long reserveNextRevision(UUID diagramId, Long expectedRevision) {
    Batch batch = activeBatch.get();
    if (batch != null) {
      if (!batch.diagramId().equals(diagramId) || (expectedRevision != null
          && expectedRevision != batch.baseRevision() && expectedRevision != batch.revision()))
        throw new ConflictException("La operación no pertenece al lote UML autorizado.");
      return batch.revision();
    }
    if (aiAnalysisQueue != null) aiAnalysisQueue.beforeMutation(diagramId);
    if (expectedRevision != null) {
      int updated = diagrams.incrementRevisionIfMatches(diagramId, expectedRevision);
      if (updated == 0) {
        long currentRev = diagrams.findRevisionById(diagramId).orElse(1L);
        throw new ConflictException("Conflicto de concurrencia: el diagrama ha sido modificado por otro colaborador (revisión esperada: "
            + expectedRevision + ", revisión actual: " + currentRev + "). Actualiza el diagrama para sincronizar.");
      }
      return expectedRevision + 1;
    } else {
      diagrams.incrementRevisionUnconditional(diagramId);
      return diagrams.findRevisionById(diagramId).orElse(1L);
    }
  }

  /** One CAS and one post-commit event; all writes still use the normal UML business methods. */
  public <T> T mutateBatch(UUID projectId, UUID diagramId, String email, long expectedRevision,
      java.util.function.Supplier<T> operations) {
    return mutateBatch(projectId, diagramId, email, expectedRevision, "UML_EDIT", "UML_BATCH_UPDATED", operations);
  }

  public <T> T mutateBatch(UUID projectId, UUID diagramId, String email, long expectedRevision,
      String source, String eventType, java.util.function.Supplier<T> operations) {
    if (activeBatch.get() != null) throw new BadRequestException("No se admiten lotes UML anidados.");
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = findDiagram(projectId, diagramId);
    requireNotLocked(diagram, email);
    long next = reserveNextRevision(diagramId, expectedRevision);
    diagram.setRevision(next);
    activeBatch.set(new Batch(diagramId, expectedRevision, next));
    T result;
    try { result = operations.get(); } finally { activeBatch.remove(); }
    notifyCollabAfterCommit(diagramId, eventType, next, email, source);
    return result;
  }

  @Transactional(readOnly = true)
  public long getDiagramRevision(UUID diagramId) {
    return diagrams.findRevisionById(diagramId).orElse(1L);
  }

  @Transactional(readOnly = true)
  public List<DiagramSummary> listDiagrams(UUID projectId, String email) {
    projectService.requireAccess(projectId, email);
    List<DiagramaClase> list = diagrams.findByProjectIdOrderByFechaCreacionAsc(projectId);
    return list.stream().map(this::summary).toList();
  }

  public DiagramSummary getOrCreateDefaultDiagram(UUID projectId, String email) {
    ProjectMember member = projectService.requireAccess(projectId, email);
    List<DiagramaClase> list = diagrams.findByProjectIdOrderByFechaCreacionAsc(projectId);
    if (!list.isEmpty()) {
      return summary(list.get(0));
    }
    // Si no hay diagramas y el usuario puede editar, creamos el diagrama inicial
    projectService.requireCanEdit(projectId, email);
    DiagramaClase nuevo = diagrams.save(new DiagramaClase(member.getProject(), "Diagrama Principal", "Diagrama de clases inicial"));
    notifyCollabAfterCommit(nuevo.getId(), "DIAGRAM_CREATED", nuevo.getRevision(), email, "INITIAL");
    return summary(nuevo);
  }

  public DiagramSummary createDiagram(UUID projectId, String email, CreateDiagram request) {
    ProjectMember member = projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = diagrams.save(new DiagramaClase(member.getProject(), request.nombre().trim(), clean(request.descripcion())));
    notifyCollabAfterCommit(diagram.getId(), "DIAGRAM_CREATED", diagram.getRevision(), email, "INITIAL");
    return summary(diagram);
  }

  @Transactional(readOnly = true)
  public DiagramFullView getDiagram(UUID projectId, UUID diagramId, String email) {
    projectService.requireAccess(projectId, email);
    DiagramaClase diagram = findDiagram(projectId, diagramId);
    List<ElementDetail> elementDetails = elements.findByDiagramaId(diagramId).stream().map(this::elementDetail).toList();
    List<RelationDetail> relationDetails = relations.findByDiagramaId(diagramId).stream().map(this::relationDetail).toList();
    return new DiagramFullView(summary(diagram), elementDetails, relationDetails);
  }

  @Transactional(readOnly = true)
  public void requireDiagramAccess(UUID projectId, UUID diagramId, String email) {
    projectService.requireAccess(projectId, email);
    findDiagram(projectId, diagramId);
  }

  @Transactional(readOnly = true)
  public ValidationReport validateDiagram(UUID projectId, UUID diagramId, String email) {
    projectService.requireAccess(projectId, email);
    findDiagram(projectId, diagramId);
    List<ElementoUML> elList = elements.findByDiagramaId(diagramId);
    List<RelacionUML> relList = relations.findByDiagramaId(diagramId);
    return validatorService.validate(elList, relList);
  }

  @Transactional(readOnly = true)
  public RelationalSchema generateRelationalSchema(UUID projectId, UUID diagramId, String email) {
    projectService.requireAccess(projectId, email);
    findDiagram(projectId, diagramId);
    List<ElementoUML> elList = elements.findByDiagramaId(diagramId);
    List<RelacionUML> relList = relations.findByDiagramaId(diagramId);
    return relationalModelService.generateSchema(elList, relList);
  }

  public void requireNotLocked(DiagramaClase diagram, String email) {
    if (diagram.isBloqueado() && (diagram.getBloqueadoPor() == null || !diagram.getBloqueadoPor().equalsIgnoreCase(email))) {
      throw new ForbiddenException("El diagrama está bloqueado por " + diagram.getBloqueadoPor() + " durante una operación de importación/exportación EA.");
    }
  }

  public void acquireLock(UUID projectId, UUID diagramId, String email) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = findDiagram(projectId, diagramId);
    if (diagram.isBloqueado() && !email.equalsIgnoreCase(diagram.getBloqueadoPor())) {
      throw new ForbiddenException("El diagrama ya está bloqueado por " + diagram.getBloqueadoPor());
    }
    diagram.bloquear(email);
    diagrams.save(diagram);
    notifyCollab(diagramId, "DIAGRAM_LOCKED");
  }

  public void releaseLock(UUID projectId, UUID diagramId, String email) {
    DiagramaClase diagram = findDiagram(projectId, diagramId);
    if (diagram.isBloqueado()) {
      diagram.desbloquear();
      diagrams.save(diagram);
      notifyCollab(diagramId, "DIAGRAM_UNLOCKED");
    }
  }

  public DiagramSummary updateDiagram(UUID projectId, UUID diagramId, String email, UpdateDiagram request) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = findDiagram(projectId, diagramId);
    requireNotLocked(diagram, email);

    String newNombre = request.nombre().trim();
    String newDesc = clean(request.descripcion());
    if (Objects.equals(diagram.getNombre(), newNombre) && Objects.equals(diagram.getDescripcion(), newDesc)) {
      return summary(diagram);
    }
    long newRev = reserveNextRevision(diagramId, request.expectedRevision());
    diagram.setRevision(newRev);

    diagram.update(newNombre, newDesc);
    notifyCollabAfterCommit(diagramId, "DIAGRAM_UPDATED", newRev, email);
    return summary(diagram);
  }

  public void deleteDiagram(UUID projectId, UUID diagramId, String email) {
    deleteDiagram(projectId, diagramId, email, null);
  }

  public void deleteDiagram(UUID projectId, UUID diagramId, String email, Long expectedRevision) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = findDiagram(projectId, diagramId);
    requireNotLocked(diagram, email);
    if (expectedRevision != null && diagram.getRevision() != expectedRevision) {
      throw new ConflictException("Conflicto de concurrencia al eliminar diagrama (revisión esperada: "
          + expectedRevision + ", actual: " + diagram.getRevision() + ").");
    }
    diagrams.delete(diagram);
    notifyCollab(diagramId, "DIAGRAM_DELETED", null);
  }

  public ElementDetail saveElement(UUID projectId, UUID diagramId, String email, SaveElement request) {
    return saveElement(projectId, diagramId, email, request,
        request.id() == null ? SaveMode.CREATE : SaveMode.UPDATE);
  }

  public ElementDetail createElement(UUID projectId, UUID diagramId, String email, SaveElement request) {
    return saveElement(projectId, diagramId, email, request, SaveMode.CREATE);
  }

  public ElementDetail updateElement(UUID projectId, UUID diagramId, String email, UUID elementId, SaveElement request) {
    if (request.id() != null && !request.id().equals(elementId)) {
      throw new BadRequestException("El identificador del cuerpo no coincide con el elemento solicitado.");
    }
    return saveElement(projectId, diagramId, email,
        new SaveElement(elementId, request.tipoElemento(), request.nombre(), request.posicionX(),
            request.posicionY(), request.propiedadesJson(), request.expectedRevision()), SaveMode.UPDATE);
  }

  private ElementDetail saveElement(UUID projectId, UUID diagramId, String email, SaveElement request, SaveMode mode) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = findDiagram(projectId, diagramId);
    requireNotLocked(diagram, email);

    ElementoUML element;
    boolean created = false;
    if (request.id() != null) {
      java.util.Optional<ElementoUML> existing = elements.findById(request.id());
      if (existing.isPresent()) {
        element = existing.get();
        if (!element.getDiagrama().getId().equals(diagramId)) {
          throw new BadRequestException("El elemento no pertenece a este diagrama.");
        }
        if (matches(element, request)) return elementDetail(element);
        if (mode == SaveMode.CREATE) {
          throw new ConflictException("El identificador del elemento ya corresponde a otro contenido.");
        }
        long newRev = reserveNextRevision(diagramId, request.expectedRevision());
        diagram.setRevision(newRev);
        element.update(
            request.tipoElemento(),
            request.nombre().trim(),
            request.posicionX(),
            request.posicionY(),
            request.propiedadesJson()
        );
      } else {
        if (mode == SaveMode.UPDATE) throw new NotFoundException("Elemento UML no encontrado.");
        created = true;
        long newRev = reserveNextRevision(diagramId, request.expectedRevision());
        diagram.setRevision(newRev);
        element = new ElementoUML(
            request.id(),
            diagram,
            request.tipoElemento(),
            request.nombre().trim(),
            request.posicionX(),
            request.posicionY(),
            request.propiedadesJson()
        );
      }
    } else {
      if (mode == SaveMode.UPDATE) throw new BadRequestException("La actualización requiere el identificador del elemento.");
      created = true;
      long newRev = reserveNextRevision(diagramId, request.expectedRevision());
      diagram.setRevision(newRev);
      element = new ElementoUML(
          diagram,
          request.tipoElemento(),
          request.nombre().trim(),
          request.posicionX(),
          request.posicionY(),
          request.propiedadesJson()
      );
    }
    ElementoUML persisted = created ? saveNewElement(element) : elements.save(element);
    ElementDetail saved = elementDetail(persisted);
    notifyCollabAfterCommit(diagramId, "ELEMENT_SAVED", diagram.getRevision(), email);
    return saved;
  }

  public void syncPositions(UUID projectId, UUID diagramId, String email, SyncPositions request) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = findDiagram(projectId, diagramId);
    requireNotLocked(diagram, email);

    if (request.positions() == null || request.positions().isEmpty()) return;
    long newRev = reserveNextRevision(diagramId, request.expectedRevision());
    diagram.setRevision(newRev);

    for (PositionUpdate pos : request.positions()) {
      elements.findById(pos.id()).ifPresent(el -> {
        if (el.getDiagrama().getId().equals(diagramId)) {
          el.updatePosition(pos.posicionX(), pos.posicionY());
          elements.save(el);
        }
      });
    }
    notifyCollabAfterCommit(diagramId, "POSITIONS_SYNCED", newRev, email);
  }

  public void deleteElement(UUID projectId, UUID diagramId, String email, UUID elementId) {
    deleteElement(projectId, diagramId, email, elementId, null);
  }

  public void deleteElement(UUID projectId, UUID diagramId, String email, UUID elementId, Long expectedRevision) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = findDiagram(projectId, diagramId);
    requireNotLocked(diagram, email);
    ElementoUML element = elements.findById(elementId)
        .orElseThrow(() -> new NotFoundException("Elemento UML no encontrado."));
    if (!element.getDiagrama().getId().equals(diagramId)) {
      throw new BadRequestException("El elemento no pertenece a este diagrama.");
    }
    long newRev = reserveNextRevision(diagramId, expectedRevision);
    diagram.setRevision(newRev);
    // Eliminar relaciones conectadas
    relations.deleteByElementoOrigenIdOrElementoDestinoId(elementId, elementId);
    elements.delete(element);
    notifyCollabAfterCommit(diagramId, "ELEMENT_DELETED", newRev, email);
  }

  public void deleteElements(UUID projectId, UUID diagramId, String email, List<UUID> elementIds, Long expectedRevision) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = findDiagram(projectId, diagramId);
    requireNotLocked(diagram, email);
    List<UUID> uniqueIds = elementIds == null ? List.of() : elementIds.stream().filter(Objects::nonNull).distinct().toList();
    if (uniqueIds.isEmpty()) throw new BadRequestException("Selecciona al menos una clase para eliminar.");
    List<ElementoUML> selected = elements.findAllById(uniqueIds);
    if (selected.size() != uniqueIds.size() || selected.stream().anyMatch(element -> !element.getDiagrama().getId().equals(diagramId))) {
      throw new BadRequestException("Una o más clases seleccionadas no pertenecen a este diagrama.");
    }
    long newRev = reserveNextRevision(diagramId, expectedRevision);
    diagram.setRevision(newRev);
    for (UUID elementId : uniqueIds) relations.deleteByElementoOrigenIdOrElementoDestinoId(elementId, elementId);
    elements.deleteAll(selected);
    notifyCollabAfterCommit(diagramId, "ELEMENTS_DELETED", newRev, email);
  }

  public RelationDetail saveRelation(UUID projectId, UUID diagramId, String email, SaveRelation request) {
    return saveRelation(projectId, diagramId, email, request,
        request.id() == null ? SaveMode.CREATE : SaveMode.UPDATE);
  }

  public RelationDetail createRelation(UUID projectId, UUID diagramId, String email, SaveRelation request) {
    return saveRelation(projectId, diagramId, email, request, SaveMode.CREATE);
  }

  public RelationDetail updateRelation(UUID projectId, UUID diagramId, String email, UUID relationId, SaveRelation request) {
    if (request.id() != null && !request.id().equals(relationId)) {
      throw new BadRequestException("El identificador del cuerpo no coincide con la relación solicitada.");
    }
    return saveRelation(projectId, diagramId, email,
        new SaveRelation(relationId, request.elementoOrigen(), request.elementoDestino(), request.nombreRelacion(),
            request.tipoRelacion(), request.multiplicidadOrigen(), request.multiplicidadDestino(), request.expectedRevision()), SaveMode.UPDATE);
  }

  public DiagramFullView createIntermediateClass(UUID projectId, UUID diagramId, String email, UUID relationId,
      CreateIntermediateClass request, Long expectedRevision) {
    long revision = expectedRevision != null ? expectedRevision : getDiagramRevision(diagramId);
    return mutateBatch(projectId, diagramId, email, revision, () -> {
      RelacionUML relation = relations.findById(relationId)
          .orElseThrow(() -> new NotFoundException("Relación UML no encontrada."));
      if (!relation.getDiagrama().getId().equals(diagramId))
        throw new BadRequestException("La relación no pertenece a este diagrama.");
      if (!isMany(relation.getMultiplicidadOrigen()) || !isMany(relation.getMultiplicidadDestino()))
        throw new BadRequestException("La clase intermedia solo puede agregarse a una relación muchos a muchos.");
      if (elements.findByDiagramaId(diagramId).stream().anyMatch(e -> e.getNombre().equalsIgnoreCase(request.nombre().trim())))
        throw new BadRequestException("Ya existe una clase con ese nombre en el diagrama.");

      var properties = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
      var attributes = properties.putArray("attributes");
      if (request.atributos() != null) for (IntermediateAttribute attribute : request.atributos()) {
        String name = attribute.nombre().trim();
        String type = attribute.tipo().trim();
        attributes.addObject().put("id", UUID.randomUUID().toString()).put("visibility", "+")
            .put("name", name).put("type", type);
      }
      properties.putArray("methods");

      ElementoUML source = relation.getElementoOrigen();
      ElementoUML target = relation.getElementoDestino();
      double x = (source.getPosicionX() + target.getPosicionX()) / 2.0;
      double y = Math.max(20, (source.getPosicionY() + target.getPosicionY()) / 2.0 - 150.0);
      ElementDetail middle = createElement(projectId, diagramId, email,
          new SaveElement(null, TipoElementoUML.CLASE, request.nombre().trim(), x, y, properties.toString(), revision));
      updateRelation(projectId, diagramId, email, relationId,
          new SaveRelation(relationId, source.getId(), middle.id(), null, TipoRelacionUML.ASOCIACION, "1", "*", revision));
      createRelation(projectId, diagramId, email,
          new SaveRelation(null, target.getId(), middle.id(), null, TipoRelacionUML.ASOCIACION, "1", "*", revision));
      return getDiagram(projectId, diagramId, email);
    });
  }

  private boolean isMany(String multiplicity) {
    if (multiplicity == null) return false;
    String value = multiplicity.trim().toLowerCase(Locale.ROOT);
    return value.equals("*") || value.equals("n") || value.equals("m") || value.matches("(?:0|1)\\.\\.(?:\\*|n|m)");
  }

  private RelationDetail saveRelation(UUID projectId, UUID diagramId, String email, SaveRelation request, SaveMode mode) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = findDiagram(projectId, diagramId);
    requireNotLocked(diagram, email);

    ElementoUML origen = elements.findById(request.elementoOrigen())
        .orElseThrow(() -> new NotFoundException("Elemento origen no encontrado."));
    ElementoUML destino = elements.findById(request.elementoDestino())
        .orElseThrow(() -> new NotFoundException("Elemento destino no encontrado."));

    if (!origen.getDiagrama().getId().equals(diagramId) || !destino.getDiagrama().getId().equals(diagramId)) {
      throw new BadRequestException("Los elementos relacionados deben pertenecer al mismo diagrama.");
    }

    RelacionUML relation;
    boolean created = false;
    if (request.id() != null) {
      java.util.Optional<RelacionUML> existing = relations.findById(request.id());
      if (existing.isPresent()) {
        relation = existing.get();
        if (!relation.getDiagrama().getId().equals(diagramId)) {
          throw new BadRequestException("La relación no pertenece a este diagrama.");
        }
        if (matches(relation, request, origen, destino)) return relationDetail(relation);
        if (mode == SaveMode.CREATE) {
          throw new ConflictException("El identificador de la relación ya corresponde a otro contenido.");
        }
        long newRev = reserveNextRevision(diagramId, request.expectedRevision());
        diagram.setRevision(newRev);
        relation.update(
            origen,
            destino,
            clean(request.nombreRelacion()),
            request.tipoRelacion(),
            clean(request.multiplicidadOrigen()),
            clean(request.multiplicidadDestino())
        );
      } else {
        if (mode == SaveMode.UPDATE) throw new NotFoundException("Relación UML no encontrada.");
        created = true;
        long newRev = reserveNextRevision(diagramId, request.expectedRevision());
        diagram.setRevision(newRev);
        relation = new RelacionUML(
            request.id(),
            diagram,
            origen,
            destino,
            clean(request.nombreRelacion()),
            request.tipoRelacion(),
            clean(request.multiplicidadOrigen()),
            clean(request.multiplicidadDestino())
        );
      }
    } else {
      if (mode == SaveMode.UPDATE) throw new BadRequestException("La actualización requiere el identificador de la relación.");
      created = true;
      long newRev = reserveNextRevision(diagramId, request.expectedRevision());
      diagram.setRevision(newRev);
      relation = new RelacionUML(
          diagram,
          origen,
          destino,
          clean(request.nombreRelacion()),
          request.tipoRelacion(),
          clean(request.multiplicidadOrigen()),
          clean(request.multiplicidadDestino())
      );
    }
    RelacionUML persisted = created ? saveNewRelation(relation) : relations.save(relation);
    RelationDetail saved = relationDetail(persisted);
    notifyCollabAfterCommit(diagramId, "RELATION_SAVED", diagram.getRevision(), email);
    return saved;
  }

  public void deleteRelation(UUID projectId, UUID diagramId, String email, UUID relationId) {
    deleteRelation(projectId, diagramId, email, relationId, null);
  }

  public void deleteRelation(UUID projectId, UUID diagramId, String email, UUID relationId, Long expectedRevision) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = findDiagram(projectId, diagramId);
    requireNotLocked(diagram, email);
    RelacionUML relation = relations.findById(relationId)
        .orElseThrow(() -> new NotFoundException("Relación UML no encontrada."));
    if (!relation.getDiagrama().getId().equals(diagramId)) {
      throw new BadRequestException("La relación no pertenece a este diagrama.");
    }
    long newRev = reserveNextRevision(diagramId, expectedRevision);
    diagram.setRevision(newRev);
    relations.delete(relation);
    notifyCollabAfterCommit(diagramId, "RELATION_DELETED", newRev, email);
  }

  private DiagramaClase findDiagram(UUID projectId, UUID diagramId) {
    DiagramaClase diagram = diagrams.findById(diagramId)
        .orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    if (!diagram.getProject().getId().equals(projectId)) {
      throw new NotFoundException("Diagrama no encontrado en este proyecto.");
    }
    return diagram;
  }

  private DiagramSummary summary(DiagramaClase d) {
    return new DiagramSummary(
        d.getId(),
        d.getProject().getId(),
        d.getNombre(),
        d.getDescripcion(),
        d.getEstado(),
        d.getFechaCreacion(),
        d.isBloqueado(),
        d.getBloqueadoPor(),
        d.getFechaBloqueo(),
        d.getRevision()
    );
  }

  private ElementDetail elementDetail(ElementoUML e) {
    return new ElementDetail(e.getId(), e.getDiagrama().getId(), e.getTipoElemento(), e.getNombre(), e.getPosicionX(), e.getPosicionY(), e.getPropiedadesJson());
  }

  private RelationDetail relationDetail(RelacionUML r) {
    return new RelationDetail(r.getId(), r.getDiagrama().getId(), r.getElementoOrigen().getId(), r.getElementoDestino().getId(), r.getNombreRelacion(), r.getTipoRelacion(), r.getMultiplicidadOrigen(), r.getMultiplicidadDestino());
  }

  private String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private ElementoUML saveNewElement(ElementoUML element) {
    try {
      return elements.saveAndFlush(element);
    } catch (DataIntegrityViolationException exception) {
      throw new ConflictException("El identificador del elemento ya está en uso. Reintenta la operación.");
    }
  }

  private RelacionUML saveNewRelation(RelacionUML relation) {
    try {
      return relations.saveAndFlush(relation);
    } catch (DataIntegrityViolationException exception) {
      throw new ConflictException("El identificador de la relación ya está en uso. Reintenta la operación.");
    }
  }

  private boolean matches(ElementoUML element, SaveElement request) {
    String properties = request.propiedadesJson();
    return element.getTipoElemento() == request.tipoElemento()
        && element.getNombre().equals(request.nombre().trim())
        && Double.compare(element.getPosicionX(), request.posicionX()) == 0
        && Double.compare(element.getPosicionY(), request.posicionY()) == 0
        && (properties == null || properties.isBlank() || properties.equals(element.getPropiedadesJson()));
  }

  private boolean matches(RelacionUML relation, SaveRelation request, ElementoUML origin, ElementoUML target) {
    return relation.getElementoOrigen().getId().equals(origin.getId())
        && relation.getElementoDestino().getId().equals(target.getId())
        && Objects.equals(relation.getNombreRelacion(), clean(request.nombreRelacion()))
        && relation.getTipoRelacion() == request.tipoRelacion()
        && Objects.equals(relation.getMultiplicidadOrigen(), clean(request.multiplicidadOrigen()))
        && Objects.equals(relation.getMultiplicidadDestino(), clean(request.multiplicidadDestino()));
  }
}
