package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCalendarSubscriptionJpaRepository extends JpaRepository<CalendarSubscriptionJpaEntity, String> {

	Optional<CalendarSubscriptionJpaEntity> findFirstByUserIdAndRevokedAtIsNull(String userId);

	Optional<CalendarSubscriptionJpaEntity> findByTokenHash(String tokenHash);
}
