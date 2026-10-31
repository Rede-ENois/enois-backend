package com.socialcoisa.rede.feed.adapters.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Alternativa de uma enquete; `position` e o que o voto guarda (0..5). */
@Entity
@Table(name = "poll_options",
		uniqueConstraints = @UniqueConstraint(name = "uk_poll_options_post_position",
			columnNames = { "post_id", "position" }),
		indexes = @Index(name = "idx_poll_options_post", columnList = "post_id, position"))
class PollOptionJpaEntity {

	@Id
	private String id;

	@Column(name = "post_id", nullable = false)
	private String postId;

	@Column(name = "position", nullable = false)
	private int position;

	@Column(name = "text", nullable = false, length = 120)
	private String text;

	protected PollOptionJpaEntity() {
	}

	PollOptionJpaEntity(String id, String postId, int position, String text) {
		this.id = id;
		this.postId = postId;
		this.position = position;
		this.text = text;
	}

	String getPostId() { return postId; }
	int getPosition() { return position; }
	String getText() { return text; }
}
