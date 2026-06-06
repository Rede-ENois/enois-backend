package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_credentials")
class UserCredentialJpaEntity {

	@Id
	@Column(name = "user_id")
	private String userId;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false, length = 500)
	private String passwordHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected UserCredentialJpaEntity() {
	}

	UserCredentialJpaEntity(String userId, String email, String passwordHash, Instant now) {
		this.userId = userId;
		this.email = email;
		this.passwordHash = passwordHash;
		this.createdAt = now;
		this.updatedAt = now;
	}

	String userId() {
		return userId;
	}

	String email() {
		return email;
	}

	String passwordHash() {
		return passwordHash;
	}
}
