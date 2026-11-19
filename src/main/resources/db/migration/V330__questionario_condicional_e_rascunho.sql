-- Questionário da candidatura: lógica condicional nos campos já existentes e rascunho que se salva
-- enquanto a pessoa responde.
--
-- `visible_if_field_id`/`visible_if_value`: o campo só entra no formulário quando a resposta do
-- campo de origem casa com o valor declarado (casagem exata, normalizada). Quem responde "Sim" em
-- "Tem disponibilidade para viagem?" vê o campo seguinte; quem responde "Não" não o vê — e a
-- validação de obrigatório só alcança o que está visível.
--
-- O rascunho é UM por par (candidato, vaga): upsert no autosave, sem histórico — o que vale é o
-- último estado digitado. Apagar a candidatura enviada não apaga o rascunho de outrém porque ele
-- morre quando o envio acontece (a leitura própria devolve o que ainda não virou candidatura).
--
-- Mesmos cuidados de sempre: `if not exists`, aditivo, charset/collation explícitos, sem FK entre
-- collations diferentes.
alter table job_application_fields add column if not exists visible_if_field_id varchar(255) null;
alter table job_application_fields add column if not exists visible_if_value varchar(200) null;

create table if not exists job_application_drafts (
  job_id varchar(255) not null,
  user_id varchar(255) not null,
  answers_json mediumtext not null,
  updated_at timestamp not null,
  primary key (job_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

create index idx_application_drafts_user on job_application_drafts (user_id, updated_at);
