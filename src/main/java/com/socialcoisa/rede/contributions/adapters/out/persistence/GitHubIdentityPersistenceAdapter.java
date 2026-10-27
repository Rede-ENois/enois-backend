package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.contributions.application.port.out.GitHubIdentityRepository;
import com.socialcoisa.rede.contributions.domain.GitHubIdentity;
import com.socialcoisa.rede.contributions.domain.GitHubSyncState;

@Repository
class GitHubIdentityPersistenceAdapter implements GitHubIdentityRepository {

	private final SpringDataGitHubIdentityJpaRepository repository;

	GitHubIdentityPersistenceAdapter(SpringDataGitHubIdentityJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public GitHubIdentity save(GitHubIdentity identity) {
		return repository.save(GitHubIdentityJpaEntity.fromDomain(identity)).toDomain();
	}

	@Override
	public Optional<GitHubIdentity> findByUserId(String userId) {
		return repository.findByUserId(userId).map(GitHubIdentityJpaEntity::toDomain);
	}

	@Override
	public Optional<GitHubIdentity> findByGithubUserId(String githubUserId) {
		return repository.findByGithubUserId(githubUserId).map(GitHubIdentityJpaEntity::toDomain);
	}

	@Override
	public java.util.List<GitHubIdentity> findAll() {
		return repository.findAll().stream().map(GitHubIdentityJpaEntity::toDomain).toList();
	}

	@Override
	public void deleteByUserId(String userId) {
		repository.deleteByUserId(userId);
	}

	@Override
	public void markSynced(String githubUserId, GitHubSyncState state, String error, Instant at) {
		repository.findByGithubUserId(githubUserId).ifPresent(entity -> repository
			.save(GitHubIdentityJpaEntity.fromDomain(entity.toDomain().withSync(state, error, at))));
	}
}
