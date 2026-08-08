-- Additive per-bank credentials. registered_banks.api_key_hash remains the
-- legacy full-bank credential and is intentionally neither changed nor copied.
CREATE TABLE bank_api_credentials (
    id           BIGSERIAL PRIMARY KEY,
    bank_id      BIGINT NOT NULL,
    api_key_hash VARCHAR(64) NOT NULL UNIQUE,
    scope        VARCHAR(40) NOT NULL,
    label        VARCHAR(120) NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    revoked_at   TIMESTAMPTZ NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by   VARCHAR(160) NULL,
    CONSTRAINT fk_bank_api_credentials_bank
        FOREIGN KEY (bank_id) REFERENCES registered_banks (id) ON DELETE RESTRICT,
    CONSTRAINT chk_bank_api_credentials_scope CHECK (scope IN ('ASTRO_REGISTRY')),
    CONSTRAINT chk_bank_api_credentials_lifecycle CHECK (
        (active = TRUE AND revoked_at IS NULL) OR (active = FALSE AND revoked_at IS NOT NULL)
    )
);

CREATE INDEX idx_bank_api_credentials_active_bank
    ON bank_api_credentials (bank_id)
    WHERE active = TRUE;
