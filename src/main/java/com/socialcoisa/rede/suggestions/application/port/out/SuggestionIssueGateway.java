package com.socialcoisa.rede.suggestions.application.port.out;

import java.time.Instant;
import java.util.Optional;

import com.socialcoisa.rede.suggestions.domain.SuggestionTarget;

/**
 * Opens the public issue of an approved suggestion. The credentials and target repositories belong to
 * the suggestions module alone: nothing here borrows another module's token.
 */
public interface SuggestionIssueGateway {

	/** Whether {@code target} can publish now, and if not, which piece of configuration is missing. */
	Availability availability(SuggestionTarget target);

	/**
	 * Publishes the issue idempotently. {@code key} is a stable id carried by the issue; when
	 * {@code previousAttempt} is set, an issue with that key created since then is returned instead of
	 * opening a second one. Empty when GitHub did not answer (the caller puts the suggestion back in the
	 * queue and a later approval retries safely).
	 */
	Optional<IssueRef> publish(SuggestionTarget target, String key, String title, String body,
			Instant previousAttempt);

	enum Availability {
		READY,
		/** No dedicated suggestions token: every target is off. */
		TOKEN_MISSING,
		/** No (or malformed) repository for this target in this environment. */
		REPOSITORY_MISSING
	}

	record IssueRef(int number, String url) {
	}
}
