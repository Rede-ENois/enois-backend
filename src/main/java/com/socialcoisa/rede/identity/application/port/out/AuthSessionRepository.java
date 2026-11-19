package com.socialcoisa.rede.identity.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuthSessionRepository {

	void save(String token, String refreshToken, String userId, Instant createdAt, Instant expiresAt,
			Instant refreshExpiresAt, String ip);

	void saveSanctionsOnly(String token, String userId, Instant createdAt, Instant expiresAt, String ip);

	Optional<String> findActiveSanctionsUserId(String token, Instant now);

	boolean isSanctionsOnly(String token, Instant now);

	Optional<String> findActiveUserId(String token, Instant now);

	Optional<String> findActiveUserIdByRefreshToken(String refreshToken, Instant now);

	void revoke(String token, Instant revokedAt);

	/** Revoga de forma atômica; false quando outra requisição concorrente já revogou (replay). */
	boolean revokeByRefreshToken(String refreshToken, Instant revokedAt);

	void revokeAllByUser(String userId, Instant revokedAt);

	/** Revokes every session of the user except the one presenting {@code keepToken}. */
	void revokeOthersByUser(String userId, String keepToken, Instant revokedAt);

	/** IPs recentes de onde o usuario entrou (V115), mais recentes primeiro. Nulos filtrados fora. */
	List<String> findRecentIpsByUser(String userId, int limit);

	/** Remove um lote de sessoes cujo refresh (ou access, sem refresh) venceu antes do corte. */
	int deleteExpiredBefore(Instant cutoff, int batchSize);

	/** Quantas sessoes estao elegiveis agora, sem apagar nada (diagnostico e teste). */
	long countExpiredBefore(Instant cutoff);
}
