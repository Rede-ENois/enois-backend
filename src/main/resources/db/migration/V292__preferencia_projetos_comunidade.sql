-- Contas existentes permanecem sem avisos de projetos ate a adesao explicita.
alter table notification_preferences
  add column community_projects boolean not null default false;

create index idx_notification_preferences_community_projects
  on notification_preferences (community_projects, user_id);
