-- Domain (primary or mirror) where an OAuth flow started, so the provider callback and the return to
-- the app stay on that site. Existing rows keep null, read as the primary domain (they expire in
-- minutes). The column inherits the table charset and collation (utf8mb4_uca1400_ai_ci, V22).
alter table oauth_authorization_requests
  add column if not exists site_domain varchar(253) null;
