package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates one digest period (an hour by default): request counts, every collected failure grouped
 * by fingerprint (announced or not), and the slow requests per route.
 *
 * <p>Owned by the dispatcher thread. Memory is bounded: at most {@code MAX_KINDS} failure kinds and
 * {@code MAX_ROUTES} routes are kept by name (the rest only counted), and each route keeps a bounded
 * sample of durations for the median.</p>
 */
final class DigestCollector {

	private static final int MAX_KINDS = 1000;
	private static final int MAX_ROUTES = 500;
	private static final int DURATION_SAMPLE = 256;

	private Instant from;
	private long requests;
	private long requests4xx;
	private long requests5xx;
	private long slow;
	private long otherKinds;
	private Severity worst;
	private final Map<String, Failure> failures = new LinkedHashMap<>();
	private final Map<String, SlowRoute> slowRoutes = new LinkedHashMap<>();

	DigestCollector(Instant from) {
		this.from = from;
	}

	void request(Integer status) {
		requests++;
		if (status != null && status >= 500) {
			requests5xx++;
			note(Severity.ERROR);
		}
		else if (status != null && status >= 400) {
			requests4xx++;
		}
	}

	void slow(String route, long durationMs) {
		slow++;
		String key = route == null ? "?" : route;
		SlowRoute entry = slowRoutes.get(key);
		if (entry == null && slowRoutes.size() < MAX_ROUTES) {
			entry = new SlowRoute(key);
			slowRoutes.put(key, entry);
		}
		if (entry != null) {
			entry.add(durationMs);
		}
	}

	void slowEntered() {
		// The latency alert itself is a WARN; a period that only got slow counts as WARN news.
		note(Severity.WARN);
	}

	void failure(Alert alert) {
		Failure entry = failures.get(alert.fingerprint());
		if (entry != null) {
			entry.count++;
			entry.lastAt = alert.at();
		}
		else if (failures.size() < MAX_KINDS) {
			failures.put(alert.fingerprint(), new Failure(alert));
		}
		else {
			otherKinds++;
		}
		note(alert.severity());
	}

	/** Whether the period reached the digest minimum and so deserves its digest message. */
	boolean reaches(Severity digestMinLevel) {
		return worst != null && worst.atLeast(digestMinLevel);
	}

	/** Snapshot of the period ending at {@code to}; the collector starts the next period from there. */
	Digest close(Instant to) {
		List<FailureLine> lines = failures.values().stream()
			.sorted(Comparator.comparing((Failure failure) -> failure.alert.severity()).reversed()
				.thenComparing(Comparator.comparingLong((Failure failure) -> failure.count).reversed()))
			.map(failure -> new FailureLine(failure.alert, failure.count, failure.lastAt))
			.toList();
		List<SlowLine> routes = slowRoutes.values().stream()
			.sorted(Comparator.comparingLong((SlowRoute route) -> route.count).reversed())
			.map(SlowRoute::line)
			.toList();
		Digest digest = new Digest(from, to, requests, requests4xx, requests5xx, slow, lines, otherKinds, routes);
		from = to;
		requests = 0;
		requests4xx = 0;
		requests5xx = 0;
		slow = 0;
		otherKinds = 0;
		worst = null;
		failures.clear();
		slowRoutes.clear();
		return digest;
	}

	private void note(Severity severity) {
		if (worst == null || severity.atLeast(worst)) {
			worst = severity;
		}
	}

	private static final class Failure {
		private final Alert alert;
		private long count = 1;
		private Instant lastAt;

		private Failure(Alert alert) {
			this.alert = alert;
			this.lastAt = alert.at();
		}
	}

	private static final class SlowRoute {
		private final String route;
		private long count;
		private long maxMs;
		private final long[] sample = new long[DURATION_SAMPLE];

		private SlowRoute(String route) {
			this.route = route;
		}

		private void add(long durationMs) {
			// Keeps the first durations of the period; enough for a median that tells 2 s from 20 s.
			if (count < DURATION_SAMPLE) {
				sample[(int) count] = durationMs;
			}
			count++;
			maxMs = Math.max(maxMs, durationMs);
		}

		private SlowLine line() {
			long[] sorted = Arrays.copyOf(sample, (int) Math.min(count, DURATION_SAMPLE));
			Arrays.sort(sorted);
			return new SlowLine(route, count, sorted.length == 0 ? 0 : sorted[sorted.length / 2], maxMs);
		}
	}

	/** One failure kind in the period, with how often it happened. */
	record FailureLine(Alert alert, long count, Instant lastAt) {
	}

	/** Slow requests of one route in the period. */
	record SlowLine(String route, long count, long medianMs, long maxMs) {
	}

	/** Everything the digest message shows. */
	record Digest(Instant from, Instant to, long requests, long requests4xx, long requests5xx, long slow,
			List<FailureLine> failures, long otherKinds, List<SlowLine> slowRoutes) {
	}
}
