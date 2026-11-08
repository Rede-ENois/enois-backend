-- Sala "Tamo junto" de um encontro: o evento passa a ser um vínculo de chamada, junto com a
-- conversa (1:1) e o grupo. A coluna é nula em toda chamada que já existe, então nada muda para
-- quem usa conversa ou grupo — só a sala do evento nasce com ela preenchida.
--
-- Aditiva e idempotente: coluna e índice nascem vazios; o índice só acelera a busca da sala aberta
-- do encontro.
alter table call_sessions
  add column if not exists event_id varchar(255) null;

create index if not exists idx_call_sessions_event on call_sessions (event_id, state);
