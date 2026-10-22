package bo.edu.caseapp.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import bo.edu.caseapp.uml.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiSemanticSnapshotTest {
  private final AiSemanticSnapshot service = new AiSemanticSnapshot(mock(DiagramaClaseRepository.class),
      mock(ElementoUMLRepository.class), mock(RelacionUMLRepository.class), mock(ProjectAiMemoryRepository.class),
      mock(ProjectAiMemoryEntryRepository.class), new ObjectMapper());
  private ElementoUML element(UUID id, double x, String properties) {
    return new ElementoUML(id, mock(DiagramaClase.class), TipoElementoUML.CLASE, "Cliente", x, 0, properties);
  }
  @Test void positionsColorsAndJsonKeyOrderDoNotChangeSemanticFingerprint() {
    UUID id = UUID.randomUUID();
    var before = element(id, 1, "{\"color\":\"red\",\"attributes\":[{\"name\":\"id\",\"type\":\"Long\"}],\"methods\":[]}");
    var after = element(id, 500, "{\"methods\":[],\"color\":\"blue\",\"attributes\":[{\"type\":\"Long\",\"name\":\"id\"}]}");
    assertEquals(service.model("Modelo", "", List.of(before), List.of()), service.model("Modelo", "", List.of(after), List.of()));
  }
  @Test void memberOrderDoesNotChangeFingerprintButChangingTypeDoes() {
    UUID id = UUID.randomUUID();
    var before = element(id, 1, "{\"attributes\":[{\"name\":\"a\",\"type\":\"Long\"},{\"name\":\"b\",\"type\":\"String\"}]}");
    var ordered = element(id, 1, "{\"attributes\":[{\"name\":\"b\",\"type\":\"String\"},{\"name\":\"a\",\"type\":\"Long\"}]}");
    var changed = element(id, 1, "{\"attributes\":[{\"name\":\"b\",\"type\":\"String\"},{\"name\":\"a\",\"type\":\"UUID\"}]}");
    assertEquals(service.model("M", "", List.of(before), List.of()), service.model("M", "", List.of(ordered), List.of()));
    assertNotEquals(service.model("M", "", List.of(before), List.of()), service.model("M", "", List.of(changed), List.of()));
  }
  @Test void multiplicityChangesAreSemantic() {
    var source = element(UUID.randomUUID(), 0, "{}");
    var target = element(UUID.randomUUID(), 0, "{}");
    var link = new RelacionUML(UUID.randomUUID(), source.getDiagrama(), source, target, "compras", TipoRelacionUML.ASOCIACION, "1", "*");
    String before = service.model("M", "", List.of(source,target), List.of(link)).toString();
    link.update("compras", TipoRelacionUML.ASOCIACION, "1", "1");
    assertNotEquals(before, service.model("M", "", List.of(source,target), List.of(link)).toString());
  }
  @Test void equivalentModelsWithNewlyImportedUuidsHaveTheSameFingerprint() {
    var before=service.model("Modelo","",List.of(element(UUID.randomUUID(),0,"{}")),List.of());
    var after=service.model("Modelo","",List.of(element(UUID.randomUUID(),700,"{}")),List.of());
    assertEquals(AiSemanticSnapshot.fingerprint(before),AiSemanticSnapshot.fingerprint(after));
  }
  @Test void evidenceUsesMoreThanClassCountAndNeverRecommendsForEmptyModel() {
    assertFalse(snapshot(0,0,0,true).hasEvidence());
    assertFalse(snapshot(2,0,0,false).hasEvidence());
    assertTrue(snapshot(2,1,0,false).hasEvidence());
    assertTrue(snapshot(1,0,0,true).hasEvidence());
  }
  private AiSemanticSnapshot.Snapshot snapshot(int classes, int relations, int members, boolean purpose) {
    return new AiSemanticSnapshot.Snapshot(UUID.randomUUID(), UUID.randomUUID(), 1,"M", "hash",
        new ObjectMapper().createObjectNode(),classes,relations,members,purpose);
  }
}
