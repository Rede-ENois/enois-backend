package com.socialcoisa.rede.network.domain;

import java.time.Instant;

/**
 * Silenciamento unidirecional: quem silencia deixa de ver o outro no feed, mensagens e
 * notificacoes, mas o outro continua vendo normalmente — sem saber que foi silenciado.
 */
public class UserMute {

	private final Long id;
	private final String muterId;
	private final String mutedId;
	private final Instant createdAt;

	private UserMute(Long id, String muterId, String mutedId, Instant createdAt) {
		this.id = id;
		this.muterId = muterId;
		this.mutedId = mutedId;
		this.createdAt = createdAt;
	}

	public static UserMute create(String muterId, String mutedId, Instant now) {
		return new UserMute(null, muterId, mutedId, now);
	}

	public static UserMute restore(Long id, String muterId, String mutedId, Instant createdAt) {
		return new UserMute(id, muterId, mutedId, createdAt);
	}

	public Long id() { return id; }
	public String muterId() { return muterId; }
	public String mutedId() { return mutedId; }
	public Instant createdAt() { return createdAt; }
}
