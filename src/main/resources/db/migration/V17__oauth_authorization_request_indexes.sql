CREATE INDEX IF NOT EXISTS idx_oauth_auth_requests_request_expires
    ON oauth_authorization_requests (request_expires_at);

CREATE INDEX IF NOT EXISTS idx_oauth_auth_requests_status_request_expires
    ON oauth_authorization_requests (status, request_expires_at);

CREATE INDEX IF NOT EXISTS idx_oauth_auth_requests_code_expires
    ON oauth_authorization_requests (code_expires_at);
