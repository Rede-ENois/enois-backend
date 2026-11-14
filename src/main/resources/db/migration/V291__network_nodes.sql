-- Énóis Distributed Network (fase 1): identidade dos nós da rede cooperativa.
--
-- A rede permite que servidores autorizados de terceiros executem trabalho do Énóis. Cada nó tem
-- UMA chave de API, gerada por um administrador na tela de administração, exibida uma única vez e
-- persistida só como hash (o mesmo padrão de Hashes.sha256Hex). O nó também recebe um token de
-- provisionamento de uso único para o enroll — separado da chave de operação porque vazamentos
-- têm ciclos de vida diferentes.
--
-- Regras de desenho (docs/rede-distribuida/04-identidade-e-autorizacao.md):
-- - nenhum nó externo recebe credencial do banco principal; esta tabela é lida só pelo backend;
-- - revogação é terminal para a chave, e REVOKED é terminal para o nó (voltar exige cadastro novo);
-- - toda ação administrativa (emissão, revogação, aprovação) grava em audit_logs.

create table if not exists network_nodes (
  id varchar(40) not null,
  display_name varchar(120) not null,
  owner_user_id varchar(40) not null,
  status varchar(24) not null default 'PENDING_APPROVAL',
  profiles varchar(255) not null default '',
  declared_cpu_cores int not null default 0,
  declared_memory_mb int not null default 0,
  declared_disk_mb int not null default 0,
  declared_bandwidth_mbps int not null default 0,
  observed_cpu_percent double null,
  observed_memory_mb int null,
  observed_running_tasks int null,
  region varchar(64) null,
  country varchar(2) null,
  runtime_version varchar(32) null,
  approved_by varchar(40) null,
  approved_at timestamp null,
  revoked_at timestamp null,
  created_at timestamp not null,
  last_seen_at timestamp null,
  primary key (id),
  key idx_network_nodes_status (status),
  key idx_network_nodes_owner (owner_user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Uma chave ativa por nó: a emissão revoga a anterior dentro da mesma transação, e o índice
-- (node_id, revoked_at) é o que a leitura "chave vigente" usa.
create table if not exists network_node_keys (
  id varchar(40) not null,
  node_id varchar(40) not null,
  key_hash varchar(64) not null,
  key_prefix varchar(16) not null,
  created_by varchar(40) not null,
  created_at timestamp not null,
  last_used_at timestamp null,
  revoked_at timestamp null,
  revoked_by varchar(40) null,
  primary key (id),
  unique key uk_network_node_keys_hash (key_hash),
  key idx_network_node_keys_node (node_id, revoked_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Token de provisionamento de uso único (ADR-003): cria o nó; a chave opera o nó.
create table if not exists network_provision_tokens (
  token_hash varchar(64) not null,
  display_name varchar(120) not null,
  owner_user_id varchar(40) not null,
  profiles varchar(255) not null default '',
  expires_at timestamp not null,
  used_at timestamp null,
  created_by varchar(40) not null,
  created_at timestamp not null,
  primary key (token_hash)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
