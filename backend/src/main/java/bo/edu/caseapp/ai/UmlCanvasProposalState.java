package bo.edu.caseapp.ai;

import bo.edu.caseapp.error.*;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.UmlService;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared selection only. No provisional UML or offline operations are persisted. */
@Service @Transactional
public class UmlCanvasProposalState {
  private final JdbcTemplate jdbc; private final ProjectService projects; private final UmlService uml;
  private final ApplicationEventPublisher events;
  public UmlCanvasProposalState(JdbcTemplate jdbc,ProjectService projects,UmlService uml,ApplicationEventPublisher events) {
    this.jdbc=jdbc;this.projects=projects;this.uml=uml;this.events=events;
  }
  public UUID active(UUID project,UUID diagram,String email) {
    projects.requireAccess(project,email);uml.requireDiagramAccess(project,diagram,email);
    var ids=jdbc.queryForList("SELECT s.proposal_id FROM ai_canvas_proposal s JOIN ai_uml_proposal p ON p.id=s.proposal_id WHERE s.diagram_id=? AND p.project_id=? AND p.scope='GROUP' AND p.status IN ('PENDIENTE','CONFLICTO')",UUID.class,diagram,project);
    return ids.isEmpty()?null:ids.getFirst();
  }
  public void select(UUID project,UUID diagram,UUID proposal,String email) {
    projects.requireCanEdit(project,email);uml.requireDiagramAccess(project,diagram,email);lock(diagram);
    if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ai_uml_proposal WHERE id=? AND project_id=? AND diagram_id=? AND scope='GROUP' AND status IN ('PENDIENTE','CONFLICTO'))",Boolean.class,proposal,project,diagram)))
      throw new NotFoundException("Propuesta compartida pendiente no encontrada.");
    jdbc.update("INSERT INTO ai_canvas_proposal(diagram_id,proposal_id) VALUES (?,?) ON CONFLICT(diagram_id) DO UPDATE SET proposal_id=excluded.proposal_id,updated_at=now()",diagram,proposal);
    changed(diagram);
  }
  public void prepared(UUID diagram,UUID proposal,String scope,boolean ready) {
    if(!scope.equals("GROUP") || !ready) return;
    lock(diagram);
    jdbc.update("INSERT INTO ai_canvas_proposal(diagram_id,proposal_id) VALUES (?,?) ON CONFLICT(diagram_id) DO UPDATE SET proposal_id=excluded.proposal_id,updated_at=now() WHERE ai_canvas_proposal.proposal_id IS NULL",diagram,proposal);
    changed(diagram); // Also refresh the pending list when another proposal remains active.
  }
  public void decided(UUID diagram,UUID proposal,String scope) {
    if(!scope.equals("GROUP")) return;
    jdbc.update("UPDATE ai_canvas_proposal SET proposal_id=null,updated_at=now() WHERE diagram_id=? AND proposal_id=?",diagram,proposal);
    changed(diagram);
  }
  public void changed(UUID diagram) { events.publishEvent(new UmlCanvasProposalChanged(diagram)); }
  public void lock(UUID diagram) { jdbc.queryForObject("SELECT id FROM diagrama_clase WHERE id=? FOR UPDATE",UUID.class,diagram); }
}
