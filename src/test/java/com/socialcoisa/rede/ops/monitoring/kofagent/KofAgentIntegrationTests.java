package com.socialcoisa.rede.ops.monitoring.kofagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MarkerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.ops.monitoring.MonitoringEvent;
import com.socialcoisa.rede.ops.monitoring.MonitoringEventListener;
import com.socialcoisa.rede.ops.monitoring.MonitoringService;
import com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTestSupport.FakeTransport;
import com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTransport.SendResult;
import com.socialcoisa.rede.stats.application.StatsService;

/**
 * End to end inside the app: a real HTTP 500 through the observability filter and the global
 * exception handler reaches the channel while Telegram hangs, without slowing the response down;
 * and log lines of every level reach the channel through the root appender.
 */
@SpringBootTest(properties = "rede.monitoring.file-dir=${java.io.tmpdir}/rede-monitoring-kofagent-test")
@AutoConfigureMockMvc
class KofAgentIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MonitoringService monitoring;

	/** One exception entry for the endpoint, with its optional repeat count. */
	private static final java.util.regex.Pattern FAILURES = java.util.regex.Pattern.compile(
		"HTTP 500 · Exceção(?: <b>×(\\d+)</b>)?[^\\n]*\\n<code>GET /stats/community</code>");
	/** The same kind in the digest, which counts every occurrence whether it was alerted or not. */
	private static final java.util.regex.Pattern DIGEST = java.util.regex.Pattern.compile(
		"<b>×(\\d+)</b> · HTTP 500 · Exceção · <code>GET /stats/community</code>");

	@MockitoBean
	private StatsService statsService;

	/** Sums the counts matched across every bulletin (a match without a count is one). */
	private static int sum(String text, java.util.regex.Pattern pattern) {
		var matcher = pattern.matcher(text);
		int total = 0;
		while (matcher.find()) {
			total += matcher.group(1) == null ? 1 : Integer.parseInt(matcher.group(1));
		}
		return total;
	}

	@TempDir
	Path spoolDir;

	@Test
	void http500IsDeliveredOnceWithoutBlockingTheRequestWhileTelegramHangs() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch entered = new CountDownLatch(1);
		FakeTransport hanging = new FakeTransport() {
			@Override
			public synchronized SendResult send(String text, boolean quiet, boolean html) {
				entered.countDown();
				try {
					release.await(15, TimeUnit.SECONDS);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				return super.send(text, quiet, html);
			}
		};
		KofAgentDispatcher dispatcher = new KofAgentDispatcher(KofAgentTestSupport.properties(spoolDir, 40, 60, 20,
			java.util.EnumSet.of(Severity.WARN, Severity.ERROR, Severity.FATAL), java.util.EnumSet.of(Severity.ERROR,
				Severity.FATAL), java.time.Duration.ofSeconds(1)),
			hanging, KofAgentTestSupport.spool(spoolDir), Clock.systemUTC());
		dispatcher.activate();
		dispatcher.startThread();
		monitoring.addListener(dispatcher);
		try {
			String marker = "stats-" + UUID.randomUUID();
			given(statsService.community()).willThrow(new IllegalStateException(marker));
			// The first bulletin (this warm-up) leaves after ~1 s; wait until its send is really stuck.
			monitoring.recordEvent("ERROR", "TEST", "warm-up " + marker, null);
			assertThat(entered.await(15, TimeUnit.SECONDS)).as("sender reached Telegram").isTrue();

			long started = System.nanoTime();
			for (int i = 0; i < 5; i++) {
				mockMvc.perform(get("/stats/community")).andExpect(status().isInternalServerError());
			}
			long perRequestMs = (System.nanoTime() - started) / 1_000_000 / 5;
			assertThat(perRequestMs).as("requests do not wait for Telegram").isLessThan(1000);
			assertThat(hanging.sent).isEmpty();

			release.countDown();
			long deadline = System.currentTimeMillis() + 20_000;
			while (sum(hanging.all(), FAILURES) < 1 && System.currentTimeMillis() < deadline) {
				Thread.sleep(50);
			}
			// Shutdown flushes the running period as a partial digest, with the count of every kind.
			monitoring.removeListener(dispatcher);
			dispatcher.shutdown();
			String all = hanging.all();
			assertThat(all).contains("warm-up", "HTTP 500", "<code>GET /stats/community</code>",
				"IllegalStateException", "IP <code>127.0.0.1</code>", "req <code>");
			// Five failures of one kind, however the flush windows cut them (the shared test context also
			// logs unrelated events in the background, so no global counter is asserted): the alert shows
			// the kind at least once, the digest counts all five, and each request's own 5xx event is
			// merged into its exception instead of becoming a second kind.
			assertThat(sum(all, FAILURES)).isBetween(1, 5);
			assertThat(sum(all, DIGEST)).isEqualTo(5);
			assertThat(java.util.regex.Pattern.compile("HTTP 500(?! · Exceção)[^\\n]*\\n<code>GET /stats/community</code>")
				.matcher(all).find()).as("no separate request-level 5xx entry").isFalse();
		}
		finally {
			release.countDown();
			monitoring.removeListener(dispatcher);
			dispatcher.shutdown();
		}
	}

	@Test
	void logLinesOfEveryLevelReachTheListenersThroughTheAppender() {
		List<MonitoringEvent> seen = new CopyOnWriteArrayList<>();
		MonitoringEventListener recorder = seen::add;
		monitoring.addListener(recorder);
		try {
			String marker = "appender-" + UUID.randomUUID();
			var log = LoggerFactory.getLogger("com.socialcoisa.rede.kofagent.Probe");
			log.info("info " + marker);
			log.warn("warn " + marker);
			log.error("error " + marker, new IllegalStateException("cause " + marker));
			log.error(MarkerFactory.getMarker("FATAL"), "fatal " + marker);
			LoggerFactory.getLogger("com.zaxxer.hikari.pool.HikariPool").warn("pool exhausted " + marker);
			LoggerFactory.getLogger("org.springframework.probe").info("framework info " + marker);

			List<String> levels = seen.stream().filter(event -> event.message().contains(marker))
				.map(event -> event.level() + ":" + event.message().replaceAll(".*: ", "").split(" ")[0])
				.toList();
			assertThat(levels).containsExactly("INFO:info", "WARN:warn", "ERROR:error", "FATAL:fatal", "WARN:pool");
			assertThat(seen.stream().filter(event -> event.message().contains("error " + marker)).findFirst()
				.orElseThrow().failure()).isInstanceOf(IllegalStateException.class);
		}
		finally {
			monitoring.removeListener(recorder);
		}
	}
}
