-- Daily games: a finished run that is faster than a person can play keeps its result for the
-- player but is stored with a flag and stays out of the ranking and the prize queue.
-- Additive and idempotent: existing sessions keep integrity_flag null (unflagged), nothing is
-- rewritten. ASCII-only value, so the column follows the table's charset.
alter table daily_word_sessions add column if not exists integrity_flag varchar(32) null;
alter table daily_maze_sessions add column if not exists integrity_flag varchar(32) null;
