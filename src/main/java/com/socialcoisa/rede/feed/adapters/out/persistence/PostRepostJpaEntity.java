package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.PostRepost;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "post_reposts", uniqueConstraints = {
		@UniqueConstraint(name = "uk_post_reposts_user_post", columnNames = { "user_id", "post_id" }) },
		indexes = {
			@Index(name = "idx_post_reposts_user", columnList = "user_id"),
			@Index(name = "idx_post_reposts_post", columnList = "post_id") })
class PostRepostJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "post_id", nullable = false)
	private String postId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected PostRepostJpaEntity() {
	}

	static PostRepostJpaEntity fromDomain(PostRepost repost) {
		PostRepostJpaEntity e = new PostRepostJpaEntity();
		e.id = repost.id();
		e.userId = repost.userId();
		e.postId = repost.postId();
		e.createdAt = repost.createdAt();
		return e;
	}

	PostRepost toDomain() {
		return PostRepost.restore(id, userId, postId, createdAt);
	}

	String getUserId() { return userId; }
	String getPostId() { return postId; }
}
