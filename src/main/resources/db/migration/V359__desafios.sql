-- Desafios: a experiência de desafio comunitário do Explorar — alguém convoca ("quem topa
-- desenhar o mascote só com formas geométricas?"), quem topa publica o que fez, quem convocou
-- acompanha e encerra escolhendo o que mais representou o desafio. SEM gamificação: não há
-- pontos, ranking, nível nem prêmio — o desafio termina com um reconhecimento nominal no
-- próprio desafio, e nada sai daqui para o feed geral, perfil, busca ou contadores.
--
-- Tabelas próprias, FORA de `posts` (isolação por construção, como Confessionário e Desabafos).
-- Autoria é pública e nominal: desafio é convite com responsável — diferente das histórias
-- anônimas. Sem FK (MariaDB errno 150 no ambiente da rede), tudo aditivo e idempotente.
--
-- desafios: o convite. Estados: ACTIVE (no ar, recebendo participação), CLOSED (encerrado
-- pelo autor com reconhecimento), REMOVED (saído do ar pelo autor ou moderação).
--
-- desafios_participacoes: o que cada participante publicou como resultado — texto, um por
-- pessoa por desafio (a chave (desafio, pessoa) é o próprio id da linha; republicar substitui).
--
-- desafios_reacoes: torcida nas participações ("torcendo"), uma por pessoa por participação.

create table if not exists desafios (
  id varchar(64) not null,
  author_id varchar(64) not null,
  title varchar(120) not null,
  description text not null,
  created_at timestamp not null,
  ends_at timestamp null,
  status varchar(20) not null,
  closed_at timestamp null,
  -- O reconhecimento do encerramento: o id da participação que mais representou o desafio.
  -- Preenchido no fechamento; desafia a leitura depois: desafio encerrado é história.
  destaque_id varchar(64) null,
  removed_at timestamp null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists desafios_participacoes (
  id varchar(64) not null,
  desafio_id varchar(64) not null,
  author_id varchar(64) not null,
  content text not null,
  created_at timestamp not null,
  status varchar(20) not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists desafios_reacoes (
  id varchar(140) not null,
  participacao_id varchar(64) not null,
  user_id varchar(64) not null,
  created_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create index if not exists idx_desafios_feed on desafios (created_at);
create index if not exists idx_desafios_participacoes_desafio on desafios_participacoes (desafio_id, created_at);
create index if not exists idx_desafios_reacoes_participacao on desafios_reacoes (participacao_id);
