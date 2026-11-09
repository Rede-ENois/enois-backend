package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.MDC;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.web.RequestObservabilityFilter;

@Repository
class AuthSessionPersistenceAdapter implements AuthSessionRepository {

	private static final java.util.regex.Pattern STORED_HASH = java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

	private final SpringDataAuthSessionJpaRepository repository;

	AuthSessionPersistenceAdapter(SpringDataAuthSessionJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public void save(String token, String refreshToken, String userId, Instant createdAt, Instant expiresAt,
			Instant refreshExpiresAt, String ip) {
		// ponytail: callers other than password login (e-mail verify, 2FA, OAuth, refresh) pass null;
		// the request IP from the MDC fills it in one place instead of threading ip through every use case.
		String sessionIp = ip != null ? ip : MDC.get(RequestObservabilityFilter.MDC_CLIENT_IP);
		// O banco guarda só o hash: um dump/backup não entrega uma sessão viva.
		repository.save(new AuthSessionJpaEntity(Hashes.sha256Hex(token),
			refreshToken == null ? null : Hashes.sha256Hex(refreshToken), userId, createdAt, expiresAt,
			refreshExpiresAt, sessionIp));
	}

	@Override
	public Optional<String> findActiveUserId(String token, Instant now) {
		return findSession(token)
			.filter(session -> session.activeAt(now))
			.map(AuthSessionJpaEntity::userId);
	}

	@Override
	public Optional<String> findActiveUserIdByRefreshToken(String refreshToken, Instant now) {
		return findSessionByRefresh(refreshToken)
			.filter(session -> session.activeRefreshAt(now))
			.map(AuthSessionJpaEntity::userId);
	}

	@Override
	public void revoke(String token, Instant revokedAt) {
		findSession(token).ifPresent(session -> {
			session.revoke(revokedAt);
			repository.save(session);
		});
	}

	@Override
	@org.springframework.transaction.annotation.Transactional
	public boolean revokeByRefreshToken(String refreshToken, Instant revokedAt) {
		return findSessionByRefresh(refreshToken)
			.map(session -> repository.revokeIfActive(session.token(), revokedAt) > 0)
			.orElse(false);
	}

	/**
	 * Hash primeiro. O fallback em claro existe só durante a janela de deploy: um pod antigo
	 * ainda pode criar sessão crua enquanto o novo já migrou as antigas. O rehash agendado
	 * converte essas linhas em até 1 minuto; depois o fallback nunca encontra nada.
	 */
	private Optional<AuthSessionJpaEntity> findSession(String token) {
		Optional<AuthSessionJpaEntity> hashed = repository.findById(Hashes.sha256Hex(token));
		return hashed.isPresent() || looksLikeStoredHash(token) ? hashed : repository.findById(token);
	}

	private Optional<AuthSessionJpaEntity> findSessionByRefresh(String refreshToken) {
		Optional<AuthSessionJpaEntity> hashed = repository.findByRefreshToken(Hashes.sha256Hex(refreshToken));
		return hashed.isPresent() || looksLikeStoredHash(refreshToken) ? hashed
			: repository.findByRefreshToken(refreshToken);
	}

	/**
	 * Issued tokens are base64url, never 64 lowercase hex: a value in that shape can only be a hash
	 * copied from the table, and the raw fallback must not turn it back into a live session.
	 */
	private static boolean looksLikeStoredHash(String value) {
		return STORED_HASH.matcher(value).matches();
	}

	@Override
	public void revokeAllByUser(String userId, Instant revokedAt) {
		repository.revokeAllByUser(userId, revokedAt);
	}

	@Override
	public void revokeOthersByUser(String userId, String keepToken, Instant revokedAt) {
		String keep = keepToken == null ? "" : keepToken.trim();
		repository.revokeOthersByUser(userId, Hashes.sha256Hex(keep), keep, revokedAt);
	}

	@Override
	public int deleteExpiredBefore(Instant cutoff, int batchSize) {
		java.util.List<String> tokens = repository.findTokensExpiredBefore(cutoff,
			org.springframework.data.domain.PageRequest.ofSize(batchSize));
		if (tokens.isEmpty()) {
			return 0;
		}
		repository.deleteByTokenIn(tokens);
		return tokens.size();
	}

	@Override
	public long countExpiredBefore(Instant cutoff) {
		return repository.countExpiredBefore(cutoff);
	}

	@Override
	public java.util.List<String> findRecentIpsByUser(String userId, int limit) {
		return repository.findRecentIpsByUser(userId, org.springframework.data.domain.PageRequest.of(0,
			Math.max(1, limit)));
	}
}
