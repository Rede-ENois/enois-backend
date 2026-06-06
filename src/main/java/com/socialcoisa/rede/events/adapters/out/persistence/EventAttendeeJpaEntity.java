package com.socialcoisa.rede.events.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "event_attendees",
	uniqueConstraints = @UniqueConstraint(name = "uk_event_attendee", columnNames = { "event_id", "user_id" }),
	indexes = @Index(name = "idx_event_attendees_event", columnList = "event_id"))
class EventAttendeeJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", nullable = false)
	private String eventId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "responded_at", nullable = false)
	private Instant respondedAt;

	protected EventAttendeeJpaEntity() {
	}

	static EventAttendeeJpaEntity create(String eventId, String userId, Instant now) {
		EventAttendeeJpaEntity e = new EventAttendeeJpaEntity();
		e.eventId = eventId;
		e.userId = userId;
		e.respondedAt = now;
		return e;
	}

}
