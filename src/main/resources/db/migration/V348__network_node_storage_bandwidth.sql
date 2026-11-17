-- Énóis Storage, brief §6: tipos de storage node, e a banda própria de cada tipo.
--
-- O §6 pede seis tipos (STANDARD, MEDIA, ARCHIVE, EDGE, REPLICA, HOME) e diz que "um mesmo
-- servidor poderá disponibilizar múltiplos perfis, respeitando os limites e permissões
-- autorizados". Os perfis entram no enum `NodeProfile` (coluna `profiles` já existente, CSV) —
-- nenhuma coluna nova para isso. O que precisava de lugar é o outro lado da frase: os LIMITES por
-- tipo.
--
-- Por que uma tabela e não uma coluna com JSON:
--
-- 1. O limite é por TIPO, e um nó pode ter vários. Uma coluna `declared_bandwidth_by_kind` com JSON
--    seria um segundo formato de configuração só para este campo, fora de qualquer verificação de
--    esquema.
-- 2. A leitura é sempre "a banda DESTE tipo neste nó" — uma linha por (nó, tipo). O índice único faz
--    o banco recusar duas linhas para o mesmo par, o que uma coluna JSON não faria.
-- 3. Nó sem linha para um tipo usa o `declared_bandwidth_mbps` do nó: ausência é "não
--    especializado", nunca zero. Zero é banda nenhuma e é outra afirmação.
--
-- O tipo é validado em `StorageNodeKind.valueOf` na leitura; o banco guarda o nome do enum, como
-- `profiles` faz desde a V305. Mesma escolha, mesmo motivo: é vocabulário do código, não tabela de
-- domínio.
--
-- Aditiva e idempotente, como as demais deste módulo.

create table if not exists network_node_storage_bandwidth (
  node_id varchar(40) not null,
  kind varchar(24) not null,
  bandwidth_mbps int not null,
  updated_at timestamp(3) not null,
  primary key (node_id, kind),
  constraint fk_network_node_storage_bandwidth_node
    foreign key (node_id) references network_nodes (id) on delete cascade,
  constraint ck_network_node_storage_bandwidth_positive check (bandwidth_mbps >= 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;