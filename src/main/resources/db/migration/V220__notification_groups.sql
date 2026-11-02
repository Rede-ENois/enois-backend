-- Grouped notifications: reactions to the same post (or comment) share one unread notification
-- ("Ana e mais 14 pessoas reagiram ao seu post.") instead of one row per reaction.
-- group_key identifies the target (e.g. NEW_REACTION:post:<id>); null = never grouped.
-- Existing rows keep group_key null and actor_count 1, so nothing changes for them.
alter table notifications add column if not exists group_key varchar(191) null;
alter table notifications add column if not exists actor_count int not null default 1;
create index if not exists idx_notifications_user_group_read on notifications (user_id, group_key, is_read);
