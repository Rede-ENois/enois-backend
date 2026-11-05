-- Silenciamento por tempo: `expires_at` nulo continua sendo "até reativar" (o comportamento antigo).
-- As linhas antigas ficam nulas, então nada muda para quem já tinha silenciado alguém.
alter table user_mutes
  add column expires_at timestamp null;
