ALTER TABLE case_tool.version_diagrama
  ADD COLUMN source_revision BIGINT NOT NULL DEFAULT 1,
  ADD COLUMN operation_type VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
  ADD COLUMN restored_from_version_id UUID REFERENCES case_tool.version_diagrama(id) ON DELETE SET NULL,
  ADD COLUMN idempotency_key UUID;

CREATE UNIQUE INDEX uq_version_diagrama_number
  ON case_tool.version_diagrama(diagrama_id, numero_version);

CREATE UNIQUE INDEX uq_version_diagrama_idempotency
  ON case_tool.version_diagrama(diagrama_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

UPDATE case_tool.version_diagrama
SET source_revision = COALESCE((snapshot_json::jsonb #>> '{diagram,revision}')::BIGINT, source_revision)
WHERE snapshot_json IS NOT NULL;
