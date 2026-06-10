package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.ReactionType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "comment_reactions",
	uniqueConstraints = @UniqueConstraint(name = "uk_comment_reactions_comment_user", columnNames = { "comment_id", "user_id" }),
	indexes = @Index(name = "idx_comment_reactions_user", columnList = "user_id, created_at"))
class CommentReactionJpaEntity {

	@Id
	private String id;

	@Column(name = "comment_id", nullable = false)
	private String commentId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reaction_type", nullable = false)
	private ReactionType reactionType;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected CommentReactionJpaEntity() {
	}

	CommentReactionJpaEntity(String id, String commentId, String userId, ReactionType reactionType, Instant createdAt) {
		this.id = id;
		this.commentId = commentId;
		this.userId = userId;
		this.reactionType = reactionType;
		this.createdAt = createdAt;
	}

	String getCommentId() { return commentId; }
	String getUserId() { return userId; }
	ReactionType getReactionType() { return reactionType; }
	Instant getCreatedAt() { return createdAt; }
}
