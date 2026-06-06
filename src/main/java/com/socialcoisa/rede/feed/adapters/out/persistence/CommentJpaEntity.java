package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.CommentStatus;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "comments", indexes = {
		@Index(name = "idx_comments_post_created", columnList = "post_id, created_at"),
		@Index(name = "idx_comments_parent_created", columnList = "parent_comment_id, created_at") })
class CommentJpaEntity {

	@Id
	private String id;

	@Column(name = "post_id", nullable = false)
	private String postId;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	@Column(name = "parent_comment_id")
	private String parentCommentId;

	@Column(nullable = false, length = 1024)
	private String content;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CommentStatus status;

	protected CommentJpaEntity() {
	}

	static CommentJpaEntity fromDomain(Comment comment) {
		CommentJpaEntity entity = new CommentJpaEntity();
		entity.id = comment.id();
		entity.postId = comment.postId().value();
		entity.authorId = comment.authorId().value();
		entity.parentCommentId = comment.parentCommentId();
		entity.content = comment.content();
		entity.createdAt = comment.createdAt();
		entity.status = comment.status();
		return entity;
	}

	Comment toDomain() {
		return new Comment(id, new PostId(postId), new UserId(authorId), parentCommentId, content, createdAt, status);
	}
}
