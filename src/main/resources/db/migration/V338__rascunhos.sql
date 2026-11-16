-- Rascunho do compositor: onde a pessoa parou de escrever, FORA de `posts` (não é post — não
-- aparece em feed, perfil nem busca). Um rascunho por pessoa: salvar de novo ATUALIZA o mesmo
-- rascunho; publicar, agendar ou descartar o apaga. Mídia anexada não entra (os arquivos só
-- sobem no momento de publicar); o resto do compositor viaja inteiro. Sem FK (MariaDB errno
-- 150 no ambiente da rede), tudo aditivo e idempotente.
create table if not exists draft_posts (
  id varchar(255) not null,
  user_id varchar(255) not null,
  post_type varchar(32) not null,
  content text,
  page_html longtext,
  article_body text,
  topic varchar(40),
  poll_options text,
  poll_multiple_choice boolean not null default false,
  poll_ends_at timestamp null,
  mentioned_ids text,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- A própria unicidade é a regra "um rascunho por pessoa": o upsert passa pelo user_id.
create unique index if not exists uk_draft_posts_user on draft_posts (user_id);
