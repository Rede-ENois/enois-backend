package com.socialcoisa.rede.suggestions.domain;

import java.time.Instant;

/**
 * Sugestão de melhoria enviada por uma pessoa. O autor só existe aqui dentro: nada que o
 * identifique vai para a issue pública.
 *
 * {@code publishAttemptedAt} is set by the first approval and never cleared: once a publication was
 * attempted, an issue may exist on GitHub even if the call failed, so every retry looks it up first.
 */
public record Suggestion(String id, String userId, SuggestionTarget target, String title, String body,
		SuggestionStatus status, Instant createdAt, String reviewedBy, Instant reviewedAt, Integer githubIssueNumber,
		String githubIssueUrl, Instant publishAttemptedAt) {

	public static Suggestion create(String id, String userId, SuggestionTarget target, String title, String body,
			Instant now) {
		return new Suggestion(id, userId, target, title, body, SuggestionStatus.PENDING, now, null, null, null, null,
			null);
	}

	public Suggestion withIssue(int number, String url) {
		return new Suggestion(id, userId, target, title, body, status, createdAt, reviewedBy, reviewedAt, number, url,
			publishAttemptedAt);
	}
}
