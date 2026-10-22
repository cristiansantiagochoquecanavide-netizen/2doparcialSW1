package bo.edu.caseapp.ai;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiAnalysisQueue {
  private final JdbcTemplate jdbc;
  private final AiSemanticSnapshot snapshots;
  private final AiAnalysisProperties settings;
  public AiAnalysisQueue(JdbcTemplate jdbc, AiSemanticSnapshot snapshots, AiAnalysisProperties settings) {
    this.jdbc=jdbc; this.snapshots=snapshots; this.settings=settings;
  }

  /** Called before mutation, in the SAME transaction. A rollback also removes this outbox write. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void beforeMutation(UUID diagramId) {
    if (!settings.isEnabled()) return;
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated() || authentication.getName().equals("anonymousUser")) return;
    jdbc.queryForObject("SELECT id FROM diagrama_clase WHERE id = ? FOR UPDATE", UUID.class, diagramId);
    enqueue(snapshots.read(diagramId), authentication.getName(), false);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(AiSemanticSnapshot.Snapshot snapshot, String email, boolean manual) {
    ensurePolicy(snapshot.projectId());
    jdbc.update("""
        INSERT INTO ai_analysis_job (diagram_id, project_id, actor_email, baseline_hash, baseline_model, due_at, force_review)
        VALUES (?, ?, ?, ?, ?, now() + (? * interval '1 second'), ?)
        ON CONFLICT (diagram_id) DO UPDATE SET
          baseline_hash = CASE WHEN ai_analysis_job.sequence = ai_analysis_job.processed_sequence THEN EXCLUDED.baseline_hash ELSE ai_analysis_job.baseline_hash END,
          baseline_model = CASE WHEN ai_analysis_job.sequence = ai_analysis_job.processed_sequence THEN EXCLUDED.baseline_model ELSE ai_analysis_job.baseline_model END,
          sequence = ai_analysis_job.sequence + 1, actor_email = EXCLUDED.actor_email,
          due_at = EXCLUDED.due_at, force_review = ai_analysis_job.force_review OR EXCLUDED.force_review,
          attempts = CASE WHEN ai_analysis_job.sequence = ai_analysis_job.processed_sequence THEN 0 ELSE ai_analysis_job.attempts END,
          last_status = 'PENDING', updated_at = now()
        """, snapshot.diagramId(), snapshot.projectId(), email, snapshot.hash(), snapshot.model().toString(),
        manual ? 0 : settings.getDebounceSeconds(), manual);
  }

  public void ensurePolicy(UUID projectId) {
    if (settings.getExplicitReserve() >= settings.getTokenBudget()) throw new IllegalStateException("La reserva explícita debe ser menor al presupuesto IA.");
    jdbc.update("""
        INSERT INTO ai_analysis_policy(project_id, max_calls, period_seconds, token_budget, explicit_reserve)
        VALUES (?, ?, ?, ?, ?) ON CONFLICT (project_id) DO NOTHING
        """, projectId, settings.getMaxCalls(), settings.getPeriodSeconds(), settings.getTokenBudget(), settings.getExplicitReserve());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void contextChanged(UUID diagramId, String email) {
    if (!settings.isEnabled()) return;
    enqueue(snapshots.read(diagramId), email, false);
    jdbc.update("UPDATE ai_analysis_job SET baseline_hash = 'CONTEXT_CHANGED' WHERE diagram_id = ?", diagramId);
  }
}
