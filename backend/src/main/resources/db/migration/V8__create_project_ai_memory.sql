CREATE TABLE project_ai_memory (
  project_id UUID PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
  system_name VARCHAR(160),
  system_description VARCHAR(4000),
  business_domain VARCHAR(1000),
  objectives TEXT,
  additional_context TEXT,
  updated_by UUID NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE project_ai_memory_entry (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  diagram_id UUID REFERENCES diagrama_clase(id) ON DELETE CASCADE,
  entry_type VARCHAR(32) NOT NULL,
  confidence_scope VARCHAR(16) NOT NULL,
  content VARCHAR(4000) NOT NULL,
  created_by UUID REFERENCES user_account(id) ON DELETE RESTRICT,
  updated_by UUID NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_project_ai_memory_entry_type CHECK (
    entry_type IN ('BUSINESS_RULE', 'FUNCTIONAL_CONSTRAINT', 'ARCHITECTURE_DECISION', 'MODELING_CONVENTION', 'ADDITIONAL')
  ),
  CONSTRAINT ck_project_ai_memory_entry_scope CHECK (confidence_scope IN ('CONFIRMED', 'INFERRED')),
  CONSTRAINT ck_project_ai_memory_entry_author CHECK (
    confidence_scope = 'INFERRED' OR updated_by IS NOT NULL
  ),
  CONSTRAINT ck_project_ai_memory_entry_content CHECK (length(btrim(content)) > 0)
);

CREATE INDEX idx_project_ai_memory_entries_project_updated
  ON project_ai_memory_entry(project_id, updated_at DESC);
CREATE INDEX idx_project_ai_memory_entries_diagram
  ON project_ai_memory_entry(project_id, diagram_id, updated_at DESC);
