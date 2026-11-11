package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.ProvisionTokenRepository;

@Repository
class ProvisionTokenPersistenceAdapter implements ProvisionTokenRepository {

	private final SpringDataProvisionTokenJpaRepository repository;

	ProvisionTokenPersistenceAdapter(SpringDataProvisionTokenJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public void insert(String tokenHash, String displayName, String ownerUserId, String profiles, Instant expiresAt,
			String createdBy, Instant now) {
		repository.save(
			new ProvisionTokenJpaEntity(tokenHash, displayName, ownerUserId, profiles, expiresAt, createdBy, now));
	}

	@Override
	public Optional<ProvisionTokenRecord> findByHash(String tokenHash) {
		return repository.findByTokenHash(tokenHash)
			.map(entity -> new ProvisionTokenRecord(entity.tokenHash(), entity.displayName(), entity.ownerUserId(),
				entity.profiles(), entity.expiresAt(), entity.usedAt()));
	}

	@Override
	public boolean markUsed(String tokenHash, Instant now) {
		return repository.markUsed(tokenHash, now) == 1;
	}
}
