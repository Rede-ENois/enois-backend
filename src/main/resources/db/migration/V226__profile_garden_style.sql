-- Profile slot for the garden style shop item (GARDEN id from the cosmetics catalog).
-- Existing rows keep null: nothing equipped, the default garden.
alter table users add column if not exists garden_style varchar(64) null;
