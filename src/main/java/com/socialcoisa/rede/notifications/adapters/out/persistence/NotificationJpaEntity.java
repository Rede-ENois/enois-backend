package com.socialcoisa.rede.notifications.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.notifications.domain.Notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications", indexes = @Index(name = "idx_notifications_user_read_created", columnList = "user_id, read, created_at"))
class NotificationJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(nullable = false)
	private String kind;

	@Column(name = "actor_id", nullable = false)
	private String actorId;

	@Column(nullable = false, length = 500)
	private String title;

	@Column(length = 2000)
	private String body;

	@Column(length = 500)
	private String href;

	@Column(name = "cta_label")
	private String ctaLabel;

	@Column(nullable = false)
	private boolean read;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected NotificationJpaEntity() {
	}

	static NotificationJpaEntity fromDomain(Notification notification) {
		NotificationJpaEntity e = new NotificationJpaEntity();
		e.id = notification.id();
		e.userId = notification.userId();
		e.kind = notification.kind();
		e.actorId = notification.actorId();
		e.title = notification.title();
		e.body = notification.body();
		e.href = notification.href();
		e.ctaLabel = notification.ctaLabel();
		e.read = notification.isRead();
		e.createdAt = notification.createdAt();
		return e;
	}

	void markRead() {
		this.read = true;
	}

	Notification toDomain() {
		return Notification.restore(id, userId, kind, actorId, title, body, href, ctaLabel, read, createdAt);
	}

}
