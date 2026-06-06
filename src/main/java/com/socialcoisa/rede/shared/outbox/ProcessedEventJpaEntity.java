package com.socialcoisa.rede.shared.outbox;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "processed_events")
class ProcessedEventJpaEntity {

	@Id
	@Column(name = "event_id")
	private String eventId;

	@Column(name = "processed_at", nullable = false)
	private Instant processedAt;

	protected ProcessedEventJpaEntity() {
	}

	ProcessedEventJpaEntity(String eventId, Instant processedAt) {
		this.eventId = eventId;
		this.processedAt = processedAt;
	}
}
