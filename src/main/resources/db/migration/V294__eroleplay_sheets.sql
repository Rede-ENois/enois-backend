-- Énóis Éroleplay: modelo de ficha do mestre e ficha preenchida pelo jogador.
--
-- A ficha é a parte mais privada do produto: ela guarda o que o jogador escreveu sobre o próprio
-- personagem, e no RPG de mesa isso costuma ser segredo até da mesa. Por isso duas tabelas
-- próprias e nada de coluna em tabela alheia: o acesso é sempre resolvido pela campanha, pelo
-- papel de quem lê e pela visibilidade de cada CAMPO.
--
-- SEM chave estrangeira DE PROPÓSITO (armadilha de collation do MariaDB, errno 150, que já
-- derrubou o boot). Integridade na aplicação, como no resto do banco.
--
-- Idempotente: `if not exists` em tudo.

-- O modelo da ficha: um por campanha, montado pelo mestre a partir dos atributos que o adapter do
-- sistema de regras declara. `fields_json` guarda o descritor de cada campo (chave, rótulo, tipo,
-- obrigatoriedade, opções e a visibilidade MÁXIMA permitida). É um retrato do atributo no momento
-- em que o modelo foi definido — a troca de sistema é recusada enquanto o modelo existir, então o
-- retrato não envelhece.
create table if not exists roleplay_sheet_templates (
  id varchar(255) not null,
  campaign_id varchar(255) not null,
  game_system varchar(80) not null,
  fields_json longtext not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id),
  unique key uk_roleplay_sheet_template_campaign (campaign_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- A ficha de uma pessoa numa campanha: uma por par (campanha, pessoa). `entries_json` guarda os
-- valores já validados e normalizados pelo servidor, cada um com a visibilidade efetiva escolhida
-- (que pode ser mais fechada que o máximo do modelo, nunca mais aberta).
create table if not exists roleplay_character_sheets (
  id varchar(255) not null,
  campaign_id varchar(255) not null,
  user_id varchar(255) not null,
  name varchar(120) not null,
  entries_json longtext not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id),
  unique key uk_roleplay_sheets_campaign_user (campaign_id, user_id),
  key idx_roleplay_sheets_campaign (campaign_id, updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;