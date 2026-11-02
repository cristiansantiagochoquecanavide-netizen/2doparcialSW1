ALTER TABLE acodi_channel_job
    ADD COLUMN comparison_before_version_id UUID,
    ADD COLUMN comparison_after_version_id UUID;

CREATE INDEX idx_version_diagrama_comparison_lookup
    ON version_diagrama (diagrama_id, numero_version);
