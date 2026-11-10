-- O painel de monitorabilidade conta quantos usuários apareceram nas últimas 24h, e essa
-- consulta filtra `last_seen_at` — sem índice ela varre a tabela inteira a cada minuto de polling.
-- Aditivo e idempotente: pode rodar sobre uma base que já tenha o índice.
create index if not exists idx_user_presence_last_seen on user_presence (last_seen_at);