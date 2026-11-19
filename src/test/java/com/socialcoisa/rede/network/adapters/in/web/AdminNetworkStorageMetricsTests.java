package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Operações por segundo e latência no painel do Storage (§17, V366) — a rota e o banco reais.
 *
 * <p><b>Antes:</b> o §17 pedia os dois indicadores e o painel não tinha nenhum, porque nada contava
 * operação. <b>Agora:</b> a série é gravada a cada operação do catálogo e esta rota a lê. O que estes
 * testes fixam, no banco:</p>
 *
 * <ul>
 * <li>a rota lê a série gravada e devolve taxa e média <b>ponderadas</b> — 10 ops de 10 ms mais 1 de
 * 1000 ms dão 100 ms, nunca 505;</li>
 * <li>sem operação na janela, a resposta é 0 ops/s com 0 segundos cobertos, e não um número inventado;</li>
 * <li>{@code CLIENT} e {@code SERVER} aparecem separados no relatório;</li>
 * <li>janela fora do intervalo é recusada (400) em vez de truncada — quem pediu 30 dias e recebeu 1
 * acha que a instalação parou;</li>
 * <li>sem sessão de admin a rota não abre.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminNetworkStorageMetricsTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_storage_operation_metrics");
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_metrics_admin_" + System.nanoTime() + "_" + UUID.randomUUID());
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	/** Grava uma linha como o serviço grava: o minuto em que a operação caiu. */
	private void linha(Instant quando, String operacao, String resultado, long reqCount, long totalMs, long maxMs) {
		jdbc.update("""
			insert into network_storage_operation_metrics (bucket_at, operation, outcome, req_count, total_ms, max_ms)
			values (?, ?, ?, ?, ?, ?)
			""", Timestamp.from(quando.truncatedTo(ChronoUnit.MINUTES)), operacao, resultado, reqCount, totalMs, maxMs);
	}

	@Test
	void a_rota_devolve_a_media_ponderada_e_a_taxa_da_janela() throws Exception {
		String token = adminToken();
		Instant agora = Instant.now();
		linha(agora.minusSeconds(60), "GET", "OK", 10, 100, 10);
		linha(agora.minusSeconds(120), "GET", "OK", 1, 1000, 1000);
		linha(agora.minusSeconds(60), "PUT", "CLIENT", 2, 10, 5);
		linha(agora.minusSeconds(60), "GET", "SERVER", 1, 3000, 3000);

		mockMvc.perform(get("/admin/network/storage/operations").param("minutes", "15")
				.header("X-Session-Token", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.operations").value(14))
			// (100 + 1000 + 10 + 3000) / 14 = 293.571 ms — a média de TODAS as operações da janela.
			.andExpect(jsonPath("$.averageMs").value(293.571))
			.andExpect(jsonPath("$.maxMs").value(3000))
			.andExpect(jsonPath("$.byOperation.GET").value(12))
			.andExpect(jsonPath("$.byOperation.PUT").value(2))
			.andExpect(jsonPath("$.byOutcome.CLIENT").value(2))
			.andExpect(jsonPath("$.byOutcome.SERVER").value(1))
			.andExpect(jsonPath("$.seconds").value(900));
	}

	@Test
	void sem_operacao_na_janela_a_resposta_e_zero_e_nao_inventada() throws Exception {
		String token = adminToken();

		mockMvc.perform(get("/admin/network/storage/operations").param("minutes", "15")
				.header("X-Session-Token", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.operations").value(0))
			.andExpect(jsonPath("$.opsPerSecond").value(0.0))
			.andExpect(jsonPath("$.averageMs").value(0.0))
			.andExpect(jsonPath("$.seconds").value(0));
	}

	@Test
	void operacao_fora_da_janela_pedida_nao_entra_no_relatorio() throws Exception {
		String token = adminToken();
		// 90 minutos atrás: dentro de um dia, fora de 60 minutos. O filtro é o da rota, no banco.
		linha(Instant.now().minusSeconds(90 * 60), "PUT", "OK", 50, 50, 1);
		linha(Instant.now().minusSeconds(60), "GET", "OK", 1, 10, 10);

		mockMvc.perform(get("/admin/network/storage/operations").param("minutes", "60")
				.header("X-Session-Token", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.operations").value(1))
			.andExpect(jsonPath("$.byOperation.PUT").doesNotExist());
	}

	@Test
	void janela_acima_de_um_dia_e_recusada_e_nao_truncada() throws Exception {
		String token = adminToken();

		mockMvc.perform(get("/admin/network/storage/operations").param("minutes", "10000")
				.header("X-Session-Token", token))
			.andExpect(status().isBadRequest());
	}

	@Test
	void janela_zero_e_recusada() throws Exception {
		String token = adminToken();

		mockMvc.perform(get("/admin/network/storage/operations").param("minutes", "0")
				.header("X-Session-Token", token))
			.andExpect(status().isBadRequest());
	}

	@Test
	void sem_sessao_de_admin_a_rota_nao_abre() throws Exception {
		int status = mockMvc.perform(get("/admin/network/storage/operations"))
			.andReturn().getResponse().getStatus();
		assertThat(status).isIn(401, 403);
	}
}