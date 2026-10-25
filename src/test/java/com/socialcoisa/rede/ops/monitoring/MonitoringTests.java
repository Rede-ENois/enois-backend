package com.socialcoisa.rede.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/** O painel de monitorabilidade é só de admin e mostra eventos/métricas reais. */
@SpringBootTest(properties = "rede.monitoring.file-dir=${java.io.tmpdir}/rede-monitoring-test")
@AutoConfigureMockMvc
class MonitoringTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MonitoringService monitoring;

	@Test
	void adminSeesOverviewAndEventsWhileRegularUserGetsForbidden() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");
		var regular = new TestAuthHelper(mockMvc, objectMapper).authenticate("mon_regular");
		String marker = "monitor-test-" + UUID.randomUUID();
		monitoring.recordEvent("ERROR", "TEST", marker, Map.of("origem", "teste"));
		drain();

		mockMvc.perform(get("/admin/monitoring/overview").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.series").isArray())
			.andExpect(jsonPath("$.recent").isArray())
			.andExpect(jsonPath("$.jvm.heapMaxBytes").isNumber())
			// O pool vem do DataSource embrulhado (LazyConnectionDataSourceProxy); se o
			// desembrulho quebrar, resources.dbPool vira null e o painel mostra 0/0.
			.andExpect(jsonPath("$.resources.dbPool.max").isNumber())
			.andExpect(jsonPath("$.resources.dbPool.active").isNumber())
			.andExpect(jsonPath("$.resources.dbPool.waiting").isNumber());

		mockMvc.perform(get("/admin/monitoring/events")
				.header("X-Session-Token", admin.token())
				.param("q", marker)
				.param("level", "ERROR"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].message").value(marker))
			.andExpect(jsonPath("$[0].type").value("TEST"));

		mockMvc.perform(get("/admin/monitoring/overview").header("X-Session-Token", regular.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void eventsAcceptDateTimeRange() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");
		String marker = "monitor-range-" + UUID.randomUUID();
		monitoring.recordEvent("WARN", "TEST", marker, Map.of("origem", "teste"));
		drain();

		// Intervalo que inclui agora: o evento aparece.
		String de = java.time.Instant.now().minus(java.time.Duration.ofHours(1)).toString();
		String ate = java.time.Instant.now().plus(java.time.Duration.ofHours(1)).toString();
		mockMvc.perform(get("/admin/monitoring/events")
				.header("X-Session-Token", admin.token())
				.param("q", marker)
				.param("from", de)
				.param("to", ate))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].message").value(marker));

		// Intervalo no futuro: o evento não aparece.
		String futuro = java.time.Instant.now().plus(java.time.Duration.ofHours(2)).toString();
		mockMvc.perform(get("/admin/monitoring/events")
				.header("X-Session-Token", admin.token())
				.param("q", marker)
				.param("from", futuro))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void requestMetricsAndExceptionsFeedTheOverview() {
		long before = monitoring.overview().errors5xxLastMinute();
		// Varios registros para a sonda vencer o top-8: o singleton e compartilhado por toda a
		// suíte e o tráfego de outros testes empurra rotas com contagem baixa para fora.
		for (int i = 0; i < 12; i++) {
			monitoring.recordRequest("GET", "/monitor-probe", "/monitor-probe", 500, 42,
				"req-monitor-" + i, null, "127.0.0.1", null);
		}
		var after = monitoring.overview();

		assertThat(after.errors5xxLastMinute()).isGreaterThanOrEqualTo(before + 1);
		assertThat(after.series()).isNotEmpty();
		assertThat(after.topRoutes()).anyMatch(item -> item.label().equals("/monitor-probe"));
	}

	@Test
	void exceptionCarriesSanitizedStackTraceToThePanel() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");
		String marker = "monitor-stack-" + UUID.randomUUID();
		RuntimeException failure;
		try {
			throw new IllegalStateException(marker + " para contato@exemplo.com com token abc123");
		}
		catch (RuntimeException caught) {
			failure = caught;
		}
		monitoring.recordException(500, "INTERNAL_ERROR", marker, "GET", "/painel-prova",
			"req-stack-" + UUID.randomUUID(), null, failure);
		Thread.sleep(800);

		mockMvc.perform(get("/admin/monitoring/events")
				.header("X-Session-Token", admin.token())
				.param("q", marker))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].metadata").exists())
			.andExpect(jsonPath("$[0].metadata").value(org.hamcrest.Matchers.containsString(
				"IllegalStateException")))
			.andExpect(jsonPath("$[0].metadata").value(org.hamcrest.Matchers.not(
				org.hamcrest.Matchers.containsString("contato@exemplo.com"))))
			.andExpect(jsonPath("$[0].metadata").value(org.hamcrest.Matchers.not(
				org.hamcrest.Matchers.containsString("abc123"))));
	}

	private static void drain() throws InterruptedException {
		// O flush é assíncrono (500ms): espera o arquivo crescer, não só existir —
		// o arquivo pode já ter conteúdo de testes anteriores.
		java.nio.file.Path day = java.nio.file.Paths.get(
			System.getProperty("java.io.tmpdir"), "rede-monitoring-test",
			"events-" + java.time.LocalDate.now(java.time.ZoneOffset.UTC) + ".jsonl");
		long before;
		try {
			before = java.nio.file.Files.size(day);
		}
		catch (java.io.IOException ignored) {
			before = 0;
		}
		for (int i = 0; i < 80; i++) {
			try {
				if (java.nio.file.Files.size(day) > before) {
					return;
				}
			}
			catch (java.io.IOException ignored) {
			}
			Thread.sleep(100);
		}
	}

	@Test
	void everyRequestBecomesAnEventWithLevelByStatus() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");
		String successPath = "/prova-2xx-" + UUID.randomUUID();
		String errorPath = "/prova-4xx-" + UUID.randomUUID();
		// A pedido da dona (2026-10-25): o painel mostra TUDO — 200, 4xx e 5xx. O nível segue o
		// status (2xx INFO, 4xx WARN, 5xx ERROR), então filtrar por "só falhas" continua possível.
		monitoring.recordRequest("GET", successPath, successPath, 200, 8, "req-" + UUID.randomUUID(),
			null, "127.0.0.1", null);
		monitoring.recordRequest("GET", errorPath, errorPath, 429, 12, "req-" + UUID.randomUUID(),
			null, "127.0.0.1", null);
		drain();

		mockMvc.perform(get("/admin/monitoring/events")
				.header("X-Session-Token", admin.token())
				.param("q", successPath))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value(200))
			.andExpect(jsonPath("$[0].level").value("INFO"));

		mockMvc.perform(get("/admin/monitoring/events")
				.header("X-Session-Token", admin.token())
				.param("q", errorPath))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value(429))
			.andExpect(jsonPath("$[0].level").value("WARN"));
	}
}
