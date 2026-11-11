-- Índices que faltavam para a leitura em lote da credibilidade (job do ranking).
--
-- O job passou a ler as métricas de uma página inteira de contas por consulta, agrupando pelo
-- dono. Duas dessas consultas filtram por uma coluna que não tem índice próprio:
--
--   * event_attendees(user_id): o único índice da tabela é (event_id, user_id) — a coluna líder
--     é o evento, então "where user_id in (...)" varre a tabela inteira. A contagem de presenças
--     é uma das métricas do componente de contribuição.
--   * jobs(posted_by): as vagas do autor entram na contagem de fatos confirmados contra o
--     conteúdo dele. Os índices de jobs começam por type, posted_at, status ou company_id.
--
-- Aditivo e idempotente: pode rodar sobre uma base que já tenha os índices. Nenhum dado muda e
-- nenhuma regra de negócio é tocada — só o custo de leitura.
create index if not exists idx_event_attendees_user on event_attendees (user_id);
create index if not exists idx_jobs_posted_by on jobs (posted_by);
