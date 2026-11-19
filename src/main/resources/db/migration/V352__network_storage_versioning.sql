-- Versionamento e ciclo de vida do Storage (brief §14).
--
-- Numeração: V351 já é do termômetro na qa; esta é a V352.
--
-- O que faltava:
--   1. a chave única (bucket, object_key) de V319 fazia PUT na mesma chave devolver o objeto
--      antigo (retry idempotente). Certo para retry de rede, errado para guardei nova versão: não
--      havia como uma versão nova nascer sem apagar a antiga. Não havia versão, nem como voltar.
--   2. não havia prazo de vida: objeto guardado ficava para sempre, sem retenção nem expiração.
--   3. o bucket era só um nome em texto livre — não havia onde configurar nada por bucket.
--
-- Idempotente: aplicada duas vezes, o resultado é o mesmo. Cada instrução tem guarda própria
-- (if not exists / if exists) e a ordem das que se relacionam está justificada em cada trecho.
-- Os dados existentes não mudam de conteúdo: a tabela nova começa vazia e as colunas novas entram
-- com o valor que reproduz o comportamento anterior.
--
-- A migração tem dois tipos de passo. Coluna e tabela novas são aditivas. Índice e restrição são
-- DDL estrutural: eles têm ordem obrigatória, explicada onde aparecem.

-- Política por bucket: onde a decisão do §14 fica guardada. Bucket sem linha aqui usa os padrões do
-- código (sem versionamento, sem prazo) — criar a tabela não muda o comportamento de nada que já
-- existe.
create table if not exists network_storage_buckets (
  bucket varchar(63) not null,
  -- Versionamento opcional POR BUCKET, como o brief pede. Falso é o comportamento anterior:
  -- upload repetido na mesma chave lê o objeto que já existe.
  versioning boolean not null default false,
  -- Dias que uma versão SUBSTITUÍDA sobrevive depois de sair da frente. Nulo é guarda para
  -- sempre. É retenção, não expiração: só se aplica a quem já foi trocado.
  retain_superseded_days int null,
  -- Dias que um objeto STORED vive desde a gravação. Nulo é não expira. Ao vencer, o objeto
  -- recebe lápide (não é apagado): as cópias nos nós ainda precisam ser coletadas.
  expire_after_days int null,
  updated_by varchar(40) not null,
  updated_at timestamp(3) not null default current_timestamp(3),
  primary key (bucket),
  constraint chk_network_storage_buckets_retention
    check (retain_superseded_days is null or retain_superseded_days >= 0),
  constraint chk_network_storage_buckets_expiry
    check (expire_after_days is null or expire_after_days > 0)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_unicode_ci;

-- A versão de um objeto. Todo objeto que já existe é a versão 1 de si mesmo.
alter table network_storage_objects
  add column if not exists version bigint not null default 1;

-- Quando o objeto deixa de ser servível por tempo, calculado na gravação. Nulo é não expira.
alter table network_storage_objects
  add column if not exists expires_at timestamp(3) null;

-- Qual versão substituiu esta. Nulo é a corrente. Guarda o id, não a versão: o rastro precisa
-- apontar para a linha certa mesmo que a corrente mude de novo antes de alguém olhar.
alter table network_storage_objects
  add column if not exists superseded_by varchar(40) null;

-- A unicidade que a chave antiga dava precisa ser recriada de outro jeito: o que tem de ser único é
-- a chave CORRENTE de bucket+chave, quantas versões substituídas houver. MariaDB e H2 não aceitam
-- índice condicional, então a condição vira COLUNA gerada: o CASE devolve NULL para a versão
-- substituída, e NULL não colide em índice único — só as correntes disputam.
--
-- O CASE é obrigatório e não decorativo: sem ele, (bucket, chave) seria único para todas as versões
-- e a segunda versão nunca nasceria. Com ele, é único só para a corrente, que é a regra certa.
--
-- O separador é char(31) (unit separator) porque não pode aparecer em bucket nem em chave, senão
-- ('a', 'b/c') e ('a/b', 'c') colidiriam num mesmo valor.
--
-- Vem DEPOIS de superseded_by porque a expressão o lê: coluna gerada não pode referenciar coluna
-- que ainda não existe.
alter table network_storage_objects
  add column if not exists current_key varchar(820)
    as (case when superseded_by is null then concat(bucket, char(31), object_key) end);

-- A varredura de expiração lê quais objetos vencem até agora em todos os buckets, e as linhas sem
-- prazo são a maioria: o índice de propósito não as guarda além do necessário.
create index if not exists idx_network_storage_objects_expires
  on network_storage_objects (expires_at);

-- ============================================================================
-- Parte estrutural. Lida antes de rodar.
--
-- A chave única (bucket, object_key) de V319 tem de sair: com versionamento, a segunda versão da
-- mesma chave esbarraria nela. A unicidade que a substitui é a condicional
-- uq_network_storage_objects_current.
--
-- Ordem obrigatória, e o motivo de cada passo:
--   1. criar a unicidade nova ANTES de remover a antiga: se houver dado que a viole (duas versões
--      correntes da mesma chave — impossível pelos índices de hoje, mas o banco real é quem
--      responde), a migração falha com a proteção antiga ainda no lugar, em vez de deixar a
--      tabela sem unicidade nenhuma;
--   2. remover a antiga e criar o índice de busca não-único.
-- Trocar 1 e 2 abriria a janela sem unicidade de chave no meio da migração.
-- ============================================================================

-- A chave inclui bucket e object_key, e não só current_key: índice sobre uma coluna gerada que
-- depende de outra coluna não é alvo válido do `on duplicate key update` da inserção idempotente
-- (o H2 chega a falhar ao preparar a instrução). Com as três colunas, a unicidade é a mesma —
-- NULL no último componente não colide — e a inserção casa a restrição como antes.
create unique index if not exists uq_network_storage_objects_current
  on network_storage_objects (bucket, object_key, current_key);

alter table network_storage_objects
  drop index if exists uq_network_storage_objects_key;

create index if not exists idx_network_storage_objects_key
  on network_storage_objects (bucket, object_key);

-- find(bucket, chave) resolve a versão corrente: busca pelo índice de chave e ordena pela versão.
create index if not exists idx_network_storage_objects_version
  on network_storage_objects (bucket, object_key, version);