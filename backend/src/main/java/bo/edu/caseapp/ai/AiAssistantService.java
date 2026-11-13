package bo.edu.caseapp.ai;

import bo.edu.caseapp.ai.AiRequests.ChatRequest;
import bo.edu.caseapp.ai.AiRequests.CommandRequest;
import bo.edu.caseapp.ai.AiRequests.AnalyzeImageRequest;
import bo.edu.caseapp.ai.AiRequests.ApplyImageModelRequest;
import bo.edu.caseapp.ai.AiRequests.ImageClass;
import bo.edu.caseapp.ai.AiRequests.ImageAttribute;
import bo.edu.caseapp.ai.AiRequests.ImageImportMode;
import bo.edu.caseapp.ai.AiRequests.ImageMethod;
import bo.edu.caseapp.ai.AiRequests.ImageModel;
import bo.edu.caseapp.ai.AiRequests.ImageRelation;
import bo.edu.caseapp.ai.AiResponses.ChatResponse;
import bo.edu.caseapp.ai.AiResponses.CommandResponse;
import bo.edu.caseapp.ai.AiResponses.ImageAnalysisResponse;
import bo.edu.caseapp.ai.AiResponses.ImageReconstructionResponse;
import bo.edu.caseapp.ai.provider.LlmMessage;
import bo.edu.caseapp.ai.provider.LlmProvider;
import bo.edu.caseapp.ai.provider.LlmResponse;
import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.uml.UmlRequests.SaveElement;
import bo.edu.caseapp.uml.UmlRequests.SaveRelation;
import bo.edu.caseapp.uml.UmlRequests.CreateIntermediateClass;
import bo.edu.caseapp.uml.UmlRequests.IntermediateAttribute;
import bo.edu.caseapp.uml.UmlResponses.DiagramFullView;
import bo.edu.caseapp.user.User;
import bo.edu.caseapp.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AiAssistantService {
  private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);
  private final DiagramaClaseRepository diagramRepo;
  private final ElementoUMLRepository elementRepo;
  private final RelacionUMLRepository relationRepo;
  private final InteraccionIaRepository iaRepo;
  private final ProjectService projectService;
  private final UserRepository userRepo;
  private final UmlService umlService;
  private final bo.edu.caseapp.uml.relational.SqlDdlParserService sqlDdlParserService;
  private final bo.edu.caseapp.uml.collab.UmlWebSocketHandler wsHandler;
  private final LlmProvider llmProvider;
  @Autowired(required = false)
  private AiOrchestrationService aiOrchestrationService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @org.springframework.beans.factory.annotation.Autowired
  public AiAssistantService(
      DiagramaClaseRepository diagramRepo,
      ElementoUMLRepository elementRepo,
      RelacionUMLRepository relationRepo,
      InteraccionIaRepository iaRepo,
      ProjectService projectService,
      UserRepository userRepo,
      UmlService umlService,
      bo.edu.caseapp.uml.relational.SqlDdlParserService sqlDdlParserService,
      bo.edu.caseapp.uml.collab.UmlWebSocketHandler wsHandler,
      LlmProvider llmProvider
  ) {
    this.diagramRepo = diagramRepo;
    this.elementRepo = elementRepo;
    this.relationRepo = relationRepo;
    this.iaRepo = iaRepo;
    this.projectService = projectService;
    this.userRepo = userRepo;
    this.umlService = umlService;
    this.sqlDdlParserService = sqlDdlParserService;
    this.wsHandler = wsHandler;
    this.llmProvider = llmProvider;
  }

  /** Constructor de compatibilidad para pruebas unitarias existentes */
  public AiAssistantService(
      DiagramaClaseRepository diagramRepo,
      ElementoUMLRepository elementRepo,
      RelacionUMLRepository relationRepo,
      InteraccionIaRepository iaRepo,
      ProjectService projectService,
      UserRepository userRepo,
      UmlService umlService,
      bo.edu.caseapp.uml.relational.SqlDdlParserService sqlDdlParserService,
      bo.edu.caseapp.uml.collab.UmlWebSocketHandler wsHandler,
      String legacyApiKey,
      String legacyModel
  ) {
    this(diagramRepo, elementRepo, relationRepo, iaRepo, projectService, userRepo, umlService, sqlDdlParserService, wsHandler, (LlmProvider) null);
  }

  public CommandResponse executeCommand(UUID projectId, UUID diagramId, String email, CommandRequest request) {
    return executeCommand(projectId, diagramId, email, request, null);
  }

  public CommandResponse executeCommand(UUID projectId, UUID diagramId, String email, CommandRequest request, Long expectedRevision) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = validateDiagram(projectId, diagramId);
    umlService.requireNotLocked(diagram, email);

    if (expectedRevision != null && !expectedRevision.equals(diagram.getRevision())) {
      throw new bo.edu.caseapp.error.ConflictException("Conflicto de concurrencia: el diagrama ha sido modificado por otro colaborador (revisión esperada: "
          + expectedRevision + ", revisión actual: " + diagram.getRevision() + "). Actualiza el diagrama para sincronizar.");
    }

    User user = userRepo.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new NotFoundException("Usuario no encontrado."));

    boolean attributeDeletion = isAttributeDeletionRequest(request.command());
    boolean relationDeletion = isRelationDeletionRequest(request.command());
    if (!"LLM_CANONICAL".equals(request.resolvedSource()) && !looksLikeSqlDdl(request.command())
        && !attributeDeletion && !relationDeletion) {
      List<String> interpretedCommands = interpretCommandWithGroq(projectId, diagramId, email, request.command());
      if (interpretedCommands != null && !interpretedCommands.isEmpty()) {
        CommandResponse lastResponse = null;
        for (String interpretedCommand : interpretedCommands) {
          lastResponse = executeCommand(projectId, diagramId, email,
              new CommandRequest(interpretedCommand, "LLM_CANONICAL",
                  request.selectedElementId(), request.selectedRelationId()), expectedRevision);
        }
        return lastResponse;
      }
    }

    String raw = normalizeVoiceCommand(request.command().trim());
    String normalized = raw.toLowerCase();

    String actionType = "DESCONOCIDO";
    String message = "No se pudo interpretar el comando de modelado. Prueba con: 'Crear clase Cliente', 'Ponle nombre, correo y teléfono', 'Relacionar Cliente con Pedido', 'Uno a muchos', 'Cambia esa relación a composición'.";
    boolean success = false;

    try {
      // 0. RECONOCIMIENTO DE TABLAS SQL (CU11 / Requerimiento 8)
      if (normalized.contains("create table")) {
        int idx = normalized.indexOf("create table");
        String sqlPart = raw.substring(idx);
        DiagramFullView fullView = sqlDdlParserService.parseAndImportSql(projectId, diagramId, email, sqlPart);
        actionType = "SQL_IMPORTADO";
        message = "Script SQL DDL reconocido y procesado exitosamente. Tablas, atributos, tipos, claves primarias y relaciones foráneas creadas en el modelo.";
        InteraccionIa ia = new InteraccionIa(diagram, user, request.resolvedSource(), raw, message, actionType);
        iaRepo.save(ia);
        return new CommandResponse(true, actionType, message, fullView);
      }

      // 1. CREAR ELEMENTO (CLASE, INTERFAZ, ABSTRACTA, ENUM)
      // Ejemplos: "crear clase Producto", "agrega una clase Pedido", "crea una interfaz IRepositorio"
      Pattern pCrearClase = Pattern.compile("(?i)^(?:crear|crea|nueva|nuevo|agrega|agregar|anadir|anade)\\s+(?:una?\\s+)?(clase(?:\\s+abstracta)?|interfaz|abstracta|enum|enumeracion)\\s+(?:(?:llamada|llamado|de\\s+nombre)\\s+)?([A-Za-z][A-Za-z0-9_]*)(?:\\s+con\\s+(?:atributos|campos)?\\s*(.*))?$");
      Matcher mCrear = pCrearClase.matcher(raw);
      Pattern pIntermediate = Pattern.compile("(?i)^(?:crear|crea|agregar|agrega)\\s+(?:una?\\s+)?clase\\s+intermedia\\s+([a-zA-Z0-9_]+)(?:\\s+entre\\s+([a-zA-Z0-9_]+)\\s+y\\s+([a-zA-Z0-9_]+)|\\s+para\\s+la\\s+relacion\\s+seleccionada)?(?:\\s+con\\s+atributos?\\s+(.+))?$");
      Matcher mIntermediate = pIntermediate.matcher(raw);

      if (mIntermediate.find()) {
        String middleName = mIntermediate.group(1);
        String leftName = mIntermediate.group(2);
        String rightName = mIntermediate.group(3);
        List<IntermediateAttribute> middleAttributes = parseIntermediateAttributes(mIntermediate.group(4));
        List<RelacionUML> currentRelations = relationRepo.findByDiagramaId(diagramId);
        RelacionUML selectedRelation = null;
        if (request.selectedRelationId() != null && !request.selectedRelationId().isBlank()) {
          try {
            UUID selectedId = UUID.fromString(request.selectedRelationId());
            selectedRelation = currentRelations.stream().filter(r -> r.getId().equals(selectedId)).findFirst().orElse(null);
          } catch (IllegalArgumentException ignored) {}
        }
        if (selectedRelation == null && leftName != null && rightName != null) {
          String left = leftName, right = rightName;
          selectedRelation = currentRelations.stream().filter(r ->
              (r.getElementoOrigen().getNombre().equalsIgnoreCase(left) && r.getElementoDestino().getNombre().equalsIgnoreCase(right)
                  || r.getElementoOrigen().getNombre().equalsIgnoreCase(right) && r.getElementoDestino().getNombre().equalsIgnoreCase(left))
                  && isMany(r.getMultiplicidadOrigen()) && isMany(r.getMultiplicidadDestino())).findFirst().orElse(null);
        }
        if (selectedRelation != null && isMany(selectedRelation.getMultiplicidadOrigen()) && isMany(selectedRelation.getMultiplicidadDestino())) {
          String sourceName = selectedRelation.getElementoOrigen().getNombre();
          String targetName = selectedRelation.getElementoDestino().getNombre();
          umlService.createIntermediateClass(projectId, diagramId, email, selectedRelation.getId(),
              new CreateIntermediateClass(middleName, middleAttributes), expectedRevision);
          actionType = "CREAR_CLASE_INTERMEDIA";
          message = "Se convirtió la relación muchos a muchos entre «" + sourceName + "» y «" + targetName
              + "» en la clase intermedia «" + middleName + "».";
          success = true;
        } else {
          if (leftName == null || rightName == null) {
            message = "Selecciona una relación muchos a muchos o indica las dos clases: entre ClaseA y ClaseB.";
          } else {
        ObjectNode middleProps = objectMapper.createObjectNode();
        ArrayNode middleAttrs = middleProps.putArray("attributes");
        for (IntermediateAttribute attribute : middleAttributes) {
          middleAttrs.addObject().put("id", UUID.randomUUID().toString()).put("visibility", "+")
              .put("name", attribute.nombre()).put("type", attribute.tipo());
        }
        middleProps.putArray("methods");
        umlService.saveElement(projectId, diagramId, email,
            new SaveElement(null, TipoElementoUML.CLASE, middleName, 300.0, 150.0, middleProps.toString()));

        List<ElementoUML> currentElements = elementRepo.findByDiagramaId(diagramId);
        Optional<ElementoUML> middle = currentElements.stream().filter(e -> e.getNombre().equalsIgnoreCase(middleName)).findFirst();
        Optional<ElementoUML> left = currentElements.stream().filter(e -> e.getNombre().equalsIgnoreCase(leftName)).findFirst();
        Optional<ElementoUML> right = currentElements.stream().filter(e -> e.getNombre().equalsIgnoreCase(rightName)).findFirst();
        if (middle.isPresent() && left.isPresent() && right.isPresent()) {
          umlService.saveRelation(projectId, diagramId, email,
              new SaveRelation(null, left.get().getId(), middle.get().getId(), null, TipoRelacionUML.ASOCIACION, "1", "*"));
          umlService.saveRelation(projectId, diagramId, email,
              new SaveRelation(null, right.get().getId(), middle.get().getId(), null, TipoRelacionUML.ASOCIACION, "1", "*"));
        }
        actionType = "CREAR_CLASE_INTERMEDIA";
        message = "Se creó la clase intermedia «" + middleName + "» entre «" + leftName + "» y «" + rightName + "».";
        success = true;
          }
        }
      } else if (mCrear.find()) {
        String tipoStr = mCrear.group(1).trim().toLowerCase();
        String nombreClase = mCrear.group(2).trim();
        String atributosRaw = mCrear.group(3);

        TipoElementoUML tipo = switch (tipoStr) {
          case "interfaz" -> TipoElementoUML.INTERFAZ;
          case "abstracta", "clase abstracta" -> TipoElementoUML.CLASE_ABSTRACTA;
          case "enum", "enumeracion" -> TipoElementoUML.ENUMERACION;
          default -> TipoElementoUML.CLASE;
        };

        ObjectNode propNode = objectMapper.createObjectNode();
        ArrayNode attrs = propNode.putArray("attributes");

        if (atributosRaw != null && !atributosRaw.isBlank()) {
          String[] tokens = atributosRaw.split("[,;y]");
          for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            String attrName = t;
            String attrType = "String";
            if (t.contains(":") || t.contains(" ")) {
              String[] parts = t.contains(":") ? t.split(":") : t.split("\\s+");
              attrName = parts[0].trim();
              if (parts.length > 1) attrType = parts[1].trim();
            }
            attrs.addObject()
                .put("id", UUID.randomUUID().toString())
                .put("visibility", "+")
                .put("name", attrName)
                .put("type", capitalizeType(attrType));
          }
        }
        propNode.putArray("methods");

        double posX = 150.0 + (Math.random() * 250);
        double posY = 100.0 + (Math.random() * 200);

        SaveElement createDto = new SaveElement(null, tipo, nombreClase, posX, posY, propNode.toString());
        umlService.saveElement(projectId, diagramId, email, createDto);

        actionType = "CREAR_CLASE";
        message = "Se ha creado con éxito el elemento «" + nombreClase + "» de tipo " + tipo + " en el diagrama.";
        success = true;

      } else if (normalized.matches(".*(ponle|agrega|agregar|anade|añade|incluye|anadir|añadir)\\s+(.*)")) {
        // 2. ATRIBUTOS CONTEXTUALES: "Ponle nombre, correo y teléfono" o "Agrega fecha a Pedido"
        String body = raw.replaceFirst("(?i)^.*(ponle|agrega|agregar|anade|añade|incluye|anadir|añadir)\\s+", "").trim();
        List<ElementoUML> list = elementRepo.findByDiagramaId(diagramId);

        ElementoUML target = null;
        String attrsString = body;

        // Admite tanto "nombre String a Producto" como "a Producto nombre String".
        Matcher targetPrefix = Pattern.compile("(?i)^a\\s+([A-Za-z][A-Za-z0-9_]*)\\s+(.+)$").matcher(body);
        if (targetPrefix.matches()) {
          target = list.stream().filter(e -> e.getNombre().equalsIgnoreCase(targetPrefix.group(1))).findFirst().orElse(null);
          attrsString = targetPrefix.group(2).trim();
        } else if (body.toLowerCase().contains(" a ") || body.toLowerCase().contains(" en ")) {
          String[] parts = body.toLowerCase().contains(" a ") ? body.split("(?i)\\s+a\\s+") : body.split("(?i)\\s+en\\s+");
          attrsString = parts[0].trim();
          String targetClassName = parts[1].trim().replaceAll("[^a-zA-Z0-9_]", "");
          target = list.stream().filter(e -> e.getNombre().equalsIgnoreCase(targetClassName)).findFirst().orElse(null);
        }

        // Si no especificó clase explícita, usar el elemento seleccionado o el último elemento creado
        if (target == null && request.selectedElementId() != null && !request.selectedElementId().isBlank()) {
          try {
            UUID selId = UUID.fromString(request.selectedElementId());
            target = list.stream().filter(e -> e.getId().equals(selId)).findFirst().orElse(null);
          } catch (Exception ignored) {}
        }
        if (target == null && !list.isEmpty()) {
          target = list.get(list.size() - 1);
        }

        if (target != null) {
          // Limpiar palabras "atributo", "atributos"
          attrsString = attrsString.replaceAll("(?i)^atributos?\\s+", "").trim();
          String[] tokens = attrsString.split("[,;y]|\n");
          ObjectNode root = (ObjectNode) objectMapper.readTree(target.getPropiedadesJson());
          ArrayNode attrsNode = (ArrayNode) root.path("attributes");
          int addedCount = 0;

          for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            String aName = t;
            String aType = "String";
            if (t.contains(":") || t.contains(" ")) {
              String[] p = t.contains(":") ? t.split(":") : t.split("\\s+");
              aName = p[0].trim();
              if (p.length > 1) aType = p[1].trim();
            }
            aName = aName.replaceAll("[^a-zA-Z0-9_]", "");
            if (aName.equalsIgnoreCase("fecha")) aType = "Date";
            if (aName.equalsIgnoreCase("total") || aName.equalsIgnoreCase("precio") || aName.equalsIgnoreCase("monto")) aType = "Double";
            if (aName.equalsIgnoreCase("id")) aType = "Long";

            if (!aName.isEmpty()) {
              attrsNode.addObject()
                  .put("id", UUID.randomUUID().toString())
                  .put("visibility", "+")
                  .put("name", aName)
                  .put("type", capitalizeType(aType));
              addedCount++;
            }
          }

          target.setPropiedadesJson(root.toString());
          elementRepo.save(target);

          actionType = "AGREGAR_ATRIBUTO";
          message = "Se agregaron " + addedCount + " atributo(s) a la clase «" + target.getNombre() + "».";
          success = true;
        } else {
          message = "No hay ninguna clase en el diagrama a la cual agregar los atributos.";
        }

      } else if (isRelationDeletionRequest(raw)) {
        // Relation deletion is scoped to the requested pair (or an explicitly selected relation).
        // Never pass this intent through Groq's class-deletion action.
        List<RelacionUML> diagramRelations = relationRepo.findByDiagramaId(diagramId);
        RelacionUML targetRelation = null;
        Matcher relationEndpoints = Pattern.compile(
            "(?i)\\bentre\\s+(?:la\\s+)?(?:clase\\s+)?([\\p{L}\\p{N}_]+)\\s+(?:y|con)\\s+(?:la\\s+)?(?:clase\\s+)?([\\p{L}\\p{N}_]+)")
            .matcher(raw);
        boolean endpointsFound = relationEndpoints.find();
        if (endpointsFound) {
          String firstClass = relationEndpoints.group(1);
          String secondClass = relationEndpoints.group(2);
          List<RelacionUML> matchingRelations = diagramRelations.stream()
              .filter(relation -> connects(relation, firstClass, secondClass))
              .toList();
          if (matchingRelations.size() == 1) {
            targetRelation = matchingRelations.get(0);
          } else if (matchingRelations.size() > 1) {
            message = "Hay más de una relación entre «" + firstClass + "» y «" + secondClass
                + "». Selecciona la relación específica; no se eliminó ninguna.";
          } else {
            message = "No se encontró una relación entre «" + firstClass + "» y «" + secondClass
                + "». No se eliminó ninguna clase.";
          }
        }
        if (!endpointsFound && request.selectedRelationId() != null && !request.selectedRelationId().isBlank()) {
          try {
            UUID selectedId = UUID.fromString(request.selectedRelationId());
            targetRelation = diagramRelations.stream()
                .filter(relation -> relation.getId().equals(selectedId))
                .findFirst().orElse(null);
          } catch (IllegalArgumentException ignored) {}
        }

        if (targetRelation != null) {
          String sourceName = targetRelation.getElementoOrigen().getNombre();
          String targetName = targetRelation.getElementoDestino().getNombre();
          umlService.deleteRelation(projectId, diagramId, email, targetRelation.getId(), expectedRevision);
          actionType = "ELIMINAR_RELACION";
          message = "Se eliminó la relación entre «" + sourceName + "» y «" + targetName
              + "». Las clases y sus otras relaciones se conservaron.";
          success = true;
        } else if (message.startsWith("No se pudo interpretar")) {
          message = "Indica las dos clases, por ejemplo: «Borra la relación entre ClaseA y ClaseB». No se eliminó ninguna clase.";
        }

      } else if (isAttributeDeletionRequest(raw)) {
        // A request scoped to an attribute must never be reinterpreted as deleting its class.
        Matcher deleteAttribute = Pattern.compile(
            "(?i)(?:eliminar|borrar|elimina|borra)\\s+(?:el\\s+)?(?:atributo|campo|propiedad)\\s+([\\p{L}\\p{N}_]+)(?:\\s+[\\p{L}\\p{N}_]+)?\\s+(?:de\\s+|en\\s+)?(?:la\\s+)?clase\\s+([\\p{L}\\p{N}_]+)")
            .matcher(raw);
        if (deleteAttribute.find()) {
          String requestedAttribute = deleteAttribute.group(1);
          String className = deleteAttribute.group(2);
          List<ElementoUML> elements = elementRepo.findByDiagramaId(diagramId);
          Optional<ElementoUML> classElement = elements.stream()
              .filter(e -> e.getNombre().equalsIgnoreCase(className)).findFirst();
          if (classElement.isEmpty()) {
            message = "No se encontró la clase «" + className + ". No se eliminó ningún elemento.";
          } else {
            ElementoUML target = classElement.get();
            ObjectNode properties = (ObjectNode) objectMapper.readTree(target.getPropiedadesJson());
            JsonNode attributesNode = properties.path("attributes");
            int attributeIndex = findAttributeIndex(attributesNode, requestedAttribute);
            if (attributeIndex < 0) {
              message = "No se encontró el atributo «" + requestedAttribute + "» en «" + className
                  + ". No se eliminó la clase ni sus relaciones.";
            } else {
              String removedAttributeName = attributesNode.get(attributeIndex).path("name").asText(requestedAttribute);
              ((ArrayNode) attributesNode).remove(attributeIndex);
              target.setPropiedadesJson(properties.toString());
              elementRepo.save(target);
              actionType = "ELIMINAR_ATRIBUTO";
              message = "Se eliminó el atributo «" + removedAttributeName
                  + "» de la clase «" + className + ".";
              success = true;
            }
          }
        } else {
          message = "Indica el atributo y la clase, por ejemplo: «Borra el atributo idCliente de la clase Cliente». No se eliminó ningún elemento.";
        }

      } else if (normalized.matches(".*(eliminar|borrar|elimina|borra)\\s+.*")) {
        // 3. ELIMINAR ELEMENTO O RELACIÓN: "Elimina Cliente", "Borra la clase Pedido"
        Pattern pElim = Pattern.compile("(?i)(?:eliminar|borrar|elimina|borra)\\s+(?:la\\s+)?(?:clase|interfaz|abstracta|enum|elemento)?\\s*([a-zA-Z0-9_]+)");
        Matcher mDel = pElim.matcher(raw);
        if (mDel.find()) {
          String targetName = mDel.group(1).trim();
          List<ElementoUML> list = elementRepo.findByDiagramaId(diagramId);
          Optional<ElementoUML> target = list.stream()
              .filter(e -> e.getNombre().equalsIgnoreCase(targetName))
              .findFirst();

          if (target.isPresent()) {
            umlService.deleteElement(projectId, diagramId, email, target.get().getId());
            actionType = "ELIMINAR_CLASE";
            message = "Se ha eliminado la clase «" + targetName + "» y sus relaciones asociadas.";
            success = true;
          } else {
            message = "No se encontró ningún elemento llamado «" + targetName + "».";
          }
        }

      } else if (normalized.matches(".*(renombra|renombrar|cambiar nombre)\\s+.*")) {
        // 4. EDITAR CLASE: "Renombra la clase Cliente a Comprador"
        Pattern pRen = Pattern.compile("(?i)(?:renombra|renombrar|cambiar nombre)\\s+(?:la\\s+clase\\s+)?([a-zA-Z0-9_]+)\\s+a\\s+([a-zA-Z0-9_]+)");
        Matcher mRen = pRen.matcher(raw);
        if (mRen.find()) {
          String oldName = mRen.group(1).trim();
          String newName = mRen.group(2).trim();

          List<ElementoUML> list = elementRepo.findByDiagramaId(diagramId);
          Optional<ElementoUML> target = list.stream().filter(e -> e.getNombre().equalsIgnoreCase(oldName)).findFirst();
          if (target.isPresent()) {
            ElementoUML el = target.get();
            SaveElement updateDto = new SaveElement(el.getId(), el.getTipoElemento(), newName, el.getPosicionX(), el.getPosicionY(), el.getPropiedadesJson());
            umlService.saveElement(projectId, diagramId, email, updateDto);
            actionType = "EDITAR_CLASE";
            message = "Se renombró la clase «" + oldName + "» a «" + newName + "».";
            success = true;
          } else {
            message = "No se encontró la clase «" + oldName + "».";
          }
        }

      } else if (normalized.matches(".*(relacionar|relaciona|conectar|conecta)\\s+([a-zA-Z0-9_]+)\\s+con\\s+([a-zA-Z0-9_]+).*")) {
        // 5. CREAR RELACIÓN
        Pattern pRel = Pattern.compile("(?i)(?:relacionar|relaciona|conectar|conecta)\\s+([a-zA-Z0-9_]+)\\s+con\\s+([a-zA-Z0-9_]+)(?:\\s+(?:de\\s+tipo\\s+)?(herencia|asociacion|agregacion|composicion|dependencia))?(?:\\s+(?:con\\s+)?(?:multiplicidad|cardinalidad)?\\s*(1\\s*a\\s*n|n\\s*a\\s*m|1\\s*a\\s*1|uno\\s*a\\s*muchos|muchos\\s*a\\s*muchos|(?:0\\.\\.\\*|1\\.\\.\\*|0\\.\\.1|1|\\*)\\s*a\\s*(?:0\\.\\.\\*|1\\.\\.\\*|0\\.\\.1|1|\\*)))?");
        Matcher mRel = pRel.matcher(raw);
        if (mRel.find()) {
          String origenName = mRel.group(1).trim();
          String destinoName = mRel.group(2).trim();
          String tipoRelStr = mRel.group(3);
          String cardStr = mRel.group(4);

          List<ElementoUML> list = elementRepo.findByDiagramaId(diagramId);
          Optional<ElementoUML> origen = list.stream().filter(e -> e.getNombre().equalsIgnoreCase(origenName)).findFirst();
          Optional<ElementoUML> destino = list.stream().filter(e -> e.getNombre().equalsIgnoreCase(destinoName)).findFirst();

          if (origen.isPresent() && destino.isPresent()) {
            TipoRelacionUML tipoRel = TipoRelacionUML.ASOCIACION;
            if (tipoRelStr != null) {
              tipoRel = switch (tipoRelStr.toLowerCase()) {
                case "herencia" -> TipoRelacionUML.HERENCIA;
                case "agregacion" -> TipoRelacionUML.AGREGACION;
                case "composicion" -> TipoRelacionUML.COMPOSICION;
                case "dependencia" -> TipoRelacionUML.DEPENDENCIA;
                default -> TipoRelacionUML.ASOCIACION;
              };
            }

            String multOrigen = "1";
            String multDestino = "*";
            if (cardStr != null) {
              String[] exactMultiplicities = cardStr.split("\\s+a\\s+");
              if (exactMultiplicities.length == 2 && exactMultiplicities[0].matches("(?:0\\.\\.\\*|1\\.\\.\\*|0\\.\\.1|1|\\*)")) {
                multOrigen = exactMultiplicities[0];
                multDestino = exactMultiplicities[1];
              } else if (cardStr.contains("n a m") || cardStr.contains("muchos a muchos")) {
                multOrigen = "*";
                multDestino = "*";
              } else if (cardStr.contains("1 a 1") || cardStr.contains("uno a uno")) {
                multOrigen = "1";
                multDestino = "1";
              }
            }

            SaveRelation relDto = new SaveRelation(
                null,
                origen.get().getId(),
                destino.get().getId(),
                null,
                tipoRel,
                multOrigen,
                multDestino
            );
            umlService.saveRelation(projectId, diagramId, email, relDto);

            actionType = "CREAR_RELACION";
            message = "Relación " + tipoRel + " (" + multOrigen + " .. " + multDestino + ") establecida entre «" + origenName + "» y «" + destinoName + "».";
            success = true;
          } else {
            message = "No se pudieron encontrar ambas clases («" + origenName + "» y «" + destinoName + "»).";
          }
        }

      } else if (normalized.matches(".*(uno a muchos|1 a n|muchos a muchos|n a m|uno a uno|1 a 1).*")) {
        // 6. ESTABLECER MULTIPLICIDAD CONTEXTUAL: "Uno a muchos", "1 a N", "Muchos a muchos"
        List<RelacionUML> relList = relationRepo.findByDiagramaId(diagramId);
        RelacionUML targetRel = null;
        if (request.selectedRelationId() != null && !request.selectedRelationId().isBlank()) {
          try {
            UUID relId = UUID.fromString(request.selectedRelationId());
            targetRel = relList.stream().filter(r -> r.getId().equals(relId)).findFirst().orElse(null);
          } catch (Exception ignored) {}
        }
        if (targetRel == null && !relList.isEmpty()) {
          targetRel = relList.get(relList.size() - 1);
        }

        if (targetRel != null) {
          String multOrig = "1";
          String multDest = "*";
          if (normalized.contains("muchos a muchos") || normalized.contains("n a m")) {
            multOrig = "*";
            multDest = "*";
          } else if (normalized.contains("uno a uno") || normalized.contains("1 a 1")) {
            multOrig = "1";
            multDest = "1";
          }

          SaveRelation updateRel = new SaveRelation(
              targetRel.getId(),
              targetRel.getElementoOrigen().getId(),
              targetRel.getElementoDestino().getId(),
              targetRel.getNombreRelacion(),
              targetRel.getTipoRelacion(),
              multOrig,
              multDest
          );
          umlService.saveRelation(projectId, diagramId, email, updateRel);

          actionType = "ESTABLECER_MULTIPLICIDAD";
          message = "Multiplicidad de la relación actualizada a (" + multOrig + " .. " + multDest + ").";
          success = true;
        } else {
          message = "No hay relaciones en el diagrama para asignar multiplicidad.";
        }

      } else if (normalized.matches(".*(cambia|cambiar|tipo)\\s+.*(composicion|agregacion|herencia|asociacion|dependencia).*")) {
        // 7. EDITAR TIPO DE RELACIÓN CONTEXTUAL: "Cambia esa relación a composición"
        List<RelacionUML> relList = relationRepo.findByDiagramaId(diagramId);
        RelacionUML targetRel = null;
        if (request.selectedRelationId() != null && !request.selectedRelationId().isBlank()) {
          try {
            UUID relId = UUID.fromString(request.selectedRelationId());
            targetRel = relList.stream().filter(r -> r.getId().equals(relId)).findFirst().orElse(null);
          } catch (Exception ignored) {}
        }
        if (targetRel == null && !relList.isEmpty()) {
          targetRel = relList.get(relList.size() - 1);
        }

        if (targetRel != null) {
          TipoRelacionUML nuevoTipo = TipoRelacionUML.ASOCIACION;
          if (normalized.contains("composicion")) nuevoTipo = TipoRelacionUML.COMPOSICION;
          else if (normalized.contains("agregacion")) nuevoTipo = TipoRelacionUML.AGREGACION;
          else if (normalized.contains("herencia")) nuevoTipo = TipoRelacionUML.HERENCIA;
          else if (normalized.contains("dependencia")) nuevoTipo = TipoRelacionUML.DEPENDENCIA;

          SaveRelation updateRel = new SaveRelation(
              targetRel.getId(),
              targetRel.getElementoOrigen().getId(),
              targetRel.getElementoDestino().getId(),
              targetRel.getNombreRelacion(),
              nuevoTipo,
              targetRel.getMultiplicidadOrigen(),
              targetRel.getMultiplicidadDestino()
          );
          umlService.saveRelation(projectId, diagramId, email, updateRel);

          actionType = "EDITAR_RELACION";
          message = "Tipo de relación cambiado exitosamente a «" + nuevoTipo + "».";
          success = true;
        } else {
          message = "No se encontró ninguna relación para actualizar su tipo.";
        }
      }

    } catch (bo.edu.caseapp.error.ConflictException | bo.edu.caseapp.error.ForbiddenException
        | bo.edu.caseapp.error.NotFoundException | bo.edu.caseapp.error.BadRequestException e) {
      throw e;
    } catch (Exception e) {
      message = "Error al procesar el comando: " + e.getMessage();
    }

    try {
      iaRepo.save(new InteraccionIa(diagram, user, "COMANDO_" + request.resolvedSource(), raw, message, actionType));
    } catch (Exception ignored) {}

    wsHandler.broadcastDiagramChanged(diagramId, "COMMAND_EXECUTED", diagram.getRevision());
    DiagramFullView updated = umlService.getDiagram(projectId, diagramId, email);
    return new CommandResponse(success, actionType, message, updated);
  }

  @Transactional(readOnly = true)
  public ChatResponse chat(UUID projectId, UUID diagramId, String email, ChatRequest request) {
    projectService.requireAccess(projectId, email);
    validateDiagram(projectId, diagramId);

    String q = request.question().trim();
    if (llmProvider != null && llmProvider.isConfigured()) {
      try {
        String systemPrompt = "Eres el asistente y consultor experto en ingeniería de software y modelado UML de la herramienta CASE colaborativa.\n"
            + "Responde en español de forma concisa, técnica y didáctica. Si el usuario pregunta sobre conceptos UML (asociación, herencia, composición, etc.), explícalos claramente.\n"
            + "Si pregunta sobre el diseño del diagrama actual, analiza el contexto provisto y sugiere mejoras precisas.\n"
            + "Termina siempre con una sugerencia de comando accionable que el usuario pueda escribir o dictar en el editor.";

        LlmResponse resp = aiOrchestrationService != null
            ? aiOrchestrationService.generate(email, projectId, diagramId, AiOperation.TECHNICAL_CHAT,
                systemPrompt, List.of(LlmMessage.user(q)), 800, 0.3)
            : llmProvider.chat(List.of(LlmMessage.system(systemPrompt), LlmMessage.user(q)), 800, 0.3);

        if (resp.success() && resp.content() != null && !resp.content().isBlank()) {
          String full = resp.content().trim();
          String answer = full;
          String suggestion = null;
          int sugIdx = full.lastIndexOf("Sugerencia:");
          if (sugIdx < 0) sugIdx = full.lastIndexOf("Pruébalo con:");
          if (sugIdx < 0) sugIdx = full.lastIndexOf("Prueba:");
          if (sugIdx >= 0) {
            answer = full.substring(0, sugIdx).trim();
            suggestion = full.substring(sugIdx).replaceAll("(?i)^(?:Sugerencia|Pruébalo con|Prueba):?\s*", "").trim();
          }
          return new ChatResponse(answer, suggestion);
        }
      } catch (Exception ex) {
        log.warn("Fallo en consulta de chat con Groq, recurriendo a respuestas base: {}", ex.getMessage());
      }
    }

    String lower = q.toLowerCase(Locale.ROOT);
    String answer;
    String suggestion = null;

    if (lower.contains("herencia") || lower.contains("generaliz")) {
      answer = "En UML, la **Herencia (Generalización)** representa una relación 'es-un'. La subclase hereda atributos y operaciones de la superclase. Se representa con una línea sólida y una flecha triangular vacía hacia la clase padre.";
      suggestion = "Puedes decir: 'Crear clase Perro con herencia a Animal'.";
    } else if (lower.contains("composicion")) {
      answer = "La **Composición** es una forma fuerte de agregación donde las partes tienen un ciclo de vida dependiente del todo. Si se destruye el objeto compuesto, se eliminan sus partes. Se representa con un rombo relleno en el extremo del compuesto.";
      suggestion = "Puedes decir: 'Relacionar Factura con ItemFactura composicion 1 a N'.";
    } else if (lower.contains("agregacion")) {
      answer = "La **Agregación** representa una relación 'tiene-un' débil. Las partes pueden existir independientemente del objeto contenedor. Se representa con un rombo vacío.";
      suggestion = "Ejemplo: Un Departamento agrega Profesores.";
    } else if (lower.contains("clave foranea") || lower.contains("foreign key") || lower.contains("fk")) {
      answer = "En la transformación a modelo relacional (CU06), las relaciones 1:N propagan la clave primaria del lado '1' como clave foránea (FK) en la tabla del lado 'N'. Para relaciones N:M, se genera automáticamente una tabla intermedia con ambas claves foráneas.";
      suggestion = "Usa el botón 'Modelo Relacional' en la barra superior para ver el script SQL DDL.";
    } else if (lower.contains("backend") || lower.contains("generar") || lower.contains("codigo")) {
      answer = "La plataforma genera un backend Spring Boot 3 con Java 21 estructurado en Controller -> Service -> Repository -> Entity, conectado a PostgreSQL, junto con un cliente frontend React. Puedes previsualizar el código y descargar el proyecto completo en ZIP.";
      suggestion = "Presiona el botón 'Generar Software' en la barra de herramientas.";
    } else {
      answer = "Soy tu asistente inteligente de modelado UML y generación CASE impulsado por GroqCloud. Puedo modificar el diagrama por ti mediante comandos de voz o texto ('Crea clase Usuario', 'Agrega atributo email a Usuario', 'Relaciona Usuario con Rol') y asistirte en el diseño de software.";
      suggestion = "Prueba el comando: 'Crear clase Producto con atributos nombre:String, precio:Double'";
    }

    return new ChatResponse(answer, suggestion);
  }

  public ImageAnalysisResponse analyzeImage(UUID projectId, UUID diagramId, String email, AnalyzeImageRequest request) {
    projectService.requireCanEdit(projectId, email);
    validateDiagram(projectId, diagramId);
    String image = request.imageBase64();
    if (image == null || image.length() > 14_000_000) throw new BadRequestException("La imagen está vacía o supera el máximo de 10 MB.");
    String mimeType = "image/png";
    String imageData = image;
    if (image.startsWith("data:")) {
      int separator = image.indexOf(',');
      if (separator < 0) throw new BadRequestException("El formato de imagen enviado no es válido.");
      String metadata = image.substring(5, separator);
      if (!metadata.toLowerCase(Locale.ROOT).endsWith(";base64")) throw new BadRequestException("La imagen debe enviarse codificada en Base64.");
      mimeType = metadata.substring(0, metadata.length() - 7).toLowerCase(Locale.ROOT);
      imageData = image.substring(separator + 1);
    }
    if (!Set.of("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif").contains(mimeType)) {
      throw new BadRequestException("Formato no compatible. Usa PNG, JPG, WEBP, HEIC o HEIF.");
    }
    try {
      if (Base64.getDecoder().decode(imageData).length > 10_000_000) throw new BadRequestException("La imagen supera el máximo de 10 MB.");
    } catch (IllegalArgumentException exception) {
      throw new BadRequestException("La imagen Base64 no es válida.");
    }

    if (llmProvider == null || !llmProvider.isConfigured()) {
      log.info("LlmProvider no configurado; ejecutando fallback local para análisis de imagen.");
      return analyzeWithLocalFallback(imageData, request.promptHint(), "El proveedor de IA no está configurado.");
    }

    ImageModel model = analyzeWithGroq(projectId, diagramId, email, imageData, mimeType, request.promptHint());
    return new ImageAnalysisResponse(model, model.warnings(), "GROQ");
  }

  private ImageAnalysisResponse analyzeWithLocalFallback(String imageBase64, String hint, String reason) {
    List<DetectedClass> detectedClasses = new ArrayList<>();
    List<DetectedRelation> detectedRelations = new ArrayList<>();
    boolean recognized = tryNativeTesseract(imageBase64, detectedClasses, detectedRelations);
    if (!recognized) recognized = tryOllamaVision(imageBase64, Objects.toString(hint, ""), detectedClasses, detectedRelations);
    if (!recognized || detectedClasses.isEmpty()) {
      throw new BadRequestException(reason + " Tampoco fue posible reconocer clases con el fallback local (Tesseract/Ollama). No se modificó el diagrama.");
    }

    List<String> warnings = new ArrayList<>();
    warnings.add(reason + " Se utilizó el fallback local; revisa cuidadosamente nombres, tipos y relaciones antes de aplicar el modelo.");
    List<ImageClass> classes = new ArrayList<>();
    for (DetectedClass detected : detectedClasses) {
      List<ImageAttribute> attributes = detected.attributes.stream()
          .map(attribute -> new ImageAttribute(
              Set.of("+", "-", "#", "~").contains(attribute.visibility) ? attribute.visibility : null,
              attribute.name,
              "?".equals(attribute.type) ? null : attribute.type,
              0.56))
          .toList();
      List<ImageMethod> methods = detected.methods.stream()
          .map(method -> new ImageMethod(
              Set.of("+", "-", "#", "~").contains(method.visibility) ? method.visibility : null,
              method.name,
              "?".equals(method.returnType) ? null : method.returnType,
              method.parameters,
              0.56))
          .toList();
      classes.add(new ImageClass(detected.name, detected.tipo, 0.72, detected.posX, detected.posY, attributes, methods));
    }
    List<ImageRelation> relations = detectedRelations.stream()
        .map(relation -> new ImageRelation(relation.source, relation.target, relation.name, relation.tipo,
            relation.multSource, relation.multTarget, 0.70))
        .toList();
    if (!relations.isEmpty()) warnings.add("El fallback local estima las relaciones por texto y posición; confirma el tipo, dirección y multiplicidad visualmente.");
    ImageModel model = UmlImageModelValidator.validateForApply(new ImageModel(classes, relations, warnings));
    return new ImageAnalysisResponse(model, model.warnings(), "LOCAL");
  }

  public ImageReconstructionResponse applyImageModel(UUID projectId, UUID diagramId, String email, ApplyImageModelRequest request) {
    return applyImageModel(projectId, diagramId, email, request, null);
  }

  public ImageReconstructionResponse applyImageModel(UUID projectId, UUID diagramId, String email, ApplyImageModelRequest request, Long expectedRevision) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = validateDiagram(projectId, diagramId);
    umlService.requireNotLocked(diagram, email);
    User user = userRepo.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new NotFoundException("Usuario no encontrado."));
    ImageModel model = UmlImageModelValidator.validateForApply(request.model());
    if (request.mode() == null) throw new BadRequestException("Selecciona si deseas agregar o reemplazar el contenido.");

    List<String> warnings = new ArrayList<>(model.warnings());
    long newRev = umlService.reserveNextRevision(diagramId, expectedRevision);
    diagram.setRevision(newRev);

    if (request.mode() == ImageImportMode.REPLACE) {
      relationRepo.deleteByDiagramaId(diagramId);
      relationRepo.flush();
      elementRepo.deleteByDiagramaId(diagramId);
      elementRepo.flush();
    }

    List<ElementoUML> existingElements = elementRepo.findByDiagramaId(diagramId);
    Map<String, UUID> classIdByName = new HashMap<>();
    for (ElementoUML existing : existingElements) classIdByName.putIfAbsent(existing.getNombre().toLowerCase(Locale.ROOT), existing.getId());
    double maxX = existingElements.stream().mapToDouble(ElementoUML::getPosicionX).max().orElse(-360);
    double offsetX = request.mode() == ImageImportMode.ADD && !existingElements.isEmpty() ? Math.min(maxX + 360, 4000) : 0;
    int createdClasses = 0, createdRelations = 0, index = 0;

    for (ImageClass detected : model.classes()) {
      String key = detected.name().toLowerCase(Locale.ROOT);
      if (classIdByName.containsKey(key)) {
        warnings.add("No se duplicó «" + detected.name() + "» porque ya existe en el diagrama.");
        continue;
      }
      ObjectNode properties = objectMapper.createObjectNode();
      ArrayNode attributes = properties.putArray("attributes");
      for (ImageAttribute attribute : detected.attributes()) {
        attributes.addObject().put("id", UUID.randomUUID().toString()).put("visibility", attribute.visibility())
            .put("name", attribute.name()).put("type", attribute.type());
      }
      ArrayNode methods = properties.putArray("methods");
      for (ImageMethod method : detected.methods()) {
        methods.addObject().put("id", UUID.randomUUID().toString()).put("visibility", method.visibility())
            .put("name", method.name()).put("returnType", method.returnType()).put("parameters", method.parameters());
      }
      int col = index % 3, row = index / 3;
      double x = detected.x() != null ? detected.x() + offsetX : offsetX + 120 + col * 340.0;
      double y = detected.y() != null ? detected.y() : 90 + row * 260.0;
      UmlResponses.ElementDetail saved = umlService.saveElement(projectId, diagramId, email,
          new SaveElement(null, detected.type(), detected.name(), x, y, properties.toString()));
      classIdByName.put(key, saved.id());
      createdClasses++; index++;
    }

    Set<String> existingRelationKeys = new HashSet<>();
    for (RelacionUML existing : relationRepo.findByDiagramaId(diagramId)) {
      existingRelationKeys.add(relationKey(existing.getElementoOrigen().getId(), existing.getElementoDestino().getId(), existing.getTipoRelacion()));
    }
    for (ImageRelation relation : model.relations()) {
      UUID sourceId = classIdByName.get(relation.source().toLowerCase(Locale.ROOT));
      UUID targetId = classIdByName.get(relation.target().toLowerCase(Locale.ROOT));
      if (sourceId == null || targetId == null) {
        warnings.add("No se creó la relación «" + relation.source() + " → " + relation.target() + "» porque falta un elemento en el diagrama.");
        continue;
      }
      if (relation.associationClass() != null) {
        UUID associationId = classIdByName.get(relation.associationClass().toLowerCase(Locale.ROOT));
        if (associationId == null) {
          warnings.add("No se pudo aplicar la clase asociativa «" + relation.associationClass() + "» porque no está disponible en el diagrama.");
          continue;
        }
        String firstKey = relationKey(sourceId, associationId, TipoRelacionUML.ASOCIACION);
        String secondKey = relationKey(targetId, associationId, TipoRelacionUML.ASOCIACION);
        RelacionUML existingManyToMany = relationRepo.findByDiagramaId(diagramId).stream()
            .filter(existing -> isMany(existing.getMultiplicidadOrigen()) && isMany(existing.getMultiplicidadDestino()))
            .filter(existing -> {
              UUID existingSource = existing.getElementoOrigen().getId();
              UUID existingTarget = existing.getElementoDestino().getId();
              return existingSource.equals(sourceId) && existingTarget.equals(targetId)
                  || existingSource.equals(targetId) && existingTarget.equals(sourceId);
            }).findFirst().orElse(null);
        if (existingManyToMany != null) {
          umlService.saveRelation(projectId, diagramId, email, new SaveRelation(existingManyToMany.getId(), sourceId,
              associationId, relation.name(), TipoRelacionUML.ASOCIACION, "1", "*"));
          existingRelationKeys.remove(relationKey(sourceId, targetId, existingManyToMany.getTipoRelacion()));
          existingRelationKeys.add(firstKey);
          createdRelations++;
        } else if (!existingRelationKeys.add(firstKey)) {
          warnings.add("La conexión de «" + relation.source() + "» con la clase asociativa ya existe.");
        } else {
          umlService.saveRelation(projectId, diagramId, email, new SaveRelation(null, sourceId, associationId,
              relation.name(), TipoRelacionUML.ASOCIACION, "1", "*"));
          createdRelations++;
        }
        if (existingRelationKeys.add(secondKey)) {
          umlService.saveRelation(projectId, diagramId, email, new SaveRelation(null, targetId, associationId,
              relation.name(), TipoRelacionUML.ASOCIACION, "1", "*"));
          createdRelations++;
        } else {
          warnings.add("La conexión de «" + relation.target() + "» con la clase asociativa ya existe.");
        }
      } else {
        String key = relationKey(sourceId, targetId, relation.type());
        if (!existingRelationKeys.add(key)) { warnings.add("No se duplicó la relación «" + relation.source() + " → " + relation.target() + "."); continue; }
        umlService.saveRelation(projectId, diagramId, email, new SaveRelation(null, sourceId, targetId, relation.name(), relation.type(), relation.multiplicitySource(), relation.multiplicityTarget()));
        createdRelations++;
      }
    }

    if (createdClasses == 0 && createdRelations == 0 && request.mode() == ImageImportMode.ADD) {
      warnings.add("No se agregaron elementos nuevos; el contenido existente se conservó.");
      return new ImageReconstructionResponse(false, "No había elementos nuevos para agregar.", 0, 0, umlService.getDiagram(projectId, diagramId, email), List.copyOf(warnings));
    }
    umlService.notifyCollabAfterCommit(diagramId, request.mode() == ImageImportMode.REPLACE ? "IMAGE_MODEL_REPLACED" : "IMAGE_MODEL_ADDED", newRev, email, "IMPORT");
    String message = "Se procesaron " + createdClasses + " elementos y " + createdRelations + " relaciones desde la imagen.";
    iaRepo.save(new InteraccionIa(diagram, user, "RECONSTRUCCION_IMAGEN", "Imagen: " + Objects.toString(request.filename(), "diagrama"), message, null));
    return new ImageReconstructionResponse(true, message, createdClasses, createdRelations, umlService.getDiagram(projectId, diagramId, email), List.copyOf(warnings));
  }

  private static String relationKey(UUID source, UUID target, TipoRelacionUML type) {
    if (type == TipoRelacionUML.ASOCIACION && source.compareTo(target) > 0) {
      UUID swap = source;
      source = target;
      target = swap;
    }
    return source + "|" + target + "|" + type;
  }

  private ImageModel analyzeWithGroq(UUID projectId, UUID diagramId, String email, String imageBase64, String mimeType, String hint) {
    String unifiedPrompt = """
        Perform an exhaustive reverse-engineering of this UML class diagram image into valid JSON:
        Step 1: Extract EVERY visible class, interface, abstract class, and enumeration with its name, attributes, and methods.
        Step 2: Inspect EVERY class boundary on all 4 sides for ALL connector lines (associations, inheritances, aggregations, compositions, dependencies).
        Step 3: Preserve the image layout. For each class, return x and y as the pixel coordinates of the CENTER of its box, measured from the image's top-left corner (x increases rightward, y downward). Do not use the same/default coordinates or rearrange classes into a grid; the relative positions must match the image.
        Return ONLY valid JSON matching this exact schema:
        {
          "classes": [
            {
              "name": "ExactClassName",
              "type": "CLASE" | "INTERFAZ" | "CLASE_ABSTRACTA" | "ENUMERACION",
              "confidence": 0.95,
              "x": 100.0,
              "y": 120.0,
              "attributes": [{"visibility": "-"|"+"|"#"|"~", "name": "attrName", "type": "String", "confidence": 0.9}],
              "methods": [{"visibility": "+", "name": "methodName", "returnType": "void", "parameters": "", "confidence": 0.9}]
            }
          ],
          "relations": [
            {
              "source": "SourceClass",
              "target": "TargetClass",
              "type": "ASOCIACION" | "HERENCIA" | "AGREGACION" | "COMPOSICION" | "DEPENDENCIA",
              "name": null,
              "multiplicitySource": "1",
              "multiplicityTarget": "*",
              "confidence": 0.9,
              "associationClass": null
            }
          ],
          "warnings": []
        }
        Rules:
        - In herencia: source is the subclass and target is the superclass.
        - In composicion/agregacion: source is the owner class (near the diamond).
        - Multiplicities: use exact strings like "1", "*", "0..1", "1..*".
        - UML association classes/intermediate classes: when a box named like Detalle_Venta (or DetalleVenta) is linked to an endpoint of a relationship between Venta and Producto, or has a dashed connector to that relationship line, set associationClass="Detalle_Venta" on Venta–Producto. A recognized associationClass in this application means the relationship is M:N: return multiplicity "0..*" at BOTH endpoints, even if a label is faint or appears to say "1". Do NOT emit Detalle_Venta–Venta or Detalle_Venta–Producto as ordinary relations. Keep the class and its attributes in classes; application converts the M:N to two 1:N links.
        - More generally, when a class box has a dashed connector to an association line, set associationClass to that class name on the relationship between the two endpoint classes. Do not output a separate ordinary edge from that association class to either endpoint.
        - Set associationClass to null for ordinary relationships.
        - Do not output markdown code fences (```json). Return raw JSON only.
        """ + (hint == null || hint.isBlank() ? "" : "\nDomain hint provided by developer: " + hint.trim());

    try {
      LlmResponse response = aiOrchestrationService != null
          ? aiOrchestrationService.analyzeImage(email, projectId, diagramId, AiOperation.IMAGE_ANALYSIS,
              unifiedPrompt, imageBase64, mimeType, 1500)
          : llmProvider.analyzeImage(unifiedPrompt, imageBase64, mimeType, 1500);
      if (response.success() && response.content() != null && !response.content().isBlank()) {
        String jsonText = response.content().trim();
        if (jsonText.startsWith("```")) {
          int s = jsonText.indexOf('{');
          int e = jsonText.lastIndexOf('}');
          if (s >= 0 && e > s) jsonText = jsonText.substring(s, e + 1);
        }
        ImageModel model = UmlImageModelValidator.parseAndValidate(jsonText);
        if (model != null && !model.classes().isEmpty()) {
          log.info("GroqCloud Vision [{}] reconoció {} clases y {} relaciones.", llmProvider.getModelName(), model.classes().size(), model.relations().size());
          return model;
        }
      } else {
        log.warn("GroqCloud Vision falló: HTTP {} - {}", response.httpStatusCode(), response.errorMessage());
      }
    } catch (Exception e) {
      log.warn("Excepción al consultar visión en GroqCloud: {}", e.getMessage());
    }

    // Fallback secundario si Groq Vision no extrae clases suficientes
    log.info("GroqCloud Vision no detectó clases legibles; ejecutando fallback local.");
    ImageAnalysisResponse fallbackResp = analyzeWithLocalFallback(imageBase64, hint, "GroqCloud Vision no reconoció clases en la imagen.");
    return fallbackResp.model();
  }

  private JsonNode parseLlmJsonNode(String text) {
    try {
      String clean = text.trim();
      if (clean.contains("```")) {
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start >= 0 && end > start) {
          clean = clean.substring(start, end + 1);
        }
      }
      return objectMapper.readTree(clean);
    } catch (Exception e) {
      return objectMapper.createObjectNode();
    }
  }

  private ImageReconstructionResponse reconstructFromImageLegacy(
      UUID projectId,
      UUID diagramId,
      String email,
      AiRequests.LegacyReconstructImageRequest request
  ) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = validateDiagram(projectId, diagramId);
    User user = userRepo.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new NotFoundException("Usuario no encontrado."));

    String filename = request.filename() != null && !request.filename().isBlank() ? request.filename().trim() : "diagrama.png";
    String hint = request.promptHint() != null ? request.promptHint().trim() : "";

    List<DetectedClass> detectedClasses = new ArrayList<>();
    List<DetectedRelation> detectedRelations = new ArrayList<>();

    // 1. Si GroqCloud está configurado, priorizar visión multimodal de IA en backend
    if (llmProvider != null && llmProvider.isConfigured()) {
      log.info("Iniciando análisis multimodal con GroqCloud Vision en backend...");
      tryGroqVision(projectId, diagramId, email, request.imageBase64(), hint, detectedClasses, detectedRelations);
    }

    // 2. Si no se detectaron clases con Groq, usar lo detectado por el cliente
    if (detectedClasses.isEmpty() && request.clientDetectedClasses() != null && !request.clientDetectedClasses().isEmpty()) {
      for (AiRequests.LegacyDetectedClass cdc : request.clientDetectedClasses()) {
        if (cdc.name() == null || cdc.name().isBlank()) continue;
        DetectedClass dc = new DetectedClass(cdc.name().trim());
        if (cdc.posX() != null) dc.posX = cdc.posX();
        if (cdc.posY() != null) dc.posY = cdc.posY();
        if (cdc.attributes() != null) {
          for (AiRequests.LegacyDetectedAttr cda : cdc.attributes()) {
            if (cda.name() != null && !cda.name().isBlank()) {
              dc.attributes.add(new DetectedAttr(cda.visibility(), cda.name().trim(), cda.type() != null ? cda.type().trim() : "String"));
            }
          }
        }
        if (cdc.methods() != null) {
          for (AiRequests.LegacyDetectedMethod cdm : cdc.methods()) {
            if (cdm.name() != null && !cdm.name().isBlank()) {
              dc.methods.add(new DetectedMethod(cdm.visibility(), cdm.name().trim(), cdm.returnType() != null ? cdm.returnType().trim() : "void"));
            }
          }
        }
        detectedClasses.add(dc);
      }
      if (request.clientDetectedRelations() != null) {
        for (AiRequests.LegacyDetectedRelation cdr : request.clientDetectedRelations()) {
          if (cdr.source() != null && cdr.target() != null && !cdr.source().isBlank() && !cdr.target().isBlank()) {
            TipoRelacionUML tipoRel = TipoRelacionUML.ASOCIACION;
            if (cdr.tipo() != null) {
              try {
                tipoRel = TipoRelacionUML.valueOf(cdr.tipo().toUpperCase());
              } catch (Exception ignored) {}
            }
            detectedRelations.add(new DetectedRelation(
                cdr.source().trim(),
                cdr.target().trim(),
                cdr.name(),
                tipoRel,
                cdr.multSource() != null ? cdr.multSource().trim() : "1",
                cdr.multTarget() != null ? cdr.multTarget().trim() : "*"
            ));
          }
        }
      }
    }

    // 3. Si aún no hay clases detectadas, intentar OCR nativo local en el servidor (Tesseract)
    if (detectedClasses.isEmpty()) {
      tryNativeTesseract(request.imageBase64(), detectedClasses, detectedRelations);
    }

    // 4. Intentar modelo de visión local Ollama si está configurado
    if (detectedClasses.isEmpty()) {
      tryOllamaVision(request.imageBase64(), hint, detectedClasses, detectedRelations);
    }

    // 5. Si aún no hay clases detectadas pero el usuario proporcionó nombres en la pista
    if (detectedClasses.isEmpty() && (hint.contains(",") || hint.contains(";"))) {
      extractFromUserHint(hint, detectedClasses, detectedRelations);
    }

    // Si después de todos los análisis no se detectó nada, retornar aviso claro sin inyectar datos falsos
    if (detectedClasses.isEmpty()) {
      String message = "No se pudieron reconocer clases en la imagen. Asegúrate de que el diagrama sea legible o configura GROQ_API_KEY en backend/.env.";
      DiagramFullView currentDiagram = umlService.getDiagram(projectId, diagramId, email);
      return new ImageReconstructionResponse(false, message, 0, 0, currentDiagram);
    }

    // 6. Crear o actualizar los elementos en el lienzo UML
    Map<String, UUID> classIdByName = new HashMap<>();
    List<ElementoUML> existingElements = elementRepo.findByDiagramaId(diagramId);
    for (ElementoUML ex : existingElements) {
      classIdByName.put(ex.getNombre().toLowerCase(), ex.getId());

      // Limpiar doble paréntesis en métodos ya almacenados en base de datos
      try {
        JsonNode pNode = objectMapper.readTree(ex.getPropiedadesJson());
        if (pNode.has("methods") && pNode.get("methods").isArray()) {
          boolean changed = false;
          for (JsonNode mNode : pNode.get("methods")) {
            String mName = mNode.path("name").asText("");
            if (mName.endsWith("()")) {
              ((ObjectNode) mNode).put("name", mName.substring(0, mName.length() - 2).trim());
              changed = true;
            }
          }
          if (changed) {
            ex.setPropiedadesJson(objectMapper.writeValueAsString(pNode));
            elementRepo.save(ex);
          }
        }
      } catch (Exception ignored) {}
    }

    double baseX = 100.0;
    double baseY = 80.0;
    int col = 0;
    int row = 0;

    for (DetectedClass dc : detectedClasses) {
      String dcNameLower = dc.name.toLowerCase();
      if (classIdByName.containsKey(dcNameLower)) {
        // Actualizar la posición de la clase existente con la posición detectada en la imagen
        if (dc.posX != null && dc.posY != null) {
          UUID exId = classIdByName.get(dcNameLower);
          for (ElementoUML ex : existingElements) {
            if (ex.getId().equals(exId)) {
              ex.updatePosition(dc.posX, dc.posY);
              elementRepo.save(ex);
              break;
            }
          }
        }
        continue;
      }

      ObjectNode propNode = objectMapper.createObjectNode();
      ArrayNode attrs = propNode.putArray("attributes");
      for (DetectedAttr a : dc.attributes) {
        attrs.addObject()
            .put("id", UUID.randomUUID().toString())
            .put("visibility", a.visibility != null ? a.visibility : "-")
            .put("name", a.name)
            .put("type", capitalizeType(a.type));
      }

      ArrayNode methods = propNode.putArray("methods");
      for (DetectedMethod m : dc.methods) {
        String cleanMethodName = m.name != null ? m.name.replaceAll("\\(\\s*\\)$", "").trim() : "metodo";
        methods.addObject()
            .put("id", UUID.randomUUID().toString())
            .put("visibility", m.visibility != null ? m.visibility : "+")
            .put("name", cleanMethodName)
            .put("returnType", capitalizeType(m.returnType))
            .put("parameters", m.parameters);
      }

      double posX = dc.posX != null ? dc.posX : baseX + (col * 320.0);
      double posY = dc.posY != null ? dc.posY : baseY + (row * 240.0);
      col++;
      if (col > 2) {
        col = 0;
        row++;
      }

      SaveElement se = new SaveElement(null, dc.tipo != null ? dc.tipo : TipoElementoUML.CLASE, dc.name, posX, posY, propNode.toString());
      UmlResponses.ElementDetail saved = umlService.saveElement(projectId, diagramId, email, se);
      classIdByName.put(dc.name.toLowerCase(), saved.id());
    }

    // 7. Crear relaciones detectadas (filtrando diagonales no existentes)
    int relsCreated = 0;
    Set<String> seenPairs = new HashSet<>();

    for (DetectedRelation dr : detectedRelations) {
      if (dr.source == null || dr.target == null || dr.source.equalsIgnoreCase(dr.target)) continue;

      String s = dr.source.trim();
      String t = dr.target.trim();
      String sLower = s.toLowerCase();
      String tLower = t.toLowerCase();
      String pairKey = sLower.compareTo(tLower) < 0 ? sLower + ":::" + tLower : tLower + ":::" + sLower;
      if (seenPairs.contains(pairKey)) continue;
      seenPairs.add(pairKey);

      UUID origenId = classIdByName.get(sLower);
      UUID destinoId = classIdByName.get(tLower);

      if (origenId != null && destinoId != null && !origenId.equals(destinoId)) {
        boolean alreadyExists = relationRepo.findByDiagramaId(diagramId).stream()
            .anyMatch(r -> (r.getElementoOrigen().getId().equals(origenId) && r.getElementoDestino().getId().equals(destinoId)) ||
                           (r.getElementoOrigen().getId().equals(destinoId) && r.getElementoDestino().getId().equals(origenId)));

        if (!alreadyExists) {
          SaveRelation sr = new SaveRelation(
              null,
              origenId,
              destinoId,
              dr.name,
              dr.tipo != null ? dr.tipo : TipoRelacionUML.ASOCIACION,
              dr.multSource != null ? dr.multSource : "1",
              dr.multTarget != null ? dr.multTarget : "*"
          );
          umlService.saveRelation(projectId, diagramId, email, sr);
          relsCreated++;
        }
      }
    }

    // Notificar cambios al lienzo en tiempo real
    wsHandler.broadcastDiagramChanged(diagramId, "IMAGE_RECONSTRUCTED");

    // 7. Registrar en historial de IA
    String message = "Reconstrucción completada exitosamente. Se insertaron " + detectedClasses.size() + " clases y " + relsCreated + " relaciones a partir de la imagen «" + filename + "».";
    iaRepo.save(new InteraccionIa(
        diagram,
        user,
        "RECONSTRUCCION_IMAGEN",
        "Imagen: " + filename + (hint.isBlank() ? "" : " (Pista: " + hint + ")"),
        message,
        null
    ));

    DiagramFullView updatedDiagram = umlService.getDiagram(projectId, diagramId, email);
    return new ImageReconstructionResponse(true, message, detectedClasses.size(), relsCreated, updatedDiagram);
  }

  private boolean tryGroqVision(UUID projectId, UUID diagramId, String email, String base64Image, String hint,
      List<DetectedClass> outClasses, List<DetectedRelation> outRelations) {
    if (llmProvider == null || !llmProvider.isConfigured() || base64Image == null || base64Image.isBlank()) return false;
    try {
      String cleanBase64 = base64Image.contains(",")
          ? base64Image.substring(base64Image.indexOf(",") + 1)
          : base64Image;
      String mimeType = base64Image.startsWith("data:image/png") ? "image/png" : "image/jpeg";

      String prompt = """
          You are an expert UML Reverse Engineering system. Perform a visual analysis of this UML diagram image in detail.
          Stage 1: Extract EVERY class, interface, abstract class and enumeration with its name, attributes, and methods.
          Stage 2: Inspect all 4 sides of EVERY class box for ALL connector lines (associations, inheritances, aggregations, compositions, dependencies).
          For each class: name, attributes (visibility: '-'|'+'|'#', name, type), methods (visibility, name, returnType), estimated posX (100..860) and posY (80..420).
          For each relationship: source, target, name, tipo ('ASOCIACION'|'HERENCIA'|'AGREGACION'|'COMPOSICION'|'DEPENDENCIA'), multSource, multTarget.
          Return ONLY valid JSON matching this format: {"classes": [...], "relations": [...]}
          """ + (hint == null || hint.isBlank() ? "" : "\nDomain context: " + hint.trim());

      LlmResponse resp = aiOrchestrationService != null
          ? aiOrchestrationService.analyzeImage(email, projectId, diagramId, AiOperation.IMAGE_RECONSTRUCTION,
              prompt, cleanBase64, mimeType, 1500)
          : llmProvider.analyzeImage(prompt, cleanBase64, mimeType, 1500);
      if (resp.success() && resp.content() != null && !resp.content().isBlank()) {
        String textResp = resp.content().trim();
        int jsonStart = textResp.indexOf('{');
        int jsonEnd = textResp.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
          JsonNode parsed = objectMapper.readTree(textResp.substring(jsonStart, jsonEnd + 1));
          parseJsonIntoDetectedElements(parsed, outClasses, outRelations);
          if (!outClasses.isEmpty()) {
            log.info("GroqCloud Vision [{}] reconoció exitosamente {} clases y {} relaciones.", llmProvider.getModelName(), outClasses.size(), outRelations.size());
            return true;
          }
        }
      }
    } catch (Exception e) {
      log.warn("Error consultando GroqCloud Vision en backend: {}", e.getMessage());
    }
    return false;
  }

  private void parseJsonIntoDetectedElements(JsonNode parsed, List<DetectedClass> outClasses, List<DetectedRelation> outRelations) {
    if (parsed.has("classes") && parsed.get("classes").isArray()) {
      for (JsonNode cNode : parsed.get("classes")) {
        String cName = cNode.path("name").asText("").trim();
        if (cName.isBlank()) continue;
        DetectedClass dc = new DetectedClass(cName);
        if (cNode.has("posX")) dc.posX = cNode.path("posX").asDouble();
        if (cNode.has("posY")) dc.posY = cNode.path("posY").asDouble();
        if (cNode.has("attributes") && cNode.get("attributes").isArray()) {
          for (JsonNode aNode : cNode.get("attributes")) {
            String aName = aNode.path("name").asText("").trim();
            if (!aName.isBlank()) {
              dc.attributes.add(new DetectedAttr(
                  aNode.path("visibility").asText("~"),
                  aName,
                  aNode.path("type").asText("?")
              ));
            }
          }
        }
        if (cNode.has("methods") && cNode.get("methods").isArray()) {
          for (JsonNode mNode : cNode.get("methods")) {
            String mName = mNode.path("name").asText("").trim();
            if (!mName.isBlank()) {
              dc.methods.add(new DetectedMethod(
                  mNode.path("visibility").asText("~"),
                  mName,
                  mNode.path("returnType").asText("?")
              ));
            }
          }
        }
        outClasses.add(dc);
      }
    }
    if (parsed.has("relations") && parsed.get("relations").isArray()) {
      Set<String> seenPairs = new HashSet<>();
      for (JsonNode rNode : parsed.get("relations")) {
        String src = rNode.path("source").asText("").trim();
        String tgt = rNode.path("target").asText("").trim();
        if (!src.isBlank() && !tgt.isBlank() && !src.equalsIgnoreCase(tgt)) {
          String sLower = src.toLowerCase();
          String tLower = tgt.toLowerCase();
          String pairKey = sLower.compareTo(tLower) < 0 ? sLower + ":::" + tLower : tLower + ":::" + sLower;
          if (seenPairs.contains(pairKey)) continue;
          seenPairs.add(pairKey);

          TipoRelacionUML tipoRel = TipoRelacionUML.ASOCIACION;
          String tipoStr = rNode.path("tipo").asText("ASOCIACION");
          try {
            tipoRel = TipoRelacionUML.valueOf(tipoStr.toUpperCase());
          } catch (Exception ignored) {}
          outRelations.add(new DetectedRelation(
              src,
              tgt,
              rNode.path("name").asText(null),
              tipoRel,
              rNode.path("multSource").isMissingNode() || rNode.path("multSource").isNull() ? null : rNode.path("multSource").asText(),
              rNode.path("multTarget").isMissingNode() || rNode.path("multTarget").isNull() ? null : rNode.path("multTarget").asText()
          ));
        }
      }
    }
  }

  private boolean tryOllamaVision(String base64Image, String hint, List<DetectedClass> outClasses, List<DetectedRelation> outRelations) {
    try {
      String ollamaHost = System.getenv().getOrDefault("OLLAMA_HOST", "http://host.docker.internal:11434");
      java.net.URI uri = java.net.URI.create(ollamaHost + "/api/generate");
      java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
          .connectTimeout(java.time.Duration.ofMillis(1200))
          .build();

      String prompt = "You are a UML Diagram Recognition Expert. Analyze this image. Extract classes, attributes, methods, and relationships. Return ONLY valid JSON format: {\"classes\":[{\"name\":\"...\",\"attributes\":[{\"name\":\"...\",\"type\":\"String\"}],\"methods\":[]}],\"relations\":[{\"source\":\"...\",\"target\":\"...\",\"type\":\"ASOCIACION\",\"multSource\":\"1\",\"multTarget\":\"*\"}]}";

      ObjectNode reqJson = objectMapper.createObjectNode();
      reqJson.put("model", "llava");
      reqJson.put("prompt", prompt + (hint.isBlank() ? "" : " Context hint: " + hint));
      reqJson.put("stream", false);
      reqJson.putArray("images").add(base64Image.contains(",") ? base64Image.substring(base64Image.indexOf(",") + 1) : base64Image);

      java.net.http.HttpRequest httpReq = java.net.http.HttpRequest.newBuilder(uri)
          .header("Content-Type", "application/json")
          .timeout(java.time.Duration.ofSeconds(10))
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(reqJson.toString()))
          .build();

      java.net.http.HttpResponse<String> resp = client.send(httpReq, java.net.http.HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200) {
        JsonNode root = objectMapper.readTree(resp.body());
        String responseText = root.path("response").asText("");
        int jsonStart = responseText.indexOf('{');
        int jsonEnd = responseText.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
          JsonNode parsed = objectMapper.readTree(responseText.substring(jsonStart, jsonEnd + 1));
          parseJsonIntoDetectedElements(parsed, outClasses, outRelations);
          return !outClasses.isEmpty();
        }
      }
    } catch (Exception ignored) {}
    return false;
  }

  private void extractFromUserHint(String hint, List<DetectedClass> outClasses, List<DetectedRelation> outRelations) {
    String[] parts = hint.split("[,;]");
    for (String p : parts) {
      String name = p.trim().replaceAll("[^a-zA-Z0-9_]", "");
      if (!name.isEmpty()) {
        DetectedClass dc = new DetectedClass(Character.toUpperCase(name.charAt(0)) + name.substring(1));
        dc.attributes.add(new DetectedAttr("-", "id", "Long"));
        dc.attributes.add(new DetectedAttr("-", "nombre", "String"));
        outClasses.add(dc);
      }
    }
    for (int i = 0; i < outClasses.size() - 1; i++) {
      outRelations.add(new DetectedRelation(outClasses.get(i).name, outClasses.get(i + 1).name, null, TipoRelacionUML.ASOCIACION, "1", "*"));
    }
  }

  static class DetectedClass {
    String name;
    Double posX;
    Double posY;
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;
    TipoElementoUML tipo = TipoElementoUML.CLASE;
    List<DetectedAttr> attributes = new ArrayList<>();
    List<DetectedMethod> methods = new ArrayList<>();
    DetectedClass(String name) { this.name = name; }
  }

  static class DetectedAttr {
    String visibility = "-";
    String name;
    String type = "String";
    DetectedAttr(String visibility, String name, String type) {
      this.visibility = visibility != null && !visibility.isBlank() ? visibility : "-";
      this.name = name;
      this.type = type != null && !type.isBlank() ? type : "String";
    }
    DetectedAttr(String name, String type) { this("-", name, type); }
  }

  static class DetectedMethod {
    String visibility = "+";
    String name;
    String returnType = "void";
    String parameters = "";
    DetectedMethod(String visibility, String name, String returnType) {
      this.visibility = visibility != null && !visibility.isBlank() ? visibility : "+";
      this.name = name;
      this.returnType = returnType != null && !returnType.isBlank() ? returnType : "void";
    }
    DetectedMethod(String visibility, String name, String returnType, String parameters) {
      this(visibility, name, returnType);
      this.parameters = parameters != null ? parameters : "";
    }
    DetectedMethod(String name, String returnType) { this("+", name, returnType); }
  }

  static class DetectedRelation {
    String source;
    String target;
    String name;
    TipoRelacionUML tipo = TipoRelacionUML.ASOCIACION;
    String multSource = "1";
    String multTarget = "*";
    DetectedRelation(String source, String target, String name, TipoRelacionUML tipo, String multSource, String multTarget) {
      this.source = source;
      this.target = target;
      this.name = name;
      this.tipo = tipo != null ? tipo : TipoRelacionUML.ASOCIACION;
      this.multSource = multSource != null && !multSource.isBlank() ? multSource : null;
      this.multTarget = multTarget != null && !multTarget.isBlank() ? multTarget : null;
    }
    DetectedRelation(String source, String target, TipoRelacionUML tipo, String multSource, String multTarget) {
      this(source, target, null, tipo, multSource, multTarget);
    }
  }

  private boolean tryNativeTesseract(String base64Image, List<DetectedClass> outClasses, List<DetectedRelation> outRelations) {
    if (base64Image == null || base64Image.isBlank()) return false;
    File tmpFile = null;
    try {
      String cleanBase64 = base64Image.contains(",")
          ? base64Image.substring(base64Image.indexOf(",") + 1)
          : base64Image;
      byte[] imgBytes = Base64.getDecoder().decode(cleanBase64.replaceAll("\\s+", ""));
      tmpFile = File.createTempFile("diag_ocr_", ".png");
      try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
        fos.write(imgBytes);
      }

      ProcessBuilder pb = new ProcessBuilder("tesseract", tmpFile.getAbsolutePath(), "stdout", "-l", "spa+eng", "--psm", "11", "tsv");
      pb.redirectErrorStream(true);
      Process process = pb.start();

      List<TsvWord> words = new ArrayList<>();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
        String line;
        boolean header = true;
        while ((line = reader.readLine()) != null) {
          if (header) {
            header = false;
            continue;
          }
          String[] parts = line.split("\t");
          if (parts.length >= 12 && "5".equals(parts[0].trim())) {
            try {
              int block = Integer.parseInt(parts[2].trim());
              int par = Integer.parseInt(parts[3].trim());
              int lineNum = Integer.parseInt(parts[4].trim());
              int left = Integer.parseInt(parts[6].trim());
              int top = Integer.parseInt(parts[7].trim());
              int width = Integer.parseInt(parts[8].trim());
              int height = Integer.parseInt(parts[9].trim());
              String text = parts[11].trim();
              if (!text.isEmpty()) {
                words.add(new TsvWord(block, par, lineNum, left, top, width, height, text));
              }
            } catch (Exception ignored) {}
          }
        }
      }
      process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);

      if (words.isEmpty()) {
        return false;
      }

      Map<String, List<TsvWord>> lineGroups = new LinkedHashMap<>();
      for (TsvWord w : words) {
        String key = w.block + "_" + w.par + "_" + w.lineNum;
        lineGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
      }

      List<OcrLine> ocrLines = new ArrayList<>();
      for (List<TsvWord> g : lineGroups.values()) {
        int minLeft = Integer.MAX_VALUE;
        int minTop = Integer.MAX_VALUE;
        int maxRight = Integer.MIN_VALUE;
        int maxBottom = Integer.MIN_VALUE;
        StringBuilder sb = new StringBuilder();
        for (TsvWord w : g) {
          if (w.left < minLeft) minLeft = w.left;
          if (w.top < minTop) minTop = w.top;
          if (w.left + w.width > maxRight) maxRight = w.left + w.width;
          if (w.top + w.height > maxBottom) maxBottom = w.top + w.height;
          if (!sb.isEmpty()) sb.append(" ");
          sb.append(w.text);
        }
        ocrLines.add(new OcrLine(sb.toString().trim(), minLeft, minTop, maxRight - minLeft, maxBottom - minTop));
      }

      List<DetectedClass> detected = new ArrayList<>();
      Set<String> commonTypes = Set.of("long", "string", "date", "double", "int", "integer", "boolean", "void", "float", "list", "set", "map");
      Set<String> relWords = Set.of("realiza", "tiene", "corresponde", "pertenece", "incluye", "asociado");

      for (OcrLine ol : ocrLines) {
        String text = ol.text.replace(":", "").trim();
        if (text.matches("^[A-Z][a-zA-Z0-9_]{2,}$") &&
            !commonTypes.contains(text.toLowerCase()) &&
            !relWords.contains(text.toLowerCase()) &&
            !ol.text.contains("()") &&
            !ol.text.startsWith("-") &&
            !ol.text.startsWith("+")) {
          if (detected.stream().noneMatch(c -> c.name.equalsIgnoreCase(text))) {
            DetectedClass dc = new DetectedClass(text);
            dc.posX = (double) ol.left;
            dc.posY = (double) ol.top;
            dc.minX = ol.left;
            dc.minY = ol.top;
            dc.maxX = ol.left + ol.width;
            dc.maxY = ol.top + ol.height;
            detected.add(dc);
          }
        }
      }

      if (detected.isEmpty()) {
        return false;
      }

      for (OcrLine ol : ocrLines) {
        String t = ol.text.trim();
        if (detected.stream().anyMatch(c -> c.name.equalsIgnoreCase(t.replace(":", "").trim()))) {
          continue;
        }

        boolean isMethod = t.contains("()") || t.contains("):") || (t.startsWith("+") && t.contains("("));
        boolean isAttr = !isMethod && (t.startsWith("-") || (t.contains(":") && !t.contains("realiza") && !t.contains("tiene")));

        if (isMethod || isAttr) {
          DetectedClass closest = null;
          double bestDist = Double.MAX_VALUE;
          for (DetectedClass dc : detected) {
            double cX = dc.posX != null ? dc.posX : 0.0;
            double cY = dc.posY != null ? dc.posY : 0.0;
            double vertPen = ol.top < cY - 15 ? 5000.0 : (ol.top - cY) * 0.35;
            double dist = Math.abs(ol.left - cX) + vertPen;
            if (dist < bestDist) {
              bestDist = dist;
              closest = dc;
            }
          }

          if (closest != null && bestDist < 1200.0) {
            closest.minX = Math.min(closest.minX, ol.left);
            closest.minY = Math.min(closest.minY, ol.top);
            closest.maxX = Math.max(closest.maxX, ol.left + ol.width);
            closest.maxY = Math.max(closest.maxY, ol.top + ol.height);

            if (isMethod) {
              String vis = "~";
              String raw = t;
              if (raw.startsWith("-") || raw.startsWith("+") || raw.startsWith("#")) {
                vis = raw.substring(0, 1);
                raw = raw.substring(1).trim();
              }
              String mName = raw;
              String mReturn = "?";
              String parameters = "";
              int openParen = raw.indexOf('('), closeParen = raw.lastIndexOf(')');
              if (openParen >= 0 && closeParen > openParen) {
                mName = raw.substring(0, openParen).trim();
                parameters = raw.substring(openParen + 1, closeParen).trim();
                String tail = raw.substring(closeParen + 1).trim();
                if (tail.startsWith(":")) mReturn = tail.substring(1).trim();
              } else {
                int colonIdx = raw.indexOf(":");
                if (colonIdx > 0) {
                  mReturn = raw.substring(colonIdx + 1).trim();
                  mName = raw.substring(0, colonIdx).trim();
                }
              }
              mName = mName.replaceAll("[^\\p{L}\\p{N}_]", "").trim();
              if (mName.isEmpty()) mName = "metodo";
              closest.methods.add(new DetectedMethod(vis, mName, mReturn, parameters));
            } else {
              String vis = "~";
              String raw = t;
              if (raw.startsWith("-") || raw.startsWith("+") || raw.startsWith("#")) {
                vis = raw.substring(0, 1);
                raw = raw.substring(1).trim();
              }
              String aName = raw;
              String aType = "?";
              int colonIdx = raw.indexOf(":");
              if (colonIdx > 0) {
                aName = raw.substring(0, colonIdx).trim();
                aType = capitalizeType(raw.substring(colonIdx + 1).trim());
              }
              aName = aName.replaceAll("[^\\p{L}\\p{N}_]", "").trim();
              if (aName.equalsIgnoreCase("fechalnicio")) aName = "fechaInicio";
              if (!aName.isEmpty()) {
                closest.attributes.add(new DetectedAttr(vis, aName, aType));
              }
            }
          }
        }
      }

      // Normalizar coordenadas a canvas UML manteniendo la topología fiel
      if (!detected.isEmpty()) {
        double minXVal = detected.stream().mapToDouble(c -> c.posX != null ? c.posX : 0.0).min().orElse(0.0);
        double maxXVal = detected.stream().mapToDouble(c -> c.posX != null ? c.posX : 0.0).max().orElse(1.0);
        double minYVal = detected.stream().mapToDouble(c -> c.posY != null ? c.posY : 0.0).min().orElse(0.0);
        double maxYVal = detected.stream().mapToDouble(c -> c.posY != null ? c.posY : 0.0).max().orElse(1.0);
        double spanX = maxXVal > minXVal ? maxXVal - minXVal : 1.0;
        double spanY = maxYVal > minYVal ? maxYVal - minYVal : 1.0;

        for (DetectedClass c : detected) {
          if (c.posX != null) c.posX = (double) Math.round(80.0 + (((c.posX - minXVal) / spanX) * 750.0));
          if (c.posY != null) c.posY = (double) Math.round(60.0 + (((c.posY - minYVal) / spanY) * 380.0));
        }
      }

      // Extraer tokens no asignados para relaciones y multiplicidades
      List<OcrLine> unassignedLines = new ArrayList<>();
      for (OcrLine ol : ocrLines) {
        String t = ol.text.trim();
        if (detected.stream().anyMatch(c -> c.name.equalsIgnoreCase(t.replace(":", "").trim()))) continue;
        boolean isMethod = t.contains("()") || t.contains("):") || (t.startsWith("+") && t.contains("("));
        boolean isAttr = !isMethod && (t.startsWith("-") || (t.contains(":") && !t.contains("realiza") && !t.contains("tiene")));
        if (!isMethod && !isAttr) {
          unassignedLines.add(ol);
        }
      }

      List<MultToken> multTokens = new ArrayList<>();
      List<OcrLine> labelTokens = new ArrayList<>();

      for (OcrLine u : unassignedLines) {
        String m = parseOcrMultiplicity(u.text);
        if (m != null) {
          multTokens.add(new MultToken(m, u.left, u.top, u.width, u.height));
        } else {
          labelTokens.add(u);
        }
      }

      // Detección espacial de relaciones basada en etiquetas y dirección de flechas
      for (OcrLine u : labelTokens) {
        String raw = u.text.trim();
        String cleanWord = raw.replaceAll("[>»▶◀<v¥▼▲^:;,\\.\\-=_]", "").trim();

        if (cleanWord.length() >= 3) {
          double cx = u.left + (u.width / 2.0);
          double cy = u.top + (u.height / 2.0);

          String direction = "RIGHT";
          if (raw.matches(".*[v¥▼].*") || cleanWord.equalsIgnoreCase("contiene")) {
            direction = "DOWN";
          } else if (raw.matches(".*[▲\\^].*")) {
            direction = "UP";
          } else if (raw.matches(".*[<◀«].*")) {
            direction = "LEFT";
          } else if (raw.matches(".*[>▶»].*")) {
            direction = "RIGHT";
          }

          DetectedClass source = null;
          DetectedClass target = null;

          if ("RIGHT".equals(direction)) {
            double bestLeftDist = Double.MAX_VALUE;
            double bestRightDist = Double.MAX_VALUE;
            for (DetectedClass c : detected) {
              double cCenterY = (c.minY + c.maxY) / 2.0;
              double yDist = Math.abs(cy - cCenterY);
              if (c.maxX <= cx + 40 && yDist < 250) {
                double dist = cx - c.maxX + (yDist * 0.5);
                if (dist < bestLeftDist) { bestLeftDist = dist; source = c; }
              }
              if (c.minX >= cx - 40 && yDist < 250) {
                double dist = c.minX - cx + (yDist * 0.5);
                if (dist < bestRightDist) { bestRightDist = dist; target = c; }
              }
            }
          } else if ("DOWN".equals(direction)) {
            double bestTopDist = Double.MAX_VALUE;
            double bestBottomDist = Double.MAX_VALUE;
            for (DetectedClass c : detected) {
              double cCenterX = (c.minX + c.maxX) / 2.0;
              double xDist = Math.abs(cx - cCenterX);
              if (c.maxY <= cy + 40 && xDist < 350) {
                double dist = cy - c.maxY + (xDist * 0.5);
                if (dist < bestTopDist) { bestTopDist = dist; source = c; }
              }
              if (c.minY >= cy - 40 && xDist < 350) {
                double dist = c.minY - cy + (xDist * 0.5);
                if (dist < bestBottomDist) { bestBottomDist = dist; target = c; }
              }
            }
          } else if ("LEFT".equals(direction)) {
            double bestLeftDist = Double.MAX_VALUE;
            double bestRightDist = Double.MAX_VALUE;
            for (DetectedClass c : detected) {
              double cCenterY = (c.minY + c.maxY) / 2.0;
              double yDist = Math.abs(cy - cCenterY);
              if (c.maxX <= cx + 40 && yDist < 250) {
                double dist = cx - c.maxX + (yDist * 0.5);
                if (dist < bestRightDist) { bestRightDist = dist; source = c; }
              }
              if (c.minX >= cx - 40 && yDist < 250) {
                double dist = c.minX - cx + (yDist * 0.5);
                if (dist < bestLeftDist) { bestLeftDist = dist; target = c; }
              }
            }
          } else if ("UP".equals(direction)) {
            double bestTopDist = Double.MAX_VALUE;
            double bestBottomDist = Double.MAX_VALUE;
            for (DetectedClass c : detected) {
              double cCenterX = (c.minX + c.maxX) / 2.0;
              double xDist = Math.abs(cx - cCenterX);
              if (c.maxY <= cy + 40 && xDist < 350) {
                double dist = cy - c.maxY + (xDist * 0.5);
                if (dist < bestBottomDist) { bestBottomDist = dist; source = c; }
              }
              if (c.minY >= cy - 40 && xDist < 350) {
                double dist = c.minY - cy + (xDist * 0.5);
                if (dist < bestTopDist) { bestTopDist = dist; target = c; }
              }
            }
          }

          if (source != null && target != null && !source.name.equalsIgnoreCase(target.name)) {
            double srcEndpointX = "RIGHT".equals(direction) ? source.maxX : (source.minX + source.maxX) / 2.0;
            double srcEndpointY = "DOWN".equals(direction) ? source.maxY : (source.minY + source.maxY) / 2.0;

            double tgtEndpointX = "RIGHT".equals(direction) ? target.minX : (target.minX + target.maxX) / 2.0;
            double tgtEndpointY = "DOWN".equals(direction) ? target.minY : (target.minY + target.maxY) / 2.0;

            String mSource = null;
            String mTarget = null;
            double minSDist = Double.MAX_VALUE;
            double minTDist = Double.MAX_VALUE;

            for (MultToken mt : multTokens) {
              double mcx = mt.left + (mt.width / 2.0);
              double mcy = mt.top + (mt.height / 2.0);

              if ("DOWN".equals(direction) && mcy < source.minY + 60) continue;
              if ("RIGHT".equals(direction) && (mcy < Math.min(source.minY, target.minY) - 80 || mcy > Math.max(source.maxY, target.maxY) + 80)) continue;

              double sDist = Math.hypot(mcx - srcEndpointX, mcy - srcEndpointY);
              double tDist = Math.hypot(mcx - tgtEndpointX, mcy - tgtEndpointY);

              if (sDist < tDist) {
                if (sDist < minSDist && sDist < 220) {
                  minSDist = sDist;
                  mSource = mt.mult;
                }
              } else {
                if (tDist < minTDist && tDist < 220) {
                  minTDist = tDist;
                  mTarget = mt.mult;
                }
              }
            }

            if (mSource == null) mSource = cleanWord.equalsIgnoreCase("contiene") ? "1..*" : "1";
            if (mTarget == null) mTarget = cleanWord.equalsIgnoreCase("tiene") ? "0..1" : cleanWord.equalsIgnoreCase("contiene") ? "1" : "0..*";

            final String finalSrc = source.name;
            final String finalTgt = target.name;
            if (outRelations.stream().noneMatch(r -> r.source.equalsIgnoreCase(finalSrc) && r.target.equalsIgnoreCase(finalTgt))) {
              outRelations.add(new DetectedRelation(source.name, target.name, cleanWord, TipoRelacionUML.ASOCIACION, mSource, mTarget));
            }
          }
        }
      }

      // Respaldo semántico en firmas de métodos
      for (DetectedClass c : detected) {
        for (DetectedMethod m : c.methods) {
          for (DetectedClass other : detected) {
            if (!other.name.equalsIgnoreCase(c.name) && m.name.toLowerCase().contains(other.name.toLowerCase())) {
              if (outRelations.stream().noneMatch(r -> (r.source.equalsIgnoreCase(c.name) && r.target.equalsIgnoreCase(other.name)) ||
                                                      (r.source.equalsIgnoreCase(other.name) && r.target.equalsIgnoreCase(c.name)))) {
                outRelations.add(new DetectedRelation(c.name, other.name, "asociado", TipoRelacionUML.ASOCIACION, "1", m.returnType.contains("List") || m.returnType.contains("Set") ? "*" : "1"));
              }
            }
          }
        }
      }

      outClasses.addAll(detected);
      return !outClasses.isEmpty();
    } catch (Exception e) {
      log.warn("Fallo ejecutando Tesseract nativo local: {}", e.getMessage());
      return false;
    } finally {
      if (tmpFile != null && tmpFile.exists()) {
        tmpFile.delete();
      }
    }
  }

  private static class TsvWord {
    int block, par, lineNum, left, top, width, height;
    String text;
    TsvWord(int block, int par, int lineNum, int left, int top, int width, int height, String text) {
      this.block = block; this.par = par; this.lineNum = lineNum;
      this.left = left; this.top = top; this.width = width; this.height = height;
      this.text = text;
    }
  }

  private static class OcrLine {
    String text;
    int left, top, width, height;
    OcrLine(String text, int left, int top, int width, int height) {
      this.text = text; this.left = left; this.top = top; this.width = width; this.height = height;
    }
  }

  private DiagramaClase validateDiagram(UUID projectId, UUID diagramId) {
    DiagramaClase diagram = diagramRepo.findById(diagramId)
        .orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    if (!diagram.getProject().getId().equals(projectId)) {
      throw new NotFoundException("El diagrama no pertenece a este proyecto.");
    }
    return diagram;
  }

  /** Interpreta lenguaje natural de CU10 con GroqCloud (Qwen); el parser determinista sigue ejecutando las operaciones UML. */
  private List<String> interpretCommandWithGroq(UUID projectId, UUID diagramId, String email, String userText) {
    if (llmProvider == null || !llmProvider.isConfigured() || userText == null || userText.isBlank()) return null;
    try {
      String systemPrompt = """
          Eres el intérprete de lenguaje natural de un editor UML. Tu función es traducir las intenciones del usuario en comandos UML estructurados.
          No ejecutes operaciones por tu cuenta y no devuelvas prosa, explicaciones ni bloques de código markdown.
          Devuelve ÚNICAMENTE un objeto JSON válido con exactamente esta forma:
          {"operaciones":[{"accion":"...", ...}]}
          Acciones permitidas: CREAR_CLASE, AGREGAR_ATRIBUTOS, EDITAR_ELEMENTO, CREAR_RELACION,
          ESTABLECER_MULTIPLICIDAD, CAMBIAR_TIPO_RELACION, CREAR_CLASE_INTERMEDIA, CREAR_GENERALIZACION,
          CREAR_COMPOSICION, CREAR_AGREGACION, ELIMINAR_ELEMENTO, ELIMINAR_RELACION.
          Campos admitidos en cada operación:
          - accion: una de las acciones permitidas arriba.
          - clase / nombre: nombre exacto del elemento.
          - nuevoNombre: nombre destino en renombre/edición.
          - atributos: arreglo de objetos {"nombre":"...", "tipo":"..."}.
          - origen, destino: clases conectadas.
          - Para ELIMINAR_RELACION, origen y destino identifican el par de clases; nunca uses ELIMINAR_ELEMENTO para borrar una relación.
          - tipoRelacion: "asociacion", "herencia", "composicion", "agregacion", "dependencia".
          - multiplicidadOrigen, multiplicidadDestino: cardinalidades UML como "1", "*", "0..1", "1..*", o null.
          - claseIntermedia: nombre de la tabla/clase intermedia en relaciones muchos a muchos.
          Si la intención no es una operación UML permitida, devuelve {"operaciones":[]}.
          """;

      LlmResponse llmResp = aiOrchestrationService != null
          ? aiOrchestrationService.generate(email, projectId, diagramId, AiOperation.UML_COMMAND,
              systemPrompt, List.of(LlmMessage.user("INSTRUCCIÓN DEL USUARIO:\n" + userText.trim())), 800, 0.1)
          : llmProvider.chat(List.of(LlmMessage.system(systemPrompt), LlmMessage.user(userText.trim())), 800, 0.1);

      if (!llmResp.success() || llmResp.content() == null || llmResp.content().isBlank()) {
        log.warn("LlmProvider devolvió respuesta vacía o fallida en CU10: {}", llmResp.errorMessage());
        return null;
      }

      JsonNode root = parseLlmJsonNode(llmResp.content());
      JsonNode operations = root.path("operaciones");
      if (!operations.isArray() || operations.isEmpty()) {
        operations = root.has("accion") ? objectMapper.createArrayNode().add(root) : operations;
      }
      List<String> commands = new ArrayList<>();
      if (operations.isArray()) {
        for (JsonNode operation : operations) {
          String command = structuredActionToCommand(operation);
          if (command != null && !command.isBlank()) commands.add(command);
        }
      }
      return commands.isEmpty() ? null : commands;
    } catch (Exception e) {
      log.warn("No se pudo interpretar el comando CU10 con Groq; se usará el parser determinista local: {}", e.getMessage());
      return null;
    }
  }

  private String structuredActionToCommand(JsonNode operation) {
    String action = operation.path("accion").asText(operation.path("action").asText(""))
        .trim().toUpperCase(Locale.ROOT);
    String clase = textField(operation, "clase", "class", "nombre");
    String origen = textField(operation, "origen", "source");
    String destino = textField(operation, "destino", "target");
    String type = textField(operation, "tipoRelacion", "tipo", "relationType").toLowerCase(Locale.ROOT);
    if (type.equals("generalizacion")) type = "herencia";
    String multOrigen = normalizeMultiplicity(textField(operation, "multiplicidadOrigen", "multSource"));
    String multDestino = normalizeMultiplicity(textField(operation, "multiplicidadDestino", "multTarget"));

    return switch (action) {
      case "CREAR_CLASE" -> clase.isBlank() ? null : "Crear clase " + clase;
      case "AGREGAR_ATRIBUTOS" -> {
        if (clase.isBlank() || !operation.path("atributos").isArray()) yield null;
        List<String> attributes = new ArrayList<>();
        for (JsonNode attribute : operation.path("atributos")) {
          String name = textField(attribute, "nombre", "name");
          String attrType = textField(attribute, "tipo", "type");
          if (!name.isBlank()) attributes.add(name + (attrType.isBlank() ? "" : " " + attrType));
        }
        yield attributes.isEmpty() ? null : "Agregar " + String.join(", ", attributes) + " a " + clase;
      }
      case "EDITAR_ELEMENTO" -> {
        String newName = textField(operation, "nuevoNombre", "newName");
        yield clase.isBlank() || newName.isBlank() ? null : "Renombra la clase " + clase + " a " + newName;
      }
      case "CREAR_CLASE_INTERMEDIA" -> {
        String middle = textField(operation, "claseIntermedia", "middleClass", "clase");
        if (middle.isBlank()) yield null;
        String command = "Crear clase intermedia " + middle;
        if (!origen.isBlank() && !destino.isBlank()) command += " entre " + origen + " y " + destino;
        else command += " para la relacion seleccionada";
        if (operation.path("atributos").isArray() && !operation.path("atributos").isEmpty()) {
          List<String> attributes = new ArrayList<>();
          for (JsonNode attribute : operation.path("atributos")) {
            String name = textField(attribute, "nombre", "name");
            String attrType = textField(attribute, "tipo", "type");
            if (!name.isBlank()) attributes.add(name + ":" + (attrType.isBlank() ? "String" : attrType));
          }
          if (!attributes.isEmpty()) command += " con atributos " + String.join(", ", attributes);
        }
        yield command;
      }
      case "CREAR_GENERALIZACION" -> structuredRelationCommand("herencia", origen, destino, multOrigen, multDestino);
      case "CREAR_COMPOSICION" -> structuredRelationCommand("composicion", origen, destino, multOrigen, multDestino);
      case "CREAR_AGREGACION" -> structuredRelationCommand("agregacion", origen, destino, multOrigen, multDestino);
      case "CREAR_RELACION" -> structuredRelationCommand(
          type.isBlank() ? "asociacion" : type, origen, destino, multOrigen, multDestino);
      case "CAMBIAR_TIPO_RELACION" -> type.isBlank() ? null : "Cambia esa relacion a " + type;
      case "ESTABLECER_MULTIPLICIDAD" -> {
        if (multOrigen == null && multDestino == null) yield null;
        yield (multOrigen == null ? "1" : multOrigen) + " a " + (multDestino == null ? "*" : multDestino);
      }
      case "ELIMINAR_ELEMENTO" -> clase.isBlank() ? null : "Eliminar clase " + clase;
      case "ELIMINAR_RELACION" -> source.isBlank() || target.isBlank()
          ? null : "Eliminar relacion entre " + source + " y " + target;
      default -> null;
    };
  }

  private String structuredRelationCommand(String type, String source, String target, String sourceMultiplicity, String targetMultiplicity) {
    if (source.isBlank() || target.isBlank()) return null;
    String command = "Relacionar " + source + " con " + target + " " + type;
    if (sourceMultiplicity != null || targetMultiplicity != null) {
      command += " " + (sourceMultiplicity == null ? "1" : sourceMultiplicity) + " a "
          + (targetMultiplicity == null ? "*" : targetMultiplicity);
    }
    return command;
  }

  private String textField(JsonNode node, String... names) {
    for (String name : names) {
      String value = node.path(name).asText("").trim();
      if (!value.isBlank()) return value;
    }
    return "";
  }

  private List<IntermediateAttribute> parseIntermediateAttributes(String source) {
    if (source == null || source.isBlank()) return List.of();
    List<IntermediateAttribute> attributes = new ArrayList<>();
    for (String item : source.split("(?i)\\s+y\\s+(?=[\\p{L}_][\\p{L}\\p{N}_]*\\s*[: ])|[,;]")) {
      String value = item.trim();
      if (value.isBlank()) continue;
      String[] parts = value.contains(":") ? value.split(":", 2) : value.split("\\s+", 2);
      String name = parts[0].trim().replaceAll("[^\\p{L}\\p{N}_]", "");
      if (name.isBlank()) continue;
      String type = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim().replaceAll("[^\\p{L}\\p{N}_<>\\[\\].]", "") : "String";
      attributes.add(new IntermediateAttribute(name, type.isBlank() ? "String" : type));
    }
    return List.copyOf(attributes);
  }

  private boolean isMany(String multiplicity) {
    if (multiplicity == null) return false;
    return multiplicity.trim().matches("(?i)(?:\\*|n|m|(?:0|1)\\.\\.(?:\\*|n|m))");
  }

  private String normalizeMultiplicity(String value) {
    if (value == null || value.isBlank() || value.equalsIgnoreCase("null")) return null;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "uno", "un", "one" -> "1";
      case "muchos", "varios", "many" -> "*";
      case "uno a muchos", "1 a muchos", "uno con varios" -> "1..*";
      case "cero a muchos", "0 a muchos" -> "0..*";
      default -> value.trim();
    };
  }

  private boolean looksLikeSqlDdl(String command) {
    return command != null && command.trim().toLowerCase(Locale.ROOT).matches("(?s).*\\b(create|alter|drop)\\s+table\\b.*");
  }

  private boolean isAttributeDeletionRequest(String command) {
    if (command == null) return false;
    String normalized = Normalizer.normalize(command, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return normalized.matches("(?is).*\\b(?:eliminar|borrar|elimina|borra)\\s+(?:el\\s+)?(?:atributo|campo|propiedad)\\b.*");
  }

  private boolean isRelationDeletionRequest(String command) {
    if (command == null) return false;
    String normalized = Normalizer.normalize(command, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT);
    return normalized.matches("(?is).*\\b(?:eliminar|borrar|elimina|borra|quitar|quita)\\b.*\\b(?:relacion(?:es)?|conexion(?:es)?|asociacion(?:es)?|vinculo(?:s)?|enlace(?:s)?)\\b.*");
  }

  private boolean connects(RelacionUML relation, String firstClass, String secondClass) {
    String origin = relation.getElementoOrigen().getNombre();
    String destination = relation.getElementoDestino().getNombre();
    return (origin.equalsIgnoreCase(firstClass) && destination.equalsIgnoreCase(secondClass))
        || (origin.equalsIgnoreCase(secondClass) && destination.equalsIgnoreCase(firstClass));
  }

  private int findAttributeIndex(JsonNode attributes, String requestedName) {
    if (!attributes.isArray()) return -1;
    String requested = normalizeIdentifier(requestedName);
    List<Integer> close = new ArrayList<>();
    for (int i = 0; i < attributes.size(); i++) {
      String name = attributes.get(i).path("name").asText("");
      String candidate = normalizeIdentifier(name);
      if (candidate.equals(requested)) return i;
      if (editDistanceAtMostOne(candidate, requested)) close.add(i);
    }
    if (close.size() == 1) return close.get(0);
    return -1;
  }

  private String normalizeIdentifier(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "").replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
  }

  private boolean editDistanceAtMostOne(String left, String right) {
    if (left.equals(right)) return true;
    if (Math.abs(left.length() - right.length()) > 1) return false;
    int i = 0, j = 0, edits = 0;
    while (i < left.length() && j < right.length()) {
      if (left.charAt(i) == right.charAt(j)) { i++; j++; continue; }
      if (++edits > 1) return false;
      if (left.length() > right.length()) i++;
      else if (right.length() > left.length()) j++;
      else { i++; j++; }
    }
    return edits + (i < left.length() || j < right.length() ? 1 : 0) <= 1;
  }



  /** Normaliza las formas habituales del dictado antes de aplicar el parser CU10. */
  private String normalizeVoiceCommand(String command) {
    String value = Normalizer.normalize(command, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .replaceAll("\\s+", " ")
        .trim();

    Matcher relationBetween = Pattern.compile(
        "(?i)^crear\\s+(?:una?\\s+)?(asociacion|composicion|agregacion|generalizacion|herencia|dependencia)\\s+entre\\s+([a-zA-Z0-9_]+)(?:\\s+(0\\.\\.\\*|1\\.\\.\\*|0\\.\\.1|1|\\*))?\\s+(?:y|con)\\s+([a-zA-Z0-9_]+)(?:\\s+(0\\.\\.\\*|1\\.\\.\\*|0\\.\\.1|1|\\*))?$")
        .matcher(value);
    if (relationBetween.matches()) {
      return relationCommand(relationBetween.group(1), relationBetween.group(2), relationBetween.group(3), relationBetween.group(4), relationBetween.group(5));
    }

    Matcher relationDirect = Pattern.compile(
        "(?i)^crear\\s+(?:una?\\s+)?(asociacion|composicion|agregacion|generalizacion|herencia|dependencia)\\s+([a-zA-Z0-9_]+)(?:\\s+(0\\.\\.\\*|1\\.\\.\\*|0\\.\\.1|1|\\*))?\\s+(?:a|con|y)\\s+([a-zA-Z0-9_]+)(?:\\s+(0\\.\\*|0\\.\\.\\*|1\\.\\.\\*|0\\.\\.1|1|\\*))?$")
        .matcher(value);
    if (relationDirect.matches()) {
      return relationCommand(relationDirect.group(1), relationDirect.group(2), relationDirect.group(3), relationDirect.group(4), relationDirect.group(5));
    }

    Matcher relationWords = Pattern.compile("(?i)^relacionar\\s+([a-zA-Z0-9_]+)\\s+(uno a muchos|muchos a muchos|uno a uno)\\s+con\\s+([a-zA-Z0-9_]+)$").matcher(value);
    if (relationWords.matches()) {
      return "Relacionar " + relationWords.group(1) + " con " + relationWords.group(3) + " " + relationWords.group(2);
    }

    Matcher hasMany = Pattern.compile("(?i)^([a-zA-Z0-9_]+)\\s+tiene\\s+muchos\\s+([a-zA-Z0-9_]+)$").matcher(value);
    if (hasMany.matches()) return "Relacionar " + hasMany.group(1) + " con " + hasMany.group(2) + " 1 a n";

    Matcher composed = Pattern.compile("(?i)^([a-zA-Z0-9_]+)\\s+(?:esta\\s+)?compuesto\\s+por\\s+([a-zA-Z0-9_]+)$").matcher(value);
    if (composed.matches()) return "Relacionar " + composed.group(1) + " con " + composed.group(2) + " composicion";

    Matcher inherits = Pattern.compile("(?i)^([a-zA-Z0-9_]+)\\s+hereda\\s+de\\s+([a-zA-Z0-9_]+)$").matcher(value);
    if (inherits.matches()) return "Relacionar " + inherits.group(1) + " con " + inherits.group(2) + " herencia";

    return value;
  }

  private String relationCommand(String type, String source, String sourceMultiplicity, String target, String targetMultiplicity) {
    String normalizedType = type.equalsIgnoreCase("generalizacion") ? "herencia" : type;
    if (sourceMultiplicity == null && targetMultiplicity == null) {
      return "Relacionar " + source + " con " + target + " " + normalizedType;
    }
    return "Relacionar " + source + " con " + target + " " + normalizedType + " "
        + (sourceMultiplicity == null ? "1" : sourceMultiplicity) + " a "
        + (targetMultiplicity == null ? "*" : targetMultiplicity);
  }

  private static class MultToken {
    String mult;
    int left, top, width, height;
    MultToken(String mult, int left, int top, int width, int height) {
      this.mult = mult; this.left = left; this.top = top; this.width = width; this.height = height;
    }
  }

  private String parseOcrMultiplicity(String raw) {
    if (raw == null) return null;
    String clean = raw.trim();
    if (clean.matches(".*[a-zA-Z]{3,}.*")) return null;
    String s = clean.toLowerCase().replaceAll("[^0-9\\.\\*bnm]", "");
    if (s.equals("0.") || s.equals("0..") || s.contains("0.*") || s.contains("0..*") || s.contains("0*")) return "0..*";
    if (s.equals("1.") || s.equals("1..") || s.contains("1.*") || s.contains("1..*") || s.contains("1*") || s.equals("b") || s.equals("bi")) return "1..*";
    if (s.contains("0.1") || s.contains("0..1")) return "0..1";
    if (s.contains("1.1") || s.contains("1..1")) return "1..1";
    if (s.equals("1")) return "1";
    if (s.equals("0")) return "0";
    if (s.equals("*") || s.equals("n") || s.equals("m")) return "*";
    return null;
  }

  private String capitalizeType(String type) {
    if (type == null || type.isBlank()) return "String";
    String s = type.trim();
    if (s.startsWith("List<") || s.startsWith("Set<") || s.startsWith("Collection<") || s.startsWith("Map<")) return s;
    if (s.equalsIgnoreCase("int") || s.equalsIgnoreCase("integer")) return "Integer";
    if (s.equalsIgnoreCase("long")) return "Long";
    if (s.equalsIgnoreCase("double") || s.equalsIgnoreCase("float") || s.equalsIgnoreCase("decimal")) return "Double";
    if (s.equalsIgnoreCase("bool") || s.equalsIgnoreCase("boolean")) return "Boolean";
    if (s.equalsIgnoreCase("date") || s.equalsIgnoreCase("datetime") || s.equalsIgnoreCase("localdate")) return "Date";
    if (s.equalsIgnoreCase("void")) return "void";
    if (s.equalsIgnoreCase("uuid")) return "UUID";
    return s;
  }
}
