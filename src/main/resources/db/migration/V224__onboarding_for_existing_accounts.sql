-- Every account goes through the first-run onboarding once, no matter when it was created. V210
-- backfilled existing rows as completed with onboarding_completed_at = created_at; a real completion
-- or skip stamps the moment it happened, so that equality only matches the backfill. Clearing it
-- makes those accounts see the onboarding on their next visit to the feed.
update users set onboarding_completed_at = null where onboarding_completed_at = created_at;
