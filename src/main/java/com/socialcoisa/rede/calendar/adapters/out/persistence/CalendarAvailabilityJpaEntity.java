package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import com.socialcoisa.rede.calendar.domain.CalendarAvailabilityWindow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "calendar_availability", indexes = {
		@Index(name = "idx_calendar_availability_user", columnList = "user_id"),
		@Index(name = "uq_calendar_availability_user_day_start",
				columnList = "user_id, day_of_week, starts_at", unique = true)})
class CalendarAvailabilityJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "day_of_week", nullable = false)
	private int dayOfWeek;

	@Column(name = "starts_at", nullable = false)
	private LocalTime startsAt;

	@Column(name = "ends_at", nullable = false)
	private LocalTime endsAt;

	@Column(name = "time_zone_id", nullable = false, length = 64)
	private String timeZoneId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected CalendarAvailabilityJpaEntity() {
	}

	static CalendarAvailabilityJpaEntity fromDomain(CalendarAvailabilityWindow window) {
		CalendarAvailabilityJpaEntity e = new CalendarAvailabilityJpaEntity();
		e.id = window.id();
		e.userId = window.userId();
		e.dayOfWeek = window.dayOfWeek();
		e.startsAt = window.startsAt();
		e.endsAt = window.endsAt();
		e.timeZoneId = window.zone().getId();
		e.createdAt = window.createdAt();
		return e;
	}

	CalendarAvailabilityWindow toDomain() {
		return new CalendarAvailabilityWindow(id, userId, dayOfWeek, startsAt, endsAt,
			ZoneId.of(timeZoneId), createdAt);
	}

}