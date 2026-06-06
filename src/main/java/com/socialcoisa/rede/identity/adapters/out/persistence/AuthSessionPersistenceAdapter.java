package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;

@Repository
class AuthSessionPersistenceAdapter implements AuthSessionRepository {

	private final SpringDataAuthSessionJpaRepository repository;

	AuthSessionPersistenceAdapter(SpringDataAuthSessionJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public void save(String token, String userId, Instant createdAt, Instant expiresAt) {
		repository.save(new AuthSessionJpaEntity(token, userId, createdAt, expiresAt));
	}

	@Override
	public Optional<String> findActiveUserId(String token, Instant now) {
		return repository.findById(token)
			.filter(session -> session.activeAt(now))
			.map(AuthSessionJpaEntity::userId);
	}

	@Override
	public void revoke(String token, Instant revokedAt) {
		repository.findById(token).ifPresent(session -> {
			session.revoke(revokedAt);
			repository.save(session);
		});
	}
}
