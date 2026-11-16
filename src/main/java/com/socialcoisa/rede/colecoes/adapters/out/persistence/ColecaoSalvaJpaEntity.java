package com.socialcoisa.rede.colecoes.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.colecoes.domain.ColecaoSalva;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "colecoes_salvas")
class ColecaoSalvaJpaEntity {

	@Id
	private String id;

	@Column(name = "owner_id", nullable = false)
	private String ownerId;

	@Column(nullable = false, length = 120)
	private String title;

	@Column(length = 500)
	private String description;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ColecaoSalvaJpaEntity() {
	}

	ColecaoSalvaJpaEntity(ColecaoSalva colecao) {
		this.id = colecao.id();
		this.ownerId = colecao.ownerId().value();
		this.title = colecao.title();
		this.description = colecao.description();
		this.createdAt = colecao.createdAt();
	}

	ColecaoSalva toDomain() {
		return new ColecaoSalva(id, new com.socialcoisa.rede.feed.domain.UserId(ownerId), title, description,
			createdAt);
	}
}
