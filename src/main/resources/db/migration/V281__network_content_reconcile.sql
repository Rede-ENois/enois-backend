-- Énóis Distributed Network (fase 3): reconciliação periódica do conteúdo de borda.
--
-- O critério de aceite (docs/rede-distribuida/07-plano-incremental.md) é "divergência vira alerta
-- SYNC_DIVERGENCE". O alerta já existe, mas só nascia quando o PRÓPRIO NÓ chamava
-- POST /network/content/reconcile. Um nó que nunca chama — porque está com bug, porque foi
-- comprometido, ou porque simplesmente não implementou a chamada — nunca era conferido, e o painel
-- mostrava "sem divergência" para um nó que servia conteúdo velho há dias.
--
-- A varredura periódica fecha isso usando o que o Control Plane JÁ TEM: `network_content_snapshots`
-- guarda o que cada nó declarou ter guardado (versão e hash do corpo). Comparar essa declaração com
-- a versão vigente da origem não depende de o nó cooperar. Se o nó mentiu sobre o que guardou, a
-- varredura não tem como saber — mas mentir sobre o próprio cache não dá poder nenhum: o pior que
-- acontece é o nó servir conteúdo velho, e isso a varredura pega assim que a origem muda.
--
-- Três colunas novas, e cada uma resolve um problema diferente:
--
-- 1. `checked_at` — quando a varredura conferiu esta linha pela última vez. É o que faz a rotação
--    ser justa: a varredura pega sempre as linhas conferidas há mais tempo (nulos primeiro), então
--    um nó com milhares de agregados é coberto em rodadas, e não sempre os mesmos primeiros.
--
-- 2. `divergence_reason` — o motivo da divergência vigente (CONTENT_STALE / CONTENT_GONE), ou nulo
--    quando o nó está em dia. É o que o painel lê.
--
-- 3. `divergence_at` — quando a divergência vigente foi detectada. Sem ela, o painel não distingue
--    "divergiu agora" de "está divergente desde ontem".
--
-- Por que o motivo fica na linha e não só na auditoria: a varredura roda a cada poucos minutos. Se
-- cada rodada gravasse um SYNC_DIVERGENCE, um nó parado por uma hora encheria a trilha de auditoria
-- com dezenas de linhas idênticas e esconderia o que importa. A auditoria é gravada na TRANSIÇÃO
-- (em dia -> divergente, ou motivo diferente), e a linha guarda o estado vigente. A recuperação
-- também é auditada (SYNC_RECOVERED), para o alerta fechar no painel.

alter table network_content_snapshots
  add column if not exists checked_at datetime(3) null;

alter table network_content_snapshots
  add column if not exists divergence_reason varchar(32) null;

alter table network_content_snapshots
  add column if not exists divergence_at datetime(3) null;

-- A varredura ordena por checked_at (nulos primeiro) dentro de um nó. O índice cobre a leitura e
-- deixa a ordenação no banco em vez de trazer a tabela inteira do nó para a memória.
create index if not exists idx_network_content_snapshots_check
  on network_content_snapshots (node_id, checked_at);

-- O painel lista só o que está divergente; sem este índice a consulta varreria todos os snapshots
-- de todos os nós para achar as poucas linhas com motivo preenchido.
create index if not exists idx_network_content_snapshots_divergent
  on network_content_snapshots (divergence_reason, divergence_at);
