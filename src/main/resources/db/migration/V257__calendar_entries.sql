-- Agenda central do Énóis (projeto Énóis Calendar, Fase 1).
--
-- Todo compromisso é uma linha de `calendar_entries`, com início e fim em timestamp e o fuso do
-- organizador ao lado. O fuso é uma coluna à parte porque "toda terça às 19h" tem que continuar às
-- 19h depois da mudança de horário: o instante sozinho não guarda a regra.
--
-- `scope` separa as duas agendas (social e profissional) e serve de filtro da agenda consolidada.
-- `kind` diz o que o compromisso é para a tela; a regra de cada tipo (entrevista, mentoria, sala)
-- continua no módulo dono dele. `source_type`/`source_id` dizem de onde a entrada veio.
--
-- Aditiva e idempotente: tabela nova, ninguém depende dela ainda, e um rollback do binário deixa a
-- tabela vazia sem efeito no app.
create table if not exists calendar_entries (
  id varchar(255) not null,
  owner_id varchar(255) not null,
  scope varchar(16) not null,
  kind varchar(24) not null,
  title varchar(200) not null,
  description varchar(2000) null,
  starts_at timestamp not null,
  ends_at timestamp not null,
  time_zone_id varchar(64) not null,
  all_day boolean not null default false,
  state varchar(16) not null,
  source_type varchar(24) null,
  source_id varchar(255) null,
  created_at timestamp not null,
  updated_at timestamp not null,
  cancelled_at timestamp null,
  primary key (id),
  constraint ck_calendar_entries_interval check (ends_at > starts_at),
  constraint ck_calendar_entries_zone check (time_zone_id <> ''),
  constraint ck_calendar_entries_source check (
    (source_type is null and source_id is null) or (source_type is not null and source_id is not null)
  )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Consulta da agenda: por dono, por janela de tempo e excluindo o que foi cancelado. As três
-- colunas vêm na ordem em que a consulta filtra.
create index idx_calendar_entries_owner_start on calendar_entries (owner_id, starts_at, state);

-- Chegada de outra ponta (convite, módulo dono do compromisso) a partir da origem.
create index idx_calendar_entries_source on calendar_entries (source_type, source_id);
