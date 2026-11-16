-- Murais: coleções colaborativas de publicações do feed. O mural é só o quadro — quem cola é
-- qualquer pessoa, apontando publicações que JÁ existem no feed; nada aqui cria post novo.
-- Sem FK (MariaDB errno 150 no ambiente da rede), tudo aditivo e idempotente.

create table if not exists murais (
  id varchar(64) not null,
  owner_id varchar(64) not null,
  title varchar(120) not null,
  description varchar(500) null,
  created_at timestamp not null,
  status varchar(20) not null,
  removed_at timestamp null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Uma publicação por mural: o id é determinístico (mural:post) e o par é a própria identidade —
-- recolocar a mesma publicação no mesmo mural nunca duplica linha.
create table if not exists mural_contribuicoes (
  id varchar(140) not null,
  mural_id varchar(64) not null,
  post_id varchar(64) not null,
  added_by varchar(64) not null,
  created_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
