package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.calendar.application.port.out.CalendarAvailabilityRepository;
import com.socialcoisa.rede.calendar.domain.CalendarAvailabilityWindow;

@Repository
class CalendarAvailabilityPersistenceAdapter implements CalendarAvailabilityRepository {

	private final SpringDataCalendarAvailabilityJpaRepository springData;

	CalendarAvailabilityPersistenceAdapter(SpringDataCalendarAvailabilityJpaRepository springData) {
		this.springData = springData;
	}

	@Override
	public List<CalendarAvailabilityWindow> findByUser(String userId) {
		return springData.findByUserIdOrderByDayOfWeekAscStartsAtAsc(userId).stream()
			.map(CalendarAvailabilityJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void replaceFor(String userId, List<CalendarAvailabilityWindow> windows) {
		springData.deleteByUserId(userId);
		if (!windows.isEmpty()) {
			springData.saveAll(windows.stream().map(CalendarAvailabilityJpaEntity::fromDomain).toList());
		}
	}

}