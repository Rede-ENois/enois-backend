-- First-run onboarding (starter pack): when the person finished or skipped it. Null = still to do,
-- and the front shows the onboarding after login.
alter table users add column if not exists onboarding_completed_at timestamp null;
-- Existing accounts already know the platform: every current row is marked as completed (at its
-- creation time), so only accounts created after this migration see the onboarding.
update users set onboarding_completed_at = created_at where onboarding_completed_at is null;
