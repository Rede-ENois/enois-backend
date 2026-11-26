-- Énóis Distributed Network: distribuição do tempo de execução das tarefas por nó (brief §8.2).
--
-- POR QUE EXISTE: o §8.2 pede a latência em p50, p95 e p99 por nó. Até aqui havia uma única coluna
-- de memória (`network_nodes.observed_latency_ms`) que não é mediana: ela é uma média móvel
-- exponencial (`(antiga * 3 + nova) / 4`), e média não responde "quanto é o pior 5%". Para a cauda é
-- preciso a DISTRIBUIÇÃO, não um resumo. Esta tabela guarda a contagem de tarefas por faixa de
-- duração, por nó e por hora: é pouca linha (uma por faixa usada na hora), aguenta agregar sem
-- guardar uma amostra por tarefa e responde p50/p95/p99 em uma consulta.
--
-- POR QUE FAIXA E NÃO AMOSTRA: guardar cada duração seria uma linha por tarefa concluída, sem teto —
-- a rede que mais trabalha viraria a que mais escreve. A faixa é o histograma clássico (buckets em
-- potência de dois de milissegundos), e o percentil sai por interpolação linear dentro da faixa. O
-- erro fica limitado ao tamanho da faixa, e a resolução já está dita em `p50Ms` quando não há
-- amostra naquela faixa.
--
-- `metrics_hour` é a hora truncada em UTC, não o instante da tarefa: o UPSERT soma na faixa da hora
-- corrente, e o painel lê uma janela de horas. Assim a escrita é um UPDATE relativo por desfecho
-- (soma no banco, sem ler-decidir-escrever), o padrão de contador deste módulo.
--
-- Aditiva: tabela nova, nada é alterado nas existentes.

create table if not exists network_task_latency_buckets (
  node_id varchar(40) not null,
  metrics_hour timestamp not null,
  bucket_upper_ms int not null,
  samples int not null default 0,
  primary key (node_id, metrics_hour, bucket_upper_ms),
  key idx_network_task_latency_hour (metrics_hour)
) charset = utf8mb4 collate = utf8mb4_unicode_ci;