-- Énóis Storage (fase 1): a colocação — em qual nó está uma cópia do objeto.
--
-- A partir daqui a pergunta "onde está o arquivo?" tem resposta sem que o backend do Énóis conheça
-- servidor nenhum: o catálogo aponta para linhas desta tabela, e cada linha é (objeto, nó). O brief
-- proíbe cadastrar IP/hostname/porta de storage node (§17) e esta tabela respeita isso: ela
-- referencia `network_nodes.id`, que é a identidade já provisionada pela rede — não um endereço.
--
-- O que a fase 1 grava aqui e o que fica para a fase 3:
--
-- - Fase 1: UMA colocação por objeto, com `replica` = 0, gravada quando o nó confirma o PUT. Não é
--   redundância: é o registro mínimo que torna o objeto recuperável depois de o upload terminar.
-- - Fase 3: N colocações por objeto (replica 0..N-1), domínio de falha e healing. A chave primária
--   já é (object_id, replica), e não (object_id), exatamente para que a replicação não precise
--   alterar a tabela — só inserir linhas com outro número.
--
-- `verified_at` é o instante em que o CONTROL PLANE conferiu (tamanho e sha-256 medidos ao receber
-- o corpo), não o instante em que o nó declarou ter gravado. Mesma regra do transcode de mídia:
-- "gravou" só vale quando o CP mediu. `bytes`/`sha256` ficam aqui também porque um nó pode ter uma
-- cópia de uma versão antiga do objeto enquanto outro tem a atual, e a comparação é por colocação.
--
-- Aditiva e idempotente, como todas as deste módulo: tabela nova, nenhum dado antigo tocado.

create table if not exists network_storage_placements (
  object_id varchar(40) not null,
  replica int not null default 0,
  node_id varchar(40) not null,
  bytes bigint not null,
  sha256 varchar(64) not null,
  state varchar(16) not null,
  verified_at timestamp(3) not null,
  primary key (object_id, replica),
  constraint fk_network_storage_placements_object
    foreign key (object_id) references network_storage_objects (id) on delete cascade
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_unicode_ci;

-- O nó precisa saber o que ele mesmo hospeda (para reportar disco usado, para healing, e para
-- apagar cópia órfã depois de uma lápide) — e essa leitura é por nó, não por objeto.
create index if not exists idx_network_storage_placements_node
  on network_storage_placements (node_id, state);