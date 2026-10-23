package bo.edu.caseapp.uml;

import bo.edu.caseapp.uml.UmlRequests.*;
import bo.edu.caseapp.uml.UmlResponses.*;
import bo.edu.caseapp.uml.relational.RelationalSchema;
import bo.edu.caseapp.uml.relational.SqlDdlParserService;
import bo.edu.caseapp.uml.validation.ValidationReport;
import bo.edu.caseapp.error.BadRequestException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/diagrams")
public class UmlController {
  private final UmlService service;
  private final SqlDdlParserService sqlDdlParserService;

  public UmlController(UmlService service, SqlDdlParserService sqlDdlParserService) {
    this.service = service;
    this.sqlDdlParserService = sqlDdlParserService;
  }

  public record ImportSqlRequest(String sql) {}

  @PostMapping("/{diagramId}/import/sql")
  @ResponseStatus(HttpStatus.OK)
  public DiagramFullView importSql(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @RequestBody ImportSqlRequest request,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch, null);
    DiagramFullView view = sqlDdlParserService.parseAndImportSql(projectId, diagramId, auth.getName(), request.sql(), expectedRev);
    response.setHeader("X-Diagram-Revision", String.valueOf(service.getDiagramRevision(diagramId)));
    return view;
  }

  @GetMapping
  public List<DiagramSummary> list(@PathVariable UUID projectId, Authentication auth) {
    return service.listDiagrams(projectId, auth.getName());
  }

  @GetMapping("/default")
  public DiagramSummary getOrCreateDefault(@PathVariable UUID projectId, Authentication auth) {
    return service.getOrCreateDefaultDiagram(projectId, auth.getName());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DiagramSummary create(
      @PathVariable UUID projectId,
      Authentication auth,
      @Valid @RequestBody CreateDiagram request
  ) {
    return service.createDiagram(projectId, auth.getName(), request);
  }

  @GetMapping("/{diagramId}")
  public DiagramFullView get(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth
  ) {
    return service.getDiagram(projectId, diagramId, auth.getName());
  }

  @GetMapping("/{diagramId}/validate")
  public ValidationReport validate(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth
  ) {
    return service.validateDiagram(projectId, diagramId, auth.getName());
  }

  @GetMapping("/{diagramId}/relational")
  public RelationalSchema getRelational(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth
  ) {
    return service.generateRelationalSchema(projectId, diagramId, auth.getName());
  }

  private Long resolveExpectedRevision(Long headerRevision, String ifMatch, Long bodyRevision) {
    if (headerRevision != null) return headerRevision;
    if (ifMatch != null && !ifMatch.isBlank()) {
      String clean = ifMatch.replace("\"", "").trim();
      try {
        return Long.parseLong(clean);
      } catch (NumberFormatException ignored) {}
    }
    return bodyRevision;
  }

  private Long requireExpectedRevision(Long headerRevision, String ifMatch, Long bodyRevision) {
    Long rev = resolveExpectedRevision(headerRevision, ifMatch, bodyRevision);
    if (rev == null) {
      throw new BadRequestException("Se requiere la revisión esperada del diagrama ('X-Diagram-Revision' o 'expectedRevision') para verificar la concurrencia optimista.");
    }
    return rev;
  }

  @PutMapping("/{diagramId}")
  public DiagramSummary update(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @Valid @RequestBody UpdateDiagram request,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch, request.expectedRevision());
    UpdateDiagram resolved = new UpdateDiagram(request.nombre(), request.descripcion(), expectedRev);
    DiagramSummary summary = service.updateDiagram(projectId, diagramId, auth.getName(), resolved);
    response.setHeader("X-Diagram-Revision", String.valueOf(summary.revision()));
    return summary;
  }

  @DeleteMapping("/{diagramId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch, null);
    service.deleteDiagram(projectId, diagramId, auth.getName(), expectedRev);
  }

  @PostMapping("/{diagramId}/elements")
  @ResponseStatus(HttpStatus.OK)
  public ElementDetail saveElement(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @Valid @RequestBody SaveElement request,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch, request.expectedRevision());
    SaveElement resolved = new SaveElement(request.id(), request.tipoElemento(), request.nombre(),
        request.posicionX(), request.posicionY(), request.propiedadesJson(), expectedRev);
    ElementDetail detail = service.createElement(projectId, diagramId, auth.getName(), resolved);
    response.setHeader("X-Diagram-Revision", String.valueOf(service.getDiagramRevision(diagramId)));
    return detail;
  }

  @PutMapping("/{diagramId}/elements/{elementId}")
  public ElementDetail updateElement(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      @PathVariable UUID elementId,
      Authentication auth,
      @Valid @RequestBody SaveElement request,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch, request.expectedRevision());
    SaveElement resolved = new SaveElement(request.id() != null ? request.id() : elementId,
        request.tipoElemento(), request.nombre(), request.posicionX(), request.posicionY(),
        request.propiedadesJson(), expectedRev);
    ElementDetail detail = service.updateElement(projectId, diagramId, auth.getName(), elementId, resolved);
    response.setHeader("X-Diagram-Revision", String.valueOf(service.getDiagramRevision(diagramId)));
    return detail;
  }

  @PostMapping("/{diagramId}/elements/positions")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void syncPositions(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @Valid @RequestBody SyncPositions request,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch, request.expectedRevision());
    SyncPositions resolved = new SyncPositions(request.positions(), expectedRev);
    service.syncPositions(projectId, diagramId, auth.getName(), resolved);
    response.setHeader("X-Diagram-Revision", String.valueOf(service.getDiagramRevision(diagramId)));
  }

  @DeleteMapping("/{diagramId}/elements/{elementId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteElement(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      @PathVariable UUID elementId,
      Authentication auth,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch, null);
    service.deleteElement(projectId, diagramId, auth.getName(), elementId, expectedRev);
    response.setHeader("X-Diagram-Revision", String.valueOf(service.getDiagramRevision(diagramId)));
  }

  @DeleteMapping("/{diagramId}/elements")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteElements(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @Valid @RequestBody DeleteElementsRequest request,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch, null);
    service.deleteElements(projectId, diagramId, auth.getName(), request.elementIds(), expectedRev);
    response.setHeader("X-Diagram-Revision", String.valueOf(service.getDiagramRevision(diagramId)));
  }

  @PostMapping("/{diagramId}/relations")
  @ResponseStatus(HttpStatus.OK)
  public RelationDetail saveRelation(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @Valid @RequestBody SaveRelation request,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch, request.expectedRevision());
    SaveRelation resolved = new SaveRelation(request.id(), request.elementoOrigen(), request.elementoDestino(),
        request.nombreRelacion(), request.tipoRelacion(), request.multiplicidadOrigen(), request.multiplicidadDestino(), expectedRev);
    RelationDetail detail = service.createRelation(projectId, diagramId, auth.getName(), resolved);
    response.setHeader("X-Diagram-Revision", String.valueOf(service.getDiagramRevision(diagramId)));
    return detail;
  }

  @PostMapping("/{diagramId}/relations/{relationId}/intermediate-class")
  public DiagramFullView createIntermediateClass(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      @PathVariable UUID relationId,
      Authentication auth,
      @Valid @RequestBody CreateIntermediateClass request,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch, null);
    DiagramFullView view = service.createIntermediateClass(projectId, diagramId, auth.getName(), relationId, request, expectedRev);
    response.setHeader("X-Diagram-Revision", String.valueOf(view.diagram().revision()));
    return view;
  }

  @PutMapping("/{diagramId}/relations/{relationId}")
  public RelationDetail updateRelation(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      @PathVariable UUID relationId,
      Authentication auth,
      @Valid @RequestBody SaveRelation request,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch, request.expectedRevision());
    SaveRelation resolved = new SaveRelation(request.id() != null ? request.id() : relationId,
        request.elementoOrigen(), request.elementoDestino(), request.nombreRelacion(),
        request.tipoRelacion(), request.multiplicidadOrigen(), request.multiplicidadDestino(), expectedRev);
    RelationDetail detail = service.updateRelation(projectId, diagramId, auth.getName(), relationId, resolved);
    response.setHeader("X-Diagram-Revision", String.valueOf(service.getDiagramRevision(diagramId)));
    return detail;
  }

  @DeleteMapping("/{diagramId}/relations/{relationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteRelation(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      @PathVariable UUID relationId,
      Authentication auth,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch, null);
    service.deleteRelation(projectId, diagramId, auth.getName(), relationId, expectedRev);
    response.setHeader("X-Diagram-Revision", String.valueOf(service.getDiagramRevision(diagramId)));
  }
}
