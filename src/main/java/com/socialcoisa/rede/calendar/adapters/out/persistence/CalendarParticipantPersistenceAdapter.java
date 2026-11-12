package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.calendar.application.port.out.CalendarParticipantRepository;
import com.socialcoisa.rede.calendar.domain.CalendarParticipant;

@Repository
class CalendarParticipantPersistenceAdapter implements CalendarParticipantRepository {

	private final SpringDataCalendarParticipantJpaRepository springData;

	CalendarParticipantPersistenceAdapter(SpringDataCalendarParticipantJpaRepository springData) {
		this.springData = springData;
	}

	@Override
	public void save(CalendarParticipant participant) {
		springData.save(CalendarParticipantJpaEntity.fromDomain(participant));
	}

	@Override
	public void delete(CalendarParticipant participant) {
		springData.deleteById(participant.id());
	}

	@Override
	public Optional<CalendarParticipant> findById(String id) {
		return springData.findById(id).map(CalendarParticipantJpaEntity::toDomain);
	}

	@Override
	public Optional<CalendarParticipant> findByEntryAndUser(String entryId, String userId) {
		return springData.findByEntryIdAndUserId(entryId, userId).map(CalendarParticipantJpaEntity::toDomain);
	}

	@Override
	public List<CalendarParticipant> findByUserAndEntryIds(String userId, List<String> entryIds) {
		if (entryIds.isEmpty()) {
			return List.of();
		}
		return springData.findByUserIdAndEntryIdIn(userId, entryIds).stream()
			.map(CalendarParticipantJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<CalendarParticipant> findByEntry(String entryId) {
		return springData.findByEntryIdOrderByInvitedAtAsc(entryId).stream()
			.map(CalendarParticipantJpaEntity::toDomain)
			.toList();
	}

}
