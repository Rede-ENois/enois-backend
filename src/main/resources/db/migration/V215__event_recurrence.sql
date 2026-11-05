-- Recurring events ("rituais"): a series repeats WEEKLY, BIWEEKLY or MONTHLY. series_id is the id of
-- the first occurrence; every occurrence spawned by the scheduler carries it.
-- Existing rows: recurrence = NONE (one-off) and series_id = null, so nothing changes for them.
alter table events add column if not exists recurrence varchar(16) not null default 'NONE';
alter table events add column if not exists series_id varchar(255) null;
-- One occurrence per date in a series: the scheduler cannot create the same next one twice.
-- Rows without a series (null series_id) are not constrained.
create unique index if not exists uk_events_series_date on events (series_id, date);
create index if not exists idx_events_recurrence on events (recurrence);
