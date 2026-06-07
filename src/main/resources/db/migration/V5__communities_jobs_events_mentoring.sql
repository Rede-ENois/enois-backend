create table communities (
  id varchar(255) not null,
  name varchar(255) not null,
  description varchar(2000) not null,
  topic varchar(255),
  cover varchar(500),
  created_by varchar(255) not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id)
);

create index idx_communities_topic on communities (topic);

create table community_members (
  id bigint auto_increment,
  community_id varchar(255) not null,
  user_id varchar(255) not null,
  role varchar(32) not null,
  joined_at timestamp not null,
  primary key (id)
);

alter table community_members add constraint uk_community_member unique (community_id, user_id);
create index idx_community_members_community on community_members (community_id);
create index idx_community_members_user on community_members (user_id);

create table jobs (
  id varchar(255) not null,
  title varchar(255) not null,
  company varchar(255) not null,
  logo varchar(500),
  location varchar(255),
  type varchar(32) not null,
  level varchar(32) not null,
  salary varchar(255),
  posted_by varchar(255) not null,
  posted_at timestamp not null,
  updated_at timestamp not null,
  primary key (id)
);

create index idx_jobs_type on jobs (type);
create index idx_jobs_posted_at on jobs (posted_at);

create table job_skills (
  job_id varchar(255) not null,
  skill varchar(120) not null
);

create index idx_job_skills_job on job_skills (job_id);

create table job_applications (
  id bigint auto_increment,
  job_id varchar(255) not null,
  user_id varchar(255) not null,
  status varchar(32) not null,
  applied_at timestamp not null,
  primary key (id)
);

alter table job_applications add constraint uk_job_application unique (job_id, user_id);
create index idx_job_applications_job on job_applications (job_id);
create index idx_job_applications_user on job_applications (user_id);

create table job_saves (
  id bigint auto_increment,
  job_id varchar(255) not null,
  user_id varchar(255) not null,
  saved_at timestamp not null,
  primary key (id)
);

alter table job_saves add constraint uk_job_save unique (job_id, user_id);

create table events (
  id varchar(255) not null,
  title varchar(255) not null,
  date varchar(255) not null,
  time varchar(255) not null,
  format varchar(32) not null,
  host varchar(255) not null,
  cover varchar(500),
  created_by varchar(255) not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id)
);

create index idx_events_format on events (format);

create table event_attendees (
  id bigint auto_increment,
  event_id varchar(255) not null,
  user_id varchar(255) not null,
  responded_at timestamp not null,
  primary key (id)
);

alter table event_attendees add constraint uk_event_attendee unique (event_id, user_id);
create index idx_event_attendees_event on event_attendees (event_id);

create table mentors (
  id varchar(255) not null,
  user_id varchar(255) not null unique,
  expertise varchar(1000),
  rating double not null,
  sessions_count int not null,
  price varchar(255),
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id)
);

create table mentor_slots (
  id varchar(255) not null,
  mentor_id varchar(255) not null,
  starts_at timestamp not null,
  ends_at timestamp not null,
  available boolean not null,
  primary key (id)
);

create index idx_mentor_slots_mentor on mentor_slots (mentor_id);

create table mentor_sessions (
  id varchar(255) not null,
  mentor_id varchar(255) not null,
  mentee_id varchar(255) not null,
  slot_id varchar(255),
  message varchar(1000),
  status varchar(32) not null,
  created_at timestamp not null,
  primary key (id)
);

create index idx_mentor_sessions_mentor on mentor_sessions (mentor_id);
create index idx_mentor_sessions_mentee on mentor_sessions (mentee_id);

alter table community_members add constraint fk_community_members_community foreign key (community_id) references communities (id);
alter table job_skills add constraint fk_job_skills_job foreign key (job_id) references jobs (id);
alter table job_applications add constraint fk_job_applications_job foreign key (job_id) references jobs (id);
alter table event_attendees add constraint fk_event_attendees_event foreign key (event_id) references events (id);
alter table mentor_slots add constraint fk_mentor_slots_mentor foreign key (mentor_id) references mentors (id);
alter table mentor_sessions add constraint fk_mentor_sessions_mentor foreign key (mentor_id) references mentors (id);
