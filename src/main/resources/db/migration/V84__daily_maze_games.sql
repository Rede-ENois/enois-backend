-- Jogos diarios: Labirinto (maze diario deterministico por seed, trilha de movimentos).
-- Idempotente por convencao do projeto (see V68-V82 apos o rollout quebrado com MySQL 1061):
-- `create table if not exists` em tudo; sem indice unico anonimo (o request_id ja e PK composta).
create table if not exists daily_maze_challenges (
  challenge_date date not null primary key,
  maze_seed bigint not null
);
create table if not exists daily_maze_sessions (
  challenge_date date not null,
  user_id varchar(255) not null,
  position int not null,
  moves int not null,
  started_at timestamp(3) not null,
  completed_at timestamp(3) null,
  primary key (challenge_date,user_id)
);
create table if not exists daily_maze_moves (
  challenge_date date not null,
  user_id varchar(255) not null,
  request_id varchar(80) not null,
  direction varchar(5) not null,
  primary key (challenge_date,user_id,request_id)
);
