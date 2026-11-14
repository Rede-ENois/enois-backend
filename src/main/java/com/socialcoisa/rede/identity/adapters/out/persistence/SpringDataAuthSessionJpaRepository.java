package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataAuthSessionJpaRepository extends JpaRepository<AuthSessionJpaEntity, String> {

	Optional<AuthSessionJpaEntity> findByRefreshToken(String refreshToken);

	@Modifying
	@Query(value = "update auth_sessions set token = :tokenHash, refresh_token = :refreshHash where token = :rawToken",
		nativeQuery = true)
	int rehash(@Param("rawToken") String rawToken, @Param("tokenHash") String tokenHash,
			@Param("refreshHash") String refreshHash);

	/**
	 * Sessoes gravadas em claro por um pod antigo durante o rolling update. Um hash tem sempre 64
	 * caracteres (SHA-256 em hex); token cru do AuthService tem 43 (base64url de 32 bytes) e
	 * nenhum outro token guardado aqui chega a 64 hex — o tamanho sozinho separa as duas
	 * populacoes sem hidratar a tabela inteira em Java.
	 */
	@Query("select s from AuthSessionJpaEntity s where length(s.token) <> 64")
	List<AuthSessionJpaEntity> findLegacyRawSessions();

	@Modifying
	@Query("UPDATE AuthSessionJpaEntity s SET s.revokedAt = :now WHERE s.userId = :userId AND s.revokedAt IS NULL")
	void revokeAllByUser(@Param("userId") String userId, @Param("now") Instant now);

	@Modifying
	@Query("UPDATE AuthSessionJpaEntity s SET s.revokedAt = :now WHERE s.userId = :userId AND s.revokedAt IS NULL "
		+ "AND s.token <> :keepHash AND s.token <> :keepRaw")
	void revokeOthersByUser(@Param("userId") String userId, @Param("keepHash") String keepHash,
			@Param("keepRaw") String keepRaw, @Param("now") Instant now);

	/** Só um concorrente consegue revogar: o segundo update encontra revokedAt preenchido. */
	@Modifying
	@Query("UPDATE AuthSessionJpaEntity s SET s.revokedAt = :now WHERE s.token = :token AND s.revokedAt IS NULL")
	int revokeIfActive(@Param("token") String token, @Param("now") Instant now);

	@org.springframework.data.jpa.repository.Query(
		"SELECT s.ip FROM AuthSessionJpaEntity s WHERE s.userId = :userId AND s.ip IS NOT NULL "
			+ "ORDER BY s.createdAt DESC")
	List<String> findRecentIpsByUser(@Param("userId") String userId,
			org.springframework.data.domain.Pageable page);

	/**
	 * Sessoes que ja nao valem para nada: o refresh venceu (ou, sem refresh, o access venceu) antes
	 * do corte. Revogada ou nao, a linha nao autentica mais ninguem.
	 */
	@Query("SELECT s.token FROM AuthSessionJpaEntity s WHERE "
		+ "COALESCE(s.sanctionsExpiresAt, s.refreshExpiresAt, s.expiresAt) < :cutoff")
	List<String> findTokensExpiredBefore(@Param("cutoff") Instant cutoff,
			org.springframework.data.domain.Pageable page);

	@Modifying
	int deleteByTokenIn(List<String> tokens);

	/** Quantas sessoes estao elegiveis agora, sem apagar nada (diagnostico e teste). */
	@Query("SELECT count(s) FROM AuthSessionJpaEntity s WHERE "
		+ "COALESCE(s.sanctionsExpiresAt, s.refreshExpiresAt, s.expiresAt) < :cutoff")
	long countExpiredBefore(@Param("cutoff") Instant cutoff);
}
