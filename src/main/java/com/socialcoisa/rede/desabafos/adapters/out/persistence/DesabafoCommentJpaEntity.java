package com.socialcoisa.rede.desabafos.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.desabafos.domain.DesabafoComment;
import com.socialcoisa.rede.desabafos.domain.DesabafoStatus;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Comentário de acolhida num desabafo. Plano de propósito: sem comentário de comentário —
 * toda acolhida responde o desabafo direto.
 */
@Entity
@DynamicUpdate
@Table(name = "desabafos_comments")
class DesabafoCommentJpaEntity {

	@Id
	private String id;

	@Column(name = "post_id", nullable = false)
	private String postId;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	@Column(nullable = false, length = 800)
	private String content;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DesabafoStatus status;

	protected DesabafoCommentJpaEntity() {
	}

	DesabafoCommentJpaEntity(DesabafoComment comment) {
		this.id = comment.id();
		this.postId = comment.postId();
		this.authorId = comment.authorId().value();
		this.content = comment.content();
		this.createdAt = comment.createdAt();
		this.status = comment.status();
	}

	DesabafoComment toDomain() {
		return new DesabafoComment(id, postId, new com.socialcoisa.rede.feed.domain.UserId(authorId), content,
			createdAt, status);
	}

	String getId() {
		return id;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

}
