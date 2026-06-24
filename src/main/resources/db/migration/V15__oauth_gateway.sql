CREATE TABLE IF NOT EXISTS oauth_clients (
    id BIGSERIAL PRIMARY KEY,
    client_id VARCHAR(80) NOT NULL UNIQUE,
    client_secret_hash VARCHAR(128),
    display_name VARCHAR(160) NOT NULL,
    client_type VARCHAR(40) NOT NULL,
    owner_type VARCHAR(40) NOT NULL,
    owner_id VARCHAR(120),
    owner_handle VARCHAR(120),
    redirect_uris TEXT NOT NULL DEFAULT '[]',
    allowed_scopes TEXT NOT NULL DEFAULT '[]',
    allowed_environments VARCHAR(80) NOT NULL DEFAULT 'SANDBOX',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    mcp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    live_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    secret_rotated_at TIMESTAMP,
    revoked_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_oauth_clients_owner ON oauth_clients(owner_type, owner_id, owner_handle);
CREATE INDEX IF NOT EXISTS idx_oauth_clients_active ON oauth_clients(active);

CREATE TABLE IF NOT EXISTS oauth_tokens (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    refresh_token_hash VARCHAR(128) UNIQUE,
    client_id VARCHAR(80) NOT NULL,
    subject VARCHAR(160) NOT NULL,
    audience VARCHAR(80) NOT NULL,
    scopes TEXT NOT NULL,
    owner_type VARCHAR(40) NOT NULL,
    owner_id VARCHAR(120),
    owner_handle VARCHAR(120),
    environment VARCHAR(20) NOT NULL,
    grant_type VARCHAR(40) NOT NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    refresh_expires_at TIMESTAMP,
    revoked_at TIMESTAMP,
    revoke_reason VARCHAR(160)
);

CREATE INDEX IF NOT EXISTS idx_oauth_tokens_client ON oauth_tokens(client_id);
CREATE INDEX IF NOT EXISTS idx_oauth_tokens_subject ON oauth_tokens(subject);
CREATE INDEX IF NOT EXISTS idx_oauth_tokens_expires ON oauth_tokens(expires_at);
CREATE INDEX IF NOT EXISTS idx_oauth_tokens_refresh_hash ON oauth_tokens(refresh_token_hash);

CREATE TABLE IF NOT EXISTS oauth_settings (
    setting_key VARCHAR(80) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(160),
    note VARCHAR(255)
);

INSERT INTO oauth_settings(setting_key, enabled, note)
VALUES
    ('oauth.global', FALSE, 'Global OAuth issuance and validation gate'),
    ('mcp.global', FALSE, 'Remote MCP gateway gate'),
    ('mcp.mutations', FALSE, 'Remote MCP write tools gate'),
    ('environment.sandbox', TRUE, 'Sandbox OAuth access gate'),
    ('environment.live', FALSE, 'Live OAuth access gate'),
    ('owner.NEPTUNE', TRUE, 'Neptune owner access gate'),
    ('owner.MERCHANT', FALSE, 'Merchant OAuth access gate'),
    ('owner.BANK', FALSE, 'Bank OAuth access gate'),
    ('owner.CUSTOMER', FALSE, 'Customer OAuth access gate')
ON CONFLICT (setting_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS oauth_user_grants (
    id BIGSERIAL PRIMARY KEY,
    subject VARCHAR(160) NOT NULL,
    client_id VARCHAR(80) NOT NULL,
    scopes TEXT NOT NULL,
    owner_type VARCHAR(40) NOT NULL,
    owner_id VARCHAR(120),
    owner_handle VARCHAR(120),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP,
    revoked_by VARCHAR(160)
);

CREATE INDEX IF NOT EXISTS idx_oauth_user_grants_subject ON oauth_user_grants(subject);
CREATE INDEX IF NOT EXISTS idx_oauth_user_grants_client ON oauth_user_grants(client_id);

CREATE TABLE IF NOT EXISTS oauth_mcp_audit_events (
    id BIGSERIAL PRIMARY KEY,
    client_id VARCHAR(80),
    subject VARCHAR(160),
    owner_type VARCHAR(40),
    owner_id VARCHAR(120),
    owner_handle VARCHAR(120),
    scopes TEXT,
    tool_name VARCHAR(120) NOT NULL,
    result_status VARCHAR(40) NOT NULL,
    metadata TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_oauth_mcp_audit_client ON oauth_mcp_audit_events(client_id);
CREATE INDEX IF NOT EXISTS idx_oauth_mcp_audit_created ON oauth_mcp_audit_events(created_at);
