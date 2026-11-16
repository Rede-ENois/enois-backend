-- Desabafos: o espaço acolhedor de texto do Explorar. Tabelas próprias, FORA de `posts` —
-- por construção nada daqui entra nos feeds gerais, no perfil, na busca, no diário nem nos
-- contadores (é um espaço só dele, quase um grupo). Comentários e reações também são próprios:
-- reação mostra só a contagem, nunca a lista de quem interagiu. Sem FK (MariaDB errno 150 no
-- ambiente da rede), tudo aditivo e idempotente.

create table if not exists desabafos_posts (
  id varchar(64) not null,
  author_id varchar(64) not null,
  content text not null,
  created_at timestamp not null,
  status varchar(20) not null,
  removed_at timestamp null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists desabafos_comments (
  id varchar(64) not null,
  post_id varchar(64) not null,
  author_id varchar(64) not null,
  content varchar(800) not null,
  created_at timestamp not null,
  status varchar(20) not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Uma reação por pessoa por desabafo: a chave (post, pessoa) é o próprio id da linha.
create table if not exists desabafos_reactions (
  id varchar(140) not null,
  post_id varchar(64) not null,
  user_id varchar(64) not null,
  reaction varchar(30) not null,
  created_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Feed: página por marca de tempo. Comentários: tudo do desabafo, do mais antigo ao mais novo.
create index if not exists idx_desabafos_posts_feed on desabafos_posts (created_at);
create index if not exists idx_desabafos_comments_post on desabafos_comments (post_id, created_at);
create index if not exists idx_desabafos_reactions_post on desabafos_reactions (post_id);
