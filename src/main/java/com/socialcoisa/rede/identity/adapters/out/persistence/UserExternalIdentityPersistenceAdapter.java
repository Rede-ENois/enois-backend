package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.identity.application.port.out.UserExternalIdentityRepository;

@Repository
class UserExternalIdentityPersistenceAdapter implements UserExternalIdentityRepository {

	private final SpringDataUserExternalIdentityJpaRepository repository;
	private final Clock clock;

	UserExternalIdentityPersistenceAdapter(SpringDataUserExternalIdentityJpaRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Override
	public void save(String userId, String provider, String externalUserId, String email) {
		repository.save(new UserExternalIdentityJpaEntity(userId, provider, externalUserId, email, clock.instant()));
	}

	@Override
	public Optional<String> findUserIdByProviderAndExternalId(String provider, String externalUserId) {
		return repository.findByProviderAndExternalUserId(provider, externalUserId)
			.map(UserExternalIdentityJpaEntity::userId);
	}

	@Override
	public boolean existsByProviderAndExternalId(String provider, String externalUserId) {
		return repository.existsByProviderAndExternalUserId(provider, externalUserId);
	}
}
