-- Daily presence streak, stored instead of recomputed from coin_entries, so the weekly freeze can be
-- remembered. One row per person. last_day is the last day the streak covers (active or frozen);
-- last_frozen_day is the last day saved by the weekly freeze (one per ISO week, derived from it).
-- Existing people start WITHOUT a row: on their next action the row is created lazily from the
-- presence rewards already in coin_entries (last 60 days), so no current streak is lost. No backfill.
create table if not exists user_streaks (
  user_id varchar(255) not null,
  current_days int not null default 0,
  longest_days int not null default 0,
  last_day date null,
  last_frozen_day date null,
  updated_at timestamp not null,
  primary key (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
