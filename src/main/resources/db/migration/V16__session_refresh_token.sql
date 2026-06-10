ALTER TABLE auth_sessions ADD COLUMN refresh_token VARCHAR(255) AFTER token;
CREATE UNIQUE INDEX idx_auth_sessions_refresh_token ON auth_sessions(refresh_token);
