package com.socialcoisa.rede.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
	void usersPanelIsClosedToRegularUsersAndAnonymous() throws Exception {
		var regular = new TestAuthHelper(mockMvc, objectMapper).authenticate("metrics_regular");

		mockMvc.perform(get("/admin/monitoring/users").header("X-Session-Token", regular.token()))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/monitoring/users"))
			.andExpect(status().isUnauthorized());
	}
}