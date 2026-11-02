-- "Voce esta em dia" marker: one row per person with the newest feed moment they have already seen.
-- GET /feed returns it on the first page so the front can draw the divider; POST /feed/seen only moves
-- it forward. New table, no existing rows are touched: everyone starts without a marker (null).
create table if not exists feed_seen_markers (
  user_id varchar(64) not null,
  last_seen_at datetime(6) not null,
  updated_at datetime(6) not null,
  primary key (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
