package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

@Repository
class CalendarEntryPersistenceAdapter implements CalendarEntryRepository {

	private final SpringDataCalendarEntryJpaRepository springData;

	CalendarEntryPersistenceAdapter(SpringDataCalendarEntryJpaRepository springData) {
		this.springData = springData;
	}

	@Override
	public void save(CalendarEntry entry) {
		springData.save(CalendarEntryJpaEntity.fromDomain(entry));
	}

	@Override
	public Optional<CalendarEntry> findById(String id) {
		return springData.findById(id).map(CalendarEntryJpaEntity::toDomain);
	}

	@Override
	public List<CalendarEntry> findInWindow(String ownerId, CalendarScope scope, Instant windowStart,
			Instant windowEnd) {
		if (!windowEnd.isAfter(windowStart)) {
			// Janela vazia não devolve nada; o banco faria a mesma coisa, mas sem uma consulta.
			return List.of();
		}
		String scopeName = scope == null ? null : scope.name();
		return springData.findInWindow(ownerId, scopeName, windowStart, windowEnd).stream()
			.map(CalendarEntryJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<CalendarEntry> findPendingInvitations(String userId) {
		return springData.findPendingInvitations(userId).stream()
			.map(CalendarEntryJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<CalendarEntry> findBySeries(String seriesId) {
		return springData.findBySeriesIdOrderByStartsAtAsc(seriesId).stream()
			.map(CalendarEntryJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Optional<CalendarEntry> findBySeriesAndStart(String seriesId, Instant startsAt) {
		return springData.findBySeriesIdAndStartsAt(seriesId, startsAt)
			.map(CalendarEntryJpaEntity::toDomain);
	}

	@Override
	public List<CalendarEntry> findSeriesTails() {
		return springData.findByTailTrue().stream()
			.map(CalendarEntryJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Optional<CalendarEntry> findBySource(String ownerId, CalendarEntrySource source) {
		if (!source.present()) {
			return Optional.empty();
		}
		return springData.findByOwnerIdAndSourceTypeAndSourceId(ownerId, source.type(), source.id())
			.map(CalendarEntryJpaEntity::toDomain);
	}

	@Override
	public void deleteBySource(String ownerId, CalendarEntrySource source) {
		if (!source.present()) {
			return;
		}
		springData.deleteByOwnerIdAndSourceTypeAndSourceId(ownerId, source.type(), source.id());
	}

}
