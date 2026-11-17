create table if not exists job_application_evaluations (
	id bigint not null auto_increment,
	application_id bigint not null,
	author_id varchar(255) not null,
	criteria_json mediumtext not null,
	verdict varchar(16) not null,
	summary varchar(2000) null,
	created_at timestamp not null,
	updated_at timestamp not null,
	primary key (id),
	constraint uk_job_application_evaluations_author unique (application_id, author_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

create index idx_job_application_evaluations_updated
	on job_application_evaluations (application_id, updated_at);
