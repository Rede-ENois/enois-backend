create table user_experiences (
  id varchar(255) not null,
  user_id varchar(255) not null references users(id),
  title varchar(255) not null,
  company varchar(255) not null,
  location varchar(255),
  description text,
  currently_here boolean not null default false,
  start_date date not null,
  end_date date,
  display_order integer not null default 0,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id)
);
create index idx_user_experiences_user on user_experiences (user_id);

create table user_education (
  id varchar(255) not null,
  user_id varchar(255) not null references users(id),
  school varchar(255) not null,
  degree varchar(255) not null,
  field varchar(255),
  description text,
  currently_here boolean not null default false,
  start_date date not null,
  end_date date,
  display_order integer not null default 0,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id)
);
create index idx_user_education_user on user_education (user_id);

create table user_certifications (
  id varchar(255) not null,
  user_id varchar(255) not null references users(id),
  name varchar(255) not null,
  issuer varchar(255) not null,
  description text,
  issue_date date not null,
  expiration_date date,
  credential_url varchar(500),
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (id)
);
create index idx_user_certifications_user on user_certifications (user_id);
