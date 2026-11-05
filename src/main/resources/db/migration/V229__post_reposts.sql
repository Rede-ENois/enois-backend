-- Repost puro: ponteiro "user X repostou post Y". Nao cria post novo.
-- Likes e comentarios ficam no post original.
create table if not exists post_reposts (
  id bigint not null auto_increment,
  user_id varchar(255) not null,
  post_id varchar(255) not null,
  created_at timestamp not null,
  primary key (id),
  constraint uk_post_reposts_user_post unique (user_id, post_id),
  key idx_post_reposts_user (user_id),
  key idx_post_reposts_post (post_id),
  constraint fk_post_reposts_post foreign key (post_id) references posts (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
