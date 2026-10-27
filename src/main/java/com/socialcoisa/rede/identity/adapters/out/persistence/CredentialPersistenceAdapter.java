package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.identity.application.port.out.CredentialRepository;
import com.socialcoisa.rede.identity.application.port.out.StoredCredential;

@Repository
class CredentialPersistenceAdapter implements CredentialRepository {

	private final SpringDataUserCredentialJpaRepository repository;
	private final Clock clock;

	CredentialPersistenceAdapter(SpringDataUserCredentialJpaRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Override
	public void save(String userId, String email, String passwordHash, boolean passwordUsable) {
		repository.save(new UserCredentialJpaEntity(userId, email, passwordHash, passwordUsable, clock.instant()));
	}

	@Override
	public Optional<StoredCredential> findByEmail(String email) {
		return repository.findByEmail(email).map(this::toDomain);
	}

	@Override
	public Optional<StoredCredential> findByUserId(String userId) {
		return repository.findById(userId).map(this::toDomain);
	}

	private StoredCredential toDomain(UserCredentialJpaEntity entity) {
		return new StoredCredential(entity.userId(), entity.email(), entity.passwordHash(), entity.passwordUsable(),
			entity.resetNeeded());
	}

	@Override
	public boolean existsByEmail(String email) {
		return repository.existsByEmail(email);
	}

	@Override
	public void deleteByUserId(String userId) {
		repository.deleteById(userId);
	}
}
