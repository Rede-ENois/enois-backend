-- Disponibilidade histórica por nó (brief §7, V368).
--
-- O QUE FALTAVA, com evidência: "disponibilidade histórica" era o último item do §7 marcado como não
-- entregue (doc 17 §3), por duas razões medidas no código:
--
--   1. `NetworkParticipantService.availability` soma o tempo fora das 200 linhas MAIS RECENTES de
--      `NETWORK_NODE_RECONNECTED` e compara com o TOTAL de quedas. A partir da 201ª reconexão,
--      `complete` vira `false` para sempre e o tempo fora deixa de ser total — vira piso. Soma
--      certa, referência errada.
--   2. A trilha de auditoria registra TRANSIÇÕES (quantas vezes o nó voltou), não uma série. Não
--      existe nada que responda "ficou no ar 99,2% dos últimos 7 dias".
--
-- O QUE ESTA TABELA É: um contador por nó e por dia. `present_seconds` acumula o tempo em que o nó
-- esteve dentro da janela de "vivo" (o mesmo `alive(lastSeenAt, staleAfter)` do painel e do
-- participante), somado entre dois heartbeats consecutivos. `observed_seconds` é a janela em que o
-- Control Plane teve informação sobre ele naquele dia — a base do "esteve no ar". Ausência no
-- coração de um dia (antes do primeiro heartbeat, depois do último) NÃO conta como queda, e sim como
-- falta de dado: o Control Plane não pode afirmar o que não mediu.
--
-- A tabela é pequena de propósito: uma linha por nó por dia. É retenção de longo prazo (o padrão é um
-- ano), não a janela curta de 60 minutos de `network_node_samples` (V325), que continua sendo o
-- gráfico ao vivo — papéis diferentes, tabelas diferentes.
--
-- `measured` diz se o dia tem pelo menos uma medida; dia sem linha é "sem dado", não "0% no ar". Quem
-- lê soma os dois contadores e divide, e não mistura um dia não medido com um dia de queda.
--
-- Aditiva e idempotente: tabela nova, nenhum dado antigo tocado. A FK espelha as tabelas de amostra
-- do V325 — nó apagado não deixa contador órfão.

create table if not exists network_node_uptime_daily (
  node_id varchar(40) not null,
  dia date not null,
  -- Segundos do dia em que ele esteve dentro da janela de "vivo".
  present_seconds bigint not null default 0,
  -- Segundos do dia sobre os quais houve medida (entre o primeiro e o último heartbeat do dia).
  observed_seconds bigint not null default 0,
  -- `datetime(3)` e não `timestamp(3)`: o instante é UTC e não pode depender da zona da SESSÃO.
  -- `TIMESTAMP` no MariaDB converte na escrita e na leitura pela hora da sessão — com DSN em outro
  -- fuso, a mesma linha mudaria de valor conforme quem a lê, e a comparação "trecho posterior ao já
  -- contado" erraria de 3 h em toda leitura. `datetime` guarda o valor como está.
  first_seen_at datetime(3) not null,
  last_seen_at datetime(3) not null,
  -- O fim do trecho que chegou POR ÚLTIMO, qualquer que seja a ordem dos contatos. É ele que diz
  -- se o próximo trecho é o mesmo heartbeat republicado: comparar com `last_seen_at` (o maior
  -- instante) não serviria, porque um trecho fora de ordem não muda o maior e o reenvio dele
  -- passaria como "novo".
  received_at datetime(3) not null,
  primary key (node_id, dia),
  key idx_network_node_uptime_daily_dia (dia),
  constraint fk_network_node_uptime_daily_node
    foreign key (node_id) references network_nodes (id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;