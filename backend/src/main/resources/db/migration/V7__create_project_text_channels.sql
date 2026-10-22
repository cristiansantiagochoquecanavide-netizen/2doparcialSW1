CREATE TABLE project_chat_channel (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  slug VARCHAR(80) NOT NULL,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500),
  sort_order SMALLINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_project_chat_channel_project_slug UNIQUE (project_id, slug),
  CONSTRAINT ck_project_chat_channel_slug CHECK (slug ~ '^[a-z0-9-]+$')
);

CREATE INDEX idx_project_chat_channel_project_order
  ON project_chat_channel(project_id, sort_order, name);

CREATE TABLE project_chat_message (
  id UUID PRIMARY KEY,
  channel_id UUID NOT NULL REFERENCES project_chat_channel(id) ON DELETE CASCADE,
  sender_user_id UUID REFERENCES user_account(id) ON DELETE RESTRICT,
  client_message_id UUID NOT NULL,
  sender_role VARCHAR(20) NOT NULL,
  author_type VARCHAR(16) NOT NULL DEFAULT 'USER',
  content VARCHAR(4000) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reply_to_message_id UUID,
  CONSTRAINT ck_project_chat_message_author_type CHECK (author_type IN ('USER', 'AGENT')),
  CONSTRAINT ck_project_chat_message_author CHECK (
    (author_type = 'USER' AND sender_user_id IS NOT NULL AND sender_role IN ('PROPIETARIO', 'EDITOR', 'LECTOR'))
    OR (author_type = 'AGENT' AND sender_user_id IS NULL AND sender_role = 'AGENTE')
  ),
  CONSTRAINT ck_project_chat_message_content CHECK (length(btrim(content)) > 0),
  CONSTRAINT uq_project_chat_message_id_channel UNIQUE (id, channel_id),
  CONSTRAINT uq_project_chat_message_idempotency UNIQUE (channel_id, sender_user_id, client_message_id),
  CONSTRAINT fk_project_chat_message_reply_same_channel
    FOREIGN KEY (reply_to_message_id, channel_id)
    REFERENCES project_chat_message(id, channel_id)
);

CREATE INDEX idx_project_chat_message_history
  ON project_chat_message(channel_id, created_at DESC, id DESC);
CREATE INDEX idx_project_chat_message_sender
  ON project_chat_message(sender_user_id, created_at DESC);

INSERT INTO project_chat_channel (id, project_id, slug, name, description, sort_order)
SELECT gen_random_uuid(), p.id, defaults.slug, defaults.name, defaults.description, defaults.sort_order
FROM project p
CROSS JOIN (VALUES
  ('general', 'general', 'Conversación general entre integrantes del proyecto.', 0),
  ('diseno-uml', 'diseño-uml', 'Discusión técnica sobre el modelo UML.', 1)
) AS defaults(slug, name, description, sort_order)
ON CONFLICT (project_id, slug) DO NOTHING;
