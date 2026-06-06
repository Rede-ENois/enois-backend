package com.socialcoisa.rede.messaging.domain;

import java.time.Instant;

public class Conversation {

	private final String id;
	private final String kind;
	private final String title;
	private final String createdBy;
	private final Instant createdAt;
	private final Instant updatedAt;

	private Conversation(String id, String kind, String title, String createdBy, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.kind = kind;
		this.title = title;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static Conversation create(String id, String kind, String title, String createdBy, Instant now) {
		return new Conversation(id, kind, title, createdBy, now, now);
	}

	public static Conversation restore(String id, String kind, String title, String createdBy, Instant createdAt,
			Instant updatedAt) {
		return new Conversation(id, kind, title, createdBy, createdAt, updatedAt);
	}

	public String id() { return id; }

	public String kind() { return kind; }

	public String title() { return title; }

	public String createdBy() { return createdBy; }

	public Instant createdAt() { return createdAt; }

	public Instant updatedAt() { return updatedAt; }

}
