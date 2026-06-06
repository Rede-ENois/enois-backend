package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.identity.application.port.out.PasswordResetTokenRepository;
import com.socialcoisa.rede.identity.domain.PasswordResetToken;

@Repository
class PasswordResetTokenPersistenceAdapter implements PasswordResetTokenRepository {

	private final SpringDataPasswordResetTokenJpaRepository repository;
	private final Clock clock;

	PasswordResetTokenPersistenceAdapter(SpringDataPasswordResetTokenJpaRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Override
	public void save(PasswordResetToken token) {
		repository.save(PasswordResetTokenJpaEntity.fromDomain(token));
	}

	@Override
	public Optional<PasswordResetToken> findByToken(String token) {
		return repository.findById(token).map(PasswordResetTokenJpaEntity::toDomain);
	}

	@Override
	public void markUsed(String token) {
		repository.findById(token).ifPresent(entity -> {
			entity.markUsed(clock.instant());
			repository.save(entity);
		});
	}
}
