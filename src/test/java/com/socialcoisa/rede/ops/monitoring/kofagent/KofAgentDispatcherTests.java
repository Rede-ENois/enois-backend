package com.socialcoisa.rede.ops.monitoring.kofagent;

import static com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTestSupport.event;
import static com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTestSupport.exception;
import static com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTestSupport.properties;
import static com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTestSupport.request;
import static com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTestSupport.spool;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.socialcoisa.rede.ops.monitoring.MonitoringEvent;
import com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTestSupport.FakeTransport;
import com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTestSupport.MutableClock;
import com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTransport.Outcome;
import com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTransport.SendResult;
import com.socialcoisa.rede.shared.web.ApiException;

/** Delivery rules of the Telegram channel, driven tick by tick with a fake transport and clock. */
class KofAgentDispatcherTests {

	private static final Duration WINDOW = Duration.ofSeconds(10);

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();
	private final FakeTransport transport = new FakeTransport();

	private KofAgentDispatcher dispatcher(KofAgentProperties properties) {
		KofAgentDispatcher dispatcher = new KofAgentDispatcher(properties, transport, spool(dir), clock);
		dispatcher.activate();
		return dispatcher;
	}

	private KofAgentDispatcher dispatcher() {
		return dispatcher(properties(dir, 40, 60, 20));
	}

	/** Lets one flush window pass and runs the dispatcher. */
	private void nextWindow(KofAgentDispatcher dispatcher) {
		dispatcher.tick();
		clock.advance(WINDOW);
		dispatcher.tick();
	}

	@Test
	void everythingFromOneWindowGoesOutAsOneBulletinOrderedBySeverity() {
		KofAgentDispatcher dispatcher = dispatcher(properties(dir, 40, 60, 20, EnumSet.allOf(Severity.class)));
		for (Severity severity : Severity.values()) {
			dispatcher.onEvent(event(severity.name(), "LOG", "probe " + severity.name().toLowerCase()));
		}
		dispatcher.tick();
		assertThat(transport.sent).as("nothing before the window closes").isEmpty();

		clock.advance(WINDOW);
		dispatcher.tick();

		assertThat(transport.sent).hasSize(1);
		String bulletin = transport.sent.getFirst();
		assertThat(bulletin).startsWith("📡 <b>API Énóis</b> · Produção · <code>PRD</code>")
			.contains("🔴 FATAL 1 · 🟠 ERROR 1 · 🟡 WARN 1 · 🟢 INFO 1 · ⚪ DEBUG 1 · 🔵 TRACE 1");
		assertThat(bulletin.indexOf("🔴 <b>FATAL</b>")).isLessThan(bulletin.indexOf("🟠 <b>ERROR</b>"));
		assertThat(bulletin.indexOf("🟠 <b>ERROR</b>")).isLessThan(bulletin.indexOf("🔵 <b>TRACE</b>"));
		assertThat(transport.silent).containsExactly(false);
	}

	@Test
	void defaultLevelsListOnlyWhatMattersAndCountTheRest() {
		KofAgentDispatcher dispatcher = dispatcher();
		dispatcher.onEvent(event("INFO", "LOG", "cache warmed"));
		dispatcher.onEvent(event("DEBUG", "LOG", "cache detail"));
		for (int i = 0; i < 30; i++) {
			dispatcher.onEvent(request(200, "req-ok-" + i, "/feed", 40));
		}
		dispatcher.onEvent(request(404, "req-404", "/nope", 3));
		dispatcher.onEvent(event("INFO", "LIFECYCLE", "app_started"));
		dispatcher.onEvent(event("WARN", "LOG", "panic_mode changed active=true"));
		nextWindow(dispatcher);

		String bulletin = transport.sent.getFirst();
		assertThat(bulletin).contains("Serviço iniciado", "Estado da aplicação")
			.doesNotContain("cache warmed", "cache detail", "/feed", "/nope", "Requisições");
	}

	@Test
	void quietWindowsSendNothing() {
		KofAgentDispatcher dispatcher = dispatcher();
		dispatcher.onEvent(request(200, "req-ok", "/feed", 40));
		nextWindow(dispatcher);
		clock.advance(WINDOW);
		dispatcher.tick();

		assertThat(transport.sent).isEmpty();
	}

	@Test
	void capturesExceptionsWithWhoWhereAndAShortStack() {
		KofAgentDispatcher dispatcher = dispatcher();
		IllegalStateException failure = new IllegalStateException("profile missing",
			new java.sql.SQLException("deadlock found"));

		dispatcher.onEvent(exception(500, "req-1", "/users", failure));
		nextWindow(dispatcher);

		String bulletin = transport.sent.getFirst();
		assertThat(bulletin).contains("🟠 <b>ERROR</b> 12:30:45 · HTTP 500 · Banco de dados",
			"<code>POST /users</code> · status 500", "IP <code>203.0.113.10</code> · user <code>user-7</code> · req <code>req-1</code>",
			"IllegalStateException em KofAgentDispatcherTests.java:",
			"<pre>java.lang.IllegalStateException: profile missing", "Caused by: java.sql.SQLException: deadlock found");
		assertThat(bulletin.length()).isLessThanOrEqualTo(AlertFormatter.MAX_MESSAGE);
	}

	@Test
	void every5xxIsListedAndTheOneAlreadyReportedByItsExceptionIsMerged() {
		KofAgentDispatcher dispatcher = dispatcher();
		for (int status : List.of(500, 502, 503, 504)) {
			dispatcher.onEvent(request(status, "req-plain-" + status, "/upstream" + status, 12));
		}
		dispatcher.onEvent(exception(500, "req-merged", "/users", new IllegalStateException("boom")));
		dispatcher.onEvent(request(500, "req-merged", "/users", 30));
		nextWindow(dispatcher);

		String bulletin = transport.sent.getFirst();
		assertThat(bulletin).contains("HTTP 500", "HTTP 502", "HTTP 503", "HTTP 504");
		assertThat(bulletin.split("POST /users", -1)).hasSize(2);
		assertThat(dispatcher.grouped.get()).as("the request-level 5xx of /users is merged").isEqualTo(1);
	}

	@Test
	void repeatsCollapseIntoOneEntryWithTheIpsAndUsersAndDuplicatesAreIgnored() {
		KofAgentDispatcher dispatcher = dispatcher();
		MonitoringEvent same = event("ERROR", "LOG", "payment provider timeout after 3000 ms");
		dispatcher.onEvent(same);
		dispatcher.onEvent(same);
		for (int i = 0; i < 6; i++) {
			dispatcher.onEvent(new MonitoringEvent(KofAgentTestSupport.id(), Instant.parse("2026-11-05T15:30:4" + i + "Z"),
				"ERROR", "LOG", "payment provider timeout after " + (3000 + i) + " ms", "req-" + i, null, null,
				null, null, null, "203.0.113." + i, "user-" + i, "com.socialcoisa.rede.coin.Pay", null));
		}
		nextWindow(dispatcher);

		assertThat(dispatcher.duplicates.get()).isEqualTo(1);
		String bulletin = transport.sent.getFirst();
		assertThat(bulletin).contains("<b>×7</b>", "IP <code>203.0.113.0</code>, <code>203.0.113.1</code>, "
			+ "<code>203.0.113.2</code> +3", "user <code>user-0</code>");
	}

	@Test
	void retriesWithExponentialBackoffThenOpensTheCircuitAndRecoversFromDisk() throws Exception {
		KofAgentDispatcher dispatcher = dispatcher();
		transport.fallback = SendResult.of(Outcome.RETRY, "503 Service Unavailable");
		dispatcher.onEvent(event("FATAL", "LOG", "database unreachable"));
		nextWindow(dispatcher);
		assertThat(transport.attempts).isEqualTo(1);
		assertThat(spoolFiles()).hasSize(1);

		dispatcher.tick();
		assertThat(transport.attempts).as("backoff holds the next attempt").isEqualTo(1);

		clock.advance(Duration.ofSeconds(5));
		dispatcher.tick();
		clock.advance(Duration.ofSeconds(10));
		dispatcher.tick();
		assertThat(transport.attempts).isEqualTo(3);
		assertThat(dispatcher.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

		clock.advance(Duration.ofSeconds(20));
		dispatcher.tick();
		assertThat(transport.attempts).as("no attempts while the circuit is open").isEqualTo(3);
		assertThat(spoolFiles()).as("the critical bulletin waits on disk").hasSize(1);

		transport.fallback = SendResult.sent();
		clock.advance(Duration.ofMinutes(2));
		for (int i = 0; i < 5; i++) {
			dispatcher.tick();
			clock.advance(Duration.ofSeconds(31));
		}

		assertThat(transport.sent).hasSize(1);
		assertThat(transport.sent.getFirst()).contains("database unreachable");
		assertThat(dispatcher.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(spoolFiles()).isEmpty();
	}

	@Test
	void backoffDoublesWithJitterAndIsCapped() {
		KofAgentDispatcher dispatcher = dispatcher();
		assertThat(dispatcher.backoff(1)).isBetween(Duration.ofMillis(800), Duration.ofMillis(1200));
		assertThat(dispatcher.backoff(2)).isBetween(Duration.ofMillis(1600), Duration.ofMillis(2400));
		assertThat(dispatcher.backoff(4)).isBetween(Duration.ofMillis(6400), Duration.ofMillis(9600));
		assertThat(dispatcher.backoff(20)).isLessThanOrEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void criticalBulletinsSurviveARestartAndAreReplayedWhenTheChannelIsBack() throws Exception {
		FakeTransport down = new FakeTransport();
		down.fallback = SendResult.of(Outcome.CONFIG_ERROR, "403 Forbidden: bot is not a member of the channel chat");
		KofAgentDispatcher first = new KofAgentDispatcher(properties(dir, 40, 60, 20), down, spool(dir), clock);
		first.activate();
		first.onEvent(event("ERROR", "LOG", "queue consumer crashed"));
		first.tick();
		clock.advance(WINDOW);
		first.tick();
		first.onEvent(event("WARN", "LOG", "slow consumer"));
		first.shutdown();
		assertThat(down.sent).isEmpty();
		assertThat(spoolFiles()).as("the WARN-only bulletin is not critical").hasSize(1);

		KofAgentDispatcher second = dispatcher();
		second.tick();

		assertThat(transport.sent).hasSize(1);
		assertThat(transport.sent.getFirst()).contains("queue consumer crashed");
		assertThat(second.replayed.get()).isEqualTo(1);
		assertThat(spoolFiles()).isEmpty();
	}

	@Test
	void respectsTheMessagesPerMinuteLimitAndTelegramRetryAfter() {
		KofAgentDispatcher dispatcher = dispatcher(properties(dir, 40, 2, 1));
		dispatcher.onEvent(event("ERROR", "LOG", "first window"));
		nextWindow(dispatcher);
		assertThat(transport.sent).hasSize(1);

		dispatcher.onEvent(event("ERROR", "LOG", "second window"));
		nextWindow(dispatcher);
		assertThat(transport.sent).as("bucket empty: 2/min means one every 30 s").hasSize(1);
		clock.advance(Duration.ofSeconds(21));
		dispatcher.tick();
		assertThat(transport.sent).hasSize(2);

		transport.then(new SendResult(Outcome.RATE_LIMITED, Duration.ofSeconds(40), "429 Too Many Requests"));
		dispatcher.onEvent(event("ERROR", "LOG", "third window"));
		clock.advance(Duration.ofSeconds(30));
		dispatcher.tick();
		assertThat(transport.sent).hasSize(2);
		clock.advance(Duration.ofSeconds(30));
		dispatcher.tick();
		assertThat(transport.sent).as("retry_after not over yet").hasSize(2);
		clock.advance(Duration.ofSeconds(15));
		dispatcher.tick();
		assertThat(transport.sent).hasSize(3);
		assertThat(dispatcher.circuitState()).as("429 is not an outage").isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void aBulletinBiggerThanTelegramAllowsIsSplitIntoNumberedParts() {
		KofAgentDispatcher dispatcher = dispatcher(properties(dir, 200, 60, 20,
			EnumSet.of(Severity.WARN, Severity.ERROR, Severity.FATAL)));
		for (int i = 0; i < 120; i++) {
			dispatcher.onEvent(event("WARN", "LOG", "distinct warning kind " + Integer.toString(i, 36).repeat(3)
				+ " with a longer explanation so the bulletin grows"));
		}
		nextWindow(dispatcher);
		for (int i = 0; i < 10; i++) {
			clock.advance(Duration.ofSeconds(1));
			dispatcher.tick();
		}

		assertThat(transport.sent.size()).isGreaterThan(1);
		assertThat(transport.sent).allSatisfy(text -> assertThat(text.length()).isLessThanOrEqualTo(
			AlertFormatter.MAX_MESSAGE));
		assertThat(transport.all()).contains("parte 1/", "parte 2/");
	}

	@Test
	void redactsSecretsAndPersonalDataInsideMessages() {
		KofAgentDispatcher dispatcher = dispatcher();
		String leak = "login failed password=hunter2 Authorization: Bearer abcdef123456 cookie=SESSION=xyz "
			+ "user@example.com card 4111 1111 1111 1111 cpf 123.456.789-09 from 198.51.100.77 "
			+ "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl -----BEGIN PRIVATE KEY-----MIIEv-----END PRIVATE KEY-----";
		dispatcher.onEvent(event("ERROR", "LOG", leak));
		dispatcher.onEvent(exception(500, "req-x", "/login", new IllegalArgumentException("bad secret=s3cr3t-value")));
		nextWindow(dispatcher);

		assertThat(transport.all()).doesNotContain("hunter2", "abcdef123456", "SESSION=xyz", "user@example.com",
			"4111 1111", "123.456.789-09", "198.51.100.77", "eyJhbGciOiJIUzI1NiJ9", "MIIEv", "s3cr3t-value");
		assertThat(transport.all()).contains("login failed", "password=***");
	}

	@Test
	void producersNeverBlockEvenWhenTelegramHangs() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		FakeTransport hanging = new FakeTransport() {
			@Override
			public synchronized SendResult send(String text, boolean quiet, boolean html) {
				try {
					release.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				return super.send(text, quiet, html);
			}
		};
		KofAgentDispatcher dispatcher = new KofAgentDispatcher(properties(dir, 40, 60, 20), hanging, spool(dir),
			Clock.systemUTC());
		dispatcher.activate();
		dispatcher.startThread();
		try {
			long started = System.nanoTime();
			for (int i = 0; i < 20_000; i++) {
				dispatcher.onEvent(event("ERROR", "LOG", "burst " + i));
			}
			long elapsedMs = (System.nanoTime() - started) / 1_000_000;
			assertThat(elapsedMs).as("20k events handed off while the sender is stuck").isLessThan(1500);
		}
		finally {
			release.countDown();
			dispatcher.shutdown();
		}
	}

	@Test
	void concurrentProducersLoseNothingUnaccounted() throws Exception {
		KofAgentDispatcher dispatcher = new KofAgentDispatcher(properties(dir, 40, 60, 20), transport, spool(dir),
			Clock.systemUTC());
		dispatcher.activate();
		dispatcher.startThread();
		int threads = 8;
		int perThread = 2000;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		for (int t = 0; t < threads; t++) {
			int thread = t;
			pool.submit(() -> {
				start.await();
				for (int i = 0; i < perThread; i++) {
					dispatcher.onEvent(event("WARN", "LOG", "worker " + thread + " step " + i));
				}
				return null;
			});
		}
		start.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
		long deadline = System.currentTimeMillis() + 10_000;
		while (dispatcher.accepted.get() + dispatcher.droppedTotal() < threads * perThread
				&& System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		dispatcher.shutdown();

		assertThat(dispatcher.accepted.get() + dispatcher.droppedTotal()).isEqualTo(threads * perThread);
		assertThat(transport.all()).contains("<b>×");
	}

	@Test
	void aFullQueueKeepsCriticalEventsAndReportsTheRestOnTheNextBulletin() {
		KofAgentProperties base = properties(dir, 40, 60, 20);
		KofAgentProperties tiny = new KofAgentProperties(true, base.apiToken(), base.chatId(), null, "qa", null,
			base.levels(), base.notifyLevels(), 40, base.flushInterval(), base.incidentQuiet(), base.digestInterval(),
			base.latency(), base.retry(), base.rate(), base.circuit(), 100, base.spoolDir(),
			1000, null, null, null, null);
		KofAgentDispatcher dispatcher = dispatcher(tiny);
		for (int i = 0; i < 150; i++) {
			dispatcher.onEvent(event("WARN", "LOG", "filler " + i));
		}
		dispatcher.onEvent(event("FATAL", "LOG", "disk full"));

		assertThat(dispatcher.droppedQueueFull(Severity.WARN)).isEqualTo(50);
		assertThat(dispatcher.droppedQueueFull(Severity.FATAL)).isZero();
		nextWindow(dispatcher);
		assertThat(transport.all()).contains("disk full", "<code>QA</code>", "canal: 50 eventos descartados");
	}

	@Test
	void aFailureThatKeepsHappeningAlertsOnceAndAgainOnlyAfterGoingQuiet() {
		KofAgentDispatcher dispatcher = dispatcher();
		dispatcher.onEvent(exception(500, "req-first", "/feed/posts/{postId}/reactions", deadlock()));
		nextWindow(dispatcher);
		for (int i = 0; i < 20; i++) {
			dispatcher.onEvent(exception(500, "req-repeat-" + i, "/feed/posts/{postId}/reactions", deadlock()));
			nextWindow(dispatcher);
		}

		assertThat(transport.sent).as("one alert for the kind; repeats are only counted").hasSize(1);
		assertThat(dispatcher.suppressed.get()).isEqualTo(20);

		clock.advance(Duration.ofMinutes(31));
		dispatcher.onEvent(exception(500, "req-back", "/feed/posts/{postId}/reactions", deadlock()));
		nextWindow(dispatcher);

		assertThat(transport.sent).as("back after the quiet period: alerted again").hasSize(2);
	}

	@Test
	void slowRequestsNeverAlertOneByOneAndShowUpInTheHourlyDigest() {
		KofAgentDispatcher dispatcher = dispatcher();
		for (int i = 0; i < 30; i++) {
			dispatcher.onEvent(request(200, "req-slow-" + i, "/feed/posts/{postId}/reactions", 2600 + i));
		}
		dispatcher.onEvent(request(200, "req-slowest", "/feed/posts/{postId}/reactions", 66_296));
		for (int i = 0; i < 60; i++) {
			dispatcher.onEvent(request(200, "req-fast-" + i, "/feed", 40));
		}
		nextWindow(dispatcher);
		assertThat(transport.sent).as("a slow request is not an alert").isEmpty();

		clock.advance(Duration.ofHours(1));
		dispatcher.tick();

		assertThat(transport.sent).hasSize(1);
		assertThat(transport.silent).containsExactly(true);
		assertThat(transport.sent.getFirst()).startsWith("📊 <b>API Énóis</b> · Produção · <code>PRD</code> · resumo")
			.contains("2026-11-05 12:00 a 13:00", "Requisições 91 · 4xx 0 · 5xx 0 · lentas 31 (34,1%)",
				"<b>Rotas lentas</b> (acima de 2,0 s)",
				"<code>POST /feed/posts/{postId}/reactions</code> ×31 · mediana 2,6 s · máx 66,3 s")
			.doesNotContain("<code>POST /feed</code>");
	}

	@Test
	void periodsWithNothingToReportSendNoDigest() {
		KofAgentDispatcher dispatcher = dispatcher();
		for (int i = 0; i < 50; i++) {
			dispatcher.onEvent(request(i % 5 == 0 ? 404 : 200, "req-" + i, "/feed", 40));
		}
		nextWindow(dispatcher);
		clock.advance(Duration.ofHours(2));
		dispatcher.tick();

		assertThat(transport.sent).isEmpty();
	}

	@Test
	void latencyAlertsOnlyWhenTheApiBecomesSlowAndWhenItRecovers() {
		KofAgentDispatcher dispatcher = dispatcher();
		fiveMinutesOfTraffic(dispatcher, 30);
		assertThat(transport.sent).hasSize(1);
		assertThat(transport.sent.getFirst()).contains("🟡 <b>WARN</b> 12:05:00 · API lenta · Desempenho",
			"20,0% das requisições acima de 2,0 s entre 12:00 e 12:05 (30 de 150)",
			"Mais lentas: <code>POST /feed/posts</code> ×30");
		assertThat(transport.silent).containsExactly(false);

		fiveMinutesOfTraffic(dispatcher, 40);
		assertThat(transport.sent).as("still slow: no new alert").hasSize(1);

		fiveMinutesOfTraffic(dispatcher, 3);
		assertThat(transport.sent).hasSize(2);
		assertThat(transport.sent.get(1)).contains("🟢 <b>INFO</b> 12:15:00 · Latência normalizada",
			"2,0% das requisições acima de 2,0 s entre 12:10 e 12:15 (3 de 150)");
		assertThat(transport.silent).containsExactly(false, true);
	}

	@Test
	void aDeliberate5xxGoesToTheDigestWhileAWrappedFailureStillAlerts() {
		KofAgentDispatcher dispatcher = dispatcher();
		dispatcher.onEvent(exception(503, "req-calls", "/groups/{groupId}/calls",
			new ApiException(503, "CALLS_NOT_CONFIGURED", "calls are not configured in this environment")));
		dispatcher.onEvent(request(503, "req-calls", "/groups/{groupId}/calls", 12));
		nextWindow(dispatcher);
		assertThat(transport.sent).as("answered as designed: no alert").isEmpty();

		dispatcher.onEvent(exception(503, "req-moderation", "/storage/{*key}", new ApiException(503,
			"IMAGE_MODERATION_UNAVAILABLE", "moderation unavailable", new java.net.SocketTimeoutException("timed out"))));
		nextWindow(dispatcher);
		assertThat(transport.sent).hasSize(1);
		assertThat(transport.sent.getFirst()).contains("🟠 <b>ERROR</b>", "<code>POST /storage/{*key}</code>");

		clock.advance(Duration.ofHours(1));
		dispatcher.tick();
		assertThat(transport.sent).hasSize(2);
		assertThat(transport.sent.get(1)).contains("<b>Falhas</b> (2 tipos)",
			"🟠 <b>×1</b> · HTTP 503 · Integração externa · <code>POST /storage/{*key}</code>",
			"🟡 <b>×1</b> · HTTP 503 · Exceção · <code>POST /groups/{groupId}/calls</code>",
			"ApiException: calls are not configured in this environment");
	}

	/** One latency window: 150 requests, {@code slow} of them over the threshold, then the window closes. */
	private void fiveMinutesOfTraffic(KofAgentDispatcher dispatcher, int slow) {
		for (int i = 0; i < 150; i++) {
			boolean isSlow = i < slow;
			dispatcher.onEvent(request(200, "req-" + KofAgentTestSupport.id(), isSlow ? "/feed/posts" : "/feed",
				isSlow ? 4000 : 50));
		}
		dispatcher.tick();
		clock.advance(Duration.ofMinutes(5));
		dispatcher.tick();
	}

	/** Always thrown from this line, so every occurrence has the same origin and fingerprint. */
	private static IllegalStateException deadlock() {
		return new IllegalStateException("deadlock found when trying to get lock");
	}

	private List<Path> spoolFiles() throws Exception {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(path -> path.toString().endsWith(".json")).toList();
		}
	}
}
