-- Verificacao de identidade progressiva (Didit).
--
-- A tabela guarda APENAS a decisao operacional: quem foi liberado, em que rodada, com que
-- credibilidade e quando. Nenhum documento, selfie, dado biometrico ou payload da Didit entra
-- aqui — a verificacao em si acontece no provedor e o que volta e so o estado.
create table if not exists didit_verification_grants (
  user_id varchar(255) not null,
  round_id varchar(255),
  reputation_at_grant int not null,
  granted_at timestamp not null,
  consumed_at timestamp null,
  revoked_at timestamp null,
  primary key (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Uma rodada por linha, com a faixa do ranking que ela cobriu: e o registro auditavel de que
-- ninguem foi liberado fora de ordem nem a mao.
create table if not exists didit_release_rounds (
  id varchar(255) not null,
  executed_at timestamp not null,
  eligible_count int not null,
  released_count int not null,
  min_reputation int not null,
  rank_start int not null,
  rank_end int not null,
  state varchar(20) not null,
  reserved_capacity_used int not null,
  note varchar(500) null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create index idx_didit_release_rounds_executed on didit_release_rounds (executed_at);

-- Configuracao vigente em uma unica linha (id fixo 'default'): os numeros da liberacao sao
-- ajustaveis pela administracao e toda mudanca passa pelo log de auditoria.
create table if not exists didit_release_settings (
  id varchar(32) not null,
  users_per_round int not null,
  interval_minutes int not null,
  min_reputation int not null,
  reserved_quota_percent int not null,
  max_simultaneous int not null,
  paused boolean not null,
  next_round_at timestamp null,
  updated_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;