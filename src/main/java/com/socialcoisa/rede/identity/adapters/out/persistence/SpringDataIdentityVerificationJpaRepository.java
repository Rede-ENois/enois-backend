package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.identity.domain.IdentityVerificationStatus;

interface SpringDataIdentityVerificationJpaRepository extends JpaRepository<IdentityVerificationJpaEntity, String> {

	Optional<IdentityVerificationJpaEntity> findByUserId(String userId);

	Optional<IdentityVerificationJpaEntity> findByAttemptId(String attemptId);

	@Query("""
		select count(v) from IdentityVerificationJpaEntity v
		where v.provider = :provider and v.startedAt >= :since
		""")
	long countStartedSince(@Param("provider") String provider, @Param("since") Instant since);

	boolean existsByUserIdAndState(String userId, IdentityVerificationStatus state);

	@Query("""
		select v.userId from IdentityVerificationJpaEntity v
		where v.state = :state and v.userId in :userIds
		""")
	java.util.List<String> findUserIdsByStateAndUserIdIn(@Param("state") IdentityVerificationStatus state,
		@Param("userIds") java.util.Collection<String> userIds);
}
