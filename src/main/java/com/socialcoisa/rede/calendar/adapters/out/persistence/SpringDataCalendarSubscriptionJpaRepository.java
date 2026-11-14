package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCalendarSubscriptionJpaRepository extends JpaRepository<CalendarSubscriptionJpaEntity, String> {

	Optional<CalendarSubscriptionJpaEntity> findFirstByUserIdAndRevokedAtIsNull(String userId);

	Optional<CalendarSubscriptionJpaEntity> findByTokenHash(String tokenHash);

	@Query(value = "select id from users where id = :userId for update", nativeQuery = true)
	Optional<String> lockUserForIssue(@Param("userId") String userId);
}
