-- Rascunhos em lista (issue do blog "Rascunhos no blog"): quem escreve mais de um texto não
-- pode perder material. O rascunho deixa de ser um por pessoa: a constraint única de user_id
-- sai (múltiplos rascunhos por conta) e a consulta da lista ganha índice próprio. Tudo
-- idempotente; nada de FK (MariaDB errno 150 no ambiente da rede).
drop index if exists uk_draft_posts_user on draft_posts;
create index if not exists idx_draft_posts_user_updated on draft_posts (user_id, updated_at desc);
