package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialcoisa.rede.contributions.domain.GitHubSyncState;

interface SpringDataGitHubIdentityJpaRepository extends JpaRepository<GitHubIdentityJpaEntity, String> {

	Optional<GitHubIdentityJpaEntity> findByUserId(String userId);

	Optional<GitHubIdentityJpaEntity> findByGithubUserId(String githubUserId);

	java.util.List<GitHubIdentityJpaEntity> findByGithubUserIdIn(java.util.Collection<String> githubUserIds);

	void deleteByUserId(String userId);

	@org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
	@org.springframework.data.jpa.repository.Query("UPDATE GitHubIdentityJpaEntity i SET i.lastSyncedAt = :at, "
		+ "i.syncState = :state, i.lastSyncError = :error WHERE i.githubUserId IN :githubUserIds")
	int markSyncedInBatch(
		@org.springframework.data.repository.query.Param("githubUserIds") java.util.Collection<String> githubUserIds,
		@org.springframework.data.repository.query.Param("state") GitHubSyncState state,
		@org.springframework.data.repository.query.Param("error") String error,
		@org.springframework.data.repository.query.Param("at") Instant at);
}
