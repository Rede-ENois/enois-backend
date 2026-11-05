-- Per-message E2E: cada mensagem carrega as copias cifradas da AES key,
-- uma por participante. JSON: {"userId":"encryptedAesKeyBase64", ...}
-- Mensagens antigas (conversation key) continuam com encrypted_keys NULL.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS encrypted_keys TEXT NULL;
