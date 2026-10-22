-- A transactional outbox: a job is invisible to workers until the UML write commits.
CREATE TABLE ai_analysis_policy (
  project_id UUID PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
  automatic_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  max_calls INTEGER NOT NULL CHECK (max_calls > 0),
  period_seconds INTEGER NOT NULL CHECK (period_seconds >= 60),
  token_budget BIGINT NOT NULL CHECK (token_budget > 0),
  explicit_reserve BIGINT NOT NULL CHECK (explicit_reserve >= 0 AND explicit_reserve < token_budget),
  paused_until TIMESTAMPTZ,
  updated_by UUID REFERENCES user_account(id),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE ai_analysis_job (
  diagram_id UUID PRIMARY KEY REFERENCES diagrama_clase(id) ON DELETE CASCADE,
  project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  actor_email VARCHAR(255) NOT NULL,
  sequence BIGINT NOT NULL DEFAULT 1,
  processed_sequence BIGINT NOT NULL DEFAULT 0,
  baseline_hash VARCHAR(64) NOT NULL,
  baseline_model TEXT NOT NULL,
  last_hash VARCHAR(64),
  due_at TIMESTAMPTZ NOT NULL,
  lease_until TIMESTAMPTZ,
  run_token UUID,
  force_review BOOLEAN NOT NULL DEFAULT FALSE,
  attempts INTEGER NOT NULL DEFAULT 0,
  last_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
  analyzed_revision BIGINT,
  conversation_id UUID REFERENCES ai_conversation(id) ON DELETE SET NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_analysis_job_due ON ai_analysis_job(due_at) WHERE sequence > processed_sequence;
CREATE TABLE ai_analysis_run (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  diagram_id UUID NOT NULL REFERENCES diagrama_clase(id) ON DELETE CASCADE,
  reserved_tokens BIGINT NOT NULL CHECK (reserved_tokens > 0),
  charged_tokens BIGINT NOT NULL CHECK (charged_tokens >= 0),
  prompt_tokens INTEGER,
  completion_tokens INTEGER,
  usage_estimated BOOLEAN NOT NULL DEFAULT TRUE,
  latency_ms BIGINT,
  http_status INTEGER,
  outcome VARCHAR(40) NOT NULL DEFAULT 'RUNNING',
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ
);
CREATE INDEX idx_ai_analysis_run_budget ON ai_analysis_run(project_id, started_at);
CREATE TABLE ai_recommendation (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  diagram_id UUID NOT NULL REFERENCES diagrama_clase(id) ON DELETE CASCADE,
  revision BIGINT NOT NULL,
  semantic_hash VARCHAR(64) NOT NULL,
  dedup_key VARCHAR(64) NOT NULL,
  evidence_hash VARCHAR(64) NOT NULL,
  details_json TEXT NOT NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED','DISMISSED')),
  conversation_id UUID NOT NULL REFERENCES ai_conversation(id) ON DELETE CASCADE,
  message_id UUID NOT NULL REFERENCES ai_conversation_message(id) ON DELETE CASCADE,
  updated_by UUID REFERENCES user_account(id),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(diagram_id, dedup_key, evidence_hash)
);
CREATE INDEX idx_ai_recommendation_recent ON ai_recommendation(diagram_id, created_at DESC);
CREATE TABLE ai_analysis_audit (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  actor_user_id UUID NOT NULL REFERENCES user_account(id),
  action VARCHAR(40) NOT NULL,
  details VARCHAR(1000) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE project_ai_memory_audit DROP CONSTRAINT ck_project_ai_memory_audit_action;
ALTER TABLE project_ai_memory_audit ADD CONSTRAINT ck_project_ai_memory_audit_action
  CHECK (action IN ('CREATE','UPDATE','DELETE','CONFIRM','INFER'));
ALTER TABLE project_ai_memory_entry DROP CONSTRAINT ck_project_ai_memory_entry_type;
ALTER TABLE project_ai_memory_entry ADD CONSTRAINT ck_project_ai_memory_entry_type CHECK (
  entry_type IN ('BUSINESS_RULE','FUNCTIONAL_CONSTRAINT','ARCHITECTURE_DECISION','MODELING_CONVENTION','ADDITIONAL','SYSTEM_CONTEXT')
);
