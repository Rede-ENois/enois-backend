-- Sessões existentes mantêm seu escopo normal; novas sessões de consulta são identificadas explicitamente.
alter table auth_sessions add column if not exists sanctions_only boolean not null default false;
alter table auth_sessions add column if not exists sanctions_expires_at timestamp null;
