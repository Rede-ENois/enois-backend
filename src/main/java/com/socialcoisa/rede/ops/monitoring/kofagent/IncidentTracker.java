package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers which kinds of failure (alert fingerprints) the channel already announced, so a failure
 * that keeps happening raises one alert instead of one per flush window.
 *
 * <p>A kind is announced again only after it went quiet for {@code quiet}: the same deadlock every
 * few seconds is one alert, the same deadlock again the next morning is a new one. Repeats are not
 * lost, the dispatcher counts them in the digest. The memory is bounded (least recently seen kinds
 * are forgotten first) and lives in the dispatcher thread only, so it needs no locking; a restart
 * forgets it, which means the first occurrence after a deploy is announced again.</p>
 */
final class IncidentTracker {

	private static final int MAX_TRACKED = 5000;

	private final Duration quiet;
	private final Map<String, Instant> lastSeen = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
			return size() > MAX_TRACKED;
		}
	};

	IncidentTracker(Duration quiet) {
		this.quiet = quiet;
	}

	/**
	 * Records one occurrence.
	 *
	 * @return true when the kind is new or came back after the quiet period, i.e. it deserves an alert
	 */
	boolean announce(String fingerprint, Instant now) {
		Instant previous = lastSeen.put(fingerprint, now);
		return previous == null || !now.isBefore(previous.plus(quiet));
	}
}
