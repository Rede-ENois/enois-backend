package com.socialcoisa.rede.moderation.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.moderation.domain.LinkSafetyCheck;

/** Ledger da checagem de destino de link: uma linha por URL, sem visitar o mesmo endereço duas vezes. */
public interface LinkSafetyCheckRepository {

	Optional<LinkSafetyCheck> findByUrl(String url);

	LinkSafetyCheck save(LinkSafetyCheck check);

	/** Destinos já barrados, para o painel acompanhar o que a automação derrubou. */
	List<LinkSafetyCheck> findViolationsSince(Instant since, int limit);
}
