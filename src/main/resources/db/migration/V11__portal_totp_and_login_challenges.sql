ALTER TABLE portal_users
    ADD COLUMN totp_secret VARCHAR(128),
    ADD COLUMN totp_pending_secret VARCHAR(128),
    ADD COLUMN totp_enabled_at TIMESTAMP NULL;

CREATE TABLE portal_login_challenges (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES portal_users(id) ON DELETE CASCADE,
    purpose VARCHAR(30) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(45)
);

CREATE INDEX idx_portal_login_challenges_user_id ON portal_login_challenges(user_id);
CREATE INDEX idx_portal_login_challenges_expires_at ON portal_login_challenges(expires_at);
