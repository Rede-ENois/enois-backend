package com.socialcoisa.rede.desafios.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Torcida numa participação. O id é (participacaoId + ':' + userId): torcer de novo é salvar de
 * novo na mesma chave — uma torcida por pessoa. A lista nunca sai daqui — só contagens.
 */
@Entity
@Table(name = "desafios_reacoes")
class DesafioCheerJpaEntity {

	@Id
	private String id;

	@Column(name = "participacao_id", nullable = false)
	private String participacaoId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected DesafioCheerJpaEntity() {
	}

	DesafioCheerJpaEntity(String participacaoId, String userId, Instant createdAt) {
		this.id = participacaoId + ":" + userId;
		this.participacaoId = participacaoId;
		this.userId = userId;
		this.createdAt = createdAt;
	}

	String participacaoId() {
		return participacaoId;
	}

	String userId() {
		return userId;
	}
}
