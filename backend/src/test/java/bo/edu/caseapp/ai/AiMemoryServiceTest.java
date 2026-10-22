package bo.edu.caseapp.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import bo.edu.caseapp.ai.AiMemoryRequests.SaveEntry;
import bo.edu.caseapp.ai.AiMemoryRequests.UpdateProjectMemory;
import bo.edu.caseapp.error.ForbiddenException;
import bo.edu.caseapp.project.Project;
import bo.edu.caseapp.project.ProjectMember;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.DiagramaClaseRepository;
import bo.edu.caseapp.user.User;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AiMemoryServiceTest {
  private ProjectService projects;
  private ProjectAiMemoryRepository memories;
  private ProjectAiMemoryEntryRepository entries;
  private DiagramaClaseRepository diagrams;
  private JdbcTemplate jdbc;
  private AiMemoryService service;

  @BeforeEach
  void setUp() {
    projects = mock(ProjectService.class);
    memories = mock(ProjectAiMemoryRepository.class);
    entries = mock(ProjectAiMemoryEntryRepository.class);
    diagrams = mock(DiagramaClaseRepository.class);
    jdbc = mock(JdbcTemplate.class);
    service = new AiMemoryService(projects, memories, entries, diagrams, jdbc);
  }

  @Test
  void getRequiresProjectMembershipBeforeReadingMemory() {
    UUID projectId = UUID.randomUUID();
    when(projects.requireAccess(projectId, "outsider@example.com"))
        .thenThrow(new ForbiddenException("No access"));

    assertThrows(ForbiddenException.class, () -> service.get(projectId, null, "outsider@example.com"));

    verifyNoInteractions(memories, entries, diagrams, jdbc);
  }

  @Test
  void onlyEditableProjectMembersCanChangeConfirmedMemory() {
    UUID projectId = UUID.randomUUID();
    when(projects.requireCanEdit(projectId, "reader@example.com"))
        .thenThrow(new ForbiddenException("Reader cannot edit"));

    assertThrows(ForbiddenException.class, () -> service.updateProjectMemory(projectId, "reader@example.com",
        new UpdateProjectMemory("Sistema", null, null, null, null)));
    assertThrows(ForbiddenException.class, () -> service.createConfirmedEntry(projectId, "reader@example.com",
        new SaveEntry(AiMemoryEntryType.BUSINESS_RULE, null, "Regla confirmada")));

    verifyNoInteractions(memories, entries, diagrams, jdbc);
  }

  @Test
  void createConfirmedEntryUsesAuthenticatedMemberAndMarksConfirmed() {
    UUID projectId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Project project = mock(Project.class);
    User user = mock(User.class);
    ProjectMember member = mock(ProjectMember.class);
    when(projects.requireCanEdit(projectId, "editor@example.com")).thenReturn(member);
    when(member.getProject()).thenReturn(project);
    when(member.getUser()).thenReturn(user);
    when(project.getId()).thenReturn(projectId);
    when(user.getId()).thenReturn(userId);
    when(user.getName()).thenReturn("Editor");

    when(entries.save(any(ProjectAiMemoryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
    var response = service.createConfirmedEntry(projectId, "editor@example.com",
        new SaveEntry(AiMemoryEntryType.BUSINESS_RULE, null, "  Cada pedido tiene cliente  "));

    assertEquals(AiMemoryConfidence.CONFIRMED, response.confidenceScope());
    assertEquals("Cada pedido tiene cliente", response.content());
    assertEquals(userId, response.createdBy());
    assertEquals("Editor", response.updatedByName());
    assertNotNull(response.createdAt());
    assertNotNull(response.updatedAt());
  }
}
