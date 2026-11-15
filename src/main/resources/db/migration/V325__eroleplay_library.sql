-- Énóis Éroleplay: biblioteca — a ficha que o dono escolher PUBLICAR sai da campanha.
--
-- A publicação é uma CÓPIA deliberada: o dono decide o momento (opt-in), e o que sai é o recorte
-- que ele já abriu à campanha — campo privado nunca vira público porque nunca entra na cópia.
-- O id interno da ficha fica aqui dentro e não atravessa a API pública (a biblioteca responde
-- pelo id da publicação); despublicar apaga a cópia, nunca a ficha.
--
-- Aditiva e idempotente; sem chave estrangeira DE PROPÓSITO (collation do MariaDB de produção).
create table if not exists roleplay_published_sheets (
  id varchar(255) not null,
  campaign_id varchar(255) not null,
  sheet_id varchar(255) not null,
  owner_id varchar(255) not null,
  character_name varchar(120) not null,
  game_system varchar(80) not null,
  fields_json longtext null,
  published_at timestamp not null,
  updated_at timestamp not null,
  primary key (id),
  unique key uk_roleplay_published_sheets_sheet (sheet_id),
  key idx_roleplay_published_sheets_published (published_at),
  key idx_roleplay_published_sheets_owner (owner_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
