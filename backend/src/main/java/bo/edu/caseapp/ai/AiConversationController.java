package bo.edu.caseapp.ai;

import bo.edu.caseapp.ai.AiConversationRequests.Create;
import bo.edu.caseapp.ai.AiConversationRequests.Rename;
import bo.edu.caseapp.ai.AiConversationRequests.Send;
import bo.edu.caseapp.ai.AiConversationResponses.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/ai/conversations")
public class AiConversationController {
  private final AiConversationService conversations;
  public AiConversationController(AiConversationService conversations) { this.conversations = conversations; }

  @GetMapping
  public List<Conversation> list(@PathVariable UUID projectId, Authentication auth,
      @RequestParam(defaultValue = "false") boolean includeArchived) {
    return conversations.list(projectId, auth.getName(), includeArchived);
  }

  @PostMapping
  public ResponseEntity<Conversation> create(@PathVariable UUID projectId, Authentication auth,
      @Valid @RequestBody Create request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(conversations.create(projectId, auth.getName(), request));
  }

  @GetMapping("/{conversationId}")
  public Conversation get(@PathVariable UUID projectId, @PathVariable UUID conversationId, Authentication auth) {
    return conversations.get(projectId, conversationId, auth.getName());
  }

  @PatchMapping("/{conversationId}")
  public Conversation rename(@PathVariable UUID projectId, @PathVariable UUID conversationId,
      Authentication auth, @Valid @RequestBody Rename request) {
    return conversations.rename(projectId, conversationId, auth.getName(), request);
  }

  @PostMapping("/{conversationId}/archive")
  public Conversation archive(@PathVariable UUID projectId, @PathVariable UUID conversationId, Authentication auth) {
    return conversations.archive(projectId, conversationId, auth.getName());
  }

  @DeleteMapping("/{conversationId}")
  public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID conversationId, Authentication auth) {
    conversations.delete(projectId, conversationId, auth.getName());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{conversationId}/messages")
  public MessagePage history(@PathVariable UUID projectId, @PathVariable UUID conversationId,
      Authentication auth, @RequestParam(required = false) UUID before,
      @RequestParam(defaultValue = "50") int limit) {
    return conversations.history(projectId, conversationId, auth.getName(), before, limit);
  }

  @PostMapping("/{conversationId}/messages")
  public Turn send(@PathVariable UUID projectId, @PathVariable UUID conversationId,
      Authentication auth, @Valid @RequestBody Send request) {
    return conversations.send(projectId, conversationId, auth.getName(), request);
  }

  @PostMapping("/{conversationId}/messages/{messageId}/retry")
  public Turn retry(@PathVariable UUID projectId, @PathVariable UUID conversationId,
      @PathVariable UUID messageId, Authentication auth) {
    return conversations.retry(projectId, conversationId, messageId, auth.getName());
  }
}
