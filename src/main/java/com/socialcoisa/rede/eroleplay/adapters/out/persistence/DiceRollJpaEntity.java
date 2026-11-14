package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * O registro de uma rolagem (uma linha por rolagem).
 *
 * <p>Nao ha atualizacao nem remocao aqui: o historico da mesa e o registro do que aconteceu, e o
 * que a aplicacao expoe e so-leitura. {@code dice_json} guarda cada dado sorteado, na ordem.</p>
 */
@Entity
@Table(name = "roleplay_dice_rolls")
class DiceRollJpaEntity {

	@Id
	private String id;

	@Column(name = "campaign_id", nullable = false)
	private String campaignId;

	@Column(name = "roller_id", nullable = false)
	private String rollerId;

	@Column(nullable = false, length = 60)
	private String expression;

	@Column(name = "dice_json", nullable = false, columnDefinition = "longtext")
	private String diceJson;

	@Column(nullable = false)
	private long total;

	@Column(length = 120)
	private String label;

	@Column(nullable = false, length = 16)
	private String visibility;

	@Column(name = "request_id")
	private String requestId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected DiceRollJpaEntity() {
	}

	DiceRollJpaEntity(String id, String campaignId, String rollerId, String expression, String diceJson,
			long total, String label, String visibility, String requestId, Instant createdAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.rollerId = rollerId;
		this.expression = expression;
		this.diceJson = diceJson;
		this.total = total;
		this.label = label;
		this.visibility = visibility;
		this.requestId = requestId;
		this.createdAt = createdAt;
	}

	String id() {
		return id;
	}

	String campaignId() {
		return campaignId;
	}

	String rollerId() {
		return rollerId;
	}

	String expression() {
		return expression;
	}

	String diceJson() {
		return diceJson;
	}

	long total() {
		return total;
	}

	String label() {
		return label;
	}

	String visibility() {
		return visibility;
	}

	String requestId() {
		return requestId;
	}

	Instant createdAt() {
		return createdAt;
	}
}
