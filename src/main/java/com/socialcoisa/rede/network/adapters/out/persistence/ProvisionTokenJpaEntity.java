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

	// Quem pediu, quando o token nasce de uma inscrição aprovada (V324). Nulo no caminho
	// administrativo antigo, que só tem a conta dona.
	@Column(name = "owner_name", length = 120)
	private String ownerName;

	@Column(name = "owner_email", length = 200)
	private String ownerEmail;

	protected ProvisionTokenJpaEntity() {
	}

	ProvisionTokenJpaEntity(String tokenHash, String displayName, String ownerUserId, String profiles,
			Instant expiresAt, String createdBy, Instant createdAt, String ownerName, String ownerEmail) {
		this.tokenHash = tokenHash;
		this.displayName = displayName;
		this.ownerUserId = ownerUserId;
		this.profiles = profiles;
		this.expiresAt = expiresAt;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.ownerName = ownerName;
		this.ownerEmail = ownerEmail;
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

	String ownerName() {
		return ownerName;
	}

	String ownerEmail() {
		return ownerEmail;
	}
}
