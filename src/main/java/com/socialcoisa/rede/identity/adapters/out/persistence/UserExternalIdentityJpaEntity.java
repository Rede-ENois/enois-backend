package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "user_external_identities", uniqueConstraints = {
		@UniqueConstraint(name = "uk_user_external_identity", columnNames = { "provider", "external_user_id" }) },
		indexes = @Index(name = "idx_user_external_identities_user", columnList = "user_id"))
class UserExternalIdentityJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(nullable = false, length = 32)
	private String provider;

	@Column(name = "external_user_id", nullable = false)
	private String externalUserId;

	@Column
	private String email;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected UserExternalIdentityJpaEntity() {
	}

	UserExternalIdentityJpaEntity(String userId, String provider, String externalUserId, String email, Instant now) {
		this.userId = userId;
		this.provider = provider;
		this.externalUserId = externalUserId;
		this.email = email;
		this.createdAt = now;
	}

	String userId() { return userId; }
	String provider() { return provider; }
	String externalUserId() { return externalUserId; }
}
