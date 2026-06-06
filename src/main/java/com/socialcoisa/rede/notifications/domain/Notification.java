package com.socialcoisa.rede.notifications.domain;

import java.time.Instant;

public class Notification {

	private final String id;
	private final String userId;
	private final String kind;
	private final String actorId;
	private final String title;
	private final String body;
	private final String href;
	private final String ctaLabel;
	private final boolean read;
	private final Instant createdAt;

	private Notification(String id, String userId, String kind, String actorId, String title, String body, String href,
			String ctaLabel, boolean read, Instant createdAt) {
		this.id = id;
		this.userId = userId;
		this.kind = kind;
		this.actorId = actorId;
		this.title = title;
		this.body = body;
		this.href = href;
		this.ctaLabel = ctaLabel;
		this.read = read;
		this.createdAt = createdAt;
	}

	public static Notification create(String id, String userId, String kind, String actorId, String title, String body,
			String href, String ctaLabel, Instant now) {
		return new Notification(id, userId, kind, actorId, title, body, href, ctaLabel, false, now);
	}

	public static Notification restore(String id, String userId, String kind, String actorId, String title, String body,
			String href, String ctaLabel, boolean read, Instant createdAt) {
		return new Notification(id, userId, kind, actorId, title, body, href, ctaLabel, read, createdAt);
	}

	public Notification markRead() {
		return new Notification(id, userId, kind, actorId, title, body, href, ctaLabel, true, createdAt);
	}

	public String id() { return id; }

	public String userId() { return userId; }

	public String kind() { return kind; }

	public String actorId() { return actorId; }

	public String title() { return title; }

	public String body() { return body; }

	public String href() { return href; }

	public String ctaLabel() { return ctaLabel; }

	public boolean isRead() { return read; }

	public Instant createdAt() { return createdAt; }

}
