package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "post_shares",
		uniqueConstraints = @UniqueConstraint(name = "uk_post_shares_post_user", columnNames = { "post_id", "user_id" }),
		indexes = @Index(name = "idx_post_shares_user_created", columnList = "user_id, created_at"))
class PostShareJpaEntity {

	@Id
	private String id;

	@Column(name = "post_id", nullable = false)
	private String postId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected PostShareJpaEntity() {
	}

	PostShareJpaEntity(String id, String postId, String userId, Instant createdAt) {
		this.id = id;
		this.postId = postId;
		this.userId = userId;
		this.createdAt = createdAt;
	}
}
