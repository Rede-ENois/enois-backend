package com.socialcoisa.rede.colagem.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.colagem.domain.ColagemPost;
import com.socialcoisa.rede.colagem.domain.ColagemStatus;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@DynamicUpdate
@Table(name = "colagem_posts")
class ColagemPostJpaEntity {

	@Id
	private String id;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	@Column(name = "media_id", nullable = false)
	private String mediaId;

	@Column(length = 500)
	private String description;

	@Column(name = "media_spoiler", nullable = false)
	private boolean mediaSpoiler;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ColagemStatus status;

	@Column(name = "removed_at")
	private Instant removedAt;

	protected ColagemPostJpaEntity() {
	}

	ColagemPostJpaEntity(ColagemPost post) {
		this.id = post.id();
		this.authorId = post.authorId().value();
		this.mediaId = post.mediaId();
		this.description = post.description();
		this.mediaSpoiler = post.mediaSpoiler();
		this.createdAt = post.createdAt();
		this.status = post.status();
		this.removedAt = post.removedAt();
	}

	ColagemPost toDomain() {
		return new ColagemPost(id, new com.socialcoisa.rede.feed.domain.UserId(authorId), mediaId, description,
			mediaSpoiler, createdAt, status, removedAt);
	}
}
