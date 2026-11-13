package bo.edu.caseapp.interop;

import bo.edu.caseapp.project.Project;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.uml.UmlResponses.DiagramFullView;
import bo.edu.caseapp.uml.collab.UmlWebSocketHandler;
import bo.edu.caseapp.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class XmiServiceTest {

  @Mock private DiagramaClaseRepository diagramRepo;
  @Mock private ElementoUMLRepository elementRepo;
  @Mock private RelacionUMLRepository relationRepo;
  @Mock private ProjectService projectService;
  @Mock private UmlService umlService;
  @Mock private UmlWebSocketHandler wsHandler;

  @InjectMocks
  private XmiService service;

  private Project project;
  private DiagramaClase diagram;
  private User owner;

  private ElementoUML clienteEl;
  private ElementoUML pedidoEl;
  private ElementoUML productoEl;
  private ElementoUML detalleEl;

  private RelacionUML relClientePedido;
  private RelacionUML relPedidoDetalle;
  private RelacionUML relProductoDetalle;

  @BeforeEach
  void setUp() {
    owner = new User("tester@case.bo", "Tester", "pass");
    ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());

    project = new Project(owner, "Ventas Project", "Desc");
    ReflectionTestUtils.setField(project, "id", UUID.randomUUID());

    diagram = new DiagramaClase(project, "Diagrama de Ventas", "Ventas UML");
    ReflectionTestUtils.setField(diagram, "id", UUID.randomUUID());

    // 4 Clases del Requerimiento 10:
    // Cliente, Pedido, Producto, DetallePedido
    clienteEl = new ElementoUML(
        diagram,
        TipoElementoUML.CLASE,
        "Cliente",
        100.0,
        100.0,
        """
        {"attributes":[{"id":"c1","visibility":"+","name":"id","type":"Long"},{"id":"c2","visibility":"+","name":"nombre","type":"String"}],"methods":[]}
        """
    );
    ReflectionTestUtils.setField(clienteEl, "id", UUID.fromString("11111111-1111-1111-1111-111111111111"));

    pedidoEl = new ElementoUML(
        diagram,
        TipoElementoUML.CLASE,
        "Pedido",
        350.0,
        100.0,
        """
        {"attributes":[{"id":"p1","visibility":"+","name":"id","type":"Long"},{"id":"p2","visibility":"+","name":"fecha","type":"DateTime"}],"methods":[]}
        """
    );
    ReflectionTestUtils.setField(pedidoEl, "id", UUID.fromString("22222222-2222-2222-2222-222222222222"));

    productoEl = new ElementoUML(
        diagram,
        TipoElementoUML.CLASE,
        "Producto",
        100.0,
        350.0,
        """
        {"attributes":[{"id":"pr1","visibility":"+","name":"id","type":"Long"},{"id":"pr2","visibility":"+","name":"precio","type":"Decimal"}],"methods":[]}
        """
    );
    ReflectionTestUtils.setField(productoEl, "id", UUID.fromString("33333333-3333-3333-3333-333333333333"));

    detalleEl = new ElementoUML(
        diagram,
        TipoElementoUML.CLASE,
        "DetallePedido",
        350.0,
        350.0,
        """
        {"attributes":[{"id":"d1","visibility":"+","name":"cantidad","type":"Integer"},{"id":"d2","visibility":"+","name":"subtotal","type":"Decimal"}],"methods":[]}
        """
    );
    ReflectionTestUtils.setField(detalleEl, "id", UUID.fromString("44444444-4444-4444-4444-444444444444"));

    // 3 Relaciones especificadas en el Requerimiento 10:
    // 1. Cliente 1 -------- 0..* Pedido
    relClientePedido = new RelacionUML(
        diagram,
        clienteEl,
        pedidoEl,
        "cliente_pedidos",
        TipoRelacionUML.ASOCIACION,
        "1",
        "0..*"
    );
    ReflectionTestUtils.setField(relClientePedido, "id", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    // 2. Pedido 1 -------- 1..* DetallePedido
    relPedidoDetalle = new RelacionUML(
        diagram,
        pedidoEl,
        detalleEl,
        "pedido_detalles",
        TipoRelacionUML.COMPOSICION,
        "1",
        "1..*"
    );
    ReflectionTestUtils.setField(relPedidoDetalle, "id", UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

    // 3. Producto 1 -------- 0..* DetallePedido
    relProductoDetalle = new RelacionUML(
        diagram,
        productoEl,
        detalleEl,
        "producto_detalles",
        TipoRelacionUML.ASOCIACION,
        "1",
        "0..*"
    );
    ReflectionTestUtils.setField(relProductoDetalle, "id", UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
  }

  @Test
  void exportAndImportXmi_roundTripPreservesClassesAttributesAndCardinalities() {
    // Preparar estado del diagrama origen
    when(diagramRepo.findById(diagram.getId())).thenReturn(Optional.of(diagram));
    when(elementRepo.findByDiagramaId(diagram.getId())).thenReturn(List.of(clienteEl, pedidoEl, productoEl, detalleEl));
    when(relationRepo.findByDiagramaId(diagram.getId())).thenReturn(List.of(relClientePedido, relPedidoDetalle, relProductoDetalle));

    // 1. Exportar XMI
    String exportedXmi = service.exportXmi(project.getId(), diagram.getId(), "tester@case.bo");

    assertThat(exportedXmi).isNotBlank();
    assertThat(exportedXmi).contains("xmi:version=\"2.1\"");
    assertThat(exportedXmi).contains("http://schema.omg.org/spec/UML/2.1");
    assertThat(exportedXmi).contains("encoding=\"UTF-8\"");
    assertThat(exportedXmi).contains("name=\"Cliente\"");
    assertThat(exportedXmi).contains("name=\"Pedido\"");
    assertThat(exportedXmi).contains("name=\"Producto\"");
    assertThat(exportedXmi).contains("name=\"DetallePedido\"");

    // Verificar que las cardinalidades están presentes en el XMI
    assertThat(exportedXmi).contains("multiplicity=\"1\"");
    assertThat(exportedXmi).contains("multiplicity=\"0..*\"");
    assertThat(exportedXmi).contains("multiplicity=\"1..*\"");

    // 2. Importar el XMI generado en un diagrama destino vacío
    DiagramaClase emptyDiagram = new DiagramaClase(project, "Diagrama Destino", "Vacio");
    UUID emptyDiagramId = UUID.randomUUID();
    ReflectionTestUtils.setField(emptyDiagram, "id", emptyDiagramId);

    when(diagramRepo.findById(emptyDiagramId)).thenReturn(Optional.of(emptyDiagram));

    // Capturar las clases y relaciones que se guarden
    List<ElementoUML> savedElements = new ArrayList<>();
    when(elementRepo.save(any(ElementoUML.class))).thenAnswer(inv -> {
      ElementoUML e = inv.getArgument(0);
      ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
      savedElements.add(e);
      return e;
    });

    List<RelacionUML> savedRelations = new ArrayList<>();
    when(relationRepo.save(any(RelacionUML.class))).thenAnswer(inv -> {
      RelacionUML r = inv.getArgument(0);
      ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
      savedRelations.add(r);
      return r;
    });

    DiagramFullView expectedView = new DiagramFullView(
        new UmlResponses.DiagramSummary(emptyDiagramId, project.getId(), "Diagrama Destino", "Vacio", "ACTIVO", null),
        List.of(),
        List.of()
    );
    when(umlService.getDiagram(project.getId(), emptyDiagramId, "tester@case.bo")).thenReturn(expectedView);

    DiagramFullView importedResult = service.importXmi(project.getId(), emptyDiagramId, "tester@case.bo", exportedXmi);

    assertThat(importedResult).isNotNull();

    // Comprobar Requerimiento 10: Existen las 4 clases
    assertThat(savedElements).hasSize(4);
    List<String> names = savedElements.stream().map(ElementoUML::getNombre).toList();
    assertThat(names).containsExactlyInAnyOrder("Cliente", "Pedido", "Producto", "DetallePedido");

    // Comprobar Requerimiento 10: Existen las 3 relaciones con cardinalidades intactas
    assertThat(savedRelations).hasSize(3);

    // Relación 1: Cliente <-> Pedido con 1 y 0..*
    Optional<RelacionUML> cpRel = savedRelations.stream()
        .filter(r -> r.getElementoOrigen().getNombre().equals("Cliente") && r.getElementoDestino().getNombre().equals("Pedido"))
        .findFirst();
    assertThat(cpRel).isPresent();
    assertThat(cpRel.get().getMultiplicidadOrigen()).isEqualTo("1");
    assertThat(cpRel.get().getMultiplicidadDestino()).isEqualTo("0..*");

    // Relación 2: Pedido <-> DetallePedido con 1 y 1..* (Composición)
    Optional<RelacionUML> pdRel = savedRelations.stream()
        .filter(r -> r.getElementoOrigen().getNombre().equals("Pedido") && r.getElementoDestino().getNombre().equals("DetallePedido"))
        .findFirst();
    assertThat(pdRel).isPresent();
    assertThat(pdRel.get().getMultiplicidadOrigen()).isEqualTo("1");
    assertThat(pdRel.get().getMultiplicidadDestino()).isEqualTo("1..*");
    assertThat(pdRel.get().getTipoRelacion()).isEqualTo(TipoRelacionUML.COMPOSICION);

    // Relación 3: Producto <-> DetallePedido con 1 y 0..*
    Optional<RelacionUML> prdRel = savedRelations.stream()
        .filter(r -> r.getElementoOrigen().getNombre().equals("Producto") && r.getElementoDestino().getNombre().equals("DetallePedido"))
        .findFirst();
    assertThat(prdRel).isPresent();
    assertThat(prdRel.get().getMultiplicidadOrigen()).isEqualTo("1");
    assertThat(prdRel.get().getMultiplicidadDestino()).isEqualTo("0..*");
  }

  @Test
  void exportXmi_preservesRelationTypesRecursiveAndParallelRelations() {
    ElementoUML interfaz = new ElementoUML(
        diagram, TipoElementoUML.INTERFAZ, "IProcesable", 650.0, 100.0, "{}");
    ReflectionTestUtils.setField(interfaz, "id", UUID.fromString("55555555-5555-5555-5555-555555555555"));
    ElementoUML abstracta = new ElementoUML(
        diagram, TipoElementoUML.CLASE_ABSTRACTA, "Persona", 650.0, 350.0, "{}");
    ReflectionTestUtils.setField(abstracta, "id", UUID.fromString("66666666-6666-6666-6666-666666666666"));
    ElementoUML enumeracion = new ElementoUML(
        diagram, TipoElementoUML.ENUMERACION, "MetodoPago", 900.0, 350.0, "{}");
    ReflectionTestUtils.setField(enumeracion, "id", UUID.fromString("77777777-7777-7777-7777-777777777777"));

    RelacionUML dependencia = new RelacionUML(
        diagram, pedidoEl, productoEl, "usa", TipoRelacionUML.DEPENDENCIA, null, null);
    ReflectionTestUtils.setField(dependencia, "id", UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
    RelacionUML realizacion = new RelacionUML(
        diagram, pedidoEl, interfaz, "implementa", TipoRelacionUML.REALIZACION, null, null);
    ReflectionTestUtils.setField(realizacion, "id", UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"));
    RelacionUML reflexiva = new RelacionUML(
        diagram, clienteEl, clienteEl, "se supervisa", TipoRelacionUML.ASOCIACION, "1", "0..*");
    ReflectionTestUtils.setField(reflexiva, "id", UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
    RelacionUML paralela = new RelacionUML(
        diagram, clienteEl, pedidoEl, "segunda", TipoRelacionUML.DEPENDENCIA, null, null);
    ReflectionTestUtils.setField(paralela, "id", UUID.fromString("12121212-1212-1212-1212-121212121212"));

    when(diagramRepo.findById(diagram.getId())).thenReturn(Optional.of(diagram));
    when(elementRepo.findByDiagramaId(diagram.getId()))
        .thenReturn(List.of(clienteEl, pedidoEl, productoEl, detalleEl, interfaz, abstracta, enumeracion));
    when(relationRepo.findByDiagramaId(diagram.getId()))
        .thenReturn(List.of(relClientePedido, relPedidoDetalle, relProductoDetalle,
            dependencia, realizacion, reflexiva, paralela));

    String xmi = service.exportXmi(project.getId(), diagram.getId(), "tester@case.bo");

    assertThat(xmi).contains("xmlns:xmi=\"http://schema.omg.org/spec/XMI/2.1\"");
    assertThat(xmi).contains("xmi:type=\"uml:Interface\"");
    assertThat(xmi).contains("name=\"IProcesable\"");
    assertThat(xmi).contains("xmi:type=\"uml:Enumeration\"");
    assertThat(xmi).contains("name=\"MetodoPago\"");
    assertThat(xmi).contains("isAbstract=\"true\"");
    assertThat(xmi).contains("xmi:type=\"uml:Dependency\"");
    assertThat(xmi).contains("xmi:type=\"uml:Abstraction\"");
    assertThat(xmi).contains("<xmi:Extension extender=\"Enterprise Architect\" extenderID=\"6.5\">");
    assertThat(xmi).contains("<connector xmi:idref=\"EAID_DDDDDDDD_DDDD_DDDD_DDDD_DDDDDDDDDDDD\"");
    assertThat(xmi).contains("<connector xmi:idref=\"EAID_FFFFFFFF_FFFF_FFFF_FFFF_FFFFFFFFFFFF\"");
    assertThat(xmi).contains("EAID_11111111_1111_1111_1111_111111111111");
    assertThat(xmi).contains("ea_type=\"Realization\"");
  }

  @Test
  void exportXmi_preservesAttributeOrderAndEnterpriseArchitectMultiplicityMetadata() {
    ElementoUML pago = new ElementoUML(
        diagram, TipoElementoUML.CLASE, "Pago", 100.0, 100.0,
        """
        {"attributes":[
          {"id":"a-pago","visibility":"-","name":"idPago","type":"int"},
          {"id":"a-cuota","visibility":"-","name":"idCuota","type":"int"}
        ],"methods":[]}
        """
    );
    ElementoUML cuota = new ElementoUML(diagram, TipoElementoUML.CLASE, "Cuota", 400.0, 100.0, "{}");
    ReflectionTestUtils.setField(pago, "id", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    ReflectionTestUtils.setField(cuota, "id", UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
    RelacionUML pagoCuota = new RelacionUML(
        diagram, cuota, pago, "recibe", TipoRelacionUML.ASOCIACION, "1", "0..*");
    ReflectionTestUtils.setField(pagoCuota, "id", UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));

    when(diagramRepo.findById(diagram.getId())).thenReturn(Optional.of(diagram));
    when(elementRepo.findByDiagramaId(diagram.getId())).thenReturn(List.of(pago, cuota));
    when(relationRepo.findByDiagramaId(diagram.getId())).thenReturn(List.of(pagoCuota));

    String xmi = service.exportXmi(project.getId(), diagram.getId(), "tester@case.bo");

    assertThat(xmi.indexOf("name=\"idPago\"")).isLessThan(xmi.indexOf("name=\"idCuota\""));
    assertThat(xmi).contains("<lowerValue").contains("xmi:type=\"uml:LiteralInteger\"");
    assertThat(xmi).contains("<upperValue").contains("xmi:type=\"uml:LiteralUnlimitedNatural\"");
    assertThat(xmi).contains("<containment containment=\"Not Specified\" position=\"0\"/>");
    assertThat(xmi).contains("<containment containment=\"Not Specified\" position=\"1\"/>");
    assertThat(xmi).contains("<connector xmi:idref=\"EAID_CCCCCCCC_CCCC_CCCC_CCCC_CCCCCCCCCCCC\"");
    assertThat(xmi).contains("multiplicity=\"1\"");
    assertThat(xmi).contains("multiplicity=\"0..*\"");
  }

  @Test
  void exportXmi_preservesMethodParametersAndReturnType() {
    ElementoUML serviceElement = new ElementoUML(
        diagram, TipoElementoUML.CLASE, "Servicio", 100.0, 100.0,
        """
        {"methods":[{"name":"crearPedido","visibility":"+","parameters":"clienteId: Long, urgente: Boolean","returnType":"Pedido"}]}
        """
    );
    ReflectionTestUtils.setField(serviceElement, "id", UUID.randomUUID());
    when(diagramRepo.findById(diagram.getId())).thenReturn(Optional.of(diagram));
    when(elementRepo.findByDiagramaId(diagram.getId())).thenReturn(List.of(serviceElement));
    when(relationRepo.findByDiagramaId(diagram.getId())).thenReturn(List.of());

    String xmi = service.exportXmi(project.getId(), diagram.getId(), "tester@case.bo");

    assertThat(xmi).contains("<ownedParameter").contains("name=\"clienteId\"");
    assertThat(xmi).contains("name=\"urgente\"");
    assertThat(xmi).contains("name=\"Long\"");
    assertThat(xmi).contains("name=\"Boolean\"");
    assertThat(xmi).contains("name=\"return\"");
    assertThat(xmi).contains("name=\"Pedido\"");
  }

  @Test
  void exportAndImportJson_roundTripPreservesCompleteStructure() {
    when(diagramRepo.findById(diagram.getId())).thenReturn(Optional.of(diagram));
    when(elementRepo.findByDiagramaId(diagram.getId())).thenReturn(List.of(clienteEl, pedidoEl, productoEl, detalleEl));
    when(relationRepo.findByDiagramaId(diagram.getId())).thenReturn(List.of(relClientePedido, relPedidoDetalle, relProductoDetalle));

    // 1. Exportar JSON
    String exportedJson = service.exportJson(project.getId(), diagram.getId(), "tester@case.bo");
    assertThat(exportedJson).contains("CASE_UML_DIAGRAM_JSON");
    assertThat(exportedJson).contains("Cliente");
    assertThat(exportedJson).contains("DetallePedido");
    assertThat(exportedJson).contains("1..*");

    // 2. Importar JSON en diagrama vacío
    DiagramaClase emptyDiagram = new DiagramaClase(project, "Diagrama Vacio JSON", "");
    UUID emptyId = UUID.randomUUID();
    ReflectionTestUtils.setField(emptyDiagram, "id", emptyId);

    when(diagramRepo.findById(emptyId)).thenReturn(Optional.of(emptyDiagram));

    List<ElementoUML> savedElements = new ArrayList<>();
    when(elementRepo.save(any(ElementoUML.class))).thenAnswer(inv -> {
      ElementoUML e = inv.getArgument(0);
      savedElements.add(e);
      return e;
    });

    List<RelacionUML> savedRelations = new ArrayList<>();
    when(relationRepo.save(any(RelacionUML.class))).thenAnswer(inv -> {
      RelacionUML r = inv.getArgument(0);
      savedRelations.add(r);
      return r;
    });

    when(umlService.getDiagram(project.getId(), emptyId, "tester@case.bo")).thenReturn(mock(DiagramFullView.class));

    service.importJson(project.getId(), emptyId, "tester@case.bo", exportedJson);

    assertThat(savedElements).hasSize(4);
    assertThat(savedRelations).hasSize(3);

    // Verificar cardinalidades
    assertThat(savedRelations).anyMatch(r ->
        r.getElementoOrigen().getNombre().equals("Pedido") &&
        r.getElementoDestino().getNombre().equals("DetallePedido") &&
        "1..*".equals(r.getMultiplicidadDestino())
    );
  }

  @Test
  void importXmi_enterpriseArchitectXmi11_importsClassesTablesAndAssociations() {
    UUID destDiagramId = UUID.randomUUID();
    DiagramaClase destDiagram = new DiagramaClase(project, "EA Import Diagram", "");
    ReflectionTestUtils.setField(destDiagram, "id", destDiagramId);

    when(diagramRepo.findById(destDiagramId)).thenReturn(Optional.of(destDiagram));

    List<ElementoUML> savedElements = new ArrayList<>();
    when(elementRepo.save(any(ElementoUML.class))).thenAnswer(inv -> {
      ElementoUML e = inv.getArgument(0);
      ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
      savedElements.add(e);
      return e;
    });

    List<RelacionUML> savedRelations = new ArrayList<>();
    when(relationRepo.save(any(RelacionUML.class))).thenAnswer(inv -> {
      RelacionUML r = inv.getArgument(0);
      ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
      savedRelations.add(r);
      return r;
    });

    when(umlService.getDiagram(project.getId(), destDiagramId, "tester@case.bo"))
        .thenReturn(mock(DiagramFullView.class));

    // Archivo representativo de Enterprise Architect XMI 1.1 con tablas y clases
    String eaXmi = """
        <?xml version="1.0" encoding="windows-1252"?>
        <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
          <XMI.header>
            <XMI.documentation>
              <XMI.exporter>Enterprise Architect</XMI.exporter>
              <XMI.exporterVersion>2.5</XMI.exporterVersion>
            </XMI.documentation>
          </XMI.header>
          <XMI.content>
            <UML:Model name="EA Model" xmi.id="MX_EAId_0">
              <UML:Namespace.ownedElement>
                <!-- Tipo de datos nativos de Enterprise Architect -->
                <UML:DataType xmi.id="EAJava_int" name="int"/>
                <UML:DataType xmi.id="EAJava_String" name="String"/>
                <UML:DataType xmi.id="EASQL_decimal" name="decimal"/>

                <!-- Clase UML normal de EA -->
                <UML:Class name="Cliente" xmi.id="EAID_CL_1" visibility="public">
                  <UML:Classifier.feature>
                    <UML:Attribute name="id" visibility="private">
                      <UML:StructuralFeature.type>
                        <UML:Classifier xmi.idref="EAJava_int"/>
                      </UML:StructuralFeature.type>
                    </UML:Attribute>
                    <UML:Attribute name="nombre" visibility="public">
                      <UML:StructuralFeature.type>
                        <UML:Classifier xmi.idref="EAJava_String"/>
                      </UML:StructuralFeature.type>
                    </UML:Attribute>
                    <UML:Operation name="obtenerNombre" visibility="public">
                      <UML:BehavioralFeature.parameter>
                        <UML:Parameter kind="return">
                          <UML:Parameter.type>
                            <UML:Classifier xmi.idref="EAJava_String"/>
                          </UML:Parameter.type>
                        </UML:Parameter>
                      </UML:BehavioralFeature.parameter>
                    </UML:Operation>
                  </UML:Classifier.feature>
                </UML:Class>

                <!-- Tabla relacional creada en EA (estereotipo 'table') -->
                <UML:Class name="tbl_factura" xmi.id="EAID_TB_1">
                  <UML:ModelElement.stereotype>
                    <UML:Stereotype name="table"/>
                  </UML:ModelElement.stereotype>
                  <UML:Classifier.feature>
                    <UML:Attribute name="total" visibility="public">
                      <UML:StructuralFeature.type>
                        <UML:Classifier xmi.idref="EASQL_decimal"/>
                      </UML:StructuralFeature.type>
                    </UML:Attribute>
                  </UML:Classifier.feature>
                </UML:Class>

                <!-- Asociación entre Cliente y Factura con cardinalidades 1 y 0..* -->
                <UML:Association xmi.id="EAID_REL_1" name="cliente_facturas">
                  <UML:Association.connection>
                    <UML:AssociationEnd visibility="public" aggregation="none" type="EAID_CL_1">
                      <UML:AssociationEnd.multiplicity>
                        <UML:Multiplicity>
                          <UML:Multiplicity.range>
                            <UML:MultiplicityRange lower="1" upper="1"/>
                          </UML:Multiplicity.range>
                        </UML:Multiplicity>
                      </UML:AssociationEnd.multiplicity>
                      <UML:AssociationEnd.participant>
                        <UML:Classifier xmi.idref="EAID_CL_1"/>
                      </UML:AssociationEnd.participant>
                    </UML:AssociationEnd>
                    <UML:AssociationEnd visibility="public" aggregation="none" type="EAID_TB_1">
                      <UML:AssociationEnd.multiplicity>
                        <UML:Multiplicity>
                          <UML:Multiplicity.range>
                            <UML:MultiplicityRange lower="0" upper="-1"/>
                          </UML:Multiplicity.range>
                        </UML:Multiplicity>
                      </UML:AssociationEnd.multiplicity>
                      <UML:AssociationEnd.participant>
                        <UML:Classifier xmi.idref="EAID_TB_1"/>
                      </UML:AssociationEnd.participant>
                    </UML:AssociationEnd>
                  </UML:Association.connection>
                </UML:Association>
              </UML:Namespace.ownedElement>
            </UML:Model>
          </XMI.content>
        </XMI>
        """;

    service.importXmi(project.getId(), destDiagramId, "tester@case.bo", eaXmi);

    // Validar que se importaron tanto la clase 'Cliente' como la tabla 'tbl_factura'
    assertThat(savedElements).hasSize(2);
    List<String> names = savedElements.stream().map(ElementoUML::getNombre).toList();
    assertThat(names).containsExactlyInAnyOrder("Cliente", "tbl_factura");

    // Validar atributos y métodos de 'Cliente'
    ElementoUML clienteParsed = savedElements.stream().filter(e -> e.getNombre().equals("Cliente")).findFirst().orElseThrow();
    assertThat(clienteParsed.getPropiedadesJson()).contains("\"name\":\"id\"");
    assertThat(clienteParsed.getPropiedadesJson()).contains("\"type\":\"Integer\"");
    assertThat(clienteParsed.getPropiedadesJson()).contains("\"name\":\"nombre\"");
    assertThat(clienteParsed.getPropiedadesJson()).contains("\"type\":\"String\"");
    assertThat(clienteParsed.getPropiedadesJson()).contains("\"name\":\"obtenerNombre\"");

    // Validar atributos de 'tbl_factura'
    ElementoUML facturaParsed = savedElements.stream().filter(e -> e.getNombre().equals("tbl_factura")).findFirst().orElseThrow();
    assertThat(facturaParsed.getPropiedadesJson()).contains("\"name\":\"total\"");
    assertThat(facturaParsed.getPropiedadesJson()).contains("\"type\":\"Decimal\"");

    // Validar la relación y sus cardinalidades 1 y 0..*
    assertThat(savedRelations).hasSize(1);
    RelacionUML rel = savedRelations.get(0);
    assertThat(rel.getNombreRelacion()).isEqualTo("cliente_facturas");
    assertThat(rel.getMultiplicidadOrigen()).isEqualTo("1");
    assertThat(rel.getMultiplicidadDestino()).isEqualTo("0..*");
  }

  @Test
  void importXmi_preservesAggregationFromEnterpriseArchitectRepresentations() {
    UUID destDiagramId = UUID.randomUUID();
    DiagramaClase destDiagram = new DiagramaClase(project, "EA Aggregation Diagram", "");
    ReflectionTestUtils.setField(destDiagram, "id", destDiagramId);
    when(diagramRepo.findById(destDiagramId)).thenReturn(Optional.of(destDiagram));

    List<ElementoUML> savedElements = new ArrayList<>();
    when(elementRepo.save(any(ElementoUML.class))).thenAnswer(inv -> {
      ElementoUML element = inv.getArgument(0);
      ReflectionTestUtils.setField(element, "id", UUID.randomUUID());
      savedElements.add(element);
      return element;
    });
    List<RelacionUML> savedRelations = new ArrayList<>();
    when(relationRepo.save(any(RelacionUML.class))).thenAnswer(inv -> {
      RelacionUML relation = inv.getArgument(0);
      ReflectionTestUtils.setField(relation, "id", UUID.randomUUID());
      if (!savedRelations.contains(relation)) savedRelations.add(relation);
      return relation;
    });
    when(umlService.getDiagram(project.getId(), destDiagramId, "tester@case.bo"))
        .thenReturn(mock(DiagramFullView.class));

    String eaXmi = """
        <?xml version="1.0"?>
        <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3" xmlns:xmi="http://www.omg.org/XMI">
          <XMI.content>
            <UML:Model xmi.id="MODEL_1">
              <UML:Namespace.ownedElement>
                <UML:Class xmi.id="EAID_PEDIDO" name="Pedido"/>
                <UML:Class xmi.id="EAID_DETALLE" name="DetallePedido"/>
                <UML:Class xmi.id="EAID_CATEGORIA" name="Categoria"/>
                <UML:Class xmi.id="EAID_PRODUCTO" name="Producto"/>
                <UML:Association xmi.id="EAID_REL_PEDIDO_DETALLE" name="contiene">
                  <UML:Association.connection>
                    <UML:AssociationEnd type="EAID_PEDIDO" aggregation="none">
                      <UML:AssociationEnd.multiplicity><UML:MultiplicityRange lower="1" upper="1"/></UML:AssociationEnd.multiplicity>
                    </UML:AssociationEnd>
                    <UML:AssociationEnd type="EAID_DETALLE">
                      <UML:AssociationEnd.multiplicity><UML:MultiplicityRange lower="0" upper="-1"/></UML:AssociationEnd.multiplicity>
                    </UML:AssociationEnd>
                  </UML:Association.connection>
                </UML:Association>
                <UML:Association xmi.id="EAID_REL_CATEGORIA_PRODUCTO" name="clasifica">
                  <UML:Association.connection>
                    <UML:AssociationEnd type="EAID_CATEGORIA" aggregation="shared">
                      <UML:AssociationEnd.multiplicity><UML:MultiplicityRange lower="1" upper="1"/></UML:AssociationEnd.multiplicity>
                    </UML:AssociationEnd>
                    <UML:AssociationEnd type="EAID_PRODUCTO">
                      <UML:AssociationEnd.multiplicity><UML:MultiplicityRange lower="0" upper="-1"/></UML:AssociationEnd.multiplicity>
                    </UML:AssociationEnd>
                  </UML:Association.connection>
                </UML:Association>
              </UML:Namespace.ownedElement>
            </UML:Model>
          </XMI.content>
          <xmi:Extension extender="Enterprise Architect">
            <connectors>
              <connector xmi:idref="EAID_REL_PEDIDO_DETALLE">
                <source xmi:idref="EAID_PEDIDO"><type multiplicity="1" aggregation="none"/></source>
                <target xmi:idref="EAID_DETALLE"><type multiplicity="0..*" aggregation="none"/></target>
                <properties ea_type="Aggregation" subtype="2" direction="Destination -> Source"/>
              </connector>
            </connectors>
          </xmi:Extension>
        </XMI>
        """;

    service.importXmi(project.getId(), destDiagramId, "tester@case.bo", eaXmi);

    assertThat(savedElements).extracting(ElementoUML::getNombre)
        .containsExactlyInAnyOrder("Pedido", "DetallePedido", "Categoria", "Producto");
    assertThat(savedRelations).hasSize(2);
    assertThat(savedRelations).allMatch(r -> r.getTipoRelacion() == TipoRelacionUML.AGREGACION);
    assertThat(savedRelations).anyMatch(r -> r.getElementoOrigen().getNombre().equals("Pedido")
        && r.getElementoDestino().getNombre().equals("DetallePedido")
        && r.getMultiplicidadOrigen().equals("1")
        && r.getMultiplicidadDestino().equals("0..*"));
    assertThat(savedRelations).anyMatch(r -> r.getElementoOrigen().getNombre().equals("Categoria")
        && r.getElementoDestino().getNombre().equals("Producto")
        && r.getMultiplicidadOrigen().equals("1")
        && r.getMultiplicidadDestino().equals("0..*"));
  }

  @Test
  void exportXmi_withClienteReservaSucursal_exportsEnterpriseArchitectTypesAndDiagramGeometry() {
    // Diagrama exactamente como el del usuario: Cliente, Reserva, Sucursal con tipos Integer y long
    ElementoUML cliente = new ElementoUML(
        diagram,
        TipoElementoUML.CLASE,
        "Cliente",
        120.0,
        140.0,
        """
        {"attributes":[{"id":"c1","visibility":"+","name":"Id","type":"Integer"},{"id":"c2","visibility":"+","name":"Nombre","type":"long"}],"methods":[]}
        """
    );
    ReflectionTestUtils.setField(cliente, "id", UUID.fromString("11111111-0000-0000-0000-000000000001"));

    ElementoUML reserva = new ElementoUML(
        diagram,
        TipoElementoUML.CLASE,
        "Reserva",
        420.0,
        360.0,
        """
        {"attributes":[{"id":"r1","visibility":"+","name":"Id","type":"long"}],"methods":[]}
        """
    );
    ReflectionTestUtils.setField(reserva, "id", UUID.fromString("22222222-0000-0000-0000-000000000002"));

    ElementoUML sucursal = new ElementoUML(
        diagram,
        TipoElementoUML.CLASE,
        "Sucursal",
        720.0,
        200.0,
        """
        {"attributes":[{"id":"s1","visibility":"-","name":"IdSucursal","type":"Integer"}],"methods":[]}
        """
    );
    ReflectionTestUtils.setField(sucursal, "id", UUID.fromString("33333333-0000-0000-0000-000000000003"));

    RelacionUML relRealiza = new RelacionUML(
        diagram,
        cliente,
        reserva,
        "realiza",
        TipoRelacionUML.ASOCIACION,
        "1..1",
        "0..*"
    );
    ReflectionTestUtils.setField(relRealiza, "id", UUID.randomUUID());

    RelacionUML relSucursal = new RelacionUML(
        diagram,
        sucursal,
        reserva,
        null,
        TipoRelacionUML.ASOCIACION,
        "1..1",
        "1..*"
    );
    ReflectionTestUtils.setField(relSucursal, "id", UUID.randomUUID());

    when(diagramRepo.findById(diagram.getId())).thenReturn(Optional.of(diagram));
    when(elementRepo.findByDiagramaId(diagram.getId())).thenReturn(List.of(cliente, reserva, sucursal));
    when(relationRepo.findByDiagramaId(diagram.getId())).thenReturn(List.of(relRealiza, relSucursal));

    String xmi = service.exportXmi(project.getId(), diagram.getId(), "tester@case.bo");

    // 1. Validar que el documento usa una sola versión coherente de XMI/UML.
    assertThat(xmi).contains("xmi:version=\"2.1\"");
    assertThat(xmi).contains("xmlns:uml=\"http://schema.omg.org/spec/UML/2.1\"");
    assertThat(xmi).contains("xmi:type=\"uml:Class\"");
    assertThat(xmi).contains("name=\"Cliente\"");
    assertThat(xmi).contains("name=\"Reserva\"");
    assertThat(xmi).contains("name=\"Sucursal\"");

    // 2. Validar ownedAttribute con tipos enlazados
    assertThat(xmi).contains("<ownedAttribute").contains("name=\"Id\"");
    assertThat(xmi).contains("name=\"Nombre\"");
    assertThat(xmi).contains("name=\"IdSucursal\"");
    assertThat(xmi).contains("name=\"Integer\"");
    assertThat(xmi).contains("name=\"long\"");
    assertThat(xmi).contains("visibility=\"private\"");

    // 3. Validar extensión de Enterprise Architect con tipos y scopes
    assertThat(xmi).contains("xmi:type=\"uml:Association\"");
    assertThat(xmi).contains("name=\"realiza\"");
    assertThat(xmi).contains("xmi:idref=\"EAID_11111111_0000_0000_0000_000000000001\"");
    assertThat(xmi).contains("xmi:idref=\"EAID_22222222_0000_0000_0000_000000000002\"");
    assertThat(xmi).contains("xmi:idref=\"EAID_33333333_0000_0000_0000_000000000003\"");
    assertThat(xmi).contains("value=\"1\"");
    assertThat(xmi).contains("value=\"0\"");
    assertThat(xmi).contains("value=\"*\"");

    // 4. Validar geometría de diagrama de Enterprise Architect
    assertThat(xmi).contains("geometry=\"Left=120;Top=140;Right=320;");
    assertThat(xmi).contains("geometry=\"Left=420;Top=360;Right=620;");
    assertThat(xmi).contains("geometry=\"Left=720;Top=200;Right=920;");
    assertThat(xmi).contains("subject=\"EAID_11111111_0000_0000_0000_000000000001\"");
    assertThat(xmi).contains("subject=\"EAID_22222222_0000_0000_0000_000000000002\"");
    assertThat(xmi).contains("subject=\"EAID_33333333_0000_0000_0000_000000000003\"");
    assertThat(xmi).contains("geometry=\"SX=0;SY=0;EX=0;EY=0;Path=;\"");

    // 5. Validar conectores en la extensión
    assertThat(xmi).contains("<xmi:Extension extender=\"Enterprise Architect\" extenderID=\"6.5\">");
    assertThat(xmi).contains("<model localID=\"1\"")
        .contains("owner=\"EAPK_").contains("package=\"EAPK_");
    assertThat(xmi).contains("<properties name=\"").contains("type=\"Logical\"");
    assertThat(xmi).contains("<project author=\"Proyecto CASE\"")
        .contains("<style1 value=\"").contains("<style2 value=\"")
        .contains("<swimlanes value=\"").contains("<matrixitems value=\"")
        .contains("<extendedProperties");
    assertThat(xmi).contains("<connectors>");
    assertThat(xmi).contains("<connector xmi:idref=\"");
    assertThat(xmi).contains("name=\"realiza\"");
    assertThat(xmi).contains("ea_type=\"Association\"");
    assertThat(xmi).contains("multiplicity=\"1..1\"");
    assertThat(xmi).contains("multiplicity=\"0..*\"");
    assertThat(xmi).contains("multiplicity=\"1..*\"");
  }

  @Test
  void importXmi_fromEnterpriseArchitect_ignoresEARootClassAndPrimitiveTypesWhilePreservingClassesAndAttributeTypes() {
    String eaXmi = """
        <?xml version="1.0" encoding="windows-1252"?>
        <xmi:XMI xmi:version="2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1">
          <xmi:Documentation exporter="Enterprise Architect" exporterVersion="6.5"/>
          <uml:Model xmi:type="uml:Model" name="EA_Model">
            <packagedElement xmi:type="uml:Package" xmi:id="EAPK_001" name="Model">
              <!-- Elementos sintéticos y tipos de EA que NO deben crearse como tablas -->
              <packagedElement xmi:type="uml:Class" xmi:id="EAID_EAROOT" name="EARootClass"/>
              <packagedElement xmi:type="uml:Class" xmi:id="EAID_TYPE_INT" name="Integer"/>
              <packagedElement xmi:type="uml:Class" xmi:id="EAID_TYPE_LONG" name="long"/>

              <!-- Clases reales del diagrama -->
              <packagedElement xmi:type="uml:Class" xmi:id="EAID_CLIENTE" name="Cliente">
                <ownedAttribute xmi:type="uml:Property" xmi:id="EAID_ATTR_C1" name="Id" visibility="public" type="EAID_TYPE_INT"/>
                <ownedAttribute xmi:type="uml:Property" xmi:id="EAID_ATTR_C2" name="Nombre" visibility="public" type="EAID_TYPE_LONG"/>
              </packagedElement>
              <packagedElement xmi:type="uml:Class" xmi:id="EAID_RESERVA" name="Reserva">
                <ownedAttribute xmi:type="uml:Property" xmi:id="EAID_ATTR_R1" name="Id" visibility="public" type="EAID_TYPE_LONG"/>
              </packagedElement>
              <packagedElement xmi:type="uml:Class" xmi:id="EAID_SUCURSAL" name="Sucursal">
                <ownedAttribute xmi:type="uml:Property" xmi:id="EAID_ATTR_S1" name="IdSucursal" visibility="private" type="EAID_TYPE_INT"/>
              </packagedElement>

              <packagedElement xmi:type="uml:Association" xmi:id="EAID_REL_1" name="realiza">
                <memberEnd xmi:idref="EAID_END_1"/>
                <memberEnd xmi:idref="EAID_END_2"/>
                <ownedEnd xmi:type="uml:Property" xmi:id="EAID_END_1" type="EAID_CLIENTE" multiplicity="1..1"/>
                <ownedEnd xmi:type="uml:Property" xmi:id="EAID_END_2" type="EAID_RESERVA" multiplicity="0..*"/>
              </packagedElement>
              <packagedElement xmi:type="uml:Association" xmi:id="EAID_REL_2">
                <memberEnd xmi:idref="EAID_END_3"/>
                <memberEnd xmi:idref="EAID_END_4"/>
                <ownedEnd xmi:type="uml:Property" xmi:id="EAID_END_3" type="EAID_SUCURSAL" multiplicity="1..1"/>
                <ownedEnd xmi:type="uml:Property" xmi:id="EAID_END_4" type="EAID_RESERVA" multiplicity="1..*"/>
              </packagedElement>
            </packagedElement>
          </uml:Model>
          <xmi:Extension extender="Enterprise Architect" extenderID="6.5">
            <elements>
              <element xmi:idref="EAID_EAROOT" xmi:type="uml:Class" name="EARootClass" scope="public"/>
              <element xmi:idref="EAID_TYPE_INT" xmi:type="uml:Class" name="Integer" scope="public"/>
              <element xmi:idref="EAID_TYPE_LONG" xmi:type="uml:Class" name="long" scope="public"/>
              <element xmi:idref="EAID_CLIENTE" xmi:type="uml:Class" name="Cliente" scope="public"/>
              <element xmi:idref="EAID_RESERVA" xmi:type="uml:Class" name="Reserva" scope="public"/>
              <element xmi:idref="EAID_SUCURSAL" xmi:type="uml:Class" name="Sucursal" scope="public"/>
            </elements>
            <diagrams>
              <diagram xmi:id="EAID_DIAG_1">
                <elements>
                  <element subject="EAID_CLIENTE" geometry="Left=120;Top=140;Right=320;Bottom=250;"/>
                  <element subject="EAID_RESERVA" geometry="Left=420;Top=360;Right=620;Bottom=470;"/>
                  <element subject="EAID_SUCURSAL" geometry="Left=720;Top=200;Right=920;Bottom=310;"/>
                </elements>
              </diagram>
            </diagrams>
          </xmi:Extension>
        </xmi:XMI>
        """;

    DiagramaClase targetDiagram = new DiagramaClase(project, "Diagrama Importado EA", "EA");
    UUID targetDiagramId = UUID.randomUUID();
    ReflectionTestUtils.setField(targetDiagram, "id", targetDiagramId);

    when(diagramRepo.findById(targetDiagramId)).thenReturn(Optional.of(targetDiagram));

    List<ElementoUML> savedElements = new ArrayList<>();
    when(elementRepo.save(any(ElementoUML.class))).thenAnswer(inv -> {
      ElementoUML el = inv.getArgument(0);
      ReflectionTestUtils.setField(el, "id", UUID.randomUUID());
      savedElements.add(el);
      return el;
    });

    List<RelacionUML> savedRelations = new ArrayList<>();
    when(relationRepo.save(any(RelacionUML.class))).thenAnswer(inv -> {
      RelacionUML r = inv.getArgument(0);
      ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
      savedRelations.add(r);
      return r;
    });

    DiagramFullView expectedView = new DiagramFullView(
        new UmlResponses.DiagramSummary(targetDiagramId, project.getId(), "Diagrama Importado EA", "EA", "ACTIVO", null),
        List.of(),
        List.of()
    );
    when(umlService.getDiagram(project.getId(), targetDiagramId, "tester@case.bo")).thenReturn(expectedView);

    service.importXmi(project.getId(), targetDiagramId, "tester@case.bo", eaXmi);

    // 1. Verificar que SOLAMENTE se crearon las 3 clases reales y NO EARootClass, Integer o long
    assertThat(savedElements).hasSize(3);
    List<String> elementNames = savedElements.stream().map(ElementoUML::getNombre).toList();
    assertThat(elementNames).containsExactlyInAnyOrder("Cliente", "Reserva", "Sucursal");
    assertThat(elementNames).doesNotContain("EARootClass", "Integer", "long");

    // 2. Verificar que las posiciones se obtuvieron de la geometría del diagrama de EA
    ElementoUML cliente = savedElements.stream().filter(e -> e.getNombre().equals("Cliente")).findFirst().orElseThrow();
    ElementoUML reserva = savedElements.stream().filter(e -> e.getNombre().equals("Reserva")).findFirst().orElseThrow();
    ElementoUML sucursal = savedElements.stream().filter(e -> e.getNombre().equals("Sucursal")).findFirst().orElseThrow();

    assertThat(cliente.getPosicionX()).isEqualTo(120.0);
    assertThat(cliente.getPosicionY()).isEqualTo(140.0);
    assertThat(reserva.getPosicionX()).isEqualTo(420.0);
    assertThat(reserva.getPosicionY()).isEqualTo(360.0);
    assertThat(sucursal.getPosicionX()).isEqualTo(720.0);
    assertThat(sucursal.getPosicionY()).isEqualTo(200.0);

    // 3. Verificar que los tipos de datos de los atributos se resolvieron correctamente
    assertThat(cliente.getPropiedadesJson()).contains("\"name\":\"Id\",\"type\":\"Integer\"");
    assertThat(cliente.getPropiedadesJson()).contains("\"name\":\"Nombre\",\"type\":\"long\"");
    assertThat(reserva.getPropiedadesJson()).contains("\"name\":\"Id\",\"type\":\"long\"");
    assertThat(sucursal.getPropiedadesJson()).contains("\"name\":\"IdSucursal\",\"type\":\"Integer\"");

    // 4. Verificar que las relaciones se crearon correctamente con sus cardinalidades
    assertThat(savedRelations).hasSize(2);
    Optional<RelacionUML> rel1 = savedRelations.stream()
        .filter(r -> "realiza".equals(r.getNombreRelacion()))
        .findFirst();
    assertThat(rel1).isPresent();
    assertThat(rel1.get().getMultiplicidadOrigen()).isEqualTo("1..1");
    assertThat(rel1.get().getMultiplicidadDestino()).isEqualTo("0..*");

    Optional<RelacionUML> rel2 = savedRelations.stream()
        .filter(r -> r.getNombreRelacion() == null || r.getNombreRelacion().isBlank())
        .findFirst();
    assertThat(rel2).isPresent();
    assertThat(rel2.get().getMultiplicidadOrigen()).isEqualTo("1..1");
    assertThat(rel2.get().getMultiplicidadDestino()).isEqualTo("1..*");
  }
}

