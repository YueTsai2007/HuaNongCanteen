CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY NOT NULL,
  email TEXT NOT NULL,
  password_salt TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS users_email_unique ON users(email);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY NOT NULL,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS sessions_user_expiry_idx ON sessions(user_id, expires_at);

CREATE TABLE IF NOT EXISTS user_state (
  user_id TEXT PRIMARY KEY NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  revision INTEGER NOT NULL,
  payload_json TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_rate_limits (
  key_hash TEXT PRIMARY KEY NOT NULL,
  window_started_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS auth_rate_limits_window_idx ON auth_rate_limits(window_started_at);

