package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.calendar.application.port.out.CalendarReminderRepository;
import com.socialcoisa.rede.calendar.domain.CalendarReminder;

@Repository
class CalendarReminderPersistenceAdapter implements CalendarReminderRepository {

	private final SpringDataCalendarReminderJpaRepository springData;

	CalendarReminderPersistenceAdapter(SpringDataCalendarReminderJpaRepository springData) {
		this.springData = springData;
	}

	@Override
	public void save(CalendarReminder reminder) {
		springData.save(CalendarReminderJpaEntity.fromDomain(reminder));
	}

	@Override
	public List<CalendarReminder> findByEntry(String entryId) {
		return springData.findByEntryId(entryId).stream().map(CalendarReminderJpaEntity::toDomain).toList();
	}

	@Override
	public Optional<CalendarReminder> findByEntryAndUserAndMinutes(String entryId, String userId,
			int minutesBefore) {
		return springData.findOne(entryId, userId, minutesBefore)
			.map(CalendarReminderJpaEntity::toDomain);
	}

	@Override
	public List<CalendarReminder> findDue(Instant now, int attemptsBelow, int limit) {
		return springData.findDue(now, attemptsBelow, PageRequest.of(0, limit)).stream()
			.map(CalendarReminderJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<CalendarReminder> findPendingByEntries(Collection<String> entryIds) {
		if (entryIds == null || entryIds.isEmpty()) {
			return List.of();
		}
		return springData.findPendingByEntryIds(entryIds).stream().map(CalendarReminderJpaEntity::toDomain).toList();
	}

}