-- Énóis Storage, brief §18: "consultar tráfego" e "consultar disponibilidade" na tela do participante.
--
-- O §18 pede que o participante veja, por nó, o tráfego e a disponibilidade da máquina que ele
-- cede. Até aqui a rede registrava o que o nó EXECUTA (tarefas concluídas, devolvidas) e o que ele
-- MEDE no instante do heartbeat (CPU, memória, disco), mas nada sobre o tempo em que ele esteve no
-- ar: o `last_seen_at` diz "agora", e a amostra de observabilidade guarda o último minuto. "Ficou
-- fora das 14h às 15h ontem" não tinha onde ser respondido, e é exatamente o número que o §18
-- chama de disponibilidade.
--
-- DOIS contadores, não uma métrica instantânea:
--
-- 1. `observed_bytes_in_mb` / `observed_bytes_out_mb` — o tráfego do nó desde que começou a medir
--    (ou desde a última redefinição). Acumulado e monotônico por definição: nó que reinicia e zera
--    o contador reporta MENOS do que o painel já registrava, e o painel não pode engolir isso como
--    "tráfego negativo". O significado contábil aqui é o mesmo do `usedMb`: medida do nó, não
--    contrato. Nulo é "não reportado" (runtime anterior a este formato), zero é "não mediu ainda".
--
-- 2. `consecutive_failures` JÁ EXISTE (V311) e é o que a rede mede do lado dela: heartbeat atrasado
--    ou ausente. A disponibilidade histórica é DERIVADA das reconexões (`network_node_logs` com
--    ação `NODE_RECONNECTED`), que o V305/§8.6 já gravam — nenhuma tabela nova para isso.
--
-- O tráfego é o que o PRÓPRIO nó mede nas interfaces dele: o Control Plane só recebe a contagem no
-- heartbeat. Contar bytes servidos por nó no Control Plane mediria o que o Control Plane serve, não
-- o que a máquina do participante transfere — e o §18 é sobre a infraestrutura dele.
--
-- Aditiva e idempotente, como as demais deste módulo.

alter table network_nodes add column if not exists observed_bytes_in_mb bigint null;
alter table network_nodes add column if not exists observed_bytes_out_mb bigint null;