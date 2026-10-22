CREATE TABLE ai_proposal_request (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  diagram_id UUID NOT NULL REFERENCES diagrama_clase(id) ON DELETE CASCADE,
  requester_id UUID NOT NULL REFERENCES user_account(id),
  client_request_id UUID NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  instruction VARCHAR(8000) NOT NULL,
  conversation_id UUID REFERENCES ai_conversation(id) ON DELETE SET NULL,
  question_id UUID REFERENCES ai_conversation_message(id) ON DELETE SET NULL,
  recommendation_id UUID REFERENCES ai_recommendation(id) ON DELETE SET NULL,
  scope VARCHAR(12) NOT NULL CHECK (scope IN ('GROUP','PRIVATE')),
  status VARCHAR(12) NOT NULL CHECK (status IN ('GENERATING','READY','ERROR')),
  lease_until TIMESTAMPTZ NOT NULL,
  error_code VARCHAR(40),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(project_id,requester_id,client_request_id)
);
CREATE TABLE ai_uml_proposal (
  id UUID PRIMARY KEY,
  request_id UUID NOT NULL UNIQUE REFERENCES ai_proposal_request(id) ON DELETE CASCADE,
  project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  diagram_id UUID NOT NULL REFERENCES diagrama_clase(id) ON DELETE CASCADE,
  requester_id UUID NOT NULL REFERENCES user_account(id),
  conversation_id UUID REFERENCES ai_conversation(id) ON DELETE SET NULL,
  recommendation_id UUID REFERENCES ai_recommendation(id) ON DELETE SET NULL,
  scope VARCHAR(12) NOT NULL CHECK (scope IN ('GROUP','PRIVATE')),
  description VARCHAR(2000) NOT NULL,
  base_revision BIGINT NOT NULL,
  plan_json TEXT NOT NULL CHECK (octet_length(plan_json) <= 65536),
  preview_json TEXT NOT NULL CHECK (octet_length(preview_json) <= 131072),
  status VARCHAR(16) NOT NULL CHECK (status IN ('PENDIENTE','ACLARACION','APLICADA','RECHAZADA','CONFLICTO','FALLIDA')),
  decision_by UUID REFERENCES user_account(id),
  decided_at TIMESTAMPTZ,
  applied_at TIMESTAMPTZ,
  applied_revision BIGINT,
  application_key UUID,
  version_id UUID REFERENCES version_diagrama(id) ON DELETE SET NULL,
  result_code VARCHAR(40),
  prompt_tokens INTEGER NOT NULL DEFAULT 0,
  completion_tokens INTEGER NOT NULL DEFAULT 0,
  latency_ms BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_uml_proposal_visible ON ai_uml_proposal(project_id,diagram_id,created_at DESC);
CREATE INDEX idx_ai_uml_proposal_conversation ON ai_uml_proposal(conversation_id,created_at DESC);
CREATE TABLE ai_proposal_audit (
  id UUID PRIMARY KEY,
  proposal_id UUID NOT NULL REFERENCES ai_uml_proposal(id) ON DELETE CASCADE,
  actor_id UUID NOT NULL REFERENCES user_account(id),
  action VARCHAR(24) NOT NULL,
  result_code VARCHAR(40),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
