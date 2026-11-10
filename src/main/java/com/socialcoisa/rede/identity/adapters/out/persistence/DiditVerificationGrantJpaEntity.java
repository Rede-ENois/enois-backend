package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Convite de verificacao de identidade (tabela {@code didit_verification_grants}).
 *
 * <p>Guarda apenas a decisao de liberacao: quem, em que rodada, com que credibilidade e quando.
 * Nenhum dado da verificacao em si mora aqui.</p>
 */
@Entity
@Table(name = "didit_verification_grants")
class DiditVerificationGrantJpaEntity {

	@Id
	@Column(name = "user_id")
	private String userId;

	@Column(name = "round_id")
	private String roundId;

	@Column(name = "reputation_at_grant", nullable = false)
	private int reputationAtGrant;

	@Column(name = "granted_at", nullable = false)
	private Instant grantedAt;

	@Column(name = "consumed_at")
	private Instant consumedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected DiditVerificationGrantJpaEntity() {
	}

	static DiditVerificationGrantJpaEntity fresh(String userId, String roundId, int reputation, Instant now) {
		DiditVerificationGrantJpaEntity entity = new DiditVerificationGrantJpaEntity();
		entity.userId = userId;
		entity.roundId = roundId;
		entity.reputationAtGrant = reputation;
		entity.grantedAt = now;
		return entity;
	}

	String userId() { return userId; }

	void consume(Instant now) { this.consumedAt = now; }

	void revoke(Instant now) { this.revokedAt = now; }
}
