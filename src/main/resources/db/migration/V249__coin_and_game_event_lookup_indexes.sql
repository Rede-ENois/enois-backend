-- Lookup indexes for two reads that scanned whole tables:
-- * coin_entries (kind, ref_id): "was this reward already paid?" (CoinService.hasReward and every
--   idempotent reward, airdrop and daily presence) filters by kind and ref_id only; the existing
--   index starts with account_id and the unique key with (account_id, idempotency_key).
-- * game_events (kind, created_at): "jogados hoje" groups the PLAY_START events of the last 24
--   hours across all games; the existing index starts with game_id.
-- Additive and idempotent; no data changes. Key size stays within InnoDB's 3072-byte limit in utf8mb4.
create index if not exists idx_coin_entries_kind_ref on coin_entries (kind, ref_id);
create index if not exists idx_game_events_kind_created on game_events (kind, created_at);
