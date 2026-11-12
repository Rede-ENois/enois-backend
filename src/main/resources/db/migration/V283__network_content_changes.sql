-- Énóis Distributed Network (fase 3): feed de mudanças do conteúdo público.
--
-- O que a fase 3 pede para o cache distribuído (docs/rede-distribuida/07-plano-incremental.md):
-- "só conteúdo público; mutável com TTL curto e invalidação por evento". A metade do "só público"
-- já está garantida desde a V279 — o tipo é a allowlist `ContentKind`. Faltava a invalidação: um nó
-- de borda só sabia que um agregado mudou perguntando por ele (`?version=`), um por um. Quem tem
-- mil agregados em cache não tem como descobrir quais mudaram, e conteúdo apagado nunca convirgiria.
--
-- Esta tabela é o delta, não o conteúdo: cada linha diz "esta versão deste agregado foi cunhada
-- agora". O nó guarda o `seq` até onde leu e pede o que veio depois. Não há coluna de consumo POR
-- NÓ porque o feed é compartilhado: a posição é do cliente, não do servidor (mesma razão da V278).
--
-- Por que `seq` auto_increment e não `updated_at` da versão: dois agregados podem ser cunhados no
-- mesmo milissegundo, e o cursor precisa de uma ordem total — uma hora repetida faria o nó pular
-- (ou reler para sempre) a linha do empate. `seq` é monotônica e estritamente crescente.
--
-- A existência do agregado NÃO vira linha aqui. O feed só carrega versão cunhada, e versão só é
-- cunhada sobre corpo existente. A descoberta de que um agregado sumiu é do nó: ele guarda uma
-- entrada com TTL curto e, ao revalidar, recebe 404 do Control Plane e descarta a entrada local. A
-- reconciliação (V281) fecha o ciclo pelo outro lado. Inventar um "tombstone" por nó aqui
-- multiplicaria linhas pelo número de nós que guardaram o mesmo agregado para dizer a mesma coisa.
--
-- Retenção: o feed é janela, não histórico. A varredura apaga o que passou da janela, e um nó com
-- cursor mais velho que a menor linha retida recebe `reset=true` — nunca "nada mudou". É o caso que
-- precisa estar certo: responder silêncio para um cursor podado deixaria o nó servindo conteúdo
-- velho para sempre achando que está em dia.

create table if not exists network_content_changes (
  seq bigint not null auto_increment,
  content_type varchar(32) not null,
  content_id varchar(64) not null,
  version bigint not null,
  content_hash char(64) not null,
  changed_at datetime(3) not null,
  primary key (seq),
  key idx_network_content_changes_retention (changed_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;