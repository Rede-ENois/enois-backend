-- Silêncio escolhido pelo usuário nas configurações de notificação (desligado por padrão):
-- mute_reactions para de gerar aviso de reação (no post e no comentário); mute_post_comments
-- para de gerar aviso de comentário no post. Resposta ao próprio comentário (NEW_REPLY) e
-- menção (MENTION) seguem, porque chamam a pessoa diretamente. Aditivo e idempotente.
alter table notification_preferences add column if not exists mute_reactions boolean not null default false;
alter table notification_preferences add column if not exists mute_post_comments boolean not null default false;
