package bo.edu.caseapp.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.project.ProjectMember;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.DiagramaClaseRepository;
import bo.edu.caseapp.user.User;
import bo.edu.caseapp.user.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

class AiConversationStoreTest {
  private ProjectService projects;
  private AiConversationRepository conversations;
  private AiConversationStore store;
  private UUID projectId, conversationId, callerId;
  private ProjectMember member;
  private AiConversation conversation;

  @BeforeEach void setup() {
    projects = mock(ProjectService.class); conversations = mock(AiConversationRepository.class);
    projectId = UUID.randomUUID(); conversationId = UUID.randomUUID(); callerId = UUID.randomUUID();
    member = mock(ProjectMember.class); User caller = mock(User.class); when(caller.getId()).thenReturn(callerId);
    when(member.getUser()).thenReturn(caller);
    when(projects.requireAccess(projectId, "caller@example.com")).thenReturn(member);
    conversation = mock(AiConversation.class);
    when(conversation.getScope()).thenReturn(AiConversationScope.PRIVATE);
    User owner = mock(User.class); when(owner.getId()).thenReturn(UUID.randomUUID()); when(conversation.getOwner()).thenReturn(owner);
    when(conversations.findByIdAndProject_Id(conversationId, projectId)).thenReturn(Optional.of(conversation));
    store = new AiConversationStore(projects, conversations, mock(AiConversationMessageRepository.class),
        mock(DiagramaClaseRepository.class), mock(UserRepository.class), mock(JdbcTemplate.class),
        mock(ApplicationEventPublisher.class));
  }

  @Test void projectOwnerAndOtherMembersCannotReadSomeonesPrivateConversation() {
    assertThrows(NotFoundException.class, () -> store.get(projectId, conversationId, "caller@example.com"));
    verify(conversations).findByIdAndProject_Id(conversationId, projectId);
  }

  @Test void usersWhoAreNotProjectMembersCannotOpenConversations() {
    when(projects.requireAccess(projectId, "outsider@example.com"))
        .thenThrow(new NotFoundException("Proyecto no encontrado."));
    assertThrows(NotFoundException.class, () -> store.get(projectId, conversationId, "outsider@example.com"));
    verify(conversations, never()).findByIdAndProject_Id(any(), any());
  }
}
