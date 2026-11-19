package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface SpringDataScheduledPostJpaRepository extends JpaRepository<ScheduledPostJpaEntity, String> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from ScheduledPostJpaEntity p where p.id = :id")
	Optional<ScheduledPostJpaEntity> findByIdForUpdate(@Param("id") String id);

	List<ScheduledPostJpaEntity> findByUserIdOrderByScheduledAtAsc(String userId);

	List<ScheduledPostJpaEntity> findTop20ByScheduledAtLessThanEqualAndAttemptsLessThanOrderByScheduledAtAsc(
			Instant now, int attemptsBelow);
}
