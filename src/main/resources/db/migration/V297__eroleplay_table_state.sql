-- Énóis Éroleplay: estado da mesa — voz e posição, em duas peças.
--
-- 1) O vínculo da chamada: `call_sessions` ganha `table_id`, a mesa do Éroleplay que a sala
--    serve. É a mesma peça que o encontro (V256) e o tópico (V250): a autorização de entrar é
--    recalculada no servidor a partir do vínculo, nunca a partir da URL.
--
-- 2) O estado da mesa: turno, ordem de fala, cena visível e tokens posicionados. É estado DE
--    JOGO, vivo e reescrito a cada clique — separado do histórico (chat, rolagens), que só
--    acrescenta. Quem manda no estado é o mestre; token próprio move quem o possui.
--
-- Aditiva e idempotente; sem chave estrangeira DE PROPÓSITO (collation do MariaDB de produção).
alter table call_sessions
  add column if not exists table_id varchar(255) null after event_id;
create index if not exists idx_call_sessions_table on call_sessions (table_id, state);

create table if not exists roleplay_table_states (
  -- O id É o id da mesa: uma mesa tem no máximo um estado.
  id varchar(255) not null,
  table_id varchar(255) not null,
  campaign_id varchar(255) not null,
  -- Ordem de fala, na ordem; nulo = sem ordem definida. longtext com JSON de aplicação, como o
  -- `dice_json` (V295): coluna `json` nativa faz o driver gravar texto como literal de JSON.
  turn_order longtext null,
  -- Quem tem a vez agora; precisa estar na ordem quando a ordem existe.
  active_turn_user_id varchar(255),
  turn_started_at timestamp null,
  -- O que todos veem da cena; o detalhe secreto do mestre fica no chat privado (Fase 5).
  scene varchar(600),
  updated_at timestamp not null,
  primary key (id),
  unique key uk_roleplay_table_state_table (table_id),
  key idx_roleplay_table_state_campaign (campaign_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists roleplay_table_tokens (
  id varchar(255) not null,
  table_id varchar(255) not null,
  campaign_id varchar(255) not null,
  -- Quando owner_id é nulo, o token é do cenário (porta, baú, monstro): só o mestre move.
  owner_id varchar(255),
  label varchar(60) not null,
  -- Cor em hex do token, escolhida na criação; a renderização é do front.
  color varchar(9) not null,
  -- Posição em células do grid: inteiro de -9999 a 9999, sem fração.
  pos_x int not null default 0,
  pos_y int not null default 0,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id),
  key idx_roleplay_table_tokens_table (table_id, updated_at),
  key idx_roleplay_table_tokens_owner (owner_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
