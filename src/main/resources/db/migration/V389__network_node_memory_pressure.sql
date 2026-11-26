-- Énóis Distributed Network: swap e pressão de memória medidos e guardados (brief §8.2, "Sistema
-- operacional": "Swap" e "Pressão de memória").
--
-- POR QUE EXISTE: desde o ciclo 157 o runtime MEDE os dois — swap em uso (`SwapTotal - SwapFree`
-- de /proc/meminfo) e a pressão do PSI (`avg300` do `some` em /proc/pressure/memory) — e os manda no
-- corpo do heartbeat, mas o contrato do Control Plane nunca os recebeu: eles eram descartados na
-- borda e o painel do nó dizia "não medido" para sempre. A pressão é o número que diz "o nó está
-- sofrendo" antes de o uso chegar ao teto, e é com ela que se distingue máquina apertada de máquina
-- ociosa com swap velho ocupado.
--
-- SEMÂNTICA: `swap_used_mb` é MiB em uso (não total, não livre); `memory_pressure_percent` é a
-- porcentagem do tempo em que alguma tarefa esperou memória na média de 300 s. Nulo é "não medido"
-- — kernel sem swap, sem PSI (anterior a 4.20 ou desligado no boot), ou runtime antigo —, nunca 0,
-- que afirmaria "nenhum swap em uso" e "nenhuma espera" sem ninguém ter lido o arquivo.
--
-- Aditiva: as duas colunas nascem nulas; as amostras anteriores ficam nulas e o painel diz isso.
-- A versão é a 389 porque a maior existente é a 388 (threads).
alter table network_node_samples
  add column if not exists swap_used_mb bigint null after memory_mb;

alter table network_node_samples
  add column if not exists memory_pressure_percent double null after swap_used_mb;