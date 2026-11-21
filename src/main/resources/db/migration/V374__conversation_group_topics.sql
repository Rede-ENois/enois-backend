-- Topicos dentro do grupo de conversa (issue #915).
--
-- O QUE FALTAVA: um grupo de mensagem grande virava uma conversa so, com tudo misturado. Os
-- relatos de suporte pediam organizar a conversa por assunto, do jeito que o grupo de conteudo ja
-- faz com os proprios topicos.
--
-- O TOPICO PERTENCE A UMA CONVERSA (kind=GROUP). Nao tem chave estrangeira, como no resto do
-- projeto: o servico confere que o topico e daquela conversa antes de usar, e a migracao nao
-- depende de a conversa existir no momento do deploy.
--
-- A MENSAGEM APONTA PARA O TOPICO pela coluna `topic_id`, que fica nula nas mensagens sem topico
-- (e em todas as mensagens que ja existiam). Apagar a conversa leva os topicos dela: a lista de
-- topicos e do grupo, nao um registro global.

create table if not exists conversation_group_topics (
  id varchar(64) not null,
  conversation_id varchar(255) not null,
  name varchar(80) not null,
  position int not null default 0,
  -- OPEN | ARCHIVED (arquivado nao recebe mensagem nova; o historico continua legivel)
  status varchar(16) not null default 'OPEN',
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id),
  unique key uk_conversation_group_topics_name (conversation_id, name),
  key idx_conversation_group_topics_position (conversation_id, position)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

alter table messages
  add column if not exists topic_id varchar(64) null;
