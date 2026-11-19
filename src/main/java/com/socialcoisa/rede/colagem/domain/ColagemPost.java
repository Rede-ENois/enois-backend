package com.socialcoisa.rede.colagem.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;

/**
 * Publicação da Colagem: exatamente UMA fotografia (a mídia é do autor, pré-moderada no upload)
 * e uma descrição opcional curta. Não tem título, tags nem tópico — é foto e legada, nada mais.
 */
public class ColagemPost {

	private final String id;
	private final UserId authorId;
	private final String mediaId;
	private final String description;
	private final boolean mediaSpoiler;
	private final Instant createdAt;
	private ColagemStatus status;
	private Instant removedAt;

	public ColagemPost(String id, UserId authorId, String mediaId, String description, boolean mediaSpoiler,
			Instant createdAt, ColagemStatus status, Instant removedAt) {
		this.id = id;
		this.authorId = authorId;
		this.mediaId = mediaId;
		this.description = description;
		this.mediaSpoiler = mediaSpoiler;
		this.createdAt = createdAt;
		this.status = status;
		this.removedAt = removedAt;
	}

	public static ColagemPost publish(String id, UserId authorId, String mediaId, String description,
			boolean mediaSpoiler, Instant createdAt) {
		return new ColagemPost(id, authorId, mediaId, description, mediaSpoiler, createdAt, ColagemStatus.ACTIVE, null);
	}

	public boolean isActive() {
		return status == ColagemStatus.ACTIVE;
	}

	public boolean remove(Instant removedAt) {
		if (status == ColagemStatus.REMOVED) {
			return false;
		}
		this.status = ColagemStatus.REMOVED;
		this.removedAt = removedAt;
		return true;
	}

	public String id() {
		return id;
	}

	public UserId authorId() {
		return authorId;
	}

	public String mediaId() {
		return mediaId;
	}

	public String description() {
		return description;
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

	public Instant removedAt() {
		return removedAt;
	}
}
