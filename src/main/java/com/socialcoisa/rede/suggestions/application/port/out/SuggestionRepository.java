package com.socialcoisa.rede.suggestions.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.suggestions.domain.Suggestion;
import com.socialcoisa.rede.suggestions.domain.SuggestionStatus;

public interface SuggestionRepository {

	Suggestion save(Suggestion suggestion);

	/**
	 * Inserts a new suggestion only while its author has fewer than {@code max} created since
	 * {@code since}. Count and insert run under a lock on the author, so concurrent submissions of the
	 * same person line up and the limit holds exactly. False when the limit is already reached.
	 */
	boolean insertWithinLimit(Suggestion suggestion, Instant since, int max);

	Optional<Suggestion> findById(String id);

	List<Suggestion> findFiltered(SuggestionStatus status, int limit);

	List<Suggestion> findByUserId(String userId, int limit);

	/**
	 * Move o status de forma atômica ({@code from} -> {@code to}); falso quando outra requisição já
	 * decidiu. É o que impede duas aprovações simultâneas de abrir duas issues.
	 */
	boolean transition(String id, SuggestionStatus from, SuggestionStatus to, String reviewedBy, Instant at);

	/**
	 * Claims a pending suggestion for publication (PENDING -> PUBLISHED) and records the first attempt
	 * time, which is kept even if the suggestion later goes back to the queue. False when another
	 * request already decided.
	 */
	boolean claimForPublishing(String id, String reviewedBy, Instant at);
}
