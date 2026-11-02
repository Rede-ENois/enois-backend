create table if not exists user_presence (
  user_id varchar(64) not null,
  status varchar(24) not null,
  last_seen_at timestamp not null,
  primary key (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
