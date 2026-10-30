-- O aviso da plataforma que os administradores publicam do painel (banner e/ou modal).
--
-- Uma linha só, sempre: o aviso vigente é único e publicar por cima substitui o anterior.
-- A chave fixa "ativo" é o mesmo expediente das tabelas de ledger/config do projeto
-- (system_announcements, support_settings) — sem ela, dois avisos publicados por admins
-- diferentes apareceriam juntos na mesma tela.
--
-- updated_at é o que o front usa como chave de dispensa: editar o aviso renova a data e o
-- popup volta a aparecer para quem já tinha fechado a versão anterior.
create table if not exists admin_notice (
  id varchar(32) not null,
  title varchar(140) not null,
  body_html longtext not null,
  surface varchar(16) not null,
  published_at timestamp not null,
  updated_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
