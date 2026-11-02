ALTER TABLE case_tool.version_diagrama
  ADD COLUMN is_working BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX uq_version_diagrama_working
  ON case_tool.version_diagrama(diagrama_id)
  WHERE is_working = TRUE;
