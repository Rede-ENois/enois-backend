package com.socialcoisa.rede.desafios.domain;

import java.time.Instant;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.UserId;

/**
 * O desafio: um convite público da comunidade — quem convoca descreve o que vale a pena fazer,
 * quem topa publica o que fez, quem convocou encerra escolhendo o que mais representou o
 * desafio. Sem gamificação: sem pontos, ranking ou prêmio — o encerramento é um reconhecimento
 * nominal dentro do próprio desafio. Vive só no espaço de Desafios, fora dos feeds gerais.
 */
public class Desafio {

	private final String id;
	private final UserId authorId;
	private final String title;
	private final String description;
	private final Instant createdAt;
	private final Instant endsAt;
	private DesafioStatus status;
	private Instant closedAt;
	private String destaqueId;
	private Instant removedAt;

	public Desafio(String id, UserId authorId, String title, String description, Instant createdAt, Instant endsAt,
			DesafioStatus status, Instant closedAt, String destaqueId, Instant removedAt) {
		this.id = id;
		this.authorId = authorId;
		this.title = title;
		this.description = description;
		this.createdAt = createdAt;
		this.endsAt = endsAt;
		this.status = status;
		this.closedAt = closedAt;
		this.destaqueId = destaqueId;
		this.removedAt = removedAt;
	}

	public static Desafio publish(String id, UserId authorId, String title, String description, Instant createdAt,
			Instant endsAt) {
		return new Desafio(id, authorId, title, description, createdAt, endsAt, DesafioStatus.ACTIVE, null, null,
			null);
	}

	public boolean isActive() {
		return status == DesafioStatus.ACTIVE;
	}

	public boolean isClosed() {
		return status == DesafioStatus.CLOSED;
	}

	/** Participações só entram enquanto o desafio está no ar e aberto. */
	public boolean aceitaParticipacao(Instant agora) {
		if (!isActive()) {
			return false;
		}
		return endsAt == null || agora.isBefore(endsAt);
	}

	public boolean remove(Instant removedAt) {
		if (status == DesafioStatus.REMOVED) {
			return false;
		}
		this.status = DesafioStatus.REMOVED;
		this.removedAt = removedAt;
		return true;
	}

	/** Encerra com o reconhecimento: quem participou viu o desafio virar história. */
	public boolean close(Instant closedAt, Optional<String> destaqueId) {
		if (status != DesafioStatus.ACTIVE) {
			return false;
		}
		this.status = DesafioStatus.CLOSED;
		this.closedAt = closedAt;
		this.destaqueId = destaqueId.orElse(null);
		return true;
	}

	public String id() {
		return id;
	}

	public UserId authorId() {
		return authorId;
	}

	public String title() {
		return title;
	}

	public String description() {
		return description;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant endsAt() {
		return endsAt;
	}

	public DesafioStatus status() {
		return status;
	}

	public Instant closedAt() {
		return closedAt;
	}

	public String destaqueId() {
		return destaqueId;
	}

	public Instant removedAt() {
		return removedAt;
	}
}
