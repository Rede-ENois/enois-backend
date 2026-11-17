-- Confessionário: o espaço de histórias do Explorar — experiências, situações constrangedoras,
-- confissões e "nunca contei isso pra ninguém". Diferente dos Desabafos (que acolhem o que dói),
-- aqui o tom é contar: o que interessa é a história, não o pedido de apoio. Tabelas próprias,
-- FORA de `posts` — por construção nada daqui entra nos feeds gerais, no perfil, na busca, no
-- diário nem nos contadores. Autoria fica só no vínculo interno (moderação, auditoria e
-- penalidade); o contrato público é anônimo, como nos Desabafos. Sem FK (MariaDB errno 150 no
-- ambiente da rede), tudo aditivo e idempotente.

create table if not exists confessionario_posts (
  id varchar(64) not null,
  author_id varchar(64) not null,
  content text not null,
  created_at timestamp not null,
  status varchar(20) not null,
  removed_at timestamp null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists confessionario_comments (
  id varchar(64) not null,
  post_id varchar(64) not null,
  author_id varchar(64) not null,
  content varchar(800) not null,
  created_at timestamp not null,
  status varchar(20) not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Uma reação por pessoa por confissão: a chave (post, pessoa) é o próprio id da linha.
create table if not exists confessionario_reactions (
  id varchar(140) not null,
  post_id varchar(64) not null,
  user_id varchar(64) not null,
  reaction varchar(30) not null,
  created_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Feed: página por marca de tempo. Comentários: tudo da confissão, do mais antigo ao mais novo.
create index if not exists idx_confessionario_posts_feed on confessionario_posts (created_at);
create index if not exists idx_confessionario_comments_post on confessionario_comments (post_id, created_at);
create index if not exists idx_confessionario_reactions_post on confessionario_reactions (post_id);
