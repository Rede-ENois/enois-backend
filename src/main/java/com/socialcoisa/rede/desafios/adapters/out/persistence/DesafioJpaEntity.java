package com.socialcoisa.rede.desafios.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.desafios.domain.Desafio;
import com.socialcoisa.rede.desafios.domain.DesafioStatus;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@DynamicUpdate
@Table(name = "desafios")
class DesafioJpaEntity {

	@Id
	private String id;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	@Column(nullable = false, length = 120)
	private String title;

	@Column(nullable = false, columnDefinition = "text")
	private String description;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "ends_at")
	private Instant endsAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DesafioStatus status;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "destaque_id")
	private String destaqueId;

	@Column(name = "removed_at")
	private Instant removedAt;

	protected DesafioJpaEntity() {
	}

	DesafioJpaEntity(Desafio desafio) {
		this.id = desafio.id();
		this.authorId = desafio.authorId().value();
		this.title = desafio.title();
		this.description = desafio.description();
		this.createdAt = desafio.createdAt();
		this.endsAt = desafio.endsAt();
		this.status = desafio.status();
		this.closedAt = desafio.closedAt();
		this.destaqueId = desafio.destaqueId();
		this.removedAt = desafio.removedAt();
	}

	Desafio toDomain() {
		return new Desafio(id, new com.socialcoisa.rede.feed.domain.UserId(authorId), title, description, createdAt,
			endsAt, status, closedAt, destaqueId, removedAt);
	}
}
