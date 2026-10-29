package com.socialcoisa.rede.moderation.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.moderation.domain.MediaSafetyCheck;

/** Ledger da checagem de conteúdo explícito: uma linha por mídia, sem repetir trabalho. */
public interface MediaSafetyCheckRepository {

	Optional<MediaSafetyCheck> findById(String mediaId);

	MediaSafetyCheck save(MediaSafetyCheck check);

	/**
	 * Ids de imagem ainda não checadas, das mais recentes para as mais antigas. Só o que ainda
	 * existe e não foi apagado — checar mídia já removida não protege ninguém.
	 */
	List<String> findUncheckedMediaIdsSince(Instant since, int limit);

	/** Mídias já barradas, para o painel acompanhar o que a automação derrubou. */
	List<MediaSafetyCheck> findViolationsSince(Instant since, int limit);
}