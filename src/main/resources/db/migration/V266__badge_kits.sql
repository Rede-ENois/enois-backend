-- Historico de kits de emblemas: o conjunto anterior de emblemas de um perfil, guardado no
-- momento em que ele muda (PUT /me/badges com conteudo diferente do atual).
--
-- Guarda as URLs/ids dos emblemas, separados por linha — o mesmo formato da coluna users.badges,
-- para aplicar um kit de volta ser so trocar a lista. Nao guarda arquivo, so referencia: o asset
-- continua na media, onde sempre esteve.
--
-- Mantem os 3 kits mais recentes por pessoa (o mais antigo sai quando o quarto entra).

create table if not exists badge_kits (
  id varchar(255) not null,
  user_id varchar(255) not null,
  badges text not null,
  created_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create index idx_badge_kits_user on badge_kits (user_id, created_at desc);
