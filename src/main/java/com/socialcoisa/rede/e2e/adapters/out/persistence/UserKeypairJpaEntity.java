package com.socialcoisa.rede.e2e.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.e2e.domain.UserKeypair;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_e2e_keypairs")
class UserKeypairJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "public_key", nullable = false, columnDefinition = "text")
	private String publicKey;

	@Column(name = "encrypted_private_key", nullable = false, columnDefinition = "text")
	private String encryptedPrivateKey;

	@Column(nullable = false, length = 32)
	private String algorithm;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected UserKeypairJpaEntity() {
	}

	UserKeypairJpaEntity(UserKeypair keypair) {
		this.id = keypair.id();
		this.userId = keypair.userId();
		this.publicKey = keypair.publicKey();
		this.encryptedPrivateKey = keypair.encryptedPrivateKey();
		this.algorithm = keypair.algorithm();
		this.active = keypair.active();
		this.createdAt = keypair.createdAt();
	}

	UserKeypair toDomain() {
		return new UserKeypair(id, userId, publicKey, encryptedPrivateKey, algorithm, active, createdAt);
	}
}
