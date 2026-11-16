-- Énóis Distributed Network: painel de observabilidade POR NÓ (amostras curtas e trancadas).
--
-- Por que existe: o painel do nó hoje mostra só o último valor medido (`observed_*`). "Quadros ao
-- vivo" exigem uma série — somar uma linha por heartbeat num histórico aberto faria a tabela crescer
-- sem teto. Então são DUAS tabelas com papéis diferentes:
--
--   network_node_samples  -- 60 pontos por nó, um por minuto, agregados. É o gráfico.
--   network_node_logs     -- 200 linhas por nó, o que o runtime escreveu. É o console.
--
-- O teto é por nó e é aplicado na escrita (o serviço apaga o que passou), não por job de limpeza:
-- um nó que some da rede não deixa lixo acumulando, e um nó que volta encontra o histórico recente.
--
-- O QUE NÃO É: o Control Plane não vira repositório de log. Isto é a janela curta que alimenta a
-- tela; não é auditoria (essa é `audit_*`) nem retenção de longo prazo. É por isso que o teto é
-- pequeno e explícito.
--
-- Aditiva e idempotente, como as demais deste módulo.

create table if not exists network_node_samples (
  node_id varchar(40) not null,
  sampled_at timestamp(3) not null,
  cpu_percent double null,
  memory_mb int null,
  disk_free_mb bigint null,
  running_tasks int null,
  queue_depth int null,
  mode varchar(24) null,
  -- Rótulo do quadro de atividades: o que o nó estava fazendo (tarefa, replica, drenando).
  activity varchar(64) null,
  primary key (node_id, sampled_at),
  key idx_network_node_samples_time (sampled_at),
  -- A janela morre com o nó: o painel é do nó, e um nó revogado e apagado não deixa amostra órfã
  -- ocupando a tabela. A FK é o que o harness de MariaDB real cobrou — sem ela o H2 aceitava.
  constraint fk_network_node_samples_node
    foreign key (node_id) references network_nodes (id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists network_node_logs (
  id varchar(40) not null,
  node_id varchar(40) not null,
  logged_at timestamp(3) not null,
  level varchar(8) not null,
  message varchar(500) not null,
  primary key (id),
  key idx_network_node_logs_node (node_id, logged_at),
  constraint fk_network_node_logs_node
    foreign key (node_id) references network_nodes (id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;