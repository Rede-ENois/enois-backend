alter table users add column reputation int not null default 0;
create index idx_users_reputation on users (reputation desc);
