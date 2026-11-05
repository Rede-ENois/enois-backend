-- What reaches a person outside the app: the weekly digest e-mail and push by category.
-- One row per user, written on the first change. Existing accounts get no row, which means the
-- defaults (digest on, every push category on): nothing changes for them until they choose.
-- Security, moderation and sanctions are always pushed, so they have no column here.
create table if not exists notification_preferences (
  user_id varchar(255) not null,
  weekly_digest boolean not null default true,
  push_social boolean not null default true,
  push_messages boolean not null default true,
  push_groups boolean not null default true,
  push_jobs boolean not null default true,
  push_system boolean not null default true,
  updated_at timestamp not null,
  primary key (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- One weekly digest per person per ISO week ("2026-W44"). The primary key is the idempotency
-- rule: the row is claimed before the e-mail goes out, so a rerun or a second instance skips it.
create table if not exists digest_deliveries (
  user_id varchar(255) not null,
  week_key varchar(10) not null,
  sent_at timestamp not null,
  primary key (user_id, week_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
