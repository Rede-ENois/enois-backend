-- Free ownership of native shop items (event rewards). A purchase stays in the coin ledger
-- (PURCHASE ref cosmetico:{user}:{item}); owning = ledger entry OR a row here.
-- The primary key is the uniqueness rule: one grant per person and item.
create table if not exists cosmetic_grants (
  user_id varchar(64) not null,
  item_id varchar(64) not null,
  source varchar(64) not null,
  created_at timestamp not null,
  primary key (user_id, item_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
