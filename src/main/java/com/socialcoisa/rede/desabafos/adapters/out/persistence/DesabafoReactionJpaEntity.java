package com.socialcoisa.rede.desabafos.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Apoio de uma pessoa num desabafo. O id é (postId + ':' + userId): trocar o tipo de reação é
 * salvar de novo com a mesma chave, sem consulta para descobrir se já existia. A lista nunca
 * sai daqui — só contagens agregadas.
 */
@Entity
@Table(name = "desabafos_reactions")
class DesabafoReactionJpaEntity {

	@Id
	private String id;

	@Column(name = "post_id", nullable = false)
	private String postId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(nullable = false, length = 30)
	private String reaction;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected DesabafoReactionJpaEntity() {
	}

	DesabafoReactionJpaEntity(String postId, String userId, String reaction, Instant createdAt) {
		this.id = postId + ":" + userId;
		this.postId = postId;
		this.userId = userId;
		this.reaction = reaction;
		this.createdAt = createdAt;
	}

	String postId() {
		return postId;
	}

	String userId() {
		return userId;
	}

	String reaction() {
		return reaction;
	}
}
