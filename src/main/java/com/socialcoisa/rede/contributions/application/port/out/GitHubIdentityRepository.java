package com.socialcoisa.rede.contributions.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.contributions.domain.GitHubIdentity;
import com.socialcoisa.rede.contributions.domain.GitHubSyncState;

public interface GitHubIdentityRepository {

	/** Cria ou atualiza o vínculo do usuário (o ID imutável do GitHub não pode trocar de dono). */
	GitHubIdentity save(GitHubIdentity identity);

	Optional<GitHubIdentity> findByUserId(String userId);

	Optional<GitHubIdentity> findByGithubUserId(String githubUserId);

	List<GitHubIdentity> findAll();

	void deleteByUserId(String userId);

	/** Marca o resultado da última sincronização daquela identidade (sem expor token). */
	void markSynced(String githubUserId, GitHubSyncState state, String error, Instant at);
}
