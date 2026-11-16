-- Coleções pessoais: o dono organiza em pastas as publicações que JÁ salvou no perfil. São
-- privadas — só quem criou vê e mexe; sem FK (MariaDB errno 150 no ambiente da rede), tudo
-- aditivo e idempotente.

create table if not exists colecoes_salvas (
  id varchar(64) not null,
  owner_id varchar(64) not null,
  title varchar(120) not null,
  description varchar(500) null,
  created_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- Uma publicação por coleção: o id é determinístico (colecao:post) e o par é a própria
-- identidade — guardar a mesma publicação duas vezes nunca duplica linha.
create table if not exists colecao_salva_posts (
  id varchar(140) not null,
  colecao_id varchar(64) not null,
  post_id varchar(64) not null,
  created_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
