-- Énóis Distributed Network (fase 2): tarefas distribuídas.
--
-- O scheduler é um handler do outbox (ADR-004), mas o estado de retentativa NÃO pode viver no
-- evento: o OutboxWorker grava o recibo do handler ANTES de chamar o handler, na mesma transação
-- (shared/outbox/OutboxWorker.runHandler). Quando o nó falha no meio da tarefa, o handler já
-- concluiu — o que falhou foi o nó, não o evento. Por isso a linha da tarefa é a autoridade sobre
-- tentativa, prazo, nó designado e erro; o evento é só o gatilho que a cria (ADR-006).
--
-- Regras de desenho (docs/rede-distribuida/01-arquitetura.md, 03-contratos.md, 06-contingencia.md):
-- - nenhum nó externo escreve aqui; só o Control Plane lê e grava esta tabela;
-- - `attempt` sobe a cada designação e o resultado de tentativa antiga é descartado;
-- - `claim_expires_at` é o que devolve à fila a tarefa de um nó que caiu no meio;
-- - `deadline` vencido não executa: a tarefa vira DEAD com TASK_EXPIRED;
-- - DEAD é a DLQ: a tarefa esgotou as tentativas e aparece no painel com o último erro.

create table if not exists network_tasks (
  id varchar(40) not null,
  task_type varchar(32) not null,
  status varchar(16) not null default 'PENDING',
  attempt int not null default 0,
  idempotency_key varchar(80) not null,
  payload longtext not null,
  data_class varchar(24) not null,
  assigned_node_id varchar(40) null,
  deadline timestamp not null,
  next_attempt_at timestamp not null,
  dispatched_at timestamp null,
  claim_expires_at timestamp null,
  finished_at timestamp null,
  output longtext null,
  last_error varchar(500) null,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id),
  unique key uk_network_tasks_idempotency (idempotency_key),
  key idx_network_tasks_pending (status, next_attempt_at),
  key idx_network_tasks_node (assigned_node_id, status),
  key idx_network_tasks_claim (status, claim_expires_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Modo operacional vigente (06-contingencia.md). Uma linha só, id 'global'. A fase 2 calcula
-- NORMAL e HIGH_LOAD a partir de números medidos; DEGRADED, EMERGENCY e RECOVERY entram na fase 4
-- e o cálculo automático não os sobrescreve.
create table if not exists network_state (
  id varchar(20) not null,
  mode varchar(24) not null,
  reason varchar(200) null,
  since timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
