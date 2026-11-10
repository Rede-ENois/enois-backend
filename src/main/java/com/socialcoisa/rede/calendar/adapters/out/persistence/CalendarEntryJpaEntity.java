package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarEntryState;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "calendar_entries", indexes = {
		@Index(name = "idx_calendar_entries_owner_start", columnList = "owner_id, starts_at, state"),
		@Index(name = "uq_calendar_entries_source", columnList = "owner_id, source_type, source_id",
				unique = true)})
class CalendarEntryJpaEntity {

	@Id
	private String id;

	@Column(name = "owner_id", nullable = false)
	private String ownerId;

	@Column(nullable = false, length = 16)
	private String scope;

	@Column(nullable = false, length = 24)
	private String kind;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(length = 2000)
	private String description;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Column(name = "time_zone_id", nullable = false, length = 64)
	private String timeZoneId;

	@Column(name = "all_day", nullable = false)
	private boolean allDay;

	@Column(nullable = false, length = 16)
	private String state;

	@Column(name = "source_type", length = 24)
	private String sourceType;

	@Column(name = "source_id")
	private String sourceId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	protected CalendarEntryJpaEntity() {
	}

	static CalendarEntryJpaEntity fromDomain(CalendarEntry entry) {
		CalendarEntryJpaEntity e = new CalendarEntryJpaEntity();
		e.id = entry.id();
		e.ownerId = entry.ownerId();
		e.scope = entry.scope().name();
		e.kind = entry.kind().name();
		e.title = entry.title();
		e.description = entry.description();
		e.startsAt = entry.startsAt();
		e.endsAt = entry.endsAt();
		e.timeZoneId = entry.zone().getId();
		e.allDay = entry.allDay();
		e.state = entry.state().name();
		e.sourceType = entry.source().type();
		e.sourceId = entry.source().id();
		e.createdAt = entry.createdAt();
		e.updatedAt = entry.updatedAt();
		e.cancelledAt = entry.cancelledAt();
		return e;
	}

	CalendarEntry toDomain() {
		return new CalendarEntry(id, ownerId, CalendarScope.parse(scope), CalendarKind.parse(kind),
			new CalendarEntrySource(sourceType, sourceId), title, description, startsAt, endsAt,
			CalendarEntry.zoneOf(timeZoneId), allDay, CalendarEntryState.parse(state), createdAt, updatedAt,
			cancelledAt);
	}

}
