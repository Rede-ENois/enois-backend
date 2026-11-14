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

	/**
	 * Quantos tokens vivos (não usados, não vencidos) uma conta tem — o freio da solicitação pelo
	 * participante: sem ele, um loop de cliente abre linhas de token até o fim do TTL para a mesma
	 * conta. A consulta é por {@code owner_user_id} e não por {@code created_by}, porque é o dono
	 * que o token cria — quem pediu por ele é o mesmo dono na solicitação pelo participante.
	 */
	long countAliveForOwner(String ownerUserId, Instant now);

	record ProvisionTokenRecord(String tokenHash, String displayName, String ownerUserId, String profiles,
			Instant expiresAt, Instant usedAt) {

		public boolean usable(Instant now) {
			return usedAt == null && expiresAt.isAfter(now);
		}
	}
}
