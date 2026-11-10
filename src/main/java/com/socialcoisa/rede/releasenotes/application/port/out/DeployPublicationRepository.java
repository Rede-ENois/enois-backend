package com.socialcoisa.rede.releasenotes.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.releasenotes.domain.ReleaseNoteItem;

/** Deploys que já publicaram novidades — a memória que torna o disparo do CI idempotente. */
public interface DeployPublicationRepository {

	/** O SHA do deploy mais recente que publicou, se algum publicou. */
	Optional<String> lastPublishedCommitSha();

	/** O deploy já publicou? SHA repetido é recusa, não republicação. */
	boolean existsByCommitSha(String commitSha);

	void save(String commitSha, List<ReleaseNoteItem> items, Instant publishedAt);
}