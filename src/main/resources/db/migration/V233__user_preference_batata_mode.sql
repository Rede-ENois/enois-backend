-- Modo BATATA: interface classica e economica, para aparelho com pouca memoria e processador fraco.
-- E uma camada ACIMA do modo lite (o lite continua ligado pelo termo); default false mantem a
-- experiencia completa para quem ja usa a conta.
alter table user_preferences
  add column if not exists batata_mode boolean not null default false;