-- Pipeline de recrutamento (ATS): etapas por vaga, movimento do candidato entre etapas e
-- anotações internas da equipe sobre a candidatura.
--
-- Por que não um enum novo em job_applications: o estado atual (APPLIED/IN_REVIEW/ADVANCED/
-- NOT_SELECTED) é uma linha só, sem história. O ATS pede a etapa atual POR VAGA (cada vaga
-- pode ter um fluxo diferente), o registro de cada movimento com data e responsável, e nota
-- interna que o candidato jamais vê — três coisas que moram em tabelas próprias e deixam
-- job_applications como está.
--
-- Aditiva e idempotente: tabelas novas, ninguém depende delas ainda.
create table if not exists selection_stages (
	id varchar(64) not null,
	job_id varchar(255) not null,
	name varchar(120) not null,
	position int not null,
	created_at timestamp not null,
	primary key (id),
	unique key uk_selection_stage_name (job_id, name)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create index idx_selection_stages_job on selection_stages (job_id, position);

-- Um movimento por evento: avançar, voltar, reprovar. A etapa atual da candidatura é o último
-- movimento — nunca um campo solto, para não haver duas fontes de verdade.
create table if not exists stage_movements (
	id bigint auto_increment,
	application_id bigint not null,
	job_id varchar(255) not null,
	from_stage_id varchar(64) null,
	to_stage_id varchar(64) not null,
	-- REJECTED não é etapa: é um fim, com o motivo obrigatório gravado no movimento.
	kind varchar(32) not null,
	actor_id varchar(255) not null,
	note varchar(1000) null,
	moved_at timestamp not null,
	primary key (id),
	constraint ck_stage_movements_kind check (kind in ('ADVANCE', 'RETURN', 'REJECT')),
	constraint fk_stage_movements_application foreign key (application_id)
		references job_applications (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create index idx_stage_movements_application on stage_movements (application_id, moved_at);
create index idx_stage_movements_job_stage on stage_movements (job_id, to_stage_id);

-- Nota interna da equipe sobre a candidatura. Nunca sai pelo endpoint do candidato.
create table if not exists stage_notes (
	id bigint auto_increment,
	application_id bigint not null,
	author_id varchar(255) not null,
	body varchar(2000) not null,
	created_at timestamp not null,
	primary key (id),
	constraint fk_stage_notes_application foreign key (application_id)
		references job_applications (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create index idx_stage_notes_application on stage_notes (application_id, created_at);
