-- Énóis Distributed Network: threads do processo do nó (brief §8.2, bloco "Runtime", item "Threads").
--
-- POR QUE EXISTE: os campos do bloco "Runtime" que o nó pode medir de fato são threads e uso de
-- memória (já medido) — GC, heap e non-heap não existem num runtime shell, e o uptime do host já
-- está em `uptime_seconds`. Sem esta coluna, o heartbeat descartava o número e o painel não tinha
-- como dizer se o processo do nó abriu threads demais (encoder, threads de sinal, subprocesso).
--
-- SEMÂNTICA: `Threads:` de `/proc/<pid>/status` do processo que mede, no instante do heartbeat.
-- Nulo é "não medido" (runtime antigo, `/proc` inacessível), nunca 0 — zero afirmaria "nenhum
-- thread" para quem nunca leu o arquivo.
--
-- Aditiva: coluna nula, sem default; as amostras anteriores ficam nulas e o painel diz isso. A
-- versão é a 388 porque a maior existente é a 387 (retentativas).
alter table network_node_samples
  add column if not exists threads int null after temperature_c;
