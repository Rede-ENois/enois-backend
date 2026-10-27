package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.contributions.domain.GitHubRepository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "github_repositories")
class GitHubRepositoryJpaEntity {

	@Id
	@Column(length = 200)
	private String repository;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "added_at", nullable = false)
	private Instant addedAt;

	@Column(name = "last_synced_at")
	private Instant lastSyncedAt;

	protected GitHubRepositoryJpaEntity() {
	}

	static GitHubRepositoryJpaEntity fromDomain(GitHubRepository repo) {
		GitHubRepositoryJpaEntity entity = new GitHubRepositoryJpaEntity();
		entity.repository = repo.repository();
		entity.enabled = repo.enabled();
		entity.addedAt = repo.addedAt();
		entity.lastSyncedAt = repo.lastSyncedAt();
		return entity;
	}

	GitHubRepository toDomain() {
		return new GitHubRepository(repository, enabled, addedAt, lastSyncedAt);
	}
}
