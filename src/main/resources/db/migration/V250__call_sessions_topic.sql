-- Sala "Tamo junto" por tópico do grupo: cada tópico tem a própria sala de vídeo, independente da
-- sala geral. A coluna é nula na sala geral (e em todas as 1:1), então o que já existe continua
-- exatamente como está.
--
-- Aditiva e idempotente: nasce nula e o índice só acelera a busca da sala aberta do tópico.
alter table call_sessions
  add column if not exists topic_id varchar(255) null;

create index if not exists idx_call_sessions_topic on call_sessions (group_id, topic_id, state);
