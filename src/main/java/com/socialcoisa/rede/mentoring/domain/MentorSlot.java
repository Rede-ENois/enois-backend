package com.socialcoisa.rede.mentoring.domain;

import java.time.Instant;

public class MentorSlot {

	private final String id;
	private final String mentorId;
	private final Instant startsAt;
	private final Instant endsAt;
	private final boolean available;

	private MentorSlot(String id, String mentorId, Instant startsAt, Instant endsAt, boolean available) {
		this.id = id;
		this.mentorId = mentorId;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.available = available;
	}

	public static MentorSlot create(String id, String mentorId, Instant startsAt, Instant endsAt) {
		return new MentorSlot(id, mentorId, startsAt, endsAt, true);
	}

	public static MentorSlot restore(String id, String mentorId, Instant startsAt, Instant endsAt, boolean available) {
		return new MentorSlot(id, mentorId, startsAt, endsAt, available);
	}

	public MentorSlot book() {
		return new MentorSlot(id, mentorId, startsAt, endsAt, false);
	}

	public String id() { return id; }

	public String mentorId() { return mentorId; }

	public Instant startsAt() { return startsAt; }

	public Instant endsAt() { return endsAt; }

	public boolean available() { return available; }

}
