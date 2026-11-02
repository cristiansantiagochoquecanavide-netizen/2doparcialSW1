package bo.edu.caseapp.uml.relational;

import bo.edu.caseapp.project.Project;
import bo.edu.caseapp.project.ProjectRole;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.uml.UmlResponses.DiagramFullView;
import bo.edu.caseapp.uml.relational.SqlDdlParserService.ParsedTable;
import bo.edu.caseapp.user.User;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqlDdlParserServiceTest {

  @Mock private DiagramaClaseRepository diagramRepo;
  @Mock private ElementoUMLRepository elementRepo;
  @Mock private RelacionUMLRepository relationRepo;
  @Mock private ProjectService projectService;
  @Mock private UmlService umlService;

  @InjectMocks
  private SqlDdlParserService service;

  private Project project;
  private DiagramaClase diagram;
  private User owner;

  @BeforeEach
  void setUp() {
    owner = new User("admin@test.bo", "Admin", "hash");
    ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());

    project = new Project(owner, "Sistema Ventas", "Desc");
    ReflectionTestUtils.setField(project, "id", UUID.randomUUID());

    diagram = new DiagramaClase(project, "Diagrama Principal", "Desc");
    ReflectionTestUtils.setField(diagram, "id", UUID.randomUUID());
  }

  @Test
  void parseSqlTables_singleTableFromPromptSpecification() {
    String sql = """
        CREATE TABLE producto (
          id SERIAL PRIMARY KEY,
          nombre VARCHAR(100) NOT NULL,
          precio NUMERIC(10,2) NOT NULL,
          stock INT DEFAULT 0
        );
        """;

    List<ParsedTable> tables = service.parseSqlTables(sql);

    assertThat(tables).hasSize(1);
    ParsedTable table = tables.get(0);
    assertThat(table.rawTableName).isEqualTo("producto");
    assertThat(table.className).isEqualTo("Producto");
    assertThat(table.columns).hasSize(4);

    // id SERIAL PRIMARY KEY -> Long, isPrimaryKey=true
    assertThat(table.columns.get(0).name).isEqualTo("id");
    assertThat(table.columns.get(0).umlType).isEqualTo("Long");
    assertThat(table.columns.get(0).isPrimaryKey).isTrue();

    // nombre VARCHAR(100) NOT NULL -> String
    assertThat(table.columns.get(1).name).isEqualTo("nombre");
    assertThat(table.columns.get(1).umlType).isEqualTo("String");
    assertThat(table.columns.get(1).isNotNull).isTrue();

    // precio NUMERIC(10,2) NOT NULL -> Decimal
    assertThat(table.columns.get(2).name).isEqualTo("precio");
    assertThat(table.columns.get(2).umlType).isEqualTo("Decimal");

    // stock INT DEFAULT 0 -> Integer
    assertThat(table.columns.get(3).name).isEqualTo("stock");
    assertThat(table.columns.get(3).umlType).isEqualTo("Integer");
  }

  @Test
  void parseSqlTables_multipleTablesWithForeignKeysAndConstraints() {
    String sql = """
        CREATE TABLE cliente (
          id SERIAL PRIMARY KEY,
          nombre VARCHAR(100) NOT NULL,
          email VARCHAR(150) UNIQUE
        );

        CREATE TABLE pedido (
          id SERIAL PRIMARY KEY,
          cliente_id INT NOT NULL REFERENCES cliente(id),
          fecha TIMESTAMP NOT NULL,
          total NUMERIC(12,2) NOT NULL
        );
        """;

    List<ParsedTable> tables = service.parseSqlTables(sql);

    assertThat(tables).hasSize(2);
    ParsedTable cliente = tables.get(0);
    assertThat(cliente.className).isEqualTo("Cliente");

    ParsedTable pedido = tables.get(1);
    assertThat(pedido.className).isEqualTo("Pedido");
    assertThat(pedido.foreignKeys).hasSize(1);
    assertThat(pedido.foreignKeys.get(0).columnName).isEqualTo("cliente_id");
    assertThat(pedido.foreignKeys.get(0).referencedTable).isEqualTo("cliente");
    assertThat(pedido.foreignKeys.get(0).isNotNull).isTrue();
  }

  @Test
  void parseAndImportSql_persistsElementsAndRelations() {
    String sql = """
        CREATE TABLE producto (
          id SERIAL PRIMARY KEY,
          nombre VARCHAR(100) NOT NULL,
          precio NUMERIC(10,2) NOT NULL,
          stock INT DEFAULT 0
        );
        """;

    when(diagramRepo.findById(diagram.getId())).thenReturn(Optional.of(diagram));
    when(elementRepo.save(any(ElementoUML.class))).thenAnswer(inv -> {
      ElementoUML el = inv.getArgument(0);
      ReflectionTestUtils.setField(el, "id", UUID.randomUUID());
      return el;
    });

    DiagramFullView expectedView = new DiagramFullView(
        new UmlResponses.DiagramSummary(diagram.getId(), project.getId(), "Diagrama Principal", "Desc", "ACTIVO", null),
        List.of(),
        List.of()
    );
    when(umlService.getDiagram(project.getId(), diagram.getId(), "admin@test.bo")).thenReturn(expectedView);

    DiagramFullView result = service.parseAndImportSql(project.getId(), diagram.getId(), "admin@test.bo", sql);

    assertThat(result).isNotNull();
    verify(projectService).requireCanEdit(project.getId(), "admin@test.bo");
    verify(elementRepo, times(1)).save(any(ElementoUML.class));
    verify(umlService).notifyCollabAfterCommit(eq(diagram.getId()), eq("SQL_IMPORTED"), anyLong(), eq("admin@test.bo"), eq("IMPORT"));
  }
}
