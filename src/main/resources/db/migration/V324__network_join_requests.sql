-- Énóis Distributed Network: inscrição pública para servir um nó.
--
-- Até aqui havia dois caminhos para um nó existir: o admin criava um token de provisionamento, ou
-- um participante já autenticado pedia o dele (`POST /network/nodes/join-request`). Nenhum dos dois
-- serve para quem quer oferecer uma máquina sem já ter conta: o pedido precisa chegar à
-- administração com identificação da pessoa (RG, CPF), a justificativa de por que quer participar e
-- o aceite do contrato de responsabilidade, e só depois de aprovado a pessoa recebe um link de uso
-- único que mostra a chave — uma vez.
--
-- Por que RG e CPF: o nó passa a ser um ponto da rede que transporta e guarda conteúdo de outras
-- pessoas. A administração precisa saber quem responde por ele. Os dois ficam guardados só para a
-- decisão administrativa; a listagem os mostra mascarados e nenhuma rota pública os devolve.
--
-- `reveal_token_hash` guarda o SHA-256 do token do link de e-mail — o link em claro não é
-- persistido, do mesmo jeito que o token de reset de senha. `reveal_consumed_at` é o uso único: a
-- chave é gerada NO MOMENTO da exibição (não antes), então antes de o link ser aberto não existe
-- chave nenhuma para vazar; depois de exibida, a linha não tem como mostrá-la de novo.
--
-- `network_node_responsibles` é o retrato de quem responde pelo nó. Fica em tabela própria em vez
-- de coluna em `network_nodes` porque o nó é do domínio da rede e o responsável é do fluxo de
-- inscrição: separar deixa claro que um nó sem linha aqui é um nó provisionado pelo caminho
-- administrativo antigo, e que o painel resolve o responsável pela conta dona (`owner_user_id`).
--
-- Aditiva e idempotente, como as demais deste módulo.

create table if not exists network_join_requests (
  id varchar(40) not null,
  full_name varchar(120) not null,
  email varchar(200) not null,
  rg varchar(20) not null,
  cpf char(11) not null,
  motivation text not null,
  term_version varchar(16) not null,
  accepted_at timestamp(3) not null,
  status varchar(16) not null,
  profiles varchar(255) not null default '',
  decided_by varchar(40) null,
  decided_at timestamp(3) null,
  decision_note varchar(500) null,
  reveal_token_hash char(64) null,
  reveal_expires_at timestamp(3) null,
  reveal_consumed_at timestamp(3) null,
  email_sent_at timestamp(3) null,
  created_at timestamp(3) not null,
  updated_at timestamp(3) not null,
  primary key (id),
  key idx_network_join_requests_status (status, created_at),
  key idx_network_join_requests_email (email),
  key idx_network_join_requests_cpf (cpf),
  key idx_network_join_requests_reveal (reveal_token_hash)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- O token de provisionamento passa a carregar quem pediu (nome e e-mail do formulário) quando ele
-- nasce de uma inscrição aprovada. Nulos quando o token veio do caminho administrativo antigo, que
-- só tem a conta dona.
alter table network_provision_tokens add column if not exists owner_name varchar(120) null;
alter table network_provision_tokens add column if not exists owner_email varchar(200) null;

create table if not exists network_node_responsibles (
  node_id varchar(40) not null,
  full_name varchar(120) not null,
  email varchar(200) not null,
  source varchar(16) not null,
  created_at timestamp(3) not null,
  primary key (node_id),
  constraint fk_network_node_responsibles_node
    foreign key (node_id) references network_nodes (id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;