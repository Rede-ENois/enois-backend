-- Idempotent publication of community suggestions: the first approval records when the public issue
-- was attempted, and the value is never cleared. A retry after an attempt with an unknown outcome
-- (a timeout after GitHub created the issue) looks that issue up instead of opening a duplicate.
-- Existing rows keep null: none of them has a pending attempt to recover.
alter table suggestions
  add column if not exists publish_attempted_at timestamp null;
