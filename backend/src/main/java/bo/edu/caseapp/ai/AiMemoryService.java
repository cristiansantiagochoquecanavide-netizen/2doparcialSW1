package bo.edu.caseapp.ai;

import bo.edu.caseapp.ai.AiMemoryRequests.SaveEntry;
import bo.edu.caseapp.ai.AiMemoryRequests.UpdateProjectMemory;
import bo.edu.caseapp.ai.AiMemoryResponses.Entry;
import bo.edu.caseapp.ai.AiMemoryResponses.MemoryView;
import bo.edu.caseapp.ai.AiMemoryResponses.ProjectMemory;
import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.project.ProjectMember;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.DiagramaClase;
import bo.edu.caseapp.uml.DiagramaClaseRepository;
import bo.edu.caseapp.user.User;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AiMemoryService {
  private final ProjectService projects;
  private final ProjectAiMemoryRepository memories;
  private final ProjectAiMemoryEntryRepository entries;
  private final DiagramaClaseRepository diagrams;
  private final JdbcTemplate jdbc;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private AiAnalysisQueue analysisQueue;

  public AiMemoryService(ProjectService projects, ProjectAiMemoryRepository memories,
      ProjectAiMemoryEntryRepository entries, DiagramaClaseRepository diagrams, JdbcTemplate jdbc) {
    this.projects = projects;
    this.memories = memories;
    this.entries = entries;
    this.diagrams = diagrams;
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public MemoryView get(UUID projectId, UUID diagramId, String email) {
    ProjectMember member = projects.requireAccess(projectId, email);
    if (diagramId != null) requireDiagram(projectId, diagramId);
    ProjectMemory memory = memories.findById(projectId)
        .map(this::projectMemory)
        .orElseGet(() -> new ProjectMemory(projectId, null, null, null, null, null, null, null, null));
    List<ProjectAiMemoryEntry> rows = entries.findByProject_IdOrderByUpdatedAtDesc(projectId);
    List<Entry> visibleEntries = rows.stream()
        .filter(entry -> entry.getDiagram() == null || diagramId == null || diagramId.equals(entry.getDiagram().getId()))
        .map(this::entry)
        .toList();
    return new MemoryView(memory, visibleEntries);
  }

  public ProjectMemory updateProjectMemory(UUID projectId, String email, UpdateProjectMemory request) {
    ProjectMember member = projects.requireCanEdit(projectId, email);
    ProjectAiMemory existing = memories.findById(projectId).orElse(null);
    ProjectAiMemory memory = existing == null ? new ProjectAiMemory(member.getProject(), member.getUser()) : existing;
    memory.update(clean(request.systemName()), clean(request.systemDescription()), clean(request.businessDomain()),
        clean(request.objectives()), clean(request.additionalContext()), member.getUser());
    ProjectAiMemory saved = memories.save(memory);
    audit(projectId, member.getUser().getId(), "PROJECT", projectId, existing == null ? "CREATE" : "UPDATE",
        Stream.of(clean(request.systemName()), clean(request.systemDescription()), clean(request.businessDomain()),
            clean(request.objectives()), clean(request.additionalContext())).filter(value -> value != null)
            .collect(Collectors.joining("\n")));
    scheduleContextChange(projectId, email);
    return projectMemory(saved);
  }

  public Entry createConfirmedEntry(UUID projectId, String email, SaveEntry request) {
    ProjectMember member = projects.requireCanEdit(projectId, email);
    DiagramaClase diagram = request.diagramId() == null ? null : requireDiagram(projectId, request.diagramId());
    ProjectAiMemoryEntry entry = new ProjectAiMemoryEntry(member.getProject(), diagram, request.entryType(),
        AiMemoryConfidence.CONFIRMED, request.content().trim(), member.getUser());
    ProjectAiMemoryEntry saved = entries.save(entry);
    audit(projectId, member.getUser().getId(), "ENTRY", saved.getId(), "CREATE", saved.getContent());
    scheduleContextChange(projectId, email);
    return entry(saved);
  }

  public Entry updateAsConfirmed(UUID projectId, UUID entryId, String email, SaveEntry request) {
    ProjectMember member = projects.requireCanEdit(projectId, email);
    ProjectAiMemoryEntry entry = entries.findByIdAndProject_Id(entryId, projectId)
        .orElseThrow(() -> new NotFoundException("Entrada de memoria no encontrada."));
    if (request.diagramId() != null) {
      DiagramaClase diagram = requireDiagram(projectId, request.diagramId());
      if (entry.getDiagram() == null || !entry.getDiagram().getId().equals(diagram.getId())) {
        throw new NotFoundException("Entrada de memoria no encontrada.");
      }
    } else if (entry.getDiagram() != null) {
      throw new NotFoundException("Entrada de memoria no encontrada.");
    }
    AiMemoryConfidence previousConfidence = entry.getConfidenceScope();
    entry.updateAsConfirmed(request.entryType(), request.content().trim(), member.getUser());
    audit(projectId, member.getUser().getId(), "ENTRY", entry.getId(),
        previousConfidence == AiMemoryConfidence.INFERRED ? "CONFIRM" : "UPDATE", entry.getContent());
    scheduleContextChange(projectId, email);
    return entry(entry);
  }

  public void deleteEntry(UUID projectId, UUID entryId, String email) {
    ProjectMember member = projects.requireCanEdit(projectId, email);
    ProjectAiMemoryEntry entry = entries.findByIdAndProject_Id(entryId, projectId)
        .orElseThrow(() -> new NotFoundException("Entrada de memoria no encontrada."));
    audit(projectId, member.getUser().getId(), "ENTRY", entry.getId(), "DELETE", entry.getContent());
    entries.delete(entry);
    scheduleContextChange(projectId, email);
  }

  /** Internal hook for later semantic analysis; no endpoint imports chat messages into memory. */
  ProjectAiMemoryEntry recordInference(UUID projectId, UUID diagramId, String email,
      AiMemoryEntryType type, String content) {
    ProjectMember member = projects.requireCanEdit(projectId, email);
    DiagramaClase diagram = diagramId == null ? null : requireDiagram(projectId, diagramId);
    ProjectAiMemoryEntry inferred = entries.save(new ProjectAiMemoryEntry(member.getProject(), diagram, type,
        AiMemoryConfidence.INFERRED, content.trim(), member.getUser()));
    audit(projectId, member.getUser().getId(), "ENTRY", inferred.getId(), "INFER", inferred.getContent());
    return inferred;
  }

  /** Inferred entries are separate, never overwrite confirmed memory or another pending hypothesis. */
  public void recordAnalysisInference(UUID projectId, UUID diagramId, String email, String hypothesis) {
    var saved = get(projectId, diagramId, email);
    if (saved.projectMemory() != null && (saved.projectMemory().systemDescription() != null || saved.projectMemory().businessDomain() != null)) return;
    if (saved.entries().stream().anyMatch(entry -> entry.entryType() == AiMemoryEntryType.SYSTEM_CONTEXT || entry.confidenceScope() == AiMemoryConfidence.INFERRED)) return;
    recordInference(projectId, diagramId, email, AiMemoryEntryType.SYSTEM_CONTEXT, hypothesis);
  }

  private void scheduleContextChange(UUID projectId, String email) {
    if (analysisQueue == null) return;
    // Context edits remain subject to the project's automatic-analysis setting.
    for (DiagramaClase diagram : diagrams.findByProjectIdOrderByFechaCreacionAsc(projectId))
      analysisQueue.contextChanged(diagram.getId(), email);
  }

  private DiagramaClase requireDiagram(UUID projectId, UUID diagramId) {
    DiagramaClase diagram = diagrams.findById(diagramId)
        .orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    if (!diagram.getProject().getId().equals(projectId)) {
      throw new NotFoundException("Diagrama no encontrado en este proyecto.");
    }
    return diagram;
  }

  private ProjectMemory projectMemory(ProjectAiMemory memory) {
    User author = memory.getUpdatedBy();
    return new ProjectMemory(memory.getProjectId(), memory.getSystemName(), memory.getSystemDescription(),
        memory.getBusinessDomain(), memory.getObjectives(), memory.getAdditionalContext(),
        author.getId(), author.getName(), memory.getUpdatedAt());
  }

  private Entry entry(ProjectAiMemoryEntry entry) {
    User creator = entry.getCreatedBy();
    User author = entry.getUpdatedBy();
    return new Entry(entry.getId(), entry.getProject().getId(),
        entry.getDiagram() == null ? null : entry.getDiagram().getId(), entry.getEntryType(),
        entry.getConfidenceScope(), entry.getContent(), creator == null ? null : creator.getId(),
        creator == null ? "ACODI" : creator.getName(), author.getId(), author.getName(),
        entry.getCreatedAt(), entry.getUpdatedAt());
  }

  private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }

  private void audit(UUID projectId, UUID actorId, String targetType, UUID targetId, String action, String snapshot) {
    jdbc.update("""
        INSERT INTO project_ai_memory_audit (id, project_id, actor_user_id, target_type, target_id, action, content_snapshot)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), projectId, actorId, targetType, targetId, action, snapshot);
  }
}
