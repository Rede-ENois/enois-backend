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
	/** Target shared by grouped notifications (null = not grouped) and how many people it covers. */
	private final String groupKey;
	private final int actorCount;

	private Notification(String id, String userId, String kind, String actorId, String title, String body, String href,
			String ctaLabel, boolean read, Instant createdAt, String groupKey, int actorCount) {
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
		this.groupKey = groupKey;
		this.actorCount = actorCount;
	}

	public static Notification create(String id, String userId, String kind, String actorId, String title, String body,
			String href, String ctaLabel, Instant now) {
		return new Notification(id, userId, kind, actorId, title, body, href, ctaLabel, false, now, null, 1);
	}

	public static Notification grouped(String id, String userId, String kind, String groupKey, String actorId,
			String title, String body, String href, String ctaLabel, Instant now) {
		return new Notification(id, userId, kind, actorId, title, body, href, ctaLabel, false, now, groupKey, 1);
	}

	public static Notification restore(String id, String userId, String kind, String actorId, String title, String body,
			String href, String ctaLabel, boolean read, Instant createdAt) {
		return restore(id, userId, kind, actorId, title, body, href, ctaLabel, read, createdAt, null, 1);
	}

	public static Notification restore(String id, String userId, String kind, String actorId, String title, String body,
			String href, String ctaLabel, boolean read, Instant createdAt, String groupKey, int actorCount) {
		return new Notification(id, userId, kind, actorId, title, body, href, ctaLabel, read, createdAt, groupKey,
			actorCount);
	}

	/**
	 * One more person on a grouped notification: the newest actor leads, the count grows (unless the
	 * same person acts again) and it moves back to the top of the list.
	 */
	public Notification joinedBy(String newActorId, String newBody, String newHref, Instant now) {
		int count = newActorId.equals(actorId) ? actorCount : actorCount + 1;
		return new Notification(id, userId, kind, newActorId, title, newBody, newHref, ctaLabel, false, now, groupKey,
			count);
	}

	public Notification markRead() {
		return new Notification(id, userId, kind, actorId, title, body, href, ctaLabel, true, createdAt, groupKey,
			actorCount);
	}

	public String id() { return id; }

	public String userId() { return userId; }

	public String kind() { return kind; }

	public String actorId() { return actorId; }

	public String title() { return title; }

	public String body() { return body; }

	public String href() { return href; }

	public String ctaLabel() { return ctaLabel; }

	public String groupKey() { return groupKey; }

	public int actorCount() { return actorCount; }

	public boolean isRead() { return read; }

	public Instant createdAt() { return createdAt; }

}
