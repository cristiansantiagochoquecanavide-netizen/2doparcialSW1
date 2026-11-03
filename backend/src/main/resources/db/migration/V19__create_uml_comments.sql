CREATE TABLE uml_comment (
  id UUID PRIMARY KEY,
  diagrama_id UUID NOT NULL REFERENCES diagrama_clase(id) ON DELETE CASCADE,
  author_user_id UUID NOT NULL REFERENCES user_account(id),
  elemento_id UUID REFERENCES elemento_uml(id) ON DELETE CASCADE,
  relacion_id UUID REFERENCES relacion_uml(id) ON DELETE CASCADE,
  parent_id UUID REFERENCES uml_comment(id) ON DELETE CASCADE,
  content VARCHAR(2000) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at TIMESTAMPTZ,
  resolved_by UUID REFERENCES user_account(id),
  CONSTRAINT ck_uml_comment_target CHECK ((elemento_id IS NOT NULL) OR (relacion_id IS NOT NULL)),
  CONSTRAINT ck_uml_comment_status CHECK (status IN ('OPEN', 'RESOLVED'))
);

CREATE INDEX idx_uml_comment_diagram ON uml_comment(diagrama_id, created_at DESC);
CREATE INDEX idx_uml_comment_element ON uml_comment(elemento_id);
CREATE INDEX idx_uml_comment_relation ON uml_comment(relacion_id);
CREATE INDEX idx_uml_comment_parent ON uml_comment(parent_id);
