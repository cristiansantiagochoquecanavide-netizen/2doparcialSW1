-- Keep existing channel IDs, messages, jobs and proposals intact.
ALTER TABLE project_chat_channel ADD COLUMN channel_type VARCHAR(8) NOT NULL DEFAULT 'TEXT';
ALTER TABLE project_chat_channel ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE project_chat_channel ADD CONSTRAINT ck_project_channel_type CHECK (channel_type IN ('TEXT', 'VOICE'));
INSERT INTO project_chat_channel (id, project_id, slug, name, sort_order, channel_type)
SELECT gen_random_uuid(), id, 'voz-diseno', 'Sala de diseño', 2, 'VOICE' FROM project
ON CONFLICT (project_id, slug) DO NOTHING;
