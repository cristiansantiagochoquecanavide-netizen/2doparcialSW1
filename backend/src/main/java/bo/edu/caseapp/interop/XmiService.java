package bo.edu.caseapp.interop;

import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.uml.UmlResponses.DiagramFullView;
import bo.edu.caseapp.uml.collab.UmlWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.StringReader;
import java.time.Instant;
import java.util.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Service
@Transactional
public class XmiService {
  private final DiagramaClaseRepository diagramRepo;
  private final ElementoUMLRepository elementRepo;
  private final RelacionUMLRepository relationRepo;
  private final ProjectService projectService;
  private final UmlService umlService;
  private final UmlWebSocketHandler wsHandler;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public XmiService(
      DiagramaClaseRepository diagramRepo,
      ElementoUMLRepository elementRepo,
      RelacionUMLRepository relationRepo,
      ProjectService projectService,
      UmlService umlService,
      UmlWebSocketHandler wsHandler
  ) {
    this.diagramRepo = diagramRepo;
    this.elementRepo = elementRepo;
    this.relationRepo = relationRepo;
    this.projectService = projectService;
    this.umlService = umlService;
    this.wsHandler = wsHandler;
  }

  public String exportXmi(UUID projectId, UUID diagramId, String email) {
    return convertLegacyExportToXmi21(exportXmi11(projectId, diagramId, email));
  }

  /** Builds the existing EA metadata, then emits it alongside a UML 2.1 XMI model.
   *  The old exporter mixed an XMI 1.1 envelope with XMI 2.x extension syntax,
   *  causing EA to import the UML core while ignoring feature ordering metadata.
   */
  private String exportXmi11(UUID projectId, UUID diagramId, String email) {
    projectService.requireAccess(projectId, email);
    DiagramaClase diagram = validateDiagram(projectId, diagramId);
    List<ElementoUML> elements = elementRepo.findByDiagramaId(diagramId);
    List<RelacionUML> relations = relationRepo.findByDiagramaId(diagramId);

      Map<UUID, JsonNode> propsMap = new HashMap<>();
      for (ElementoUML el : elements) {
        try {
          if (el.getPropiedadesJson() != null && !el.getPropiedadesJson().isBlank()) {
            propsMap.put(el.getId(), objectMapper.readTree(el.getPropiedadesJson()));
          }
        } catch (Exception ignored) {}
      }

      String pkgGuid = "EAPK_" + diagram.getId().toString().replace("-", "_").toUpperCase();
      String diagGuid = "EAID_" + UUID.randomUUID().toString().replace("-", "_").toUpperCase();
      String modelGuid = "MX_EAID_" + UUID.randomUUID().toString().replace("-", "_").toUpperCase();

      StringBuilder sb = new StringBuilder();
      sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      sb.append("<XMI xmi.version=\"1.1\" xmlns:xmi=\"http://www.omg.org/XMI\" xmlns:UML=\"omg.org/UML1.3\" timestamp=\"2026-09-26 12:00:00\">\n");
      sb.append("  <XMI.header>\n");
      sb.append("    <XMI.documentation>\n");
      sb.append("      <XMI.exporter>Enterprise Architect</XMI.exporter>\n");
      sb.append("      <XMI.exporterVersion>2.5</XMI.exporterVersion>\n");
      sb.append("    </XMI.documentation>\n");
      sb.append("  </XMI.header>\n");
      sb.append("  <XMI.content>\n");
      sb.append("    <UML:Model name=\"EA Model\" xmi.id=\"").append(modelGuid).append("\">\n");
      sb.append("      <UML:Namespace.ownedElement>\n");
      sb.append("        <UML:Class name=\"EARootClass\" xmi.id=\"EAID_11111111_5487_4080_A7F4_41526CB0AA00\" isRoot=\"true\" isLeaf=\"false\" isAbstract=\"false\"/>\n");
      sb.append("        <UML:Package name=\"").append(escapeXml(diagram.getNombre())).append("\" xmi.id=\"").append(pkgGuid).append("\" isRoot=\"false\" isLeaf=\"false\" isAbstract=\"false\" visibility=\"public\">\n");
      sb.append("          <UML:ModelElement.taggedValue>\n");
      sb.append("            <UML:TaggedValue tag=\"parent\" value=\"EAPK_ROOT\"/>\n");
      sb.append("            <UML:TaggedValue tag=\"ea_package_id\" value=\"2\"/>\n");
      sb.append("            <UML:TaggedValue tag=\"iscontrolled\" value=\"FALSE\"/>\n");
      sb.append("            <UML:TaggedValue tag=\"isprotected\" value=\"FALSE\"/>\n");
      sb.append("            <UML:TaggedValue tag=\"usedtd\" value=\"FALSE\"/>\n");
      sb.append("            <UML:TaggedValue tag=\"logxml\" value=\"FALSE\"/>\n");
      sb.append("            <UML:TaggedValue tag=\"tpos\" value=\"0\"/>\n");
      sb.append("            <UML:TaggedValue tag=\"phase\" value=\"1.0\"/>\n");
      sb.append("            <UML:TaggedValue tag=\"status\" value=\"Proposed\"/>\n");
      sb.append("            <UML:TaggedValue tag=\"author\" value=\"Herramienta CASE\"/>\n");
      sb.append("            <UML:TaggedValue tag=\"complexity\" value=\"1\"/>\n");
      sb.append("            <UML:TaggedValue tag=\"ea_stype\" value=\"Public\"/>\n");
      sb.append("            <UML:TaggedValue tag=\"gentype\" value=\"Java\"/>\n");
      sb.append("          </UML:ModelElement.taggedValue>\n");
      sb.append("          <UML:Namespace.ownedElement>\n");

      // 1. Clases / Interfaces / Enumeraciones
      for (ElementoUML el : elements) {
        String elGuid = "EAID_" + el.getId().toString().replace("-", "_").toUpperCase();
        String eaStype = switch (el.getTipoElemento()) {
          case INTERFAZ -> "Interface";
          case ENUMERACION -> "Enumeration";
          default -> "Class";
        };
        boolean isAbs = el.getTipoElemento() == TipoElementoUML.CLASE_ABSTRACTA;
        String elementTag = switch (el.getTipoElemento()) {
          case INTERFAZ -> "UML:Interface";
          case ENUMERACION -> "UML:Enumeration";
          default -> "UML:Class";
        };

        sb.append("            <").append(elementTag).append(" name=\"").append(escapeXml(el.getNombre()))
          .append("\" xmi.id=\"").append(elGuid)
          .append("\" visibility=\"public\" namespace=\"").append(pkgGuid)
          .append("\" isRoot=\"false\" isLeaf=\"false\" isAbstract=\"").append(isAbs ? "true" : "false")
          .append("\" isActive=\"false\">\n");

        sb.append("              <UML:ModelElement.taggedValue>\n");
        sb.append("                <UML:TaggedValue tag=\"isSpecification\" value=\"false\"/>\n");
        sb.append("                <UML:TaggedValue tag=\"ea_stype\" value=\"").append(eaStype).append("\"/>\n");
        sb.append("                <UML:TaggedValue tag=\"ea_ntype\" value=\"0\"/>\n");
        sb.append("                <UML:TaggedValue tag=\"version\" value=\"1.0\"/>\n");
        sb.append("                <UML:TaggedValue tag=\"package\" value=\"").append(pkgGuid).append("\"/>\n");
        sb.append("                <UML:TaggedValue tag=\"gentype\" value=\"Java\"/>\n");
        sb.append("                <UML:TaggedValue tag=\"package_name\" value=\"").append(escapeXml(diagram.getNombre())).append("\"/>\n");
        sb.append("                <UML:TaggedValue tag=\"author\" value=\"Herramienta CASE\"/>\n");
        sb.append("                <UML:TaggedValue tag=\"status\" value=\"Proposed\"/>\n");
        sb.append("                <UML:TaggedValue tag=\"style\" value=\"BackColor=-1;BorderColor=-1;BorderWidth=-1;FontColor=-1;VSwimLanes=1;HSwimLanes=1;BorderStyle=0;\"/>\n");
        sb.append("              </UML:ModelElement.taggedValue>\n");

        JsonNode root = propsMap.get(el.getId());
        JsonNode attrs = root != null ? root.path("attributes") : null;
        JsonNode methods = root != null ? root.path("methods") : null;

        boolean hasAttrs = attrs != null && attrs.isArray() && attrs.size() > 0;
        boolean hasMethods = methods != null && methods.isArray() && methods.size() > 0;

        if (hasAttrs || hasMethods) {
          sb.append("              <UML:Classifier.feature>\n");
          if (hasAttrs) {
            // EA uses xmi:Extension/.../containment/@position to sort imported attributes.
            // Emit backwards and number the extension positions in import order so the
            // resulting EA list matches the editor's original order.
            for (int attrIndex = attrs.size() - 1; attrIndex >= 0; attrIndex--) {
              JsonNode a = attrs.get(attrIndex);
              int pos = attrIndex;
              String name = a.path("name").asText().trim();
              if (name.isBlank()) continue;
              UUID attrUuid = attributeUuid(el.getId(), a, pos);
              String attrGuid = toEaId(attrUuid);
              String rawType = a.path("type").asText("String").trim();
              if (rawType.isBlank()) rawType = "String";
              String vis = a.path("visibility").asText("+");
              String visibility = switch (vis) {
                case "-" -> "private";
                case "#" -> "protected";
                case "~" -> "package";
                default -> "public";
              };

              sb.append("                <UML:Attribute name=\"").append(escapeXml(name))
                .append("\" xmi.id=\"").append(attrGuid)
                .append("\" visibility=\"").append(visibility)
                .append("\" changeability=\"changeable\">\n");
              sb.append("                  <UML:Attribute.initialValue><UML:Expression body=\"\"/></UML:Attribute.initialValue>\n");
              sb.append("                  <UML:StructuralFeature.type><UML:Classifier xmi.idref=\"").append(escapeXml(rawType)).append("\"/></UML:StructuralFeature.type>\n");
              sb.append("                  <UML:ModelElement.taggedValue>\n");
              sb.append("                    <UML:TaggedValue tag=\"type\" value=\"").append(escapeXml(rawType)).append("\"/>\n");
              sb.append("                    <UML:TaggedValue tag=\"derived\" value=\"0\"/>\n");
              sb.append("                    <UML:TaggedValue tag=\"containment\" value=\"Not Specified\"/>\n");
              sb.append("                    <UML:TaggedValue tag=\"position\" value=\"").append(pos).append("\"/>\n");
              sb.append("                    <UML:TaggedValue tag=\"lowerBound\" value=\"1\"/>\n");
              sb.append("                    <UML:TaggedValue tag=\"upperBound\" value=\"1\"/>\n");
              sb.append("                    <UML:TaggedValue tag=\"ea_guid\" value=\"").append("{").append(attrUuid).append("}").append("\"/>\n");
              sb.append("                  </UML:ModelElement.taggedValue>\n");
              sb.append("                </UML:Attribute>\n");
            }
          }

          if (hasMethods) {
            int pos = 0;
            for (JsonNode m : methods) {
              String opName = m.path("name").asText().trim();
              if (opName.isBlank()) continue;
              String opGuid = "EAID_" + UUID.randomUUID().toString().replace("-", "_").toUpperCase();
              String retType = m.path("returnType").asText("void").trim();
              if (retType.isBlank()) retType = "void";
              String vis = m.path("visibility").asText("+");
              String visibility = switch (vis) {
                case "-" -> "private";
                case "#" -> "protected";
                case "~" -> "package";
                default -> "public";
              };

              sb.append("                <UML:Operation name=\"").append(escapeXml(opName))
                .append("\" xmi.id=\"").append(opGuid)
                .append("\" visibility=\"").append(visibility)
                .append("\" isAbstract=\"false\" concurrency=\"sequential\" isQuery=\"false\">\n");
              sb.append("                  <UML:ModelElement.taggedValue>\n");
              sb.append("                    <UML:TaggedValue tag=\"type\" value=\"").append(escapeXml(retType)).append("\"/>\n");
              sb.append("                    <UML:TaggedValue tag=\"concurrency\" value=\"Sequential\"/>\n");
              sb.append("                    <UML:TaggedValue tag=\"position\" value=\"").append(pos).append("\"/>\n");
              sb.append("                    <UML:TaggedValue tag=\"ea_guid\" value=\"").append(opGuid).append("\"/>\n");
              sb.append("                    <UML:TaggedValue tag=\"ea_localid\" value=\"").append(pos).append("\"/>\n");
              sb.append("                  </UML:ModelElement.taggedValue>\n");

              // The editor stores parameters as "name: Type, other: Type".
              // Export each one as an input UML Parameter so EA retains the signature.
              String parameterText = m.path("parameters").asText("").trim();
              boolean hasReturn = !"void".equalsIgnoreCase(retType);
              if (!parameterText.isBlank() || hasReturn) {
                sb.append("                  <UML:BehavioralFeature.parameter>\n");
              }
              if (!parameterText.isBlank()) {
                for (String parameter : parameterText.split(",")) {
                  String[] parts = parameter.trim().split(":", 2);
                  String parameterName = parts[0].trim();
                  if (parameterName.isBlank()) continue;
                  String parameterType = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "String";
                  String parameterGuid = "EAID_" + UUID.randomUUID().toString().replace("-", "_").toUpperCase();
                  sb.append("                    <UML:Parameter name=\"").append(escapeXml(parameterName))
                      .append("\" xmi.id=\"").append(parameterGuid).append("\" kind=\"in\">\n")
                      .append("                      <UML:Parameter.type><UML:Classifier xmi.idref=\"").append(escapeXml(parameterType)).append("\"/></UML:Parameter.type>\n")
                      .append("                      <UML:ModelElement.taggedValue><UML:TaggedValue tag=\"type\" value=\"").append(escapeXml(parameterType)).append("\"/></UML:ModelElement.taggedValue>\n")
                      .append("                    </UML:Parameter>\n");
                }
              }

              if (hasReturn) {
                String paramGuid = "EAID_" + UUID.randomUUID().toString().replace("-", "_").toUpperCase();
                sb.append("                    <UML:Parameter name=\"return\" xmi.id=\"").append(paramGuid).append("\" kind=\"return\">\n");
                sb.append("                      <UML:Parameter.type><UML:Classifier xmi.idref=\"").append(escapeXml(retType)).append("\"/></UML:Parameter.type>\n");
                sb.append("                      <UML:ModelElement.taggedValue><UML:TaggedValue tag=\"type\" value=\"").append(escapeXml(retType)).append("\"/></UML:ModelElement.taggedValue>\n");
                sb.append("                    </UML:Parameter>\n");
              }
              if (!parameterText.isBlank() || hasReturn) {
                sb.append("                  </UML:BehavioralFeature.parameter>\n");
              }

              sb.append("                </UML:Operation>\n");
              pos++;
            }
          }
          sb.append("              </UML:Classifier.feature>\n");
        }

        sb.append("            </").append(elementTag).append(">\n");
      }

      // 2. Relaciones (Asociaciones, Agregaciones, Composiciones, Herencias)
      for (RelacionUML rel : relations) {
        if (rel.getElementoOrigen() == null || rel.getElementoDestino() == null) continue;
        String relGuid = "EAID_" + rel.getId().toString().replace("-", "_").toUpperCase();
        String origGuid = "EAID_" + rel.getElementoOrigen().getId().toString().replace("-", "_").toUpperCase();
        String destGuid = "EAID_" + rel.getElementoDestino().getId().toString().replace("-", "_").toUpperCase();
        String origName = rel.getElementoOrigen().getNombre();
        String destName = rel.getElementoDestino().getNombre();
        String relName = rel.getNombreRelacion() != null ? rel.getNombreRelacion() : "";

        if (rel.getTipoRelacion() == TipoRelacionUML.HERENCIA) {
          sb.append("            <UML:Generalization xmi.id=\"").append(relGuid)
            .append("\" child=\"").append(origGuid)
            .append("\" parent=\"").append(destGuid)
            .append("\" visibility=\"public\">\n");
          sb.append("              <UML:ModelElement.taggedValue>\n");
          sb.append("                <UML:TaggedValue tag=\"style\" value=\"3\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"ea_type\" value=\"Generalization\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"direction\" value=\"Source -&gt; Destination\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"linemode\" value=\"3\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"ea_sourceName\" value=\"").append(escapeXml(origName)).append("\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"ea_targetName\" value=\"").append(escapeXml(destName)).append("\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"ea_sourceType\" value=\"Class\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"ea_targetType\" value=\"Class\"/>\n");
          sb.append("              </UML:ModelElement.taggedValue>\n");
          sb.append("            </UML:Generalization>\n");
        } else if (rel.getTipoRelacion() == TipoRelacionUML.DEPENDENCIA) {
          appendDependency(sb, rel, relGuid, origGuid, destGuid, origName, destName, relName);
        } else if (rel.getTipoRelacion() == TipoRelacionUML.REALIZACION) {
          appendRealization(sb, rel, relGuid, origGuid, destGuid, origName, destName, relName);
        } else {
          String eaType = switch (rel.getTipoRelacion()) {
            case COMPOSICION -> "Composition";
            case AGREGACION -> "Aggregation";
            case DEPENDENCIA -> "Dependency";
            default -> "Association";
          };
          String aggOrig = rel.getTipoRelacion() == TipoRelacionUML.COMPOSICION ? "composite" :
                           rel.getTipoRelacion() == TipoRelacionUML.AGREGACION ? "shared" : "none";

          String multOrig = rel.getMultiplicidadOrigen() != null ? rel.getMultiplicidadOrigen().trim() : "";
          String multDest = rel.getMultiplicidadDestino() != null ? rel.getMultiplicidadDestino().trim() : "";
          String[] boundsOrig = parseMultiplicityBoundsUpperLower(multOrig);
          String[] boundsDest = parseMultiplicityBoundsUpperLower(multDest);

          sb.append("            <UML:Association name=\"").append(escapeXml(relName))
            .append("\" xmi.id=\"").append(relGuid)
            .append("\" visibility=\"public\" isRoot=\"false\" isLeaf=\"false\" isAbstract=\"false\">\n");
          sb.append("              <UML:ModelElement.taggedValue>\n");
          sb.append("                <UML:TaggedValue tag=\"style\" value=\"3\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"ea_type\" value=\"").append(eaType).append("\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"direction\" value=\"Unspecified\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"linemode\" value=\"3\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"ea_sourceName\" value=\"").append(escapeXml(origName)).append("\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"ea_targetName\" value=\"").append(escapeXml(destName)).append("\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"ea_sourceType\" value=\"Class\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"ea_targetType\" value=\"Class\"/>\n");
          sb.append("                <UML:TaggedValue tag=\"mt\" value=\"").append(escapeXml(relName)).append("\"/>\n");
          sb.append("              </UML:ModelElement.taggedValue>\n");

          sb.append("              <UML:Association.connection>\n");
          sb.append("                <UML:AssociationEnd visibility=\"public\" aggregation=\"").append(aggOrig)
            .append("\" isOrdered=\"false\" isNavigable=\"true\" type=\"").append(origGuid).append("\">\n");
          sb.append("                  <UML:AssociationEnd.multiplicity>\n");
          sb.append("                    <UML:Multiplicity><UML:Multiplicity.range>\n");
          sb.append("                      <UML:MultiplicityRange lower=\"").append(escapeXml(boundsOrig[0]))
            .append("\" upper=\"").append(escapeXml(boundsOrig[1])).append("\"/>\n");
          sb.append("                    </UML:Multiplicity.range></UML:Multiplicity>\n");
          sb.append("                  </UML:AssociationEnd.multiplicity>\n");
          sb.append("                  <UML:AssociationEnd.participant><UML:Class xmi.idref=\"").append(origGuid).append("\"/></UML:AssociationEnd.participant>\n");
          sb.append("                  <UML:ModelElement.taggedValue>\n");
          sb.append("                    <UML:TaggedValue tag=\"lowerBound\" value=\"").append(escapeXml(boundsOrig[0])).append("\"/>\n");
          sb.append("                    <UML:TaggedValue tag=\"upperBound\" value=\"").append(escapeXml(boundsOrig[1])).append("\"/>\n");
          sb.append("                    <UML:TaggedValue tag=\"sourcestyle\" value=\"Navigable=Unspecified;Owned=0;\"/>\n");
          sb.append("                    <UML:TaggedValue tag=\"ea_end\" value=\"source\"/>\n");
          sb.append("                  </UML:ModelElement.taggedValue>\n");
          sb.append("                </UML:AssociationEnd>\n");

          sb.append("                <UML:AssociationEnd visibility=\"public\" aggregation=\"none\" isOrdered=\"false\" isNavigable=\"true\" type=\"").append(destGuid).append("\">\n");
          sb.append("                  <UML:AssociationEnd.multiplicity>\n");
          sb.append("                    <UML:Multiplicity><UML:Multiplicity.range>\n");
          sb.append("                      <UML:MultiplicityRange lower=\"").append(escapeXml(boundsDest[0]))
            .append("\" upper=\"").append(escapeXml(boundsDest[1])).append("\"/>\n");
          sb.append("                    </UML:Multiplicity.range></UML:Multiplicity>\n");
          sb.append("                  </UML:AssociationEnd.multiplicity>\n");
          sb.append("                  <UML:AssociationEnd.participant><UML:Class xmi.idref=\"").append(destGuid).append("\"/></UML:AssociationEnd.participant>\n");
          sb.append("                  <UML:ModelElement.taggedValue>\n");
          sb.append("                    <UML:TaggedValue tag=\"lowerBound\" value=\"").append(escapeXml(boundsDest[0])).append("\"/>\n");
          sb.append("                    <UML:TaggedValue tag=\"upperBound\" value=\"").append(escapeXml(boundsDest[1])).append("\"/>\n");
          sb.append("                    <UML:TaggedValue tag=\"deststyle\" value=\"Navigable=Unspecified;Owned=0;\"/>\n");
          sb.append("                    <UML:TaggedValue tag=\"ea_end\" value=\"target\"/>\n");
          sb.append("                  </UML:ModelElement.taggedValue>\n");
          sb.append("                </UML:AssociationEnd>\n");
          sb.append("              </UML:Association.connection>\n");
          sb.append("            </UML:Association>\n");
        }
      }

      sb.append("          </UML:Namespace.ownedElement>\n");
      sb.append("        </UML:Package>\n");
      sb.append("      </UML:Namespace.ownedElement>\n");
      sb.append("    </UML:Model>\n");

      // 3. Elemento Diagrama nativo de Enterprise Architect vinculado al Package mediante owner y taggedValue
      sb.append("    <UML:Diagram name=\"").append(escapeXml(diagram.getNombre()))
        .append("\" xmi.id=\"").append(diagGuid)
        .append("\" diagramType=\"ClassDiagram\" owner=\"").append(pkgGuid)
        .append("\" toolName=\"Enterprise Architect 2.5\">\n");
      sb.append("      <UML:ModelElement.taggedValue>\n");
      sb.append("        <UML:TaggedValue tag=\"version\" value=\"1.0\"/>\n");
      sb.append("        <UML:TaggedValue tag=\"package\" value=\"").append(pkgGuid).append("\"/>\n");
      sb.append("        <UML:TaggedValue tag=\"type\" value=\"Logical\"/>\n");
      sb.append("        <UML:TaggedValue tag=\"swimlanes\" value=\"locked=false;orientation=0;width=0;inbar=false;names=false;color=-1;bold=false;fcol=0;tcol=-1;ofCol=-1;ufCol=-1;hl=1;ufh=0;hh=0;cls=0;bw=0;hli=0;SwimlaneFont=lfh:-13,lfw:0,lfi:0,lfu:0,lfs:0,lfface:Calibri,lfe:0,lfo:0,lfchar:1,lfop:0,lfcp:0,lfq:0,lfpf=0,lfWidth=0;\"/>\n");
      sb.append("        <UML:TaggedValue tag=\"matrixitems\" value=\"locked=false;matrixactive=false;swimlanesactive=true;kanbanactive=false;width=1;clrLine=0;\"/>\n");
      sb.append("        <UML:TaggedValue tag=\"EAStyle\" value=\"ShowPrivate=1;ShowProtected=1;ShowPublic=1;HideRelationships=0;Locked=0;Border=1;HighlightForeign=1;PackageContents=1;SequenceNotes=0;ScalePrintImage=0;PPgs.cx=0;PPgs.cy=0;DocSize.cx=800;DocSize.cy=1100;ShowDetails=0;Orientation=P;Zoom=100;ShowTags=0;OpParams=1;VisibleAttributeDetail=0;ShowOpRetType=1;ShowIcons=1;CollabNums=0;HideProps=0;ShowReqs=0;ShowCons=0;PaperSize=9;HideParents=0;UseAlias=0;HideAtts=0;HideOps=0;HideStereo=0;HideElemStereo=0;ShowTests=0;ShowMaint=0;ConnectorNotation=UML 2.1;ExplicitNavigability=0;ShowShape=1;AllDockable=0;AdvancedElementProps=1;AdvancedFeatureProps=1;AdvancedConnectorProps=1;m_bElementClassifier=1;SPT=1;ShowNotes=0;SuppressBrackets=0;SuppConnectorLabels=0;PrintPageHeadFoot=0;ShowAsList=0;\"/>\n");
      sb.append("        <UML:TaggedValue tag=\"styleex\" value=\"ExcludeRTF=0;DocAll=0;HideQuals=0;AttPkg=1;ShowTests=0;ShowMaint=0;SuppressFOC=1;MatrixActive=0;SwimlanesActive=1;KanbanActive=0;MatrixLineWidth=1;MatrixLineClr=0;MatrixLocked=0;TConnectorNotation=UML 2.1;TExplicitNavigability=0;AdvancedElementProps=1;AdvancedFeatureProps=1;AdvancedConnectorProps=1;m_bElementClassifier=1;SPT=1;MDGDgm=;STBLDgm=;ShowNotes=0;VisibleAttributeDetail=0;ShowOpRetType=1;SuppressBrackets=0;SuppConnectorLabels=0;PrintPageHeadFoot=0;ShowAsList=0;SuppressedCompartments=;Theme=:119;\"/>\n");
      sb.append("      </UML:ModelElement.taggedValue>\n");

      sb.append("      <UML:Diagram.element>\n");
      int seq = 1;
      for (ElementoUML el : elements) {
        String elGuid = "EAID_" + el.getId().toString().replace("-", "_").toUpperCase();
        int left = Math.max(30, (int) Math.round(el.getPosicionX()));
        int top = Math.max(30, (int) Math.round(el.getPosicionY()));
        int width = 200;
        int attrCount = 0;
        int methodCount = 0;
        JsonNode root = propsMap.get(el.getId());
        if (root != null) {
          attrCount = root.path("attributes").size();
          methodCount = root.path("methods").size();
        }
        int height = Math.max(110, 50 + (attrCount * 22) + (methodCount * 22));
        int right = left + width;
        int bottom = top + height;
        String duid = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

        sb.append("        <UML:DiagramElement geometry=\"Left=").append(left)
          .append(";Top=").append(top)
          .append(";Right=").append(right)
          .append(";Bottom=").append(bottom)
          .append(";\" subject=\"").append(elGuid)
          .append("\" seqno=\"").append(seq)
          .append("\" style=\"DUID=").append(duid).append(";\"/>\n");
        seq++;
      }

      for (RelacionUML rel : relations) {
        if (rel.getElementoOrigen() == null || rel.getElementoDestino() == null) continue;
        String relGuid = "EAID_" + rel.getId().toString().replace("-", "_").toUpperCase();
        sb.append("        <UML:DiagramElement geometry=\"SX=0;SY=0;EX=0;EY=0;Path=;\" subject=\"").append(relGuid).append("\" style=\";Hidden=0;\"/>\n");
      }

      sb.append("      </UML:Diagram.element>\n");
      sb.append("    </UML:Diagram>\n");
      sb.append("  </XMI.content>\n");
      sb.append("  <XMI.difference/>\n");
      sb.append("  <XMI.extensions xmi.extender=\"Enterprise Architect 2.5\">\n");
      sb.append("    <EAModel.paramSub/>\n");
      sb.append("  </XMI.extensions>\n");
      appendEnterpriseArchitectConnectors(sb, elements, propsMap, pkgGuid, diagram.getNombre(), relations);
      sb.append("</XMI>\n");

      return sb.toString();
  }

  private String convertLegacyExportToXmi21(String legacyXml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document legacy = builder.parse(new InputSource(new StringReader(legacyXml)));
      Document result = builder.getDOMImplementation().createDocument(
          "http://schema.omg.org/spec/XMI/2.1", "xmi:XMI", null);
      Element root = result.getDocumentElement();
      root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xmi", "http://schema.omg.org/spec/XMI/2.1");
      root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:uml", "http://schema.omg.org/spec/UML/2.1");
      root.setAttributeNS("http://schema.omg.org/spec/XMI/2.1", "xmi:version", "2.1");

      Element docs = result.createElementNS("http://schema.omg.org/spec/XMI/2.1", "xmi:Documentation");
      // EA uses this identity to enable its vendor-specific XMI extension,
      // including the diagram interchange data. A custom exporter identity can
      // make EA import UML classifiers while silently ignoring <diagrams>.
      docs.setAttribute("exporter", "Enterprise Architect");
      docs.setAttribute("exporterVersion", "6.5");
      root.appendChild(docs);

      Element oldModel = firstDescendant(legacy.getDocumentElement(), "UML:Model");
      if (oldModel == null) throw new IllegalArgumentException("El XMI generado no contiene un modelo UML.");
      Element model = result.createElementNS("http://schema.omg.org/spec/UML/2.1", "uml:Model");
      model.setAttributeNS("http://schema.omg.org/spec/XMI/2.1", "xmi:type", "uml:Model");
      model.setAttributeNS("http://schema.omg.org/spec/XMI/2.1", "xmi:id", getAttr(oldModel, "xmi.id", "xmi:id"));
      model.setAttribute("name", getAttr(oldModel, "name"));
      model.setAttribute("visibility", "public");
      root.appendChild(model);

      Element oldPackage = firstDescendant(oldModel, "UML:Package");
      if (oldPackage == null) throw new IllegalArgumentException("El XMI generado no contiene el paquete UML.");
      Element pkg = result.createElementNS("http://schema.omg.org/spec/UML/2.1", "packagedElement");
      pkg.setAttributeNS("http://schema.omg.org/spec/XMI/2.1", "xmi:type", "uml:Package");
      pkg.setAttributeNS("http://schema.omg.org/spec/XMI/2.1", "xmi:id", getAttr(oldPackage, "xmi.id", "xmi:id"));
      pkg.setAttribute("name", getAttr(oldPackage, "name"));
      model.appendChild(pkg);

      Element owned = firstElement(oldPackage, "UML:Namespace.ownedElement");
      if (owned != null) {
        NodeList oldItems = owned.getChildNodes();
        for (int i = 0; i < oldItems.getLength(); i++) {
          if (oldItems.item(i) instanceof Element oldItem) {
            convertUml21Element(oldItem, pkg, result, pkg);
          }
        }
      }

      Element oldExtension = firstElement(legacy.getDocumentElement(), "xmi:Extension");
      if (oldExtension != null) {
        Element extension = (Element) result.importNode(oldExtension, true);
        remapXmiNamespace(extension);
        root.appendChild(extension);
        appendXmi21PackageExtension(result, extension, legacy.getDocumentElement());
        appendXmi21DiagramExtension(result, extension, legacy.getDocumentElement());
      }

      javax.xml.transform.Transformer transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
      transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
      java.io.StringWriter writer = new java.io.StringWriter();
      transformer.transform(new javax.xml.transform.dom.DOMSource(result), new javax.xml.transform.stream.StreamResult(writer));
      return writer.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("No se pudo generar el XMI 2.1 para Enterprise Architect.", ex);
    }
  }

  private void convertUml21Element(Element old, Element parent, Document doc, Element packageElement) {
    String tag = old.getTagName();
    if (tag.equals("UML:Namespace.ownedElement") || tag.equals("UML:Classifier.feature")
        || tag.equals("UML:BehavioralFeature.parameter")) {
      NodeList children = old.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        if (children.item(i) instanceof Element child) convertUml21Element(child, parent, doc, packageElement);
      }
      return;
    }
    String type = switch (tag) {
      case "UML:Class" -> "Class";
      case "UML:Interface" -> "Interface";
      case "UML:Enumeration" -> "Enumeration";
      case "UML:Attribute" -> "Property";
      case "UML:Operation" -> "Operation";
      case "UML:Parameter" -> "Parameter";
      case "UML:Association" -> "Association";
      case "UML:Generalization" -> "Generalization";
      case "UML:Dependency" -> "Dependency";
      case "UML:Abstraction" -> "Abstraction";
      default -> null;
    };
    if (type == null) return;

    if (tag.equals("UML:Association")) {
      Element assoc = createPackagedElement(doc, parent, old, type);
      Element connection = firstElement(old, "UML:Association.connection");
      if (connection != null) {
        int endIndex = 0;
        NodeList ends = connection.getChildNodes();
        for (int i = 0; i < ends.getLength(); i++) {
          if (!(ends.item(i) instanceof Element end) || !end.getTagName().equals("UML:AssociationEnd")) continue;
          String endId = getAttr(old, "xmi.id", "xmi:id") + "_END_" + (++endIndex);
          Element ownedEnd = doc.createElementNS("http://schema.omg.org/spec/UML/2.1", "ownedEnd");
          setXmi(ownedEnd, "type", "uml:Property");
          setXmi(ownedEnd, "id", endId);
          ownedEnd.setAttribute("type", getAttr(end, "type"));
          ownedEnd.setAttribute("aggregation", getAttr(end, "aggregation"));
          String[] bounds = endMultiplicity(end);
          appendLiteral(ownedEnd, "lowerValue", "LiteralInteger", bounds[0]);
          appendLiteral(ownedEnd, "upperValue", "LiteralUnlimitedNatural", bounds[1]);
          assoc.appendChild(ownedEnd);
          Element member = doc.createElementNS("http://schema.omg.org/spec/UML/2.1", "memberEnd");
          setXmi(member, "idref", endId);
          assoc.insertBefore(member, ownedEnd);
        }
      }
      return;
    }
    if (tag.equals("UML:Generalization")) {
      Element generalization = createPackagedElement(doc, parent, old, type);
      generalization.setAttribute("specific", getAttr(old, "child"));
      generalization.setAttribute("general", getAttr(old, "parent"));
      return;
    }
    if (tag.equals("UML:Dependency") || tag.equals("UML:Abstraction")) {
      Element dependency = createPackagedElement(doc, parent, old, type);
      dependency.setAttribute("client", getAttr(old, "client"));
      dependency.setAttribute("supplier", getAttr(old, "supplier"));
      return;
    }

    String targetTag = switch (tag) {
      case "UML:Class", "UML:Interface", "UML:Enumeration" -> "packagedElement";
      case "UML:Attribute" -> "ownedAttribute";
      case "UML:Operation" -> "ownedOperation";
      default -> "ownedParameter";
    };
    Element converted = doc.createElementNS("http://schema.omg.org/spec/UML/2.1", targetTag);
    setXmi(converted, "type", "uml:" + type);
    setXmi(converted, "id", getAttr(old, "xmi.id", "xmi:id"));
    copyIfPresent(old, converted, "name");
    copyIfPresent(old, converted, "visibility");
    copyIfPresent(old, converted, "isAbstract");
    if (tag.equals("UML:Parameter")) {
      String direction = getAttr(old, "kind");
      if (!direction.isBlank()) converted.setAttribute("direction", direction);
    }
    parent.appendChild(converted);

    if (tag.equals("UML:Attribute") || tag.equals("UML:Parameter")) {
      String rawType = getAttributeType(old);
      if (!rawType.isBlank()) {
        String typeId = "DATATYPE_" + sanitizeId(rawType);
        converted.setAttribute("type", typeId);
        ensurePrimitiveType(packageElement, doc, typeId, rawType);
      }
    }
    NodeList children = old.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element child) convertUml21Element(child, converted, doc, packageElement);
    }
  }

  private Element createPackagedElement(Document doc, Element parent, Element old, String type) {
    Element item = doc.createElementNS("http://schema.omg.org/spec/UML/2.1", "packagedElement");
    setXmi(item, "type", "uml:" + type);
    setXmi(item, "id", getAttr(old, "xmi.id", "xmi:id"));
    copyIfPresent(old, item, "name");
    parent.appendChild(item);
    return item;
  }

  private void ensurePrimitiveType(Element pkg, Document doc, String id, String name) {
    NodeList children = pkg.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element e && getAttr(e, "xmi:id", "xmi.id").equals(id)) return;
    }
    Element primitive = doc.createElementNS("http://schema.omg.org/spec/UML/2.1", "packagedElement");
    setXmi(primitive, "type", "uml:PrimitiveType");
    setXmi(primitive, "id", id);
    primitive.setAttribute("name", name);
    pkg.appendChild(primitive);
  }

  private String getAttributeType(Element feature) {
    Element taggedValues = firstElement(feature, "UML:ModelElement.taggedValue");
    if (taggedValues != null) {
      NodeList children = taggedValues.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        if (children.item(i) instanceof Element tag && getAttr(tag, "tag").equals("type")) return getAttr(tag, "value");
      }
    }
    return "";
  }

  private String[] endMultiplicity(Element end) {
    Element mult = firstElement(end, "UML:AssociationEnd.multiplicity");
    Element range = mult == null ? null : firstDescendant(mult, "UML:MultiplicityRange");
    String lower = range == null ? "1" : getAttr(range, "lower");
    String upper = range == null ? "1" : getAttr(range, "upper");
    return new String[]{lower.isBlank() ? "1" : lower, upper.equals("-1") ? "*" : (upper.isBlank() ? "1" : upper)};
  }

  private void appendLiteral(Element parent, String tag, String type, String value) {
    Element literal = parent.getOwnerDocument().createElementNS("http://schema.omg.org/spec/UML/2.1", tag);
    setXmi(literal, "type", "uml:" + type);
    setXmi(literal, "id", "LIT_" + UUID.randomUUID().toString().replace("-", ""));
    literal.setAttribute("value", value);
    parent.appendChild(literal);
  }

  private void appendXmi21DiagramExtension(Document doc, Element extension, Element legacyRoot) {
    NodeList oldDiagrams = legacyRoot.getElementsByTagName("UML:Diagram");
    if (oldDiagrams.getLength() == 0) return;
    Element oldPackage = firstDescendant(legacyRoot, "UML:Package");
    String packageId = oldPackage == null ? "" : getAttr(oldPackage, "xmi.id", "xmi:id");
    Element diagrams = doc.createElement("diagrams");
    extension.appendChild(diagrams);
    for (int i = 0; i < oldDiagrams.getLength(); i++) {
      Element old = (Element) oldDiagrams.item(i);
      Element diagram = doc.createElement("diagram");
      diagram.setAttribute("xmi:id", getAttr(old, "xmi.id", "xmi:id"));
      diagrams.appendChild(diagram);

      // EA's XMI 2.1 importer reads diagram ownership and type from these child
      // elements. Putting name/type directly on <diagram> leaves the imported
      // diagram detached from the package; EA then creates an empty canvas.
      Element model = doc.createElement("model");
      model.setAttribute("package", packageId);
      model.setAttribute("localID", "1");
      model.setAttribute("owner", packageId);
      diagram.appendChild(model);

      Element properties = doc.createElement("properties");
      properties.setAttribute("name", getAttr(old, "name"));
      properties.setAttribute("type", "Logical");
      diagram.appendChild(properties);

      Element project = doc.createElement("project");
      project.setAttribute("author", "Proyecto CASE");
      project.setAttribute("version", "1.0");
      diagram.appendChild(project);

      appendDiagramTaggedValue(doc, old, diagram, "EAStyle", "style1");
      appendDiagramTaggedValue(doc, old, diagram, "styleex", "style2");
      appendDiagramTaggedValue(doc, old, diagram, "swimlanes", "swimlanes");
      appendDiagramTaggedValue(doc, old, diagram, "matrixitems", "matrixitems");
      diagram.appendChild(doc.createElement("extendedProperties"));

      Element elements = doc.createElement("elements");
      diagram.appendChild(elements);
      NodeList oldElements = old.getElementsByTagName("UML:DiagramElement");
      for (int j = 0; j < oldElements.getLength(); j++) {
        Element oldElement = (Element) oldElements.item(j);
        Element element = doc.createElement("element");
        copyIfPresent(oldElement, element, "subject");
        copyIfPresent(oldElement, element, "geometry");
        copyIfPresent(oldElement, element, "seqno");
        copyIfPresent(oldElement, element, "style");
        elements.appendChild(element);
      }
    }
  }

  private void appendXmi21PackageExtension(Document doc, Element extension, Element legacyRoot) {
    Element oldPackage = firstDescendant(legacyRoot, "UML:Package");
    Element elements = firstElement(extension, "elements");
    if (oldPackage == null || elements == null) return;

    String packageId = getAttr(oldPackage, "xmi.id", "xmi:id");
    NodeList existing = elements.getElementsByTagName("element");
    for (int i = 0; i < existing.getLength(); i++) {
      if (packageId.equals(getAttr((Element) existing.item(i), "xmi:idref"))) return;
    }

    // EA expects the imported package itself in its vendor extension as well
    // as in the UML model. Without this package record it may import all
    // classifiers but silently discard the package's diagram records.
    Element packageEntry = doc.createElement("element");
    packageEntry.setAttribute("xmi:idref", packageId);
    packageEntry.setAttribute("xmi:type", "uml:Package");
    packageEntry.setAttribute("name", getAttr(oldPackage, "name"));
    packageEntry.setAttribute("scope", "public");
    elements.insertBefore(packageEntry, elements.getFirstChild());

    String eaPackageId = packageId.startsWith("EAPK_")
        ? "EAID_" + packageId.substring("EAPK_".length()) : packageId;
    Element model = doc.createElement("model");
    model.setAttribute("package2", eaPackageId);
    model.setAttribute("package", packageId);
    model.setAttribute("tpos", "0");
    model.setAttribute("ea_localid", "0");
    model.setAttribute("ea_eleType", "package");
    packageEntry.appendChild(model);

    Element properties = doc.createElement("properties");
    properties.setAttribute("isSpecification", "false");
    properties.setAttribute("sType", "Package");
    properties.setAttribute("nType", "0");
    properties.setAttribute("scope", "public");
    packageEntry.appendChild(properties);

    Element project = doc.createElement("project");
    project.setAttribute("author", "Proyecto CASE");
    project.setAttribute("version", "1.0");
    project.setAttribute("phase", "1.0");
    project.setAttribute("complexity", "1");
    project.setAttribute("status", "Proposed");
    packageEntry.appendChild(project);
    packageEntry.appendChild(doc.createElement("code"));
    packageEntry.appendChild(doc.createElement("style"));
    packageEntry.appendChild(doc.createElement("tags"));
    packageEntry.appendChild(doc.createElement("xrefs"));

    Element extendedProperties = doc.createElement("extendedProperties");
    extendedProperties.setAttribute("tagged", "0");
    extendedProperties.setAttribute("package_name", getAttr(oldPackage, "name"));
    packageEntry.appendChild(extendedProperties);
    Element packageProperties = doc.createElement("packageproperties");
    packageProperties.setAttribute("version", "1.0");
    packageEntry.appendChild(packageProperties);
    packageEntry.appendChild(doc.createElement("paths"));
    Element flags = doc.createElement("flags");
    flags.setAttribute("iscontrolled", "FALSE");
    flags.setAttribute("isprotected", "FALSE");
    flags.setAttribute("usedtd", "FALSE");
    flags.setAttribute("logxml", "FALSE");
    flags.setAttribute("packageFlags", "isModel=0;");
    packageEntry.appendChild(flags);
  }

  private void appendDiagramTaggedValue(Document doc, Element oldDiagram, Element diagram,
      String taggedValueName, String eaElementName) {
    NodeList taggedValues = oldDiagram.getElementsByTagName("UML:TaggedValue");
    String value = "";
    for (int i = 0; i < taggedValues.getLength(); i++) {
      Element taggedValue = (Element) taggedValues.item(i);
      if (taggedValueName.equals(getAttr(taggedValue, "tag"))) {
        value = getAttr(taggedValue, "value");
        break;
      }
    }
    Element child = doc.createElement(eaElementName);
    child.setAttribute("value", value);
    diagram.appendChild(child);
  }

  private Element firstElement(Element parent, String tagName) {
    if (parent == null) return null;
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element child && child.getTagName().equals(tagName)) return child;
    }
    return null;
  }

  private Element firstDescendant(Element parent, String tagName) {
    if (parent == null) return null;
    NodeList matches = parent.getElementsByTagName(tagName);
    return matches.getLength() == 0 ? null : (Element) matches.item(0);
  }

  private void setXmi(Element element, String localName, String value) {
    element.setAttributeNS("http://schema.omg.org/spec/XMI/2.1", "xmi:" + localName, value);
  }

  private void remapXmiNamespace(Element element) {
    String oldNamespace = "http://www.omg.org/XMI";
    String newNamespace = "http://schema.omg.org/spec/XMI/2.1";
    NodeList nodes = element.getElementsByTagName("*");
    List<Element> all = new ArrayList<>();
    all.add(element);
    for (int i = 0; i < nodes.getLength(); i++) all.add((Element) nodes.item(i));
    for (Element current : all) {
      org.w3c.dom.NamedNodeMap attributes = current.getAttributes();
      List<String[]> remapped = new ArrayList<>();
      for (int i = 0; i < attributes.getLength(); i++) {
        Node attr = attributes.item(i);
        if (oldNamespace.equals(attr.getNamespaceURI()) && attr.getNodeName().startsWith("xmi:")) {
          remapped.add(new String[]{attr.getLocalName(), attr.getNodeValue()});
        } else if (attr.getNodeName().equals("xmi:type") && attr.getNodeValue().startsWith("UML:")) {
          remapped.add(new String[]{"type", attr.getNodeValue().replaceFirst("^UML:", "uml:")});
        }
      }
      for (String[] attr : remapped) {
        if (current.hasAttribute("xmi:" + attr[0])) current.removeAttribute("xmi:" + attr[0]);
        if (oldNamespace.equals(current.getAttributeNode("xmi:" + attr[0]) == null ? null
            : current.getAttributeNode("xmi:" + attr[0]).getNamespaceURI())) {
          current.removeAttributeNS(oldNamespace, attr[0]);
        }
        current.setAttributeNS(newNamespace, "xmi:" + attr[0], attr[1]);
      }
      if (current.hasAttribute("xmlns:xmi")) {
        current.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xmi", newNamespace);
      }
    }
  }

  private void copyIfPresent(Element source, Element target, String name) {
    String value = getAttr(source, name);
    if (!value.isBlank()) target.setAttribute(name, value);
  }

  private void appendDependency(StringBuilder sb, RelacionUML rel, String relGuid,
                                String origGuid, String destGuid, String origName,
                                String destName, String relName) {
    sb.append("            <UML:Dependency xmi.id=\"").append(relGuid)
        .append("\" client=\"").append(origGuid)
        .append("\" supplier=\"").append(destGuid)
        .append("\" visibility=\"public\">\n");
    appendRelationTaggedValues(sb, "Dependency", "Source -> Destination", origName, destName, relName);
    sb.append("            </UML:Dependency>\n");
  }

  private void appendRealization(StringBuilder sb, RelacionUML rel, String relGuid,
                                 String origGuid, String destGuid, String origName,
                                 String destName, String relName) {
    // UML 1.3 models realization as an Abstraction; the EA type tag makes the
    // intended connector explicit for tools that import the EA extension.
    sb.append("            <UML:Abstraction xmi.id=\"").append(relGuid)
        .append("\" client=\"").append(origGuid)
        .append("\" supplier=\"").append(destGuid)
        .append("\" visibility=\"public\">\n");
    appendRelationTaggedValues(sb, "Realization", "Source -> Destination", origName, destName, relName);
    sb.append("            </UML:Abstraction>\n");
  }

  private void appendRelationTaggedValues(StringBuilder sb, String eaType, String direction,
                                          String origName, String destName, String relName) {
    sb.append("              <UML:ModelElement.taggedValue>\n")
        .append("                <UML:TaggedValue tag=\"style\" value=\"3\"/>\n")
        .append("                <UML:TaggedValue tag=\"ea_type\" value=\"").append(eaType).append("\"/>\n")
        .append("                <UML:TaggedValue tag=\"direction\" value=\"").append(escapeXml(direction)).append("\"/>\n")
        .append("                <UML:TaggedValue tag=\"linemode\" value=\"3\"/>\n")
        .append("                <UML:TaggedValue tag=\"ea_sourceName\" value=\"").append(escapeXml(origName)).append("\"/>\n")
        .append("                <UML:TaggedValue tag=\"ea_targetName\" value=\"").append(escapeXml(destName)).append("\"/>\n")
        .append("                <UML:TaggedValue tag=\"ea_sourceType\" value=\"Class\"/>\n")
        .append("                <UML:TaggedValue tag=\"ea_targetType\" value=\"Class\"/>\n")
        .append("                <UML:TaggedValue tag=\"mt\" value=\"").append(escapeXml(relName)).append("\"/>\n")
        .append("              </UML:ModelElement.taggedValue>\n");
  }

  private void appendEnterpriseArchitectConnectors(StringBuilder sb, List<ElementoUML> elements,
                                                    Map<UUID, JsonNode> propsMap, String packageGuid,
                                                    String packageName, List<RelacionUML> relations) {
    sb.append("  <xmi:Extension extender=\"Enterprise Architect\" extenderID=\"6.5\">\n")
        .append("    <elements>\n");
    int elementLocalId = 1;
    for (ElementoUML element : elements) {
      String elementGuid = toEaId(element.getId());
      String eaType = switch (element.getTipoElemento()) {
        case INTERFAZ -> "Interface";
        case ENUMERACION -> "Enumeration";
        default -> "Class";
      };
      JsonNode root = propsMap.get(element.getId());
      JsonNode attrs = root == null ? null : root.path("attributes");
      sb.append("      <element xmi:idref=\"").append(elementGuid).append("\" xmi:type=\"UML:").append(eaType).append("\" name=\"")
          .append(escapeXml(element.getNombre())).append("\" scope=\"public\">\n")
          .append("        <model package=\"").append(packageGuid).append("\" tpos=\"").append(elementLocalId)
          .append("\" ea_localid=\"").append(elementLocalId).append("\" ea_eleType=\"element\"/>\n")
          .append("        <properties isSpecification=\"false\" sType=\"").append(eaType)
          .append("\" nType=\"0\" scope=\"public\"/>\n")
          .append("        <project author=\"Herramienta CASE\" version=\"1.0\" phase=\"1.0\" complexity=\"1\" status=\"Proposed\"/>\n")
          .append("        <code gentype=\"Java\"/>\n")
          .append("        <style appearance=\"BackColor=-1;BorderColor=-1;BorderWidth=-1;FontColor=-1;VSwimLanes=1;HSwimLanes=1;BorderStyle=0;\"/>\n");
      if (attrs != null && attrs.isArray()) {
        sb.append("        <attributes>\n");
        for (int attrIndex = attrs.size() - 1; attrIndex >= 0; attrIndex--) {
          JsonNode attr = attrs.get(attrIndex);
          int position = attrIndex;
          String name = attr.path("name").asText().trim();
          if (name.isBlank()) continue;
          UUID attrUuid = attributeUuid(element.getId(), attr, position);
          String attrGuid = toEaId(attrUuid);
          String rawType = attr.path("type").asText("String").trim();
          if (rawType.isBlank()) rawType = "String";
          String visibility = eaVisibility(attr.path("visibility").asText("+"));
          int attributeLocalId = elementLocalId + position + 1;
          sb.append("          <attribute xmi:idref=\"").append(attrGuid).append("\" xmi:type=\"UML:Attribute\" name=\"")
              .append(escapeXml(name)).append("\" scope=\"").append(visibility).append("\">\n")
              .append("            <initial/>\n")
              .append("            <model ea_localid=\"").append(attributeLocalId).append("\" ea_guid=\"{")
              .append(attrUuid).append("}\"/>\n")
              .append("            <properties type=\"").append(escapeXml(rawType)).append("\" scope=\"").append(visibility).append("\"/>\n")
              .append("            <coords ordered=\"0\" scale=\"0\"/>\n")
              .append("            <containment containment=\"Not Specified\" position=\"").append(position).append("\"/>\n")
              .append("            <stereotype/>\n          </attribute>\n");
        }
        sb.append("        </attributes>\n");
      }
      sb.append("        <tags/>\n        <xrefs/>\n")
          .append("        <extendedProperties tagged=\"0\" package_name=\"").append(escapeXml(packageName))
          .append("\"/>\n      </element>\n");
      elementLocalId += 1 + (attrs != null && attrs.isArray() ? attrs.size() : 0);
    }
    sb.append("    </elements>\n    <connectors>\n");
    for (RelacionUML rel : relations) {
      if (rel.getElementoOrigen() == null || rel.getElementoDestino() == null) continue;
      String relGuid = "EAID_" + rel.getId().toString().replace("-", "_").toUpperCase();
      String origGuid = "EAID_" + rel.getElementoOrigen().getId().toString().replace("-", "_").toUpperCase();
      String destGuid = "EAID_" + rel.getElementoDestino().getId().toString().replace("-", "_").toUpperCase();
      String eaType = switch (rel.getTipoRelacion()) {
        case HERENCIA -> "Generalization";
        case COMPOSICION -> "Composition";
        case AGREGACION -> "Aggregation";
        case DEPENDENCIA -> "Dependency";
        case REALIZACION -> "Realization";
        default -> "Association";
      };
      String sourceAggregation = rel.getTipoRelacion() == TipoRelacionUML.COMPOSICION ? "composite" :
          rel.getTipoRelacion() == TipoRelacionUML.AGREGACION ? "shared" : "none";
      String name = rel.getNombreRelacion() == null ? "" : rel.getNombreRelacion();
      String sourceName = rel.getElementoOrigen().getNombre();
      String targetName = rel.getElementoDestino().getNombre();
      String sourceRoleMultiplicity = normalizeMultiplicity(rel.getMultiplicidadOrigen());
      String targetRoleMultiplicity = normalizeMultiplicity(rel.getMultiplicidadDestino());
      String direction = rel.getTipoRelacion() == TipoRelacionUML.DEPENDENCIA || rel.getTipoRelacion() == TipoRelacionUML.REALIZACION
          ? "Source -&gt; Destination" : "Unspecified";
      sb.append("      <connector xmi:idref=\"").append(relGuid).append("\">\n")
         .append("        <properties name=\"").append(escapeXml(name)).append("\" ea_type=\"").append(eaType)
         .append("\" direction=\"").append(direction).append("\"/>\n")
          .append("        <source xmi:idref=\"").append(origGuid).append("\">\n")
          .append("          <model type=\"Class\" name=\"").append(escapeXml(sourceName)).append("\"/>\n")
          .append("          <role visibility=\"Public\" targetScope=\"instance\"/>\n")
          .append("          <type multiplicity=\"").append(escapeXml(sourceRoleMultiplicity)).append("\" aggregation=\"").append(sourceAggregation).append("\" containment=\"Unspecified\"/>\n")
          .append("          <constraints/><modifiers isOrdered=\"false\" changeable=\"none\" isNavigable=\"true\"/>\n")
          .append("          <style value=\"Union=0;Derived=0;AllowDuplicates=0;Owned=0;Navigable=Navigable;\"/>\n")
          .append("          <documentation/><xrefs/><tags/>\n        </source>\n")
          .append("        <target xmi:idref=\"").append(destGuid).append("\">\n")
          .append("          <model type=\"Class\" name=\"").append(escapeXml(targetName)).append("\"/>\n")
          .append("          <role visibility=\"Public\" targetScope=\"instance\"/>\n")
          .append("          <type multiplicity=\"").append(escapeXml(targetRoleMultiplicity)).append("\" aggregation=\"none\" containment=\"Unspecified\"/>\n")
          .append("          <constraints/><modifiers isOrdered=\"false\" changeable=\"none\" isNavigable=\"true\"/>\n")
          .append("          <style value=\"Union=0;Derived=0;AllowDuplicates=0;Owned=0;Navigable=Navigable;\"/>\n")
          .append("          <documentation/><xrefs/><tags/>\n        </target>\n")
          .append("        <model/>\n")
          .append("        <appearance linemode=\"3\" linecolor=\"-1\" linewidth=\"0\"/>\n")
          .append("        <labels mt=\"").append(escapeXml(name)).append("\"/>\n")
          .append("      </connector>\n");
    }
    sb.append("    </connectors>\n  </xmi:Extension>\n");
  }

  private UUID attributeUuid(UUID classId, JsonNode attribute, int position) {
    String sourceId = attribute.path("id").asText("").trim();
    try {
      return UUID.fromString(sourceId);
    } catch (IllegalArgumentException ignored) {
      String name = attribute.path("name").asText("");
      return UUID.nameUUIDFromBytes((classId + ":" + sourceId + ":" + name + ":" + position)
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  private String toEaId(UUID id) {
    return "EAID_" + id.toString().replace("-", "_").toUpperCase(Locale.ROOT);
  }

  private String eaVisibility(String visibility) {
    return switch (visibility) {
      case "-" -> "Private";
      case "#" -> "Protected";
      case "~" -> "Package";
      default -> "Public";
    };
  }

  private String normalizeMultiplicity(String multiplicity) {
    if (multiplicity == null || multiplicity.isBlank()) return "1";
    return multiplicity.trim();
  }

  public DiagramFullView importXmi(UUID projectId, UUID diagramId, String email, String xmlContent) {
    return importXmi(projectId, diagramId, email, xmlContent, null);
  }

  public DiagramFullView importXmi(UUID projectId, UUID diagramId, String email, String xmlContent, Long expectedRevision) {
    umlService.acquireLock(projectId, diagramId, email);
    try {
      projectService.requireCanEdit(projectId, email);
      DiagramaClase diagram = validateDiagram(projectId, diagramId);
      long newRev = umlService.reserveNextRevision(diagramId, expectedRevision);
      diagram.setRevision(newRev);

      // 1. Limpiar BOM y normalizar encoding del prólogo XML para evitar fallos de parser con windows-1252 de EA
      String cleanContent = xmlContent;
      if (cleanContent.startsWith("\uFEFF")) {
        cleanContent = cleanContent.substring(1);
      }
      cleanContent = cleanContent.replaceFirst("(?i)encoding=[\"'][^\"']+[\"']", "encoding=\"UTF-8\"");

      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setIgnoringComments(true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document doc = builder.parse(new InputSource(new StringReader(cleanContent)));

      // 2. Recorrer de forma recursiva todo el árbol DOM para soportar cualquier estructura de Enterprise Architect
      List<Element> allElements = new ArrayList<>();
      collectAllElements(doc.getDocumentElement(), allElements);

      // 3. Primer paso: Mapear identificadores a nombres (para DataPoint/DataType/Class/Classifier de EA)
      Map<String, String> idToNameMap = new HashMap<>();
      Map<String, Element> idToDomElementMap = new HashMap<>();
      Map<String, Element> idrefToExtensionElementMap = new HashMap<>();

      for (Element el : allElements) {
        String id = getAttr(el, "xmi:id", "xmi.id", "id", "xmiId", "guid", "GUID");
        String name = getAttr(el, "name", "Name", "label");
        if (!id.isBlank()) {
          idToDomElementMap.put(id, el);
          if (!name.isBlank()) {
            idToNameMap.put(id, name.trim());
          }
        }
        // EA a veces define los nombres en extensiones <element xmi:idref="EAID_..." name="..." ...>
        String idref = getAttr(el, "xmi:idref", "xmi.idref", "idref");
        if (!idref.isBlank()) {
          idrefToExtensionElementMap.put(idref, el);
          if (!name.isBlank()) {
            idToNameMap.putIfAbsent(idref, name.trim());
          }
        }
      }

      Map<String, ElementoUML> idToElementMap = new HashMap<>();
      Map<String, ElementoUML> nameToElementMap = new HashMap<>();
      Set<String> processedClassNames = new HashSet<>();
      // UML and the EA extension can describe the same relation twice. Use the
      // relation ID for that duplicate representation, never the endpoint pair:
      // two valid relations may connect the same classes.
      Set<String> processedRelationIds = new HashSet<>();
      Map<String, RelacionUML> importedRelationsById = new HashMap<>();

      // Mapear coordenadas de diagramas si existen (Enterprise Architect o herramientas compatibles)
      Map<String, double[]> idToPositionMap = new HashMap<>();
      for (Element dEl : allElements) {
        String local = getLocalName(dEl);
        if ("element".equalsIgnoreCase(local)) {
          String subject = getAttr(dEl, "subject");
          String geom = getAttr(dEl, "geometry");
          if (!subject.isBlank() && !geom.isBlank()) {
            double[] pos = parseCoordinatesFromGeometry(geom);
            if (pos != null) {
              idToPositionMap.put(subject, pos);
            }
          }
        }
      }

      double curX = 100.0;
      double curY = 100.0;
      int count = 0;

      // 4. Segundo paso: Identificar y crear Clases, Tablas, Interfaces y Enumeraciones
      for (Element el : allElements) {
        if (!isClassOrTableElement(el)) continue;

        String xmiId = getAttr(el, "xmi:id", "xmi.id", "id", "xmiId", "xmi:idref", "xmi.idref", "idref");
        String name = getAttr(el, "name", "Name");
        if (name.isBlank() && !xmiId.isBlank()) {
          name = idToNameMap.getOrDefault(xmiId, "");
        }
        if (name.isBlank()) {
          // Intentar obtener de nodo hijo <name>
          List<Element> nameNodes = findChildElementsByTagNames(el, "name", "Name");
          if (!nameNodes.isEmpty()) {
            name = nameNodes.get(0).getTextContent().trim();
          }
        }

        if (name.isBlank()) continue;
        String normalizedName = name.trim();
        // Ignorar clases raíz de sistema de EA (EARootClass) y tipos de datos primitivos (long, Integer, String, etc.)
        if (isPrimitiveOrSystemElement(normalizedName)) {
          continue;
        }
        if (processedClassNames.contains(normalizedName.toLowerCase())) {
          // Ya procesada para evitar duplicaciones por extensiones de EA
          continue;
        }

        TipoElementoUML tipo = determineElementType(el);

        // Extraer atributos / columnas de la clase o tabla
        ObjectNode propNode = objectMapper.createObjectNode();
        ArrayNode attrs = propNode.putArray("attributes");

        List<Element> attrElements = findDescendantElementsByTagNames(el,
            "ownedAttribute", "Attribute", "attribute", "column", "property");

        // Si no se encontraron atributos en el elemento principal pero existe en la extensión de EA
        if (attrElements.isEmpty() && !xmiId.isBlank() && idrefToExtensionElementMap.containsKey(xmiId)) {
          Element extEl = idrefToExtensionElementMap.get(xmiId);
          attrElements = findDescendantElementsByTagNames(extEl, "attribute", "Attribute");
        }

        for (Element a : attrElements) {
          // Evitar propiedades que representen extremos de asociación en UML 2.1
          if (!getAttr(a, "association").isBlank()) continue;

          String aName = getAttr(a, "name", "Name");
          if (aName.isBlank()) continue;

          String aVisRaw = getAttr(a, "visibility", "Visibility", "scope", "Scope");
          String visibility = switch (aVisRaw.toLowerCase()) {
            case "private", "-" -> "-";
            case "protected", "#" -> "#";
            case "package", "package_private", "~" -> "~";
            default -> "+";
          };

          String attrType = resolveAttributeType(a, idToNameMap);

          attrs.addObject()
              .put("id", UUID.randomUUID().toString())
              .put("visibility", visibility)
              .put("name", aName.trim())
              .put("type", attrType);
        }

        // Extraer métodos / operaciones
        ArrayNode methods = propNode.putArray("methods");
        List<Element> opElements = findDescendantElementsByTagNames(el,
            "ownedOperation", "Operation", "operation", "method");

        // Si no se encontraron operaciones en el elemento principal pero existe en la extensión de EA
        if (opElements.isEmpty() && !xmiId.isBlank() && idrefToExtensionElementMap.containsKey(xmiId)) {
          Element extEl = idrefToExtensionElementMap.get(xmiId);
          opElements = findDescendantElementsByTagNames(extEl, "operation", "Operation");
        }

        for (Element op : opElements) {
          String opName = getAttr(op, "name", "Name");
          if (opName.isBlank()) continue;

          String opVisRaw = getAttr(op, "visibility", "Visibility", "scope", "Scope");
          String visibility = switch (opVisRaw.toLowerCase()) {
            case "private", "-" -> "-";
            case "protected", "#" -> "#";
            case "package", "~" -> "~";
            default -> "+";
          };

          String returnType = resolveOperationReturnType(op, idToNameMap);
          String params = resolveOperationParameters(op, idToNameMap);

          methods.addObject()
              .put("id", UUID.randomUUID().toString())
              .put("visibility", visibility)
              .put("name", opName.trim())
              .put("returnType", returnType)
              .put("parameters", params);
        }

        // Posicionamiento: usar coordenadas geométricas si existen, o cuadrícula ordenada
        double posX = curX + ((count % 4) * 280.0);
        double posY = curY + ((count / 4) * 240.0);
        if (!xmiId.isBlank()) {
          if (idToPositionMap.containsKey(xmiId)) {
            double[] pos = idToPositionMap.get(xmiId);
            posX = pos[0];
            posY = pos[1];
          } else if (xmiId.startsWith("el_") && idToPositionMap.containsKey(xmiId.substring(3))) {
            double[] pos = idToPositionMap.get(xmiId.substring(3));
            posX = pos[0];
            posY = pos[1];
          } else if (idToPositionMap.containsKey("el_" + xmiId)) {
            double[] pos = idToPositionMap.get("el_" + xmiId);
            posX = pos[0];
            posY = pos[1];
          }
        }

        ElementoUML nuevo = new ElementoUML(
            diagram,
            tipo,
            normalizedName,
            posX,
            posY,
            propNode.toString()
        );
        nuevo = elementRepo.save(nuevo);

        if (!xmiId.isBlank()) {
          idToElementMap.put(xmiId, nuevo);
          idToElementMap.put("el_" + nuevo.getId(), nuevo);
        }
        nameToElementMap.put(normalizedName.toLowerCase(), nuevo);
        processedClassNames.add(normalizedName.toLowerCase());
        count++;
      }

      // 5. Tercer paso: Importar Generalizaciones / Herencias (XMI 1.1 y XMI 2.1)
      for (Element el : allElements) {
        String local = getLocalName(el);
        String type = getAttr(el, "xmi:type", "type");

        if ("Generalization".equalsIgnoreCase(local) || type.toLowerCase().endsWith("generalization")) {
          String childId = getAttr(el, "specific", "subtype", "child");
          String parentId = getAttr(el, "general", "supertype", "parent");

          // En EA XMI 1.1 o UML 2.1 a veces el child es el elemento contenedor
          if (childId.isBlank() && el.getParentNode() instanceof Element parentEl) {
            childId = getAttr(parentEl, "xmi:id", "xmi.id", "id");
          }

          // Si parentId viene en nodo hijo <UML:Generalization.parent><UML:Class xmi.idref="..."/></UML:Generalization.parent>
          if (parentId.isBlank()) {
            List<Element> parentRefs = findDescendantElementsByTagNames(el, "Classifier", "Class");
            for (Element pr : parentRefs) {
              String ref = getAttr(pr, "xmi:idref", "xmi.idref", "idref");
              if (!ref.isBlank()) {
                parentId = ref;
                break;
              }
            }
          }

          ElementoUML childEl = resolveElement(childId, idToElementMap, nameToElementMap);
          ElementoUML parentEl = resolveElement(parentId, idToElementMap, nameToElementMap);

          if (childEl != null && parentEl != null && !childEl.getId().equals(parentEl.getId())) {
            String relationId = getRelationImportKey(el);
            if (processedRelationIds.add(relationId)) {
              String relName = getAttr(el, "name", "Name");
              RelacionUML herencia = new RelacionUML(
                  diagram,
                  childEl,
                  parentEl,
                  relName.isBlank() ? null : relName,
                  TipoRelacionUML.HERENCIA,
                  null,
                  null
              );
              relationRepo.save(herencia);
              importedRelationsById.put(relationId, herencia);
            }
          }
        }
      }

      // 6. Cuarto paso: Importar Asociaciones, Composiciones y Agregaciones (EA XMI 1.1 y 2.1)
      for (Element el : allElements) {
        String local = getLocalName(el);
        String type = getAttr(el, "xmi:type", "type");

        if ("Abstraction".equalsIgnoreCase(local) || "Realization".equalsIgnoreCase(local)
            || type.toLowerCase().endsWith("abstraction") || type.toLowerCase().endsWith("realization")) {
          String relationId = getRelationImportKey(el);
          String clientRef = getAttr(el, "client", "source", "specific");
          String supplierRef = getAttr(el, "supplier", "target", "general");
          ElementoUML origEl = resolveElement(clientRef, idToElementMap, nameToElementMap);
          ElementoUML destEl = resolveElement(supplierRef, idToElementMap, nameToElementMap);
          if (origEl != null && destEl != null && processedRelationIds.add(relationId)) {
            String relName = getAttr(el, "name", "Name");
            RelacionUML realizacion = new RelacionUML(
                diagram, origEl, destEl, relName.isBlank() ? null : relName,
                normalizeImportedRelationType(TipoRelacionUML.REALIZACION), null, null);
            relationRepo.save(realizacion);
            importedRelationsById.put(relationId, realizacion);
          }
        } else if ("Association".equalsIgnoreCase(local) || type.toLowerCase().endsWith("association")) {
          String relName = getAttr(el, "name", "Name");

          // Buscar los extremos de la asociación
          List<Element> ends = findDescendantElementsByTagNames(el, "ownedEnd", "AssociationEnd");

          // Caso estándar XMI 2.1 / 1.1 con 2 extremos definidos
          if (ends.size() >= 2) {
            Element end1 = ends.get(0);
            Element end2 = ends.get(1);

            String end1Ref = getAttr(end1, "type", "participant");
            if (end1Ref.isBlank()) {
              List<Element> pNodes = findDescendantElementsByTagNames(end1, "Classifier", "Class", "participant");
              for (Element p : pNodes) {
                String ref = getAttr(p, "xmi:idref", "xmi.idref", "idref", "type");
                if (!ref.isBlank()) { end1Ref = ref; break; }
              }
            }

            String end2Ref = getAttr(end2, "type", "participant");
            if (end2Ref.isBlank()) {
              List<Element> pNodes = findDescendantElementsByTagNames(end2, "Classifier", "Class", "participant");
              for (Element p : pNodes) {
                String ref = getAttr(p, "xmi:idref", "xmi.idref", "idref", "type");
                if (!ref.isBlank()) { end2Ref = ref; break; }
              }
            }

            ElementoUML origEl = resolveElement(end1Ref, idToElementMap, nameToElementMap);
            ElementoUML destEl = resolveElement(end2Ref, idToElementMap, nameToElementMap);

            if (origEl != null && destEl != null) {
              String relationId = getRelationImportKey(el);
              if (processedRelationIds.add(relationId)) {

                String multOrig = extractMultiplicityFromEnd(end1);
                String multDest = extractMultiplicityFromEnd(end2);

                String agg1 = extractAggregationFromEnd(end1);
                String agg2 = extractAggregationFromEnd(end2);

                TipoRelacionUML tipoRel = TipoRelacionUML.ASOCIACION;
                if (agg1.contains("comp") || agg2.contains("comp")) {
                  tipoRel = TipoRelacionUML.COMPOSICION;
                } else if (agg1.contains("shared") || agg2.contains("shared") || agg1.contains("aggreg") || agg2.contains("aggreg")) {
                  tipoRel = TipoRelacionUML.AGREGACION;
                }

                RelacionUML relacion = new RelacionUML(
                    diagram,
                    origEl,
                    destEl,
                    relName.isBlank() ? null : relName,
                    tipoRel,
                    multOrig.isBlank() ? null : multOrig,
                    multDest.isBlank() ? null : multDest
                );
                relationRepo.save(relacion);
                importedRelationsById.put(relationId, relacion);
              }
            }
          }
        } else if ("Dependency".equalsIgnoreCase(local) || type.toLowerCase().endsWith("dependency")) {
          String relName = getAttr(el, "name", "Name");
          String clientRef = getAttr(el, "client", "source");
          String supplierRef = getAttr(el, "supplier", "target");
          ElementoUML origEl = resolveElement(clientRef, idToElementMap, nameToElementMap);
          ElementoUML destEl = resolveElement(supplierRef, idToElementMap, nameToElementMap);
          String relationId = getRelationImportKey(el);
          if (origEl != null && destEl != null && processedRelationIds.add(relationId)) {
              RelacionUML dep = new RelacionUML(diagram, origEl, destEl,
                  relName.isBlank() ? null : relName, TipoRelacionUML.DEPENDENCIA, null, null);
              relationRepo.save(dep);
              importedRelationsById.put(relationId, dep);
          }
        } else if ("connector".equalsIgnoreCase(local)) {
          // Parsear conectores de Enterprise Architect (<connector>)
          String relName = "";
          String eaType = "";
          List<Element> props = findChildElementsByTagNames(el, "properties");
          if (!props.isEmpty()) {
            relName = getAttr(props.get(0), "name", "Name");
            eaType = getAttr(props.get(0), "ea_type", "eaType");
          }

          Element sourceEl = null;
          Element targetEl = null;
          List<Element> sources = findChildElementsByTagNames(el, "source");
          if (!sources.isEmpty()) sourceEl = sources.get(0);
          List<Element> targets = findChildElementsByTagNames(el, "target");
          if (!targets.isEmpty()) targetEl = targets.get(0);

          if (sourceEl != null && targetEl != null) {
            String srcRef = getAttr(sourceEl, "xmi:idref", "xmi.idref", "idref");
            String tgtRef = getAttr(targetEl, "xmi:idref", "xmi.idref", "idref");

            ElementoUML origEl = resolveElement(srcRef, idToElementMap, nameToElementMap);
            ElementoUML destEl = resolveElement(tgtRef, idToElementMap, nameToElementMap);

            if (origEl != null && destEl != null) {
              String multOrig = "";
              String aggOrig = "";
              List<Element> srcTypes = findChildElementsByTagNames(sourceEl, "type");
              if (!srcTypes.isEmpty()) {
                multOrig = getAttr(srcTypes.get(0), "multiplicity", "Multiplicity");
                aggOrig = extractAggregationFromEnd(srcTypes.get(0));
              }

              String multDest = "";
              String aggDest = "";
              List<Element> tgtTypes = findChildElementsByTagNames(targetEl, "type");
              if (!tgtTypes.isEmpty()) {
                multDest = getAttr(tgtTypes.get(0), "multiplicity", "Multiplicity");
                aggDest = extractAggregationFromEnd(tgtTypes.get(0));
              }

              TipoRelacionUML tipoRel = TipoRelacionUML.ASOCIACION;
              if ("Generalization".equalsIgnoreCase(eaType)) {
                tipoRel = TipoRelacionUML.HERENCIA;
              } else if ("Aggregation".equalsIgnoreCase(eaType) || aggOrig.contains("shared") || aggDest.contains("shared")) {
                tipoRel = TipoRelacionUML.AGREGACION;
              } else if ("Composition".equalsIgnoreCase(eaType) || aggOrig.contains("composite") || aggDest.contains("composite")) {
                tipoRel = TipoRelacionUML.COMPOSICION;
              } else if ("Dependency".equalsIgnoreCase(eaType)) {
                tipoRel = TipoRelacionUML.DEPENDENCIA;
              } else if ("Realization".equalsIgnoreCase(eaType) || "Realisation".equalsIgnoreCase(eaType)) {
                tipoRel = normalizeImportedRelationType(TipoRelacionUML.REALIZACION);
              }

              String relationId = getRelationImportKey(el);
              if (processedRelationIds.add(relationId)) {
                RelacionUML relacion = new RelacionUML(
                    diagram,
                    origEl,
                    destEl,
                    relName.isBlank() ? null : relName,
                    tipoRel,
                    multOrig.isBlank() ? null : multOrig,
                    multDest.isBlank() ? null : multDest
                );
                relationRepo.save(relacion);
                importedRelationsById.put(relationId, relacion);
              } else if ((TipoRelacionUML.AGREGACION == tipoRel || TipoRelacionUML.COMPOSICION == tipoRel)
                  && importedRelationsById.containsKey(relationId)) {
                // EA may expose the same relation first as UML:Association and
                // then as a connector carrying the authoritative aggregation.
                RelacionUML imported = importedRelationsById.get(relationId);
                if (imported.getTipoRelacion() == TipoRelacionUML.ASOCIACION) {
                  imported.update(imported.getNombreRelacion(), tipoRel,
                      imported.getMultiplicidadOrigen(), imported.getMultiplicidadDestino());
                  relationRepo.save(imported);
                }
              }
            }
          }
        }
      }

      umlService.notifyCollabAfterCommit(diagramId, "XMI_IMPORTED", newRev, email);
      return umlService.getDiagram(projectId, diagramId, email);

    } catch (Exception e) {
      throw new BadRequestException("Error al parsear el archivo XMI: " + e.getMessage());
    } finally {
      umlService.releaseLock(projectId, diagramId, email);
    }
  }

  // Exportación e Importación en Formato JSON Propio Completo (CU13 / Requerimiento 11)
  @Transactional(readOnly = true)
  public String exportJson(UUID projectId, UUID diagramId, String email) {
    projectService.requireAccess(projectId, email);
    DiagramaClase diagram = validateDiagram(projectId, diagramId);
    List<ElementoUML> elements = elementRepo.findByDiagramaId(diagramId);
    List<RelacionUML> relations = relationRepo.findByDiagramaId(diagramId);

    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("format", "CASE_UML_DIAGRAM_JSON");
      root.put("version", "2.0");
      root.put("exportedAt", Instant.now().toString());

      ObjectNode diagramNode = root.putObject("diagram");
      diagramNode.put("id", diagram.getId().toString());
      diagramNode.put("nombre", diagram.getNombre());
      diagramNode.put("descripcion", diagram.getDescripcion());

      ArrayNode elementsArray = root.putArray("elements");
      for (ElementoUML el : elements) {
        ObjectNode elNode = elementsArray.addObject();
        elNode.put("id", el.getId().toString());
        elNode.put("nombre", el.getNombre());
        elNode.put("tipoElemento", el.getTipoElemento().name());
        elNode.put("posicionX", el.getPosicionX());
        elNode.put("posicionY", el.getPosicionY());
        if (el.getPropiedadesJson() != null && !el.getPropiedadesJson().isBlank()) {
          elNode.set("properties", objectMapper.readTree(el.getPropiedadesJson()));
        }
      }

      ArrayNode relationsArray = root.putArray("relations");
      for (RelacionUML rel : relations) {
        if (rel.getElementoOrigen() == null || rel.getElementoDestino() == null) continue;
        ObjectNode relNode = relationsArray.addObject();
        relNode.put("id", rel.getId().toString());
        relNode.put("tipoRelacion", rel.getTipoRelacion().name());
        relNode.put("nombreRelacion", rel.getNombreRelacion());
        relNode.put("elementoOrigenNombre", rel.getElementoOrigen().getNombre());
        relNode.put("elementoDestinoNombre", rel.getElementoDestino().getNombre());
        relNode.put("multiplicidadOrigen", rel.getMultiplicidadOrigen());
        relNode.put("multiplicidadDestino", rel.getMultiplicidadDestino());
      }

      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    } catch (Exception e) {
      throw new BadRequestException("Error al exportar diagrama a JSON: " + e.getMessage());
    }
  }

  public DiagramFullView importJson(UUID projectId, UUID diagramId, String email, String jsonContent) {
    return importJson(projectId, diagramId, email, jsonContent, null);
  }

  public DiagramFullView importJson(UUID projectId, UUID diagramId, String email, String jsonContent, Long expectedRevision) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = validateDiagram(projectId, diagramId);
    long newRev = umlService.reserveNextRevision(diagramId, expectedRevision);
    diagram.setRevision(newRev);

    try {
      JsonNode root = objectMapper.readTree(jsonContent);
      JsonNode elementsNode = root.path("elements");
      JsonNode relationsNode = root.path("relations");

      if (!elementsNode.isArray()) {
        throw new BadRequestException("El JSON debe contener un arreglo 'elements'.");
      }

      Map<String, ElementoUML> createdMap = new HashMap<>();

      // 1. Guardar elementos
      for (JsonNode elNode : elementsNode) {
        String name = elNode.path("nombre").asText();
        String tipoStr = elNode.path("tipoElemento").asText("CLASE");
        double posX = elNode.path("posicionX").asDouble(150.0);
        double posY = elNode.path("posicionY").asDouble(120.0);
        JsonNode props = elNode.path("properties");
        String propsJson = props.isMissingNode() ? "{}" : props.toString();

        TipoElementoUML tipo = TipoElementoUML.valueOf(tipoStr);
        ElementoUML el = new ElementoUML(diagram, tipo, name, posX, posY, propsJson);
        el = elementRepo.save(el);
        createdMap.put(name.toLowerCase(), el);
      }

      // 2. Guardar relaciones
      if (relationsNode.isArray()) {
        for (JsonNode rNode : relationsNode) {
          String origName = rNode.path("elementoOrigenNombre").asText();
          String destName = rNode.path("elementoDestinoNombre").asText();
          String tipoStr = rNode.path("tipoRelacion").asText("ASOCIACION");
          String relName = rNode.path("nombreRelacion").isTextual() ? rNode.path("nombreRelacion").asText() : null;
          String multOrig = rNode.path("multiplicidadOrigen").isTextual() ? rNode.path("multiplicidadOrigen").asText() : null;
          String multDest = rNode.path("multiplicidadDestino").isTextual() ? rNode.path("multiplicidadDestino").asText() : null;

          ElementoUML orig = createdMap.get(origName.toLowerCase());
          ElementoUML dest = createdMap.get(destName.toLowerCase());

          if (orig != null && dest != null) {
            TipoRelacionUML tipoRel = TipoRelacionUML.valueOf(tipoStr);
            RelacionUML rel = new RelacionUML(diagram, orig, dest, relName, tipoRel, multOrig, multDest);
            relationRepo.save(rel);
          }
        }
      }

    umlService.notifyCollabAfterCommit(diagramId, "JSON_IMPORTED", newRev, email);
      return umlService.getDiagram(projectId, diagramId, email);

    } catch (Exception e) {
      throw new BadRequestException("Error al importar diagrama desde JSON: " + e.getMessage());
    }
  }

  private String extractMultiplicityFromEnd(Element end) {
    if (end == null) return "";
    String attr = getAttr(end, "multiplicity", "Multiplicity");
    if (!attr.isBlank()) return attr.trim();

    String lower = "";
    String upper = "";

    List<Element> lowerNodes = findDescendantElementsByTagNames(end, "lowerValue", "lower");
    if (!lowerNodes.isEmpty()) {
      lower = getAttr(lowerNodes.get(0), "value", "Value");
    }

    List<Element> upperNodes = findDescendantElementsByTagNames(end, "upperValue", "upper");
    if (!upperNodes.isEmpty()) {
      upper = getAttr(upperNodes.get(0), "value", "Value");
    }

    // Enterprise Architect XMI 1.1: <UML:MultiplicityRange lower="0" upper="-1"/>
    if (lower.isBlank() && upper.isBlank()) {
      List<Element> ranges = findDescendantElementsByTagNames(end, "MultiplicityRange", "multiplicityRange");
      if (!ranges.isEmpty()) {
        lower = getAttr(ranges.get(0), "lower", "Lower");
        upper = getAttr(ranges.get(0), "upper", "Upper");
      }
    }

    if ("-1".equals(upper)) upper = "*";

    if (!lower.isBlank() && !upper.isBlank()) {
      if (lower.equals(upper)) return lower;
      return lower + ".." + upper;
    } else if (!upper.isBlank()) {
      return upper;
    } else if (!lower.isBlank()) {
      return lower;
    }
    return "";
  }

  private String extractAggregationFromEnd(Element end) {
    if (end == null) return "";

    String direct = getAttr(end, "aggregation", "Aggregation");
    if (!direct.isBlank()) return direct.trim().toLowerCase(Locale.ROOT);

    List<Element> aggregationNodes = findDescendantElementsByTagNames(
        end, "aggregation", "AssociationEnd.aggregation", "Foundation.Core.AssociationEnd.aggregation",
        "AggregationKind");
    for (Element node : aggregationNodes) {
      String value = getAttr(node, "xmi.value", "xmi:value", "value", "Value");
      if (value.isBlank()) {
        value = node.getTextContent() == null ? "" : node.getTextContent().trim();
      }
      if (!value.isBlank()) return value.toLowerCase(Locale.ROOT);
    }
    return "";
  }

  private void collectAllElements(org.w3c.dom.Node node, List<Element> result) {
    if (node == null) return;
    if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
      result.add((Element) node);
    }
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      collectAllElements(children.item(i), result);
    }
  }

  private String getLocalName(org.w3c.dom.Node node) {
    if (node == null) return "";
    String name = node.getNodeName();
    if (name == null) return "";
    int idx = name.indexOf(':');
    return (idx >= 0) ? name.substring(idx + 1) : name;
  }

  private String getAttr(Element el, String... candidateNames) {
    if (el == null) return "";
    for (String cand : candidateNames) {
      if (el.hasAttribute(cand)) {
        String val = el.getAttribute(cand);
        if (val != null && !val.isBlank()) return val.trim();
      }
    }
    org.w3c.dom.NamedNodeMap attrs = el.getAttributes();
    if (attrs != null) {
      for (int i = 0; i < attrs.getLength(); i++) {
        org.w3c.dom.Node attrNode = attrs.item(i);
        String attrNodeName = attrNode.getNodeName();
        String localAttr = attrNodeName.contains(":") ? attrNodeName.substring(attrNodeName.indexOf(":") + 1) : attrNodeName;
        for (String cand : candidateNames) {
          String cleanCand = cand.contains(":") ? cand.substring(cand.indexOf(":") + 1) : cand;
          if (localAttr.equalsIgnoreCase(cleanCand) || attrNodeName.equalsIgnoreCase(cand)) {
            String val = attrNode.getNodeValue();
            if (val != null && !val.isBlank()) return val.trim();
          }
        }
      }
    }
    return "";
  }

  private static final Set<String> IGNORED_TYPE_NAMES = Set.of(
      "earootclass", "ea_root_class", "earoot", "easystem", "eadiagram", "eamodel",
      "int", "integer", "long", "longint", "bigint", "int64", "int32", "int16", "int8",
      "short", "byte", "tinyint", "smallint",
      "uint", "uint8", "uint16", "uint32", "uint64", "ulong", "ushort", "ubyte",
      "float", "float4", "float8", "double", "decimal", "numeric", "number", "real", "money", "smallmoney",
      "char", "nchar", "varchar", "varchar2", "nvarchar", "nvarchar2", "text", "ntext", "string", "str",
      "character", "character varying",
      "bool", "boolean", "bit",
      "date", "datetime", "datetime2", "timestamp", "timestamptz", "time", "year", "smalldatetime",
      "void", "object", "any", "unknown", "null", "undefined",
      "blob", "clob", "binary", "varbinary", "image", "bytea", "raw",
      "uuid", "guid", "json", "jsonb", "xml"
  );

  public static boolean isPrimitiveOrSystemElement(String name) {
    if (name == null || name.isBlank()) return true;
    String clean = name.trim().toLowerCase();
    if (clean.equals("earootclass") || clean.startsWith("earoot") || clean.startsWith("ea_root")) {
      return true;
    }
    // Quitar prefijos comunes de EA o de tipos generados (EAJava_, EASQL_, dt_, uml_)
    if (clean.startsWith("eajava_")) clean = clean.substring(7);
    if (clean.startsWith("easql_")) clean = clean.substring(6);
    if (clean.startsWith("ea_")) clean = clean.substring(3);
    if (clean.startsWith("dt_")) clean = clean.substring(3);
    if (clean.startsWith("uml_")) clean = clean.substring(4);

    return IGNORED_TYPE_NAMES.contains(clean);
  }

  private boolean isClassOrTableElement(Element el) {
    if (el == null) return false;
    String local = getLocalName(el);
    String type = getAttr(el, "xmi:type", "type");
    String cleanType = type.contains(":") ? type.substring(type.indexOf(":") + 1) : type;

    // Descartar etiquetas que no son entidades de clase ni tabla
    if (local.equalsIgnoreCase("Package") || cleanType.equalsIgnoreCase("Package")
        || local.equalsIgnoreCase("Model") || cleanType.equalsIgnoreCase("Model")
        || local.equalsIgnoreCase("elements") || local.equalsIgnoreCase("connectors")
        || local.equalsIgnoreCase("diagrams") || local.equalsIgnoreCase("diagram")
        || local.equalsIgnoreCase("diagramElement") || local.equalsIgnoreCase("attributes")
        || local.equalsIgnoreCase("operations") || local.equalsIgnoreCase("properties")
        || local.equalsIgnoreCase("project") || local.equalsIgnoreCase("Documentation")
        || local.equalsIgnoreCase("XMI.header") || local.equalsIgnoreCase("XMI.content")
        || local.equalsIgnoreCase("DataType") || cleanType.equalsIgnoreCase("DataType")
        || local.equalsIgnoreCase("PrimitiveType") || cleanType.equalsIgnoreCase("PrimitiveType")
        || local.equalsIgnoreCase("Association") || cleanType.equalsIgnoreCase("Association")
        || local.equalsIgnoreCase("Generalization") || cleanType.equalsIgnoreCase("Generalization")
        || local.equalsIgnoreCase("Dependency") || cleanType.equalsIgnoreCase("Dependency")
        || local.equalsIgnoreCase("Attribute") || cleanType.equalsIgnoreCase("Property")
        || local.equalsIgnoreCase("Operation") || cleanType.equalsIgnoreCase("Parameter")
        || local.equalsIgnoreCase("Stereotype") || cleanType.equalsIgnoreCase("Tag")) {
      return false;
    }

    // Descartar estereotipos de tipos de datos primitivos
    if (hasStereotype(el, "datatype", "primitive", "type")) {
      return false;
    }

    // Descartar si atributos internos de EA señalan DataType o PrimitiveType
    String sType = getAttr(el, "sType", "s_type", "tType", "t_type");
    if (sType.equalsIgnoreCase("DataType") || sType.equalsIgnoreCase("PrimitiveType")) {
      return false;
    }
    List<Element> propNodes = findChildElementsByTagNames(el, "properties", "model");
    for (Element p : propNodes) {
      String psType = getAttr(p, "sType", "s_type", "tType", "t_type");
      if (psType.equalsIgnoreCase("DataType") || psType.equalsIgnoreCase("PrimitiveType")) {
        return false;
      }
    }

    // Descartar si el nombre es una clase raíz de EA o un tipo primitivo conocido
    String name = getAttr(el, "name", "Name");
        if (!name.isBlank() && isPrimitiveOrSystemElement(name)) {
          return false;
        }
        // EA may export auxiliary extension records as Class/Table nodes.
        // They are metadata, not UML elements of the user diagram.
        String normalizedName = name.trim().toLowerCase(Locale.ROOT);
        if (normalizedName.equals("extension_table") || normalizedName.equals("extension_enumeration")) {
          return false;
        }

    // Aceptación directa por nombre de etiqueta o tipo
    if (local.equalsIgnoreCase("Class") || local.equalsIgnoreCase("Table")
        || local.equalsIgnoreCase("Interface") || local.equalsIgnoreCase("Enumeration")) {
      return true;
    }

    if (cleanType.equalsIgnoreCase("Class") || cleanType.equalsIgnoreCase("Table")
        || cleanType.equalsIgnoreCase("Interface") || cleanType.equalsIgnoreCase("Enumeration")) {
      return true;
    }

    // Estereotipo de Enterprise Architect para tablas de base de datos
    return hasStereotype(el, "table", "entity", "class");
  }

  private boolean hasStereotype(Element el, String... candidateStereotypes) {
    String stAttr = getAttr(el, "stereotype", "Stereotype").toLowerCase();
    for (String cand : candidateStereotypes) {
      if (stAttr.contains(cand.toLowerCase())) return true;
    }
    List<Element> stereoNodes = findDescendantElementsByTagNames(el, "Stereotype", "stereotype");
    for (Element s : stereoNodes) {
      String sName = getAttr(s, "name", "Name").toLowerCase();
      for (String cand : candidateStereotypes) {
        if (sName.contains(cand.toLowerCase())) return true;
      }
    }
    return false;
  }

  private TipoElementoUML determineElementType(Element el) {
    String local = getLocalName(el);
    String type = getAttr(el, "xmi:type", "type");
    String isAbstract = getAttr(el, "isAbstract", "is_abstract");

    if (local.equalsIgnoreCase("Interface") || type.toLowerCase().endsWith("interface")) {
      return TipoElementoUML.INTERFAZ;
    }
    if (local.equalsIgnoreCase("Enumeration") || type.toLowerCase().endsWith("enumeration")) {
      return TipoElementoUML.ENUMERACION;
    }
    if ("true".equalsIgnoreCase(isAbstract)) {
      return TipoElementoUML.CLASE_ABSTRACTA;
    }
    return TipoElementoUML.CLASE;
  }

  private List<Element> findChildElementsByTagNames(Element parent, String... tagNames) {
    List<Element> result = new ArrayList<>();
    if (parent == null) return result;
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      org.w3c.dom.Node child = children.item(i);
      if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
        String local = getLocalName(child);
        for (String t : tagNames) {
          if (local.equalsIgnoreCase(t) || child.getNodeName().equalsIgnoreCase(t)) {
            result.add((Element) child);
            break;
          }
        }
      }
    }
    return result;
  }

  private List<Element> findDescendantElementsByTagNames(Element parent, String... tagNames) {
    List<Element> result = new ArrayList<>();
    if (parent == null) return result;
    List<Element> all = new ArrayList<>();
    collectAllElements(parent, all);
    // Ignorar el elemento padre
    for (int i = 1; i < all.size(); i++) {
      Element el = all.get(i);
      String local = getLocalName(el);
      for (String t : tagNames) {
        if (local.equalsIgnoreCase(t) || el.getNodeName().equalsIgnoreCase(t)) {
          result.add(el);
          break;
        }
      }
    }
    return result;
  }

  private String resolveAttributeType(Element attrEl, Map<String, String> idToNameMap) {
    // 1. Tipo en atributo directo
    String typeAttr = getAttr(attrEl, "type", "typeRef", "datatype");
    if (!typeAttr.isBlank() && !isGenericAttributeType(typeAttr)) {
      if (idToNameMap.containsKey(typeAttr)) {
        return cleanTypeName(idToNameMap.get(typeAttr));
      }
      return cleanTypeName(typeAttr);
    }

    // 2. Tipo en nodo hijo o propiedades de EA
    List<Element> typeNodes = findDescendantElementsByTagNames(attrEl,
        "type", "Classifier", "DataType", "PrimitiveType", "StructuralFeature.type", "properties", "property", "TaggedValue", "taggedValue");
    for (Element tn : typeNodes) {
      String tag = getAttr(tn, "tag", "name", "key");
      String taggedValue = getAttr(tn, "value", "Value", "dataValue", "DataValue");
      if (tag.equalsIgnoreCase("type") || tag.equalsIgnoreCase("datatype") || tag.equalsIgnoreCase("sqltype")) {
        if (!taggedValue.isBlank() && !isGenericAttributeType(taggedValue)) {
          return cleanTypeName(taggedValue);
        }
        String text = tn.getTextContent() == null ? "" : tn.getTextContent().trim();
        if (!text.isBlank() && !isGenericAttributeType(text)) {
          return cleanTypeName(text);
        }
      }
      String ref = getAttr(tn, "xmi:idref", "xmi.idref", "idref");
      if (!ref.isBlank() && idToNameMap.containsKey(ref)) {
        return cleanTypeName(idToNameMap.get(ref));
      }
      String typeVal = getAttr(tn, "type", "Type");
      if (!typeVal.isBlank()) {
        if (idToNameMap.containsKey(typeVal)) {
          return cleanTypeName(idToNameMap.get(typeVal));
        }
        return cleanTypeName(typeVal);
      }
      String href = getAttr(tn, "href", "Href");
      if (href.contains("#")) {
        return cleanTypeName(href.substring(href.indexOf("#") + 1));
      }
      String name = getAttr(tn, "name", "Name");
      if (!name.isBlank()) {
        return cleanTypeName(name);
      }
    }

    return "String";
  }

  private boolean isGenericAttributeType(String value) {
    if (value == null || value.isBlank()) return true;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("property") || normalized.equals("attribute")
        || normalized.equals("ownedattribute") || normalized.equals("uml:property");
  }

  private String cleanTypeName(String raw) {
    if (raw == null || raw.isBlank()) return "String";
    String s = raw.trim();
    if (s.startsWith("EAJava_")) s = s.substring(7);
    if (s.startsWith("EASQL_")) s = s.substring(6);
    if (s.startsWith("dt_")) s = s.substring(3);
    if (s.contains(":")) s = s.substring(s.lastIndexOf(":") + 1);
    if (s.equalsIgnoreCase("int") || s.equalsIgnoreCase("integer") || s.equalsIgnoreCase("int4") || s.equalsIgnoreCase("int32")) return "Integer";
    if (s.equalsIgnoreCase("long") || s.equalsIgnoreCase("bigint") || s.equalsIgnoreCase("int8") || s.equalsIgnoreCase("int64")) return "long";
    if (s.equalsIgnoreCase("varchar") || s.equalsIgnoreCase("char") || s.equalsIgnoreCase("text") || s.equalsIgnoreCase("string") || s.equalsIgnoreCase("nvarchar")) return "String";
    if (s.equalsIgnoreCase("bool") || s.equalsIgnoreCase("boolean") || s.equalsIgnoreCase("bit")) return "Boolean";
    if (s.equalsIgnoreCase("double") || s.equalsIgnoreCase("float") || s.equalsIgnoreCase("numeric") || s.equalsIgnoreCase("decimal") || s.equalsIgnoreCase("real")) return "Decimal";
    if (s.equalsIgnoreCase("date") || s.equalsIgnoreCase("datetime") || s.equalsIgnoreCase("timestamp") || s.equalsIgnoreCase("datetime2")) return "DateTime";
    return s;
  }

  private String sanitizeId(String name) {
    if (name == null || name.isBlank()) return "String";
    return name.replaceAll("[^a-zA-Z0-9_]", "_");
  }

  private double[] parseCoordinatesFromGeometry(String geom) {
    try {
      double left = -1;
      double top = -1;
      String[] parts = geom.split(";");
      for (String part : parts) {
        String[] kv = part.split("=");
        if (kv.length == 2) {
          String k = kv[0].trim().toLowerCase();
          String v = kv[1].trim();
          if ("left".equals(k) || "l".equals(k)) {
            left = Math.abs(Double.parseDouble(v));
          } else if ("top".equals(k) || "t".equals(k)) {
            top = Math.abs(Double.parseDouble(v));
          }
        }
      }
      if (left >= 0 && top >= 0) {
        return new double[]{left, top};
      }

      // EA also exports geometry as x,y,width,height (or x,y,right,bottom).
      // React Flow only needs the original upper-left position here.
      String[] compact = geom.split(",");
      if (compact.length >= 2) {
        double x = Double.parseDouble(compact[0].trim());
        double y = Double.parseDouble(compact[1].trim());
        if (Double.isFinite(x) && Double.isFinite(y)) {
          return new double[]{Math.abs(x), Math.abs(y)};
        }
      }
    } catch (Exception ignored) {}
    return null;
  }

  private String resolveOperationReturnType(Element opEl, Map<String, String> idToNameMap) {
    List<Element> params = findDescendantElementsByTagNames(opEl, "Parameter", "ownedParameter", "parameter");
    for (Element p : params) {
      String kind = getAttr(p, "kind", "direction", "name").toLowerCase();
      if ("return".equals(kind)) {
        return resolveAttributeType(p, idToNameMap);
      }
    }
    return "void";
  }

  private String resolveOperationParameters(Element opEl, Map<String, String> idToNameMap) {
    List<Element> params = findDescendantElementsByTagNames(opEl, "Parameter", "ownedParameter", "parameter");
    List<String> list = new ArrayList<>();
    for (Element p : params) {
      String kind = getAttr(p, "kind", "direction", "name").toLowerCase();
      if ("return".equals(kind)) continue;
      String pName = getAttr(p, "name", "Name");
      if (pName.isBlank()) continue;
      String pType = resolveAttributeType(p, idToNameMap);
      list.add(pName + ": " + pType);
    }
    return String.join(", ", list);
  }

  private ElementoUML resolveElement(String idOrName, Map<String, ElementoUML> idMap, Map<String, ElementoUML> nameMap) {
    if (idOrName == null || idOrName.isBlank()) return null;
    ElementoUML el = idMap.get(idOrName.trim());
    if (el != null) return el;
    return nameMap.get(idOrName.trim().toLowerCase());
  }

  private String[] parseMultiplicityBounds(String mult) {
    if (mult == null || mult.isBlank()) return new String[]{"1", "1"};
    String s = mult.trim();
    if (s.contains("..")) {
      String[] parts = s.split("\\.\\.");
      return new String[]{parts[0].trim(), parts[1].trim()};
    }
    if (s.equals("*")) return new String[]{"0", "*"};
    return new String[]{s, s};
  }

  private String getRelationImportKey(Element relation) {
    String id = getAttr(relation, "xmi:id", "xmi.id", "id", "xmiId", "xmi:idref", "xmi.idref", "idref",
        "connectorID", "connectorId", "guid", "GUID");
    if (!id.isBlank()) return id;

    // Fallback only for malformed exports without a relation identifier. It
    // includes semantic data beyond the endpoints so parallel relation types
    // are not collapsed merely because they connect the same two elements.
    String local = getLocalName(relation).toLowerCase(Locale.ROOT);
    String name = getAttr(relation, "name", "Name");
    String client = getAttr(relation, "client", "source", "specific", "general");
    String supplier = getAttr(relation, "supplier", "target", "general", "specific");
    if (client.isBlank() || supplier.isBlank()) {
      List<Element> sources = findDescendantElementsByTagNames(relation, "source");
      List<Element> targets = findDescendantElementsByTagNames(relation, "target");
      if (!sources.isEmpty()) client = getAttr(sources.get(0), "xmi:idref", "xmi.idref", "idref", "type");
      if (!targets.isEmpty()) supplier = getAttr(targets.get(0), "xmi:idref", "xmi.idref", "idref", "type");
    }
    if (client.isBlank() || supplier.isBlank()) {
      List<Element> ends = findDescendantElementsByTagNames(relation, "ownedEnd", "AssociationEnd");
      if (ends.size() >= 2) {
        client = getAttr(ends.get(0), "type", "participant");
        supplier = getAttr(ends.get(1), "type", "participant");
      }
    }
    if (client.isBlank() || supplier.isBlank()) {
      List<Element> participants = findDescendantElementsByTagNames(relation, "Classifier", "Class");
      if (participants.size() >= 2) {
        client = getAttr(participants.get(0), "xmi:idref", "xmi.idref", "idref", "type");
        supplier = getAttr(participants.get(1), "xmi:idref", "xmi.idref", "idref", "type");
      }
    }
    String eaType = getAttr(relation, "ea_type", "eaType", "type");
    if (eaType.isBlank()) {
      List<Element> tags = findDescendantElementsByTagNames(relation, "TaggedValue", "taggedValue", "properties");
      for (Element tag : tags) {
        if (getAttr(tag, "tag", "key").equalsIgnoreCase("ea_type")) {
          eaType = getAttr(tag, "value", "Value", "dataValue", "DataValue");
          break;
        }
      }
    }
    if (eaType.isBlank()) {
      eaType = switch (local) {
        case "association" -> "Association";
        case "generalization" -> "Generalization";
        case "dependency" -> "Dependency";
        case "abstraction", "realization" -> "Realization";
        case "connector" -> "Association";
        default -> local;
      };
    }
    String multiplicity = getAttr(relation, "multiplicity", "Multiplicity");
    String aggregation = getAttr(relation, "aggregation", "Aggregation");
    return "DOM_RELATION:" + eaType.toLowerCase(Locale.ROOT) + ":" + name + ":" + client + ":" + supplier
        + ":" + multiplicity + ":" + aggregation;
  }

  /**
   * The current database constraint predates the REALIZACION enum value and
   * only accepts the five original relation types. Keep realization in the
   * export/model vocabulary, but normalize it before an XMI import is saved.
   */
  private TipoRelacionUML normalizeImportedRelationType(TipoRelacionUML type) {
    return type == TipoRelacionUML.REALIZACION ? TipoRelacionUML.ASOCIACION : type;
  }

  private String[] parseMultiplicityBoundsUpperLower(String mult) {
    if (mult == null || mult.isBlank()) return new String[]{"1", "1"};
    String s = mult.trim();
    String lower = "1";
    String upper = "1";
    if (s.contains("..")) {
      String[] parts = s.split("\\.\\.");
      lower = parts[0].trim();
      upper = parts[1].trim();
    } else if (s.equals("*")) {
      lower = "0";
      upper = "*";
    } else {
      lower = s;
      upper = s;
    }
    if ("*".equals(upper)) {
      upper = "-1";
    }
    return new String[]{lower, upper};
  }

  private DiagramaClase validateDiagram(UUID projectId, UUID diagramId) {
    DiagramaClase diagram = diagramRepo.findById(diagramId)
        .orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    if (!diagram.getProject().getId().equals(projectId)) {
      throw new NotFoundException("El diagrama no pertenece a este proyecto.");
    }
    return diagram;
  }

  private String escapeXml(String input) {
    if (input == null) return "";
    return input.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
