package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataGitHubIdentityJpaRepository extends JpaRepository<GitHubIdentityJpaEntity, String> {

	Optional<GitHubIdentityJpaEntity> findByUserId(String userId);

	Optional<GitHubIdentityJpaEntity> findByGithubUserId(String githubUserId);

	void deleteByUserId(String userId);
}
