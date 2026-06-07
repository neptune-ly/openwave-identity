ALTER TABLE npt_identities
    ADD COLUMN IF NOT EXISTS email VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_identity_email
    ON npt_identities (email)
    WHERE email IS NOT NULL;

