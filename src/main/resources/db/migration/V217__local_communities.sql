-- Comunidades por cidade: cidade + UF estruturados em comunidades, perfis e eventos presenciais.
-- `city` guarda o texto como a pessoa escreveu; `city_key` e a forma de comparar (sem acento,
-- minuscula), calculada na aplicacao para funcionar igual em H2 e MariaDB e ser indexavel.
-- Linhas existentes ficam com null em tudo (sem local); `users.location` (texto livre) continua.
-- `users.show_city` nasce false: a cidade so aparece no perfil publico de quem optar.
alter table communities add column if not exists city varchar(80) null;
alter table communities add column if not exists state varchar(2) null;
alter table communities add column if not exists city_key varchar(80) null;
create index if not exists idx_communities_state_city on communities (state, city_key);

alter table users add column if not exists city varchar(80) null;
alter table users add column if not exists state varchar(2) null;
alter table users add column if not exists city_key varchar(80) null;
alter table users add column if not exists show_city boolean not null default false;

alter table events add column if not exists city varchar(80) null;
alter table events add column if not exists state varchar(2) null;
alter table events add column if not exists city_key varchar(80) null;
create index if not exists idx_events_state_city on events (state, city_key);
