package com.socialcoisa.rede.shared.outbox;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Recibo de UM handler para UM evento: a prova de que aquele efeito ja foi aplicado. A nova
 * tentativa pula o handler com recibo e executa so os que faltaram.
 *
 * <p>Fica em tabela propria (e nao em {@code processed_events}) porque a linha daquela era gravada
 * antes da execucao e nao significa "handler executou".</p>
 */
@Entity
@Table(name = "processed_event_handlers")
@IdClass(OutboxHandlerReceiptJpaEntity.Key.class)
class OutboxHandlerReceiptJpaEntity {

	@Id
	@Column(name = "event_id")
	private String eventId;

	@Id
	@Column(name = "handler")
	private String handler;

	@Column(name = "processed_at", nullable = false)
	private Instant processedAt;

	protected OutboxHandlerReceiptJpaEntity() {
	}

	OutboxHandlerReceiptJpaEntity(String eventId, String handler, Instant processedAt) {
		this.eventId = eventId;
		this.handler = handler;
		this.processedAt = processedAt;
	}

	String eventId() {
		return eventId;
	}

	String handler() {
		return handler;
	}

	Instant processedAt() {
		return processedAt;
	}

	record Key(String eventId, String handler) implements Serializable {
	}
}
