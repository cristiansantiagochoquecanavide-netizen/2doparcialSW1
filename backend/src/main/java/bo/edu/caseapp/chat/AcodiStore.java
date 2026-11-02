package bo.edu.caseapp.chat;

import bo.edu.caseapp.ai.*;
import bo.edu.caseapp.ai.provider.LlmResponse;
import bo.edu.caseapp.error.*;
import bo.edu.caseapp.project.ProjectService;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional
public class AcodiStore {
  private final JdbcTemplate jdbc;private final ProjectService projects;private final AcodiQueue queue;
  private final AiContextService contexts;private final AiAnalysisStore budget;private final ProjectChatService chat;
  @Value("${app.ai.acodi.max-attempts:3}") private int maxAttempts=3;
  @Value("${app.ai.acodi.max-calls:30}") private int maxCalls=30;
  @Value("${app.groq.timeout-seconds:30}") private int timeout=30;
  public AcodiStore(JdbcTemplate jdbc,ProjectService projects,AcodiQueue queue,AiContextService contexts,AiAnalysisStore budget,ProjectChatService chat) {
    this.jdbc=jdbc;this.projects=projects;this.queue=queue;this.contexts=contexts;this.budget=budget;this.chat=chat;
  }
  public Optional<Work> claim() {
    var expired=jdbc.queryForList("UPDATE acodi_channel_job SET status='FALLIDO',error_code='INTERRUPTED',retry_after=now(),updated_at=now() WHERE status='PROCESANDO' AND lease_until<now() RETURNING message_id,project_id,run_token");
    for(var row:expired) {
      budget.settleExplicit((UUID)row.get("project_id"),(UUID)row.get("run_token"),null,0,"ABANDONED");
      chat.publishUpdated((UUID)row.get("project_id"),(UUID)row.get("message_id"));
    }
    var ids=jdbc.queryForList("SELECT message_id FROM acodi_channel_job WHERE status='PENDIENTE' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",UUID.class);
    if(ids.isEmpty()) return Optional.empty();UUID messageId=ids.getFirst(),token=UUID.randomUUID();
    jdbc.update("UPDATE acodi_channel_job SET status='PROCESANDO',run_token=?,attempts=attempts+1,lease_until=now()+(?*interval '1 second'),updated_at=now() WHERE message_id=?",token,Math.max(120,timeout+60),messageId);
    var row=jdbc.queryForMap("SELECT j.*,m.content,u.email,u.name FROM acodi_channel_job j JOIN project_chat_message m ON m.id=j.message_id JOIN user_account u ON u.id=m.sender_user_id WHERE j.message_id=?",messageId);
    var comparisonIds=new ArrayList<UUID>();
    if(row.get("comparison_before_version_id")!=null)comparisonIds.add((UUID)row.get("comparison_before_version_id"));
    if(row.get("comparison_after_version_id")!=null)comparisonIds.add((UUID)row.get("comparison_after_version_id"));
    var work=new Work(messageId,(UUID)row.get("project_id"),(UUID)row.get("channel_id"),(UUID)row.get("diagram_id"),token,(String)row.get("email"),(String)row.get("name"),(String)row.get("content"),List.copyOf(comparisonIds));
    chat.publishUpdated(work.projectId(),messageId);return Optional.of(work);
  }
  public String prepare(Work work) {
    owned(work);projects.requireAccess(work.projectId(),work.email());
    if(work.diagramId()==null) throw new BadRequestException("CONTEXT_REQUIRED");
    return contexts.loadAuthorizedContext(work.email(),work.projectId(),work.diagramId());
  }
  public boolean reserve(Work work,long tokens) {
    owned(work);contexts.authorize(work.email(),work.projectId(),work.diagramId());
    Instant pause=budget.reserveExplicit(work.projectId(),work.diagramId(),work.email(),work.token(),tokens,maxCalls);
    if(pause==null) return true;
    jdbc.update("UPDATE acodi_channel_job SET status='FALLIDO',error_code='LIMIT_REACHED',retry_after=?,updated_at=now() WHERE message_id=?",java.sql.Timestamp.from(pause),work.messageId());
    chat.publishUpdated(work.projectId(),work.messageId());return false;
  }
  public void complete(Work work,String answer,LlmResponse response,long latency) {
    owned(work);projects.requireAccess(work.projectId(),work.email());if(work.diagramId()!=null)contexts.authorize(work.email(),work.projectId(),work.diagramId());
    if(answer==null || answer.isBlank() || answer.length()>3900 || answer.indexOf('\0')>=0) throw new BadRequestException("INVALID_RESPONSE");
    if(!answer.regionMatches(true,0,work.name(),0,work.name().length())) answer=work.name()+", "+answer;
    if(answer.length()>4000) answer=answer.substring(0,4000);
    var message=chat.publishAgent(work.projectId(),work.channelId(),work.email(),answer,work.messageId(),null);
    budget.settleExplicit(work.projectId(),work.token(),response,latency,"COMPLETED");
    jdbc.update("UPDATE acodi_channel_job SET status='COMPLETADO',response_message_id=?,error_code=null,lease_until=null,prompt_tokens=?,completion_tokens=?,latency_ms=?,updated_at=now() WHERE message_id=?",
        message.id(),response.promptTokens(),response.completionTokens(),latency,work.messageId());
    chat.publishUpdated(work.projectId(),work.messageId());
  }
  public void fail(Work work,String code,LlmResponse response,long latency) {
    var rows=jdbc.queryForList("SELECT message_id FROM acodi_channel_job WHERE message_id=? AND run_token=? AND status='PROCESANDO' FOR UPDATE",work.messageId(),work.token());
    if(rows.isEmpty()) return;
    budget.settleExplicit(work.projectId(),work.token(),response,latency,"ERROR");
    int wait=response!=null && Integer.valueOf(429).equals(response.httpStatusCode())?120:10;
    jdbc.update("UPDATE acodi_channel_job SET status='FALLIDO',error_code=?,retry_after=now()+(?*interval '1 second'),latency_ms=?,lease_until=null,updated_at=now() WHERE message_id=?",code,wait,latency,work.messageId());
    chat.publishUpdated(work.projectId(),work.messageId());
  }
  public ChatResponses.Invocation retry(UUID projectId,UUID channelId,UUID messageId,String email,Retry request) {
    var member=projects.requireAccess(projectId,email);
    var rows=jdbc.queryForList("SELECT j.*,m.sender_user_id FROM acodi_channel_job j JOIN project_chat_message m ON m.id=j.message_id WHERE j.message_id=? AND j.project_id=? AND j.channel_id=? FOR UPDATE OF j",messageId,projectId,channelId);
    if(rows.isEmpty() || !rows.getFirst().get("sender_user_id").equals(member.getUser().getId())) throw new NotFoundException("Consulta ACODI no encontrada.");
    var row=rows.getFirst();
    if(request.clientRequestId().equals(row.get("retry_key")) && request.diagramId()!=null && !request.diagramId().equals(row.get("diagram_id"))) throw new ConflictException("La clave de reintento corresponde a otro diagrama.");
    if(request.clientRequestId().equals(row.get("retry_key")) || row.get("status").equals("COMPLETADO")) return queue.view(messageId);
    if(!row.get("status").equals("FALLIDO") || ((Number)row.get("attempts")).intValue()>=maxAttempts) throw new ConflictException("Esta consulta no admite otro reintento. Puedes formular una nueva consulta.");
    var due=(java.sql.Timestamp)row.get("retry_after");if(due!=null && due.toInstant().isAfter(Instant.now())) throw new ConflictException("Espera el plazo indicado antes de reintentar.");
    UUID diagramId=request.diagramId()==null?(UUID)row.get("diagram_id"):queue.diagram(projectId,email,request.diagramId());
    jdbc.update("UPDATE acodi_channel_job SET status='PENDIENTE',retry_key=?,diagram_id=?,error_code=null,run_token=null,lease_until=null,updated_at=now() WHERE message_id=?",request.clientRequestId(),diagramId,messageId);
    chat.publishUpdated(projectId,messageId);return queue.view(messageId);
  }
  private void owned(Work work) {
    var valid=jdbc.queryForList("SELECT message_id FROM acodi_channel_job WHERE message_id=? AND run_token=? AND status='PROCESANDO' AND lease_until>now() FOR UPDATE",work.messageId(),work.token());
    if(valid.isEmpty()) throw new ConflictException("La solicitud ACODI ya no está activa.");
  }
  public record Work(UUID messageId,UUID projectId,UUID channelId,UUID diagramId,UUID token,String email,String name,String content,List<UUID> comparisonVersionIds) { }
  public record Retry(@jakarta.validation.constraints.NotNull UUID clientRequestId,UUID diagramId) { }
}
