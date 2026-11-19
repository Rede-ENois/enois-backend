package com.socialcoisa.rede.desabafos.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;

/**
 * O comentário de um desabafo: texto puro, uma acolhida. Sem resposta aninhada — a conversa é
 * rasa de propósito, para acolher sem virar palco.
 */
public class DesabafoComment {

	private final String id;
	private final String postId;
	private final UserId authorId;
	private final String content;
	private final Instant createdAt;
	private DesabafoStatus status;

	public DesabafoComment(String id, String postId, UserId authorId, String content, Instant createdAt,
			DesabafoStatus status) {
		this.id = id;
		this.postId = postId;
		this.authorId = authorId;
		this.content = content;
		this.createdAt = createdAt;
		this.status = status;
	}

	public boolean isActive() {
		return status == DesabafoStatus.ACTIVE;
	}

	public void remove() {
		this.status = DesabafoStatus.REMOVED;
	}

	public String id() {
		return id;
	}

	public String postId() {
		return postId;
	}

	public UserId authorId() {
		return authorId;
	}

	public String content() {
		return content;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public DesabafoStatus status() {
		return status;
	}
}
