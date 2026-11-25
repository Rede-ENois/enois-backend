-- Figurinhas da Comunidade (V376).
--
-- Catálogo comunitário, SEPARADO do pacote oficial "Figurinhas do Énóis" (que é do front, em
-- /emojis/enois/, e não passa pelo banco). Por isso não há nome nem código herdado daqui: o
-- pacote oficial nunca é tocado, e um código daqui nunca substitui um `enois_*`.
--
-- `ativo` é 1 enquanto a figurinha está no ar e NULL depois de desativada. É a metade da chave
-- única: duas ATIVAS não podem ter o mesmo código, mas depois de uma desativação o código fica
-- livre de novo (o banco aceita vários NULL na mesma coluna). `update_scope` guarda 0 no lugar do
-- NULL para o índice único não depender de índice funcional (que o MariaDB não tem).
create table if not exists figurinhas_comunidade (
	id varchar(64) not null,
	codigo varchar(64) not null,
	codigo_normalizado varchar(64) not null,
	nome varchar(80) not null,
	autor_id varchar(64) not null,
	media_id varchar(64) not null,
	image_url varchar(1024) not null,
	mime_type varchar(64) not null,
	largura int not null,
	altura int not null,
	bytes int not null,
	ativo tinyint null,
	update_scope tinyint not null default 0,
	criado_em timestamp not null,
	atualizado_em timestamp not null,
	desativado_em timestamp null,
	desativado_por varchar(64) null,
	motivo_desativacao varchar(500) null,
	primary key (id),
	constraint uk_figurinhas_comunidade_codigo_ativo unique (codigo, ativo)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Histórico da figurinha (cadastro, desativação): a linha da figurinha continua gravada depois de
-- sair do ar — o histórico é a prova de auditoria, com autor, ator e motivo.
create table if not exists figurinhas_comunidade_eventos (
	id varchar(64) not null,
	figurinha_id varchar(64) not null,
	tipo varchar(32) not null,
	ator_id varchar(64) null,
	detalhe varchar(1000) null,
	criado_em timestamp not null,
	primary key (id),
	key idx_figurinhas_comunidade_eventos_figurinha (figurinha_id, criado_em)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
