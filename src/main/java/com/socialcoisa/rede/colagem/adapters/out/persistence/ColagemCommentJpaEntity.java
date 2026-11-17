package com.socialcoisa.rede.colagem.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.colagem.domain.ColagemComment;
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
@Table(name = "colagem_comments")
class ColagemCommentJpaEntity {

	@Id
	private String id;

	@Column(name = "post_id", nullable = false)
	private String postId;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	@Column(name = "parent_comment_id")
	private String parentCommentId;

	@Column(length = 800)
	private String content;

	@Column(name = "media_id")
	private String mediaId;

	@Column(name = "media_spoiler", nullable = false)
	private boolean mediaSpoiler;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ColagemStatus status;

	protected ColagemCommentJpaEntity() {
	}

	ColagemCommentJpaEntity(ColagemComment comment) {
		this.id = comment.id();
		this.postId = comment.postId();
		this.authorId = comment.authorId().value();
		this.parentCommentId = comment.parentCommentId();
		this.content = comment.content();
		this.mediaId = comment.mediaId();
		this.mediaSpoiler = comment.mediaSpoiler();
		this.createdAt = comment.createdAt();
		this.status = comment.status();
	}

	ColagemComment toDomain() {
		return new ColagemComment(id, postId, new com.socialcoisa.rede.feed.domain.UserId(authorId), parentCommentId,
			content, mediaId, mediaSpoiler, createdAt, status);
	}
}
