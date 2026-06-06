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
	public void save(String userId, String email, String passwordHash) {
		repository.save(new UserCredentialJpaEntity(userId, email, passwordHash, clock.instant()));
	}

	@Override
	public Optional<StoredCredential> findByEmail(String email) {
		return repository.findByEmail(email)
			.map(entity -> new StoredCredential(entity.userId(), entity.email(), entity.passwordHash()));
	}

	@Override
	public boolean existsByEmail(String email) {
		return repository.existsByEmail(email);
	}
}
