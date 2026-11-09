-- Origem única por dono (projeto Énóis Calendar, Fase 1).
--
-- A D3 promete que criar a entrada de uma mentoria duas vezes não gera dois compromissos, e o
-- mecanismo é o banco recusando o duplicado. A V257 criou um índice COMUM em (source_type,
-- source_id), que acelera a busca mas não impede nada: dois cliques, ou o retry de um cliente,
-- gravavam duas linhas e a agenda mostrava a sessão duas vezes.
--
-- O índice é por (owner_id, source_type, source_id) porque a origem viaja no serviço de aplicação e
-- o dono da entrada é quem organiza: duas pessoas não têm a mesma linha de origem, mas a busca do
-- serviço é sempre "a entrada deste dono com esta origem".
--
-- No MySQL/MariaDB valores nulos não colidem em índice único, então as entradas que nascem no
-- próprio calendário (origem nula) continuam podendo existir quantas forem.
--
-- Aditiva e idempotente: o índice comum é substituído pelo único, e repetir a migração não muda
-- nada. Se houver duplicata antiga, a criação falha alto em vez de escolher uma linha em silêncio.
drop index if exists idx_calendar_entries_source on calendar_entries;
create unique index if not exists uq_calendar_entries_source
  on calendar_entries (owner_id, source_type, source_id);
