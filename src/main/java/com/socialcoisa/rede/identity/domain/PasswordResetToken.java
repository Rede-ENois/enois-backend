package com.socialcoisa.rede.identity.domain;

import java.time.Instant;

public class PasswordResetToken {

	private final String token;
	private final String userId;
	private final Instant expiresAt;
	private final Instant usedAt;
	private final Instant createdAt;

	private PasswordResetToken(String token, String userId, Instant expiresAt, Instant usedAt, Instant createdAt) {
		this.token = token;
		this.userId = userId;
		this.expiresAt = expiresAt;
		this.usedAt = usedAt;
		this.createdAt = createdAt;
	}

	public static PasswordResetToken create(String token, String userId, Instant expiresAt, Instant now) {
		return new PasswordResetToken(token, userId, expiresAt, null, now);
	}

	public static PasswordResetToken restore(String token, String userId, Instant expiresAt, Instant usedAt,
			Instant createdAt) {
		return new PasswordResetToken(token, userId, expiresAt, usedAt, createdAt);
	}

	public PasswordResetToken use() {
		return new PasswordResetToken(token, userId, expiresAt, createdAt, createdAt);
	}

	public boolean isExpired(Instant now) {
		return now.isAfter(expiresAt);
	}

	public boolean isUsed() {
		return usedAt != null;
	}

	public String token() { return token; }
	public String userId() { return userId; }
	public Instant expiresAt() { return expiresAt; }
	public Instant usedAt() { return usedAt; }
	public Instant createdAt() { return createdAt; }
}
