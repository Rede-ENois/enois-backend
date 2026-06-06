create table media_assets (
  id varchar(255) not null,
  owner_id varchar(255) not null,
  file_name varchar(255) not null,
  mime_type varchar(120) not null,
  size_bytes bigint not null,
  purpose varchar(80),
  object_key varchar(500) not null,
  cdn_url varchar(500) not null,
  status varchar(32) not null,
  delete_status varchar(32) not null,
  created_at timestamp with time zone not null,
  expires_at timestamp with time zone not null,
  deleted_at timestamp with time zone,
  primary key (id)
);

create index idx_media_assets_owner_created on media_assets (owner_id, created_at);
create index idx_media_assets_delete_status on media_assets (delete_status);

create table media_deletion_jobs (
  id varchar(255) not null,
  media_id varchar(255) not null,
  post_id varchar(255),
  owner_id varchar(255) not null,
  status varchar(32) not null,
  attempts integer not null,
  created_at timestamp with time zone not null,
  next_attempt_at timestamp with time zone not null,
  processed_at timestamp with time zone,
  error_message varchar(1024),
  primary key (id)
);

alter table media_deletion_jobs add constraint uk_media_deletion_jobs_media unique (media_id);
create index idx_media_deletion_jobs_status_next on media_deletion_jobs (status, next_attempt_at);
alter table media_deletion_jobs add constraint fk_media_deletion_jobs_media foreign key (media_id) references media_assets (id);
