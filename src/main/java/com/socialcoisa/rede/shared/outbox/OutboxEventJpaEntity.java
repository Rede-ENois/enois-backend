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

	@Column(nullable = false, length = 10000)
	private String payload;

	@Column(nullable = false)
	private String status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "last_error", length = 500)
	private String lastError;

	@Column(name = "claimed_at")
	private Instant claimedAt;

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

	String eventType() {
		return eventType;
	}

	String aggregateId() {
		return aggregateId;
	}

	String payload() {
		return payload;
	}

	String status() {
		return status;
	}

	Instant nextAttemptAt() {
		return nextAttemptAt;
	}

	int attempts() {
		return attempts;
	}

	String lastError() {
		return lastError;
	}

	Instant claimedAt() {
		return claimedAt;
	}

	void markProcessed() {
		this.status = "PROCESSED";
		this.claimedAt = null;
	}

	/**
	 * Falha do handler: o evento continua pendente e so volta na proxima tentativa, depois do
	 * backoff. O erro fica na linha para o diagnostico nao depender do log do pod.
	 */
	void markFailed(String error, Instant nextAttemptAt) {
		this.attempts++;
		this.lastError = error;
		this.nextAttemptAt = nextAttemptAt;
		this.claimedAt = null;
	}

	/**
	 * Estado terminal: o evento esgotou as tentativas e sai da fila.
	 *
	 * <p>Reprocessar para sempre nao conserta evento quebrado — so queima CPU e, como o lote sai
	 * pelos mais antigos, empurra os eventos novos para fora da leitura. O evento fica na tabela
	 * (o efeito nao se perde: ha o que inspecionar e o que reprocessar a mao) e para de ser
	 * escolhido pela fila.</p>
	 */
	void markFailedTerminal(String error) {
		this.attempts++;
		this.lastError = error;
		this.status = "FAILED";
		this.claimedAt = null;
	}
}
