-- Páginas de PDF renderizadas no servidor, como arquivos de imagem próprios.
--
-- O PDF é o único tipo de mídia que chega inteiro e não tem imagem para o classificador olhar: a
-- checagem pré-armazenamento só lê image/*, e a varredura de mídia só encontra imagem *reconhecida*
-- como imagem pelos prefixos `%thumb`, `%card` e `%avatar`. Um PDF com página explícita não tinha
-- nenhum ponto do caminho olhando o conteúdo.
--
-- Aqui cada página vira um objeto PNG no bucket, com chave `paginas/<mediaId>/pagina-<n>.png`, e a
-- linha correspondente nasce em `media_assets`: assim a página entra na varredura que já existe
-- (`media_safety_checks`) e no takedown que já existe — não é um segundo caminho de moderação.
--
-- `media_render_jobs` guarda o pedido e a tentativa. O id da linha é o id do asset de origem, o que
-- faz do pedido um só por PDF: quem chega depois encontra o trabalho já pedido, feito ou em erro.
create table if not exists media_render_jobs (
  media_id varchar(64) character set utf8mb4 collate utf8mb4_unicode_ci not null,
  owner_id varchar(255) character set utf8mb4 collate utf8mb4_unicode_ci not null,
  status varchar(20) character set utf8mb4 collate utf8mb4_unicode_ci not null,
  attempt int not null default 0,
  pages int not null default 0,
  -- chaves das páginas gravadas no bucket, separadas por vírgula (a página é achada pela chave)
  page_keys varchar(1000) character set utf8mb4 collate utf8mb4_unicode_ci null,
  last_error varchar(300) character set utf8mb4 collate utf8mb4_unicode_ci null,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (media_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- O worker de recuperação recolhe o que ainda está PENDING, do mais antigo para o mais novo.
create index if not exists idx_media_render_jobs_status on media_render_jobs (status, updated_at);
