package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.Optional;

/** Tokens de provisionamento de uso único (ADR-003). Só o hash é persistido. */
public interface ProvisionTokenRepository {

	void insert(String tokenHash, String displayName, String ownerUserId, String profiles, Instant expiresAt,
			String createdBy, Instant now);

	Optional<ProvisionTokenRecord> findByHash(String tokenHash);

	/** Marca como usado; devolve false quando outra requisição já consumiu (corrida). */
	boolean markUsed(String tokenHash, Instant now);

	record ProvisionTokenRecord(String tokenHash, String displayName, String ownerUserId, String profiles,
			Instant expiresAt, Instant usedAt) {

		public boolean usable(Instant now) {
			return usedAt == null && expiresAt.isAfter(now);
		}
	}
}
