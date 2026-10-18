-- Jogos diarios: Palavra (estilo Wordle, 6 tentativas, retomada no dia anterior).
-- Idempotente por convencao do projeto (see V68-V82 apos o rollout quebrado com MySQL 1061):
-- `create table if not exists` em tudo e unicidades nomeadas — indice unico anonimo ganha nome
-- automatico do MySQL (a primeira coluna) e e exatamente a classe de fragilidade que derruba o boot.
create table if not exists daily_word_challenges (
  challenge_date date not null primary key,
  answer varchar(5) not null
);
create table if not exists daily_word_sessions (
  challenge_date date not null,
  user_id varchar(255) not null,
  status varchar(16) not null,
  started_at timestamp(3) not null,
  completed_at timestamp(3) null,
  primary key (challenge_date, user_id)
);
create table if not exists daily_word_guesses (
  challenge_date date not null,
  user_id varchar(255) not null,
  attempt_no int not null,
  request_id varchar(80) not null,
  guess varchar(5) not null,
  marks varchar(64) not null,
  primary key (challenge_date, user_id, attempt_no),
  constraint uk_daily_word_guesses_request unique (challenge_date, user_id, request_id)
);
