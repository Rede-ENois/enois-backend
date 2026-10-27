package com.socialcoisa.rede.contributions.domain;

import java.time.Instant;

/** Repositório explicitamente elegível para gerar evidências. */
public record GitHubRepository(String repository, boolean enabled, Instant addedAt, Instant lastSyncedAt) {

	public GitHubRepository withLastSyncedAt(Instant at) {
		return new GitHubRepository(repository, enabled, addedAt, at);
	}

	public GitHubRepository withEnabled(boolean newEnabled) {
		return new GitHubRepository(repository, newEnabled, addedAt, lastSyncedAt);
	}
}
