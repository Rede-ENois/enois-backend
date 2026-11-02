package com.socialcoisa.rede.feed.application.port.out;

import java.time.Instant;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.UserId;

/** Per-person "you are caught up" marker: the newest feed moment the person has already seen. */
public interface FeedSeenMarkerRepository {

	Optional<Instant> lastSeenAt(UserId userId);

	/** Moves the marker to {@code seenUntil} only when it is newer than the stored one (never back). */
	void advance(UserId userId, Instant seenUntil, Instant now);
}
