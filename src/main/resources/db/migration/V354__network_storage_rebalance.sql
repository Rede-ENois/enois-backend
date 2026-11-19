-- Rebalanceamento do Storage (brief §16).
--
-- O que faltava: existia o RELATÓRIO de equilíbrio ("quem está com cópia demais, quem tem folga"), e
-- nada que agisse. Mover uma réplica é uma transferência pela rede de quem participa, então a
-- decisão do operador precisa ser gravada — e pausada, retomada e recontada entre chamadas.
--
-- O que esta tabela NÃO faz: mover nada sozinha. Ela guarda a decisão ("esta réplica sai deste nó e
-- vai para aquele") para que o mesmo pedido repetido não abra duas transferências do mesmo objeto,
-- e para que a remoção da cópia antiga nunca aconteça antes da confirmação do destino.
--
-- Idempotente e aditiva: tabela nova, nenhuma linha existente tocada.

create table if not exists network_storage_rebalances (
  id varchar(40) not null,
  object_id varchar(40) not null,
  -- Réplica que sai (a antiga) e réplica que entra (a nova). A nova é sempre um número que o
  -- catálogo ainda não usa para este objeto: reaproveitar um ocupado faria a chave primária da
  -- colocação colidir e a designação sobrescrever a cópia antiga em silêncio.
  from_replica int not null,
  from_node_id varchar(40) not null,
  to_replica int not null,
  to_node_id varchar(40) not null,
  -- PENDING: designada, o nó destino ainda não confirmou.
  -- DONE:    destino confirmou o digest e a cópia antiga foi removida.
  -- CANCELLED: o operador desfez antes da confirmação (a cópia antiga continua lá).
  -- FAILED:  o destino recusou ou a verificação falhou; a cópia antiga continua lá.
  state varchar(16) not null,
  -- Por que o destino falhou, para o operador não adivinhar. Nulo nos demais estados.
  failure_reason varchar(120) null,
  -- Bytes a transferir e teto de banda desta transferência. O teto é POR TRANSFERÊNCIA: o nó
  -- destino aplica o menor entre ele e a própria capacidade (é o mesmo critério do §8.3).
  bytes bigint not null,
  bandwidth_limit_mbps int null,
  requested_by varchar(40) not null,
  created_at timestamp(3) not null default current_timestamp(3),
  finished_at timestamp(3) null,
  primary key (id),
  -- Uma transferência viva por réplica de origem. É o que impede a segunda chamada do operador
  -- (o clique duplo, a retentativa) de abrir outra transferência do mesmo objeto.
  --
  -- A unicidade não pode ser (object_id, from_replica, state): ela também proibiria duas
  -- transferências CONCLUÍDAS da mesma réplica ao longo do tempo, e a segunda recuperação do mesmo
  -- objeto seria impossível para sempre. A condição vira coluna gerada que só vale no estado vivo
  -- (NULL nos demais, e NULL não colide em índice único) — o mesmo recurso do V352, e pelo motivo
  -- explicado lá: MariaDB e H2 não têm índice condicional.
  live_key varchar(120)
    as (case when state = 'PENDING' then concat(object_id, ':', from_replica) end),
  constraint uq_network_storage_rebalances_live unique (object_id, from_replica, live_key),
  constraint fk_network_storage_rebalances_object
    foreign key (object_id) references network_storage_objects (id) on delete cascade,
  constraint chk_network_storage_rebalances_bytes check (bytes >= 0),
  constraint chk_network_storage_rebalances_bandwidth
    check (bandwidth_limit_mbps is null or bandwidth_limit_mbps > 0)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_unicode_ci;

-- A varredura de transferências em curso lê por estado e data. O índice é por estado primeiro: as
-- concluídas são a maioria e nunca entram nesta consulta.
create index if not exists idx_network_storage_rebalances_state
  on network_storage_rebalances (state, created_at);

-- O painel lista por objeto.
create index if not exists idx_network_storage_rebalances_object
  on network_storage_rebalances (object_id, created_at);