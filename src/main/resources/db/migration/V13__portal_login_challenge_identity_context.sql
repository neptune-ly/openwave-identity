ALTER TABLE portal_login_challenges
    ADD COLUMN IF NOT EXISTS requires_bank_approval BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS identifier_type VARCHAR(24),
    ADD COLUMN IF NOT EXISTS identifier_value VARCHAR(120);
