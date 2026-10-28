package com.socialcoisa.rede.cosmetics.application.port.out;

import java.time.Instant;
import java.util.List;

/** Free ownership of native shop items (event rewards); one row per person and item. */
public interface CosmeticGrantRepository {

	List<String> itemIdsOf(String userId);

	boolean exists(String userId, String itemId);

	/** Idempotent: granting an item the person already has is a no-op. */
	void grant(String userId, String itemId, String source, Instant now);

	/** Grants the item to everyone who made {@code choice} in the event; idempotent. */
	void grantToChoosers(String eventId, String choice, String itemId, String source, Instant now);
}
