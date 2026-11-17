-- Énóis Distributed Network (fase 3): conteúdo público versionado para os nós de borda.
--
-- O que a fase 3 pede para o NODE_EDGE (docs/rede-distribuida/07-plano-incremental.md): "entrega
-- conteúdo público com versão; nunca decide autorização de usuário". Duas metades, e as duas
-- precisam de estado:
--
-- 1. `network_content_versions` — a versão do agregado público que o nó de borda serve junto com o
--    conteúdo (`sourceVersion`), mais o hash do corpo servido. A versão NÃO é `updated_at`: dois
--    commits no mesmo milissegundo, ou um efeito que muda o que a borda mostra sem tocar a linha do
--    agregado, mudariam o conteúdo sem mudar o carimbo de tempo — e o nó que comparasse por tempo
--    não enxergaria a mudança. A versão sobe quando o CORPO muda (comparação por hash), então ela
--    é monotônica e significa exatamente "o que a borda entrega mudou".
--
--    Por que a chave é (content_type, content_id) e não uma coluna em cada tabela de conteúdo: a
--    borda não pode consultar tabela crua de domínio (docs/rede-distribuida/05 §5). Esta tabela é a
--    projeção de versão, e o tipo é a allowlist — tipo fora de `ContentKind` não tem linha aqui.
--
-- 2. `network_content_snapshots` — o que o nó já baixou, por recurso, com a versão e o hash do
--    corpo. É o outro lado da reconciliação: comparar o `sourceVersion` da origem com o do nó é
--    comparar esta coluna com a de cima (fase 3, reconciliação → alerta `SYNC_DIVERGENCE`).
--
-- Regra que não se negocia (docs/rede-distribuida/05 §2): só conteúdo público entra aqui. Não há
-- coluna de classe de dado porque a tabela só recebe tipo público; o tipo é a allowlist.

create table if not exists network_content_versions (
  content_type varchar(32) not null,
  content_id varchar(64) not null,
  version bigint not null,
  content_hash char(64) not null,
  updated_at datetime(3) not null,
  primary key (content_type, content_id),
  key idx_network_content_versions_updated (updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists network_content_snapshots (
  node_id varchar(40) not null,
  content_type varchar(32) not null,
  content_id varchar(64) not null,
  source_version bigint not null,
  content_hash char(64) not null,
  synced_at datetime(3) not null,
  primary key (node_id, content_type, content_id),
  key idx_network_content_snapshots_synced (synced_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
