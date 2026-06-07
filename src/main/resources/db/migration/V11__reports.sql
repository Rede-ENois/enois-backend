create table reports (
  id varchar(255) not null,
  reporter_id varchar(255) not null,
  target_type varchar(32) not null,
  target_id varchar(255) not null,
  reason varchar(32) not null,
  description varchar(2000),
  status varchar(32) not null,
  created_at timestamp not null,
  reviewed_by varchar(255),
  reviewed_at timestamp,
  resolution_note varchar(1000),
  primary key (id)
);

create index idx_reports_status_created on reports (status, created_at);
create index idx_reports_target on reports (target_type, target_id);
create index idx_reports_reporter on reports (reporter_id);

alter table reports add constraint fk_reports_reporter foreign key (reporter_id) references users (id);
