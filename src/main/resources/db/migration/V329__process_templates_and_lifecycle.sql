-- ATS incremento 4: modelos de processo seletivo e ciclo de vida configurável.
--
-- Modelo = biblioteca pessoal de fluxos de etapas. Aplicar o modelo numa vaga copia as etapas
-- para selection_stages — a vaga continua dona do próprio fluxo (editar o modelo depois não
-- muda vaga já configurada). Ciclo de vida ganha prazo de candidatura (application_deadline em
-- jobs); reabrir/encerrar não precisam de coluna nova (status/closed_at já existem).
--
-- Aditiva e idempotente.
create table if not exists selection_process_templates (
	id varchar(64) not null,
	owner_id varchar(255) not null,
	name varchar(120) not null,
	stages_json mediumtext not null,
	created_at timestamp not null,
	updated_at timestamp not null,
	primary key (id),
	unique key uk_process_template_owner_name (owner_id, name)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create index idx_process_templates_owner on selection_process_templates (owner_id, updated_at);

alter table jobs add column if not exists application_deadline timestamp null;
