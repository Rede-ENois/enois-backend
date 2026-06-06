create table comments (
  id varchar(255) not null,
  post_id varchar(255) not null,
  author_id varchar(255) not null,
  parent_comment_id varchar(255),
  content varchar(1024) not null,
  created_at timestamp with time zone not null,
  status varchar(32) not null,
  primary key (id)
);

create index idx_comments_post_created on comments (post_id, created_at);
create index idx_comments_parent_created on comments (parent_comment_id, created_at);

create table reactions (
  id varchar(255) not null,
  post_id varchar(255) not null,
  user_id varchar(255) not null,
  reaction_type varchar(32) not null,
  created_at timestamp with time zone not null,
  primary key (id)
);

alter table reactions add constraint uk_reactions_post_user unique (post_id, user_id);
create index idx_reactions_user_created on reactions (user_id, created_at);

create table saved_posts (
  id varchar(255) not null,
  post_id varchar(255) not null,
  user_id varchar(255) not null,
  created_at timestamp with time zone not null,
  primary key (id)
);

alter table saved_posts add constraint uk_saved_posts_post_user unique (post_id, user_id);
create index idx_saved_posts_user_created on saved_posts (user_id, created_at);

create table post_shares (
  id varchar(255) not null,
  post_id varchar(255) not null,
  user_id varchar(255) not null,
  created_at timestamp with time zone not null,
  primary key (id)
);

alter table post_shares add constraint uk_post_shares_post_user unique (post_id, user_id);
create index idx_post_shares_user_created on post_shares (user_id, created_at);

alter table comments add constraint fk_comments_post foreign key (post_id) references posts (id);
alter table comments add constraint fk_comments_parent foreign key (parent_comment_id) references comments (id);
alter table reactions add constraint fk_reactions_post foreign key (post_id) references posts (id);
alter table saved_posts add constraint fk_saved_posts_post foreign key (post_id) references posts (id);
alter table post_shares add constraint fk_post_shares_post foreign key (post_id) references posts (id);
