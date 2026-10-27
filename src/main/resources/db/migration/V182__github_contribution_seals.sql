-- Selos de contribuição: identidade GitHub, evidências (PR e issue) e histórico auditável.
-- A identidade é vinculada pelo OAuth oficial e guarda o ID numérico imutável do GitHub; o login
-- é só apresentação e pode mudar. Nada aqui guarda token nem dado pessoal além do necessário.

create table if not exists github_identities (
  id varchar(64) not null,
  user_id varchar(64) not null,
  github_user_id varchar(64) not null,
  github_login varchar(100) not null,
  linked_at timestamp not null,
  last_synced_at timestamp null,
  sync_state varchar(20) null,
  last_sync_error varchar(1000) null,
  primary key (id),
  unique key uk_github_identities_user (user_id),
  unique key uk_github_identities_external (github_user_id),
  key idx_github_identities_login (github_login)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists github_repositories (
  repository varchar(200) not null,
  enabled boolean not null default true,
  added_at timestamp not null,
  last_synced_at timestamp null,
  primary key (repository)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

insert ignore into github_repositories (repository, enabled, added_at) values
  ('Rede-ENois/enois-backend', true, current_timestamp),
  ('Rede-ENois/enois-frontend', true, current_timestamp);

-- Uma linha por objeto externo (PR ou issue). A chave única impede contagem dupla quando o mesmo
-- objeto aparece em sincronizações, páginas ou reprocessamentos diferentes.
create table if not exists github_contributions (
  id varchar(64) not null,
  user_id varchar(64) null,
  github_user_id varchar(64) not null,
  github_login varchar(100) not null,
  repository varchar(200) not null,
  kind varchar(20) not null,
  object_number int not null,
  object_id varchar(64) null,
  title varchar(500) not null,
  url varchar(500) not null,
  state varchar(20) not null,
  opened_at timestamp null,
  merged_at timestamp null,
  closed_at timestamp null,
  github_updated_at timestamp null,
  details_fetched boolean not null default false,
  additions int not null default 0,
  deletions int not null default 0,
  changed_files int not null default 0,
  files_json longtext null,
  labels varchar(1000) null,
  reviews_count int not null default 0,
  approvals_count int not null default 0,
  changes_requested_count int not null default 0,
  has_tests boolean not null default false,
  reverted boolean not null default false,
  category varchar(40) not null,
  evaluation_status varchar(24) not null,
  score int not null default 0,
  signals_json longtext null,
  review_note varchar(2000) null,
  reviewed_by varchar(64) null,
  reviewed_at timestamp null,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id),
  unique key uk_github_contributions_object (repository, kind, object_number),
  key idx_github_contributions_user (user_id),
  key idx_github_contributions_status (evaluation_status, kind),
  key idx_github_contributions_github (github_user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists github_contribution_events (
  id varchar(64) not null,
  contribution_id varchar(64) not null,
  actor_user_id varchar(64) null,
  action varchar(40) not null,
  from_status varchar(24) null,
  to_status varchar(24) null,
  note varchar(1000) null,
  created_at timestamp not null,
  primary key (id),
  key idx_github_contribution_events_contribution (contribution_id, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Um selo por (usuário, tipo). A pessoa exibe só o nível mais alto de cada categoria; quando sobe
-- ou desce de nível, o anterior é revogado e o evento fica no histórico.
create table if not exists user_seals (
  id varchar(64) not null,
  user_id varchar(64) not null,
  seal_type varchar(40) not null,
  category varchar(40) not null,
  status varchar(20) not null,
  granted_at timestamp not null,
  granted_by varchar(64) null,
  revoked_at timestamp null,
  revoked_by varchar(64) null,
  revoke_reason varchar(1000) null,
  evidence_count int not null default 0,
  primary key (id),
  unique key uk_user_seals_user_type (user_id, seal_type),
  key idx_user_seals_type_status (seal_type, status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists user_seal_events (
  id varchar(64) not null,
  user_id varchar(64) not null,
  seal_type varchar(40) not null,
  action varchar(40) not null,
  actor_user_id varchar(64) null,
  note varchar(1000) null,
  created_at timestamp not null,
  primary key (id),
  key idx_user_seal_events_user (user_id, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
