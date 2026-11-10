package com.socialcoisa.rede.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/** Contas da comunidade no painel: total, série diária, crescimento e projeção. */
@SpringBootTest(properties = "rede.monitoring.file-dir=${java.io.tmpdir}/rede-monitoring-test")
@AutoConfigureMockMvc
class UserMetricsTests {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private UserMetricsService userMetrics;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	@Test
	void usersPanelShowsDailySeriesGrowthAndProjections() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");

		UserMetricsService.UsersView view = userMetrics.users();

		assertThat(view.total()).isPositive();
		assertThat(view.series()).hasSize(30);
		assertThat(view.projections()).hasSize(3);
		assertThat(view.projections()).extracting(UserMetricsService.Projection::days)
			.containsExactly(7, 30, 90);
		// Ninguém some do banco ao apagar a conta (o cadastro é anonimizado), então a projeção
		// nunca fica abaixo do total atual.
		assertThat(view.projections()).allSatisfy(projecao ->
			assertThat(projecao.total()).isGreaterThanOrEqualTo(view.total()));

		List<LocalDate> dias = new ArrayList<>();
		view.series().forEach(ponto -> dias.add(LocalDate.parse(ponto.date())));
		assertThat(dias).isSorted();
		assertThat(dias.get(dias.size() - 1)).isEqualTo(LocalDate.now(ZONE));
		// O total acumulado sobe ao longo da janela: a série é cumulativa, não só o diário.
		assertThat(view.series().get(0).total()).isLessThanOrEqualTo(view.series().get(29).total());

		mockMvc.perform(get("/admin/monitoring/users").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").isNumber())
			.andExpect(jsonPath("$.newLast7Days").isNumber())
			.andExpect(jsonPath("$.growthTodayPercent").isNumber())
			.andExpect(jsonPath("$.growth7DaysPercent").isNumber())
			.andExpect(jsonPath("$.averageDailyNew7Days").isNumber())
			.andExpect(jsonPath("$.series.length()").value(30))
			.andExpect(jsonPath("$.projections[0].days").value(7));
	}

	@Test
	void usersPanelCountsDistinctActivesInLast24Hours() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");

		// Dois usuários com heartbeat recente e um com last_seen_at velho, fora da janela de 24h.
		// O heartbeat cria a linha via upsert (touch), então o POST garante que a linha existe
		// antes do update direto — igual ao que o app faz em toda tela autenticada.
		long agora = System.currentTimeMillis();
		var ativos = List.of(
			new TestAuthHelper(mockMvc, objectMapper).authenticate("metrics_active_a"),
			new TestAuthHelper(mockMvc, objectMapper).authenticate("metrics_active_b"));
		var fora = new TestAuthHelper(mockMvc, objectMapper).authenticate("metrics_stale");
		int i = 0;
		for (var sessao : ativos) {
			mockMvc.perform(post("/me/presence/heartbeat").header("X-Session-Token", sessao.token()))
				.andExpect(status().isNoContent());
			jdbc.update("update user_presence set last_seen_at = ? where user_id = ?",
				new java.sql.Timestamp(agora - i * 60_000L), sessao.userId());
			i++;
		}
		mockMvc.perform(post("/me/presence/heartbeat").header("X-Session-Token", fora.token()))
			.andExpect(status().isNoContent());
		jdbc.update("update user_presence set last_seen_at = ? where user_id = ?",
			new java.sql.Timestamp(agora - 25L * 3_600_000L), fora.userId());

		mockMvc.perform(get("/admin/monitoring/users").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activeLast24Hours")
				.value(org.hamcrest.Matchers.greaterThanOrEqualTo(ativos.size())));

		// A janela é de relógio, não de fuso: 24h antes de agora, não "desde a meia idade".
		var view = userMetrics.users();
		Long contado = jdbc.queryForObject(
			"select count(*) from user_presence where last_seen_at >= ?", Long.class,
			java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofHours(24))));
		assertThat(view.activeLast24Hours()).isEqualTo(contado);
		assertThat(view.activeLast24Hours()).isLessThanOrEqualTo(view.total());
	}

	@Test
	void usersPanelIsClosedToRegularUsersAndAnonymous() throws Exception {
		var regular = new TestAuthHelper(mockMvc, objectMapper).authenticate("metrics_regular");

		mockMvc.perform(get("/admin/monitoring/users").header("X-Session-Token", regular.token()))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/monitoring/users"))
			.andExpect(status().isUnauthorized());
	}
}