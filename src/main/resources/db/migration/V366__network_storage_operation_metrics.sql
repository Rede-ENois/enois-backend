-- Énóis Storage §17: operações por segundo e latência do painel (V366).
--
-- Por que existe: o §17 do brief pede "Operações por segundo" e "Latência" na visão geral do Storage, e
-- o painel não tinha NENHUM dos dois — não porque faltasse tela, mas porque ninguém contava operação.
-- Os demais indicadores (capacidade, uso, objetos, cópias, integridade) vêm de estado que o catálogo já
-- grava; operação e latência são EVENTOS, e evento precisa de lugar para cair.
--
-- A decisão que faz esta tabela não virar um segundo sistema de métricas: ela guarda só o agregado por
-- minuto, não cada requisição. Uma linha por (minuto, operação, resultado) — um SDK que lista 1000
-- vezes no mesmo minuto soma na mesma linha, como a promoção de um contador. Guardar cada requisição
-- seria uma tabela que cresce com o tráfego do mundo e que ninguém consulta por linha.
--
-- Campos:
--   bucket_at       o minuto, truncado (UTC) — é a chave de série.
--   operation       o que o chamador pediu: PUT, GET, HEAD, DELETE, LIST, MULTIPART_*, PRESIGN.
--   outcome         OK, CLIENT (4xx: o pedido estava errado) ou SERVER (5xx: a falha é nossa). Separar
--                   os dois é o que permite ao operador distinguir "a rede está sob ataque de clientes
--                   quebrados" de "o Storage está falhando" sem ler log.
--   req_count       quantas operações no minuto.
--   total_ms        soma dos milissegundos medidos. A média se calcula NA LEITURA (total_ms/req_count):
--                   guardar média por minuto perderia a ponderação entre minutos e daria número errado
--                   no agregado do dia.
--   max_ms          o pior caso do minuto — uma média baixa com p99 alto é o sintoma de "por que o
--                   cliente reclama se o painel está verde?".
--
-- Por que não Prometheus/actuator: o painel do Énóis já fala com o próprio banco e o operador não tem
-- servidor de métricas (`docs/rede-distribuida/08-impacto.md`). Um endpoint de scrape seria mais uma
-- peça para operar num ambiente doméstico.
--
-- Retenção: o agregado por minuto de 30 dias são ~43 mil linhas por par (operação, resultado) — trivial.
-- A varredura (`StorageOperationMetrics.retention`) apaga o que passa do prazo; sem ela a tabela cresce
-- devagar, mas para sempre.

create table if not exists network_storage_operation_metrics (
  bucket_at datetime(3) not null,
  operation varchar(24) not null,
  outcome varchar(8) not null,
  req_count bigint not null default 0,
  total_ms bigint not null default 0,
  max_ms bigint not null default 0,
  primary key (bucket_at, operation, outcome),
  key idx_network_storage_operation_metrics_retention (bucket_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;