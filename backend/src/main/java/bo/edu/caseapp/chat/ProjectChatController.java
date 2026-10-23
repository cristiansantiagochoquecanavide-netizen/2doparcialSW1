package bo.edu.caseapp.chat;

import static bo.edu.caseapp.chat.ChatResponses.*;

import bo.edu.caseapp.chat.ChatRequests.SendMessage;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/chat")
public class ProjectChatController {
  private final ProjectChatService chat;
  @org.springframework.beans.factory.annotation.Autowired private AcodiStore acodi;

  public ProjectChatController(ProjectChatService chat) {
    this.chat = chat;
  }

  @GetMapping("/channels")
  public List<Channel> channels(@PathVariable UUID projectId, Authentication authentication) {
    return chat.listChannels(projectId, authentication.getName());
  }

  @GetMapping("/channels/{channelId}/messages")
  public MessagePage history(
      @PathVariable UUID projectId,
      @PathVariable UUID channelId,
      Authentication authentication,
      @RequestParam(required = false) UUID before,
      @RequestParam(defaultValue = "50") int limit
  ) {
    return chat.history(projectId, channelId, authentication.getName(), before, limit);
  }

  @PostMapping("/channels/{channelId}/messages")
  public ResponseEntity<Message> send(
      @PathVariable UUID projectId,
      @PathVariable UUID channelId,
      Authentication authentication,
      @Valid @RequestBody SendMessage request
  ) {
    SendMessageResult result = chat.send(projectId, channelId, authentication.getName(), request);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.message());
  }
  @PostMapping("/channels/{channelId}/messages/{messageId}/acodi/retry")
  public Invocation retry(@PathVariable UUID projectId,@PathVariable UUID channelId,@PathVariable UUID messageId,
      Authentication authentication,@Valid @RequestBody AcodiStore.Retry request) {
    return acodi.retry(projectId,channelId,messageId,authentication.getName(),request);
  }
}
