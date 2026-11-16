package com.socialcoisa.rede.colagem.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;

/**
 * Contribuição numa publicação da Colagem: texto, uma fotografia, ou os dois juntos. Resposta a
 * comentário fotográfico não pode carregar fotografia — sem recursão de imagens (valida o service).
 */
public class ColagemComment {

	private final String id;
	private final String postId;
	private final UserId authorId;
	/** Comentário pai (resposta); nulo = contribuição de primeiro nível. */
	private final String parentCommentId;
	private final String content;
	private final String mediaId;
	private final boolean mediaSpoiler;
	private final Instant createdAt;
	private ColagemStatus status;

	public ColagemComment(String id, String postId, UserId authorId, String parentCommentId, String content,
			String mediaId, boolean mediaSpoiler, Instant createdAt, ColagemStatus status) {
		this.id = id;
		this.postId = postId;
		this.authorId = authorId;
		this.parentCommentId = parentCommentId;
		this.content = content;
		this.mediaId = mediaId;
		this.mediaSpoiler = mediaSpoiler;
		this.createdAt = createdAt;
		this.status = status;
	}

	public boolean isActive() {
		return status == ColagemStatus.ACTIVE;
	}

	public boolean remove() {
		if (status == ColagemStatus.REMOVED) {
			return false;
		}
		this.status = ColagemStatus.REMOVED;
		return true;
	}

	public boolean hasPhoto() {
		return mediaId != null && !mediaId.isBlank();
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

	public String parentCommentId() {
		return parentCommentId;
	}

	public String content() {
		return content;
	}

	public String mediaId() {
		return mediaId;
	}

	public boolean mediaSpoiler() {
		return mediaSpoiler;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public ColagemStatus status() {
		return status;
	}
}
