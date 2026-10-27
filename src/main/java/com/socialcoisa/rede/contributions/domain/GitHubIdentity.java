package com.socialcoisa.rede.contributions.domain;

import java.time.Instant;

/** Vínculo entre uma conta Énóis e uma conta GitHub; o ID numérico é a chave imutável. */
public record GitHubIdentity(String id, String userId, String githubUserId, String githubLogin,
		Instant linkedAt, Instant lastSyncedAt, GitHubSyncState syncState, String lastSyncError) {

	public GitHubIdentity withSync(GitHubSyncState state, String error, Instant at) {
		return new GitHubIdentity(id, userId, githubUserId, githubLogin, linkedAt, at, state, error);
	}
}
