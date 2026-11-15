-- Énóis Éroleplay: World Builder — as entidades do mundo da campanha.
--
-- O mundo é do GRUPO, não do mestre e não do sistema de regras: local, personagem do cenário,
-- item lendário, pista e lore são a mesma coisa de natureza diferente, e qualquer participante
-- pode registrar. O que muda por sistema é a ficha (V294), não a lore. Título + corpo + tags
-- (busca); vinculação com ficha é opcional e nunca sai na API — sai o nome do personagem.
--
-- Aditiva e idempotente; sem chave estrangeira DE PROPÓSITO (collation do MariaDB de produção).
create table if not exists roleplay_world_entries (
  id varchar(255) not null,
  campaign_id varchar(255) not null,
  kind varchar(20) not null,
  title varchar(120) not null,
  body longtext null,
  tags varchar(600) null,
  linked_sheet_id varchar(255) null,
  author_id varchar(255) not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id),
  key idx_roleplay_world_entries_campaign (campaign_id, updated_at),
  key idx_roleplay_world_entries_author (author_id),
  key idx_roleplay_world_entries_kind (campaign_id, kind, updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
