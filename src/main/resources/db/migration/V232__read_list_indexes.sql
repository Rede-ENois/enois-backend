-- Indices das vitrines que paginam por `created_at DESC` (ranking de chegada, comunidades, eventos).
-- Sem eles a primeira pagina faz varredura completa e ordena a tabela inteira so para devolver as
-- primeiras N linhas; com eles o banco le o trecho pedido direto pela ordem do indice.
-- Aditivo de proposito: nenhuma coluna, linha ou constraint muda — so o caminho de leitura.
create index if not exists idx_users_created on users (created_at);
create index if not exists idx_communities_created on communities (created_at);
create index if not exists idx_events_created on events (created_at);
