package com.socialcoisa.rede.suggestions.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.suggestions.domain.Suggestion;
import com.socialcoisa.rede.suggestions.domain.SuggestionStatus;

public interface SuggestionRepository {

	Suggestion save(Suggestion suggestion);

	Optional<Suggestion> findById(String id);

	List<Suggestion> findFiltered(SuggestionStatus status, int limit);

	List<Suggestion> findByUserId(String userId, int limit);

	long countByUserSince(String userId, Instant since);

	/**
	 * Move o status de forma atômica ({@code from} -> {@code to}); falso quando outra requisição já
	 * decidiu. É o que impede duas aprovações simultâneas de abrir duas issues.
	 */
	boolean transition(String id, SuggestionStatus from, SuggestionStatus to, String reviewedBy, Instant at);
}
