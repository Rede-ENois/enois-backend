create table comment_reactions (
  id varchar(255) not null,
  comment_id varchar(255) not null,
  user_id varchar(255) not null,
  reaction_type varchar(32) not null,
  created_at timestamp not null,
  primary key (id)
);

alter table comment_reactions
  add constraint uk_comment_reactions_comment_user unique (comment_id, user_id);
create index idx_comment_reactions_user on comment_reactions (user_id, created_at);

create table message_reactions (
  id varchar(255) not null,
  message_id varchar(255) not null,
  user_id varchar(255) not null,
  reaction_type varchar(32) not null,
  created_at timestamp not null,
  primary key (id)
);

alter table message_reactions
  add constraint uk_message_reactions_message_user unique (message_id, user_id);
create index idx_message_reactions_user on message_reactions (user_id, created_at);
