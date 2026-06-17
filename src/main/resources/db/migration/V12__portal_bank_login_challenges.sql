CREATE TABLE IF NOT EXISTS portal_bank_login_challenges (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    identity_id BIGINT NOT NULL,
    identifier_type VARCHAR(24) NOT NULL,
    identifier_hint VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    approved_bank_handle VARCHAR(20),
    portal_session_token VARCHAR(2048),
    portal_session_expires_at TIMESTAMP NULL,
    expires_at TIMESTAMP NOT NULL,
    actioned_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(45),
    CONSTRAINT fk_portal_bank_login_challenges_user
        FOREIGN KEY (user_id) REFERENCES portal_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_portal_bank_login_challenges_identity
        FOREIGN KEY (identity_id) REFERENCES npt_identities(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_portal_bank_login_challenges_user
    ON portal_bank_login_challenges (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_portal_bank_login_challenges_identity
    ON portal_bank_login_challenges (identity_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_portal_bank_login_challenges_status
    ON portal_bank_login_challenges (status, expires_at);
