package bo.edu.caseapp.ai;

import static bo.edu.caseapp.ai.UmlProposalContract.*;
import bo.edu.caseapp.error.*;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.versioning.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UmlProposalExecutor {
  private final UmlProposalStore store;
  private final UmlProposalEngine engine;
  private final UmlService uml;
  private final ProjectService projects;
  private final JdbcTemplate jdbc;
  private final AiConversationStore conversations;
  @PersistenceContext private EntityManager entities;
  @org.springframework.beans.factory.annotation.Autowired private UmlCanvasProposalState canvas;
  public UmlProposalExecutor(UmlProposalStore store,UmlProposalEngine engine,UmlService uml,ProjectService projects,
      JdbcTemplate jdbc,AiConversationStore conversations) {
    this.store=store; this.engine=engine; this.uml=uml; this.projects=projects; this.jdbc=jdbc; this.conversations=conversations;
  }
  @Transactional
  public UmlProposalStore.View apply(UUID projectId,UUID diagramId,UUID id,String email,Apply request) {
    var member=projects.requireCanEdit(projectId,email);
    store.authorized(projectId,diagramId,id,email,false);
    jdbc.queryForObject("SELECT id FROM diagrama_clase WHERE id=? FOR UPDATE",UUID.class,diagramId);
    var diagram=entities.find(DiagramaClase.class,diagramId); entities.refresh(diagram);
    var row=store.authorized(projectId,diagramId,id,email,true);
    if(row.get("status").equals("APLICADA")) return store.get(projectId,diagramId,id,email);
    if(!row.get("status").equals("PENDIENTE")) throw new ConflictException("Esta propuesta ya no está pendiente. Revísala o prepara una nueva.");
    uml.requireNotLocked(diagram,email);
    long base=((Number)row.get("base_revision")).longValue();
    if(request.expectedRevision()!=base || uml.getDiagramRevision(diagramId)!=base) throw new StaleProposal();
    var preview=store.preview(row);
    if(engine.destructive(preview.changes()) && !request.confirmDestructive()) throw new ConfirmationRequired();
    var before=uml.getDiagram(projectId,diagramId,email); engine.verify(before,preview);
    uml.mutateBatch(projectId,diagramId,email,base,"ACODI","PROPOSAL_APPLIED",()-> {
      for(Change change:preview.changes()) if(change.entity()==EntityKind.RELATION && change.kind()==ChangeKind.DELETE) uml.deleteRelation(projectId,diagramId,email,change.id(),base);
      for(Change change:preview.changes()) if(change.entity()==EntityKind.CLASS && change.kind()!=ChangeKind.DELETE) writeClass(projectId,diagramId,email,change,base);
      for(Change change:preview.changes()) if(change.entity()==EntityKind.RELATION && change.kind()!=ChangeKind.DELETE) writeRelation(projectId,diagramId,email,change,base);
      for(Change change:preview.changes()) if(change.entity()==EntityKind.CLASS && change.kind()==ChangeKind.DELETE) uml.deleteElement(projectId,diagramId,email,change.id(),base);
      var after=uml.validateDiagram(projectId,diagramId,email); var baseline=engine.validation(before);
      Set<String> previous=new HashSet<>(); baseline.issues().stream().filter(issue->issue.severity().equals("ERROR")).forEach(issue->previous.add(issue.elementoId()+":"+issue.mensaje()));
      if(after.issues().stream().anyMatch(issue->issue.severity().equals("ERROR")&&!previous.contains(issue.elementoId()+":"+issue.mensaje()))) throw new BadRequestException("La aplicación introduce errores UML.");
      return null;
    });
    long revision=uml.getDiagramRevision(diagramId);
    jdbc.update("""
        UPDATE ai_uml_proposal SET status='APLICADA',decision_by=?,decided_at=now(),applied_at=now(),applied_revision=?,
          application_key=?,version_id=NULL,result_code='APPLIED' WHERE id=?
        """,member.getUser().getId(),revision,request.clientRequestId(),id);
    store.audit(id,member.getUser().getId(),"APPLIED","APPLIED");
    canvas.decided(diagramId,id,(String)row.get("scope"));
    if(row.get("conversation_id")!=null) conversations.publishProposalDecision(projectId,(UUID)row.get("conversation_id"),email,"aplicó una propuesta UML",revision);
    return store.get(projectId,diagramId,id,email);
  }
  private void writeClass(UUID projectId,UUID diagramId,String email,Change change,long revision) {
    JsonNode node=change.after(); var request=new UmlRequests.SaveElement(change.id(),TipoElementoUML.valueOf(node.path("type").asText()),
        node.path("name").asText(),node.path("x").asDouble(),node.path("y").asDouble(),node.path("properties").toString(),revision);
    if(change.kind()==ChangeKind.CREATE) uml.createElement(projectId,diagramId,email,request); else uml.updateElement(projectId,diagramId,email,change.id(),request);
  }
  private void writeRelation(UUID projectId,UUID diagramId,String email,Change change,long revision) {
    JsonNode node=change.after(); var request=new UmlRequests.SaveRelation(change.id(),UUID.fromString(node.path("source").asText()),UUID.fromString(node.path("destination").asText()),
        node.path("name").asText(),TipoRelacionUML.valueOf(node.path("type").asText()),node.path("sourceMultiplicity").asText(),node.path("targetMultiplicity").asText(),revision);
    if(change.kind()==ChangeKind.CREATE) uml.createRelation(projectId,diagramId,email,request); else uml.updateRelation(projectId,diagramId,email,change.id(),request);
  }
  public record Apply(@jakarta.validation.constraints.NotNull UUID clientRequestId,@jakarta.validation.constraints.Min(1) long expectedRevision,boolean confirmDestructive) { }
  public static class StaleProposal extends ConflictException { public StaleProposal() { super("La revisión del diagrama cambió. Regenera la propuesta antes de aplicarla."); } }
  public static class ConfirmationRequired extends BadRequestException { public ConfirmationRequired() { super("Confirma explícitamente las eliminaciones de la vista previa."); } }
}
