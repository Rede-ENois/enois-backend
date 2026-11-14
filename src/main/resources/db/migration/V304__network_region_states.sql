-- Énóis Distributed Network (fase 6): recuperação regional — a região declarada fora de operação.
--
-- O critério do plano é "região afetada sai; nós de outras regiões atendem o que podem" e a
-- estratégia está em docs/rede-distribuida/06-contingencia.md §3. Até aqui a região era SÓ
-- ordenação: `regionPenalty` (ADR-012) fazia o nó da região do pedido vir antes, mas nada podia
-- tirar uma região de operação. Numa queda regional o nó continua ACTIVE, dá heartbeat do outro
-- lado do desastre quando consegue, e a rede segue designando tarefa para ele — tarefa que não
-- vai terminar. E a fila esquece: `findOnNonOperatingNodes` drena por `n.status <> 'ACTIVE'`,
-- então o que estava designado aos nós da região espera o claim TTL inteiro (120s no padrão) por
-- um resultado que não vem, e `availableCapacity` segue contando a vaga de uma região que não
-- executa nada — o modo dizia "vagas=6" com a região inteira caída.
--
-- A tabela guarda a DECISÃO humana, não um estado medido: região fora de operação é incidente
-- declarado por quem olhou, com motivo obrigatório e auditado (o mesmo raciocínio do modo
-- operacional, ADR-009). Não é automação: nenhum número que o Control Plane meça distingue
-- "região em desastre" de "região com a rede lenta", e readmitir região caída por relógio seria
-- a automação desfazendo decisão de incidente — a região volta quando alguém declara o fim.
--
-- - `region` é a chave e é normalizada (trim + lower-case) na aplicação: o comparador de afinidade
--   já é case-insensitive (`equalsIgnoreCase`), e duas grafias da mesma região ("sudeste" e
--   "Sudeste") teriam gerado duas declarações e meia evacuação.
-- - `declared_by`/`declared_reason`/`declared_at` vão para o painel e para a auditoria do
--   incidente: tirar uma região do ar sem registro seria contingência sem história.
--
-- Aditiva e idempotente, como todas as deste módulo: tabela nova, nenhum dado antigo tocado.

create table if not exists network_region_states (
  region varchar(64) not null,
  declared_by varchar(40) not null,
  declared_reason varchar(200) not null,
  declared_at datetime(3) not null,
  primary key (region)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
