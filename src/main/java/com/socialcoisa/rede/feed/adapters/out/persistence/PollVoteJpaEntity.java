package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Um voto por pessoa por enquete — a unique (post_id, user_id) e a regra, nao uma checagem
 * otimista no codigo: concorrencia nao cria dois votos nem troca o primeiro.
 */
@Entity
@Table(name = "poll_votes",
		uniqueConstraints = @UniqueConstraint(name = "uk_poll_votes_post_user", columnNames = { "post_id", "user_id" }),
		indexes = @Index(name = "idx_poll_votes_post", columnList = "post_id, option_position"))
class PollVoteJpaEntity {

	@Id
	private String id;

	@Column(name = "post_id", nullable = false)
	private String postId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "option_position", nullable = false)
	private int optionPosition;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected PollVoteJpaEntity() {
	}

	PollVoteJpaEntity(String id, String postId, String userId, int optionPosition, Instant createdAt) {
		this.id = id;
		this.postId = postId;
		this.userId = userId;
		this.optionPosition = optionPosition;
		this.createdAt = createdAt;
	}

	String getPostId() { return postId; }
	String getUserId() { return userId; }
	int getOptionPosition() { return optionPosition; }
}