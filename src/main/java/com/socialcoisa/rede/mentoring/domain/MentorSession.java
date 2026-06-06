package com.socialcoisa.rede.mentoring.domain;

import java.time.Instant;

public class MentorSession {

	private final String id;
	private final String mentorId;
	private final String menteeId;
	private final String slotId;
	private final String message;
	private final SessionStatus status;
	private final Instant createdAt;

	private MentorSession(String id, String mentorId, String menteeId, String slotId, String message,
			SessionStatus status, Instant createdAt) {
		this.id = id;
		this.mentorId = mentorId;
		this.menteeId = menteeId;
		this.slotId = slotId;
		this.message = message;
		this.status = status;
		this.createdAt = createdAt;
	}

	public static MentorSession schedule(String id, String mentorId, String menteeId, String slotId, String message,
			Instant now) {
		return new MentorSession(id, mentorId, menteeId, slotId, message, SessionStatus.SCHEDULED, now);
	}

	public static MentorSession restore(String id, String mentorId, String menteeId, String slotId, String message,
			SessionStatus status, Instant createdAt) {
		return new MentorSession(id, mentorId, menteeId, slotId, message, status, createdAt);
	}

	public String id() { return id; }

	public String mentorId() { return mentorId; }

	public String menteeId() { return menteeId; }

	public String slotId() { return slotId; }

	public String message() { return message; }

	public SessionStatus status() { return status; }

	public Instant createdAt() { return createdAt; }

}
