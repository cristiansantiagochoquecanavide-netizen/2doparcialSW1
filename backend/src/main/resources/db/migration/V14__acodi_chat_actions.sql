CREATE TABLE acodi_chat_action (
  message_id UUID PRIMARY KEY REFERENCES project_chat_message(id) ON DELETE CASCADE,
  kind VARCHAR(40) NOT NULL,
  payload_json TEXT NOT NULL DEFAULT '{}',
  status VARCHAR(16) NOT NULL CHECK(status IN ('PENDING','CONFIRMED','CANCELLED','DONE')),
  result_text TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
