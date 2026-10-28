package com.socialcoisa.rede.cosmetics.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Entries of the free cosmetic events; one per event and person. */
public interface CosmeticEventEntryRepository {

	Optional<String> choiceOf(String eventId, String userId);

	Map<String, Long> countByChoice(String eventId);

	/** Which of {@code eventIds} the person has an entry in. */
	List<String> eventIdsOf(String userId, Collection<String> eventIds);

	/** Throws {@link org.springframework.dao.DuplicateKeyException} when the person already has an entry. */
	void insert(String eventId, String userId, String choice, Instant now);
}
