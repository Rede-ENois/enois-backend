-- Banner e ícone do card de projeto: URLs https guardadas como texto.
-- Charset declarado na coluna para não depender do default do banco de produção
-- (o collation da base difere do das tabelas criadas antes).
alter table user_projects add column if not exists banner
  varchar(500) character set utf8mb4 collate utf8mb4_unicode_ci null;
alter table user_projects add column if not exists icon
  varchar(500) character set utf8mb4 collate utf8mb4_unicode_ci null;
