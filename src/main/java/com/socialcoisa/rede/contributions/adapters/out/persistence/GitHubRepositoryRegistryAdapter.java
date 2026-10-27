package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.contributions.application.port.out.GitHubRepositoryRegistry;
import com.socialcoisa.rede.contributions.domain.GitHubRepository;

@Repository
class GitHubRepositoryRegistryAdapter implements GitHubRepositoryRegistry {

	private final SpringDataGitHubRepositoryJpaRepository repository;

	GitHubRepositoryRegistryAdapter(SpringDataGitHubRepositoryJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public List<GitHubRepository> findAll() {
		return repository.findAll().stream().map(GitHubRepositoryJpaEntity::toDomain).toList();
	}

	@Override
	public List<GitHubRepository> findEnabled() {
		return repository.findByEnabledTrueOrderByRepositoryAsc().stream()
			.map(GitHubRepositoryJpaEntity::toDomain).toList();
	}

	@Override
	public GitHubRepository save(GitHubRepository repo) {
		return repository.save(GitHubRepositoryJpaEntity.fromDomain(repo)).toDomain();
	}
}
