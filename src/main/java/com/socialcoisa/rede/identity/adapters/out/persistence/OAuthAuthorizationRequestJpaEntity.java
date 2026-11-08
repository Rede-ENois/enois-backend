package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.identity.domain.OAuthAuthorizationRequest;
import com.socialcoisa.rede.identity.domain.OAuthProvider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "oauth_authorization_requests",
		indexes = @Index(name = "idx_oauth_authorization_requests_expires", columnList = "expires_at"))
class OAuthAuthorizationRequestJpaEntity {

	@Id
	private String state;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private OAuthProvider provider;

	@Column(nullable = false)
	private String nonce;

	@Column(name = "user_id")
	private String userId;

	@Column(name = "site_domain", length = 253)
	private String siteDomain;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected OAuthAuthorizationRequestJpaEntity() {
	}

	static OAuthAuthorizationRequestJpaEntity fromDomain(OAuthAuthorizationRequest domain) {
		OAuthAuthorizationRequestJpaEntity entity = new OAuthAuthorizationRequestJpaEntity();
		entity.state = domain.state();
		entity.provider = domain.provider();
		entity.nonce = domain.nonce();
		entity.userId = domain.userId();
		entity.siteDomain = domain.siteDomain();
		entity.expiresAt = domain.expiresAt();
		entity.createdAt = domain.createdAt();
		return entity;
	}

	OAuthAuthorizationRequest toDomain() {
		return OAuthAuthorizationRequest.restore(state, provider, nonce, userId, siteDomain, expiresAt, createdAt);
	}
}