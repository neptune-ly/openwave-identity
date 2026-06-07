CREATE TABLE portal_email_otps (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES portal_users(id),
    purpose VARCHAR(40) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(45)
);

CREATE INDEX idx_portal_email_otps_user_purpose ON portal_email_otps(user_id, purpose);
CREATE INDEX idx_portal_email_otps_expires ON portal_email_otps(expires_at);

CREATE TABLE portal_user_passkeys (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES portal_users(id),
    credential_id VARCHAR(512) NOT NULL UNIQUE,
    public_key TEXT NOT NULL,
    signature_count BIGINT NOT NULL DEFAULT 0,
    aaguid VARCHAR(64),
    attestation_type VARCHAR(50),
    friendly_name VARCHAR(120),
    rp_id VARCHAR(255),
    origin VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ
);

CREATE INDEX idx_portal_user_passkeys_user_id ON portal_user_passkeys(user_id);
CREATE INDEX idx_portal_user_passkeys_rp_id ON portal_user_passkeys(rp_id);

CREATE TABLE portal_webauthn_challenges (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES portal_users(id),
    purpose VARCHAR(30) NOT NULL,
    challenge VARCHAR(512) NOT NULL UNIQUE,
    request_json TEXT NOT NULL,
    rp_id VARCHAR(255),
    origin VARCHAR(255),
    expires_at TIMESTAMPTZ NOT NULL,
    is_used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at TIMESTAMPTZ,
    ip_address VARCHAR(45)
);

CREATE INDEX idx_portal_webauthn_user_purpose ON portal_webauthn_challenges(user_id, purpose);
CREATE INDEX idx_portal_webauthn_expires ON portal_webauthn_challenges(expires_at);
CREATE INDEX idx_portal_webauthn_rp_id ON portal_webauthn_challenges(rp_id);
