create table user_recommendations (
  id varchar(255) not null,
  recommender_id varchar(255) not null,
  recommended_id varchar(255) not null,
  comment varchar(500),
  created_at timestamp not null,
  primary key (id)
);

alter table user_recommendations add constraint uk_recommendation_pair unique (recommender_id, recommended_id);
create index idx_recommendations_recommended on user_recommendations (recommended_id);
alter table user_recommendations add constraint fk_recommendations_recommender foreign key (recommender_id) references users (id);
alter table user_recommendations add constraint fk_recommendations_recommended foreign key (recommended_id) references users (id);
