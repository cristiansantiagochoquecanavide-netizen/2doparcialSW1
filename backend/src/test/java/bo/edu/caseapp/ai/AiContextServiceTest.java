package bo.edu.caseapp.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import bo.edu.caseapp.error.ForbiddenException;
import bo.edu.caseapp.project.Project;
import bo.edu.caseapp.project.ProjectMember;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.user.User;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.uml.validation.ValidationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiContextServiceTest {
  private ProjectService projects;
  private DiagramaClaseRepository diagrams;
  private ElementoUMLRepository elements;
  private RelacionUMLRepository relations;
  private UmlService uml;
  private AiContextService service;

  @BeforeEach
  void setUp() {
    projects = mock(ProjectService.class); diagrams = mock(DiagramaClaseRepository.class);
    elements = mock(ElementoUMLRepository.class); relations = mock(RelacionUMLRepository.class);
    uml = mock(UmlService.class);
    service = new AiContextService(projects, diagrams, elements, relations, uml, new ObjectMapper(), 12000);
  }

  @Test
  void contextChecksMembershipBeforeReadingDiagram() {
    UUID projectId = UUID.randomUUID(), diagramId = UUID.randomUUID();
    when(projects.requireAccess(projectId, "external@example.com")).thenThrow(new ForbiddenException("No access"));
    assertThrows(ForbiddenException.class, () -> service.loadAuthorizedContext("external@example.com", projectId, diagramId));
    verifyNoInteractions(diagrams, elements, relations, uml);
  }

  @Test
  void contextContainsUmlSemanticsAndLeavesOutVisualCoordinates() {
    UUID projectId = UUID.randomUUID(), diagramId = UUID.randomUUID(), userId = UUID.randomUUID();
    Project project = mock(Project.class); ProjectMember member = mock(ProjectMember.class); User user = mock(User.class);
    DiagramaClase diagram = mock(DiagramaClase.class); ElementoUML element = mock(ElementoUML.class);
    when(projects.requireAccess(projectId, "member@example.com")).thenReturn(member);
    when(member.getUser()).thenReturn(user); when(user.getId()).thenReturn(userId); when(user.getEmail()).thenReturn("member@example.com");
    when(diagrams.findById(diagramId)).thenReturn(Optional.of(diagram));
    when(diagram.getProject()).thenReturn(project); when(project.getId()).thenReturn(projectId); when(project.getName()).thenReturn("Ventas");
    when(diagram.getId()).thenReturn(diagramId); when(diagram.getNombre()).thenReturn("Principal"); when(diagram.getRevision()).thenReturn(3L);
    when(elements.findByDiagramaId(diagramId)).thenReturn(List.of(element));
    when(element.getNombre()).thenReturn("Cliente"); when(element.getTipoElemento()).thenReturn(TipoElementoUML.CLASE);
    when(element.getPropiedadesJson()).thenReturn("{\"attributes\":[{\"name\":\"correo\",\"type\":\"String\"}],\"methods\":[]}");
    when(relations.findByDiagramaId(diagramId)).thenReturn(List.of());
    when(uml.validateDiagram(projectId, diagramId, "member@example.com")).thenReturn(new ValidationReport(true, 0, 0, List.of()));

    String context = service.loadAuthorizedContext("member@example.com", projectId, diagramId);

    assertTrue(context.contains("Ventas")); assertTrue(context.contains("Cliente"));
    assertTrue(context.contains("correo")); assertTrue(context.contains("\"revision\":3"));
    assertFalse(context.contains("posicionX")); assertFalse(context.contains("posicionY"));
  }
}
