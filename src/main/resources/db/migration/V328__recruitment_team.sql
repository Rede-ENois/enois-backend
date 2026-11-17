-- Equipe de recrutamento da organizacao (ATS): quem conduz a selecao das vagas de uma pagina
-- empresarial e com qual papel.
--
-- Eixo separado de company_memberships: publicar na pagina (conteudo) e conduzir selecao
-- (recrutamento) sao trabalhos diferentes, com papeis diferentes. O dono da pagina comanda o
-- recrutamento dela sem registro aqui; o par (company_id, user_id) e unico.
--
-- Aditiva e idempotente; sem FK para users de proposito (armadilha de collation do MariaDB).
create table if not exists recruitment_members (
	id varchar(255) not null,
	company_id varchar(255) not null,
	user_id varchar(255) not null,
	role varchar(20) not null,
	created_at timestamp not null,
	primary key (id),
	unique key uk_recruitment_member (company_id, user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create index idx_recruitment_members_user on recruitment_members (user_id);
