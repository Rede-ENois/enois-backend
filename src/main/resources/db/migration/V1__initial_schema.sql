create table users (
  id varchar(255) not null,
  username varchar(80) not null,
  name varchar(160) not null,
  headline varchar(180),
  email varchar(255) not null,
  company varchar(160),
  location varchar(160),
  avatar varchar(500),
  banner varchar(500),
  bio varchar(1024),
  availability varchar(160),
  portfolio varchar(500),
  open_to_work boolean not null,
  is_mentor boolean not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id)
);

alter table users add constraint uk_users_username unique (username);
alter table users add constraint uk_users_email unique (email);
create index idx_users_name on users (name);

create table user_skills (
  user_id varchar(255) not null,
  skill varchar(120) not null
);

create table user_goals (
  user_id varchar(255) not null,
  goal varchar(160) not null
);

create table user_external_links (
  user_id varchar(255) not null,
  external_link varchar(500) not null
);

create table user_credentials (
  user_id varchar(255) not null,
  email varchar(255) not null,
  password_hash varchar(500) not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (user_id)
);

alter table user_credentials add constraint uk_user_credentials_email unique (email);

create table auth_sessions (
  token varchar(255) not null,
  user_id varchar(255) not null,
  created_at timestamp not null,
  expires_at timestamp not null,
  revoked_at timestamp,
  primary key (token)
);

create index idx_auth_sessions_user_expires on auth_sessions (user_id, expires_at);

create table posts (
  id varchar(255) not null,
  author_id varchar(255) not null,
  type varchar(32) not null,
  content varchar(1024) not null,
  created_at timestamp not null,
  visibility varchar(32) not null,
  reactions integer not null,
  comments integer not null,
  saves integer not null,
  shares integer not null,
  status varchar(32) not null,
  deleted_at timestamp,
  primary key (id)
);

create index idx_posts_author_created on posts (author_id, created_at);
create index idx_posts_author_status_created on posts (author_id, status, created_at);
create index idx_posts_created on posts (created_at);

create table post_tags (
  post_id varchar(255) not null,
  tag varchar(255) not null
);

create index idx_post_tags_tag on post_tags (tag);

create table post_media (
  post_id varchar(255) not null,
  media_id varchar(255) not null
);

create table idempotency_keys (
  id bigint auto_increment,
  user_id varchar(255) not null,
  method varchar(255) not null,
  route varchar(255) not null,
  idempotency_key varchar(255) not null,
  request_hash varchar(255) not null,
  status varchar(32) not null,
  response_status integer,
  response_body longtext,
  created_at timestamp not null,
  expires_at timestamp not null,
  completed_at timestamp,
  primary key (id)
);

alter table idempotency_keys add constraint uk_idempotency_key_scope unique (user_id, method, route, idempotency_key);
create index idx_idempotency_expires_at on idempotency_keys (expires_at);

create table outbox_events (
  id varchar(255) not null,
  event_type varchar(255) not null,
  aggregate_id varchar(255) not null,
  payload longtext not null,
  status varchar(255) not null,
  created_at timestamp not null,
  next_attempt_at timestamp not null,
  primary key (id)
);

create index idx_outbox_status_next_attempt on outbox_events (status, next_attempt_at);

create table processed_events (
  event_id varchar(255) not null,
  processed_at timestamp not null,
  primary key (event_id)
);

create table audit_logs (
  id varchar(255) not null,
  actor_id varchar(255) not null,
  action varchar(255) not null,
  target_id varchar(255) not null,
  metadata longtext,
  created_at timestamp not null,
  primary key (id)
);

create index idx_audit_actor_created on audit_logs (actor_id, created_at);

alter table user_skills add constraint fk_user_skills_user foreign key (user_id) references users (id);
alter table user_goals add constraint fk_user_goals_user foreign key (user_id) references users (id);
alter table user_external_links add constraint fk_user_external_links_user foreign key (user_id) references users (id);
alter table user_credentials add constraint fk_user_credentials_user foreign key (user_id) references users (id);
alter table auth_sessions add constraint fk_auth_sessions_user foreign key (user_id) references users (id);
alter table post_tags add constraint fk_post_tags_post foreign key (post_id) references posts (id);
alter table post_media add constraint fk_post_media_post foreign key (post_id) references posts (id);
