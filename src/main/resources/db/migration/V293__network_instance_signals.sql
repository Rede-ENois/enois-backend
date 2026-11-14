-- Énóis Distributed Network (fase 3): broadcast entre instâncias do Control Plane.
--
-- O problema: MessageStreamHub (messaging/adapters/in/web) e CacheInvalidator
-- (shared/application) guardam estado NA MEMÓRIA de cada pod. Quando o backend roda em duas
-- instâncias, uma escrita atendida em uma delas só limpa o cache e só avisa o stream daquela
-- instância; a outra continua servindo a versão velha até o TTL vencer e a aba aberta na outra
-- espera o próximo refresh. A fase 3 exige "MessageStreamHub funciona com duas instâncias; cache
-- invalida em todas" (docs/rede-distribuida/07-plano-incremental.md).
--
-- Por que uma tabela e não o outbox (ADR-007): o OutboxWorker reserva cada evento com claim
-- atômico e grava o recibo do handler na mesma transação do efeito. Isso garante que o evento rode
-- em EXATAMENTE UMA instância — ótimo para efeito de negócio, inútil para broadcast, que por
-- definição precisa chegar em todas. Aqui a semântica é o oposto de propósito: cada instância lê
-- todos os sinais que NÃO são dela e entrega só no próprio estado local. Sinal é fan-out efêmero,
-- não trabalho: não tem recibo, não tem retentativa e não é a outbox.
--
-- Por que `seq` e não uma coluna de consumo: a posição de leitura é POR INSTÂNCIA. Uma coluna
-- `consumed_at` compartilhada faria a primeira instância que lesse o sinal escondê-lo das outras —
-- exatamente o bug que esta tabela existe para corrigir. `seq` é monotônica e cada instância
-- guarda a própria posição em network_instance_readers. (`cursor` é palavra reservada no MariaDB,
-- por isso o nome é `seq`.)
--
-- Regras de desenho (ADR-004 e ADR-007, docs/rede-distribuida/05-sincronizacao.md §6):
-- - nenhum sinal carrega conteúdo: só ids e nomes de cache. Conteúdo continua vindo dos endpoints
--   autorizados, como já acontece no stream;
-- - `origin_instance` é o que faz a instância ignorar o sinal que ela mesma emitiu (a entrega
--   local já aconteceu quando o sinal foi gravado);
-- - a retenção poda sinal antigo: broadcast perdido é tolerável porque o TTL do cache e o
--   reconnect do EventSource cobrem a falta. Perder sinal nunca pode fazer o sistema parar.

create table if not exists network_instance_signals (
  seq bigint not null auto_increment,
  id varchar(40) not null,
  origin_instance varchar(64) not null,
  signal_type varchar(32) not null,
  target varchar(120) not null,
  payload longtext not null,
  created_at datetime(3) not null,
  primary key (seq),
  unique key uk_network_instance_signals_id (id),
  key idx_network_instance_signals_retention (created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Posição de leitura de cada instância. `last_seq` é até onde ela já entregou; `last_seen_at` é o
-- heartbeat que o painel usa para dizer quantas instâncias estão lendo de verdade.
create table if not exists network_instance_readers (
  instance_id varchar(64) not null,
  started_at datetime(3) not null,
  last_seen_at datetime(3) not null,
  last_seq bigint not null default 0,
  last_signal_at datetime(3) null,
  primary key (instance_id),
  key idx_network_instance_readers_seen (last_seen_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
