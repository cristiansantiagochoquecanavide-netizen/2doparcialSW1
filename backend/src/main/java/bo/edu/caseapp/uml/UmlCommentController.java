package bo.edu.caseapp.uml;

import bo.edu.caseapp.uml.UmlCommentRequests.Create;
import bo.edu.caseapp.uml.UmlCommentRequests.Status;
import bo.edu.caseapp.uml.UmlCommentRequests.Update;
import bo.edu.caseapp.uml.UmlCommentResponses.CommentView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/diagrams/{diagramId}/comments")
public class UmlCommentController {
  private final UmlCommentService service;
  public UmlCommentController(UmlCommentService service) { this.service = service; }
  @GetMapping public List<CommentView> list(@PathVariable UUID projectId, @PathVariable UUID diagramId, Authentication auth) { return service.list(projectId, diagramId, auth.getName()); }
  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  public CommentView create(@PathVariable UUID projectId, @PathVariable UUID diagramId, Authentication auth, @Valid @RequestBody Create request) { return service.create(projectId, diagramId, auth.getName(), request); }
  @PatchMapping("/{commentId}/status")
  public CommentView status(@PathVariable UUID projectId, @PathVariable UUID diagramId, @PathVariable UUID commentId, Authentication auth, @Valid @RequestBody Status request) { return service.updateStatus(projectId, diagramId, commentId, auth.getName(), request.status()); }
  @PatchMapping("/{commentId}")
  public CommentView update(@PathVariable UUID projectId, @PathVariable UUID diagramId, @PathVariable UUID commentId, Authentication auth, @RequestBody Update request) { return service.update(projectId, diagramId, commentId, auth.getName(), request); }
  @DeleteMapping("/{commentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID projectId, @PathVariable UUID diagramId, @PathVariable UUID commentId, Authentication auth) { service.delete(projectId, diagramId, commentId, auth.getName()); }
}
