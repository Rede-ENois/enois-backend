package com.socialcoisa.rede.suggestions.domain;

import java.time.Instant;

/**
 * Sugestão de melhoria enviada por uma pessoa. O autor só existe aqui dentro: nada que o
 * identifique vai para a issue pública.
 */
public record Suggestion(String id, String userId, SuggestionTarget target, String title, String body,
		SuggestionStatus status, Instant createdAt, String reviewedBy, Instant reviewedAt, Integer githubIssueNumber,
		String githubIssueUrl) {

	public static Suggestion create(String id, String userId, SuggestionTarget target, String title, String body,
			Instant now) {
		return new Suggestion(id, userId, target, title, body, SuggestionStatus.PENDING, now, null, null, null, null);
	}
}
