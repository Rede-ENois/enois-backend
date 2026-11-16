-- Énóis Storage (fase 1): o catálogo de objetos — o que existe, quanto pesa e onde foi parar.
--
-- O brief do Storage (enois-storage-brief.txt §3.1) pede um catálogo que responda "qual é este
-- objeto, quanto pesa e onde ele está" sem que o backend do Énóis conheça o servidor físico. Esta
-- tabela é a primeira metade disso: o objeto LÓGICO, com identidade estável própria, independente
-- de qualquer nó que um dia o guardou.
--
-- Decisões que valem registrar, porque a fase seguinte (redundância, §FASE 3) depende delas:
--
-- - `object_key` é única por bucket, case-sensitive e não normalizada: uma chave S3 é byte a byte
--   o que o cliente mandou, e "Foto.JPG" e "foto.jpg" são dois objetos. Normalizar aqui mudaria o
--   nome de coisas que o cliente vai procurar de novo pelo nome que ele deu.
-- - `bytes` e `sha256` são NULOS até a primeira colocação confirmada. O objeto nasce no upload
--   (quando ainda não sabemos o tamanho real) e é o nó que declara, medido por ele, no PUT. Ler
--   "bytes" nulo é "ainda não há cópia inteira", que é diferente de "zero bytes".
-- - `state` distingue PENDING (upload iniciado, nenhuma cópia confirmada) de STORED (ao menos uma
--   cópia inteira e conferida) e de DELETED (lápide — a chave não volta a ser usável pelo mesmo
--   objeto, e a fase 3 usa a lápide para varrer cópias órfãs nos nós).
-- - Não há coluna de nó aqui de propósito. O objeto lógico não pertence a nó nenhum: a colocação
--   vive em `network_storage_placements`, e a fase 3a põe N linhas por objeto sem tocar nesta.
--
-- Aditiva e idempotente, como todas as deste módulo: tabela nova, nenhum dado antigo tocado.

create table if not exists network_storage_objects (
  id varchar(40) not null,
  bucket varchar(63) not null,
  object_key varchar(700) not null,
  content_type varchar(200) not null default 'application/octet-stream',
  bytes bigint null,
  sha256 varchar(64) null,
  state varchar(16) not null,
  created_by varchar(40) not null,
  created_at timestamp(3) not null default current_timestamp(3),
  updated_at timestamp(3) not null default current_timestamp(3),
  deleted_at timestamp(3) null,
  primary key (id),
  constraint uq_network_storage_objects_key unique (bucket, object_key)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_unicode_ci;

-- A listagem por bucket é prefixo de chave e é a leitura mais frequente (listar o bucket inteiro
-- para o cliente S3 e para o painel). `object_key` no índice, não só na tabela: o `ORDER BY` da
-- listagem sai da árvore em vez de ordenar em memória.
--
-- O limite de 700 caracteres da chave vem daqui, não do gosto: em utf8mb4, é o maior valor que
-- cabe INTEIRO num índice composto com o bucket. 63 + 700 caracteres são 3052 bytes, abaixo dos
-- 3072 que o InnoDB aceita — a primeira versão usava varchar(1024) (4096 bytes) e o MariaDB 11.4.13
-- recusou com ERROR 1071. Prefixo de índice resolveria no MariaDB, mas o H2 dos testes não o
-- suporta; uma coluna de tamanho válido nos dois é a única forma de o mesmo arquivo servir aos dois
-- bancos. 700 cobre com folga o uso real (a prática do S3 fica abaixo de 200).
create index if not exists idx_network_storage_objects_bucket
  on network_storage_objects (bucket, object_key);

-- Espaço ocupado por bucket é a soma de `bytes` das cópias confirmadas; a varredura de lápides
-- (state = 'DELETED') é por data, e sem índice ela leria o catálogo inteiro para achar as poucas
-- linhas que a coleta noturna toca.
create index if not exists idx_network_storage_objects_state
  on network_storage_objects (state, updated_at);