package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.moderation.application.port.out.UserWarningRepository;
import com.socialcoisa.rede.moderation.domain.UserWarning;

@Repository
class UserWarningPersistenceAdapter implements UserWarningRepository {

	private final SpringDataUserWarningJpaRepository warnings;

	UserWarningPersistenceAdapter(SpringDataUserWarningJpaRepository warnings) {
		this.warnings = warnings;
	}

	@Override
	public UserWarning save(UserWarning warning) {
		return warnings.save(UserWarningJpaEntity.fromDomain(warning)).toDomain();
	}

	@Override
	public Optional<UserWarning> findById(String id) {
		return warnings.findById(id).map(UserWarningJpaEntity::toDomain);
	}

	@Override
	public List<UserWarning> findByUser(String userId, int limit) {
		return warnings.findByUserIdOrderByCreatedAtDesc(userId,
				PageRequest.of(0, Math.max(1, Math.min(limit, 100)))).stream()
			.map(UserWarningJpaEntity::toDomain)
			.toList();
	}

	@Override
	public long countActiveByUser(String userId) {
		return warnings.countByUserIdAndRevokedAtIsNull(userId);
	}
}