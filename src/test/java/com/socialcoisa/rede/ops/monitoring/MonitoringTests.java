package com.socialcoisa.rede.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

	/**
	 * O painel mostra o TOP 8 de rotas por contagem (`limit(8)`) e o registro de métricas é
	 * COMPARTILHADO pela suíte inteira: outras classes pedem rotas e ocupam as oito vagas. A sonda
	 * precisa passar na frente da 8ª colocada, senão o teste depende de quantas rotas o resto da
	 * suíte pediu — foi assim que a rota nova do supply empurrou a sonda para fora da lista. O que
	 * se prova aqui é que a requisição registrada APARECE no painel, não que ela vença um ranking
	 * de tráfego alheio.
	 */
	@Test
	void requestMetricsAndExceptionsFeedTheOverview() {
		long before = monitoring.overview().errors5xxLastMinute();
		// O corte da 8ª rota é medido ANTES da sonda: é contra ele que ela precisa passar.
		long corte = corteDaOitava(monitoring.overview().topRoutes());
		monitoring.recordRequest("GET", "/monitor-probe", "/monitor-probe", 500, 42, "req-monitor",
			null, "127.0.0.1", null);
		// O 500 acima prova o contador de erro; o resto vai com 200 para não poluir o log de eventos.
		for (long i = 0; i <= corte; i++) {
			monitoring.recordRequest("GET", "/monitor-probe", "/monitor-probe", 200, 12, "req-monitor-" + i,
				null, "127.0.0.1", null);
		}
		var after = monitoring.overview();

		assertThat(after.errors5xxLastMinute()).isGreaterThanOrEqualTo(before + 1);
		assertThat(after.series()).isNotEmpty();
		assertThat(after.topRoutes()).anyMatch(item -> item.label().equals("/monitor-probe"));
	}

	/** Contagem da 8ª rota do painel (o corte que a sonda precisa passar). */
	private long corteDaOitava(List<MonitoringService.CountView> topRoutes) {
		return topRoutes.size() < 8 ? 0 : topRoutes.get(7).count();
	}
}
