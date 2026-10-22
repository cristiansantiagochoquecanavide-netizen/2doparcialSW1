package bo.edu.caseapp.ai;

import bo.edu.caseapp.ai.AiMemoryRequests.SaveEntry;
import bo.edu.caseapp.ai.AiMemoryRequests.UpdateProjectMemory;
import bo.edu.caseapp.ai.AiMemoryResponses.Entry;
import bo.edu.caseapp.ai.AiMemoryResponses.MemoryView;
import bo.edu.caseapp.ai.AiMemoryResponses.ProjectMemory;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/ai/memory")
public class AiMemoryController {
  private final AiMemoryService memory;

  public AiMemoryController(AiMemoryService memory) { this.memory = memory; }

  @GetMapping
  public MemoryView get(@PathVariable UUID projectId, @RequestParam(required = false) UUID diagramId,
      Authentication authentication) {
    return memory.get(projectId, diagramId, authentication.getName());
  }

  @PutMapping
  public ProjectMemory updateProjectMemory(@PathVariable UUID projectId, Authentication authentication,
      @Valid @RequestBody UpdateProjectMemory request) {
    return memory.updateProjectMemory(projectId, authentication.getName(), request);
  }

  @PostMapping("/entries")
  public Entry createEntry(@PathVariable UUID projectId, Authentication authentication,
      @Valid @RequestBody SaveEntry request) {
    return memory.createConfirmedEntry(projectId, authentication.getName(), request);
  }

  @PutMapping("/entries/{entryId}")
  public Entry updateEntry(@PathVariable UUID projectId, @PathVariable UUID entryId,
      Authentication authentication, @Valid @RequestBody SaveEntry request) {
    return memory.updateAsConfirmed(projectId, entryId, authentication.getName(), request);
  }

  @DeleteMapping("/entries/{entryId}")
  public void deleteEntry(@PathVariable UUID projectId, @PathVariable UUID entryId, Authentication authentication) {
    memory.deleteEntry(projectId, entryId, authentication.getName());
  }
}
