create table notifications (
  id varchar(255) not null,
  user_id varchar(255) not null,
  kind varchar(64) not null,
  actor_id varchar(255) not null,
  title varchar(500) not null,
  body varchar(2000),
  href varchar(500),
  cta_label varchar(255),
  read boolean not null,
  created_at timestamp with time zone not null,
  primary key (id)
);

create index idx_notifications_user_read_created on notifications (user_id, read, created_at);
