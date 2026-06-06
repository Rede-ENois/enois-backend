alter table posts add column community_id varchar(255);
create index idx_posts_community on posts (community_id);
