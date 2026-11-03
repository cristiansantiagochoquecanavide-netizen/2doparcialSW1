ALTER TABLE uml_comment
  ADD COLUMN posicion_x DOUBLE PRECISION,
  ADD COLUMN posicion_y DOUBLE PRECISION;

ALTER TABLE uml_comment DROP CONSTRAINT ck_uml_comment_target;
ALTER TABLE uml_comment ADD CONSTRAINT ck_uml_comment_target CHECK (
  elemento_id IS NOT NULL OR relacion_id IS NOT NULL OR (posicion_x IS NOT NULL AND posicion_y IS NOT NULL)
);
