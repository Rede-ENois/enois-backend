package com.socialcoisa.rede.desafios.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;

/**
 * A participação: o que uma pessoa publicou como resultado do desafio — texto puro, uma por
 * pessoa por desafio (republicar substitui a anterior). Nominal como o desafio: quem topa um
 * convite público assina o que fez.
 */
public class Participacao {

	private final String id;
	private final String desafioId;
	private final UserId authorId;
	private final String content;
	private final Instant createdAt;
	private ParticipacaoStatus status;

	public Participacao(String id, String desafioId, UserId authorId, String content, Instant createdAt,
			ParticipacaoStatus status) {
		this.id = id;
		this.desafioId = desafioId;
		this.authorId = authorId;
		this.content = content;
		this.createdAt = createdAt;
		this.status = status;
	}

	public static Participacao publish(String id, String desafioId, UserId authorId, String content,
			Instant createdAt) {
		return new Participacao(id, desafioId, authorId, content, createdAt, ParticipacaoStatus.ACTIVE);
	}

	public boolean isActive() {
		return status == ParticipacaoStatus.ACTIVE;
	}

	public boolean remove() {
		if (status == ParticipacaoStatus.REMOVED) {
			return false;
		}
		this.status = ParticipacaoStatus.REMOVED;
		return true;
	}

	public String id() {
		return id;
	}

	public String desafioId() {
		return desafioId;
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

	public ParticipacaoStatus status() {
		return status;
	}
}
