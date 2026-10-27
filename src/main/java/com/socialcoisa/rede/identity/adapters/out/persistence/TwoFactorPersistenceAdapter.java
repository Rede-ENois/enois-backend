package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.identity.application.port.out.TwoFactorRepository;
import com.socialcoisa.rede.identity.domain.TwoFactorChallenge;
import com.socialcoisa.rede.identity.domain.TwoFactorMethod;

/**
 * Persistencia do segundo fator.
 *
 * Aqui o SQL direto e proposital: sao tres tabelas de chave-valor (desafio, segredo cifrado e codigo
 * de recuperacao) sem agregado de dominio que justifique entidade JPA, e assim os segredos ficam
 * fora do modelo de perfil — nada de espalhar segredo de TOTP por entidade mapeada.
 */
@Repository
class TwoFactorPersistenceAdapter implements TwoFactorRepository {

	private static final RowMapper<TwoFactorChallenge> CHALLENGE_MAPPER = (rs, row) -> mapChallenge(rs);

	private final JdbcTemplate jdbc;

	TwoFactorPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void saveChallenge(TwoFactorChallenge challenge) {
		jdbc.update("""
				insert into two_factor_challenges (id, user_id, method, code_hash, attempts, expires_at,
					consumed_at, created_at)
				values (?, ?, ?, ?, ?, ?, ?, ?)""",
			challenge.id(), challenge.userId(), challenge.method().name(), challenge.codeHash(), challenge.attempts(),
			java.sql.Timestamp.from(challenge.expiresAt()),
			challenge.consumedAt() == null ? null : java.sql.Timestamp.from(challenge.consumedAt()),
			java.sql.Timestamp.from(challenge.createdAt()));
	}

	@Override
	public boolean replaceCode(String challengeId, String codeHash, Instant expiresAt) {
		return jdbc.update("""
				update two_factor_challenges set code_hash = ?, attempts = 0, expires_at = ?
				where id = ? and consumed_at is null""", codeHash, java.sql.Timestamp.from(expiresAt), challengeId) == 1;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public AttemptClaim claimAttempt(String challengeId) {
		int claimed = jdbc.update("""
				update two_factor_challenges set attempts = attempts + 1
				where id = ? and attempts < ? and consumed_at is null
				""", challengeId, TwoFactorChallenge.MAX_ATTEMPTS);
		if (claimed == 1) {
			return AttemptClaim.CLAIMED;
		}
		boolean consumed = !jdbc.queryForList(
			"select id from two_factor_challenges where id = ? and consumed_at is not null", String.class, challengeId)
			.isEmpty();
		return consumed ? AttemptClaim.CONSUMED : AttemptClaim.OUT_OF_ATTEMPTS;
	}

	@Override
	public boolean consumeChallenge(String challengeId, Instant now) {
		return jdbc.update("update two_factor_challenges set consumed_at = ? where id = ? and consumed_at is null",
			java.sql.Timestamp.from(now), challengeId) == 1;
	}

	@Override
	public boolean markTotpStepUsed(String userId, long step) {
		return jdbc.update("""
				update user_two_factor_secrets set totp_last_step = ?
				where user_id = ? and (totp_last_step is null or totp_last_step < ?)""", step, userId, step) > 0;
	}

	@Override
	public Optional<TwoFactorChallenge> findChallenge(String id) {
		return jdbc.query("select * from two_factor_challenges where id = ?", CHALLENGE_MAPPER, id).stream().findFirst();
	}

	@Override
	public Optional<TwoFactorChallenge> findLatestChallenge(String userId, TwoFactorMethod method) {
		return jdbc.query("""
				select * from two_factor_challenges where user_id = ? and method = ? and consumed_at is null
				order by created_at desc limit 1""", CHALLENGE_MAPPER, userId, method.name())
			.stream()
			.findFirst();
	}

	@Override
	public int deleteExpiredChallenges(Instant now) {
		return jdbc.update("delete from two_factor_challenges where expires_at < ?", java.sql.Timestamp.from(now));
	}

	@Override
	public void savePendingSecret(String userId, String encryptedSecret, Instant now) {
		java.sql.Timestamp ts = java.sql.Timestamp.from(now);
		if (findPendingSecret(userId).isPresent() || findActiveSecret(userId).isPresent()) {
			jdbc.update("update user_two_factor_secrets set pending_secret_encrypted = ?, pending_created_at = ?, updated_at = ? where user_id = ?",
				encryptedSecret, ts, ts, userId);
			return;
		}
		jdbc.update("""
				insert into user_two_factor_secrets (user_id, totp_secret_encrypted, pending_secret_encrypted,
					pending_created_at, updated_at)
				values (?, null, ?, ?, ?)""",
			userId, encryptedSecret, ts, ts);
	}

	@Override
	public Optional<String> findPendingSecret(String userId) {
		return jdbc.query("select pending_secret_encrypted from user_two_factor_secrets where user_id = ?",
			(rs, row) -> rs.getString(1), userId).stream().filter(java.util.Objects::nonNull).findFirst();
	}

	@Override
	public Optional<Instant> findPendingSecretCreatedAt(String userId) {
		return jdbc.query("select pending_created_at from user_two_factor_secrets where user_id = ?",
			(rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), userId)
			.stream().filter(java.util.Objects::nonNull).findFirst();
	}

	@Override
	public void activateTotpSecret(String userId, String encryptedSecret, Instant now) {
		jdbc.update("""
				update user_two_factor_secrets set totp_secret_encrypted = ?, pending_secret_encrypted = null,
					pending_created_at = null, updated_at = ? where user_id = ?""",
			encryptedSecret, java.sql.Timestamp.from(now), userId);
	}

	@Override
	public Optional<String> findActiveSecret(String userId) {
		return jdbc.query("select totp_secret_encrypted from user_two_factor_secrets where user_id = ?",
			(rs, row) -> rs.getString(1), userId).stream().filter(java.util.Objects::nonNull).findFirst();
	}

	@Override
	public void clearSecrets(String userId) {
		jdbc.update("delete from user_two_factor_secrets where user_id = ?", userId);
	}

	@Override
	public List<String> findUsersWithActiveTotpSecret() {
		return jdbc.queryForList(
			"select user_id from user_two_factor_secrets where totp_secret_encrypted is not null",
			String.class);
	}

	@Override
	public void saveBackupCodes(String userId, List<String> codeHashes, Instant now) {
		deleteBackupCodes(userId);
		java.sql.Timestamp ts = java.sql.Timestamp.from(now);
		for (String hash : codeHashes) {
			jdbc.update("insert into two_factor_backup_codes (code_hash, user_id, used_at, created_at) values (?, ?, null, ?)",
				hash, userId, ts);
		}
	}

	@Override
	public int countUnusedBackupCodes(String userId) {
		Integer count = jdbc.queryForObject(
			"select count(*) from two_factor_backup_codes where user_id = ? and used_at is null", Integer.class, userId);
		return count == null ? 0 : count;
	}

	@Override
	public boolean consumeBackupCode(String userId, String codeHash, Instant now) {
		return jdbc.update("""
				update two_factor_backup_codes set used_at = ? where code_hash = ? and user_id = ? and used_at is null""",
			java.sql.Timestamp.from(now), codeHash, userId) > 0;
	}

	@Override
	public void deleteBackupCodes(String userId) {
		jdbc.update("delete from two_factor_backup_codes where user_id = ?", userId);
	}

	private static TwoFactorChallenge mapChallenge(ResultSet rs) throws SQLException {
		String methodStr = rs.getString("method");
		TwoFactorMethod method = null;
		if (methodStr != null && !methodStr.isBlank()) {
			try {
				method = TwoFactorMethod.valueOf(methodStr.trim().toUpperCase());
			} catch (IllegalArgumentException ignored) {
				method = null;
			}
		}
		java.sql.Timestamp expiresAt = rs.getTimestamp("expires_at");
		java.sql.Timestamp consumedAt = rs.getTimestamp("consumed_at");
		java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
		return TwoFactorChallenge.restore(
			rs.getString("id"),
			rs.getString("user_id"),
			method,
			rs.getString("code_hash"),
			rs.getInt("attempts"),
			expiresAt == null ? null : expiresAt.toInstant(),
			consumedAt == null ? null : consumedAt.toInstant(),
			createdAt == null ? null : createdAt.toInstant());
	}
}