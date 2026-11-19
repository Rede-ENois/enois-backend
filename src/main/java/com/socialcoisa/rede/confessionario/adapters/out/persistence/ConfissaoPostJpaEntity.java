package com.socialcoisa.rede.confessionario.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.confessionario.domain.ConfissaoPost;
import com.socialcoisa.rede.confessionario.domain.ConfissaoStatus;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@DynamicUpdate
@Table(name = "confessionario_posts")
class ConfissaoPostJpaEntity {

	@Id
	private String id;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConfissaoStatus status;

	@Column(name = "removed_at")
	private Instant removedAt;

	protected ConfissaoPostJpaEntity() {
	}

	ConfissaoPostJpaEntity(ConfissaoPost post) {
		this.id = post.id();
		this.authorId = post.authorId().value();
		this.content = post.content();
		this.createdAt = post.createdAt();
		this.status = post.status();
		this.removedAt = post.removedAt();
	}

	ConfissaoPost toDomain() {
		return new ConfissaoPost(id, new com.socialcoisa.rede.feed.domain.UserId(authorId), content, createdAt, status,
			removedAt);
	}
}
