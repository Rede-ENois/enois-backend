-- Énóis Distributed Network: IOPS e latência de disco medidos pelo nó (brief §8.2).
--
-- POR QUE EXISTE: o §8.2 pede, por nó, IOPS e latência de disco. O Control Plane não abre conexão
-- para dentro da máquina do participante (brief §17), então quem mede é o runtime — de
-- `/proc/diskstats` — e manda no heartbeat que já existe. Sem coluna, a medida não teria onde
-- pousar e o painel continuaria sem ela.
--
-- O QUE SÃO AS DUAS MEDIDAS:
--   `disk_iops`        -- operações de leitura + escrita concluídas por segundo no intervalo entre
--                         dois heartbeats (`reads completed + writes completed` / segundos).
--   `disk_latency_ms`  -- tempo médio por operação concluída no mesmo intervalo
--                         (`ms reading + ms writing` / operações). O kernel pode sobrepor I/O, então
--                         é latência média observada, não um percentil.
--
-- NULO NÃO É ZERO, nas duas colunas: zero IOPS é uma medida ("medi e não houve operação"); nulo é
-- "não medido" (primeiro ciclo depois de subir, runtime antigo, ou /proc/diskstats ilegível). A
-- latência fica nula quando o intervalo não teve operação concluída — não há por onde dividir.
--
-- A série curta (`network_node_samples`) ganha as mesmas duas colunas para o quadro ao vivo: o
-- painel desenha a janela de 60 pontos, não só o último valor.
--
-- Aditiva e idempotente, como as demais deste módulo.

alter table network_nodes
  add column if not exists observed_disk_iops double null after observed_disk_total_mb;

alter table network_nodes
  add column if not exists observed_disk_latency_ms double null after observed_disk_iops;

alter table network_node_samples
  add column if not exists disk_iops double null after disk_free_mb;

alter table network_node_samples
  add column if not exists disk_latency_ms double null after disk_iops;