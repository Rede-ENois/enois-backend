package com.socialcoisa.rede.ops.monitoring.kofagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import com.socialcoisa.rede.ops.monitoring.MonitoringEvent;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end check against the real Telegram channel. Never runs in CI: it needs
 * {@code KOF_AGENT_E2E=true} plus {@code KOF_AGENT_API_TOKEN} and {@code KOF_AGENT_CHAT_ID} exported
 * in the shell. Sends one test bulletin with every severity, an exception and a 5xx, tagged with the
 * environment from {@code KOF_AGENT_ENVIRONMENT} (default {@code e2e}) so it is never mistaken for a
 * production event.
 */
@EnabledIfEnvironmentVariable(named = "KOF_AGENT_E2E", matches = "true")
class KofAgentLiveChannelTests {

	@TempDir
	Path spool;

	@Test
	void deliversATestBulletinToTheRealChannel() throws Exception {
		String environment = System.getenv().getOrDefault("KOF_AGENT_ENVIRONMENT", "e2e");
		KofAgentProperties properties = new KofAgentProperties(true, System.getenv("KOF_AGENT_API_TOKEN"),
			System.getenv("KOF_AGENT_CHAT_ID"), null, environment, null, EnumSet.allOf(Severity.class), 40,
			Duration.ofSeconds(1), null, null, null, 0, spool.toString(), 0, null, null, null,
			Duration.ofSeconds(10));
		TelegramBotTransport transport = new TelegramBotTransport(properties, new ObjectMapper());
		KofAgentTransport.SendResult check = transport.verify();
		assertThat(check.outcome()).as(String.valueOf(check.detail())).isEqualTo(KofAgentTransport.Outcome.SENT);

		KofAgentDispatcher dispatcher = new KofAgentDispatcher(properties, transport,
			new AlertSpool(spool, 100, new ObjectMapper()), Clock.systemUTC());
		dispatcher.activate();
		dispatcher.startThread();
		String run = UUID.randomUUID().toString().substring(0, 8);
		for (Severity severity : Severity.values()) {
			dispatcher.onEvent(event(severity.name(), "LOG", "teste de integração " + run + ": nível "
				+ severity.name(), null, null));
		}
		dispatcher.onEvent(new MonitoringEvent("e2e-req-" + run, Instant.now(), "ERROR", "EXCEPTION",
			"IllegalStateException: falha simulada " + run, "e2e-" + run, "POST", "/e2e/teste", "/e2e/teste", 500,
			null, "203.0.113.10", "e2e-user", null, new IllegalStateException("falha simulada " + run)));
		dispatcher.onEvent(event("INFO", "LIFECYCLE", "app_started (teste " + run + ")", null, null));
		Thread.sleep(4000);
		dispatcher.shutdown();

		assertThat(dispatcher.sentMessages.get()).isGreaterThanOrEqualTo(1);
		assertThat(dispatcher.failedAttempts.get()).isZero();
	}

	private static MonitoringEvent event(String level, String type, String message, String ip, String user) {
		return new MonitoringEvent("e2e-" + UUID.randomUUID(), Instant.now(), level, type, message, null, null, null,
			null, null, null, ip, user, "com.socialcoisa.rede.e2e.Probe", null);
	}
}
