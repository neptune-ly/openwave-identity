ALTER TABLE registered_banks
    ADD COLUMN logo_url VARCHAR(512),
    ADD COLUMN brand_color VARCHAR(32),
    ADD COLUMN support_email VARCHAR(255),
    ADD COLUMN website VARCHAR(512);

CREATE TABLE IF NOT EXISTS portal_audit_events (
    id BIGSERIAL PRIMARY KEY,
    actor VARCHAR(160) NOT NULL,
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id VARCHAR(120) NOT NULL,
    details JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_portal_audit_events_entity ON portal_audit_events(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_portal_audit_events_created ON portal_audit_events(created_at);
