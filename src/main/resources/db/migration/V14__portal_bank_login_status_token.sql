ALTER TABLE portal_bank_login_challenges
    ADD COLUMN IF NOT EXISTS status_token_hash VARCHAR(64);
