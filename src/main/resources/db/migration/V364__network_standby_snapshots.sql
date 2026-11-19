-- Cópia do banco deixa no nó de reserva (brief cooperativo §11/§12).
--
-- O que faltava: a promoção de autoridade (V317) troca QUEM manda, e a réplica de destino
-- continuava sem os DADOS de quem mandava. Promover sem cópia é trocar o nome do controlador e
-- recomeçar do zero — o failover só existe se o nó de reserva tiver o estado para assumir.
--
-- Decisões que esta tabela fixa:
--
-- * A linha é do PAR (nó de reserva, autoridade de origem). O mesmo nó pode guardar a cópia de mais
--   de uma autoridade durante um failover em cadeia, e a chave primária impede duas linhas
--   "vigentes" para o mesmo par — que diriam duas coisas diferentes sobre a mesma cópia.
-- * `state` distingue o que o operador precisa distinguir:
--     SYNCING    cópia em andamento (a anterior continua sendo a última completa);
--     READY      última cópia completa e conferida pelo digest; é a que vale para assumir;
--     STALE      a cópia completa passou de um dia SEM cópia boa (o refresh diário não rodou);
--     FAILED     a última tentativa falhou; `failure_reason` diz por quê.
--   "STALE" não é "FAILED": a cópia de ontem continua inteira, e o operador precisa saber a
--   diferença entre "o arquivo existe mas é velho" e "não há arquivo".
-- * `sha256` é o digest do `mariadb-dump --skip-dump-date` (sem a data de geração, que muda a cada
--   execução sem o dado mudar). É por ele que o nó confere o que gravou — o mesmo mecanismo da
--   réplica de storage.
-- * `byte_start > 0` é cópia RETOMADA: o nó só reinicia do zero quando o servidor pede o início; se
--   o pedido trouxer `offset`, o dump é enviado a partir dele e a tabela não culpa o nó por não ter
--   começado do primeiro byte. Sem isto, uma conexão que cai a 90% obrigaria a transferir tudo de
--   novo — e é justamente a transferência que é cara.
--
-- Idempotente e aditiva: tabela nova, nenhuma linha existente tocada.

create table if not exists network_standby_snapshots (
  node_id varchar(40) not null,
  authority_id varchar(120) not null,
  -- Última cópia CONCLUÍDA (READY/STALE) e instante em que o nó confirmou o digest.
  state varchar(16) not null,
  -- Termo da autoridade no momento do início da cópia: a cópia do termo 3 não substitui a do 4.
  term int not null,
  -- Início da última tentativa (SYNCING/FAILED) e da última conclusão (READY/STALE).
  started_at timestamp(3) not null default current_timestamp(3),
  completed_at timestamp(3) null,
  bytes bigint null,
  -- Primeiro byte enviado nesta cópia. > 0 é retomada; 0 é cópia desde o começo.
  byte_start bigint not null default 0,
  -- Digest do dump inteiro (não do trecho): o nó confere o que gravou contra o que o servidor diz.
  sha256 char(64) null,
  failure_reason varchar(200) null,
  -- Quem pediu a cópia: o admin que promoveu, ou "scheduler" no refresh automático.
  requested_by varchar(120) not null,
  primary key (node_id, authority_id),
  key idx_network_standby_snapshots_ready (state, completed_at)
) engine = InnoDB default charset = utf8mb4;