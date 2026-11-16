-- Entrevistas: a unicidade passa a valer só para a entrevista AGENDADA da candidatura.
--
-- A V274 criou `uq_job_interviews_application` como único em (application_id, status). A intenção
-- era "uma entrevista valendo por candidatura", mas o índice também limitava a UMA linha
-- CANCELLED: o primeiro cancelamento ocupava a vaga e o segundo colidia, devolvendo 409 e
-- desfazendo o cancelamento (a entrevista seguia agendada nas duas agendas).
--
-- `active_application_id` vale o `application_id` enquanto a entrevista está SCHEDULED e fica NULL
-- nas demais situações; o índice único sobre a coluna anulável faz o papel de índice parcial (que
-- o MariaDB não tem) e o H2 dos testes aceita. É o mesmo padrão da V228.
--
-- Linhas existentes: nenhuma é apagada nem muda de situação. As SCHEDULED recebem o próprio
-- `application_id` na coluna nova e as CANCELLED ficam com NULL. Não há duplicata a sanear: o
-- índice antigo já impedia duas SCHEDULED na mesma candidatura.
--
-- Aditiva e idempotente: o índice único antigo é trocado por um comum nas mesmas colunas, que
-- continua servindo a busca "a entrevista agendada desta candidatura".
alter table job_interviews add column if not exists active_application_id bigint null;

update job_interviews
  set active_application_id = case when status = 'SCHEDULED' then application_id else null end;

create unique index if not exists uq_job_interviews_active_application
  on job_interviews (active_application_id);

drop index if exists uq_job_interviews_application on job_interviews;
create index if not exists idx_job_interviews_application_status
  on job_interviews (application_id, status);
