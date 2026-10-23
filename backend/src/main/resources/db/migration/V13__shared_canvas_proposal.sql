CREATE TABLE ai_canvas_proposal (
  diagram_id UUID PRIMARY KEY REFERENCES diagrama_clase(id) ON DELETE CASCADE,
  proposal_id UUID UNIQUE REFERENCES ai_uml_proposal(id) ON DELETE SET NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
