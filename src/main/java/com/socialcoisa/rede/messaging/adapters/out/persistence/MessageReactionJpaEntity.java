package com.socialcoisa.rede.messaging.adapters.out.persistence;

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
@Table(name = "message_reactions",
	uniqueConstraints = @UniqueConstraint(name = "uk_message_reactions_message_user", columnNames = { "message_id", "user_id" }),
	indexes = @Index(name = "idx_message_reactions_user", columnList = "user_id, created_at"))
public class MessageReactionJpaEntity {

	@Id
	private String id;

	@Column(name = "message_id", nullable = false)
	private String messageId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reaction_type", nullable = false)
	private ReactionType reactionType;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected MessageReactionJpaEntity() {
	}

	public MessageReactionJpaEntity(String id, String messageId, String userId, ReactionType reactionType, Instant createdAt) {
		this.id = id;
		this.messageId = messageId;
		this.userId = userId;
		this.reactionType = reactionType;
		this.createdAt = createdAt;
	}

	public String getMessageId() { return messageId; }
	public String getUserId() { return userId; }
	public ReactionType getReactionType() { return reactionType; }
	public Instant getCreatedAt() { return createdAt; }
}
