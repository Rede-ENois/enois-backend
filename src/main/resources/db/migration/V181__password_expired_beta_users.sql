-- Flag reutilizavel: qualquer conta marcada com reset_needed = true precisa trocar a senha no login.
alter table user_credentials add column if not exists reset_needed boolean not null default false;

-- Politica de seguranca: beta users (antes de 15/out/2026) trocam a senha.
-- Contas OAuth (password_usable = false) nao sao afetadas.
update user_credentials set reset_needed = true
where password_usable = true
  and user_id in (select id from users where created_at < '2026-10-15 00:00:00');

-- Desativa 2FA (segredos podem estar com chave antiga)
update users set totp_enabled_at = null, email_otp_enabled_at = null
where created_at < '2026-10-15 00:00:00';

delete from user_two_factor_secrets
where user_id in (select id from users where created_at < '2026-10-15 00:00:00');

delete from two_factor_challenges
where user_id in (select id from users where created_at < '2026-10-15 00:00:00');

delete from two_factor_backup_codes
where user_id in (select id from users where created_at < '2026-10-15 00:00:00');

-- Revoga sessoes (forca relogin imediato)
delete from auth_sessions
where user_id in (select id from users where created_at < '2026-10-15 00:00:00');
