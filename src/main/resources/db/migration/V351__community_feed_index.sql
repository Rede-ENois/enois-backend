create index if not exists idx_posts_community_feed on posts (community_id, status, channel_id, created_at);
