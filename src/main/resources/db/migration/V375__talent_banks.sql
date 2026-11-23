-- Banco de talentos individual: a vitrine de recrutamento que CADA recrutador publica e administra.
--
-- Diferente do banco por organizacao (V333, `talent_pool_opt_ins`), aqui a banca pertence a uma
-- pessoa recrutadora e e descoberta por qualquer conta logada. O par (banca, candidato) e unico:
-- ninguem entra duas vezes no mesmo banco, e a revogacao da pessoa nao apaga a linha — ela marca o
-- fim do acesso e a linha so sai no expurgo, porque o fato de ter consentido (e de ter retirado)
-- precisa continuar auditavel.
--
-- A inscricao guarda o que foi autorizado (campos compartilhados), a versao do termo de consentimento
-- e a data; o recruiter nunca le dado de perfil por fora dessa linha. Nenhum retrato do candidato e
-- copiado para ca: o que o recrutador ve e o perfil de hoje, pelas mesmas colunas que ele ja le em
-- qualquer outra tela, e apenas dos campos que a pessoa liberou.
--
-- `talent_bank_events` e a trilha de transicoes (quem pediu, quem confirmou, quem revogou), legivel
-- pelas duas partes; `audit_logs` guarda as operacoes sensiveis da administracao.
--
-- Aditiva e idempotente; sem FK para users de proposito (armadilha de collation do MariaDB).
create table if not exists talent_banks (
  id varchar(255) not null,
  owner_id varchar(255) not null,
  title varchar(120) not null,
  purpose varchar(600) not null,
  description varchar(1500) null,
  handling_conditions varchar(1500) null,
  focus_area varchar(120) null,
  desired_skills varchar(500) null,
  requested_fields varchar(300) not null,
  consent_version varchar(32) not null,
  discoverable boolean not null default false,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id),
  constraint uk_talent_banks_owner unique (owner_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- O painel do dono do banco.
create index if not exists idx_talent_banks_owner on talent_banks (owner_id, created_at, id);
-- A vitrine publica: so as bancas marcadas como visiveis entram aqui.
create index if not exists idx_talent_banks_public on talent_banks (discoverable, created_at, id);

create table if not exists talent_bank_enrollments (
  id varchar(255) not null,
  bank_id varchar(255) not null,
  candidate_id varchar(255) not null,
  state varchar(32) not null,
  consent_version varchar(32) not null,
  shared_fields varchar(300) not null,
  purpose_snapshot varchar(600) not null,
  note varchar(500) null,
  requested_at timestamp not null,
  consented_at timestamp null,
  activated_at timestamp null,
  revoked_at timestamp null,
  closed_at timestamp null,
  erased_at timestamp null,
  updated_at timestamp not null,
  primary key (id),
  constraint uk_talent_bank_enrollment unique (bank_id, candidate_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- O painel do candidato ("onde estou inscrito") e a busca por estado.
create index if not exists idx_talent_bank_enrollments_candidate on talent_bank_enrollments (candidate_id, updated_at);
-- A leitura do recrutador: as inscricoes da banca, das mais recentes para as mais antigas.
create index if not exists idx_talent_bank_enrollments_bank on talent_bank_enrollments (bank_id, state, consented_at, id);

create table if not exists talent_bank_events (
  id varchar(255) not null,
  bank_id varchar(255) not null,
  enrollment_id varchar(255) null,
  actor_id varchar(255) not null,
  action varchar(40) not null,
  detail varchar(500) null,
  created_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- A trilha que as duas partes podem ler.
create index if not exists idx_talent_bank_events_enrollment on talent_bank_events (enrollment_id, created_at);
create index if not exists idx_talent_bank_events_bank on talent_bank_events (bank_id, created_at);
