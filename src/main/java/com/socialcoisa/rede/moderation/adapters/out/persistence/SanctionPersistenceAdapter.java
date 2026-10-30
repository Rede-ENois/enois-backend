package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.domain.SanctionStatus;
import com.socialcoisa.rede.moderation.domain.SanctionType;
import com.socialcoisa.rede.moderation.domain.UserSanction;

@Repository
class SanctionPersistenceAdapter implements SanctionRepository, ExpiredSuspensionReader {

	private final SpringDataUserSanctionJpaRepository sanctions;

	SanctionPersistenceAdapter(SpringDataUserSanctionJpaRepository sanctions) {
		this.sanctions = sanctions;
	}

	@Override
	public UserSanction save(UserSanction sanction) {
		return sanctions.save(UserSanctionJpaEntity.fromDomain(sanction)).toDomain();
	}

	@Override
	public Optional<UserSanction> findById(String id) {
		return sanctions.findById(id).map(UserSanctionJpaEntity::toDomain);
	}

	@Override
	public Optional<UserSanction> findActiveByUser(String userId) {
		return sanctions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, SanctionStatus.ACTIVE)
			.map(UserSanctionJpaEntity::toDomain);
	}

	@Override
	public List<UserSanction> findActiveByUsers(Collection<String> userIds) {
		if (userIds.isEmpty()) {
			return List.of();
		}
		return sanctions.findByUserIdInAndStatus(userIds, SanctionStatus.ACTIVE).stream()
			.map(UserSanctionJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<UserSanction> findByUser(String userId, int limit) {
		return sanctions.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream()
			.limit(limit)
			.map(UserSanctionJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<UserSanction> findByStatus(SanctionStatus status, int limit) {
		return sanctions.findByStatusOrderByCreatedAtDesc(status,
			PageRequest.of(0, Math.max(1, Math.min(limit, 200)))).stream()
			.map(UserSanctionJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<UserSanction> expiredActive(Instant cutoff, int limit) {
		return sanctions
			.findByTypeAndStatusAndCreatedAtBeforeOrderByCreatedAtAsc(SanctionType.SUSPENDED,
				SanctionStatus.ACTIVE, cutoff, PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
			.stream().map(UserSanctionJpaEntity::toDomain).toList();
	}

}
