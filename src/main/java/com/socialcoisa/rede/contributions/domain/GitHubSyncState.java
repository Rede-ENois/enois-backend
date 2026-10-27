package com.socialcoisa.rede.contributions.domain;

/** Estado da última sincronização de contribuições daquela identidade GitHub. */
public enum GitHubSyncState {
	/** Última sincronização concluída sem erro. */
	OK,
	/** Nunca sincronizou ou a última tentativa falhou; os dados podem estar desatualizados. */
	STALE,
	/** A última tentativa falhou; o motivo fica em {@code lastSyncError}. */
	ERROR
}
