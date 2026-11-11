package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.calendar.application.port.out.CalendarSubscriptionRepository;
import com.socialcoisa.rede.calendar.domain.CalendarSubscription;

@Repository
class CalendarSubscriptionPersistenceAdapter implements CalendarSubscriptionRepository {

	private final SpringDataCalendarSubscriptionJpaRepository springData;

	CalendarSubscriptionPersistenceAdapter(SpringDataCalendarSubscriptionJpaRepository springData) {
		this.springData = springData;
	}

	@Override
	public void save(CalendarSubscription subscription) {
		springData.save(CalendarSubscriptionJpaEntity.fromDomain(subscription));
	}

	@Override
	public Optional<CalendarSubscription> findActiveByUser(String userId) {
		return springData.findFirstByUserIdAndRevokedAtIsNull(userId)
			.map(CalendarSubscriptionJpaEntity::toDomain);
	}

	@Override
	public Optional<CalendarSubscription> findByTokenHash(String tokenHash) {
		return springData.findByTokenHash(tokenHash).map(CalendarSubscriptionJpaEntity::toDomain);
	}
}
