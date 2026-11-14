package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "auth_sessions", indexes = @Index(name = "idx_auth_sessions_user_expires", columnList = "user_id, expires_at"))
class AuthSessionJpaEntity {

	@Id
	private String token;

	@Column(name = "refresh_token", unique = true)
	private String refreshToken;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	/** Validade do refresh token (30 dias), independente do access token. */
	@Column(name = "refresh_expires_at")
	private Instant refreshExpiresAt;

	/** IP de onde a sessao nasceu (V115); nulo para sessoes antigas e fluxos sem o cabecalho. */
	@Column(name = "ip")
	private String ip;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "sanctions_only", nullable = false)
	private boolean sanctionsOnly;

	@Column(name = "sanctions_expires_at")
	private Instant sanctionsExpiresAt;

	/** A mesma decisão mais recente do gate, na leitura da sessão, sem consulta adicional. */
	@org.hibernate.annotations.Formula("(select s.type from user_sanctions s where s.user_id = user_id "
		+ "and s.status = 'ACTIVE' order by s.created_at desc limit 1)")
	private String activeSanctionType;

	boolean blockedAccount() {
		return "SUSPENDED".equals(activeSanctionType) || "BAN".equals(activeSanctionType)
			|| "PERMABAN".equals(activeSanctionType);
	}

	protected AuthSessionJpaEntity() {
	}

	AuthSessionJpaEntity(String token, String refreshToken, String userId, Instant createdAt, Instant expiresAt,
			Instant refreshExpiresAt) {
		this(token, refreshToken, userId, createdAt, expiresAt, refreshExpiresAt, null);
	}

	AuthSessionJpaEntity(String token, String refreshToken, String userId, Instant createdAt, Instant expiresAt,
			Instant refreshExpiresAt, String ip) {
		this.token = token;
		this.refreshToken = refreshToken;
		this.userId = userId;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.refreshExpiresAt = refreshExpiresAt;
		this.ip = ip;
	}

	String token() {
		return token;
	}

	String userId() {
		return userId;
	}

	boolean fullAccess() {
		return !sanctionsOnly;
	}

	void restrictToSanctions() {
		this.sanctionsOnly = true;
		this.sanctionsExpiresAt = expiresAt;
		// Pods antigos desconhecem o escopo: o acesso já nasce vencido, mesmo com diferença de relógios.
		this.expiresAt = Instant.parse("2000-01-01T00:00:00Z");
		// Sem token de refresh, sua data só mantém a retenção compatível com os pods antigos.
	}

	boolean activeForSanctionsAt(Instant now) {
		return sanctionsOnly ? revokedAt == null && sanctionsExpiresAt != null && sanctionsExpiresAt.isAfter(now)
			: activeAt(now);
	}

	String refreshToken() {
		return refreshToken;
	}

	boolean activeAt(Instant now) {
		return revokedAt == null && expiresAt.isAfter(now);
	}

	/** Validade do refresh: usa a janela propria (30 dias); sessoes antigas caem no fallback. */
	boolean activeRefreshAt(Instant now) {
		Instant limit = refreshExpiresAt != null ? refreshExpiresAt : expiresAt;
		return revokedAt == null && limit.isAfter(now);
	}

	void revoke(Instant revokedAt) {
		this.revokedAt = revokedAt;
	}
}
