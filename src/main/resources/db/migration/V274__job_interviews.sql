-- Entrevista marcada pelo empregador para uma candidatura (projeto Énóis Calendar, fase 5).
--
-- A entrevista é o fato que gera o compromisso na agenda do candidato E na agenda de quem
-- publicou a vaga, pelas origens ("JOB_INTERVIEW", "entrevista", "candidato") e
-- ("JOB_INTERVIEW", "entrevista", "empregador") da porta de origem do calendário. Reagendar a
-- entrevista move as duas entradas em vez de criar outras, porque a origem não muda.
--
-- O fuso da entrevista é o de quem marcou; o instante é o que os dois lados compartilham.
--
-- Uma candidatura tem no máximo uma entrevista valendo: remarcar é UPDATE na mesma linha, e o
-- índice único barra uma segunda ativa se a regulação por código falhar. A linha cancelada fica
-- para o histórico (a agenda guarda o cancelamento, não a ausência).
--
-- Aditiva e idempotente: tabela nova, ninguém depende dela ainda.
create table if not exists job_interviews (
  id varchar(255) not null,
  application_id bigint not null,
  job_id varchar(255) not null,
  candidate_id varchar(255) not null,
  employer_id varchar(255) not null,
  starts_at timestamp not null,
  ends_at timestamp not null,
  zone_id varchar(64) not null,
  notes varchar(1000) null,
  status varchar(32) not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  cancelled_at timestamp null,
  primary key (id),
  constraint ck_job_interviews_status check (status in ('SCHEDULED', 'CANCELLED'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- A busca do agendamento é "a entrevista desta candidatura"; o índice único é o que garante uma
-- só entrevista valendo por candidatura (a cancelada libera a linha para uma nova).
create unique index uq_job_interviews_application
  on job_interviews (application_id, status);
create index idx_job_interviews_job on job_interviews (job_id);
