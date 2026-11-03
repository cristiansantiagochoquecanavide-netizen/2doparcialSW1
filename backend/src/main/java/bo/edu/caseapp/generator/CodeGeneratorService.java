package bo.edu.caseapp.generator;

import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.generator.GeneratorRequests.GenerateConfig;
import bo.edu.caseapp.generator.GeneratorResponses.GeneratedFile;
import bo.edu.caseapp.generator.GeneratorResponses.GenerationPreview;
import bo.edu.caseapp.generator.GeneratorResponses.GenerationHistoryItem;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.uml.relational.*;
import bo.edu.caseapp.user.User;
import bo.edu.caseapp.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CodeGeneratorService {
  private final DiagramaClaseRepository diagramRepo;
  private final ElementoUMLRepository elementRepo;
  private final RelacionUMLRepository relationRepo;
  private final GeneracionSoftwareRepository genRepo;
  private final ProjectService projectService;
  private final UserRepository userRepo;
  private final RelationalModelService relationalService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public CodeGeneratorService(
      DiagramaClaseRepository diagramRepo,
      ElementoUMLRepository elementRepo,
      RelacionUMLRepository relationRepo,
      GeneracionSoftwareRepository genRepo,
      ProjectService projectService,
      UserRepository userRepo,
      RelationalModelService relationalService
  ) {
    this.diagramRepo = diagramRepo;
    this.elementRepo = elementRepo;
    this.relationRepo = relationRepo;
    this.genRepo = genRepo;
    this.projectService = projectService;
    this.userRepo = userRepo;
    this.relationalService = relationalService;
  }

  @Transactional(readOnly = true)
  public GenerationPreview preview(UUID projectId, UUID diagramId, String email, GenerateConfig config) {
    projectService.requireAccess(projectId, email);
    DiagramaClase diagram = validateDiagram(projectId, diagramId);
    List<ElementoUML> elements = elementRepo.findByDiagramaId(diagramId);
    List<RelacionUML> relations = relationRepo.findByDiagramaId(diagramId);

    if (elements.isEmpty()) {
      throw new BadRequestException("El diagrama no tiene clases modeladas para generar software.");
    }

    Map<String, String> backendFiles = generateBackendFiles(elements, relations, config, diagram.getNombre());
    Map<String, String> frontendFiles = config.resolvedIncludeFrontend()
        ? generateFrontendFiles(elements, config, diagram.getNombre())
        : Collections.emptyMap();
    Map<String, String> flutterFiles = config.resolvedIncludeFlutter()
        ? generateFlutterFiles(elements, config, diagram.getNombre())
        : Collections.emptyMap();

    List<GeneratedFile> list = new ArrayList<>();
    backendFiles.forEach((path, content) ->
        list.add(new GeneratedFile(path, detectLanguage(path), content))
    );
    frontendFiles.forEach((path, content) ->
        list.add(new GeneratedFile(path, detectLanguage(path), content))
    );
    flutterFiles.forEach((path, content) ->
        list.add(new GeneratedFile(path, detectLanguage(path), content))
    );

    return new GenerationPreview(
        config.resolvedProjectName(),
        config.resolvedPackage(),
        backendFiles.size(),
        frontendFiles.size(),
        flutterFiles.size(),
        list
    );
  }

  @Transactional(readOnly = true)
  public List<GenerationHistoryItem> history(UUID projectId, UUID diagramId, String email) {
    projectService.requireAccess(projectId, email);
    validateDiagram(projectId, diagramId);
    return genRepo.findByDiagramaIdOrderByFechaDesc(diagramId)
        .stream()
        .map(g -> new GenerationHistoryItem(g.getId(), g.getTipoGeneracion(), g.getEstado(), g.getFecha()))
        .toList();
  }

  public byte[] generateZip(UUID projectId, UUID diagramId, String email, GenerateConfig config) {
    projectService.requireAccess(projectId, email);
    DiagramaClase diagram = validateDiagram(projectId, diagramId);
    User user = userRepo.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new NotFoundException("Usuario no encontrado."));
    List<ElementoUML> elements = elementRepo.findByDiagramaId(diagramId);
    List<RelacionUML> relations = relationRepo.findByDiagramaId(diagramId);

    if (elements.isEmpty()) {
      throw new BadRequestException("El diagrama debe tener al menos una clase modelada.");
    }

    Map<String, String> backendFiles = generateBackendFiles(elements, relations, config, diagram.getNombre());
    Map<String, String> frontendFiles = config.resolvedIncludeFrontend()
        ? generateFrontendFiles(elements, config, diagram.getNombre())
        : Collections.emptyMap();
    Map<String, String> flutterFiles = config.resolvedIncludeFlutter()
        ? generateFlutterFiles(elements, config, diagram.getNombre())
        : Collections.emptyMap();

    // Registrar en BD la generación
    try {
      String cfgJson = objectMapper.writeValueAsString(config);
      genRepo.save(new GeneracionSoftware(diagram, user, config.resolvedIncludeFrontend() ? "FULLSTACK" : "BACKEND", cfgJson));
    } catch (Exception ignored) {}

    // Empaquetar en memoria como ZIP
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
         ZipOutputStream zos = new ZipOutputStream(baos)) {

      String rootDir = config.resolvedProjectName() + "/";

      for (Map.Entry<String, String> entry : backendFiles.entrySet()) {
        ZipEntry ze = new ZipEntry(rootDir + "backend/" + entry.getKey());
        zos.putNextEntry(ze);
        zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }

      for (Map.Entry<String, String> entry : frontendFiles.entrySet()) {
        ZipEntry ze = new ZipEntry(rootDir + "frontend/" + entry.getKey());
        zos.putNextEntry(ze);
        zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }

      for (Map.Entry<String, String> entry : flutterFiles.entrySet()) {
        ZipEntry ze = new ZipEntry(rootDir + "flutter/" + entry.getKey());
        zos.putNextEntry(ze);
        zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }

      // Añadir compose.yaml raíz para ejecutar el proyecto generado fácilmente
      String composeContent = generateRootDockerCompose(config);
      ZipEntry cze = new ZipEntry(rootDir + "docker-compose.yml");
      zos.putNextEntry(cze);
      zos.write(composeContent.getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();

      // Añadir README raíz con instrucciones de ejecución
      String readmeContent = generateRootReadme(config);
      ZipEntry rze = new ZipEntry(rootDir + "README.md");
      zos.putNextEntry(rze);
      zos.write(readmeContent.getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();

      zos.finish();
      return baos.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException("Error al empaquetar el archivo ZIP de software generado.", e);
    }
  }

  // =========================================================================
  // GENERADOR BACKEND SPRING BOOT 3 + JAVA 21
  // =========================================================================

  private Map<String, String> generateBackendFiles(
      List<ElementoUML> elements,
      List<RelacionUML> relations,
      GenerateConfig config,
      String diagramName
  ) {
    Map<String, String> files = new LinkedHashMap<>();
    String pkg = config.resolvedPackage();
    String pkgPath = "src/main/java/" + pkg.replace('.', '/') + "/";

    // 1. pom.xml
    files.put("pom.xml", generatePomXml(config));

    // 2. application.yml
    files.put("src/main/resources/application.yml", generateApplicationYml(config));

    // 3. schema.sql DDL
    RelationalSchema schema = relationalService.generateSchema(elements, relations);
    files.put("src/main/resources/schema.sql", schema.sqlDdl());

    // 4. Application.java
    files.put(pkgPath + "Application.java", generateApplicationClass(pkg));

    // 5. Generar clases de dominio
    for (ElementoUML el : elements) {
      if (el.getTipoElemento() == TipoElementoUML.INTERFAZ) continue;

      String className = toPascalCase(el.getNombre());
      List<FieldDef> fields = parseFields(el.getPropiedadesJson());

      // Entity
      files.put(pkgPath + "model/" + className + ".java", generateEntityJava(pkg, className, fields, el.getTipoElemento()));
      // Repository
      files.put(pkgPath + "repository/" + className + "Repository.java", generateRepositoryJava(pkg, className));
      // Service
      files.put(pkgPath + "service/" + className + "Service.java", generateServiceJava(pkg, className, fields));
      // Controller
      files.put(pkgPath + "controller/" + className + "Controller.java", generateControllerJava(pkg, className, fields));
    }

    return files;
  }

  private String generatePomXml(GenerateConfig config) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>3.4.5</version>
            <relativePath/>
          </parent>
          <groupId>%s</groupId>
          <artifactId>%s</artifactId>
          <version>0.0.1-SNAPSHOT</version>
          <name>%s</name>
          <description>Backend generado automáticamente desde modelo UML</description>
          <properties>
            <java.version>21</java.version>
          </properties>
          <dependencies>
            <dependency>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-web</artifactId>
            </dependency>
            <dependency>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-data-jpa</artifactId>
            </dependency>
            <dependency>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-validation</artifactId>
            </dependency>
            <dependency>
              <groupId>org.postgresql</groupId>
              <artifactId>postgresql</artifactId>
              <scope>runtime</scope>
            </dependency>
          </dependencies>
          <build>
            <plugins>
              <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
              </plugin>
            </plugins>
          </build>
        </project>
        """.formatted(config.resolvedPackage(), config.resolvedProjectName(), config.resolvedProjectName());
  }

  private String generateApplicationYml(GenerateConfig config) {
    return """
        server:
          port: %d
        spring:
          application:
            name: %s
          datasource:
            url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/%s
            username: ${DB_USER:%s}
            password: ${DB_PASSWORD:%s}
            driver-class-name: org.postgresql.Driver
          jpa:
            hibernate:
              ddl-auto: update
            show-sql: true
            properties:
              hibernate:
                format_sql: true
        """.formatted(
        config.resolvedPort(),
        config.resolvedProjectName(),
        config.resolvedDbName(),
        config.resolvedDbUser(),
        config.resolvedDbPassword()
    );
  }

  private String generateApplicationClass(String pkg) {
    return """
        package %s;

        import org.springframework.boot.SpringApplication;
        import org.springframework.boot.autoconfigure.SpringBootApplication;

        @SpringBootApplication
        public class Application {
          public static void main(String[] args) {
            SpringApplication.run(Application.class, args);
          }
        }
        """.formatted(pkg);
  }

  private String generateEntityJava(String pkg, String className, List<FieldDef> fields, TipoElementoUML tipo) {
    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(pkg).append(".model;\n\n");
    sb.append("import jakarta.persistence.*;\n");
    sb.append("import java.time.LocalDate;\n");
    sb.append("import java.time.Instant;\n");
    sb.append("import java.util.UUID;\n\n");

    sb.append("@Entity\n");
    sb.append("@Table(name = \"").append(toSnakeCase(className)).append("\")\n");
    if (tipo == TipoElementoUML.CLASE_ABSTRACTA) {
      sb.append("@Inheritance(strategy = InheritanceType.JOINED)\n");
      sb.append("public abstract class ").append(className).append(" {\n");
    } else {
      sb.append("public class ").append(className).append(" {\n");
    }

    // ID
    sb.append("  @Id\n");
    sb.append("  @GeneratedValue(strategy = GenerationType.UUID)\n");
    sb.append("  private UUID id;\n\n");

    // Fields
    for (FieldDef f : fields) {
      if (f.name().equalsIgnoreCase("id")) continue;
      sb.append("  @Column(name = \"").append(toSnakeCase(f.name())).append("\"");
      if (f.nullable()) sb.append(", nullable = false");
      sb.append(")\n");
      sb.append("  private ").append(f.javaType()).append(" ").append(f.name()).append(";\n\n");
    }

    // Default constructor
    sb.append("  public ").append(className).append("() {}\n\n");

    // Getters and Setters
    sb.append("  public UUID getId() { return id; }\n");
    sb.append("  public void setId(UUID id) { this.id = id; }\n\n");

    for (FieldDef f : fields) {
      if (f.name().equalsIgnoreCase("id")) continue;
      String cap = Character.toUpperCase(f.name().charAt(0)) + f.name().substring(1);
      sb.append("  public ").append(f.javaType()).append(" get").append(cap).append("() { return ").append(f.name()).append("; }\n");
      sb.append("  public void set").append(cap).append("(").append(f.javaType()).append(" ").append(f.name()).append(") { this.").append(f.name()).append(" = ").append(f.name()).append("; }\n\n");
    }

    sb.append("}\n");
    return sb.toString();
  }

  private String generateRepositoryJava(String pkg, String className) {
    return """
        package %s.repository;

        import %s.model.%s;
        import java.util.UUID;
        import org.springframework.data.jpa.repository.JpaRepository;
        import org.springframework.stereotype.Repository;

        @Repository
        public interface %sRepository extends JpaRepository<%s, UUID> {
        }
        """.formatted(pkg, pkg, className, className, className);
  }

  private String generateServiceJava(String pkg, String className, List<FieldDef> fields) {
    return """
        package %s.service;

        import %s.model.%s;
        import %s.repository.%sRepository;
        import java.util.List;
        import java.util.Optional;
        import java.util.UUID;
        import org.springframework.stereotype.Service;
        import org.springframework.transaction.annotation.Transactional;

        @Service
        @Transactional
        public class %sService {
          private final %sRepository repository;

          public %sService(%sRepository repository) {
            this.repository = repository;
          }

          @Transactional(readOnly = true)
          public List<%s> findAll() {
            return repository.findAll();
          }

          @Transactional(readOnly = true)
          public Optional<%s> findById(UUID id) {
            return repository.findById(id);
          }

          public %s create(%s entity) {
            return repository.save(entity);
          }

          public Optional<%s> update(UUID id, %s updated) {
            return repository.findById(id).map(existing -> {
              updated.setId(id);
              return repository.save(updated);
            });
          }

          public boolean deleteById(UUID id) {
            if (repository.existsById(id)) {
              repository.deleteById(id);
              return true;
            }
            return false;
          }
        }
        """.formatted(pkg, pkg, className, pkg, className, className, className, className, className, className, className, className, className, className, className);
  }

  private String generateControllerJava(String pkg, String className, List<FieldDef> fields) {
    String plural = toPlural(className.toLowerCase());
    return """
        package %s.controller;

        import %s.model.%s;
        import %s.service.%sService;
        import jakarta.validation.Valid;
        import java.util.List;
        import java.util.UUID;
        import org.springframework.http.HttpStatus;
        import org.springframework.http.ResponseEntity;
        import org.springframework.web.bind.annotation.*;

        @RestController
        @RequestMapping("/api/v1/%s")
        @CrossOrigin(origins = "*")
        public class %sController {
          private final %sService service;

          public %sController(%sService service) {
            this.service = service;
          }

          @GetMapping
          public List<%s> listAll() {
            return service.findAll();
          }

          @GetMapping("/{id}")
          public ResponseEntity<%s> getById(@PathVariable UUID id) {
            return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
          }

          @PostMapping
          @ResponseStatus(HttpStatus.CREATED)
          public %s create(@Valid @RequestBody %s entity) {
            return service.create(entity);
          }

          @PutMapping("/{id}")
          public ResponseEntity<%s> update(@PathVariable UUID id, @Valid @RequestBody %s entity) {
            return service.update(id, entity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
          }

          @DeleteMapping("/{id}")
          public ResponseEntity<Void> delete(@PathVariable UUID id) {
            return service.deleteById(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
          }
        }
        """.formatted(pkg, pkg, className, pkg, className, plural, className, className, className, className, className, className, className, className, className, className);
  }

  // =========================================================================
  // GENERADOR FRONTEND REACT + TYPESCRIPT + VITE
  // =========================================================================

  private Map<String, String> generateFrontendFiles(
      List<ElementoUML> elements,
      GenerateConfig config,
      String diagramName
  ) {
    Map<String, String> files = new LinkedHashMap<>();

    // 1. package.json
    files.put("package.json", generateFrontendPackageJson(config));

    // 2. vite.config.ts
    files.put("vite.config.ts", generateFrontendViteConfig(config));

    // 3. index.html
    files.put("index.html", generateFrontendIndexHtml(config));

    // 4. src/api.ts
    files.put("src/api.ts", generateFrontendApiTs(config));

    // 5. src/index.css
    files.put("src/index.css", generateFrontendCss());

    // 6. Componentes por entidad
    List<ElementoUML> validClasses = elements.stream()
        .filter(e -> e.getTipoElemento() != TipoElementoUML.INTERFAZ)
        .toList();

    for (ElementoUML el : validClasses) {
      String className = toPascalCase(el.getNombre());
      List<FieldDef> fields = parseFields(el.getPropiedadesJson());
      files.put("src/components/" + className + "View.tsx", generateEntityComponent(className, fields, config));
    }

    // 7. src/App.tsx con navegación por pestañas de entidades
    files.put("src/App.tsx", generateFrontendAppTsx(validClasses, config));

    return files;
  }

  private String generateFrontendPackageJson(GenerateConfig config) {
    return """
        {
          "name": "%s-frontend",
          "private": true,
          "version": "0.1.0",
          "type": "module",
          "scripts": {
            "dev": "vite",
            "build": "tsc && vite build",
            "preview": "vite preview"
          },
          "dependencies": {
            "react": "^19.0.0",
            "react-dom": "^19.0.0"
          },
          "devDependencies": {
            "@types/react": "^19.0.0",
            "@types/react-dom": "^19.0.0",
            "@vitejs/plugin-react": "^4.3.4",
            "typescript": "^5.7.2",
            "vite": "^6.0.11"
          }
        }
        """.formatted(config.resolvedProjectName());
  }

  private String generateFrontendViteConfig(GenerateConfig config) {
    return """
        import { defineConfig } from 'vite'
        import react from '@vitejs/plugin-react'

        export default defineConfig({
          plugins: [react()],
          server: {
            port: 3000,
            proxy: {
              '/api': {
                target: 'http://localhost:%d',
                changeOrigin: true
              }
            }
          }
        })
        """.formatted(config.resolvedPort());
  }

  private String generateFrontendIndexHtml(GenerateConfig config) {
    return """
        <!doctype html>
        <html lang="es">
          <head>
            <meta charset="UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>%s · Aplicación Generada</title>
          </head>
          <body>
            <div id="root"></div>
            <script type="module" src="/src/App.tsx"></script>
          </body>
        </html>
        """.formatted(config.resolvedProjectName());
  }

  private String generateFrontendApiTs(GenerateConfig config) {
    return """
        const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:%d';

        export async function apiRequest<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
          const res = await fetch(`${BASE_URL}${endpoint}`, {
            ...options,
            headers: {
              'Content-Type': 'application/json',
              ...options.headers
            }
          });
          if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.message || 'Error en la petición al servidor.');
          }
          if (res.status === 204) return null as unknown as T;
          return res.json();
        }
        """.formatted(config.resolvedPort());
  }

  private String generateEntityComponent(String className, List<FieldDef> fields, GenerateConfig config) {
    String plural = toPlural(className.toLowerCase());
    StringBuilder formFields = new StringBuilder();
    StringBuilder tableHeaders = new StringBuilder();
    StringBuilder tableCells = new StringBuilder();

    for (FieldDef f : fields) {
      if (f.name().equalsIgnoreCase("id")) continue;
      String inputType = switch (f.javaType()) {
        case "Integer", "Long", "Double" -> "number";
        case "LocalDate" -> "date";
        case "Boolean" -> "checkbox";
        default -> "text";
      };

      tableHeaders.append("            <th>").append(f.name()).append("</th>\n");
      tableCells.append("              <td>{String(item.").append(f.name()).append(" ?? '-')}</td>\n");

      formFields.append("""
                  <div className="form-group">
                    <label>%s</label>
                    <input
                      name="%s"
                      type="%s"
                      defaultValue={editing ? String(editing.%s ?? '') : ''}
                      required
                    />
                  </div>
          """.formatted(f.name(), f.name(), inputType, f.name()));
    }

    return """
        import React, { useEffect, useState } from 'react';
        import { apiRequest } from '../api';

        export interface %s {
          id: string;
          [key: string]: any;
        }

        export function %sView() {
          const [items, setItems] = useState<%s[]>([]);
          const [loading, setLoading] = useState(false);
          const [editing, setEditing] = useState<%s | null>(null);
          const [showModal, setShowModal] = useState(false);
          const [error, setError] = useState<string | null>(null);

          const load = async () => {
            try {
              setLoading(true);
              const data = await apiRequest<%s[]>('/api/v1/%s');
              setItems(data);
              setError(null);
            } catch (err: any) {
              setError(err.message);
            } finally {
              setLoading(false);
            }
          };

          useEffect(() => { load(); }, []);

          const handleSave = async (e: React.FormEvent<HTMLFormElement>) => {
            e.preventDefault();
            const form = new FormData(e.currentTarget);
            const payload: any = {};
            form.forEach((value, key) => { payload[key] = value; });

            try {
              if (editing?.id) {
                await apiRequest(`/api/v1/%s/${editing.id}`, { method: 'PUT', body: JSON.stringify(payload) });
              } else {
                await apiRequest('/api/v1/%s', { method: 'POST', body: JSON.stringify(payload) });
              }
              setShowModal(false);
              setEditing(null);
              load();
            } catch (err: any) {
              alert(err.message);
            }
          };

          const handleDelete = async (id: string) => {
            if (!confirm('¿Eliminar este registro?')) return;
            try {
              await apiRequest(`/api/v1/%s/${id}`, { method: 'DELETE' });
              load();
            } catch (err: any) {
              alert(err.message);
            }
          };

          return (
            <div className="entity-view">
              <div className="view-header">
                <h2>Gestión de %s</h2>
                <button className="btn btn-primary" onClick={() => { setEditing(null); setShowModal(true); }}>
                  + Nuevo %s
                </button>
              </div>

              {error && <div className="alert error">{error}</div>}

              {loading ? <p>Cargando datos...</p> : (
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>ID</th>
        %s              <th>Acciones</th>
                    </tr>
                  </thead>
                  <tbody>
                    {items.length === 0 ? (
                      <tr><td colSpan={10} style={{ textAlign: 'center' }}>No hay registros disponibles.</td></tr>
                    ) : (
                      items.map(item => (
                        <tr key={item.id}>
                          <td><code>{item.id?.substring(0, 8)}...</code></td>
        %s                  <td>
                            <button className="btn-sm" onClick={() => { setEditing(item); setShowModal(true); }}>Editar</button>
                            <button className="btn-sm btn-danger" onClick={() => handleDelete(item.id)}>Eliminar</button>
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              )}

              {showModal && (
                <div className="modal-backdrop">
                  <div className="modal-card">
                    <h3>{editing ? 'Editar %s' : 'Nuevo %s'}</h3>
                    <form onSubmit={handleSave}>
        %s              <div className="modal-actions">
                        <button type="button" className="btn btn-secondary" onClick={() => setShowModal(false)}>Cancelar</button>
                        <button type="submit" className="btn btn-primary">Guardar</button>
                      </div>
                    </form>
                  </div>
                </div>
              )}
            </div>
          );
        }
        """.formatted(
        className, className, className, className, className, plural,
        plural, plural, plural, className, className,
        tableHeaders.toString(), tableCells.toString(),
        className, className, formFields.toString()
    );
  }

  private String generateFrontendAppTsx(List<ElementoUML> validClasses, GenerateConfig config) {
    StringBuilder imports = new StringBuilder();
    StringBuilder tabs = new StringBuilder();
    StringBuilder views = new StringBuilder();

    String firstClass = validClasses.isEmpty() ? "" : toPascalCase(validClasses.get(0).getNombre());

    for (ElementoUML el : validClasses) {
      String name = toPascalCase(el.getNombre());
      imports.append("import { ").append(name).append("View } from './components/").append(name).append("View';\n");
      tabs.append("""
                <button
                  className={`tab-btn ${activeTab === '%s' ? 'active' : ''}`}
                  onClick={() => setActiveTab('%s')}
                >
                  %s
                </button>
          """.formatted(name, name, name));
      views.append("""
            {activeTab === '%s' && <%sView />}
          """.formatted(name, name));
    }

    return """
        import React, { useState } from 'react';
        import './index.css';
        %s
        export default function App() {
          const [activeTab, setActiveTab] = useState('%s');

          return (
            <div className="app-container">
              <header className="app-header">
                <div>
                  <h1>%s</h1>
                  <p className="subtitle">Sistema generado automáticamente desde Modelo UML</p>
                </div>
              </header>

              <nav className="tabs-nav">
        %s      </nav>

              <main className="content-area">
        %s      </main>
            </div>
          );
        }
        """.formatted(imports.toString(), firstClass, config.resolvedProjectName(), tabs.toString(), views.toString());
  }

  private String generateFrontendCss() {
    return """
        :root {
          --primary: #2563eb;
          --primary-hover: #1d4ed8;
          --bg: #f8fafc;
          --card: #ffffff;
          --text: #0f172a;
          --muted: #64748b;
          --border: #e2e8f0;
          --danger: #ef4444;
        }

        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: var(--bg); color: var(--text); }
        .app-container { max-width: 1200px; margin: 0 auto; padding: 2rem; }
        .app-header { margin-bottom: 1.5rem; padding-bottom: 1rem; border-bottom: 1px solid var(--border); }
        .app-header h1 { font-size: 1.8rem; font-weight: 700; color: var(--primary); }
        .subtitle { color: var(--muted); font-size: 0.95rem; margin-top: 0.25rem; }

        .tabs-nav { display: flex; gap: 0.5rem; margin-bottom: 1.5rem; border-bottom: 2px solid var(--border); }
        .tab-btn { padding: 0.6rem 1.2rem; background: none; border: none; border-bottom: 3px solid transparent; font-weight: 600; cursor: pointer; color: var(--muted); }
        .tab-btn.active { color: var(--primary); border-bottom-color: var(--primary); }

        .entity-view { background: var(--card); border-radius: 8px; border: 1px solid var(--border); padding: 1.5rem; box-shadow: 0 1px 3px rgba(0,0,0,0.05); }
        .view-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.25rem; }
        .btn { padding: 0.5rem 1rem; border-radius: 6px; font-weight: 600; cursor: pointer; border: none; }
        .btn-primary { background: var(--primary); color: white; }
        .btn-primary:hover { background: var(--primary-hover); }
        .btn-secondary { background: var(--border); color: var(--text); }
        .btn-sm { padding: 0.3rem 0.6rem; font-size: 0.8rem; border-radius: 4px; border: 1px solid var(--border); background: white; cursor: pointer; margin-right: 0.4rem; }
        .btn-danger { color: var(--danger); border-color: var(--danger); }

        .data-table { width: 100%; border-collapse: collapse; margin-top: 1rem; }
        .data-table th, .data-table td { padding: 0.75rem 1rem; border-bottom: 1px solid var(--border); text-align: left; }
        .data-table th { background: #f1f5f9; color: var(--muted); font-size: 0.85rem; text-transform: uppercase; }

        .modal-backdrop { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 50; }
        .modal-card { background: white; padding: 2rem; border-radius: 10px; width: 450px; max-width: 90vw; }
        .form-group { margin-bottom: 1rem; }
        .form-group label { display: block; font-size: 0.85rem; font-weight: 600; margin-bottom: 0.25rem; }
        .form-group input { width: 100%; padding: 0.5rem; border: 1px solid var(--border); border-radius: 6px; }
        .modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1.5rem; }
        .alert.error { padding: 0.75rem; background: #fee2e2; color: #991b1b; border-radius: 6px; margin-bottom: 1rem; }
        """;
  }

  // =========================================================================
  // GENERADOR FRONTEND FLUTTER + REGISTRO DE DATOS POR VOZ
  // =========================================================================

  private Map<String, String> generateFlutterFiles(
      List<ElementoUML> elements,
      GenerateConfig config,
      String diagramName
  ) {
    Map<String, String> files = new LinkedHashMap<>();
    List<ElementoUML> validClasses = elements.stream()
        .filter(e -> e.getTipoElemento() != TipoElementoUML.INTERFAZ)
        .toList();
    String context = escapeDart(diagramName == null || diagramName.isBlank()
        ? config.resolvedProjectName()
        : diagramName);

    files.put("pubspec.yaml", generateFlutterPubspec(config));
    files.put("lib/api_client.dart", generateFlutterApiClient(config));
    files.put("lib/voice_register.dart", generateFlutterVoiceRegister());
    files.put("lib/crud_screen.dart", generateFlutterCrudScreen());
    files.put("lib/main.dart", generateFlutterMain(validClasses, config, context));
    files.put("build_apk.ps1", generateFlutterApkScript(config));
    files.put("build_apk.bat", generateFlutterApkBatch(config));
    files.put("README.md", generateFlutterReadme(config, context));
    return files;
  }

  private String generateFlutterPubspec(GenerateConfig config) {
    return """
        name: %s_flutter
        description: Frontend Flutter generado desde el modelo UML.
        publish_to: 'none'
        version: 1.0.0+1

        environment:
          sdk: '>=3.3.0 <4.0.0'

        dependencies:
          flutter:
            sdk: flutter
          http: ^1.2.2
          speech_to_text: ^7.0.0

        dev_dependencies:
          flutter_test:
            sdk: flutter
          flutter_lints: ^4.0.0

        flutter:
          uses-material-design: true
        """.formatted(config.resolvedProjectName().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase());
  }

  private String generateFlutterApiClient(GenerateConfig config) {
    return """
        import 'dart:convert';
        import 'package:http/http.dart' as http;

        class ApiClient {
          // Android Emulator usa 10.0.2.2 para acceder al host local.
          static const baseUrl = String.fromEnvironment(
            'API_URL',
            defaultValue: 'http://10.0.2.2:%d',
          );

          Future<dynamic> request(String method, String endpoint, [Map<String, dynamic>? body]) async {
            final uri = Uri.parse('$baseUrl$endpoint');
            final headers = {'Content-Type': 'application/json'};
            late http.Response response;
            if (method == 'POST') {
              response = await http.post(uri, headers: headers, body: jsonEncode(body ?? {}));
            } else if (method == 'DELETE') {
              response = await http.delete(uri, headers: headers);
            } else if (method == 'PUT') {
              response = await http.put(uri, headers: headers, body: jsonEncode(body ?? {}));
            } else {
              response = await http.get(uri, headers: headers);
            }
            if (response.statusCode < 200 || response.statusCode >= 300) {
              throw Exception('Error ${response.statusCode}: ${response.body}');
            }
            return response.statusCode == 204 || response.body.isEmpty ? null : jsonDecode(response.body);
          }
        }
        """.formatted(config.resolvedPort());
  }

  private String generateFlutterVoiceRegister() {
    return """
        import 'package:flutter/material.dart';
        import 'package:speech_to_text/speech_to_text.dart' as stt;

        class VoiceRegisterButton extends StatefulWidget {
          final ValueChanged<String> onText;
          const VoiceRegisterButton({super.key, required this.onText});

          @override
          State<VoiceRegisterButton> createState() => _VoiceRegisterButtonState();
        }

        class _VoiceRegisterButtonState extends State<VoiceRegisterButton> {
          final stt.SpeechToText speech = stt.SpeechToText();
          bool listening = false;

          Future<void> toggle() async {
            if (listening) {
              await speech.stop();
              setState(() => listening = false);
              return;
            }
            final ready = await speech.initialize();
            if (!ready) return;
            setState(() => listening = true);
            await speech.listen(
              localeId: 'es_BO',
              onResult: (result) {
                widget.onText(result.recognizedWords);
                if (result.finalResult && mounted) setState(() => listening = false);
              },
            );
          }

          @override
          Widget build(BuildContext context) => IconButton(
            tooltip: listening ? 'Detener registro por voz' : 'Registrar por voz',
            icon: Icon(listening ? Icons.mic : Icons.mic_none, color: listening ? Colors.red : null),
            onPressed: toggle,
          );
        }
        """;
  }

  private String generateFlutterCrudScreen() {
    return """
        import 'package:flutter/material.dart';
        import 'api_client.dart';
        import 'voice_register.dart';

        class FieldDefinition {
          final String name;
          final String type;
          const FieldDefinition(this.name, this.type);
        }

        class CrudScreen extends StatefulWidget {
          final String title;
          final String endpoint;
          final List<FieldDefinition> fields;
          const CrudScreen({super.key, required this.title, required this.endpoint, required this.fields});

          @override
          State<CrudScreen> createState() => _CrudScreenState();
        }

        class _CrudScreenState extends State<CrudScreen> {
          final ApiClient api = ApiClient();
          final Map<String, TextEditingController> controllers = {};
          List<dynamic> items = [];
          String? error;
          bool loading = false;

          @override
          void initState() {
            super.initState();
            for (final field in widget.fields) controllers[field.name] = TextEditingController();
            load();
          }

          @override
          void dispose() { for (final c in controllers.values) c.dispose(); super.dispose(); }

          Future<void> load() async {
            setState(() => loading = true);
            try { items = await api.request('GET', widget.endpoint) as List<dynamic>; error = null; }
            catch (e) { error = e.toString(); }
            if (mounted) setState(() => loading = false);
          }

          void applyVoice(String text) {
            // Acepta frases como: "registrar nombre: Carlos, ciudad: La Paz".
            final normalized = text.trim().replaceFirst(RegExp(r'^.*?registrar\\s+', caseSensitive: false), '');
            for (final part in normalized.split(RegExp(r',|\\s+y\\s+', caseSensitive: false))) {
              final pieces = part.split(RegExp(r'\\s*[:=]\\s*'));
              if (pieces.length < 2) continue;
              final key = pieces.first.trim();
              final field = widget.fields.cast<FieldDefinition?>().firstWhere(
                (item) => item!.name.toLowerCase() == key.toLowerCase(),
                orElse: () => null,
              );
              if (field != null) controllers[field.name]?.text = pieces.sublist(1).join(':').trim();
            }
          }

          dynamic typedValue(FieldDefinition field, String value) {
            switch (field.type) {
              case 'Integer': return int.tryParse(value) ?? value;
              case 'Long': return int.tryParse(value) ?? value;
              case 'Double': return double.tryParse(value) ?? value;
              case 'Boolean': return value.toLowerCase() == 'true' || value.toLowerCase() == 'sí' || value == '1';
              default: return value;
            }
          }

          Future<void> save() async {
            final body = <String, dynamic>{};
            for (final field in widget.fields) {
              final value = controllers[field.name]?.text.trim() ?? '';
              if (value.isNotEmpty) body[field.name] = typedValue(field, value);
            }
            try { await api.request('POST', widget.endpoint, body); if (mounted) Navigator.pop(context); await load(); }
            catch (e) { if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString()))); }
          }

          Future<void> remove(String id) async {
            try { await api.request('DELETE', '${widget.endpoint}/$id'); await load(); }
            catch (e) { if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString()))); }
          }

          void openForm() {
            for (final c in controllers.values) c.clear();
            showDialog(context: context, builder: (_) => AlertDialog(
              title: Row(children: [Expanded(child: Text('Nuevo ${widget.title}')), VoiceRegisterButton(onText: applyVoice)]),
              content: SingleChildScrollView(child: Column(children: widget.fields.map((field) => TextField(
                controller: controllers[field.name], decoration: InputDecoration(labelText: field.name),
              )).toList())),
              actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancelar')), ElevatedButton(onPressed: save, child: const Text('Guardar'))],
            ));
          }

          @override
          Widget build(BuildContext context) => Scaffold(
            appBar: AppBar(title: Text(widget.title), actions: [IconButton(onPressed: load, icon: const Icon(Icons.refresh))]),
            body: loading ? const Center(child: CircularProgressIndicator()) : error != null
              ? Center(child: Text(error!))
              : ListView.builder(itemCount: items.length, itemBuilder: (_, i) {
                  final item = items[i] as Map<String, dynamic>;
                  return Card(child: ListTile(
                    title: Text(item.values.take(2).map((v) => '$v').join(' · ')),
                    subtitle: Text(item.entries.map((e) => '${e.key}: ${e.value}').join(' | ')),
                    trailing: IconButton(icon: const Icon(Icons.delete_outline), onPressed: () => remove('${item['id']}')),
                  ));
                }),
            floatingActionButton: FloatingActionButton.extended(onPressed: openForm, icon: const Icon(Icons.add), label: const Text('Registrar')),
          );
        }
        """;
  }

  private String generateFlutterMain(List<ElementoUML> classes, GenerateConfig config, String context) {
    StringBuilder tabs = new StringBuilder();
    for (ElementoUML el : classes) {
      String name = toPascalCase(el.getNombre());
      String endpoint = "/api/v1/" + toPlural(name.toLowerCase());
      StringBuilder fields = new StringBuilder("[");
      for (FieldDef field : parseFields(el.getPropiedadesJson())) {
        if (!field.name().equalsIgnoreCase("id")) {
          fields.append("const FieldDefinition('").append(escapeDart(field.name())).append("', '")
              .append(escapeDart(field.javaType())).append("'),");
        }
      }
      fields.append("]");
      tabs.append("        CrudScreen(title: '").append(escapeDart(name)).append("', endpoint: '")
          .append(endpoint).append("', fields: ").append(fields).append("),\n");
    }
    String first = classes.isEmpty() ? "const Scaffold(body: Center(child: Text('No hay clases para mostrar')))" : "screens[0]";
    return """
        import 'package:flutter/material.dart';
        import 'crud_screen.dart';

        void main() => runApp(const GeneratedApp());

        class GeneratedApp extends StatelessWidget {
          const GeneratedApp({super.key});
          @override
          Widget build(BuildContext context) {
            final screens = <Widget>[
        %s            ];
            return MaterialApp(
              debugShowCheckedModeBanner: false,
              title: '%s',
              theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
              home: screens.isEmpty ? %s : _Home(screens: screens, contextName: '%s'),
            );
          }
        }

        class _Home extends StatefulWidget {
          final List<Widget> screens;
          final String contextName;
          const _Home({required this.screens, required this.contextName});
          @override State<_Home> createState() => _HomeState();
        }
        class _HomeState extends State<_Home> {
          int index = 0;
          @override
          Widget build(BuildContext context) => Scaffold(
            drawer: Drawer(child: ListView(children: [DrawerHeader(child: Text(widget.contextName)), ...List.generate(widget.screens.length, (i) => ListTile(title: Text('Módulo ${i + 1}'), onTap: () { setState(() => index = i); Navigator.pop(context); }))])),
            body: widget.screens[index],
          );
        }
        """.formatted(tabs.toString(), escapeDart(config.resolvedProjectName()), first, context);
  }

  private String generateFlutterReadme(GenerateConfig config, String context) {
    return """
        # Frontend Flutter — %s

        Generado desde el contexto del diagrama UML: **%s**.

        ## Ejecución
        1. Instala Flutter.
        2. Ejecuta `flutter create .` para crear las carpetas Android/iOS/Web del proyecto.
           En Android agrega el permiso `android.permission.RECORD_AUDIO` a `android/app/src/main/AndroidManifest.xml` para activar el micrófono.
        3. Ejecuta `flutter pub get`.
        4. Inicia el backend Spring Boot en el puerto `%d`.
        5. Ejecuta `flutter run`.

        En Android Emulator la API apunta a `10.0.2.2`. Para otro entorno usa:
        `flutter run --dart-define=API_URL=http://HOST:PUERTO`.

        ## Crear APK de prueba
        En Windows ejecuta `build_apk.ps1` o `build_apk.bat`.
        El archivo resultante estará en `build/app/outputs/flutter-apk/app-debug.apk`.

        ## Registro por voz
        En cada módulo pulsa **Registrar**, luego el micrófono y di, por ejemplo:
        `registrar nombre: Carlos, ciudad: La Paz`.
        Revisa los campos reconocidos antes de pulsar **Guardar**.
        """.formatted(config.resolvedProjectName(), context, config.resolvedPort());
  }

  private String generateFlutterApkScript(GenerateConfig config) {
    return """
        $ErrorActionPreference = 'Stop'
        if (-not (Get-Command flutter -ErrorAction SilentlyContinue)) {
          throw 'Flutter no está instalado o no está en el PATH.'
        }
        flutter create .
        flutter pub get
        $manifest = 'android/app/src/main/AndroidManifest.xml'
        if (Test-Path $manifest) {
          $xml = Get-Content $manifest -Raw
          if ($xml -notmatch 'android.permission.RECORD_AUDIO') {
            $xml = $xml -replace '(<manifest[^>]*>)', '$1`n    <uses-permission android:name="android.permission.RECORD_AUDIO" />'
            Set-Content -Path $manifest -Value $xml -Encoding UTF8
          }
        }
        $apiUrl = if ($env:API_URL) { $env:API_URL } else { 'http://10.0.2.2:%d' }
        flutter build apk --debug --dart-define=API_URL=$apiUrl
        Write-Host 'APK creado en build/app/outputs/flutter-apk/app-debug.apk' -ForegroundColor Green
        """.formatted(config.resolvedPort());
  }

  private String generateFlutterApkBatch(GenerateConfig config) {
    return """
        @echo off
        setlocal
        where flutter >nul 2>nul || (echo Flutter no esta instalado o no esta en PATH.& exit /b 1)
        flutter create .
        flutter pub get
        if "%API_URL%"=="" set API_URL=http://10.0.2.2:%d
        flutter build apk --debug --dart-define=API_URL=%API_URL%
        echo APK creado en build\\app\\outputs\\flutter-apk\\app-debug.apk
        """.formatted(config.resolvedPort());
  }

  private String escapeDart(String value) {
    return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
  }

  private String generateRootDockerCompose(GenerateConfig config) {
    return """
        services:
          postgres:
            image: postgres:16-alpine
            environment:
              POSTGRES_DB: %s
              POSTGRES_USER: %s
              POSTGRES_PASSWORD: %s
            ports:
              - "5432:5432"
            volumes:
              - pg_data:/var/lib/postgresql/data

          backend:
            build: ./backend
            environment:
              DB_HOST: postgres
              DB_PORT: 5432
              DB_NAME: %s
              DB_USER: %s
              DB_PASSWORD: %s
            ports:
              - "%d:%d"
            depends_on:
              - postgres

        volumes:
          pg_data:
        """.formatted(
        config.resolvedDbName(), config.resolvedDbUser(), config.resolvedDbPassword(),
        config.resolvedDbName(), config.resolvedDbUser(), config.resolvedDbPassword(),
        config.resolvedPort(), config.resolvedPort()
    );
  }

  private String generateRootReadme(GenerateConfig config) {
    return """
        # %s (Generado automáticamente)

        Proyecto fullstack generado a partir de la Herramienta CASE Colaborativa.

        ## Estructura
        - `backend/`: API Spring Boot 3 + Java 21 + Spring Data JPA.
        - `frontend/`: Aplicación React + TypeScript + Vite.

        ## Ejecución Rápida con Docker
        ```bash
        docker compose up --build -d
        ```

        ## Ejecución Manual en Desarrollo
        1. Base de datos: asegúrate de tener PostgreSQL ejecutándose en puerto 5432 con base de datos `%s`.
        2. Backend:
           ```bash
           cd backend
           mvn spring-boot:run
           ```
           API disponible en `http://localhost:%d`
        3. Frontend:
           ```bash
           cd frontend
           npm install
           npm run dev
           ```
           Cliente disponible en `http://localhost:3000`
        """.formatted(config.resolvedProjectName(), config.resolvedDbName(), config.resolvedPort());
  }

  // =========================================================================
  // AUXILIARES DE PARSEO Y FORMATEO
  // =========================================================================

  private record FieldDef(String name, String javaType, boolean nullable) {}

  private List<FieldDef> parseFields(String propiedadesJson) {
    List<FieldDef> fields = new ArrayList<>();
    if (propiedadesJson == null || propiedadesJson.isBlank()) return fields;

    try {
      JsonNode root = objectMapper.readTree(propiedadesJson);
      JsonNode attrs = root.get("attributes");
      if (attrs != null && attrs.isArray()) {
        for (JsonNode attr : attrs) {
          String name = attr.path("name").asText("campo");
          String type = attr.path("type").asText("String");
          fields.add(new FieldDef(toCamelCase(name), mapJavaType(type), true));
        }
      }
    } catch (Exception ignored) {}
    return fields;
  }

  private String mapJavaType(String type) {
    if (type == null) return "String";
    return switch (type.trim().toLowerCase()) {
      case "int", "integer" -> "Integer";
      case "long", "bigint" -> "Long";
      case "double", "decimal", "numeric", "float" -> "Double";
      case "bool", "boolean" -> "Boolean";
      case "date", "localdate" -> "LocalDate";
      case "timestamp", "datetime", "instant" -> "Instant";
      case "uuid" -> "UUID";
      default -> "String";
    };
  }

  private String toPascalCase(String name) {
    if (name == null || name.isBlank()) return "Entidad";
    String clean = name.replaceAll("[^a-zA-Z0-9]", " ").trim();
    String[] parts = clean.split("\\s+");
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (!part.isEmpty()) {
        sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase());
      }
    }
    return sb.length() > 0 ? sb.toString() : "Entidad";
  }

  private String toCamelCase(String name) {
    String pascal = toPascalCase(name);
    return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
  }

  private String toSnakeCase(String name) {
    if (name == null) return "tabla";
    return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase().replaceAll("[^a-z0-9_]", "_");
  }

  private String toPlural(String name) {
    if (name.endsWith("s")) return name;
    if (name.endsWith("y")) return name.substring(0, name.length() - 1) + "ies";
    return name + "s";
  }

  private String detectLanguage(String path) {
    if (path.endsWith(".java")) return "java";
    if (path.endsWith(".tsx") || path.endsWith(".ts")) return "typescript";
    if (path.endsWith(".xml")) return "xml";
    if (path.endsWith(".yml") || path.endsWith(".yaml")) return "yaml";
    if (path.endsWith(".json")) return "json";
    if (path.endsWith(".dart")) return "dart";
    if (path.endsWith(".sql")) return "sql";
    if (path.endsWith(".css")) return "css";
    return "plaintext";
  }

  private DiagramaClase validateDiagram(UUID projectId, UUID diagramId) {
    DiagramaClase diagram = diagramRepo.findById(diagramId)
        .orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    if (!diagram.getProject().getId().equals(projectId)) {
      throw new NotFoundException("El diagrama no pertenece a este proyecto.");
    }
    return diagram;
  }
}
