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

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected AuthSessionJpaEntity() {
	}

	AuthSessionJpaEntity(String token, String userId, Instant createdAt, Instant expiresAt) {
		this.token = token;
		this.userId = userId;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	String userId() {
		return userId;
	}

	boolean activeAt(Instant now) {
		return revokedAt == null && expiresAt.isAfter(now);
	}

	void revoke(Instant revokedAt) {
		this.revokedAt = revokedAt;
	}
}
