-- Candidatura a vaga EXTERNA enviada pelo Énóis (fase 3 do agregador).
--
-- Por que tabela própria (e não job_applications): a candidatura interna pertence a uma vaga
-- publicada na plataforma, tem campos de formulário e estado que o EMPREGADOR move. Aqui a
-- candidatura é um e-mail enviado à caixa da origem — não há triagem dentro do Énóis, só o
-- comprovante de que foi enviado (e se o currículo foi anexado de verdade).
--
-- O currículo NUNCA é guardado aqui: só o nome do arquivo e se o anexo saiu. O documento
-- continua morando no CDN da conta — o Énóis não vira repositório de currículos.

create table if not exists external_job_applications (
	id varchar(64) not null,
	external_job_id varchar(64) not null,
	user_id varchar(64) not null,
	recipient_email varchar(320) not null,
	subject varchar(500) not null,
	body mediumtext not null,
	resume_attached boolean not null default false,
	resume_name varchar(200) null,
	-- PENDING enquanto o e-mail esta a caminho; SENT ou FAILED ao fim do envio. A reserva existe
	-- para a chave unica barrar a segunda requisicao antes de o SMTP ser chamado.
	status varchar(20) not null,
	error varchar(500) null,
	applied_at timestamp not null,
	primary key (id),
	unique key uk_external_job_application (external_job_id, user_id),
	key idx_external_job_applications_user (user_id, applied_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
