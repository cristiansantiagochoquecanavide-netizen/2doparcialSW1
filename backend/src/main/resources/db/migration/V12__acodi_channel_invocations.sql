ALTER TABLE project_chat_message ADD COLUMN canonical_ai_message_id UUID UNIQUE REFERENCES ai_conversation_message(id) ON DELETE SET NULL;
CREATE UNIQUE INDEX uq_chat_agent_reply ON project_chat_message(reply_to_message_id) WHERE author_type='AGENT' AND reply_to_message_id IS NOT NULL;
ALTER TABLE ai_analysis_run ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'PROACTIVE' CHECK (kind IN ('PROACTIVE','EXPLICIT'));
CREATE TABLE acodi_channel_job (
  message_id UUID PRIMARY KEY REFERENCES project_chat_message(id) ON DELETE CASCADE,
  project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  channel_id UUID NOT NULL REFERENCES project_chat_channel(id) ON DELETE CASCADE,
  diagram_id UUID REFERENCES diagrama_clase(id) ON DELETE SET NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDIENTE' CHECK (status IN ('PENDIENTE','PROCESANDO','COMPLETADO','FALLIDO')),
  attempts INTEGER NOT NULL DEFAULT 0,
  run_token UUID,
  lease_until TIMESTAMPTZ,
  retry_key UUID,
  retry_after TIMESTAMPTZ,
  error_code VARCHAR(40),
  response_message_id UUID UNIQUE REFERENCES project_chat_message(id),
  prompt_tokens INTEGER NOT NULL DEFAULT 0,
  completion_tokens INTEGER NOT NULL DEFAULT 0,
  latency_ms BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (message_id,channel_id) REFERENCES project_chat_message(id,channel_id) ON DELETE CASCADE
);
CREATE INDEX idx_acodi_pending ON acodi_channel_job(created_at) WHERE status='PENDIENTE';
