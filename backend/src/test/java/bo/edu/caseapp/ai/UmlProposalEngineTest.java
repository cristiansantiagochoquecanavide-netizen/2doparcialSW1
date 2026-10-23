package bo.edu.caseapp.ai;

import static org.junit.jupiter.api.Assertions.*;
import static bo.edu.caseapp.ai.UmlProposalContract.*;
import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.uml.UmlResponses.*;
import bo.edu.caseapp.uml.validation.UmlValidatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class UmlProposalEngineTest {
  final ObjectMapper mapper=new ObjectMapper();
  final UmlProposalEngine engine=new UmlProposalEngine(mapper,new UmlValidatorService());
  final UUID diagram=UUID.randomUUID(), existing=UUID.randomUUID(), member=UUID.randomUUID();
  DiagramFullView base() {
    return new DiagramFullView(new DiagramSummary(diagram,UUID.randomUUID(),"Ventas","", "ACTIVO",Instant.now()),
        List.of(new ElementDetail(existing,diagram,TipoElementoUML.CLASE,"Venta",12.0,20.0,
            "{\"attributes\":[{\"id\":\""+member+"\",\"name\":\"precio\",\"type\":\"Double\",\"visibility\":\"-\"}],\"methods\":[]}")),List.of());
  }
  Preview compile(String operations) { return engine.compile(base(),engine.parse("{\"description\":\"Cambio de prueba\",\"operations\":"+operations+"}")); }
  @Test void projectsClassMemberAndRelationWithBackendAssignedIdsAndRoundTripsExactPreview() throws Exception {
    var preview=compile("""
        [{"type":"CREATE_CLASS","ref":"new:pago","name":"Pago"},
        {"type":"ADD_ATTRIBUTE","target":"new:pago","name":"monto","dataType":"BigDecimal"},
        {"type":"ADD_METHOD","target":"new:pago","name":"registrar","dataType":"Boolean","parameters":"monto: BigDecimal","visibility":"+"},
        {"type":"CREATE_RELATION","ref":"new:r","source":"%s","destination":"new:pago","relationType":"ASOCIACION","sourceMultiplicity":"1","targetMultiplicity":"0..*"}]
        """.formatted(existing));
    assertEquals(2,preview.changes().size()); assertFalse(preview.destructive());
    assertEquals(1,preview.changes().getFirst().after().path("properties").path("attributes").size());
    assertEquals("registrar",preview.changes().getFirst().after().path("properties").path("methods").get(0).path("name").asText());
    var persisted=mapper.readValue(mapper.writeValueAsString(preview),Preview.class);
    assertDoesNotThrow(()->engine.verify(base(),persisted)); assertEquals(1,base().elements().size());
  }
  @Test void updatesAnActualMemberWithoutLosingItsIdOrPosition() {
    var preview=compile("[{\"type\":\"UPDATE_ATTRIBUTE\",\"target\":\""+existing+"\",\"memberId\":\""+member+"\",\"dataType\":\"BigDecimal\"}]");
    var change=preview.changes().getFirst(); assertEquals(12.0,change.after().path("x").asDouble());
    assertEquals(member.toString(),change.after().path("properties").path("attributes").get(0).path("id").asText());
    assertEquals("BigDecimal",change.after().path("properties").path("attributes").get(0).path("type").asText());
  }
  @Test void memberDeletionIsDestructiveEvenThoughClassRemains() {
    var preview=compile("[{\"type\":\"DELETE_ATTRIBUTE\",\"target\":\""+existing+"\",\"memberId\":\""+member+"\"}]");
    assertTrue(preview.destructive()); assertEquals(ChangeKind.UPDATE,preview.changes().getFirst().kind());
  }
  @Test void rejectsMalformedUnknownFieldsUnsupportedOperationsAndTrailingJson() {
    for(String json:List.of("{", "{\"description\":\"x\",\"operations\":[{\"type\":\"EXECUTE_SQL\"}]}",
        "{\"description\":\"x\",\"operations\":[],\"sql\":\"DROP TABLE\"}",
        "{\"description\":\"x\",\"clarification\":\"¿Cuál?\",\"operations\":[]} {}"))
      assertThrows(BadRequestException.class,()->engine.parse(json));
  }
  @Test void rejectsForeignClassAndInventedMemberIds() {
    assertThrows(BadRequestException.class,()->compile("[{\"type\":\"DELETE_CLASS\",\"target\":\""+UUID.randomUUID()+"\"}]"));
    assertThrows(BadRequestException.class,()->compile("[{\"type\":\"DELETE_ATTRIBUTE\",\"target\":\""+existing+"\",\"memberId\":\"inventado\"}]"));
  }
  @Test void rejectsInvalidMultiplicitiesAndUndeclaredTemporaryReferences() {
    for(String multiplicity:List.of("2..1","SQL","999999999999999999999")) assertThrows(BadRequestException.class,()->compile(
        "[{\"type\":\"CREATE_RELATION\",\"ref\":\"new:r\",\"source\":\""+existing+"\",\"destination\":\""+existing+"\",\"relationType\":\"ASOCIACION\",\"sourceMultiplicity\":\"1\",\"targetMultiplicity\":\""+multiplicity+"\"}]"));
    assertThrows(BadRequestException.class,()->compile("[{\"type\":\"ADD_METHOD\",\"target\":\"new:missing\",\"name\":\"pagar\",\"dataType\":\"void\"}]"));
  }
  @Test void clarificationsNeverContainExecutableChanges() {
    var plan=engine.parse("{\"description\":\"Aclarar destino\",\"clarification\":\"¿Qué clase?\",\"operations\":[]}");
    assertTrue(engine.compile(base(),plan).changes().isEmpty());
    assertThrows(BadRequestException.class,()->engine.parse("{\"description\":\"x\",\"clarification\":\"¿Cuál?\",\"operations\":[{\"type\":\"DELETE_CLASS\"}]}"));
  }
  @Test void duplicateNamesAndNoopUpdatesAreRejectedByLocalValidation() {
    assertThrows(BadRequestException.class,()->compile("[{\"type\":\"CREATE_CLASS\",\"ref\":\"new:v\",\"name\":\"Venta\"}]"));
    assertThrows(BadRequestException.class,()->compile("[{\"type\":\"UPDATE_CLASS\",\"target\":\""+existing+"\",\"name\":\"Venta\"}]"));
  }
  @Test void refusesOversizedPlansAndMoreThan32Operations() {
    assertThrows(BadRequestException.class,()->engine.parse("x".repeat(65537)));
    assertThrows(BadRequestException.class,()->engine.parse("{\"description\":\"x\",\"operations\":["+String.join(",",Collections.nCopies(33,"{\"type\":\"CREATE_CLASS\"}"))+"]}"));
  }
  @Test void conservativeIntentRoutingNeverTreatsExplanationOrApprovalAsApplication() {
    assertEquals(UmlProposalIntent.Intent.PREPARE,UmlProposalIntent.classify("IA, agrega una clase Pago"));
    assertEquals(UmlProposalIntent.Intent.PREPARE,UmlProposalIntent.classify("Modifica el atributo precio"));
    assertEquals(UmlProposalIntent.Intent.DISCUSS,UmlProposalIntent.classify("¿Cómo puedo agregar una clase?"));
    assertEquals(UmlProposalIntent.Intent.DISCUSS,UmlProposalIntent.classify("Estamos diseñando un hospital"));
    assertEquals(UmlProposalIntent.Intent.DECIDE,UmlProposalIntent.classify("Sí, aplica la propuesta"));
    assertEquals(UmlProposalIntent.Intent.DECIDE,UmlProposalIntent.classify("dale"));
    for(String text:List.of("@ACODI ¿Puedes eliminar la relación entre Venta y Producto?","Hola @ACODI, quiero que elimines la relación detalle","Me podrías quitar la asociación detalle","Por favor borra el método pagar de Venta","Necesito agregar el atributo metodoPago a Venta","Quiero crear una relación"))
      assertEquals(UmlProposalIntent.Intent.PREPARE,UmlProposalIntent.classify(text),text);
    assertEquals(UmlProposalIntent.Intent.DISCUSS,UmlProposalIntent.classify("No elimines la relación entre Venta y Producto"));
  }
  @Test void updatesAndDeletesMethodsUsingStableIds() throws Exception {
    UUID method=UUID.randomUUID();
    var current=base(); var classView=current.elements().getFirst();
    var props=mapper.readTree(classView.propiedadesJson());
    ((com.fasterxml.jackson.databind.node.ArrayNode)props.path("methods")).addObject().put("id",method.toString()).put("name","pagar").put("returnType","void").put("parameters","");
    var withMethod=new DiagramFullView(current.diagram(),List.of(new ElementDetail(existing,diagram,TipoElementoUML.CLASE,"Venta",12.0,20.0,props.toString())),List.of());
    var plan=engine.parse("{\"description\":\"Modificar método\",\"operations\":[{\"type\":\"UPDATE_METHOD\",\"target\":\""+existing+"\",\"memberId\":\""+method+"\",\"dataType\":\"Boolean\",\"parameters\":\"monto: BigDecimal\"}]}");
    var updated=engine.compile(withMethod,plan); assertEquals("Boolean",updated.changes().getFirst().after().path("properties").path("methods").get(0).path("returnType").asText());
    var deleted=engine.compile(withMethod,engine.parse("{\"description\":\"Eliminar método\",\"operations\":[{\"type\":\"DELETE_METHOD\",\"target\":\""+existing+"\",\"memberId\":\""+method+"\"}]}"));
    assertTrue(deleted.destructive());
  }
  @Test void updatesClassRelationAndMultiplicityAndShowsCascadedDeletes() {
    UUID related=UUID.randomUUID(),relation=UUID.randomUUID(); var current=base();
    var withRelation=new DiagramFullView(current.diagram(),List.of(current.elements().getFirst(),new ElementDetail(related,diagram,TipoElementoUML.CLASE,"Pago",100,100,"{}")),
        List.of(new RelationDetail(relation,diagram,existing,related,"pagos",TipoRelacionUML.ASOCIACION,"1","1")));
    var plan=engine.parse("""
        {"description":"Revisar venta","operations":[{"type":"UPDATE_CLASS","target":"%s","name":"VentaConfirmada"},
        {"type":"UPDATE_RELATION","target":"%s","name":"pagosConfirmados"},
        {"type":"UPDATE_MULTIPLICITY","target":"%s","sourceMultiplicity":"1","targetMultiplicity":"0..*"}]}
        """.formatted(existing,relation,relation));
    var preview=engine.compile(withRelation,plan); assertEquals(2,preview.changes().size());
    assertEquals("0..*",preview.changes().get(1).after().path("targetMultiplicity").asText()); assertDoesNotThrow(()->engine.verify(withRelation,preview));
    var deleted=engine.compile(withRelation,engine.parse("{\"description\":\"Eliminar clase y sus relaciones\",\"operations\":[{\"type\":\"DELETE_CLASS\",\"target\":\""+related+"\"}]}"));
    assertTrue(deleted.destructive()); assertEquals(2,deleted.changes().size()); assertTrue(deleted.changes().stream().allMatch(c->c.kind()==ChangeKind.DELETE));
    var onlyRelation=engine.compile(withRelation,engine.parse("{\"description\":\"Eliminar relación\",\"operations\":[{\"type\":\"DELETE_RELATION\",\"target\":\""+relation+"\"}]}"));
    assertEquals(EntityKind.RELATION,onlyRelation.changes().getFirst().entity()); assertTrue(onlyRelation.destructive());
  }
  @Test void rejectsDuplicateJsonKeysAndNumericEnums() {
    assertThrows(BadRequestException.class,()->engine.parse("{\"description\":\"x\",\"description\":\"y\",\"clarification\":\"¿Cuál?\",\"operations\":[]}"));
    assertThrows(BadRequestException.class,()->engine.parse("{\"description\":\"x\",\"operations\":[{\"type\":0,\"ref\":\"new:x\",\"name\":\"X\"}]}"));
  }
}
