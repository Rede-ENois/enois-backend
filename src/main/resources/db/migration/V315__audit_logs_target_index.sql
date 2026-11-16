-- Énóis Distributed Network (fase 5): o histórico do nó no painel.
--
-- O contrato do painel pede a página por nó com "histórico", e a trilha já existe: toda ação
-- administrativa sobre um nó grava em audit_logs com target_id = id do nó (NODE_APPROVED,
-- NODE_SUSPENDED, NODE_KEY_ISSUED, NODE_CAPACITY_CHANGED, NETWORK_NODE_QUARANTINED, ...). O que
-- não existia é a leitura — e o motivo é o índice: audit_logs só tinha (actor_id, created_at)
-- (V1) e created_at (V261). O primeiro serve para "o que fulano fez"; o segundo, para a retenção
-- varrer o tempo. Perguntar "o que aconteceu com ESTE nó" por qualquer um deles é varrer a tabela
-- com filtro em coluna sem índice — e a tabela é a maior trilha do sistema, cresce para sempre
-- menos o corte da retenção.
--
-- Este índice responde a pergunta do painel. É (target_id, created_at) e não só target_id porque
-- a leitura é sempre "as últimas ações do nó, mais novas primeiro": o índice entrega a faixa do
-- nó já em ordem, do mesmo modo que idx_audit_created entrega o lote da retenção.
--
-- Aditiva e idempotente, como as demais deste módulo.

create index if not exists idx_audit_target_created on audit_logs (target_id, created_at);
