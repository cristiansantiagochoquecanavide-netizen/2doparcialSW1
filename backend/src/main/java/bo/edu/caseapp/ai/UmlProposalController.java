package bo.edu.caseapp.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/diagrams/{diagramId}/ai/proposals")
public class UmlProposalController {
  private final UmlProposalStore store;
  private final UmlProposalService service;
  private final UmlProposalApplication application;
  private final UmlProposalDecisions decisions;
  @org.springframework.beans.factory.annotation.Autowired private UmlCanvasProposalState canvas;
  public UmlProposalController(UmlProposalStore store,UmlProposalService service,UmlProposalApplication application,UmlProposalDecisions decisions) {
    this.store=store; this.service=service; this.application=application; this.decisions=decisions;
  }
  @GetMapping public List<UmlProposalStore.View> list(@PathVariable UUID projectId,@PathVariable UUID diagramId,
      @RequestParam(required=false) UUID conversationId,Authentication auth) { return store.list(projectId,diagramId,conversationId,auth.getName()); }
  @PostMapping public ResponseEntity<UmlProposalStore.Generation> generate(@PathVariable UUID projectId,@PathVariable UUID diagramId,
      @Valid @RequestBody UmlProposalStore.Generate request,Authentication auth) { return generation(service.generate(projectId,diagramId,auth.getName(),request)); }
  @GetMapping("/requests/{id}") public UmlProposalStore.Generation request(@PathVariable UUID projectId,@PathVariable UUID diagramId,@PathVariable UUID id,Authentication auth) {
    return store.generation(projectId,diagramId,id,auth.getName());
  }
  @GetMapping("/{id}") public UmlProposalStore.View get(@PathVariable UUID projectId,@PathVariable UUID diagramId,@PathVariable UUID id,Authentication auth) { return store.get(projectId,diagramId,id,auth.getName()); }
  @GetMapping("/canvas") public CanvasView canvas(@PathVariable UUID projectId,@PathVariable UUID diagramId,Authentication auth) {
    UUID active=canvas.active(projectId,diagramId,auth.getName());
    return new CanvasView(active==null?null:store.get(projectId,diagramId,active,auth.getName()),
        store.pendingShared(projectId,diagramId,auth.getName()));
  }
  @PostMapping("/{id}/canvas") public CanvasView select(@PathVariable UUID projectId,@PathVariable UUID diagramId,@PathVariable UUID id,Authentication auth) {
    canvas.select(projectId,diagramId,id,auth.getName());return canvas(projectId,diagramId,auth);
  }
  public record CanvasView(UmlProposalStore.View active,List<UmlProposalStore.View> pending) { }
  @PostMapping("/{id}/apply") public UmlProposalStore.View apply(@PathVariable UUID projectId,@PathVariable UUID diagramId,@PathVariable UUID id,
      @Valid @RequestBody UmlProposalExecutor.Apply request,Authentication auth) { return application.apply(projectId,diagramId,id,auth.getName(),request); }
  @PostMapping("/{id}/reject") public UmlProposalStore.View reject(@PathVariable UUID projectId,@PathVariable UUID diagramId,@PathVariable UUID id,Authentication auth) { return decisions.reject(projectId,diagramId,id,auth.getName()); }
  @PostMapping("/{id}/regenerate") public ResponseEntity<UmlProposalStore.Generation> regenerate(@PathVariable UUID projectId,@PathVariable UUID diagramId,@PathVariable UUID id,
      @Valid @RequestBody Regenerate request,Authentication auth) { return generation(service.regenerate(projectId,diagramId,id,auth.getName(),request.clientRequestId())); }
  private ResponseEntity<UmlProposalStore.Generation> generation(UmlProposalStore.Generation result) {
    return ResponseEntity.status(result.status().equals("ERROR")?422:result.status().equals("GENERATING")?202:200).body(result);
  }
  public record Regenerate(@NotNull UUID clientRequestId) { }
}
