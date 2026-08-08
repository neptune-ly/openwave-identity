-- Keep the original registered_banks hash valid during cutover, while allowing
-- a second full-bank key to be issued, verified by the caller, then promoted by
-- explicitly deactivating the exposed original key.
ALTER TABLE registered_banks
    ADD COLUMN IF NOT EXISTS legacy_api_key_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS legacy_api_key_deactivated_at TIMESTAMPTZ NULL;

ALTER TABLE bank_api_credentials
    DROP CONSTRAINT IF EXISTS chk_bank_api_credentials_scope;

ALTER TABLE bank_api_credentials
    ADD CONSTRAINT chk_bank_api_credentials_scope
    CHECK (scope IN ('ASTRO_REGISTRY', 'FULL_BANK'));
