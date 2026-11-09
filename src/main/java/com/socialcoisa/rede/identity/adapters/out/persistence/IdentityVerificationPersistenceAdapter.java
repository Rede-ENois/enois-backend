package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.identity.application.port.out.IdentityVerificationRepository;
import com.socialcoisa.rede.identity.domain.IdentityVerification;
import com.socialcoisa.rede.identity.domain.IdentityVerificationStatus;

@Repository
class IdentityVerificationPersistenceAdapter implements IdentityVerificationRepository {

	private final SpringDataIdentityVerificationJpaRepository repository;

	IdentityVerificationPersistenceAdapter(SpringDataIdentityVerificationJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public Optional<IdentityVerification> findByUserId(String userId) {
		return repository.findByUserId(userId).map(IdentityVerificationJpaEntity::toDomain);
	}

	@Override
	public Optional<IdentityVerification> findByAttemptId(String attemptId) {
		return repository.findByAttemptId(attemptId).map(IdentityVerificationJpaEntity::toDomain);
	}

	@Override
	public void save(IdentityVerification verification) {
		repository.save(IdentityVerificationJpaEntity.fromDomain(verification));
	}

	@Override
	public int countStartedSince(String provider, Instant since) {
		return (int) repository.countStartedSince(provider, since);
	}

	@Override
	public boolean isVerified(String userId) {
		return repository.existsByUserIdAndState(userId, IdentityVerificationStatus.VERIFIED);
	}

	@Override
	public Set<String> verifiedAmong(Collection<String> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(repository.findUserIdsByStateAndUserIdIn(IdentityVerificationStatus.VERIFIED, userIds));
	}
}
