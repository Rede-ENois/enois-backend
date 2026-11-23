package com.socialcoisa.rede.desabafos.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.desabafos.domain.DesabafoPost;
import com.socialcoisa.rede.desabafos.domain.DesabafoStatus;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@DynamicUpdate
@Table(name = "desabafos_posts")
class DesabafoPostJpaEntity {

	@Id
	private String id;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DesabafoStatus status;

	@Column(name = "removed_at")
	private Instant removedAt;

	protected DesabafoPostJpaEntity() {
	}

	DesabafoPostJpaEntity(DesabafoPost post) {
		this.id = post.id();
		this.authorId = post.authorId().value();
		this.content = post.content();
		this.createdAt = post.createdAt();
		this.status = post.status();
		this.removedAt = post.removedAt();
	}

	DesabafoPost toDomain() {
		return new DesabafoPost(id, new com.socialcoisa.rede.feed.domain.UserId(authorId), content, createdAt, status,
			removedAt);
	}

	String getId() {
		return id;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

}
