-- Énóis Distributed Network (fase 6): autoridade redundante, termo monotônico e quórum.
--
-- O critério do plano é "Control Plane redundante: promoção controlada com term; sem quórum,
-- ninguém promove" e a estratégia está em docs/rede-distribuida/06-contingencia.md §4. Até aqui o
-- Control Plane era ponto único: `network_state` dizia o modo de UMA autoridade e nada dizia qual
-- autoridade era, nem desde quando. Dois Control Planes subindo ao mesmo tempo no mesmo banco
-- escreviam a mesma linha sem que ninguém percebesse que havia dois — o defeito que a fase 6
-- existe para impedir.
--
-- O termo é o que separa duas autoridades no tempo:
-- - `term` sobe SÓ na promoção, por compare-and-set (`... where term = <lido>`). Duas promoções
--   simultâneas disputam a mesma linha: a segunda atualiza zero linhas e é recusada. É por
--   construção que dois controladores com o mesmo termo não existem.
-- - `authority_id` é quem detém o termo. Sem ele, o termo seria um número sem dono e a auditoria
--   não diria quem promoveu.
-- - `promoted_at` é quando aquele termo passou a valer. `since` já existe, mas é do MODO: o modo
--   pode mudar dentro do mesmo termo (DEGRADED, EMERGENCY) e o termo não muda com ele.
--
-- `term` nasce 0: antes desta migração não havia promoção, e 0 lê como "autoridade original".
-- Nenhuma linha é reescrita com um número inventado.
--
-- `network_authorities` é o ELEITORADO DECLARADO, e é isso que torna o quórum correto: a lista de
-- quem pode votar é decisão humana (declarada por um admin), não derivada de quem está vivo agora.
-- Se o eleitorado fosse "as instâncias respondendo", a metade de uma partição se acharia maioria e
-- promoveria a si mesma — o split-brain que este desenho existe para evitar. A vivacidade de cada
-- uma vem de `network_instance_readers` (V278), a mesma tabela que já diz quais instâncias estão
-- lendo de verdade: uma autoridade viva é uma que está declarada E deu sinal dentro da janela.
--
-- Nenhum nó externo escreve aqui; a promoção é operação administrativa auditada.

alter table network_state add column if not exists term int not null default 0;

alter table network_state add column if not exists authority_id varchar(64) null;

-- A linha global do modo nasce aqui e não na primeira transição: NetworkStatePersistenceAdapter
-- cria-a preguiçosamente ao gravar um modo (V277), e um banco que nunca passou por transição
-- admite não ter a linha. O CAS da promoção é `update ... where term = <lido>` — sem a linha,
-- atualiza ZERO linhas e a primeira promoção de um banco novo falharia sempre com
-- NETWORK_AUTHORITY_PROMOTION_LOST. "A linha da autoridade existe" é invariante do deploy, não
-- efeito colateral de alguém ter mudado o modo um dia. A linha existente (produção já transicionou
-- modo) não é tocada: `insert ... on duplicate key` grava só onde falta.
alter table network_state add column if not exists promoted_at timestamp null;

insert into network_state (id, mode, reason, since, term)
values ('global', 'NORMAL', 'seed inicial de autoridade (V288)', current_timestamp, 0)
on duplicate key update id = id;

create table if not exists network_authorities (
  instance_id varchar(64) not null,
  declared_at datetime(3) not null,
  declared_by varchar(40) not null,
  primary key (instance_id),
  key idx_network_authorities_declared (declared_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;