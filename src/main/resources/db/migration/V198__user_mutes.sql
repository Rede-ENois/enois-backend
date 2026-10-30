create table if not exists user_mutes (
  id bigint not null auto_increment,
  muter_id varchar(255) not null,
  muted_id varchar(255) not null,
  created_at timestamp not null,
  primary key (id),
  constraint uk_user_mutes_pair unique (muter_id, muted_id),
  key idx_user_mutes_muter (muter_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
