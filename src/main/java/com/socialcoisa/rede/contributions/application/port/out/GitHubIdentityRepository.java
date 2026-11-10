package com.socialcoisa.rede.contributions.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.contributions.domain.GitHubIdentity;
import com.socialcoisa.rede.contributions.domain.GitHubSyncState;

public interface GitHubIdentityRepository {

	/** Cria ou atualiza o vínculo do usuário (o ID imutável do GitHub não pode trocar de dono). */
	GitHubIdentity save(GitHubIdentity identity);

	Optional<GitHubIdentity> findByUserId(String userId);

	Optional<GitHubIdentity> findByGithubUserId(String githubUserId);

	List<GitHubIdentity> findAll();

	/**
	 * Os vínculos de vários IDs do GitHub de uma vez, em uma consulta. A sincronização de
	 * contribuições mapeia autor → conta Énóis para CADA PR/issue importado; um `findByGithubUserId`
	 * por objeto virava uma ida ao banco por linha da página.
	 */
	java.util.Map<String, GitHubIdentity> findByGithubUserIds(java.util.Collection<String> githubUserIds);

	void deleteByUserId(String userId);

	/** Marca o resultado da última sincronização daquela identidade (sem expor token). */
	void markSynced(String githubUserId, GitHubSyncState state, String error, Instant at);

	/**
	 * A mesma marcação para vários vínculos de uma vez, em um UPDATE. A sincronização marca todos
	 * os autores que viu na página; um `markSynced` por autor era um SELECT + um UPDATE por linha,
	 * em cima do mapeamento (que também já foi uma consulta por objeto).
	 */
	int markSyncedInBatch(java.util.Collection<String> githubUserIds, GitHubSyncState state, String error,
			Instant at);
}
