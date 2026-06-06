package com.socialcoisa.rede.shared.outbox;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_events", indexes = @Index(name = "idx_outbox_status_next_attempt", columnList = "status, next_attempt_at"))
class OutboxEventJpaEntity {

	@Id
	private String id;

	@Column(name = "event_type", nullable = false)
	private String eventType;

	@Column(name = "aggregate_id", nullable = false)
	private String aggregateId;

	@Lob
	@Column(nullable = false)
	private String payload;

	@Column(nullable = false)
	private String status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	protected OutboxEventJpaEntity() {
	}

	OutboxEventJpaEntity(String id, String eventType, String aggregateId, String payload, Instant createdAt) {
		this.id = id;
		this.eventType = eventType;
		this.aggregateId = aggregateId;
		this.payload = payload;
		this.status = "PENDING";
		this.createdAt = createdAt;
		this.nextAttemptAt = createdAt;
	}

	String id() {
		return id;
	}

	String status() {
		return status;
	}

	Instant nextAttemptAt() {
		return nextAttemptAt;
	}

	void markProcessed() {
		this.status = "PROCESSED";
	}
}
