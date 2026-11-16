package com.socialcoisa.rede.desabafos.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;

/**
 * O desabafo: um texto curto e sincero, sem título, tags, mídia nem tópico — nada de vitrine.
 * Vive só no feed dos Desabafos; sair daqui é sair do ar (pelo autor ou pela moderação).
 */
public class DesabafoPost {

	private final String id;
	private final UserId authorId;
	private final String content;
	private final Instant createdAt;
	private DesabafoStatus status;
	private Instant removedAt;

	public DesabafoPost(String id, UserId authorId, String content, Instant createdAt, DesabafoStatus status,
			Instant removedAt) {
		this.id = id;
		this.authorId = authorId;
		this.content = content;
		this.createdAt = createdAt;
		this.status = status;
		this.removedAt = removedAt;
	}

	public static DesabafoPost publish(String id, UserId authorId, String content, Instant createdAt) {
		return new DesabafoPost(id, authorId, content, createdAt, DesabafoStatus.ACTIVE, null);
	}

	public boolean isActive() {
		return status == DesabafoStatus.ACTIVE;
	}

	public boolean remove(Instant removedAt) {
		if (status == DesabafoStatus.REMOVED) {
			return false;
		}
		this.status = DesabafoStatus.REMOVED;
		this.removedAt = removedAt;
		return true;
	}

	public String id() {
		return id;
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

	public Instant removedAt() {
		return removedAt;
	}
}
