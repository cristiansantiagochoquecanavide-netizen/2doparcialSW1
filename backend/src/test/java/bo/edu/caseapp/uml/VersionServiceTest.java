package bo.edu.caseapp.versioning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import bo.edu.caseapp.project.Project;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.error.ConflictException;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.uml.UmlResponses.*;
import bo.edu.caseapp.user.User;
import bo.edu.caseapp.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class VersionServiceTest {
  private VersionDiagramaRepository versions;
  private DiagramaClaseRepository diagrams;
  private ElementoUMLRepository elements;
  private RelacionUMLRepository relations;
  private ProjectService projects;
  private UserRepository users;
  private UmlService uml;
  private ObjectMapper mapper;
  private VersionService service;
  private UUID projectId, diagramId, versionId, elementId, relationId, actorId;
  private Project project;
  private DiagramaClase diagram;
  private User actor;
  private String email = "raul@case.com";

  @BeforeEach void setup() {
    versions = mock(VersionDiagramaRepository.class); diagrams = mock(DiagramaClaseRepository.class);
    elements = mock(ElementoUMLRepository.class); relations = mock(RelacionUMLRepository.class);
    projects = mock(ProjectService.class); users = mock(UserRepository.class); uml = mock(UmlService.class);
    mapper = new ObjectMapper().findAndRegisterModules();
    service = new VersionService(versions, diagrams, elements, relations, projects, users, uml, mapper);
    projectId = UUID.randomUUID(); diagramId = UUID.randomUUID(); versionId = UUID.randomUUID();
    elementId = UUID.randomUUID(); relationId = UUID.randomUUID(); actorId = UUID.randomUUID();
    actor = new User("Raúl", email, "hash"); ReflectionTestUtils.setField(actor, "id", actorId);
    project = new Project(actor, "Proyecto", "Descripción"); ReflectionTestUtils.setField(project, "id", projectId);
    diagram = new DiagramaClase(project, "Diagrama", "Descripción"); ReflectionTestUtils.setField(diagram, "id", diagramId);
    when(diagrams.findById(diagramId)).thenReturn(Optional.of(diagram));
    when(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(actor));
    when(elements.save(any(ElementoUML.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(relations.save(any(RelacionUML.class))).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test void restoreCreatesNewVersionAndPreservesStableElementAndRelationIds() throws Exception {
    var source = new DiagramFullView(new DiagramSummary(diagramId, projectId, "Diagrama", "Descripción", "ACTIVE", null, false, null, null, 4),
        List.of(new ElementDetail(elementId, diagramId, TipoElementoUML.CLASE, "Cliente", 120, 240, "{\"attributes\":[]}")),
        List.of(new RelationDetail(relationId, diagramId, elementId, elementId, "asocia", TipoRelacionUML.ASOCIACION, "1", "*")));
    VersionDiagrama oldVersion = new VersionDiagrama(diagram, actor, 2, "V2", mapper.writeValueAsString(source));
    ReflectionTestUtils.setField(oldVersion, "id", versionId);
    when(versions.findByIdAndDiagramaId(versionId, diagramId)).thenReturn(Optional.of(oldVersion));
    when(versions.findByDiagramaIdAndIdempotencyKey(eq(diagramId), any())).thenReturn(Optional.empty());
    when(versions.findMaxVersionNumber(diagramId)).thenReturn(4);
    when(uml.reserveNextRevision(diagramId, 9L)).thenReturn(10L);
    var restored = new DiagramFullView(new DiagramSummary(diagramId, projectId, "Diagrama", "Descripción", "ACTIVE", null, false, null, null, 10), source.elements(), source.relations());
    when(uml.getDiagram(projectId, diagramId, email)).thenReturn(restored);
    UUID key = UUID.randomUUID();

    var result = service.restoreVersion(projectId, diagramId, versionId, email, 9L, key);

    assertEquals(10, result.diagram().revision());
    ArgumentCaptor<ElementoUML> element = ArgumentCaptor.forClass(ElementoUML.class);
    verify(elements).save(element.capture()); assertEquals(elementId, element.getValue().getId());
    ArgumentCaptor<RelacionUML> relation = ArgumentCaptor.forClass(RelacionUML.class);
    verify(relations).save(relation.capture()); assertEquals(relationId, relation.getValue().getId());
    ArgumentCaptor<VersionDiagrama> savedVersion = ArgumentCaptor.forClass(VersionDiagrama.class);
    verify(versions).save(savedVersion.capture());
    assertEquals("RESTORE", savedVersion.getValue().getOperationType());
    assertEquals(oldVersion, savedVersion.getValue().getRestoredFrom());
    assertEquals(key, savedVersion.getValue().getIdempotencyKey());
    assertEquals(10, savedVersion.getValue().getSourceRevision());
    verify(uml).notifyCollabAfterCommit(diagramId, "VERSION_RESTORED", 10L);
  }

  @Test void repeatedRestoreRequestReturnsItsRecordedSnapshotWithoutApplyingAgain() throws Exception {
    UUID key = UUID.randomUUID();
    var saved = new DiagramFullView(new DiagramSummary(diagramId, projectId, "Diagrama", "Descripción", "ACTIVE", null, false, null, null, 8), List.of(), List.of());
    VersionDiagrama existing = new VersionDiagrama(diagram, actor, 5, "Restauración", mapper.writeValueAsString(saved), 8, "RESTORE", null, key);
    when(versions.findByDiagramaIdAndIdempotencyKey(diagramId, key)).thenReturn(Optional.of(existing));

    var result = service.restoreVersion(projectId, diagramId, versionId, email, 7L, key);

    assertEquals(8, result.diagram().revision());
    verify(relations, never()).deleteByDiagramaId(diagramId);
    verify(elements, never()).deleteByDiagramaId(diagramId);
    verify(versions, never()).save(any());
  }

  @Test void confirmedAcodiEditIsCapturedAsTheApprovingHuman() {
    when(diagrams.lockById(diagramId)).thenReturn(Optional.of(diagram));
    when(versions.findFirstByDiagramaIdOrderByNumeroVersionDesc(diagramId)).thenReturn(Optional.empty());
    when(versions.findMaxVersionNumber(diagramId)).thenReturn(2);
    var current = new DiagramFullView(new DiagramSummary(diagramId, projectId, "Diagrama", "Descripción", "ACTIVE", null, false, null, null, 7), List.of(), List.of());
    when(uml.getDiagram(projectId, diagramId, email)).thenReturn(current);

    service.captureAutomatic(new UmlVersionCaptureRequested(diagramId, email, 7, "ACODI"));

    ArgumentCaptor<VersionDiagrama> saved = ArgumentCaptor.forClass(VersionDiagrama.class);
    verify(versions).save(saved.capture());
    assertEquals("ACODI", saved.getValue().getOperationType());
    assertEquals("Continuación del trabajo", saved.getValue().getDescripcion());
    assertEquals(actor, saved.getValue().getUsuario());
    assertEquals(7, saved.getValue().getSourceRevision());
    assertTrue(saved.getValue().isWorking());
  }

  @Test void approvedAcodiChangesUpdateTheCurrentWorkingVersionWithoutCreatingANumber() {
    var active = new VersionDiagrama(diagram, actor, 2, "V2 manual", "before", 6, "MANUAL", null, null);
    active.setWorking(true);
    when(diagrams.lockById(diagramId)).thenReturn(Optional.of(diagram));
    when(versions.findFirstByDiagramaIdAndWorkingTrue(diagramId)).thenReturn(Optional.of(active));
    when(uml.getDiagram(projectId, diagramId, email)).thenReturn(new DiagramFullView(
        new DiagramSummary(diagramId, projectId, "Diagrama", "Descripción", "ACTIVE", null, false, null, null, 7), List.of(), List.of()));

    service.captureAutomatic(new UmlVersionCaptureRequested(diagramId, email, 7, "ACODI"));

    verify(versions).save(active);
    verify(versions, never()).save(argThat(version -> version.getNumeroVersion() != 2));
    assertEquals(2, active.getNumeroVersion());
    assertEquals(7, active.getSourceRevision());
    assertEquals("MANUAL", active.getOperationType());
    assertTrue(active.isWorking());
  }

  @Test void tenAutomaticSavesUpdateOneWorkingVersionWithoutAddingNumbers() {
    var working = new VersionDiagrama(diagram, actor, 1, "Versión inicial", "old", 1, "INITIAL", null, null);
    working.setWorking(true);
    when(diagrams.lockById(diagramId)).thenReturn(Optional.of(diagram));
    when(versions.findFirstByDiagramaIdAndWorkingTrue(diagramId)).thenReturn(Optional.of(working));
    when(uml.getDiagram(projectId, diagramId, email)).thenAnswer(invocation ->
        new DiagramFullView(new DiagramSummary(diagramId, projectId, "Diagrama", "Descripción", "ACTIVE", null, false, null, null, 1), List.of(), List.of()));

    for (int revision = 1; revision <= 10; revision++) {
      when(uml.getDiagram(projectId, diagramId, email)).thenReturn(new DiagramFullView(
          new DiagramSummary(diagramId, projectId, "Diagrama", "Descripción", "ACTIVE", null, false, null, null, revision), List.of(), List.of()));
      service.captureAutomatic(new UmlVersionCaptureRequested(diagramId, email, revision, "UML_EDIT"));
    }

    verify(versions, times(10)).save(working);
    assertEquals(1, working.getNumeroVersion());
    assertEquals(10, working.getSourceRevision());
    assertTrue(working.isWorking());
  }

  @Test void manualSaveClosesActiveVersionAndOpensNextVersionWithCurrentSnapshot() throws Exception {
    var active = new VersionDiagrama(diagram, actor, 1, "Versión inicial", "first", 1, "INITIAL", null, null);
    active.setWorking(true);
    var snapshot = new DiagramFullView(new DiagramSummary(diagramId, projectId, "Diagrama", "Descripción", "ACTIVE", null, false, null, null, 2), List.of(), List.of());
    when(diagrams.lockById(diagramId)).thenReturn(Optional.of(diagram));
    when(versions.findByDiagramaIdAndIdempotencyKey(eq(diagramId), any())).thenReturn(Optional.empty());
    when(versions.findFirstByDiagramaIdAndWorkingTrue(diagramId)).thenReturn(Optional.of(active));
    when(versions.findMaxVersionNumber(diagramId)).thenReturn(1);
    when(uml.getDiagram(projectId, diagramId, email)).thenReturn(snapshot);
    when(versions.saveAndFlush(active)).thenReturn(active);
    when(versions.save(any(VersionDiagrama.class))).thenAnswer(invocation -> invocation.getArgument(0));

    VersionResponses.VersionSummary result = service.createVersion(projectId, diagramId, email,
        new VersionRequests.CreateVersion("Guardar avance", UUID.randomUUID()));

    assertEquals(2, result.numeroVersion());
    assertFalse(active.isWorking());
    ArgumentCaptor<VersionDiagrama> saved = ArgumentCaptor.forClass(VersionDiagrama.class);
    verify(versions).save(saved.capture());
    assertEquals(2, saved.getValue().getNumeroVersion());
    assertTrue(saved.getValue().isWorking());
    assertEquals("Guardar avance", saved.getValue().getDescripcion());
  }

  @Test void staleRevisionStopsRestoreBeforeAnyModelOrHistoryWrite() throws Exception {
    var source = new DiagramFullView(new DiagramSummary(diagramId, projectId, "Diagrama", "Descripción", "ACTIVE", null, false, null, null, 4), List.of(), List.of());
    VersionDiagrama oldVersion = new VersionDiagrama(diagram, actor, 2, "V2", mapper.writeValueAsString(source));
    when(versions.findByIdAndDiagramaId(versionId, diagramId)).thenReturn(Optional.of(oldVersion));
    when(versions.findByDiagramaIdAndIdempotencyKey(eq(diagramId), any())).thenReturn(Optional.empty());
    when(uml.reserveNextRevision(diagramId, 3L)).thenThrow(new ConflictException("stale revision"));

    assertThrows(ConflictException.class, () -> service.restoreVersion(projectId, diagramId, versionId, email, 3L, UUID.randomUUID()));
    verify(relations, never()).deleteByDiagramaId(diagramId);
    verify(elements, never()).deleteByDiagramaId(diagramId);
    verify(versions, never()).save(any());
  }
}
