-- Study pairs: two people with the same goal check in once a week. New tables only; no existing
-- row changes. A request waits OPEN until someone with the same goal_key (normalized slug of the
-- goal) shows up, then both become MATCHED and a study_pairs row is created.
create table if not exists study_pair_requests (
  id varchar(64) not null,
  user_id varchar(64) not null,
  goal_key varchar(120) not null,
  goal_text varchar(120) not null,
  topic varchar(40),
  status varchar(16) not null,
  created_at timestamp not null,
  primary key (id),
  key idx_study_pair_requests_open (status, goal_key, created_at),
  key idx_study_pair_requests_user (user_id, status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists study_pairs (
  id varchar(64) not null,
  user_a varchar(64) not null,
  user_b varchar(64) not null,
  goal_key varchar(120) not null,
  goal_text varchar(120) not null,
  topic varchar(40),
  status varchar(16) not null,
  started_at timestamp not null,
  ended_at timestamp null,
  primary key (id),
  key idx_study_pairs_user_a (user_a, status),
  key idx_study_pairs_user_b (user_b, status),
  key idx_study_pairs_status (status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- One check-in per member per ISO week (week_key like 2026-W44, America/Sao_Paulo); the primary
-- key is the rule, and an edit in the same week updates the row.
create table if not exists study_checkins (
  pair_id varchar(64) not null,
  user_id varchar(64) not null,
  week_key varchar(10) not null,
  note text,
  created_at timestamp not null,
  updated_at timestamp not null,
  primary key (pair_id, user_id, week_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- One weekly reminder per member per pair, claimed by inserting this row before sending: with
-- several replicas the scheduler fires on each, and only the insert that wins sends.
create table if not exists study_pair_reminders (
  pair_id varchar(64) not null,
  user_id varchar(64) not null,
  week_key varchar(10) not null,
  created_at timestamp not null,
  primary key (pair_id, user_id, week_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
