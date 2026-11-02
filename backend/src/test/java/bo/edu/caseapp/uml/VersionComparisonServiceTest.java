package bo.edu.caseapp.versioning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import bo.edu.caseapp.project.Project;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.DiagramaClase;
import bo.edu.caseapp.uml.DiagramaClaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class VersionComparisonServiceTest {
  ProjectService projects;
  DiagramaClaseRepository diagrams;
  VersionDiagramaRepository versions;
  VersionComparisonService service;
  UUID projectId=UUID.randomUUID(), diagramId=UUID.randomUUID(), v2=UUID.randomUUID(), v5=UUID.randomUUID();
  DiagramaClase diagram;

  @BeforeEach void setup() {
    projects=mock(ProjectService.class);diagrams=mock(DiagramaClaseRepository.class);versions=mock(VersionDiagramaRepository.class);
    var project=mock(Project.class);when(project.getId()).thenReturn(projectId);
    diagram=mock(DiagramaClase.class);when(diagram.getProject()).thenReturn(project);
    when(diagrams.findById(diagramId)).thenReturn(Optional.of(diagram));
    service=new VersionComparisonService(projects,diagrams,versions,new ObjectMapper());
  }

  @Test void missingSelectionReturnsClarificationWithoutReadingSnapshots() {
    var result=service.resolve(projectId,diagramId,"ana@test","Compara las versiones seleccionadas",null);
    assertTrue(result.requested());assertNull(result.context());assertTrue(result.clarification().contains("Selecciona dos versiones"));
    verifyNoInteractions(versions);
  }

  @Test void explicitNumbersResolveRealVersionsAndProduceDeterministicUmlDiff() {
    var old=version(v2,2,"""
        {"elements":[{"id":"a","nombre":"Venta","tipoElemento":"CLASE"}],"relations":[]}
        """);
    var current=version(v5,5,"""
        {"elements":[{"id":"a","nombre":"Venta","tipoElemento":"CLASE","propiedadesJson":"{\\\"attributes\\\":[{\\\"name\\\":\\\"monto\\\"}]}"},{"id":"b","nombre":"Pago","tipoElemento":"CLASE"}],"relations":[]}
        """);
    when(versions.findByDiagramaIdAndNumeroVersion(diagramId,2)).thenReturn(Optional.of(old));
    when(versions.findByDiagramaIdAndNumeroVersion(diagramId,5)).thenReturn(Optional.of(current));
    var result=service.resolve(projectId,diagramId,"ana@test","@ACODI compara V2 con V5",null);
    assertTrue(result.requested());assertNull(result.clarification());
    assertTrue(result.context().contains("v2 y v5"));assertTrue(result.context().contains("CLASE Pago: agregada"));
    assertTrue(result.context().contains("CLASE Venta: modificada"));assertTrue(result.context().contains("monto"));
  }

  @Test void selectedIdsMustBothBelongToAuthorizedDiagram() {
    when(versions.findByIdAndDiagramaId(v2,diagramId)).thenReturn(Optional.of(version(v2,2,"{\"elements\":[],\"relations\":[]}")));
    var result=service.resolve(projectId,diagramId,"ana@test","compara las seleccionadas",List.of(v2,UUID.randomUUID()));
    assertTrue(result.clarification().contains("No encontré"));assertNull(result.context());
  }

  @Test void foreignDiagramIsRejectedBeforeLookingUpVersions() {
    when(diagrams.findById(diagramId)).thenReturn(Optional.empty());
    var result=service.resolve(projectId,diagramId,"ana@test","compara V2 con V5",null);
    assertTrue(result.clarification().contains("No pude validar"));verifyNoInteractions(versions);
  }

  private VersionDiagrama version(UUID id,int number,String snapshot) {
    var version=new VersionDiagrama(diagram,null,number,"V"+number,snapshot,number,"MANUAL",null,null);
    ReflectionTestUtils.setField(version,"id",id);return version;
  }
}
