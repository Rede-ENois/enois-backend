package com.socialcoisa.rede.murais.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;

/**
 * O quadro do mural: título curto, descrição opcional e um dono. Não guarda conteúdo — as
 * publicações continuam no feed; o mural só coleciona os elos ({@link MuralContribuicao}).
 */
public class Mural {

	private final String id;
	private final UserId ownerId;
	private String title;
	private String description;
	private final Instant createdAt;
	private MuralStatus status;
	private Instant removedAt;

	public Mural(String id, UserId ownerId, String title, String description, Instant createdAt, MuralStatus status,
			Instant removedAt) {
		this.id = id;
		this.ownerId = ownerId;
		this.title = title;
		this.description = description;
		this.createdAt = createdAt;
		this.status = status;
		this.removedAt = removedAt;
	}

	public static Mural novo(String id, UserId ownerId, String title, String description, Instant createdAt) {
		return new Mural(id, ownerId, title, description, createdAt, MuralStatus.ACTIVE, null);
	}

	public boolean isActive() {
		return status == MuralStatus.ACTIVE;
	}

	public boolean ownedBy(UserId userId) {
		return ownerId.equals(userId);
	}

	public void edit(String title, String description) {
		if (!isActive()) {
			throw new IllegalStateException("Mural removido não se edita.");
		}
		this.title = title;
		this.description = description;
	}

	public boolean remove(Instant removedAt) {
		if (status == MuralStatus.REMOVED) {
			return false;
		}
		this.status = MuralStatus.REMOVED;
		this.removedAt = removedAt;
		return true;
	}

	public String id() {
		return id;
	}

	public UserId ownerId() {
		return ownerId;
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

	public MuralStatus status() {
		return status;
	}

	public Instant removedAt() {
		return removedAt;
	}
}
