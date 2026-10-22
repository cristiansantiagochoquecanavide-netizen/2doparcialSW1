CREATE TABLE ai_conversation (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  diagram_id UUID REFERENCES diagrama_clase(id) ON DELETE SET NULL,
  scope VARCHAR(12) NOT NULL,
  owner_user_id UUID REFERENCES user_account(id) ON DELETE CASCADE,
  created_by UUID NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  title VARCHAR(160) NOT NULL,
  archived_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ai_conversation_scope CHECK (
    (scope = 'GROUP' AND owner_user_id IS NULL) OR (scope = 'PRIVATE' AND owner_user_id IS NOT NULL)
  )
);

CREATE INDEX idx_ai_conversation_project_scope_updated
  ON ai_conversation(project_id, scope, updated_at DESC);
CREATE INDEX idx_ai_conversation_private_owner
  ON ai_conversation(project_id, owner_user_id, updated_at DESC)
  WHERE scope = 'PRIVATE';

CREATE TABLE ai_conversation_message (
  id UUID PRIMARY KEY,
  conversation_id UUID NOT NULL REFERENCES ai_conversation(id) ON DELETE CASCADE,
  client_message_id UUID NOT NULL,
  sender_user_id UUID REFERENCES user_account(id) ON DELETE SET NULL,
  recipient_user_id UUID REFERENCES user_account(id) ON DELETE SET NULL,
  reply_to_message_id UUID REFERENCES ai_conversation_message(id) ON DELETE CASCADE,
  sender_type VARCHAR(8) NOT NULL,
  content VARCHAR(8000) NOT NULL,
  processing_status VARCHAR(12) NOT NULL,
  error_code VARCHAR(40),
  prompt_tokens INTEGER NOT NULL DEFAULT 0,
  completion_tokens INTEGER NOT NULL DEFAULT 0,
  latency_ms BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ai_conversation_message_sender_type CHECK (sender_type IN ('USER', 'AGENT')),
  CONSTRAINT ck_ai_conversation_message_status CHECK (processing_status IN ('PENDING', 'COMPLETED', 'ERROR')),
  CONSTRAINT ck_ai_conversation_message_author CHECK (
    (sender_type = 'USER' AND sender_user_id IS NOT NULL AND processing_status IN ('PENDING', 'COMPLETED', 'ERROR'))
    OR (sender_type = 'AGENT' AND sender_user_id IS NULL AND processing_status = 'COMPLETED')
  ),
  CONSTRAINT ck_ai_conversation_message_content CHECK (length(btrim(content)) > 0),
  CONSTRAINT uq_ai_conversation_message_client_id UNIQUE (conversation_id, sender_user_id, client_message_id)
);

CREATE INDEX idx_ai_conversation_message_history
  ON ai_conversation_message(conversation_id, created_at DESC, id DESC);

CREATE TABLE project_ai_memory_audit (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  actor_user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  target_type VARCHAR(16) NOT NULL,
  target_id UUID,
  action VARCHAR(12) NOT NULL,
  content_snapshot TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_project_ai_memory_audit_target CHECK (target_type IN ('PROJECT', 'ENTRY')),
  CONSTRAINT ck_project_ai_memory_audit_action CHECK (action IN ('CREATE', 'UPDATE', 'DELETE', 'CONFIRM'))
);

CREATE INDEX idx_project_ai_memory_audit_project_created
  ON project_ai_memory_audit(project_id, created_at DESC);
