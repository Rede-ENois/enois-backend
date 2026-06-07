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
@Table(name = "reactions",
		uniqueConstraints = @UniqueConstraint(name = "uk_reactions_post_user", columnNames = { "post_id", "user_id" }),
		indexes = @Index(name = "idx_reactions_user_created", columnList = "user_id, created_at"))
class ReactionJpaEntity {

	@Id
	private String id;

	@Column(name = "post_id", nullable = false)
	private String postId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reaction_type", nullable = false)
	private ReactionType reactionType;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ReactionJpaEntity() {
	}

	ReactionJpaEntity(String id, String postId, String userId, ReactionType reactionType, Instant createdAt) {
		this.id = id;
		this.postId = postId;
		this.userId = userId;
		this.reactionType = reactionType;
		this.createdAt = createdAt;
	}

	String getPostId() { return postId; }
	String getUserId() { return userId; }
	ReactionType getReactionType() { return reactionType; }
	Instant getCreatedAt() { return createdAt; }
}
