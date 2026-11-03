package bo.edu.caseapp.versioning;

import bo.edu.caseapp.uml.UmlResponses.DiagramFullView;
import bo.edu.caseapp.versioning.VersionRequests.CreateVersion;
import bo.edu.caseapp.versioning.VersionResponses.VersionSummary;
import bo.edu.caseapp.versioning.VersionResponses.VersionDetail;
import bo.edu.caseapp.versioning.VersionResponses.ActivityItem;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/diagrams/{diagramId}/versions")
public class VersionController {
  private final VersionService versionService;

  public VersionController(VersionService versionService) {
    this.versionService = versionService;
  }

  @GetMapping
  public Page<VersionSummary> list(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      Pageable pageable
  ) {
    Pageable safe = PageRequest.of(Math.max(0, pageable.getPageNumber()), Math.min(100, Math.max(1, pageable.getPageSize())),
        Sort.by(Sort.Direction.DESC, "numeroVersion"));
    return versionService.listVersions(projectId, diagramId, auth.getName(), safe);
  }

  @GetMapping("/{versionId}")
  public VersionDetail get(@PathVariable UUID projectId, @PathVariable UUID diagramId,
      @PathVariable UUID versionId, Authentication auth) {
    return versionService.getVersion(projectId, diagramId, versionId, auth.getName());
  }

  @GetMapping("/at/{number}")
  public VersionDetail getAtNumber(@PathVariable UUID projectId, @PathVariable UUID diagramId,
      @PathVariable int number, Authentication auth) {
    return versionService.getVersionAtNumber(projectId, diagramId, number, auth.getName());
  }

  @GetMapping("/activity")
  public List<ActivityItem> activity(@PathVariable UUID projectId, @PathVariable UUID diagramId,
      Authentication auth) {
    return versionService.activity(projectId, diagramId, auth.getName());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public VersionSummary create(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
      @Valid @RequestBody(required = false) CreateVersion request
  ) {
    CreateVersion body = request != null ? request : new CreateVersion(null);
    CreateVersion req = new CreateVersion(body.descripcion(), idempotencyKey != null ? idempotencyKey : body.idempotencyKey());
    return versionService.createVersion(projectId, diagramId, auth.getName(), req);
  }

  @PostMapping("/{versionId}/restore")
  public DiagramFullView restore(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      @PathVariable UUID versionId,
      Authentication auth,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      jakarta.servlet.http.HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch);
    DiagramFullView view = versionService.restoreVersion(projectId, diagramId, versionId, auth.getName(), expectedRev, idempotencyKey);
    if (view.diagram() != null) {
      response.setHeader("X-Diagram-Revision", String.valueOf(view.diagram().revision()));
    }
    return view;
  }

  private Long requireExpectedRevision(Long headerRevision, String ifMatch) {
    if (headerRevision != null) return headerRevision;
    if (ifMatch != null && !ifMatch.isBlank()) {
      try {
        return Long.parseLong(ifMatch.replace("\"", "").trim());
      } catch (NumberFormatException ignored) {}
    }
    throw new bo.edu.caseapp.error.BadRequestException(
        "Se requiere la revisión esperada del diagrama ('X-Diagram-Revision' o 'If-Match') para verificar la concurrencia optimista.");
  }
}
