-- Énóis Distributed Network: taxa de rede medida, não só o acumulado (brief §8.2, "Sistema
-- operacional": "Throughput de entrada e saída"; §8.3: "Throughput global").
--
-- POR QUE EXISTE: desde a V345 o nó manda `bytesInMb`/`bytesOutMb`, e a série do minuto nunca os
-- recebeu. E o número que ela mandaria seria o ACUMULADO desde o boot — um contador que só sobe,
-- útil para o painel do participante ("quanto já passou por aqui") e inútil para a pergunta que o
-- §8.2 faz: quanto está PASSANDO agora. "Throughput" e "total transferido" são medidas diferentes;
-- somar os acumulados não dá razão nenhuma, e a diferença entre dois pontos dividida por um intervalo
-- que ninguém guardou não é calculável depois. Por isso a taxa é calculada no runtime (que tem o
-- relógio da medida anterior) e gravada aqui como número medido.
--
-- SEMÂNTICA: MiB por segundo, entrada (`net_in_mbps`) e saída (`net_out_mbps`), no instante do
-- ponto do minuto. Nulo é "não medido" — runtime anterior a esta versão, `/proc/net/dev` ilegível, ou
-- o primeiro ciclo sem medida anterior para subtrair —, nunca 0, que afirmaria "nada passou".
--
-- Aditiva: as duas colunas nascem nulas sem default; as amostras anteriores ficam nulas e o painel
-- diz isso. A versão é a 391 porque a maior existente é a 390 (capacidade do executor).
alter table network_node_samples
  add column if not exists net_in_mbps double null after memory_pressure_percent;

alter table network_node_samples
  add column if not exists net_out_mbps double null after net_in_mbps;