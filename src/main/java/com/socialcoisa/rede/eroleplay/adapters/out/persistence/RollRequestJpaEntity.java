package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * O pedido de rolagem do mestre (uma linha por pedido). O estado muda uma unica vez: PENDING
 * vira ANSWERED com a rolagem que respondeu, e nao ha volta — um pedido respondido nao se
 * reabre.
 */
@Entity
@Table(name = "roleplay_roll_requests")
class RollRequestJpaEntity {

	@Id
	private String id;

	@Column(name = "campaign_id", nullable = false)
	private String campaignId;

	@Column(name = "master_id", nullable = false)
	private String masterId;

	@Column(name = "player_id", nullable = false)
	private String playerId;

	@Column(nullable = false, length = 60)
	private String expression;

	@Column(length = 120)
	private String label;

	@Column(name = "max_visibility", nullable = false, length = 16)
	private String maxVisibility;

	@Column(length = 300)
	private String reason;

	@Column(nullable = false, length = 16)
	private String status;

	@Column(name = "answered_roll_id")
	private String answeredRollId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "answered_at")
	private Instant answeredAt;

	protected RollRequestJpaEntity() {
	}

	RollRequestJpaEntity(String id, String campaignId, String masterId, String playerId, String expression,
			String label, String maxVisibility, String reason, String status, String answeredRollId,
			Instant createdAt, Instant answeredAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.masterId = masterId;
		this.playerId = playerId;
		this.expression = expression;
		this.label = label;
		this.maxVisibility = maxVisibility;
		this.reason = reason;
		this.status = status;
		this.answeredRollId = answeredRollId;
		this.createdAt = createdAt;
		this.answeredAt = answeredAt;
	}

	String id() {
		return id;
	}

	String campaignId() {
		return campaignId;
	}

	String masterId() {
		return masterId;
	}

	String playerId() {
		return playerId;
	}

	String expression() {
		return expression;
	}

	String label() {
		return label;
	}

	String maxVisibility() {
		return maxVisibility;
	}

	String reason() {
		return reason;
	}

	String status() {
		return status;
	}

	String answeredRollId() {
		return answeredRollId;
	}

	Instant createdAt() {
		return createdAt;
	}

	Instant answeredAt() {
		return answeredAt;
	}
}
