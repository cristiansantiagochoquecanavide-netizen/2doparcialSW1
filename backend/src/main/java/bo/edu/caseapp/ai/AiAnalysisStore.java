package bo.edu.caseapp.ai;

import bo.edu.caseapp.error.*;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.ai.provider.LlmResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short database transactions only. The external request belongs to AiAnalysisWorker. */
@Service @Transactional
public class AiAnalysisStore {
  private final JdbcTemplate jdbc;
  private final AiAnalysisQueue queue;
  private final AiSemanticSnapshot snapshots;
  private final AiContextService contexts;
  private final ProjectService projects;
  private final AiConversationStore conversations;
  private final AiMemoryService memory;
  private final ObjectMapper mapper;
  private final AiAnalysisProperties config;
  private final int leaseSeconds;

  public AiAnalysisStore(JdbcTemplate jdbc, AiAnalysisQueue queue, AiSemanticSnapshot snapshots,
      AiContextService contexts, ProjectService projects, AiConversationStore conversations,
      AiMemoryService memory, ObjectMapper mapper, AiAnalysisProperties config,
      @Value("${app.groq.timeout-seconds:30}") int providerTimeout) {
    this.jdbc=jdbc; this.queue=queue; this.snapshots=snapshots; this.contexts=contexts;
    this.projects=projects; this.conversations=conversations; this.memory=memory; this.mapper=mapper;
    this.config=config; this.leaseSeconds=Math.max(config.getLeaseSeconds(), providerTimeout + 60);
  }

  public Optional<Job> claim() {
    jdbc.update("""
        UPDATE ai_analysis_run r SET outcome = 'ABANDONED', completed_at = now()
        WHERE kind='PROACTIVE' AND outcome = 'RUNNING' AND NOT EXISTS
          (SELECT 1 FROM ai_analysis_job j WHERE j.run_token = r.id AND j.lease_until > now())
        """);
    jdbc.update("""
        UPDATE ai_analysis_job SET processed_sequence = sequence, run_token = NULL, lease_until = NULL,
          force_review = FALSE, last_status = 'LEASE_EXHAUSTED', updated_at = now()
        WHERE attempts >= ? AND lease_until < now()
        """, config.getMaxAttempts());
    UUID token = UUID.randomUUID();
    var jobs = jdbc.query("""
        WITH candidate AS (
          SELECT j.diagram_id FROM ai_analysis_job j JOIN ai_analysis_policy p ON p.project_id = j.project_id
          WHERE j.sequence > j.processed_sequence AND j.due_at <= now()
            AND (j.lease_until IS NULL OR j.lease_until < now())
            AND (p.automatic_enabled OR j.force_review) AND (p.paused_until IS NULL OR p.paused_until <= now())
          ORDER BY j.due_at LIMIT 1 FOR UPDATE OF j SKIP LOCKED
        ) UPDATE ai_analysis_job j SET run_token = ?, lease_until = now() + (? * interval '1 second'),
          attempts = attempts + 1, last_status = 'ANALYZING', updated_at = now()
        FROM candidate c WHERE j.diagram_id = c.diagram_id RETURNING j.*
        """, this::job, token, leaseSeconds);
    return jobs.stream().findFirst();
  }

  public Prepared prepare(Job job) {
    lockDiagram(job.diagramId());
    projects.requireCanEdit(job.projectId(), job.email());
    if (!ownsLease(job)) throw new ConflictException("El trabajo de análisis fue reasignado.");
    var snapshot = snapshots.read(job.diagramId());
    if (!snapshot.projectId().equals(job.projectId())) throw new NotFoundException("Diagrama no encontrado.");
    String context = contexts.loadAuthorizedContext(job.email(), job.projectId(), job.diagramId());
    String changes = changes(job.baselineModel(), snapshot.model());
    var previous = jdbc.queryForList("""
        SELECT details_json, status FROM ai_recommendation WHERE diagram_id = ?
        ORDER BY created_at DESC LIMIT 5
        """, job.diagramId());
    StringBuilder summaries = new StringBuilder();
    for (var record : previous) {
      try {
        var result = mapper.readTree((String) record.get("details_json"));
        summaries.append(record.get("status")).append(':').append(result.path("type").asText()).append(' ')
            .append(result.path("title").asText()).append(' ').append(result.path("elementIds")).append('\n');
      } catch (Exception ignored) { /* Old malformed telemetry is never sent to the model. */ }
    }
    return new Prepared(snapshot, context, changes, summaries.toString());
  }

  /** The policy row serializes reservations across diagrams, instances and running requests. */
  public boolean reserve(Job job, long tokens) {
    var policy = jdbc.queryForMap("SELECT * FROM ai_analysis_policy WHERE project_id = ? FOR UPDATE", job.projectId());
    if (!ownsLease(job)) return false;
    int period = ((Number)policy.get("period_seconds")).intValue();
    var usage = usage(job.projectId(), period);
    var shared=sharedUsage(job.projectId(),period);
    boolean explicitWaiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM acodi_channel_job WHERE project_id=? AND status IN ('PENDIENTE','PROCESANDO'))",Boolean.class,job.projectId()));
    long allowance = ((Number)policy.get("token_budget")).longValue() - ((Number)policy.get("explicit_reserve")).longValue();
    var paused = (java.sql.Timestamp)policy.get("paused_until");
    boolean disabled = !((Boolean)policy.get("automatic_enabled")) && !job.force();
    if (disabled || explicitWaiting || ((Number)shared.get("tokens")).longValue()+tokens>((Number)policy.get("token_budget")).longValue() || (paused != null && paused.toInstant().isAfter(Instant.now()))
        || ((Number)usage.get("calls")).intValue() >= ((Number)policy.get("max_calls")).intValue()
        || ((Number)usage.get("tokens")).longValue() + tokens > allowance) {
      jdbc.update("""
          UPDATE ai_analysis_job SET run_token = NULL, lease_until = NULL,
            attempts = greatest(attempts - 1, 0), due_at = now() + (? * interval '1 second'),
            last_status = ?, updated_at = now() WHERE diagram_id = ? AND run_token = ?
          """, config.getRetrySeconds(), disabled ? "DISABLED" : "BUDGET_PAUSED", job.diagramId(), job.token());
      return false;
    }
    jdbc.update("""
        INSERT INTO ai_analysis_run(id, project_id, diagram_id, reserved_tokens, charged_tokens)
        VALUES (?, ?, ?, ?, ?)
        """, job.token(), job.projectId(), job.diagramId(), tokens, tokens);
    return true;
  }

  public void skip(Job job, AiSemanticSnapshot.Snapshot snapshot, String reason) {
    finishJob(job, snapshot, reason, true);
  }

  public void complete(Job job, Prepared prepared, AiAnalysisResult.Result result, LlmResponse response, long latency) {
    lockDiagram(job.diagramId());
    // Lock order is diagram -> job, the same order used by UML mutation scheduling.
    var currentJobs = jdbc.query("SELECT * FROM ai_analysis_job WHERE diagram_id = ? FOR UPDATE", this::job, job.diagramId());
    settle(job, response, latency, "COMPLETED");
    if (currentJobs.isEmpty() || !ownsLease(job)) return;
    var current = snapshots.read(job.diagramId());
    if (!current.hash().equals(prepared.snapshot().hash())) {
      jdbc.update("UPDATE ai_analysis_run SET outcome = 'STALE_DISCARDED' WHERE id = ?", job.token());
      finishJob(job, null, "STALE_DISCARDED", false); return;
    }
    projects.requireCanEdit(job.projectId(), job.email());
    Boolean enabled = jdbc.queryForObject("SELECT automatic_enabled FROM ai_analysis_policy WHERE project_id = ?", Boolean.class, job.projectId());
    if (!Boolean.TRUE.equals(enabled) && !job.force()) { finishJob(job, null, "DISABLED", false); return; }
    if (result.hypothesis() != null && !current.confirmedPurpose())
      memory.recordAnalysisInference(job.projectId(), job.diagramId(), job.email(), result.hypothesis());
    String outcome = "NO_RECOMMENDATION";
    if (result.hasRecommendation()) {
      String key = prepared.snapshot().recommendationKey(result.type(), result.elementIds()), evidence = prepared.snapshot().evidence(result.elementIds());
      Boolean duplicate = jdbc.queryForObject("""
          SELECT EXISTS (SELECT 1 FROM ai_recommendation WHERE diagram_id = ? AND dedup_key = ?
            AND (evidence_hash = ? OR created_at >= now() - (? * interval '1 hour')))
          """, Boolean.class, job.diagramId(), key, evidence, config.getDedupHours());
      if (!Boolean.TRUE.equals(duplicate)) {
        var publication = conversations.publishRecommendation(job.projectId(), job.diagramId(),
            currentJobs.getFirst().conversationId(), job.email(), result.teamMessage(current.diagramName()),
            response.promptTokens(), response.completionTokens(), latency);
        jdbc.update("""
            INSERT INTO ai_recommendation(id, project_id, diagram_id, revision, semantic_hash,
              dedup_key, evidence_hash, details_json, conversation_id, message_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), job.projectId(), job.diagramId(), prepared.snapshot().revision(),
            prepared.snapshot().hash(), key, evidence, recommendationJson(result, prepared.snapshot()), publication.conversationId(), publication.messageId());
        jdbc.update("UPDATE ai_analysis_job SET conversation_id = ? WHERE diagram_id = ?", publication.conversationId(), job.diagramId());
        outcome = "PUBLISHED";
      } else outcome = "DUPLICATE_SUPPRESSED";
    }
    jdbc.update("UPDATE ai_analysis_run SET outcome = ? WHERE id = ?", outcome, job.token());
    finishJob(job, prepared.snapshot(), outcome, true);
  }

  public void fail(Job job, Integer httpStatus, long latency, boolean retryable, String analyzedHash) {
    lockDiagram(job.diagramId());
    jdbc.queryForList("SELECT diagram_id FROM ai_analysis_job WHERE diagram_id = ? FOR UPDATE", job.diagramId());
    // A failed/crashed request remains charged conservatively until its rolling window expires.
    jdbc.update("UPDATE ai_analysis_run SET outcome = 'ERROR', http_status = ?, latency_ms = ?, completed_at = now() WHERE id = ?",
        httpStatus, latency, job.token());
    if (!ownsLease(job)) return;
    boolean newerSemantics = analyzedHash != null && !snapshots.read(job.diagramId()).hash().equals(analyzedHash);
    if (Integer.valueOf(429).equals(httpStatus)) jdbc.update("""
        UPDATE ai_analysis_policy SET paused_until = greatest(coalesce(paused_until, now()), now() + (? * interval '1 second'))
        WHERE project_id = ?
        """, config.getRateLimitPauseSeconds(), job.projectId());
    boolean retry = newerSemantics || (retryable && job.attempts() < config.getMaxAttempts());
    jdbc.update("""
        UPDATE ai_analysis_job SET run_token = NULL, lease_until = NULL,
          processed_sequence = CASE WHEN ? THEN processed_sequence ELSE sequence END,
          force_review = CASE WHEN ? THEN force_review ELSE FALSE END,
          attempts = CASE WHEN ? THEN 0 ELSE attempts END,
          due_at = now() + (? * interval '1 second'), last_status = ?, updated_at = now()
        WHERE diagram_id = ? AND run_token = ?
        """, retry, retry, newerSemantics,
        Math.max(config.getRetrySeconds(), Integer.valueOf(429).equals(httpStatus) ? config.getRateLimitPauseSeconds() : 0),
        newerSemantics ? "PENDING" : retry ? "RETRY_PENDING" : "ERROR", job.diagramId(), job.token());
  }

  private void settle(Job job, LlmResponse response, long latency, String status) {
    boolean estimated = response.promptTokens() <= 0 || response.completionTokens() <= 0;
    jdbc.update("""
        UPDATE ai_analysis_run SET charged_tokens = CASE WHEN ? THEN reserved_tokens ELSE ? END,
          prompt_tokens = ?, completion_tokens = ?, usage_estimated = ?, latency_ms = ?, http_status = ?,
          outcome = ?, completed_at = now() WHERE id = ?
        """, estimated, Math.max(0L, (long)response.promptTokens() + response.completionTokens()),
        response.promptTokens(), response.completionTokens(), estimated, latency, response.httpStatusCode(), status, job.token());
  }

  private void finishJob(Job job, AiSemanticSnapshot.Snapshot snapshot, String status, boolean recordHash) {
    jdbc.update("""
        UPDATE ai_analysis_job SET processed_sequence = greatest(processed_sequence, ?), run_token = NULL,
          lease_until = NULL, force_review = CASE WHEN sequence = ? THEN FALSE ELSE force_review END,
          last_hash = CASE WHEN ? THEN ? ELSE last_hash END,
          analyzed_revision = CASE WHEN ? THEN ? ELSE analyzed_revision END,
          attempts = 0, last_status = ?, updated_at = now()
        WHERE diagram_id = ? AND run_token = ?
        """, job.sequence(), job.sequence(), recordHash, snapshot == null ? null : snapshot.hash(),
        snapshot != null, snapshot == null ? null : snapshot.revision(), status, job.diagramId(), job.token());
  }

  public Map<String, Object> status(UUID projectId, UUID diagramId, String email) {
    contexts.authorize(email, projectId, diagramId); queue.ensurePolicy(projectId);
    var policy = jdbc.queryForMap("SELECT * FROM ai_analysis_policy WHERE project_id = ?", projectId);
    var snapshot = snapshots.read(diagramId);
    var jobs = jdbc.queryForList("SELECT last_status, analyzed_revision, due_at, lease_until FROM ai_analysis_job WHERE diagram_id = ?", diagramId);
    Map<String,Object> view = new LinkedHashMap<>();
    view.put("globallyEnabled", config.isEnabled()); view.put("policy", policy);
    view.put("usage", usage(projectId, ((Number)policy.get("period_seconds")).intValue()));
    view.put("job", jobs.isEmpty() ? null : jobs.getFirst()); view.put("recommendations", recommendations(diagramId));
    view.put("needsContext", !snapshot.confirmedPurpose()); view.put("classCount", snapshot.classCount());
    view.put("hypotheses", memory.get(projectId, diagramId, email).entries().stream()
        .filter(entry -> entry.confidenceScope() == AiMemoryConfidence.INFERRED).toList());
    view.put("currentRevision", snapshot.revision()); return view;
  }

  public void requestReview(UUID projectId, UUID diagramId, String email) {
    var member = projects.requireCanEdit(projectId, email); contexts.authorize(email, projectId, diagramId);
    if (!config.isEnabled()) throw new BadRequestException("El análisis IA está desactivado en este servidor.");
    lockDiagram(diagramId); queue.enqueue(snapshots.read(diagramId), email, true);
    audit(projectId, member.getUser().getId(), "MANUAL_REVIEW", diagramId.toString());
  }

  public void updatePolicy(UUID projectId, String email, PolicyUpdate update) {
    var member = projects.requireCanEdit(projectId, email); queue.ensurePolicy(projectId);
    var row = jdbc.queryForMap("SELECT * FROM ai_analysis_policy WHERE project_id = ? FOR UPDATE", projectId);
    long budget = update.tokenBudget() == null ? ((Number)row.get("token_budget")).longValue() : update.tokenBudget();
    long reserve = update.explicitReserve() == null ? ((Number)row.get("explicit_reserve")).longValue() : update.explicitReserve();
    if (reserve >= budget) throw new BadRequestException("La reserva para consultas explícitas debe ser menor al presupuesto.");
    jdbc.update("""
        UPDATE ai_analysis_policy SET automatic_enabled = coalesce(?, automatic_enabled), max_calls = coalesce(?, max_calls),
          period_seconds = coalesce(?, period_seconds), token_budget = ?, explicit_reserve = ?, updated_by = ?, updated_at = now()
        WHERE project_id = ?
        """, update.enabled(), update.maxCalls(), update.periodSeconds(), budget, reserve, member.getUser().getId(), projectId);
    audit(projectId, member.getUser().getId(), "POLICY_UPDATED", json(update));
  }

  public void disposition(UUID projectId, UUID diagramId, UUID recommendationId, String email, String status) {
    var member = projects.requireCanEdit(projectId, email); contexts.authorize(email, projectId, diagramId);
    if (!Set.of("RESOLVED", "DISMISSED").contains(status)) throw new BadRequestException("Estado de recomendación no válido.");
    int changed = jdbc.update("""
        UPDATE ai_recommendation SET status = ?, updated_by = ?, updated_at = now()
        WHERE id = ? AND project_id = ? AND diagram_id = ?
        """, status, member.getUser().getId(), recommendationId, projectId, diagramId);
    if (changed != 1) throw new NotFoundException("Recomendación no encontrada.");
    audit(projectId, member.getUser().getId(), status, recommendationId.toString());
  }

  private List<Map<String,Object>> recommendations(UUID diagramId) {
    return jdbc.query("SELECT * FROM ai_recommendation WHERE diagram_id = ? ORDER BY created_at DESC LIMIT 10", (rs, row) -> {
      Map<String,Object> view = new LinkedHashMap<>();
      view.put("id", rs.getObject("id", UUID.class)); view.put("revision", rs.getLong("revision"));
      try { view.put("details", mapper.readTree(rs.getString("details_json"))); } catch(Exception invalid) { view.put("details", null); }
      view.put("status", rs.getString("status")); view.put("conversationId", rs.getObject("conversation_id", UUID.class));
      view.put("messageId", rs.getObject("message_id", UUID.class)); view.put("createdAt", rs.getTimestamp("created_at").toInstant()); return view;
    }, diagramId);
  }

  private Map<String,Object> usage(UUID projectId, int period) {
    return jdbc.queryForMap("""
        SELECT count(*) AS calls, coalesce(sum(charged_tokens), 0) AS tokens,
          count(*) FILTER (WHERE outcome = 'RUNNING') AS running,
          count(*) FILTER (WHERE usage_estimated) AS estimated
        FROM ai_analysis_run WHERE kind='PROACTIVE' AND project_id = ? AND (started_at >= now() - (? * interval '1 second') OR outcome = 'RUNNING')
        """, projectId, period);
  }
  private Map<String,Object> sharedUsage(UUID projectId,int period) {
    return jdbc.queryForMap("SELECT coalesce(sum(charged_tokens),0) AS tokens,count(*) FILTER(WHERE kind='EXPLICIT') AS explicit_calls FROM ai_analysis_run WHERE project_id=? AND (started_at>=now()-(?*interval '1 second') OR outcome='RUNNING')",projectId,period);
  }
  public Instant reserveExplicit(UUID projectId,UUID diagramId,String email,UUID runId,long tokens,int maxCalls) {
    projects.requireAccess(projectId,email);queue.ensurePolicy(projectId);
    var policy=jdbc.queryForMap("SELECT * FROM ai_analysis_policy WHERE project_id=? FOR UPDATE",projectId);
    var pause=(java.sql.Timestamp)policy.get("paused_until");
    if(pause!=null && pause.toInstant().isAfter(Instant.now())) return pause.toInstant();
    int period=((Number)policy.get("period_seconds")).intValue();var usage=sharedUsage(projectId,period);
    if(((Number)usage.get("tokens")).longValue()+tokens>((Number)policy.get("token_budget")).longValue()
        || ((Number)usage.get("explicit_calls")).intValue()>=maxCalls) return Instant.now().plusSeconds(period);
    jdbc.update("INSERT INTO ai_analysis_run(id,project_id,diagram_id,reserved_tokens,charged_tokens,kind) VALUES (?,?,?,?,?,'EXPLICIT')",runId,projectId,diagramId,tokens,tokens);
    return null;
  }
  public void settleExplicit(UUID projectId,UUID runId,LlmResponse response,long latency,String outcome) {
    boolean estimated=response==null || response.promptTokens()<=0 || response.completionTokens()<=0;
    jdbc.update("UPDATE ai_analysis_run SET charged_tokens=CASE WHEN ? THEN reserved_tokens ELSE ? END,prompt_tokens=?,completion_tokens=?,latency_ms=?,http_status=?,usage_estimated=?,outcome=?,completed_at=now() WHERE id=? AND project_id=? AND kind='EXPLICIT'",
        estimated,response==null?0L:(long)response.promptTokens()+response.completionTokens(),response==null?0:response.promptTokens(),response==null?0:response.completionTokens(),latency,response==null?null:response.httpStatusCode(),estimated,outcome,runId,projectId);
    if(response!=null && Integer.valueOf(429).equals(response.httpStatusCode())) jdbc.update("UPDATE ai_analysis_policy SET paused_until=greatest(coalesce(paused_until,now()),now()+(?*interval '1 second')) WHERE project_id=?",config.getRateLimitPauseSeconds(),projectId);
  }
  private boolean ownsLease(Job job) {
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ai_analysis_job WHERE diagram_id = ? AND run_token = ? AND lease_until > now())", Boolean.class, job.diagramId(), job.token()));
  }
  private void lockDiagram(UUID id) { jdbc.queryForObject("SELECT id FROM diagrama_clase WHERE id = ? FOR UPDATE", UUID.class, id); }
  private void audit(UUID projectId, UUID userId, String action, String details) {
    jdbc.update("INSERT INTO ai_analysis_audit(id, project_id, actor_user_id, action, details) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), projectId, userId, action, details);
  }
  private String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("No se pudo guardar el análisis IA.", e); } }
  private String recommendationJson(AiAnalysisResult.Result result, AiSemanticSnapshot.Snapshot snapshot) {
    com.fasterxml.jackson.databind.node.ObjectNode root = mapper.valueToTree(result);
    var names = root.putArray("elements");
    for (String group : List.of("classes", "relations")) snapshot.model().path(group).forEach(element -> {
      if (result.elementIds().contains(element.path("id").asText())) names.addObject()
          .put("id",element.path("id").asText()).put("name",element.path("name").asText("Relación UML"));
    });
    return root.toString();
  }
  private String changes(String baseline, JsonNode current) {
    try {
      JsonNode before = mapper.readTree(baseline);
      List<String> diff = new ArrayList<>();
      for (String group : List.of("classes", "relations")) {
        Map<String, JsonNode> old = new HashMap<>(), now = new HashMap<>();
        before.path(group).forEach(value -> old.put(value.path("id").asText(), value));
        current.path(group).forEach(value -> now.put(value.path("id").asText(), value));
        now.forEach((id,value) -> { if (!value.equals(old.get(id))) diff.add((old.containsKey(id) ? "MODIFIED " : "ADDED ") + group + " " + id); });
        old.keySet().stream().filter(id -> !now.containsKey(id)).forEach(id -> diff.add("DELETED " + group + " " + id));
      }
      return "Cambios semánticos agrupados: " + diff.size() + "\n" + String.join("\n", diff.stream().limit(30).toList());
    } catch (Exception invalid) { return "Revisión global solicitada."; }
  }
  private Job job(ResultSet rs, int row) throws SQLException {
    return new Job(rs.getObject("diagram_id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("actor_email"),
        rs.getLong("sequence"), rs.getString("baseline_hash"), rs.getString("last_hash"), rs.getBoolean("force_review"),
        rs.getObject("run_token", UUID.class), rs.getInt("attempts"), rs.getString("baseline_model"), rs.getObject("conversation_id", UUID.class));
  }
  public record Job(UUID diagramId, UUID projectId, String email, long sequence, String baselineHash, String lastHash,
      boolean force, UUID token, int attempts, String baselineModel, UUID conversationId) { }
  public record Prepared(AiSemanticSnapshot.Snapshot snapshot, String context, String changes, String previous) {
    public boolean equivalent(Job job) { return !job.force() && (snapshot.hash().equals(job.baselineHash()) || snapshot.hash().equals(job.lastHash())); }
  }
  public record PolicyUpdate(Boolean enabled, @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(1000) Integer maxCalls,
      @jakarta.validation.constraints.Min(60) @jakarta.validation.constraints.Max(2592000) Integer periodSeconds,
      @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100000000) Long tokenBudget,
      @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100000000) Long explicitReserve) { }
}
