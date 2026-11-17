package com.socialcoisa.rede.murais.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.murais.domain.MuralContribuicao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "mural_contribuicoes")
class MuralContribuicaoJpaEntity {

	@Id
	private String id;

	@Column(name = "mural_id", nullable = false)
	private String muralId;

	@Column(name = "post_id", nullable = false)
	private String postId;

	@Column(name = "added_by", nullable = false)
	private String addedBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected MuralContribuicaoJpaEntity() {
	}

	MuralContribuicaoJpaEntity(MuralContribuicao contribuicao) {
		this.id = contribuicao.id();
		this.muralId = contribuicao.muralId();
		this.postId = contribuicao.postId();
		this.addedBy = contribuicao.addedBy().value();
		this.createdAt = contribuicao.createdAt();
	}

	MuralContribuicao toDomain() {
		return new MuralContribuicao(id, muralId, postId, new com.socialcoisa.rede.feed.domain.UserId(addedBy),
			createdAt);
	}

	String muralId() {
		return muralId;
	}
}
