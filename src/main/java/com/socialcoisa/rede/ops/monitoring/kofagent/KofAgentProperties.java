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
 * @param levels log/exception severities listed in the bulletin; lifecycle, health, audit and
 *        application-state events are always listed, plain successful requests never are (only counted)
 * @param batchMaxEvents distinct entries (after grouping repeats) per bulletin; the rest is counted
 * @param flushInterval the bulletin cadence: everything collected in this window goes out together
 * @param queueCapacity bounded in-memory queue between the producers and the dispatcher
 * @param spoolDir where critical messages wait on disk until Telegram confirms them
 * @param slowRequest successful requests slower than this are reported as WARN
 */
@ConfigurationProperties(prefix = "rede.kof-agent")
public record KofAgentProperties(boolean enabled, String apiToken, String chatId, String apiBaseUrl,
		String environment, String serviceName, Set<Severity> levels, int batchMaxEvents, Duration flushInterval,
		Retry retry,
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
		batchMaxEvents = clamp(batchMaxEvents, 1, 500, 40);
		flushInterval = positive(flushInterval, Duration.ofSeconds(10));
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
			+ levels + ", flushInterval=" + flushInterval + "]";
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
