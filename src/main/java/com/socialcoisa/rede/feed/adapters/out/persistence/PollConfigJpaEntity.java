package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Configuracao da enquete (multipla escolha e prazo), uma linha por enquete. Enquetes antigas
 * ficam sem linha e o servico le como escolha unica e sem prazo.
 */
@Entity
@Table(name = "poll_configs")
class PollConfigJpaEntity {

	@Id
	@Column(name = "post_id")
	private String postId;

	@Column(name = "multiple_choice", nullable = false)
	private boolean multipleChoice;

	@Column(name = "ends_at")
	private Instant endsAt;

	protected PollConfigJpaEntity() {
	}

	PollConfigJpaEntity(String postId, boolean multipleChoice, Instant endsAt) {
		this.postId = postId;
		this.multipleChoice = multipleChoice;
		this.endsAt = endsAt;
	}

	String getPostId() { return postId; }
	boolean isMultipleChoice() { return multipleChoice; }
	Instant getEndsAt() { return endsAt; }
}
