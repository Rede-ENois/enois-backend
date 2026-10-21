-- Limpa conexoes pendentes e aceitas de usuarios banidos (ghosts).
-- Antes do fix no BanCleanupService, o ban nao removia conexoes.
-- Esta migration resolve os ghosts que ficaram no banco.
-- Roda uma vez; novos bans ja limpam via BanCleanupService.

UPDATE user_connections SET status = 'REMOVED', removed_at = NOW()
WHERE status IN ('PENDING', 'ACCEPTED')
  AND (requester_user_id IN (
      SELECT user_id FROM user_sanctions WHERE type = 'BAN' AND status = 'ACTIVE'
    )
    OR receiver_user_id IN (
      SELECT user_id FROM user_sanctions WHERE type = 'BAN' AND status = 'ACTIVE'
    ));
