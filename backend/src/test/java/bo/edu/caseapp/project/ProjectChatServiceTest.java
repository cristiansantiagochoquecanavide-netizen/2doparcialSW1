package bo.edu.caseapp.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.error.ConflictException;
import bo.edu.caseapp.error.ForbiddenException;
import bo.edu.caseapp.project.Project;
import bo.edu.caseapp.project.ProjectMember;
import bo.edu.caseapp.project.ProjectRole;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;

class ProjectChatServiceTest {
  private ProjectChatChannelRepository channels;
  private ProjectChatMessageRepository messages;
  private ProjectService projects;
  private JdbcTemplate jdbc;
  private ApplicationEventPublisher events;
  private ProjectChatService service;
  private UUID projectId;
  private UUID channelId;
  private User user;
  private Project project;
  private ProjectMember membership;
  private ProjectChatChannel channel;

  @BeforeEach
  void setUp() {
    channels = mock(ProjectChatChannelRepository.class);
    messages = mock(ProjectChatMessageRepository.class);
    projects = mock(ProjectService.class);
    jdbc = mock(JdbcTemplate.class);
    events = mock(ApplicationEventPublisher.class);
    service = new ProjectChatService(channels, messages, projects, jdbc, events);
    projectId = UUID.randomUUID();
    channelId = UUID.randomUUID();
    user = new User("Ana", "ana@example.com", "hash");
    ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    project = new Project(user, "Proyecto", null);
    ReflectionTestUtils.setField(project, "id", projectId);
    membership = new ProjectMember(project, user, ProjectRole.EDITOR);
    channel = new ProjectChatChannel(project, "general", "general", "General", (short) 0);
    ReflectionTestUtils.setField(channel, "id", channelId);
  }

  @Test
  void seedsAndListsProjectChannelsOnlyAfterMembershipCheck() {
    when(projects.requireAccess(projectId, user.getEmail())).thenReturn(membership);
    when(channels.findByProjectIdOrderBySortOrderAscNameAsc(projectId)).thenReturn(List.of(channel));

    var result = service.listChannels(projectId, user.getEmail());

    assertEquals(1, result.size());
    assertEquals(channelId, result.get(0).id());
    verify(channels).ensureDefaultChannels(projectId);
  }

  @Test
  void outsiderCannotTriggerChannelSeedingOrReadChannels() {
    when(projects.requireAccess(projectId, "outsider@example.com"))
        .thenThrow(new ForbiddenException("No tienes acceso a este proyecto."));

    assertThrows(ForbiddenException.class, () -> service.listChannels(projectId, "outsider@example.com"));
    verifyNoInteractions(channels);
  }

  @Test
  void historyHasStableCursorAndChronologicalResponseOrder() {
    when(projects.requireAccess(projectId, user.getEmail())).thenReturn(membership);
    when(channels.findByIdAndProjectId(channelId, projectId)).thenReturn(Optional.of(channel));
    ProjectChatMessage newest = message(UUID.randomUUID(), "newest", Instant.parse("2026-01-01T00:00:02Z"));
    ProjectChatMessage middle = message(UUID.randomUUID(), "middle", Instant.parse("2026-01-01T00:00:01Z"));
    when(messages.findByChannelIdOrderByCreatedAtDescIdDesc(eq(channelId), any(PageRequest.class)))
        .thenReturn(List.of(newest, middle));

    var page = service.history(projectId, channelId, user.getEmail(), null, 1);

    assertEquals(List.of("newest"), page.messages().stream().map(ChatResponses.Message::content).toList());
    assertTrue(page.hasMore());
    assertEquals(newest.getId(), page.nextCursor());
    verify(messages).findByChannelIdOrderByCreatedAtDescIdDesc(channelId, PageRequest.of(0, 2));
  }

  @Test
  void rejectsCursorAndReplyFromAnotherChannel() {
    when(projects.requireAccess(projectId, user.getEmail())).thenReturn(membership);
    when(channels.findByIdAndProjectId(channelId, projectId)).thenReturn(Optional.of(channel));
    UUID unknownCursor = UUID.randomUUID();
    when(messages.findByIdAndChannelId(unknownCursor, channelId)).thenReturn(Optional.empty());
    assertThrows(BadRequestException.class, () -> service.history(projectId, channelId, user.getEmail(), unknownCursor, 50));

    when(messages.findByIdAndChannelId(unknownCursor, channelId)).thenReturn(Optional.empty());
    when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<UUID>>any(), any(Object[].class))).thenReturn(null);
    assertThrows(BadRequestException.class, () -> service.send(
        projectId, channelId, user.getEmail(), new ChatRequests.SendMessage(UUID.randomUUID(), "hello", unknownCursor)));
    verifyNoInteractions(events);
  }

  @Test
  void duplicateRequestReturnsExistingMessageWithoutPublishingAnotherEvent() {
    when(projects.requireAccess(projectId, user.getEmail())).thenReturn(membership);
    when(channels.findByIdAndProjectId(channelId, projectId)).thenReturn(Optional.of(channel));
    UUID clientId = UUID.randomUUID();
    ProjectChatMessage saved = message(UUID.randomUUID(), "same content", Instant.parse("2026-01-01T00:00:00Z"));
    when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<UUID>>any(), any(Object[].class))).thenReturn(null);
    when(messages.findByChannelIdAndSenderIdAndClientMessageId(channelId, user.getId(), clientId)).thenReturn(Optional.of(saved));

    var result = service.send(projectId, channelId, user.getEmail(), new ChatRequests.SendMessage(clientId, " same content ", null));

    assertFalse(result.created());
    assertEquals(saved.getId(), result.message().id());
    verifyNoInteractions(events);
  }

  @Test
  void idempotencyKeyCannotBeReusedForDifferentContent() {
    when(projects.requireAccess(projectId, user.getEmail())).thenReturn(membership);
    when(channels.findByIdAndProjectId(channelId, projectId)).thenReturn(Optional.of(channel));
    UUID clientId = UUID.randomUUID();
    ProjectChatMessage saved = message(UUID.randomUUID(), "first", Instant.parse("2026-01-01T00:00:00Z"));
    when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<UUID>>any(), any(Object[].class))).thenReturn(null);
    when(messages.findByChannelIdAndSenderIdAndClientMessageId(channelId, user.getId(), clientId)).thenReturn(Optional.of(saved));

    assertThrows(ConflictException.class, () -> service.send(
        projectId, channelId, user.getEmail(), new ChatRequests.SendMessage(clientId, "different", null)));
  }

  private ProjectChatMessage message(UUID id, String content, Instant at) {
    ProjectChatMessage value = new ProjectChatMessage(channel, user, UUID.randomUUID(), "EDITOR", ChatAuthorType.USER, content, at, null);
    ReflectionTestUtils.setField(value, "id", id);
    return value;
  }
}
