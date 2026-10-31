-- Sugestoes da comunidade: ideias de melhoria que, depois de aprovadas por um moderador, viram
-- issue num repositorio PUBLICO do GitHub (backend, frontend ou design system).
--
-- A issue publica nunca carrega nome, e-mail nem id de quem sugeriu: o autor fica so aqui, para
-- limite diario e historico proprio. Por isso esta tabela nao guarda e-mail de contato.
create table if not exists suggestions (
  id varchar(64) not null,
  user_id varchar(64) not null,
  target varchar(24) not null,
  title varchar(120) not null,
  body varchar(4000) not null,
  status varchar(16) not null,
  created_at timestamp not null,
  reviewed_by varchar(64) null,
  reviewed_at timestamp null,
  github_issue_number int null,
  github_issue_url varchar(255) null,
  primary key (id),
  key idx_suggestions_status_created (status, created_at),
  key idx_suggestions_user_created (user_id, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
