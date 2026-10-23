package bo.edu.caseapp.ai;

import static org.junit.jupiter.api.Assertions.*;

import bo.edu.caseapp.ai.AiRequests.*;
import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.uml.TipoRelacionUML;
import java.util.List;
import org.junit.jupiter.api.Test;

class UmlImageModelValidatorTest {
  @Test
  void parsesClassWithAttributesAndSpanishIdentifiers() {
    ImageModel model = validate("""
        {"classes":[{"name":"Artículo","type":"CLASE","confidence":0.98,"x":160,"y":120,"attributes":[{"name":"precio","type":"Decimal","visibility":"-","confidence":0.94}],"methods":[]}],"relations":[],"warnings":[]}
        """);
    assertEquals("Artículo", model.classes().getFirst().name());
    assertEquals("precio", model.classes().getFirst().attributes().getFirst().name());
    assertEquals(160.0, model.classes().getFirst().x());
  }

  @Test
  void parsesSeveralClassesAndAssociation() {
    ImageModel model = validate("""
        {"classes":[{"name":"Cliente","type":"CLASE","confidence":0.9,"x":100,"y":100,"attributes":[],"methods":[]},{"name":"Pedido","type":"CLASE","confidence":0.9,"x":500,"y":100,"attributes":[],"methods":[]}],"relations":[{"source":"Cliente","target":"Pedido","type":"ASOCIACION","name":"realiza","multiplicitySource":"1","multiplicityTarget":"*","confidence":0.91}],"warnings":[]}
        """);
    assertEquals(2, model.classes().size());
    assertEquals(1, model.relations().size());
  }

  @Test
  void parsesMultipleRelationshipKindsWithValidatedEndpoints() {
    ImageModel model = validate("""
        {"classes":[{"name":"A","type":"CLASE","confidence":0.9,"x":100,"y":100,"attributes":[],"methods":[]},{"name":"B","type":"CLASE","confidence":0.9,"x":400,"y":100,"attributes":[],"methods":[]},{"name":"C","type":"CLASE","confidence":0.9,"x":700,"y":100,"attributes":[],"methods":[]},{"name":"D","type":"CLASE_ABSTRACTA","confidence":0.9,"x":1000,"y":100,"attributes":[],"methods":[]}],"relations":[{"source":"A","target":"B","type":"COMPOSICION","name":null,"multiplicitySource":"1","multiplicityTarget":"1..N","confidence":0.9},{"source":"B","target":"C","type":"AGREGACION","name":null,"multiplicitySource":"1","multiplicityTarget":"*","confidence":0.9},{"source":"C","target":"D","type":"DEPENDENCIA","name":null,"multiplicitySource":null,"multiplicityTarget":null,"confidence":0.9},{"source":"A","target":"D","type":"HERENCIA","name":null,"multiplicitySource":null,"multiplicityTarget":null,"confidence":0.9}],"warnings":[]}
        """);
    assertEquals(4, model.relations().size());
    assertTrue(model.relations().stream().anyMatch(r -> r.type() == TipoRelacionUML.COMPOSICION));
    assertTrue(model.relations().stream().anyMatch(r -> r.type() == TipoRelacionUML.AGREGACION));
    assertTrue(model.relations().stream().anyMatch(r -> r.type() == TipoRelacionUML.DEPENDENCIA));
    assertEquals("1..*", model.relations().getFirst().multiplicityTarget());
  }

  @Test
  void preservesInheritanceAndAbstractTypes() {
    ImageModel model = validate("""
        {"classes":[{"name":"Animal","type":"CLASE_ABSTRACTA","confidence":0.9,"x":100,"y":100,"attributes":[],"methods":[]},{"name":"Perro","type":"CLASE","confidence":0.9,"x":100,"y":400,"attributes":[],"methods":[]}],"relations":[{"source":"Perro","target":"Animal","type":"HERENCIA","name":null,"multiplicitySource":null,"multiplicityTarget":null,"confidence":0.9}],"warnings":[]}
        """);
    assertEquals("CLASE_ABSTRACTA", model.classes().getFirst().type().name());
    assertEquals("HERENCIA", model.relations().getFirst().type().name());
  }

  @Test
  void preservesMultiplicitiesAtTheirEndpoints() {
    ImageModel model = validate("""
        {"classes":[{"name":"Cliente","type":"CLASE","confidence":0.9,"x":100,"y":100,"attributes":[],"methods":[]},{"name":"Pedido","type":"CLASE","confidence":0.9,"x":500,"y":100,"attributes":[],"methods":[]}],"relations":[{"source":"Cliente","target":"Pedido","type":"ASOCIACION","name":null,"multiplicitySource":"1","multiplicityTarget":"*","confidence":0.9}],"warnings":[]}
        """);
    assertEquals("1", model.relations().getFirst().multiplicitySource());
    assertEquals("*", model.relations().getFirst().multiplicityTarget());
  }

  @Test
  void recognizesDetailClassAsIntermediateWhenVisionReturnsItAsSeparateEdge() {
    ImageModel model = validate("""
        {"classes":[
          {"name":"Venta","type":"CLASE","confidence":0.95,"x":100,"y":100,"attributes":[],"methods":[]},
          {"name":"Producto","type":"CLASE","confidence":0.95,"x":500,"y":100,"attributes":[],"methods":[]},
          {"name":"Detalle_Venta","type":"CLASE","confidence":0.95,"x":300,"y":250,"attributes":[{"name":"cantidad","type":"Integer","visibility":"-","confidence":0.9}],"methods":[]}
        ],"relations":[
          {"source":"Venta","target":"Producto","type":"ASOCIACION","name":null,"multiplicitySource":"0..*","multiplicityTarget":"0..*","confidence":0.9},
          {"source":"Detalle_Venta","target":"Venta","type":"ASOCIACION","name":null,"multiplicitySource":"1","multiplicityTarget":"*","confidence":0.9}
        ],"warnings":[]}
        """);

    assertEquals(1, model.relations().size());
    assertEquals("Detalle_Venta", model.relations().getFirst().associationClass());
    assertEquals("cantidad", model.classes().get(2).attributes().getFirst().name());
    assertTrue(model.warnings().stream().anyMatch(w -> w.contains("clase intermedia")));
  }

  @Test
  void repairsWrongMultiplicityWhenVisionIdentifiesAnAssociationClass() {
    ImageModel model = validate("""
        {"classes":[
          {"name":"Venta","type":"CLASE","confidence":0.95,"x":100,"y":100,"attributes":[],"methods":[]},
          {"name":"Producto","type":"CLASE","confidence":0.95,"x":500,"y":100,"attributes":[],"methods":[]},
          {"name":"Detalle_Venta","type":"CLASE","confidence":0.95,"x":300,"y":250,"attributes":[],"methods":[]}
        ],"relations":[
          {"source":"Venta","target":"Producto","type":"ASOCIACION","name":null,"multiplicitySource":"1","multiplicityTarget":"0..*","confidence":0.9,"associationClass":"Detalle_Venta"}
        ],"warnings":[]}
        """);

    ImageRelation relation = model.relations().getFirst();
    assertEquals("0..*", relation.multiplicitySource());
    assertEquals("0..*", relation.multiplicityTarget());
    assertEquals("Detalle_Venta", relation.associationClass());
    assertTrue(model.warnings().stream().anyMatch(w -> w.contains("Se corrigió")));
  }

  @Test
  void expandsDetectedImageCoordinatesWhilePreservingClassOrder() {
    ImageModel model = validate("""
        {"classes":[
          {"name":"A","type":"CLASE","confidence":0.95,"x":10,"y":20,"attributes":[],"methods":[]},
          {"name":"B","type":"CLASE","confidence":0.95,"x":110,"y":120,"attributes":[],"methods":[]},
          {"name":"C","type":"CLASE","confidence":0.95,"x":210,"y":220,"attributes":[],"methods":[]}
        ],"relations":[],"warnings":[]}
        """);

    assertEquals(80.0, model.classes().getFirst().x());
    assertEquals(1000.0, model.classes().getLast().x());
    assertTrue(model.classes().getFirst().y() < model.classes().getLast().y());
    assertTrue(model.classes().getFirst().x() < model.classes().get(1).x());
  }

  @Test
  void preservesMethodsAndParameters() {
    ImageModel model = validate("""
        {"classes":[{"name":"Pedido","type":"CLASE","confidence":0.9,"x":100,"y":100,"attributes":[],"methods":[{"name":"buscar","returnType":"Pedido","parameters":"id: Long, activo: Boolean","visibility":"+","confidence":0.88}]}],"relations":[],"warnings":[]}
        """);
    ImageMethod method = model.classes().getFirst().methods().getFirst();
    assertEquals("buscar", method.name());
    assertEquals("id: Long, activo: Boolean", method.parameters());
  }

  @Test
  void omitsLowConfidenceElementsAndReturnsWarning() {
    ImageModel model = validate("""
        {"classes":[{"name":"Ilegible","type":"CLASE","confidence":0.2,"x":null,"y":null,"attributes":[],"methods":[]},{"name":"Cliente","type":"CLASE","confidence":0.95,"x":null,"y":null,"attributes":[],"methods":[]}],"relations":[],"warnings":["Hay una zona parcialmente oculta."]}
        """);
    assertEquals(1, model.classes().size());
    assertTrue(model.warnings().stream().anyMatch(w -> w.contains("baja confianza")));
    assertTrue(model.warnings().stream().anyMatch(w -> w.contains("parcialmente oculta")));
  }

  @Test
  void rejectsMalformedOrUnstructuredResponsesBeforeApply() {
    assertThrows(BadRequestException.class, () -> validate("```json {\"classes\":[]} ```"));
    assertThrows(BadRequestException.class, () -> validate("{\"classes\":[{\"name\":\"A\"}],\"relations\":[],\"warnings\":[]}"));
    assertThrows(BadRequestException.class, () -> validate("{\"classes\":[],\"relations\":[],\"warnings\":[]} extra"));
    assertThrows(BadRequestException.class, () -> validate("{\"classes\":[{\"name\":\"A\",\"type\":\"CLASE\",\"x\":null,\"y\":null,\"attributes\":[],\"methods\":[]}],\"relations\":[],\"warnings\":[]}"));
  }

  @Test
  void warnsAndSkipsRelationsWithUnknownEndpoints() {
    ImageModel model = validate("""
        {"classes":[{"name":"Cliente","type":"CLASE","confidence":0.95,"x":100,"y":100,"attributes":[],"methods":[]}],"relations":[{"source":"Cliente","target":"Pedido","type":"ASOCIACION","name":null,"multiplicitySource":"1","multiplicityTarget":"*","confidence":0.9}],"warnings":[]}
        """);
    assertTrue(model.relations().isEmpty());
    assertTrue(model.warnings().stream().anyMatch(w -> w.contains("no existe")));
  }

  private static ImageModel validate(String json) { return UmlImageModelValidator.parseAndValidate(json); }
}
