package com.socialcoisa.rede.network.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Silenciamento unidirecional: quem silencia deixa de ver o outro no feed, mensagens e
 * notificacoes, mas o outro continua vendo normalmente — sem saber que foi silenciado.
 *
 * <p>O silenciamento pode ter prazo (`expiresAt`): passado o instante, ele deixa de valer sozinho,
 * sem ninguem precisar voltar ao perfil para desfazer. `expiresAt` nulo e o "ate reativar", que e
 * o comportamento que existia antes do prazo.</p>
 */
public class UserMute {

	private final Long id;
	private final String muterId;
	private final String mutedId;
	private final Instant createdAt;
	private final Instant expiresAt;

	private UserMute(Long id, String muterId, String mutedId, Instant createdAt, Instant expiresAt) {
		this.id = id;
		this.muterId = muterId;
		this.mutedId = mutedId;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public static UserMute create(String muterId, String mutedId, Instant now) {
		return new UserMute(null, muterId, mutedId, now, null);
	}

	/** Silenciamento com prazo: `duration` nulo (ou zero/negativo) vira "ate reativar". */
	public static UserMute create(String muterId, String mutedId, Instant now, Duration duration) {
		Instant expiresAt = duration == null || duration.isZero() || duration.isNegative()
			? null
			: now.plus(duration);
		return new UserMute(null, muterId, mutedId, now, expiresAt);
	}

	public static UserMute restore(Long id, String muterId, String mutedId, Instant createdAt) {
		return new UserMute(id, muterId, mutedId, createdAt, null);
	}

	public static UserMute restore(Long id, String muterId, String mutedId, Instant createdAt, Instant expiresAt) {
		return new UserMute(id, muterId, mutedId, createdAt, expiresAt);
	}

	/** Vale agora? Um silenciamento com prazo vencido nao esconde mais ninguem. */
	public boolean isActiveAt(Instant now) {
		return expiresAt == null || expiresAt.isAfter(now);
	}

	public Long id() { return id; }
	public String muterId() { return muterId; }
	public String mutedId() { return mutedId; }
	public Instant createdAt() { return createdAt; }
	public Instant expiresAt() { return expiresAt; }
}
