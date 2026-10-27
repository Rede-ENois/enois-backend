package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.contributions.domain.GitHubIdentity;
import com.socialcoisa.rede.contributions.domain.GitHubSyncState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "github_identities")
class GitHubIdentityJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false, unique = true)
	private String userId;

	@Column(name = "github_user_id", nullable = false, unique = true)
	private String githubUserId;

	@Column(name = "github_login", nullable = false, length = 100)
	private String githubLogin;

	@Column(name = "linked_at", nullable = false)
	private Instant linkedAt;

	@Column(name = "last_synced_at")
	private Instant lastSyncedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "sync_state", length = 20)
	private GitHubSyncState syncState;

	@Column(name = "last_sync_error", length = 1000)
	private String lastSyncError;

	protected GitHubIdentityJpaEntity() {
	}

	static GitHubIdentityJpaEntity fromDomain(GitHubIdentity identity) {
		GitHubIdentityJpaEntity entity = new GitHubIdentityJpaEntity();
		entity.id = identity.id();
		entity.userId = identity.userId();
		entity.githubUserId = identity.githubUserId();
		entity.githubLogin = identity.githubLogin();
		entity.linkedAt = identity.linkedAt();
		entity.lastSyncedAt = identity.lastSyncedAt();
		entity.syncState = identity.syncState();
		entity.lastSyncError = identity.lastSyncError();
		return entity;
	}

	GitHubIdentity toDomain() {
		return new GitHubIdentity(id, userId, githubUserId, githubLogin, linkedAt, lastSyncedAt, syncState,
			lastSyncError);
	}

	String userId() {
		return userId;
	}

	String githubUserId() {
		return githubUserId;
	}
}
