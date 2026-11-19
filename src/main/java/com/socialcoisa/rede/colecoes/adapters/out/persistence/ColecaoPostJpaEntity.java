package com.socialcoisa.rede.colecoes.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.colecoes.domain.ColecaoPost;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "colecao_salva_posts")
class ColecaoPostJpaEntity {

	@Id
	private String id;

	@Column(name = "colecao_id", nullable = false)
	private String colecaoId;

	@Column(name = "post_id", nullable = false)
	private String postId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ColecaoPostJpaEntity() {
	}

	ColecaoPostJpaEntity(ColecaoPost elo) {
		this.id = elo.id();
		this.colecaoId = elo.colecaoId();
		this.postId = elo.postId();
		this.createdAt = elo.createdAt();
	}

	ColecaoPost toDomain() {
		return new ColecaoPost(id, colecaoId, postId, createdAt);
	}
}
