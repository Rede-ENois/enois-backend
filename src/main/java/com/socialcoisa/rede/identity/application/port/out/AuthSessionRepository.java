package com.socialcoisa.rede.identity.application.port.out;

import java.time.Instant;
import java.util.Optional;

public interface AuthSessionRepository {

	void save(String token, String refreshToken, String userId, Instant createdAt, Instant expiresAt);

	Optional<String> findActiveUserId(String token, Instant now);

	Optional<String> findActiveUserIdByRefreshToken(String refreshToken, Instant now);

	void revoke(String token, Instant revokedAt);

	void revokeByRefreshToken(String refreshToken, Instant revokedAt);

	void revokeAllByUser(String userId, Instant revokedAt);
}
