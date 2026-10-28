-- Participation in the free cosmetic events: the Saci x Halloween duel stores the chosen team; the
-- Saci hunt stores one row per spot found (event_id "caca-saci:{spot}"). The primary key is the
-- uniqueness rule: one entry per event and person.
create table if not exists cosmetic_event_entries (
  event_id varchar(64) not null,
  user_id varchar(64) not null,
  choice varchar(32) not null,
  created_at timestamp not null,
  primary key (event_id, user_id),
  key idx_cosmetic_event_choice (event_id, choice)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
