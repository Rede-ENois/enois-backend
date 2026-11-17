package com.socialcoisa.rede.confessionario.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.confessionario.domain.ConfissaoComment;
import com.socialcoisa.rede.confessionario.domain.ConfissaoStatus;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Comentário de reação numa confissão. Plano de propósito: sem comentário de comentário —
 * toda reação responde a história direta.
 */
@Entity
@DynamicUpdate
@Table(name = "confessionario_comments")
class ConfissaoCommentJpaEntity {

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
	private ConfissaoStatus status;

	protected ConfissaoCommentJpaEntity() {
	}

	ConfissaoCommentJpaEntity(ConfissaoComment comment) {
		this.id = comment.id();
		this.postId = comment.postId();
		this.authorId = comment.authorId().value();
		this.content = comment.content();
		this.createdAt = comment.createdAt();
		this.status = comment.status();
	}

	ConfissaoComment toDomain() {
		return new ConfissaoComment(id, postId, new com.socialcoisa.rede.feed.domain.UserId(authorId), content,
			createdAt, status);
	}
}
