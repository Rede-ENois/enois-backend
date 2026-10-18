-- Jogos diarios: Blocos (tetris diario com replay deterministico por acao).
-- Idempotente por convencao do projeto (see V68-V82 apos o rollout quebrado com MySQL 1061):
-- `create table if not exists` em tudo e unicidades nomeadas — indice unico anonimo ganha nome
-- automatico do MySQL (a primeira coluna) e e exatamente a classe de fragilidade que derruba o boot.
create table if not exists daily_blocks_challenges (
  challenge_date date not null primary key,
  seed bigint not null,
  target_lines int not null,
  rules_version int not null
);
create table if not exists daily_blocks_sessions (
  challenge_date date not null,
  user_id varchar(255) not null,
  started_at timestamp(3) not null,
  last_action_at timestamp(3) null,
  completed_at timestamp(3) null,
  status varchar(16) not null,
  score int not null,
  lines int not null,
  primary key (challenge_date,user_id)
);
create table if not exists daily_blocks_actions (
  challenge_date date not null,
  user_id varchar(255) not null,
  action_no int not null,
  request_id varchar(80) not null,
  action varchar(16) not null,
  created_at timestamp(3) not null default current_timestamp(3),
  primary key (challenge_date,user_id,action_no),
  constraint uk_daily_blocks_actions_request unique (challenge_date,user_id,request_id)
);
