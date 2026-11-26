-- Portfólio profissional: a vitrine que CADA pessoa monta para mostrar trabalho.
--
-- A tabela `portfolio_pages` guarda o estado EDITÁVEL da página do dono: `draft_html` é o que ele
-- está montando agora, `published_version` diz qual versão está no ar (0 = nunca publicado) e
-- `published_at` quando. A página inteira usa o mesmo formato de HTML do resto do enois — limpeza
-- por SafeHtml na gravação, teto de 200.000 caracteres, render em quadro isolado sem scripts.
-- Não existe uma segunda linguagem de página.
--
-- `portfolio_publications` é a trilha: uma linha por publicação, com o HTML que estava no ar, as
-- peças e a autoria naquele instante. Como migração aplicada não se edita, a fase seguinte de
-- "desfazer" pode ler desta tabela sem inventar histórico retroativo.
--
-- `portfolio_blocks` é a lista ordenada de peças do rascunho (`PROJECT`, `TEXT`, `MEDIA`, `PDF`,
-- `LINK`). O bloco de projeto REFERENCIA `user_projects` por id — não copia o card — para a
-- reordenação no portfólio não mudar a ordem do perfil. `published_version` null = peça que ainda
-- nunca foi publicada.
--
-- Aditiva e idempotente; sem FK para users de propósito (armadilha de collation do MariaDB).
create table if not exists portfolio_pages (
  user_id varchar(255) character set utf8mb4 collate utf8mb4_unicode_ci not null,
  draft_html longtext,
  published_version int not null default 0,
  published_at timestamp null,
  updated_at timestamp not null,
  primary key (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists portfolio_publications (
  id varchar(40) character set utf8mb4 collate utf8mb4_unicode_ci not null,
  user_id varchar(255) character set utf8mb4 collate utf8mb4_unicode_ci not null,
  version int not null,
  html longtext,
  -- JSON: [{position, type, title, text, projectId, mediaId, linkUrl}]
  blocks longtext,
  published_at timestamp not null,
  primary key (id),
  constraint uk_portfolio_publications_version unique (user_id, version)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists portfolio_blocks (
  id varchar(40) character set utf8mb4 collate utf8mb4_unicode_ci not null,
  user_id varchar(255) character set utf8mb4 collate utf8mb4_unicode_ci not null,
  position int not null,
  block_type varchar(20) character set utf8mb4 collate utf8mb4_unicode_ci not null,
  title varchar(160) character set utf8mb4 collate utf8mb4_unicode_ci null,
  `text` varchar(4000) character set utf8mb4 collate utf8mb4_unicode_ci null,
  project_id varchar(40) character set utf8mb4 collate utf8mb4_unicode_ci null,
  media_id varchar(64) character set utf8mb4 collate utf8mb4_unicode_ci null,
  link_url varchar(1000) character set utf8mb4 collate utf8mb4_unicode_ci null,
  published_version int null,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- O editor do dono lê a página e os blocos juntos.
create index if not exists idx_portfolio_blocks_owner on portfolio_blocks (user_id, position, id);
-- A trilha é lida por dono, do mais recente para o mais antigo.
create index if not exists idx_portfolio_publications_owner on portfolio_publications (user_id, version);
