package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.calendar.domain.CalendarSubscription;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "calendar_subscriptions")
class CalendarSubscriptionJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	/** Nulo é a agenda consolidada, o mesmo contrato da leitura. */
	@Column(nullable = true, length = 32)
	private String scope;

	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected CalendarSubscriptionJpaEntity() {
	}

	static CalendarSubscriptionJpaEntity fromDomain(CalendarSubscription subscription) {
		CalendarSubscriptionJpaEntity e = new CalendarSubscriptionJpaEntity();
		e.id = subscription.id();
		e.userId = subscription.userId();
		e.scope = subscription.scope() == null ? null : subscription.scope().name();
		e.tokenHash = subscription.tokenHash();
		e.createdAt = subscription.createdAt();
		e.revokedAt = subscription.revokedAt();
		return e;
	}

	CalendarSubscription toDomain() {
		return new CalendarSubscription(id, userId, scope == null ? null : CalendarScope.parse(scope), tokenHash,
			createdAt, revokedAt);
	}
}
