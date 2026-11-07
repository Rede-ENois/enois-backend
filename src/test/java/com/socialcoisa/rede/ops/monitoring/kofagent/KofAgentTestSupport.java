package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.socialcoisa.rede.ops.monitoring.MonitoringEvent;

import tools.jackson.databind.ObjectMapper;

/** Fakes shared by the Kof Agent tests: a scripted transport, a movable clock, event builders. */
final class KofAgentTestSupport {

	private KofAgentTestSupport() {
	}

	/** Production defaults: WARN and above collected, ERROR and FATAL alerted. */
	static KofAgentProperties properties(Path spool, int batchMax, int perMinute, int burst) {
		return properties(spool, batchMax, perMinute, burst, EnumSet.of(Severity.WARN, Severity.ERROR, Severity.FATAL),
			EnumSet.of(Severity.ERROR, Severity.FATAL), Duration.ofSeconds(10));
	}

	/** Every level in {@code levels} is collected and alerted. */
	static KofAgentProperties properties(Path spool, int batchMax, int perMinute, int burst, EnumSet<Severity> levels) {
		return properties(spool, batchMax, perMinute, burst, levels, levels, Duration.ofSeconds(10));
	}

	static KofAgentProperties properties(Path spool, int batchMax, int perMinute, int burst, EnumSet<Severity> levels,
			EnumSet<Severity> notify, Duration flushInterval) {
		return properties(spool, batchMax, perMinute, burst, levels, notify, flushInterval,
			new KofAgentProperties.Latency(Duration.ofMinutes(5), 100, 0.10));
	}

	static KofAgentProperties properties(Path spool, int batchMax, int perMinute, int burst, EnumSet<Severity> levels,
			EnumSet<Severity> notify, Duration flushInterval, KofAgentProperties.Latency latency) {
		return new KofAgentProperties(true, "123456:test-token-not-real", "-1001234567890", null, "prd", null,
			levels, notify, batchMax, flushInterval, Duration.ofMinutes(30), Duration.ofHours(1), latency,
			new KofAgentProperties.Retry(3, Duration.ofSeconds(1), Duration.ofSeconds(30)),
			new KofAgentProperties.Rate(perMinute, burst), new KofAgentProperties.Circuit(3, Duration.ofSeconds(30),
				Duration.ofMinutes(5)), 100_000, spool.toString(), 1000, "America/Sao_Paulo", Duration.ofSeconds(2),
			Duration.ofSeconds(2), Duration.ofMillis(300));
	}

	static AlertSpool spool(Path dir) {
		return new AlertSpool(dir, 1000, new ObjectMapper());
	}

	static MonitoringEvent event(String level, String type, String message) {
		return new MonitoringEvent(id(), Instant.parse("2026-11-05T15:30:45Z"), level, type, message, null, null, null,
			null, null, null, null, null, "com.socialcoisa.rede.feed.FeedService", null);
	}

	static MonitoringEvent request(int status, String requestId, String route, long durationMs) {
		return new MonitoringEvent(id(), Instant.parse("2026-11-05T15:30:45Z"), status >= 500 ? "ERROR"
			: status >= 400 ? "WARN" : "INFO", "REQUEST", "POST " + route + " -> " + status, requestId, "POST",
			route.replace("{userId}", "42"), route, status, durationMs, "203.0.113.10", "user-7", null, null);
	}

	static MonitoringEvent exception(int status, String requestId, String route, Throwable failure) {
		return new MonitoringEvent(id(), Instant.parse("2026-11-05T15:30:45Z"), status >= 500 ? "ERROR" : "WARN",
			"EXCEPTION", failure.getClass().getSimpleName() + ": " + failure.getMessage(), requestId, "POST", route,
			route, status, null, "203.0.113.10", "user-7", null, failure);
	}

	static String id() {
		return "evt_" + UUID.randomUUID().toString().replace("-", "");
	}

	/** Clock the test moves by hand. */
	static final class MutableClock extends Clock {
		private Instant now = Instant.parse("2026-11-05T15:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
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

	/** Records every attempt; answers from a script, then with the default outcome. */
	static class FakeTransport implements KofAgentTransport {
		final List<String> sent = new CopyOnWriteArrayList<>();
		final List<Boolean> silent = new CopyOnWriteArrayList<>();
		volatile int attempts;
		private final Deque<SendResult> script = new ArrayDeque<>();
		volatile SendResult fallback = SendResult.sent();

		synchronized FakeTransport then(SendResult result) {
			script.addLast(result);
			return this;
		}

		@Override
		public synchronized SendResult send(String text, boolean quiet, boolean html) {
			attempts++;
			SendResult result = script.isEmpty() ? fallback : script.pollFirst();
			if (result.outcome() == Outcome.SENT) {
				sent.add(text);
				silent.add(quiet);
			}
			return result;
		}

		@Override
		public SendResult verify() {
			return new SendResult(Outcome.SENT, null, "bot=@test chat_type=channel");
		}

		String all() {
			return String.join("\n#####\n", sent);
		}
	}
}
