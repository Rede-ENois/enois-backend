package com.socialcoisa.rede.murais.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.murais.domain.Mural;
import com.socialcoisa.rede.murais.domain.MuralStatus;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@DynamicUpdate
@Table(name = "murais")
class MuralJpaEntity {

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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MuralStatus status;

	@Column(name = "removed_at")
	private Instant removedAt;

	protected MuralJpaEntity() {
	}

	MuralJpaEntity(Mural mural) {
		this.id = mural.id();
		this.ownerId = mural.ownerId().value();
		this.title = mural.title();
		this.description = mural.description();
		this.createdAt = mural.createdAt();
		this.status = mural.status();
		this.removedAt = mural.removedAt();
	}

	Mural toDomain() {
		return new Mural(id, new com.socialcoisa.rede.feed.domain.UserId(ownerId), title, description, createdAt,
			status, removedAt);
	}

	String id() {
		return id;
	}
}
