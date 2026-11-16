-- Colagem: o feed fotográfico do Explorar. Tabelas próprias, fora de `posts` — por construção
-- nada daqui entra nos feeds gerais (é um espaço só dele, quase um grupo de fotografias).
-- Sem FK (MariaDB errno 150 no ambiente da rede), tudo aditivo e idempotente.

create table if not exists colagem_posts (
  id varchar(64) not null,
  author_id varchar(64) not null,
  media_id varchar(64) not null,
  description varchar(500) null,
  media_spoiler boolean not null default false,
  created_at timestamp not null,
  status varchar(20) not null,
  removed_at timestamp null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists colagem_comments (
  id varchar(64) not null,
  post_id varchar(64) not null,
  author_id varchar(64) not null,
  parent_comment_id varchar(64) null,
  content varchar(800) null,
  media_id varchar(64) null,
  media_spoiler boolean not null default false,
  created_at timestamp not null,
  status varchar(20) not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Uma reação por pessoa por foto: a chave (post, pessoa) é o próprio id da linha.
create table if not exists colagem_reactions (
  id varchar(140) not null,
  post_id varchar(64) not null,
  user_id varchar(64) not null,
  reaction varchar(30) not null,
  created_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Feed: página por marca de tempo.
create index if not exists idx_colagem_posts_feed on colagem_posts (status, created_at);
-- Takedown de mídia: derrubar todas as fotos que usam a imagem removida.
create index if not exists idx_colagem_posts_media on colagem_posts (media_id);
create index if not exists idx_colagem_comments_media on colagem_comments (media_id);
create index if not exists idx_colagem_comments_post on colagem_comments (post_id, status, created_at);
-- Contagem em lote da página.
create index if not exists idx_colagem_reactions_post on colagem_reactions (post_id);
