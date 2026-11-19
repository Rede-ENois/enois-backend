package com.socialcoisa.rede.colecoes.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;

/**
 * A pasta pessoal: título curto, descrição opcional e um dono. Não guarda conteúdo — as
 * publicações continuam salvas no perfil; a coleção só organiza os elos ({@link ColecaoPost}).
 * Não tem status de remoção: coleção é dado privado, e remover é apagar de verdade.
 */
public class ColecaoSalva {

	private final String id;
	private final UserId ownerId;
	private String title;
	private String description;
	private final Instant createdAt;

	public ColecaoSalva(String id, UserId ownerId, String title, String description, Instant createdAt) {
		this.id = id;
		this.ownerId = ownerId;
		this.title = title;
		this.description = description;
		this.createdAt = createdAt;
	}

	public boolean ownedBy(UserId userId) {
		return ownerId.equals(userId);
	}

	public void edit(String title, String description) {
		this.title = title;
		this.description = description;
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
}
