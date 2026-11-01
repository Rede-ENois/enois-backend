-- The "what's new" release notes admins publish from the panel (popup shown once per release).
--
-- Always a single row: the current release is unique and publishing replaces it. The fixed key
-- "ativo" is the same device as admin_notice (V197).
--
-- items_json holds the items as a JSON array of {title, description}. updated_at is the version key
-- the front uses to reopen the popup after an edit.
create table if not exists release_note (
  id varchar(32) not null,
  items_json longtext not null,
  published_at timestamp not null,
  updated_at timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
