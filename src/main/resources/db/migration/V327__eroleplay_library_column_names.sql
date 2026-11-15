-- Éroleplay: garante as colunas que a entidade da biblioteca mapeia em bancos que já passaram por 326.
--
-- Por que existe: o boot da QA falhou com
--   Schema validation: missing column [campaignId] in table [roleplay_published_sheets]
-- e o `ddl-auto=validate` só roda no container, contra o banco já migrado. Um banco criado do zero
-- pelo V326 valida (o gate de CI faz isso e passa); um banco que já tinha a versão 326 aplicada de
-- um build anterior fica com a tabela num formato que não casa com a entidade, e o Flyway não
-- reaplica um número que já está na história. Migração aplicada não se edita, então o caminho
-- aditivo é uma versão nova.
--
-- Aditiva, idempotente, sem chave estrangeira (collation do MariaDB de produção), como o V326.
-- `if not exists` é a forma que os dois bancos do projeto aceitam (o H2 da suíte e o MariaDB).
create table if not exists roleplay_published_sheets (
  id varchar(255) not null,
  campaign_id varchar(255) null,
  sheet_id varchar(255) null,
  owner_id varchar(255) null,
  character_name varchar(120) null,
  game_system varchar(80) null,
  fields_json longtext null,
  published_at timestamp null,
  updated_at timestamp null,
  primary key (id),
  unique key uk_roleplay_published_sheets_sheet (sheet_id),
  key idx_roleplay_published_sheets_published (published_at),
  key idx_roleplay_published_sheets_owner (owner_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- A entidade passou a nomear cada coluna (@Column(name = "campaign_id")). Se o banco veio do 326
-- antigo, a coluna existe com o nome do campo; a versão nova cria a forma snake_case ao lado,
-- nullable, e copia o valor. Os dois nomes convivem porque apagar coluna é destrutivo e não é
-- preciso para o boot.
alter table roleplay_published_sheets add column if not exists campaign_id varchar(255) null;
alter table roleplay_published_sheets add column if not exists sheet_id varchar(255) null;
alter table roleplay_published_sheets add column if not exists owner_id varchar(255) null;
alter table roleplay_published_sheets add column if not exists character_name varchar(120) null;
alter table roleplay_published_sheets add column if not exists game_system varchar(80) null;
alter table roleplay_published_sheets add column if not exists fields_json longtext null;
alter table roleplay_published_sheets add column if not exists published_at timestamp null;
alter table roleplay_published_sheets add column if not exists updated_at timestamp null;

-- O atlas do mundo veio no mesmo PR e tem os mesmos pares de nome.
alter table roleplay_world_entries add column if not exists campaign_id varchar(255) null;
alter table roleplay_world_entries add column if not exists linked_sheet_id varchar(255) null;
alter table roleplay_world_entries add column if not exists author_id varchar(255) null;
alter table roleplay_world_entries add column if not exists created_at timestamp null;
alter table roleplay_world_entries add column if not exists updated_at timestamp null;