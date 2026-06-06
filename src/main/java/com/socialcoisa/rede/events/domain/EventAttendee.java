package com.socialcoisa.rede.events.domain;

import java.time.Instant;

public class EventAttendee {

	private final Long id;
	private final String eventId;
	private final String userId;
	private final Instant respondedAt;

	private EventAttendee(Long id, String eventId, String userId, Instant respondedAt) {
		this.id = id;
		this.eventId = eventId;
		this.userId = userId;
		this.respondedAt = respondedAt;
	}

	public static EventAttendee attend(String eventId, String userId, Instant now) {
		return new EventAttendee(null, eventId, userId, now);
	}

	public static EventAttendee restore(Long id, String eventId, String userId, Instant respondedAt) {
		return new EventAttendee(id, eventId, userId, respondedAt);
	}

	public Long id() { return id; }

	public String eventId() { return eventId; }

	public String userId() { return userId; }

	public Instant respondedAt() { return respondedAt; }

}
