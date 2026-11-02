package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.socialcoisa.rede.moderation.domain.SanctionStatus;
import com.socialcoisa.rede.moderation.domain.SanctionType;

interface SpringDataUserSanctionJpaRepository extends JpaRepository<UserSanctionJpaEntity, String> {

	Optional<UserSanctionJpaEntity> findFirstByUserIdAndStatusOrderByCreatedAtDesc(String userId, SanctionStatus status);

	List<UserSanctionJpaEntity> findTop20ByUserIdOrderByCreatedAtDesc(String userId);

	List<UserSanctionJpaEntity> findByUserIdInAndStatus(Collection<String> userIds, SanctionStatus status);

	List<UserSanctionJpaEntity> findByTypeAndStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
			SanctionType type, SanctionStatus status, Instant cutoff, Pageable pageable);

	List<UserSanctionJpaEntity> findByStatusOrderByCreatedAtDesc(SanctionStatus status, Pageable pageable);
}
