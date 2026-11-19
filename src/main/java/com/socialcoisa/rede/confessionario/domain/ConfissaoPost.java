package com.socialcoisa.rede.confessionario.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;

/**
 * A confissão: uma história contada — experiência, situação constrangedora, confissão ou
 * "nunca contei isso pra ninguém". Sem título, tags, mídia nem tópico: a história é o texto,
 * e o texto é anônimo. Vive só no feed do Confessionário; sair daqui é sair do ar (pelo autor
 * ou pela moderação).
 */
public class ConfissaoPost {

	private final String id;
	private final UserId authorId;
	private final String content;
	private final Instant createdAt;
	private ConfissaoStatus status;
	private Instant removedAt;

	public ConfissaoPost(String id, UserId authorId, String content, Instant createdAt, ConfissaoStatus status,
			Instant removedAt) {
		this.id = id;
		this.authorId = authorId;
		this.content = content;
		this.createdAt = createdAt;
		this.status = status;
		this.removedAt = removedAt;
	}

	public static ConfissaoPost publish(String id, UserId authorId, String content, Instant createdAt) {
		return new ConfissaoPost(id, authorId, content, createdAt, ConfissaoStatus.ACTIVE, null);
	}

	public boolean isActive() {
		return status == ConfissaoStatus.ACTIVE;
	}

	public boolean remove(Instant removedAt) {
		if (status == ConfissaoStatus.REMOVED) {
			return false;
		}
		this.status = ConfissaoStatus.REMOVED;
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

	public ConfissaoStatus status() {
		return status;
	}

	public Instant removedAt() {
		return removedAt;
	}
}
