-- Ocultar reposts no feed pessoal: sem republicações puras e sem repost com opinião nas abas do feed.
-- Default false mantém o feed completo para quem já usa a conta.
alter table user_preferences
  add column if not exists hide_reposts boolean not null default false;
