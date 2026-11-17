-- Progresso dos jogos vinculado à conta (issue "Proposta: vincular o progresso dos jogos à
-- conta do Énóis"): trocar de aparelho ou limpar dados não pode perder histórico. Estado
-- genérico por jogo+usuário — cada jogo guarda o que precisa (JSON do cliente), o backend não
-- interpreta. "Último estado vence": o PUT substitui o estado inteiro. Sem FK para users/games
-- pelo motivo de sempre (collation de users difere do default). Tudo aditivo e idempotente.
create table if not exists game_progress (
  id varchar(64) not null,
  game_id varchar(64) not null,
  user_id varchar(64) not null,
  state longtext not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id),
  unique key uk_game_progress_user (game_id, user_id),
  key idx_game_progress_user (user_id, updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
