package bo.edu.caseapp.ai;

import bo.edu.caseapp.error.*;
import bo.edu.caseapp.project.ProjectService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Separate transactions preserve a failure decision AFTER a failed UML transaction has rolled back. */
@Service @Transactional
public class UmlProposalDecisions {
  private final UmlProposalStore store; private final ProjectService projects; private final JdbcTemplate jdbc;
  private final AiConversationStore conversations;
  @org.springframework.beans.factory.annotation.Autowired private UmlCanvasProposalState canvas;
  public UmlProposalDecisions(UmlProposalStore store,ProjectService projects,JdbcTemplate jdbc,AiConversationStore conversations) {
    this.store=store; this.projects=projects; this.jdbc=jdbc; this.conversations=conversations;
  }
  public UmlProposalStore.View reject(UUID projectId,UUID diagramId,UUID id,String email) {
    var member=projects.requireCanEdit(projectId,email); canvas.lock(diagramId); var row=store.authorized(projectId,diagramId,id,email,true);
    String state=(String)row.get("status");
    if(state.equals("RECHAZADA")) return store.get(projectId,diagramId,id,email);
    if(state.equals("APLICADA")) throw new ConflictException("La propuesta ya fue aplicada.");
    jdbc.update("UPDATE ai_uml_proposal SET status='RECHAZADA',decision_by=?,decided_at=now(),result_code='REJECTED' WHERE id=?",member.getUser().getId(),id);
    store.audit(id,member.getUser().getId(),"REJECTED","REJECTED");
    canvas.decided(diagramId,id,(String)row.get("scope"));
    if(row.get("conversation_id")!=null) conversations.publishProposalDecision(projectId,(UUID)row.get("conversation_id"),email,"rechazó una propuesta UML",null);
    return store.get(projectId,diagramId,id,email);
  }
  public void failure(UUID projectId,UUID diagramId,UUID id,String email,boolean stale) {
    var member=projects.requireCanEdit(projectId,email); var row=store.authorized(projectId,diagramId,id,email,true);
    if(!row.get("status").equals("PENDIENTE")) return;
    String state=stale?"CONFLICTO":"FALLIDA",code=stale?"REVISION_CONFLICT":"APPLICATION_FAILED";
    jdbc.update("UPDATE ai_uml_proposal SET status=?,decision_by=?,decided_at=now(),result_code=? WHERE id=?",state,member.getUser().getId(),code,id);
    store.audit(id,member.getUser().getId(),state,code);
    if(stale) { if(row.get("scope").equals("GROUP")) canvas.changed(diagramId); }
    else canvas.decided(diagramId,id,(String)row.get("scope"));
  }
}
