ALTER TABLE oauth_tokens
    ADD COLUMN IF NOT EXISTS subject_role VARCHAR(40);

ALTER TABLE oauth_tokens
    ADD COLUMN IF NOT EXISTS grant_id BIGINT;

ALTER TABLE oauth_user_grants
    ADD COLUMN IF NOT EXISTS audience VARCHAR(80) NOT NULL DEFAULT 'astro';

ALTER TABLE oauth_user_grants
    ADD COLUMN IF NOT EXISTS environment VARCHAR(20) NOT NULL DEFAULT 'SANDBOX';

ALTER TABLE oauth_user_grants
    ADD COLUMN IF NOT EXISTS approved_by VARCHAR(160);

CREATE TABLE IF NOT EXISTS oauth_authorization_requests (
    request_id VARCHAR(80) PRIMARY KEY,
    client_id VARCHAR(80) NOT NULL,
    subject VARCHAR(160),
    subject_role VARCHAR(40),
    redirect_uri VARCHAR(500) NOT NULL,
    scopes TEXT NOT NULL,
    audience VARCHAR(80) NOT NULL DEFAULT 'astro',
    environment VARCHAR(20) NOT NULL DEFAULT 'SANDBOX',
    state VARCHAR(500),
    code_challenge VARCHAR(160) NOT NULL,
    code_challenge_method VARCHAR(20) NOT NULL DEFAULT 'S256',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    authorization_code_hash VARCHAR(128) UNIQUE,
    request_expires_at TIMESTAMP NOT NULL,
    code_expires_at TIMESTAMP,
    approved_at TIMESTAMP,
    rejected_at TIMESTAMP,
    exchanged_at TIMESTAMP,
    grant_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_oauth_auth_requests_client ON oauth_authorization_requests(client_id);
CREATE INDEX IF NOT EXISTS idx_oauth_auth_requests_subject ON oauth_authorization_requests(subject);
CREATE INDEX IF NOT EXISTS idx_oauth_auth_requests_status ON oauth_authorization_requests(status);
CREATE INDEX IF NOT EXISTS idx_oauth_auth_requests_code_hash ON oauth_authorization_requests(authorization_code_hash);
