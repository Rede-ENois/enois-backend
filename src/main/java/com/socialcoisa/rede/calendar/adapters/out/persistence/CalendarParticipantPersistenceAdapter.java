package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.calendar.application.port.out.CalendarInvitationRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarParticipantRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarParticipant;
import com.socialcoisa.rede.calendar.domain.CalendarParticipantState;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

@Repository
class CalendarParticipantPersistenceAdapter implements CalendarParticipantRepository, CalendarInvitationRepository {

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
	public List<CalendarParticipant> findByEntry(String entryId) {
		return springData.findByEntryIdOrderByInvitedAtAsc(entryId).stream()
			.map(CalendarParticipantJpaEntity::toDomain)
			.toList();
	}

	@Override
	public long countByEntry(String entryId) {
		return springData.countByEntryId(entryId);
	}

	@Override
	public List<InvitedEntry> findInvited(String userId, CalendarScope scope, Instant windowStart, Instant windowEnd) {
		if (!windowEnd.isAfter(windowStart)) {
			// Janela vazia não devolve nada; o banco faria a mesma coisa, mas sem uma consulta.
			return List.of();
		}
		String scopeName = scope == null ? null : scope.name();
		return springData.findInvitedInWindow(userId, scopeName, windowStart, windowEnd).stream()
			.map(row -> new InvitedEntry(row.getEntry().toDomain(), CalendarParticipantState.parse(row.getAnswer())))
			.toList();
	}

}
