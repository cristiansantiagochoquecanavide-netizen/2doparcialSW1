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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
  @org.springframework.beans.factory.annotation.Autowired private bo.edu.caseapp.voice.ProjectVoiceWebSocketHandler voice;

  public ProjectChatController(ProjectChatService chat) {
    this.chat = chat;
  }

  @GetMapping("/channels")
  public List<Channel> channels(@PathVariable UUID projectId, Authentication authentication) {
    return chat.listChannels(projectId, authentication.getName());
  }

  @GetMapping("/voice-rooms")
  public List<Channel> voiceRooms(@PathVariable UUID projectId, Authentication auth) {
    return chat.voiceRooms(projectId, auth.getName());
  }

  @GetMapping("/voice-rooms/{roomId}/participants")
  public bo.edu.caseapp.voice.VoiceRoomPresenceChanged voiceParticipants(@PathVariable UUID projectId,
      @PathVariable UUID roomId, Authentication auth) {
    return voice.presence(projectId, roomId, auth.getName());
  }

  @PostMapping({"/channels", "/voice-rooms"})
  public ResponseEntity<Channel> createChannel(@PathVariable UUID projectId, Authentication auth,
      @Valid @RequestBody ChatRequests.ChannelName request, jakarta.servlet.http.HttpServletRequest http) {
    return ResponseEntity.status(HttpStatus.CREATED).body(chat.createChannel(projectId, auth.getName(), channelType(http), request));
  }

  @PatchMapping({"/channels/{channelId}", "/voice-rooms/{channelId}"})
  public Channel renameChannel(@PathVariable UUID projectId, @PathVariable UUID channelId, Authentication auth,
      @Valid @RequestBody ChatRequests.ChannelName request, jakarta.servlet.http.HttpServletRequest http) {
    return chat.renameChannel(projectId, channelId, auth.getName(), channelType(http), request);
  }

  @DeleteMapping({"/channels/{channelId}", "/voice-rooms/{channelId}"})
  public ResponseEntity<Void> deleteChannel(@PathVariable UUID projectId, @PathVariable UUID channelId,
      Authentication auth, @RequestParam(defaultValue = "false") boolean confirmDisconnect,
      jakarta.servlet.http.HttpServletRequest http) {
    chat.deleteChannel(projectId, channelId, auth.getName(), channelType(http), confirmDisconnect);
    return ResponseEntity.noContent().build();
  }

  private String channelType(jakarta.servlet.http.HttpServletRequest request) {
    return request.getRequestURI().contains("/chat/voice-rooms") ? "VOICE" : "TEXT";
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
