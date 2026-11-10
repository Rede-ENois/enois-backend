package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
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
	public java.util.Map<String, GitHubIdentity> findByGithubUserIds(java.util.Collection<String> githubUserIds) {
		if (githubUserIds.isEmpty()) {
			return java.util.Map.of();
		}
		return repository.findByGithubUserIdIn(githubUserIds).stream()
			.map(GitHubIdentityJpaEntity::toDomain)
			.collect(java.util.stream.Collectors.toMap(GitHubIdentity::githubUserId, identity -> identity));
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

	// A sincronização roda fora de transação (cada save abre a sua); o UPDATE em lote precisa da
	// dele para o flush do Hibernate valer.
	@Override
	@org.springframework.transaction.annotation.Transactional
	public int markSyncedInBatch(java.util.Collection<String> githubUserIds, GitHubSyncState state, String error,
			Instant at) {
		if (githubUserIds.isEmpty()) {
			return 0;
		}
		return repository.markSyncedInBatch(githubUserIds, state, error, at);
	}
}
