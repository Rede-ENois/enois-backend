-- Extensoes da enquete: multipla escolha e prazo de encerramento. A configuracao mora numa
-- tabela propria, uma linha por enquete; enquetes antigas ficam sem linha e leem como escolha
-- unica e sem prazo (nenhuma linha existente muda).
create table if not exists poll_configs (
  post_id varchar(64) not null,
  multiple_choice boolean not null default false,
  ends_at timestamp null,
  primary key (post_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- O voto passa a poder ser trocado enquanto a enquete esta aberta: a unicidade muda de
-- (post_id, user_id) para (post_id, user_id, option_position) — as linhas antigas (uma por
-- pessoa) continuam validas no novo indice, no molde da migracao V72 (post_seals).
alter table poll_votes drop index if exists uk_poll_votes_post_user;
create unique index if not exists uk_poll_votes_post_user_option on poll_votes (post_id, user_id, option_position);
