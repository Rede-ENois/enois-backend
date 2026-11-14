package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "network_provision_tokens")
class ProvisionTokenJpaEntity {

	@Id
	@Column(name = "token_hash", length = 64)
	private String tokenHash;

	@Column(name = "display_name", nullable = false, length = 120)
	private String displayName;

	@Column(name = "owner_user_id", nullable = false, length = 40)
	private String ownerUserId;

	@Column(nullable = false, length = 255)
	private String profiles;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	@Column(name = "created_by", nullable = false, length = 40)
	private String createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ProvisionTokenJpaEntity() {
	}

	ProvisionTokenJpaEntity(String tokenHash, String displayName, String ownerUserId, String profiles,
			Instant expiresAt, String createdBy, Instant createdAt) {
		this.tokenHash = tokenHash;
		this.displayName = displayName;
		this.ownerUserId = ownerUserId;
		this.profiles = profiles;
		this.expiresAt = expiresAt;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
	}

	String tokenHash() {
		return tokenHash;
	}

	String displayName() {
		return displayName;
	}

	String ownerUserId() {
		return ownerUserId;
	}

	String profiles() {
		return profiles;
	}

	Instant expiresAt() {
		return expiresAt;
	}

	Instant usedAt() {
		return usedAt;
	}
}
