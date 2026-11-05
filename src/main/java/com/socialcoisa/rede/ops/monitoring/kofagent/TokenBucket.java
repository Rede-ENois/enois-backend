package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.time.Instant;

/** Messages-per-minute limiter with a small burst. Only the dispatcher thread uses it. */
final class TokenBucket {

	private final double capacity;
	private final double perMilli;
	private double tokens;
	private Instant last;

	TokenBucket(int perMinute, int burst) {
		this.capacity = burst;
		this.perMilli = perMinute / 60_000.0;
		this.tokens = burst;
	}

	boolean tryAcquire(Instant now) {
		refill(now);
		if (tokens >= 1) {
			tokens -= 1;
			return true;
		}
		return false;
	}

	private void refill(Instant now) {
		if (last != null && now.isAfter(last)) {
			tokens = Math.min(capacity, tokens + (now.toEpochMilli() - last.toEpochMilli()) * perMilli);
		}
		if (last == null || now.isAfter(last)) {
			last = now;
		}
	}
}
