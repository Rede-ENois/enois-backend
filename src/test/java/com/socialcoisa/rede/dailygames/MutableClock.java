package com.socialcoisa.rede.dailygames;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock that a scene moves forward by hand, so play time is chosen, not measured. */
final class MutableClock extends Clock {

	private Instant now;

	MutableClock(Instant start) {
		this.now = start;
	}

	void advance(Duration step) {
		now = now.plus(step);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return now;
	}
}
