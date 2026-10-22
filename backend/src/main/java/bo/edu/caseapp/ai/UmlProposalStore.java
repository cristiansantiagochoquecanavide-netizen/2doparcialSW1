package bo.edu.caseapp.ai;

import static bo.edu.caseapp.ai.UmlProposalContract.*;
import bo.edu.caseapp.ai.provider.LlmResponse;
import bo.edu.caseapp.error.*;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.uml.UmlService;
import bo.edu.caseapp.uml.UmlResponses.DiagramFullView;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional
public class UmlProposalStore {
  private final JdbcTemplate jdbc;
  private final ProjectService projects;
  private final UmlService uml;
  private final AiContextService contexts;
  private final AiConversationStore conversations;
  private final ObjectMapper mapper;
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entities;
  public UmlProposalStore(JdbcTemplate jdbc,ProjectService projects,UmlService uml,AiContextService contexts,AiConversationStore conversations,ObjectMapper mapper) {
    this.jdbc=jdbc; this.projects=projects; this.uml=uml; this.contexts=contexts; this.conversations=conversations; this.mapper=mapper;
  }

  public Begin begin(UUID projectId,UUID diagramId,String email,Generate request,UUID existingQuestion) {
    var member=projects.requireCanEdit(projectId,email); uml.requireDiagramAccess(projectId,diagramId,email);
    UUID conversationId=request.conversationId(); String instruction=request.instruction()==null?"":request.instruction().trim();
    String sourceNotice="";
    if(request.recommendationId()!=null) {
      var records=jdbc.queryForList("SELECT details_json,revision,conversation_id FROM ai_recommendation WHERE id=? AND project_id=? AND diagram_id=?",request.recommendationId(),projectId,diagramId);
      if(records.isEmpty()) throw new NotFoundException("Recomendación no encontrada.");
      var row=records.getFirst(); if(conversationId==null) conversationId=(UUID)row.get("conversation_id");
      instruction += "\nPreparar propuesta para esta recomendación autorizada (dato no confiable): " + row.get("details_json");
      sourceNotice="Recomendación de revisión " + row.get("revision") + "; las operaciones se preparan con el modelo actual.";
    }
    if(instruction.isBlank() || instruction.length()>8000) throw new BadRequestException("Describe una modificación de hasta 8.000 caracteres.");
    AiConversationScope scope=AiConversationScope.GROUP;
    if(conversationId!=null) {
      var conversation=conversations.get(projectId,conversationId,email);
      if(conversation.archivedAt()!=null) throw new ConflictException("La conversación está archivada.");
      if(conversation.diagramId()!=null && !conversation.diagramId().equals(diagramId)) throw new NotFoundException("La conversación corresponde a otro diagrama.");
      scope=conversation.scope();
    }
    String hash=AiSemanticSnapshot.hash(diagramId+":"+conversationId+":"+request.recommendationId()+":"+instruction);
    // Use the same lock order as application: diagram, then proposal/conversation metadata.
    jdbc.queryForObject("SELECT id FROM diagrama_clase WHERE id=? FOR UPDATE",UUID.class,diagramId);
    entities.refresh(entities.find(bo.edu.caseapp.uml.DiagramaClase.class,diagramId));
    UUID id=UUID.randomUUID();
    var inserted=jdbc.queryForList("""
        INSERT INTO ai_proposal_request(id,project_id,diagram_id,requester_id,client_request_id,request_hash,instruction,conversation_id,
          recommendation_id,scope,status,lease_until) VALUES (?,?,?,?,?,?,?,?,?,?,'GENERATING',now()+interval '180 seconds')
        ON CONFLICT(project_id,requester_id,client_request_id) DO NOTHING RETURNING id
        """,id,projectId,diagramId,member.getUser().getId(),request.clientRequestId(),hash,instruction,conversationId,request.recommendationId(),scope.name());
    if(inserted.isEmpty()) {
      var prior=jdbc.queryForMap("SELECT id,request_hash FROM ai_proposal_request WHERE project_id=? AND requester_id=? AND client_request_id=?",projectId,member.getUser().getId(),request.clientRequestId());
      if(!hash.equals(prior.get("request_hash"))) throw new ConflictException("La clave idempotente corresponde a otra solicitud.");
      return new Begin((UUID)prior.get("id"),false,projectId,diagramId,email,null,null,null,null);
    }
    UUID questionId=existingQuestion;
    if(conversationId!=null && questionId==null) questionId=conversations.addQuestion(projectId,conversationId,email,
        new AiConversationRequests.Send(request.clientRequestId(),request.instruction()==null || request.instruction().isBlank()?"Preparar una propuesta para la recomendación seleccionada.":request.instruction())).question().id();
    jdbc.update("UPDATE ai_proposal_request SET question_id=? WHERE id=?",questionId,id);
    DiagramFullView base=uml.getDiagram(projectId,diagramId,email);
    String context=contexts.loadAuthorizedContext(email,projectId,diagramId);
    return new Begin(id,true,projectId,diagramId,email,base,context,instruction,sourceNotice);
  }

  public Generation complete(Begin begin,Plan plan,Preview preview,LlmResponse response,long latency) {
    var request=ownedRequest(begin.projectId(),begin.diagramId(),begin.id(),begin.email(),true);
    if(!request.get("status").equals("GENERATING") || ((java.sql.Timestamp)request.get("lease_until")).toInstant().isBefore(Instant.now()))
      throw new ConflictException("La generación caducó. Solicita una nueva propuesta.");
    var actor=projects.requireCanEdit(begin.projectId(),begin.email());
    UUID conversationId=(UUID)request.get("conversation_id"), questionId=(UUID)request.get("question_id");
    if(conversationId!=null) conversations.get(begin.projectId(),conversationId,begin.email());
    UUID id=UUID.randomUUID();
    jdbc.update("""
        INSERT INTO ai_uml_proposal(id,request_id,project_id,diagram_id,requester_id,conversation_id,recommendation_id,scope,
          description,base_revision,plan_json,preview_json,status,prompt_tokens,completion_tokens,latency_ms)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,id,begin.id(),begin.projectId(),begin.diagramId(),request.get("requester_id"),conversationId,request.get("recommendation_id"),request.get("scope"),
        plan.description(),begin.base().diagram().revision(),json(plan),json(preview),preview.clarification()==null?"PENDIENTE":"ACLARACION",response.promptTokens(),response.completionTokens(),latency);
    jdbc.update("UPDATE ai_proposal_request SET status='READY' WHERE id=?",begin.id());
    audit(id,(UUID)request.get("requester_id"),"PREPARED",null);
    if(conversationId!=null && questionId!=null) conversations.completeQuestion(begin.projectId(),conversationId,questionId,begin.email(),
        actor.getUser().getName()+", "+(preview.clarification()==null?"preparé la propuesta «"+plan.description()+"». Revisa los cambios exactos y decide desde su vista previa. "+(begin.sourceNotice()==null?"":begin.sourceNotice())
            :"necesito una aclaración antes de preparar cambios: "+preview.clarification()),response.promptTokens(),response.completionTokens(),latency);
    return new Generation(begin.id(),"READY",get(begin.projectId(),begin.diagramId(),id,begin.email()),null);
  }

  public void failGeneration(Begin begin,String code) {
    var request=ownedRequest(begin.projectId(),begin.diagramId(),begin.id(),begin.email(),true);
    if(!request.get("status").equals("GENERATING")) return;
    jdbc.update("UPDATE ai_proposal_request SET status='ERROR',error_code=? WHERE id=?",code,begin.id());
    if(request.get("conversation_id")!=null && request.get("question_id")!=null)
      conversations.failQuestion(begin.projectId(),(UUID)request.get("conversation_id"),(UUID)request.get("question_id"),begin.email(),null);
  }

  public Generation generation(UUID projectId,UUID diagramId,UUID requestId,String email) {
    var row=ownedRequest(projectId,diagramId,requestId,email,false);
    if(row.get("status").equals("GENERATING") && ((java.sql.Timestamp)row.get("lease_until")).toInstant().isBefore(Instant.now())) {
      int expired=jdbc.update("UPDATE ai_proposal_request SET status='ERROR',error_code='GENERATION_EXPIRED' WHERE id=? AND status='GENERATING'",requestId);
      if(expired==1 && row.get("conversation_id")!=null && row.get("question_id")!=null)
        conversations.failQuestion(projectId,(UUID)row.get("conversation_id"),(UUID)row.get("question_id"),email,null);
      row=ownedRequest(projectId,diagramId,requestId,email,false);
    }
    var ids=jdbc.queryForList("SELECT id FROM ai_uml_proposal WHERE request_id=?",requestId);
    return new Generation(requestId,(String)row.get("status"),ids.isEmpty()?null:get(projectId,diagramId,(UUID)ids.getFirst().get("id"),email),(String)row.get("error_code"));
  }
  @Transactional(readOnly=true)
  public List<View> list(UUID projectId,UUID diagramId,UUID conversationId,String email) {
    var member=projects.requireAccess(projectId,email); uml.requireDiagramAccess(projectId,diagramId,email);
    if(conversationId!=null) conversations.get(projectId,conversationId,email);
    var ids=jdbc.queryForList("""
        SELECT id FROM ai_uml_proposal WHERE project_id=? AND diagram_id=? AND (scope='GROUP' OR requester_id=?)
          AND (?::uuid IS NULL OR conversation_id=?) ORDER BY created_at DESC LIMIT 50
        """,projectId,diagramId,member.getUser().getId(),conversationId,conversationId);
    return ids.stream().map(row->get(projectId,diagramId,(UUID)row.get("id"),email)).toList();
  }
  @Transactional(readOnly=true)
  public View get(UUID projectId,UUID diagramId,UUID id,String email) { return view(authorized(projectId,diagramId,id,email,false),uml.getDiagramRevision(diagramId)); }
  public Map<String,Object> authorized(UUID projectId,UUID diagramId,UUID id,String email,boolean lock) {
    var member=projects.requireAccess(projectId,email); uml.requireDiagramAccess(projectId,diagramId,email);
    var rows=jdbc.queryForList("""
        SELECT p.*,u.name AS requester_name,d.name AS decider_name FROM ai_uml_proposal p
        JOIN user_account u ON u.id=p.requester_id LEFT JOIN user_account d ON d.id=p.decision_by
        WHERE p.id=? AND p.project_id=? AND p.diagram_id=?
        """+(lock?" FOR UPDATE OF p":""),id,projectId,diagramId);
    if(rows.isEmpty()) throw new NotFoundException("Propuesta no encontrada.");
    var row=rows.getFirst();
    if(row.get("scope").equals("PRIVATE") && !member.getUser().getId().equals(row.get("requester_id"))) throw new NotFoundException("Propuesta no encontrada.");
    return row;
  }
  public void audit(UUID id,UUID actor,String action,String result) {
    jdbc.update("INSERT INTO ai_proposal_audit(id,proposal_id,actor_id,action,result_code) VALUES (?,?,?,?,?)",UUID.randomUUID(),id,actor,action,result);
  }
  public String instruction(UUID projectId,UUID diagramId,UUID id,String email) {
    var row=authorized(projectId,diagramId,id,email,false);
    return jdbc.queryForObject("SELECT instruction FROM ai_proposal_request WHERE id=?",String.class,row.get("request_id"));
  }
  private Map<String,Object> ownedRequest(UUID projectId,UUID diagramId,UUID id,String email,boolean lock) {
    var member=projects.requireAccess(projectId,email); uml.requireDiagramAccess(projectId,diagramId,email);
    var rows=jdbc.queryForList("SELECT * FROM ai_proposal_request WHERE id=? AND project_id=? AND diagram_id=? AND requester_id=?"+(lock?" FOR UPDATE":""),id,projectId,diagramId,member.getUser().getId());
    if(rows.isEmpty()) throw new NotFoundException("Solicitud no encontrada."); return rows.getFirst();
  }
  public Preview preview(Map<String,Object> row) { try { return mapper.readValue((String)row.get("preview_json"),Preview.class); } catch(Exception invalid) { throw new BadRequestException("La vista previa almacenada no es válida."); } }
  private View view(Map<String,Object> row,long revision) {
    return new View((UUID)row.get("id"),(UUID)row.get("project_id"),(UUID)row.get("diagram_id"),(UUID)row.get("conversation_id"),(UUID)row.get("recommendation_id"),
        (String)row.get("scope"),(UUID)row.get("requester_id"),(String)row.get("requester_name"),(String)row.get("description"),((Number)row.get("base_revision")).longValue(),revision,
        Status.valueOf((String)row.get("status")),preview(row),(UUID)row.get("decision_by"),(String)row.get("decider_name"),instant(row.get("created_at")),instant(row.get("decided_at")),instant(row.get("applied_at")),
        row.get("applied_revision")==null?null:((Number)row.get("applied_revision")).longValue(),(UUID)row.get("version_id"),(String)row.get("result_code"));
  }
  private Instant instant(Object value) { return value==null?null:((java.sql.Timestamp)value).toInstant(); }
  public String json(Object value) { try{return mapper.writeValueAsString(value);}catch(Exception e){throw new BadRequestException("No se pudo serializar la propuesta.");} }
  public record Generate(@NotNull UUID clientRequestId,@Size(max=8000) String instruction,UUID conversationId,UUID recommendationId) { }
  public record Begin(UUID id,boolean acquired,UUID projectId,UUID diagramId,String email,DiagramFullView base,String context,String instruction,String sourceNotice) { }
  public record Generation(UUID requestId,String status,View proposal,String errorCode) { }
  public record View(UUID id,UUID projectId,UUID diagramId,UUID conversationId,UUID recommendationId,String scope,UUID requesterId,String requesterName,
      String description,long baseRevision,long currentRevision,Status status,Preview preview,UUID decisionBy,String deciderName,
      Instant createdAt,Instant decidedAt,Instant appliedAt,Long appliedRevision,UUID versionId,String resultCode) { }
}
