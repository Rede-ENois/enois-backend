-- Enóis Éroleplay: o motor de dados — rolagem sorteada no servidor e pedido do mestre.
--
-- A rolagem é o registro do que ACONTECEU na mesa: quem rolou, o que foi pedido, cada dado que
-- saiu e o total. Por isso a tabela é só-insertável na prática — a aplicação não expõe edição nem
-- remoção, e o histórico da mesa é a prova do que deu. `dice_json` guarda CADA DADO, para qualquer
-- um da mesa poder conferir a conta.
--
-- SEM chave estrangeira DE PROPÓSITO (collation do MariaDB, errno 150). Integridade na aplicação.
-- Idempotente: `if not exists` em tudo.

-- O registro imutável das rolagens da campanha.
create table if not exists roleplay_dice_rolls (
  id varchar(255) not null,
  campaign_id varchar(255) not null,
  roller_id varchar(255) not null,
  -- A expressão normalizada (ex.: "2d6+3"): o que foi pedido, como o servidor interpretou.
  expression varchar(60) not null,
  -- Cada dado sorteado, na ordem: a prova de que o total sai dos dados.
  dice_json longtext not null,
  total bigint not null,
  -- O que a mesa chamou a rolagem ("ataque"), quando informado.
  label varchar(120),
  -- PRIVATE | GAME_MASTER | CAMPAIGN: quem enxerga no histórico, na mesma régua da ficha.
  visibility varchar(16) not null default 'GAME_MASTER',
  -- O pedido do mestre que originou a rolagem, quando houve.
  request_id varchar(255),
  created_at timestamp not null,
  primary key (id),
  key idx_roleplay_rolls_campaign (campaign_id, created_at),
  key idx_roleplay_rolls_request (request_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- O pedido de rolagem que o mestre faz e o jogador responde (PENDENTE | RESPONDIDO).
create table if not exists roleplay_roll_requests (
  id varchar(255) not null,
  campaign_id varchar(255) not null,
  master_id varchar(255) not null,
  player_id varchar(255) not null,
  expression varchar(60) not null,
  label varchar(120),
  -- O teto de visibilidade que a resposta pode ter (o jogador pode fechar mais).
  max_visibility varchar(16) not null default 'GAME_MASTER',
  -- O motivo declarado pelo mestre ("percepção na porta"), quando informado.
  reason varchar(300),
  status varchar(16) not null default 'PENDING',
  answered_roll_id varchar(255),
  created_at timestamp not null,
  answered_at timestamp,
  primary key (id),
  key idx_roleplay_requests_campaign (campaign_id, status, created_at),
  key idx_roleplay_requests_player (campaign_id, player_id, status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
