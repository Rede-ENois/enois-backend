package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "articles")
class ArticleJpaEntity {

	@Id
	@Column(name = "post_id")
	private String postId;

	@Column(nullable = false, length = 10000)
	private String body;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "edited_at")
	private Instant editedAt;

	protected ArticleJpaEntity() {
	}

	static ArticleJpaEntity of(String postId, String body, Instant createdAt) {
		ArticleJpaEntity entity = new ArticleJpaEntity();
		entity.postId = postId;
		entity.body = body;
		entity.createdAt = createdAt;
		return entity;
	}

	String body() {
		return body;
	}

	void changeBody(String newBody, Instant editedAt) {
		this.body = newBody;
		this.editedAt = editedAt;
	}
}
