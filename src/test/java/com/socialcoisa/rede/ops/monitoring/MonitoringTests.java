package com.socialcoisa.rede.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/** O painel de monitorabilidade é só de admin e mostra eventos/métricas reais. */
@SpringBootTest
@AutoConfigureMockMvc
class MonitoringTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MonitoringService monitoring;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void adminSeesOverviewAndEventsWhileRegularUserGetsForbidden() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");
		var regular = new TestAuthHelper(mockMvc, objectMapper).authenticate("mon_regular");
		String marker = "monitor-test-" + UUID.randomUUID();
		try {
			monitoring.recordEvent("ERROR", "TEST", marker, Map.of("origem", "teste"));
			Thread.sleep(800);

			mockMvc.perform(get("/admin/monitoring/overview").header("X-Session-Token", admin.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.series").isArray())
				.andExpect(jsonPath("$.recent").isArray())
				.andExpect(jsonPath("$.jvm.heapMaxBytes").isNumber());

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
		finally {
			jdbc.update("delete from system_events where message = ?", marker);
		}
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
}
