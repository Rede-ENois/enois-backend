package com.socialcoisa.rede.cosmetics.adapters.in.web;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A clock the seasonal tests move: it starts on the duel day (2026-10-31, 23:30 in São Paulo), when
 * the Saci and Halloween collections are on sale and both events are open. {@code AFTER_EVENTS} is
 * just past the end, close enough that a session opened at the start (60 min) still works. Shared
 * by the seasonal test classes so they reuse one Spring context.
 */
@TestConfiguration
class SeasonalTestClock {

	static final Instant DUEL_DAY = Instant.parse("2026-11-01T02:30:00Z");
	static final Instant AFTER_EVENTS = Instant.parse("2026-11-01T03:00:30Z");

	static final class MovableClock extends Clock {
		private volatile Instant now = DUEL_DAY;

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

		void set(Instant instant) {
			now = instant;
		}
	}

	@Bean
	@Primary
	MovableClock seasonalClock() {
		return new MovableClock();
	}
}
