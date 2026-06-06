package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.identity.domain.PasswordResetToken;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "password_reset_tokens", indexes = @Index(name = "idx_password_reset_tokens_expires", columnList = "expires_at"))
class PasswordResetTokenJpaEntity {

	@Id
	private String token;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected PasswordResetTokenJpaEntity() {
	}

	static PasswordResetTokenJpaEntity fromDomain(PasswordResetToken domain) {
		PasswordResetTokenJpaEntity e = new PasswordResetTokenJpaEntity();
		e.token = domain.token();
		e.userId = domain.userId();
		e.expiresAt = domain.expiresAt();
		e.usedAt = domain.usedAt();
		e.createdAt = domain.createdAt();
		return e;
	}

	PasswordResetToken toDomain() {
		return PasswordResetToken.restore(token, userId, expiresAt, usedAt, createdAt);
	}

	void markUsed(Instant now) {
		this.usedAt = now;
	}
}
