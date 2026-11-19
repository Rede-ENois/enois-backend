package com.socialcoisa.rede.confessionario.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;

/**
 * O comentário de uma confissão: texto puro, uma reação à história — riso, empatia, o seu
 * "passei pelo mesmo". Sem resposta aninhada: toda reação responde a confissão direta.
 */
public class ConfissaoComment {

	private final String id;
	private final String postId;
	private final UserId authorId;
	private final String content;
	private final Instant createdAt;
	private ConfissaoStatus status;

	public ConfissaoComment(String id, String postId, UserId authorId, String content, Instant createdAt,
			ConfissaoStatus status) {
		this.id = id;
		this.postId = postId;
		this.authorId = authorId;
		this.content = content;
		this.createdAt = createdAt;
		this.status = status;
	}

	public boolean isActive() {
		return status == ConfissaoStatus.ACTIVE;
	}

	public void remove() {
		this.status = ConfissaoStatus.REMOVED;
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

	public ConfissaoStatus status() {
		return status;
	}
}
