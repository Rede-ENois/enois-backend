-- Sistema de notificacoes de novas features, layout e design system.
-- whats_new_enabled: permite ao usuario ignorar/desativar os avisos no perfil da conta.
-- whats_new_last_seen: versao da ultima novidade vista pelo usuario em qualquer dispositivo.
alter table user_preferences add column if not exists whats_new_enabled boolean not null default true;
alter table user_preferences add column if not exists whats_new_last_seen varchar(64) null;
