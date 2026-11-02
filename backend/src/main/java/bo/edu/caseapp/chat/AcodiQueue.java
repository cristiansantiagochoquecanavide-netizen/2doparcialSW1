package bo.edu.caseapp.chat;

import bo.edu.caseapp.ai.AiContextService;
import bo.edu.caseapp.error.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Enqueued in the same transaction as the accepted human message; invisible until commit. */
@Service @Transactional
public class AcodiQueue {
  private final JdbcTemplate jdbc; private final AiContextService contexts;
  @org.springframework.beans.factory.annotation.Value("${app.ai.acodi.max-attempts:3}") private int maxAttempts=3;
  public AcodiQueue(JdbcTemplate jdbc,AiContextService contexts) {this.jdbc=jdbc;this.contexts=contexts;}
  public UUID diagram(UUID projectId,String email,UUID requested) {
    if(requested!=null) { contexts.authorize(email,projectId,requested);return requested; }
    var ids=jdbc.queryForList("SELECT id FROM diagrama_clase WHERE project_id=? ORDER BY fecha_creacion LIMIT 2",UUID.class,projectId);
    return ids.size()==1?ids.getFirst():null;
  }
  public void enqueue(UUID projectId,UUID channelId,UUID messageId,UUID diagramId) {
    enqueue(projectId,channelId,messageId,diagramId,null);
  }
  public void enqueue(UUID projectId,UUID channelId,UUID messageId,UUID diagramId,List<UUID> comparisonVersionIds) {
    UUID before=comparisonVersionIds!=null && comparisonVersionIds.size()==2?comparisonVersionIds.get(0):null;
    UUID after=comparisonVersionIds!=null && comparisonVersionIds.size()==2?comparisonVersionIds.get(1):null;
    jdbc.update("INSERT INTO acodi_channel_job(message_id,project_id,channel_id,diagram_id,comparison_before_version_id,comparison_after_version_id) VALUES (?,?,?,?,?,?)",messageId,projectId,channelId,diagramId,before,after);
  }
  @Transactional(readOnly=true)
  public ChatResponses.Invocation view(UUID messageId) {
    var rows=jdbc.queryForList("SELECT * FROM acodi_channel_job WHERE message_id=?",messageId);
    if(rows.isEmpty()) return null;var r=rows.getFirst();
    return new ChatResponses.Invocation((String)r.get("status"),(UUID)r.get("diagram_id"),((Number)r.get("attempts")).intValue(),
        (String)r.get("error_code"),instant(r.get("retry_after")),(UUID)r.get("response_message_id"),maxAttempts);
  }
  private Instant instant(Object value) {return value==null?null:((java.sql.Timestamp)value).toInstant();}
}
