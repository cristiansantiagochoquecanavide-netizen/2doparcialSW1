package bo.edu.caseapp.uml.relational;

import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.uml.UmlResponses.DiagramFullView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SqlDdlParserService {
  private final DiagramaClaseRepository diagramRepo;
  private final ElementoUMLRepository elementRepo;
  private final RelacionUMLRepository relationRepo;
  private final ProjectService projectService;
  private final UmlService umlService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public SqlDdlParserService(
      DiagramaClaseRepository diagramRepo,
      ElementoUMLRepository elementRepo,
      RelacionUMLRepository relationRepo,
      ProjectService projectService,
      UmlService umlService
  ) {
    this.diagramRepo = diagramRepo;
    this.elementRepo = elementRepo;
    this.relationRepo = relationRepo;
    this.projectService = projectService;
    this.umlService = umlService;
  }

  public DiagramFullView parseAndImportSql(UUID projectId, UUID diagramId, String email, String sqlContent) {
    return parseAndImportSql(projectId, diagramId, email, sqlContent, null);
  }

  public DiagramFullView parseAndImportSql(UUID projectId, UUID diagramId, String email, String sqlContent, Long expectedRevision) {
    projectService.requireCanEdit(projectId, email);
    DiagramaClase diagram = diagramRepo.findById(diagramId)
        .orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    if (!diagram.getProject().getId().equals(projectId)) {
      throw new NotFoundException("El diagrama no pertenece a este proyecto.");
    }

    if (sqlContent == null || sqlContent.isBlank()) {
      throw new BadRequestException("El script SQL no puede estar vacío.");
    }

    List<ParsedTable> parsedTables = parseSqlTables(sqlContent);
    if (parsedTables.isEmpty()) {
      throw new BadRequestException("No se encontraron sentencias CREATE TABLE válidas en el script SQL provisto.");
    }

    long newRev = umlService.reserveNextRevision(diagramId, expectedRevision);
    diagram.setRevision(newRev);

    // 1. Guardar elementos de clase UML para cada tabla
    Map<String, ElementoUML> classMapByRawName = new HashMap<>();
    Map<String, ElementoUML> classMapByClassName = new HashMap<>();

    double posX = 140.0;
    double posY = 100.0;
    int colIndex = 0;

    for (ParsedTable pt : parsedTables) {
      String className = pt.className;
      ObjectNode propNode = objectMapper.createObjectNode();
      ArrayNode attrsArray = propNode.putArray("attributes");

      List<String> pkNames = new ArrayList<>();
      for (ParsedColumn col : pt.columns) {
        if (col.isPrimaryKey) {
          pkNames.add(col.name);
        }
        ObjectNode attrObj = attrsArray.addObject();
        attrObj.put("id", UUID.randomUUID().toString());
        attrObj.put("visibility", "+");
        attrObj.put("name", col.name);
        attrObj.put("type", col.umlType);
      }
      propNode.putArray("methods");

      if (!pkNames.isEmpty()) {
        propNode.put("description", "Tabla: " + pt.rawTableName + " [PK: " + String.join(", ", pkNames) + "]");
      } else {
        propNode.put("description", "Tabla: " + pt.rawTableName);
      }
      propNode.put("stereotype", "Entity");

      ElementoUML elemento = new ElementoUML(
          diagram,
          TipoElementoUML.CLASE,
          className,
          posX + (colIndex % 3) * 280.0,
          posY + (colIndex / 3) * 230.0,
          propNode.toString()
      );
      elemento = elementRepo.save(elemento);
      classMapByRawName.put(pt.rawTableName.toLowerCase(), elemento);
      classMapByClassName.put(className.toLowerCase(), elemento);
      colIndex++;
    }

    // 2. Crear relaciones para las Claves Foráneas (FK)
    for (ParsedTable pt : parsedTables) {
      ElementoUML originElement = classMapByRawName.get(pt.rawTableName.toLowerCase());
      if (originElement == null) continue;

      for (ParsedForeignKey fk : pt.foreignKeys) {
        String targetRef = fk.referencedTable.toLowerCase();
        ElementoUML targetElement = classMapByRawName.get(targetRef);
        if (targetElement == null) {
          targetElement = classMapByClassName.get(targetRef);
        }

        if (targetElement != null) {
          // Relación de Asociación: Target (1) <-----> Origin (0..* o 1..*)
          String multOrigin = fk.isNotNull ? "1..*" : "0..*";
          String multDestino = "1";

          RelacionUML relacion = new RelacionUML(
              diagram,
              targetElement,
              originElement,
              fk.columnName,
              TipoRelacionUML.ASOCIACION,
              multDestino,
              multOrigin
          );
          relationRepo.save(relacion);
        }
      }
    }

    umlService.notifyCollabAfterCommit(diagramId, "SQL_IMPORTED", newRev, email, "IMPORT");
    return umlService.getDiagram(projectId, diagramId, email);
  }

  public List<ParsedTable> parseSqlTables(String sql) {
    List<ParsedTable> tables = new ArrayList<>();
    // Eliminar comentarios de una línea (-- ...) y comentarios multilínea (/* ... */)
    String cleaned = sql.replaceAll("--.*", "")
                        .replaceAll("/\\*[\\s\\S]*?\\*/", "")
                        .trim();

    Pattern tablePattern = Pattern.compile(
        "(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([`\"\\[]?\\w+[`\"\\]]?)\\s*\\((([\\s\\S]*?))\\)(?:\\s*;|\\s*(?:ENGINE|DEFAULT|TABLESPACE|ON)|$)",
        Pattern.CASE_INSENSITIVE
    );

    Matcher m = tablePattern.matcher(cleaned);
    while (m.find()) {
      String rawTableName = cleanIdentifier(m.group(1));
      String body = m.group(2);
      ParsedTable table = parseTableBody(rawTableName, body);
      tables.add(table);
    }

    return tables;
  }

  private ParsedTable parseTableBody(String rawTableName, String body) {
    String className = toPascalCase(rawTableName);
    ParsedTable table = new ParsedTable(rawTableName, className);

    // Dividir columnas cuidando los paréntesis anidados (ej. NUMERIC(10,2))
    List<String> tokens = splitTopLevelCommas(body);
    Set<String> explicitPks = new LinkedHashSet<>();

    for (String token : tokens) {
      String line = token.trim();
      if (line.isEmpty()) continue;
      String upper = line.toUpperCase();

      // Restricción a nivel de tabla: PRIMARY KEY (col1, col2)
      if (upper.matches(".*PRIMARY\\s+KEY\\s*\\([\\s\\S]*\\).*")) {
        Pattern pkPat = Pattern.compile("(?i)PRIMARY\\s+KEY\\s*\\(([^)]+)\\)");
        Matcher pkMat = pkPat.matcher(line);
        if (pkMat.find()) {
          String[] cols = pkMat.group(1).split(",");
          for (String c : cols) {
            explicitPks.add(cleanIdentifier(c.trim()));
          }
        }
        continue;
      }

      // Restricción a nivel de tabla: [CONSTRAINT fk_name] FOREIGN KEY (col) REFERENCES target (target_col)
      if (upper.contains("FOREIGN KEY") && upper.contains("REFERENCES")) {
        Pattern fkPat = Pattern.compile("(?i)(?:CONSTRAINT\\s+[`\"\\[]?\\w+[`\"\\]]?\\s+)?FOREIGN\\s+KEY\\s*\\(([^)]+)\\)\\s*REFERENCES\\s+([`\"\\[]?\\w+[`\"\\]]?)\\s*(?:\\(([^)]+)\\))?");
        Matcher fkMat = fkPat.matcher(line);
        if (fkMat.find()) {
          String col = cleanIdentifier(fkMat.group(1).trim());
          String refTable = cleanIdentifier(fkMat.group(2).trim());
          table.foreignKeys.add(new ParsedForeignKey(col, refTable, false));
        }
        continue;
      }

      // Ignorar otras restricciones de tabla (UNIQUE, CHECK, CONSTRAINT sin FK ni PK)
      if (upper.startsWith("CONSTRAINT") || upper.startsWith("UNIQUE") || upper.startsWith("CHECK")) {
        continue;
      }

      // Definición de columna individual
      ParsedColumn col = parseColumnLine(line);
      if (col != null) {
        table.columns.add(col);
      }
    }

    // Marcar claves primarias explícitas identificadas en tabla
    for (ParsedColumn col : table.columns) {
      if (explicitPks.contains(col.name)) {
        col.isPrimaryKey = true;
      }
    }

    // Si una columna contiene inline REFERENCES tabla(col)
    for (ParsedColumn col : table.columns) {
      if (col.inlineRefTable != null) {
        table.foreignKeys.add(new ParsedForeignKey(col.name, col.inlineRefTable, col.isNotNull));
      }
    }

    return table;
  }

  private ParsedColumn parseColumnLine(String line) {
    String trimmed = line.trim();
    String[] parts = trimmed.split("\\s+", 2);
    if (parts.length < 2) return null;

    String colName = cleanIdentifier(parts[0]);
    String rest = parts[1].trim();
    String restUpper = rest.toUpperCase();

    // Extraer tipo de dato (primer token antes de restricciones)
    String typeToken;
    Matcher typeMatcher = Pattern.compile("^([a-zA-Z_]+(?:\\s*\\([^)]*\\))?)").matcher(rest);
    if (typeMatcher.find()) {
      typeToken = typeMatcher.group(1).trim().toUpperCase();
    } else {
      typeToken = rest.split("\\s+")[0].toUpperCase();
    }

    String umlType = mapSqlTypeToUml(typeToken);

    boolean isPk = restUpper.contains("PRIMARY KEY") || typeToken.contains("SERIAL");
    boolean isNotNull = restUpper.contains("NOT NULL") || isPk;

    // Detectar inline REFERENCES
    String inlineRef = null;
    Pattern refPat = Pattern.compile("(?i)REFERENCES\\s+([`\"\\[]?\\w+[`\"\\]]?)(?:\\s*\\(([^)]+)\\))?");
    Matcher refMat = refPat.matcher(rest);
    if (refMat.find()) {
      inlineRef = cleanIdentifier(refMat.group(1));
    }

    ParsedColumn column = new ParsedColumn(colName, umlType, isPk, isNotNull);
    column.inlineRefTable = inlineRef;
    return column;
  }

  public String mapSqlTypeToUml(String sqlType) {
    String base = sqlType.replaceAll("\\(.*\\)", "").trim().toUpperCase();
    return switch (base) {
      case "SERIAL", "BIGSERIAL", "BIGINT" -> "Long";
      case "INT", "INTEGER", "SMALLINT", "TINYINT", "MEDIUMINT" -> "Integer";
      case "VARCHAR", "CHAR", "CHARACTER VARYING", "TEXT", "LONGTEXT", "MEDIUMTEXT", "TINYTEXT" -> "String";
      case "NUMERIC", "DECIMAL", "MONEY" -> "Decimal";
      case "FLOAT", "DOUBLE", "REAL", "DOUBLE PRECISION" -> "Double";
      case "BOOL", "BOOLEAN" -> "Boolean";
      case "DATE" -> "Date";
      case "TIMESTAMP", "TIMESTAMPTZ", "DATETIME" -> "DateTime";
      case "TIME", "TIMETZ" -> "Time";
      case "UUID" -> "UUID";
      case "JSON", "JSONB" -> "JSON";
      default -> "String";
    };
  }

  private List<String> splitTopLevelCommas(String text) {
    List<String> list = new ArrayList<>();
    int depth = 0;
    StringBuilder current = new StringBuilder();

    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(') {
        depth++;
        current.append(c);
      } else if (c == ')') {
        depth = Math.max(0, depth - 1);
        current.append(c);
      } else if (c == ',' && depth == 0) {
        list.add(current.toString().trim());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    if (current.length() > 0 && !current.toString().trim().isEmpty()) {
      list.add(current.toString().trim());
    }
    return list;
  }

  private String cleanIdentifier(String id) {
    if (id == null) return "";
    return id.replaceAll("[`\"\\[\\]]", "").trim();
  }

  public String toPascalCase(String input) {
    if (input == null || input.isBlank()) return "Entidad";
    String[] parts = input.split("[_\\-\\s]+");
    StringBuilder sb = new StringBuilder();
    for (String p : parts) {
      if (!p.isEmpty()) {
        sb.append(Character.toUpperCase(p.charAt(0)));
        if (p.length() > 1) {
          sb.append(p.substring(1).toLowerCase());
        }
      }
    }
    return sb.toString();
  }

  public static class ParsedTable {
    public final String rawTableName;
    public final String className;
    public final List<ParsedColumn> columns = new ArrayList<>();
    public final List<ParsedForeignKey> foreignKeys = new ArrayList<>();

    public ParsedTable(String rawTableName, String className) {
      this.rawTableName = rawTableName;
      this.className = className;
    }
  }

  public static class ParsedColumn {
    public final String name;
    public final String umlType;
    public boolean isPrimaryKey;
    public final boolean isNotNull;
    public String inlineRefTable;

    public ParsedColumn(String name, String umlType, boolean isPrimaryKey, boolean isNotNull) {
      this.name = name;
      this.umlType = umlType;
      this.isPrimaryKey = isPrimaryKey;
      this.isNotNull = isNotNull;
    }
  }

  public static class ParsedForeignKey {
    public final String columnName;
    public final String referencedTable;
    public final boolean isNotNull;

    public ParsedForeignKey(String columnName, String referencedTable, boolean isNotNull) {
      this.columnName = columnName;
      this.referencedTable = referencedTable;
      this.isNotNull = isNotNull;
    }
  }
}
