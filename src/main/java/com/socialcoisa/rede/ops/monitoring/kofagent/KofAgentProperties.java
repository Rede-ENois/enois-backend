package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the Telegram observability channel fed through the Kof Agent bot. Every value has an
 * environment variable in {@code application.properties}; see {@code docs/KOF_AGENT.md}.
 *
 * <p>The channel is active only when {@link #enabled} is true AND both the bot token and the chat id
 * are set, so a developer machine or a test run without them never sends anything.</p>
 *
 * @param apiToken bot token, only from {@code KOF_AGENT_API_TOKEN} (never in code or logs)
 * @param chatId numeric channel id ({@code -100...}), never the invite link
 * @param environment environment key (prd, qa, hml, sandbox, dev); shown on every message
 * @param levels log/exception severities collected at all (they appear in the hourly digest)
 * @param notifyLevels severities that raise an immediate alert the first time a kind of failure shows
 *        up; repeats of the same kind stay quiet and are counted in the digest
 * @param batchMaxEvents distinct entries (after grouping repeats) per alert message; the rest is counted
 * @param flushInterval how long new alerts are collected before going out together
 * @param incidentQuiet a kind of failure that has not happened for this long is announced again when it
 *        comes back
 * @param digestInterval cadence of the silent digest (aligned to the clock in {@link #zone}); a period
 *        with nothing at the digest minimum sends nothing
 * @param digestMinLevel lowest severity a period must contain (a failure kind, a 5xx or slow requests
 *        above the latency threshold) for the digest of that period to go out; periods with only
 *        lower-severity noise (4xx, slow requests on a fast API) send nothing
 * @param latency when the share of slow requests is high enough to raise an alert
 * @param queueCapacity bounded in-memory queue between the producers and the dispatcher
 * @param spoolDir where critical messages wait on disk until Telegram confirms them
 * @param slowRequest successful requests slower than this count as slow (digest and latency alert)
 */
@ConfigurationProperties(prefix = "rede.kof-agent")
public record KofAgentProperties(boolean enabled, String apiToken, String chatId, String apiBaseUrl,
		String environment, String serviceName, Set<Severity> levels, Set<Severity> notifyLevels, int batchMaxEvents,
		Duration flushInterval, Duration incidentQuiet, Duration digestInterval, Severity digestMinLevel,
		Latency latency, Retry retry,
		Rate rate, Circuit circuit, int queueCapacity, String spoolDir, int spoolMaxFiles, String zone,
		Duration requestTimeout, Duration slowRequest, Duration shutdownTimeout) {

	public KofAgentProperties {
		apiToken = apiToken == null ? "" : apiToken.trim();
		chatId = chatId == null ? "" : chatId.trim();
		apiBaseUrl = blank(apiBaseUrl) ? "https://api.telegram.org" : apiBaseUrl.trim().replaceAll("/+$", "");
		environment = blank(environment) ? "dev" : environment.trim();
		serviceName = blank(serviceName) ? "API Énóis" : serviceName.trim();
		levels = levels == null || levels.isEmpty() ? EnumSet.of(Severity.WARN, Severity.ERROR, Severity.FATAL)
			: EnumSet.copyOf(levels);
		notifyLevels = notifyLevels == null || notifyLevels.isEmpty() ? EnumSet.of(Severity.ERROR, Severity.FATAL)
			: EnumSet.copyOf(notifyLevels);
		batchMaxEvents = clamp(batchMaxEvents, 1, 500, 40);
		flushInterval = positive(flushInterval, Duration.ofSeconds(10));
		incidentQuiet = positive(incidentQuiet, Duration.ofMinutes(30));
		digestInterval = positive(digestInterval, Duration.ofHours(1));
		digestMinLevel = digestMinLevel == null ? Severity.WARN : digestMinLevel;
		latency = latency == null ? new Latency(null, 0, 0) : latency;
		retry = retry == null ? new Retry(0, null, null) : retry;
		rate = rate == null ? new Rate(0, 0) : rate;
		circuit = circuit == null ? new Circuit(0, null, null) : circuit;
		queueCapacity = clamp(queueCapacity, 100, 1_000_000, 10_000);
		spoolDir = blank(spoolDir) ? "/app/data/monitoring/kof-agent-spool" : spoolDir.trim();
		spoolMaxFiles = clamp(spoolMaxFiles, 10, 1_000_000, 10_000);
		zone = blank(zone) ? "America/Sao_Paulo" : zone.trim();
		requestTimeout = positive(requestTimeout, Duration.ofSeconds(10));
		slowRequest = positive(slowRequest, Duration.ofSeconds(2));
		shutdownTimeout = positive(shutdownTimeout, Duration.ofSeconds(5));
	}

	/** Active only with the switch on and both credentials present. */
	public boolean configured() {
		return enabled && !apiToken.isEmpty() && !chatId.isEmpty();
	}

	/** Never prints the token: properties objects end up in logs and actuator dumps. */
	@Override
	public String toString() {
		return "KofAgentProperties[enabled=" + enabled + ", apiToken=" + (apiToken.isEmpty() ? "<unset>" : "<redacted>")
			+ ", chatId=" + (chatId.isEmpty() ? "<unset>" : "<set>") + ", environment=" + environment + ", levels="
			+ levels + ", notifyLevels=" + notifyLevels + ", flushInterval=" + flushInterval + ", digestInterval="
			+ digestInterval + ", digestMinLevel=" + digestMinLevel + "]";
	}

	/**
	 * The API counts as slow when, inside one {@code window}, at least {@code minRequests} requests were
	 * served and at least {@code slowRatio} of them took longer than {@link #slowRequest}. Entering and
	 * leaving that state are the only two latency alerts.
	 */
	public record Latency(Duration window, int minRequests, double slowRatio) {
		public Latency {
			window = positive(window, Duration.ofMinutes(5));
			minRequests = clamp(minRequests, 1, 1_000_000, 100);
			slowRatio = slowRatio <= 0 || slowRatio > 1 ? 0.10 : slowRatio;
		}
	}

	/** Exponential backoff between attempts of the same batch. */
	public record Retry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
		public Retry {
			maxAttempts = clamp(maxAttempts, 1, 100, 6);
			initialBackoff = positive(initialBackoff, Duration.ofSeconds(1));
			maxBackoff = positive(maxBackoff, Duration.ofMinutes(5));
		}
	}

	/** Telegram accepts about 20 messages per minute in one group or channel; stay below it. */
	public record Rate(int messagesPerMinute, int burst) {
		public Rate {
			messagesPerMinute = clamp(messagesPerMinute, 1, 60, 18);
			burst = clamp(burst, 1, 20, 3);
		}
	}

	/** Consecutive failures that open the circuit, and how long it stays open (doubling up to the max). */
	public record Circuit(int failureThreshold, Duration openDuration, Duration maxOpenDuration) {
		public Circuit {
			failureThreshold = clamp(failureThreshold, 1, 100, 5);
			openDuration = positive(openDuration, Duration.ofSeconds(30));
			maxOpenDuration = positive(maxOpenDuration, Duration.ofMinutes(10));
		}
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static int clamp(int value, int min, int max, int fallback) {
		return value <= 0 ? fallback : Math.max(min, Math.min(max, value));
	}

	private static Duration positive(Duration value, Duration fallback) {
		return value == null || value.isNegative() || value.isZero() ? fallback : value;
	}
}
