-- Enquete (PostType.POLL): o titulo vive em posts.content (limite 400) e as alternativas/votos
-- moram aqui. Novas tabelas; nenhuma linha existente muda. O voto e unico e imutavel: a
-- unique (post_id, user_id) e a regra no banco — um voto por pessoa, sem troca.
create table if not exists poll_options (
  id varchar(64) not null,
  post_id varchar(64) not null,
  position int not null,
  text varchar(120) not null,
  primary key (id),
  unique key uk_poll_options_post_position (post_id, position)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists poll_votes (
  id varchar(64) not null,
  post_id varchar(64) not null,
  user_id varchar(64) not null,
  option_position int not null,
  created_at timestamp not null,
  primary key (id),
  unique key uk_poll_votes_post_user (post_id, user_id),
  key idx_poll_votes_post (post_id, option_position)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
