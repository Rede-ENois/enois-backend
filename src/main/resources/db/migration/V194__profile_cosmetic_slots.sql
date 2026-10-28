-- Profile slots for the seasonal shop items (ids from the cosmetics catalog) and the status bubble.
-- Existing rows keep null in every new column: nothing equipped, no status.
alter table users add column if not exists card_theme varchar(64) null;
alter table users add column if not exists nameplate varchar(64) null;
alter table users add column if not exists banner_item varchar(64) null;
alter table users add column if not exists profile_effect varchar(64) null;
alter table users add column if not exists status_text varchar(60) null;
alter table users add column if not exists status_icon varchar(32) null;
