package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.time.Duration;
import java.time.Instant;

/**
 * Stops hammering Telegram while it (or the network, or the bot config) is down.
 *
 * <p>CLOSED: every attempt allowed. After {@code threshold} consecutive failures: OPEN for
 * {@code openDuration}, no attempts. Then HALF_OPEN: one probe; success closes it, failure reopens it
 * with the open time doubled (capped at {@code maxOpen}). Single-threaded by design: only the
 * dispatcher thread touches it; {@link #state()} is read by metrics, hence volatile.</p>
 *
 * <p>Hand-rolled instead of Resilience4j because the dispatcher needs an injectable clock for tests
 * and a probe that is driven by its own loop, not by a decorated call.</p>
 */
final class CircuitBreaker {

	enum State {
		CLOSED, OPEN, HALF_OPEN
	}

	private final int threshold;
	private final Duration openDuration;
	private final Duration maxOpen;
	private volatile State state = State.CLOSED;
	private int consecutiveFailures;
	private int consecutiveOpens;
	private Instant openUntil = Instant.MIN;

	CircuitBreaker(int threshold, Duration openDuration, Duration maxOpen) {
		this.threshold = threshold;
		this.openDuration = openDuration;
		this.maxOpen = maxOpen;
	}

	boolean allows(Instant now) {
		if (state == State.OPEN && !now.isBefore(openUntil)) {
			state = State.HALF_OPEN;
		}
		return state != State.OPEN;
	}

	/** @return true when this success closed a previously open circuit */
	boolean onSuccess() {
		boolean recovered = state != State.CLOSED;
		state = State.CLOSED;
		consecutiveFailures = 0;
		consecutiveOpens = 0;
		return recovered;
	}

	/** @return true when this failure (re)opened the circuit */
	boolean onFailure(Instant now) {
		consecutiveFailures++;
		if (state == State.HALF_OPEN || consecutiveFailures >= threshold) {
			long factor = 1L << Math.min(consecutiveOpens, 16);
			Duration wait = openDuration.multipliedBy(factor);
			openUntil = now.plus(wait.compareTo(maxOpen) > 0 ? maxOpen : wait);
			consecutiveOpens++;
			state = State.OPEN;
			return true;
		}
		return false;
	}

	State state() {
		return state;
	}

	Instant openUntil() {
		return openUntil;
	}
}
